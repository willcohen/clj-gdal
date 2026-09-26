// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

import { defineConfig } from '@playwright/test';

// The default is 8082, because other local test servers often use 8080 and
// 8081. With reuseExistingServer, a shared port makes these tests load the
// pages of that other server.
const port = Number(process.env.PORT || 8082);

// A second origin, as a CDN is, for cdn.spec.js. It sends CORS headers,
// because a module from another origin loads only with them.
const cdnPort = port + 1;

const server = (p, env = {}) => ({
  command: 'node server.mjs',
  url: `http://127.0.0.1:${p}/test/browser/test.html`,
  env: { PORT: String(p), ...env },
  reuseExistingServer: !process.env.CI,
  timeout: 30000,
});

export default defineConfig({
  testDir: './tests',
  timeout: 60000,
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    trace: 'retain-on-failure',
  },
  webServer: [server(port), server(cdnPort, { CORS: '1' })],
});
