// A minimal reader for the central directory of a zip file (an APK is one). It does not inflate anything: the central directory
// already holds each entry's name, CRC-32 and sizes, which is all that is needed to say whether two APKs contain the same files.
import { readFileSync } from 'node:fs';

const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_SIGNATURE = 0x02014b50;
const ZIP64_MARKER = 0xffffffff;

/** @returns {{name: string, crc32: number, size: number}[]} sorted by name */
export function readEntries(path) {
  return parseEntries(readFileSync(path));
}

export function parseEntries(buffer) {
  // The end-of-central-directory record is within the last 64 KB + 22 bytes (a comment may follow it).
  let eocd = -1;
  for (let i = buffer.length - 22; i >= Math.max(0, buffer.length - 22 - 0xffff); i--) {
    if (buffer.readUInt32LE(i) === EOCD_SIGNATURE) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error('not a zip file: no end-of-central-directory record');
  const count = buffer.readUInt16LE(eocd + 10);
  let offset = buffer.readUInt32LE(eocd + 16);
  if (offset === ZIP64_MARKER || count === 0xffff) throw new Error('zip64 archives are not supported (an APK of this app is far below 4 GB)');

  const entries = [];
  for (let n = 0; n < count; n++) {
    if (buffer.readUInt32LE(offset) !== CENTRAL_SIGNATURE) throw new Error(`corrupt central directory at entry ${n}`);
    const crc32 = buffer.readUInt32LE(offset + 16);
    const size = buffer.readUInt32LE(offset + 24);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLength);
    entries.push({ name, crc32, size });
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

/**
 * Compares two lists of entries. Differences are the only output: a file present in one APK only, or present in both with other
 * content. Compression level, entry order and timestamps do not matter, and neither does the APK signing block (it is outside the
 * zip entries), which is exactly what differs between an official signed APK and a rebuild signed with someone else's key.
 */
export function diffEntries(a, b) {
  const left = new Map(a.map((e) => [e.name, e]));
  const right = new Map(b.map((e) => [e.name, e]));
  const differences = [];
  for (const [name, entry] of left) {
    const other = right.get(name);
    if (!other) differences.push({ name, kind: 'only in first' });
    else if (other.crc32 !== entry.crc32 || other.size !== entry.size) differences.push({ name, kind: 'content differs' });
  }
  for (const name of right.keys()) if (!left.has(name)) differences.push({ name, kind: 'only in second' });
  return differences;
}
