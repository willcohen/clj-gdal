// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

// Usage: node test.mjs <tiny.gpkg> [package dir]
//
// bb test:npm runs this script in a new project that installs the packed
// gdal-wasm tarball. Because the script imports the package by name, it can
// use only the files that the tarball contains. bb test:node gives the
// package dir of the source tree.
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const [gpkg, pkgDir] = process.argv.slice(2);
const specifier = (name, file) =>
  pkgDir ? pathToFileURL(resolve(pkgDir, file)).href : name;
const gdal = await import(specifier('gdal-wasm', 'gdal.mjs'));
const fndefs = await import(specifier('gdal-wasm/fndefs', 'fndefs.mjs'));

// Node must exit after gdal.shutdown(). Because of unref(), this timer fires
// only if a different handle keeps the process alive.
setTimeout(() => {
  console.error('FAIL: Node did not exit within 60 s');
  process.exit(1);
}, 60000).unref();

function check(ok, message) {
  if (!ok) {
    console.error('FAIL: ' + message);
    process.exit(1);
  }
  console.log('ok: ' + message);
}

await gdal.init();
const ds = await gdal.open_from_disk_BANG_(gpkg, fndefs.GDAL_OF_VECTOR);
check(!!ds, 'open tiny.gpkg');
const layer = await gdal.gdal_dataset_get_layer(ds, 0);
const records = await gdal.read_vector_features_BANG_(layer);
check(records.map((r) => r.fields.name).sort().join() === 'a,b,c', '3 features, a, b and c');
check(records.every((r) => r.wkb.length === 93 && r.wkb[0] === 1), 'little-endian polygon WKB');
await gdal.gdal_close(ds);

// GDAL finds EPSG:3857 in the proj.db that the tarball contains.
const srs = await gdal.osr_new_spatial_reference('');
check((await gdal.osr_import_from_epsg(srs, 3857)) === 0, 'EPSG:3857 from proj.db');
await gdal.osr_destroy_spatial_reference(srs);

await gdal.shutdown();
