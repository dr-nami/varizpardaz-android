#!/usr/bin/env node
// Prints the SHA-256 of a release APK and the `app_update` block for the server's config (docs/device-api.md section 5),
// so the number the phones check is the number of the file that was published, not one typed by hand.
//
//   node tools/release/update-entry.mjs path/to/listener-1.2.0-3.apk --url https://example.com/listener-1.2.0-3.apk
//        [--version-code 3] [--min-version-code 1]
//
// --version-code defaults to versionCode in version.properties. Nothing is sent anywhere; paste the JSON where the config is edited.
import { createHash } from 'node:crypto';
import { readFileSync, statSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

function parseArgs(argv) {
  const options = {};
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) options[argv[i].slice(2)] = argv[++i];
    else positional.push(argv[i]);
  }
  return { options, positional };
}

export function versionCodeFromProperties(text) {
  const match = /^\s*versionCode\s*=\s*(\d+)\s*$/m.exec(text);
  return match ? Number(match[1]) : null;
}

export function buildEntry({ sha256, versionCode, minVersionCode, url }) {
  if (!Number.isInteger(versionCode) || versionCode < 1) throw new Error('version code must be a positive integer');
  if (!Number.isInteger(minVersionCode) || minVersionCode < 1) throw new Error('min version code must be a positive integer');
  if (minVersionCode > versionCode) throw new Error('min version code is above the version code: phones would be told to update to something that does not satisfy it');
  if (!/^https:\/\/[^/\s@]+\/\S*$/.test(url ?? '')) throw new Error('--url must be an https link to the APK (no credentials in it)');
  return { latest_version_code: versionCode, min_version_code: minVersionCode, url, sha256 };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { options, positional } = parseArgs(process.argv.slice(2));
  const [apk] = positional;
  if (!apk) {
    console.error('usage: node update-entry.mjs <apk> --url <https link> [--version-code N] [--min-version-code N]');
    process.exit(2);
  }
  try {
    const bytes = readFileSync(apk);
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const fromFile = versionCodeFromProperties(readFileSync(resolve(here, '../../version.properties'), 'utf8'));
    const entry = buildEntry({
      sha256,
      versionCode: Number(options['version-code'] ?? fromFile),
      minVersionCode: Number(options['min-version-code'] ?? 1),
      url: options.url,
    });
    console.error(`${apk}: ${statSync(apk).size} bytes, sha256 ${sha256}`);
    console.log(JSON.stringify({ app_update: entry }, null, 2));
  } catch (error) {
    console.error(`error: ${error.message}`);
    process.exit(2);
  }
}
