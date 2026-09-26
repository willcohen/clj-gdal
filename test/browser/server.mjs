// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

// Serves the repository root for the Playwright tests, without COOP or COEP
// headers. /geoservices-mock/ is a mock FeatureServer with the same 3
// features on 2 pages as the fixture-file-for of geoservices_test.cljc.
// With CORS=1, each response lets any origin read it, as a CDN does.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const rootDir = fileURLToPath(new URL('../..', import.meta.url));
const fixtureDir = join(rootDir, 'test/fixtures/geoservices');
const PORT = Number(process.env.PORT || 8082);
const CORS_HEADERS = process.env.CORS === '1' ? { 'Access-Control-Allow-Origin': '*' } : {};

const MIME_TYPES = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.mjs': 'text/javascript',
  '.json': 'application/json',
  '.wasm': 'application/wasm',
};

function geoservicesFixtureFor(reqUrl) {
  return reqUrl.includes('resultOffset=2') ? 'page2.json' : 'page1.json';
}

// A GeoJSON FeatureCollection with one feature, whose properties are the
// request headers. A read of /headers.geojson shows the headers of GDAL.
function headersGeoJson(headers) {
  return JSON.stringify({
    type: 'FeatureCollection',
    features: [{ type: 'Feature', properties: headers, geometry: { type: 'Point', coordinates: [0, 0] } }],
  });
}

createServer(async (req, res) => {
  const { pathname } = new URL(req.url, 'http://127.0.0.1');
  if (pathname === '/headers.geojson') {
    res.writeHead(200, { ...CORS_HEADERS, 'Content-Type': 'application/geo+json' });
    res.end(headersGeoJson(req.headers));
    return;
  }
  const file = pathname.startsWith('/geoservices-mock/')
    ? join(fixtureDir, geoservicesFixtureFor(req.url))
    : join(rootDir, decodeURIComponent(pathname));
  try {
    const content = await readFile(file);
    res.writeHead(200, { ...CORS_HEADERS, 'Content-Type': MIME_TYPES[extname(file)] ?? 'application/octet-stream' });
    res.end(content);
  } catch (err) {
    res.writeHead(err.code === 'ENOENT' || err.code === 'EISDIR' ? 404 : 500, CORS_HEADERS);
    res.end(`${err.code}: ${pathname}`);
  }
}).listen(PORT, '127.0.0.1', () => {
  console.log(`Serving ${rootDir} at http://127.0.0.1:${PORT}/`);
});
