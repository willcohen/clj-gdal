// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

// cdn.html loads gdal-wasm and its dependencies from a second origin, as a
// page does from a CDN. A module worker does not use the importmap of the
// page, thus the worker must load each of its modules by URL.

import { test, expect } from '@playwright/test';
import { openTestPage } from './page.js';

// playwright.config.js starts the second origin on the port after baseURL.
function cdnOrigin(baseURL) {
  const url = new URL(baseURL);
  url.port = String(Number(url.port) + 1);
  return url.origin;
}

test('gdal-wasm reads a GeoPackage from a second origin with no specifier rewrite', async ({ page, context, baseURL }) => {
  const cdn = cdnOrigin(baseURL);
  const assetOrigins = new Set();
  context.on('request', (req) => {
    const url = new URL(req.url());
    if (/\.(mjs|js|wasm|db)$/.test(url.pathname)) assetOrigins.add(url.origin);
  });
  await openTestPage(page, `/test/browser/cdn.html?cdn=${encodeURIComponent(cdn)}`);

  const names = await page.evaluate(async () => {
    const { gdal, fndefs } = window;
    const resp = await fetch('/test/fixtures/tiny.gpkg');
    const staged = await gdal.stage_files_BANG_(
      { 'tiny.gpkg': new Uint8Array(await resp.arrayBuffer()) }, '/work-cdn');
    const ds = await gdal.gdal_open_ex(staged['tiny.gpkg'], fndefs.GDAL_OF_VECTOR, null, null, null);
    try {
      const records = await gdal.read_vector_features_BANG_(await gdal.gdal_dataset_get_layer(ds, 0));
      return records.map((r) => r.fields.name);
    } finally {
      await gdal.gdal_close(ds);
    }
  });
  expect(names).toEqual(['a', 'b', 'c']);
  expect([...assetOrigins]).toEqual([cdn]);
});
