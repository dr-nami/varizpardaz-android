#!/usr/bin/env node
// Development mock of the paygate device API. See README.md.
//
//   node tools/mock-server/server.mjs
//   MOCK_FILES_DIR=./apks node tools/mock-server/server.mjs   (serves /mock/files/<name>; POST /mock/app-update offers one as the update)
//   MOCK_PORT=8787 MOCK_PUBLIC_URL=http://192.168.1.20:8787 MOCK_STATE_FILE=.state/mock.json node tools/mock-server/server.mjs
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createMockServer } from './lib/mock.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.MOCK_PORT ?? 8787);
const host = process.env.MOCK_HOST ?? '0.0.0.0';
const publicUrl = process.env.MOCK_PUBLIC_URL ?? `http://localhost:${port}`;
const stateFile = process.env.MOCK_STATE_FILE ? resolve(process.env.MOCK_STATE_FILE) : null;
if (stateFile) mkdirSync(dirname(stateFile), { recursive: true });

const mock = createMockServer({
  configPath: resolve(here, process.env.MOCK_CONFIG ?? '../../fixtures/config.example.json'),
  publicUrl,
  stateFile,
  filesDir: process.env.MOCK_FILES_DIR ? resolve(process.env.MOCK_FILES_DIR) : null,
  // MOCK_MERCHANT_NAME= (empty) leaves merchant_name out of the config, like a server that does not send it yet.
  ...(process.env.MOCK_MERCHANT_NAME !== undefined ? { merchantName: process.env.MOCK_MERCHANT_NAME } : {}),
});

await mock.listen(port, host);
const { code, link } = mock.newPairingCode();
console.log(`mock server listening on ${host}:${port}  (public url ${publicUrl})`);
console.log(`pairing code (valid 10 minutes): ${code}`);
console.log(`pairing link: ${link}`);
console.log('more codes: curl -X POST ' + publicUrl + '/mock/pairing-codes   state: ' + publicUrl + '/mock/state');
if (!stateFile) console.log('state is in memory only: restarting the server means pairing again (set MOCK_STATE_FILE to keep it)');
