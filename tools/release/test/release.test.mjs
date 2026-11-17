import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { after, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { crc32, deflateRawSync } from 'node:zlib';
import { buildEntry, versionCodeFromProperties } from '../update-entry.mjs';
import { diffEntries, parseEntries } from '../lib/zip.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const dir = mkdtempSync(join(tmpdir(), 'release-tools-'));
after(() => rmSync(dir, { recursive: true, force: true }));

/** A zip writer good enough for tests: entries are stored or deflated, with a trailing signing-block-like blob before the central directory. */
function makeZip(files, { compress = false, signingBlock = null } = {}) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, content] of files) {
    const nameBytes = Buffer.from(name, 'utf8');
    const data = Buffer.from(content);
    const body = compress ? deflateRawSync(data) : data;
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(compress ? 8 : 0, 8);
    local.writeUInt32LE(crc32(data), 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    locals.push(local, nameBytes, body);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(compress ? 8 : 0, 10);
    central.writeUInt32LE(crc32(data), 16);
    central.writeUInt32LE(body.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBytes);
    offset += 30 + nameBytes.length + body.length;
  }
  const block = signingBlock ? Buffer.from(signingBlock) : Buffer.alloc(0);
  const centralBytes = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(offset + block.length, 16);
  return Buffer.concat([...locals, block, centralBytes, end]);
}

const files = [
  ['classes.dex', 'dex bytes'.repeat(50)],
  ['AndroidManifest.xml', '<manifest/>'],
  ['res/values/strings.xml', 'text'],
];

describe('zip entries', () => {
  test('lists every entry by name with its crc and size, sorted', () => {
    const entries = parseEntries(makeZip(files));
    assert.deepEqual(entries.map((e) => e.name), ['AndroidManifest.xml', 'classes.dex', 'res/values/strings.xml']);
    assert.equal(entries.find((e) => e.name === 'AndroidManifest.xml').size, '<manifest/>'.length);
  });

  test('rejects something that is not a zip', () => {
    assert.throws(() => parseEntries(Buffer.from('this is not a zip file at all, just text')), /not a zip/);
  });

  test('the same files compare equal whatever the compression, order or signature block', () => {
    const a = parseEntries(makeZip(files));
    const b = parseEntries(makeZip([...files].reverse(), { compress: true, signingBlock: 'APK Sig Block 42 ...a different key...' }));
    assert.deepEqual(diffEntries(a, b), []);
  });

  test('a changed, missing or extra file is reported by name', () => {
    const base = parseEntries(makeZip(files));
    const changed = parseEntries(makeZip([['classes.dex', 'other'], files[1], files[2]]));
    assert.deepEqual(diffEntries(base, changed), [{ name: 'classes.dex', kind: 'content differs' }]);
    const missing = parseEntries(makeZip(files.slice(1)));
    assert.deepEqual(diffEntries(base, missing), [{ name: 'classes.dex', kind: 'only in first' }]);
    assert.deepEqual(diffEntries(missing, base), [{ name: 'classes.dex', kind: 'only in second' }]);
  });
});

describe('compare-apks command', () => {
  const run = (...args) => spawnSync(process.execPath, [resolve(here, '../compare-apks.mjs'), ...args], { encoding: 'utf8' });

  test('exit code 0 for the same files, 1 for different ones, 2 for unreadable input', () => {
    writeFileSync(join(dir, 'a.apk'), makeZip(files));
    writeFileSync(join(dir, 'b.apk'), makeZip(files, { compress: true, signingBlock: 'x'.repeat(100) }));
    writeFileSync(join(dir, 'c.apk'), makeZip([['classes.dex', 'changed'], ...files.slice(1)]));
    writeFileSync(join(dir, 'd.apk'), 'nope');
    assert.equal(run(join(dir, 'a.apk'), join(dir, 'b.apk')).status, 0);
    const different = run(join(dir, 'a.apk'), join(dir, 'c.apk'));
    assert.equal(different.status, 1);
    assert.match(different.stdout, /content differs: classes\.dex/);
    assert.equal(run(join(dir, 'a.apk'), join(dir, 'd.apk')).status, 2);
    assert.equal(run(join(dir, 'a.apk')).status, 2);
  });
});

describe('update entry', () => {
  const sha256 = 'ab'.repeat(32);
  const ok = { sha256, versionCode: 3, minVersionCode: 1, url: 'https://updates.example.com/listener-3.apk' };

  test('builds the app_update block of the config', () => {
    assert.deepEqual(buildEntry(ok), {
      latest_version_code: 3, min_version_code: 1, url: 'https://updates.example.com/listener-3.apk', sha256,
    });
  });

  test('refuses what a phone would refuse', () => {
    assert.throws(() => buildEntry({ ...ok, url: 'http://updates.example.com/a.apk' }), /https/);
    assert.throws(() => buildEntry({ ...ok, url: 'https://user:pw@updates.example.com/a.apk' }), /https/);
    assert.throws(() => buildEntry({ ...ok, url: undefined }), /https/);
    assert.throws(() => buildEntry({ ...ok, minVersionCode: 4 }), /above the version code/);
    assert.throws(() => buildEntry({ ...ok, versionCode: 0 }), /positive integer/);
    assert.throws(() => buildEntry({ ...ok, versionCode: Number.NaN }), /positive integer/);
  });

  test('reads the version code from version.properties text', () => {
    assert.equal(versionCodeFromProperties('# c\nversionCode=12\nversionName=1.2.0\n'), 12);
    assert.equal(versionCodeFromProperties('versionName=1.0'), null);
  });
});
