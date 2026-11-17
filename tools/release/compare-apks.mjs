#!/usr/bin/env node
// Says whether two APKs contain the same files, ignoring the signature. Use it to check that an APK you built from source is the
// APK that was published (docs/release.md). Exit code 0: same files; 1: different; 2: bad usage or unreadable input.
//
//   node tools/release/compare-apks.mjs official.apk mine.apk
import { diffEntries, readEntries } from './lib/zip.mjs';

const [first, second] = process.argv.slice(2);
if (!first || !second) {
  console.error('usage: node compare-apks.mjs <first.apk> <second.apk>');
  process.exit(2);
}

let differences;
try {
  const a = readEntries(first);
  const b = readEntries(second);
  differences = diffEntries(a, b);
  console.log(`${first}: ${a.length} files\n${second}: ${b.length} files`);
} catch (error) {
  console.error(`cannot read: ${error.message}`);
  process.exit(2);
}

if (differences.length === 0) {
  console.log('SAME: every file is identical (the signature is not compared).');
} else {
  console.log(`DIFFERENT: ${differences.length} file(s) differ`);
  for (const d of differences.slice(0, 50)) console.log(`  ${d.kind}: ${d.name}`);
  if (differences.length > 50) console.log(`  ... and ${differences.length - 50} more`);
  process.exit(1);
}
