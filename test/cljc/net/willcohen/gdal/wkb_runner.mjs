// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

// wkb_test.clj starts this process. Each stdin line is
// {"geojson": "<FeatureCollection>"}. Each stdout line is
// {"ok": true, "wkbs": ["<base64>", ...]} or {"ok": false, "error": "..."}.
// The first stdout line is {"ready": true}. When stdin closes, the process
// stops the worker pool and exits.

import { createInterface } from 'node:readline';
import * as gdal from '../../../../../src/cljc/net/willcohen/gdal/gdal.mjs';
import { layerRecords } from './support.mjs';

let requests = 0;

async function readWkbs(geojson) {
  const name = `wkb-${requests++}.geojson`;
  const staged = await gdal.stage_files_BANG_(
    { [name]: new TextEncoder().encode(geojson) }, '/work');
  const records = await layerRecords(staged[name]);
  return records.map((r) => Buffer.from(r.wkb).toString('base64'));
}

const write = (obj) => process.stdout.write(JSON.stringify(obj) + '\n');

await gdal.init();

// readline does not wait for an async handler. The chain keeps the answers
// in the order of the requests.
let chain = Promise.resolve();
createInterface({ input: process.stdin })
  .on('line', (line) => {
    chain = chain
      .then(() => readWkbs(JSON.parse(line).geojson))
      .then((wkbs) => write({ ok: true, wkbs }),
            (e) => write({ ok: false, error: String(e?.message ?? e) }));
  })
  .on('close', () => chain.then(() => gdal.shutdown()));

write({ ready: true });
