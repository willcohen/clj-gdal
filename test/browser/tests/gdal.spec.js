// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

import { readdirSync } from 'node:fs';
import { test, expect } from '@playwright/test';
import { openTestPage } from './page.js';

const GDB_FILES = readdirSync(new URL('../../fixtures/tiny.gdb/', import.meta.url));

// NDR, wkbPolygon, 1 ring, 5 points.
const POLYGON_HEADER = [1, 3, 0, 0, 0, 1, 0, 0, 0, 5, 0, 0, 0];

// An import error fails here with its message. Each test starts the pool.
test.beforeEach(async ({ page }) => {
  await openTestPage(page, '/test/browser/test.html');
  await page.evaluate(() => window.gdal.init());
});

// The single-threaded libgdal module must load in a page with no
// cross-origin isolation.
test('the test page is not cross-origin isolated', async ({ page }) => {
  expect(await page.evaluate(() => self.crossOriginIsolated)).toBe(false);
});

// A Web Worker has no window. The loader must take the browser path there too.
test('loadGdalResources reads proj.db in a Web Worker', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const loaderUrl = new URL('./dist/gdal-loader.mjs', location.href).href;
    const src = `import { loadGdalResources } from '${loaderUrl}';
      loadGdalResources().then((r) => postMessage(new TextDecoder().decode(r.projDb.subarray(0, 15))),
                               (e) => postMessage(String(e)));`;
    const url = URL.createObjectURL(new Blob([src], { type: 'text/javascript' }));
    const worker = new Worker(url, { type: 'module' });
    return await new Promise((resolve) => { worker.onmessage = (e) => resolve(e.data); });
  });
  expect(result).toBe('SQLite format 3');
});

test('proj.db gives the EPSG:3857 definition', async ({ page }) => {
  const err = await page.evaluate(async () => {
    const { gdal } = window;
    const srs = await gdal.osr_new_spatial_reference('');
    try {
      return await gdal.osr_import_from_epsg(srs, 3857);
    } finally {
      await gdal.osr_destroy_spatial_reference(srs);
    }
  });
  expect(err).toBe(0);
});

// The names are upper case, because the loader accepts an extension in any
// case, and the caller opens the name that it returns.
test('loadShapeFamilyFromFiles stages a shapefile with its sidecar files', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { gdal, gdalLoader, fixtureFile, readFeatures } = window;
    const blobs = await Promise.all(['shp', 'shx', 'dbf', 'prj'].map(
      async (ext) => new File([await fixtureFile('tiny.' + ext)], 'TINY.' + ext.toUpperCase())));
    const { files, name } = await gdalLoader.loadShapeFamilyFromFiles(blobs);
    await gdal.stage_files_BANG_(files, '/work');
    const records = await readFeatures(`/work/${name}`);
    return {
      name,
      pairs: records.map((r) => `${r.fields.name}|${r.fields.id}`),
      headers: records.map((r) => Array.from(r.wkb.slice(0, 13))),
    };
  });
  expect(result.name).toBe('TINY.SHP');
  expect(result.pairs).toEqual(['a|1', 'b|2', 'c|3']);
  expect(result.headers).toEqual([POLYGON_HEADER, POLYGON_HEADER, POLYGON_HEADER]);
});

test('loadBytesFromBlob stages a GeoTIFF, and read_raster_band reads its pixels', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { gdal, gdalLoader, fndefs, fixtureFile } = window;
    const { files, name } = await gdalLoader.loadBytesFromBlob(await fixtureFile('tiny.tif'));
    const staged = await gdal.stage_files_BANG_(files, '/work-raster');
    const ds = await gdal.gdal_open_ex(staged[name], fndefs.GDAL_OF_RASTER, null, null, null);
    if (!ds) throw new Error('gdal_open_ex returned NULL');
    const pixels = await gdal.read_raster_band(ds, 1);
    const result = {
      size: [await gdal.gdal_get_raster_x_size(ds), await gdal.gdal_get_raster_y_size(ds)],
      bands: await gdal.gdal_get_raster_count(ds),
      arrayType: pixels.constructor.name,
      pixels: Array.from(pixels),
    };
    await gdal.gdal_close(ds);
    return result;
  });
  expect(result.size).toEqual([16, 16]);
  expect(result.bands).toBe(1);
  expect(result.arrayType).toBe('Uint8Array');
  expect(result.pixels).toEqual(Array.from({ length: 256 }, (_, i) => i));
});

test('loadBytesFromBlob stages a GeoPackage', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const { gdal, gdalLoader, fixtureFile, readFeatures } = window;
    const { files, name } = await gdalLoader.loadBytesFromBlob(await fixtureFile('tiny.gpkg'));
    const staged = await gdal.stage_files_BANG_(files, '/work-gpkg');
    const records = await readFeatures(staged[name]);
    return {
      names: records.map((r) => r.fields.name),
      headers: records.map((r) => Array.from(r.wkb.slice(0, 13))),
    };
  });
  expect(result.names).toEqual(['a', 'b', 'c']);
  expect(result.headers).toEqual([POLYGON_HEADER, POLYGON_HEADER, POLYGON_HEADER]);
});

test('loadDirectoryFromFiles stages a File Geodatabase', async ({ page }) => {
  const result = await page.evaluate(async (gdbFiles) => {
    const { gdal, gdalLoader, fixtureFile, readFeatures } = window;
    const blobs = await Promise.all(gdbFiles.map((f) => fixtureFile('tiny.gdb/' + f)));
    const { files } = await gdalLoader.loadDirectoryFromFiles(blobs);
    await gdal.stage_files_BANG_(files, '/work/tiny.gdb');
    const records = await readFeatures('/work/tiny.gdb');
    return {
      fileCount: Object.keys(files).length,
      names: records.map((r) => r.fields.name).sort(),
      types: records.map((r) => r.wkb[1]),
    };
  }, GDB_FILES);
  expect(result.fileCount).toBe(GDB_FILES.length);
  expect(result.names).toEqual(['a', 'b', 'c']);
  // OpenFileGDB keeps each polygon as a MultiPolygon.
  expect(result.types).toEqual([6, 6, 6]);
});

// In a browser, CPLHTTPFetch goes through the synchronous XHR of the
// http-bridge. server.mjs serves the mock FeatureServer at the origin of the
// page. 3 features on 2 pages show that the paging loop of the driver ran.
test('ESRIJSON reads a FeatureServer through the CPLHTTPFetch callback', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const url = 'ESRIJSON:' + window.location.origin +
      '/geoservices-mock/FeatureServer/0/query?where=1%3D1&outFields=*&f=json&orderByFields=OBJECTID+ASC';
    const records = await window.readFeatures(url);
    return records.map((r) => [r.wkb[0], r.wkb[1]]);
  });
  // NDR and wkbPolygon: a one-ring ESRIJSON polygon reads as a Polygon.
  expect(result).toEqual([[1, 3], [1, 3], [1, 3]]);
});

test('GeoJSON reads a URL through the CPLHTTPFetch callback', async ({ page }) => {
  const result = await page.evaluate(async () => {
    const url = 'GeoJSON:' + window.location.origin + '/test/fixtures/tiny.geojson';
    const records = await window.readFeatures(url);
    return records.map((r) => [r.fields.name, Array.from(r.wkb.slice(0, 13))]);
  });
  expect(result).toEqual([['a', POLYGON_HEADER], ['b', POLYGON_HEADER], ['c', POLYGON_HEADER]]);
});

// server.mjs gives the request headers as the fields of one feature.
test('The HEADERS option of GDAL reaches the server', async ({ page }) => {
  const fields = await page.evaluate(async () => {
    await window.gdal.cpl_set_config_option('GDAL_HTTP_HEADERS', 'X-Test: a');
    const records = await window.readFeatures('GeoJSON:' + window.location.origin + '/headers.geojson');
    return records[0].fields;
  });
  expect(fields['x-test']).toBe('a');
  expect(fields.accept).toBe('text/plain, application/json');
});
