// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

// The test files import gdal.mjs by the same URL, thus this is one module.
import * as gdal from '../../../../../src/cljc/net/willcohen/gdal/gdal.mjs';
import { GDAL_OF_VECTOR, wkbNDR, wkbPolygon } from '../../../../../src/cljc/net/willcohen/gdal/fndefs.mjs';

export const gpkg_path = 'test/fixtures/tiny.gpkg';
export const geojson_path = 'test/fixtures/tiny.geojson';
export const tif_path = 'test/fixtures/tiny.tif';

// The message of the rejection of the promise `p`, or "no error".
export const rejectionMessage = (p) => p.then(() => 'no error', (e) => e.message);

// The records of layer 0 of the vector dataset at `path`.
export async function layerRecords(path) {
  const ds = await gdal.gdal_open_ex(path, GDAL_OF_VECTOR, null, null, null);
  if (!ds) throw new Error(`gdal_open_ex returned NULL for ${path}`);
  try {
    return await gdal.read_vector_features_BANG_(await gdal.gdal_dataset_get_layer(ds, 0));
  } finally {
    await gdal.gdal_close(ds);
  }
}

// The values of field `name` in `records`, in order.
export const fieldValues = (records, name) => records.map((r) => r.fields[name]);

// The little-endian WKB of the square of `side` at (x0, y0), counterclockwise.
export function squareWkb(x0, y0, side) {
  const ring = [[x0, y0], [x0 + side, y0], [x0 + side, y0 + side], [x0, y0 + side], [x0, y0]];
  const dv = new DataView(new ArrayBuffer(13 + 16 * ring.length));
  dv.setUint8(0, wkbNDR);
  dv.setUint32(1, wkbPolygon, true);
  dv.setUint32(5, 1, true);
  dv.setUint32(9, ring.length, true);
  ring.forEach(([x, y], i) => {
    dv.setFloat64(13 + 16 * i, x, true);
    dv.setFloat64(21 + 16 * i, y, true);
  });
  return new Uint8Array(dv.buffer);
}
