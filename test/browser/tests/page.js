// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

import { expect } from '@playwright/test';

// Open the test page at `path`, and wait until it imported gdal-wasm. The
// log of a failed request names the file, because an import error names only
// the top module.
export async function openTestPage(page, path) {
  page.on('console', (msg) => {
    if (msg.type() === 'error' || msg.type() === 'warning') {
      console.log(`[browser ${msg.type()}]`, msg.text());
    }
  });
  page.on('pageerror', (err) => console.log('[pageerror]', err.message));
  page.context().on('requestfailed', (req) =>
    console.log('[requestfailed]', req.url(), req.failure()?.errorText));
  page.context().on('response', (resp) => {
    if (resp.status() >= 400) console.log(`[response ${resp.status()}]`, resp.url());
  });
  await page.goto(path);
  await page.waitForFunction(() => window.__gdalImport !== undefined, null, { timeout: 30000 });
  expect(await page.evaluate(() => window.__gdalImport)).toBe('ok');
}
