# clj-gdal

[![NPM Version](https://img.shields.io/npm/v/gdal-wasm)](https://www.npmjs.com/package/gdal-wasm)
[![Clojars Version](https://img.shields.io/clojars/v/net.willcohen%2Fgdal)](https://clojars.org/net.willcohen/gdal)

clj-gdal gives [GDAL](https://gdal.org/) to the JVM and to JavaScript. The
Clojars package `net.willcohen/gdal` has a Clojure API. The npm package
`gdal-wasm` has the same API over a WebAssembly build of GDAL.

clj-gdal is new. A release can still make breaking changes.

```clojure
net.willcohen/gdal {:mvn/version "0.0.1"}
```

```bash
npm install gdal-wasm
```

The JVM must be JDK 25 or later, and Node.js must be 22 or later. A Maven
profile adds the native library jar of the host: `darwin-aarch64`,
`linux-amd64` or `linux-aarch64` of `net.willcohen/gdal-native`. Gradle does not read the profile, and
an uberjar gets only the jar of the host that builds it: add each jar
yourself, for example `net.willcohen:gdal-native:0.0.1:linux-amd64`.

## Usage

### Clojure

```clojure
(require '[net.willcohen.gdal.gdal :as gdal]
         '[net.willcohen.gdal.fndefs :as fndefs])

(gdal/init!)

(let [ds    (gdal/gdal-open-ex "example.gpkg" fndefs/GDAL_OF_VECTOR nil nil nil)
      layer (gdal/gdal-dataset-get-layer ds 0)]
  (println (gdal/layer-feature-count layer))
  (println (map :fields (gdal/read-vector-features! layer)))
  (gdal/gdal-close ds))
```

Add `--enable-native-access=ALL-UNNAMED` to the JVM options.

### JavaScript

```js
import * as gdal from 'gdal-wasm';
import { GDAL_OF_VECTOR } from 'gdal-wasm/fndefs';

await gdal.init();
const ds = await gdal.open_from_disk_BANG_('example.gpkg', GDAL_OF_VECTOR);
const layer = await gdal.gdal_dataset_get_layer(ds, 0);
console.log(Number(await gdal.layer_feature_count(layer)));
const records = await gdal.read_vector_features_BANG_(layer);
await gdal.gdal_close(ds);
await gdal.shutdown(); // The worker pool keeps Node.js alive until this call.
```

In a browser, copy the bytes into the worker, and open the staged path:

```js
import { loadBytesFromBlob } from 'gdal-wasm/gdal-loader';

const { files, name } = await loadBytesFromBlob(file);
const staged = await gdal.stage_files_BANG_(files);
const ds = await gdal.gdal_open_ex(staged[name], GDAL_OF_VECTOR, null, null, null);
```

`loadShapeFamilyFromFiles` reads a shapefile and its sidecar files.
`loadDirectoryFromFiles` reads a File Geodatabase: stage its files in a
directory with the name of the dataset, for example `/work/x.gdb`.

A page needs no COOP or COEP headers. A page without a bundler maps each
dependency in an import map, and a server on a different origin must send
CORS headers:

```html
<script type="importmap">
{
  "imports": {
    "gdal-wasm": "https://cdn.jsdelivr.net/npm/gdal-wasm@0.0.1/gdal.mjs",
    "gdal-wasm/fndefs": "https://cdn.jsdelivr.net/npm/gdal-wasm@0.0.1/fndefs.mjs",
    "gdal-wasm/gdal-loader": "https://cdn.jsdelivr.net/npm/gdal-wasm@0.0.1/gdal-loader.mjs",
    "ffi-wasm": "https://cdn.jsdelivr.net/npm/ffi-wasm@0.0.3/dist/ffi-wasm.mjs",
    "ffi-wasm/handler-env": "https://cdn.jsdelivr.net/npm/ffi-wasm@0.0.3/dist/handler_env.mjs",
    "worker-router": "https://cdn.jsdelivr.net/npm/@wcohen/worker-router@0.0.2/dist/index.mjs",
    "worker-router/worker-bootstrap": "https://cdn.jsdelivr.net/npm/@wcohen/worker-router@0.0.2/dist/worker-bootstrap.mjs",
    "comlink": "https://cdn.jsdelivr.net/npm/comlink@4.4.2/dist/esm/comlink.mjs",
    "resource-tracker": "https://cdn.jsdelivr.net/npm/resource-tracker@0.0.1/resource.mjs",
    "squint-cljs/": "https://cdn.jsdelivr.net/npm/squint-cljs@0.14.210/"
  }
}
</script>
```

## API

Each C function in `net.willcohen.gdal.fndefs` has a function with a
kebab-case name: `GDALOpenEx` is `gdal-open-ex`. Most functions that take a
buffer have a helper in their place. Each helper has a docstring:

- Features: `read-vector-features!`, `layer-feature-count`, `layer-extent`,
  `field-date-time`, `field-binary`, `set-field-binary!`
- Geometries: `geometry-from-wkb`, `geometry-from-wkt`, `geometry->wkb`,
  `geometry->iso-wkb`, `geometry->wkt`, `geometry->json`, `feature->wkb`,
  `feature->iso-wkb`, `transform-points`, `srs-export-to-wkt`,
  `srs-export-to-projjson`
- Rasters: `read-raster-band`, `write-raster-band!`, `get-geo-transform`,
  `set-geo-transform!`, `band-nodata`, `band-block-size`, `build-overviews!`
- Apps: `translate-vector!`, `translate-raster!`, `warp-raster!`,
  `raster-info`, `vector-info`
- Other: `metadata`, `file-list`, `build-csl-options`, `stage-files!`,
  `read-vsimem-file`, `read-vsi-dir`, `force-graal!` and `graal?` (JVM),
  `handler-spec` (JavaScript), `open-from-disk!` (Node.js)

Some buffer functions, for example the OGR list fields, have no helper yet.
Close each dataset with `gdal-close`. When GDAL fails, a helper throws an
`ex-info` with the GDAL error message.

In JavaScript, each function returns a Promise, and its name is the squint
name, for example `geometry__GT_wkb`. A 64-bit integer is a `BigInt`. Read
the data of an error with the `ex_data` of the squint-cljs of gdal-wasm. To
share a worker pool with proj-wasm, register the spec of `handler_spec()`
with `ffi.register_handler_BANG_(registry, 'compute', 'net.willcohen.gdal',
spec)`, and give the pool to `init` as `pool`. `shutdown()` does not stop
that pool.

## Files and HTTP

On FFI, GDAL opens host paths. On GraalVM, an open copies the host file and
its sidecar files into MEMFS below `/host`, and `gdal-close` copies a written
dataset back. In JavaScript, GDAL opens MEMFS and `/vsimem` paths only.

The GeoJSON, ESRIJSON and TopoJSON drivers read HTTP URLs. Set
`GDAL_HTTP_HEADERS` to add a header. On the JVM, bind
`net.willcohen.gdal.network/*transport*` to change a request.

## Platforms

The JVM uses a native libgdal on macOS arm64 and on Linux amd64 and arm64
(glibc 2.28 or later, or musl), and `libgdal.wasm` on GraalVM on each other
platform. JavaScript runs `libgdal.wasm` in a worker. Each has GDAL 3.11.5,
PROJ 9.9.0 and 18 drivers: VRT, GTiff, COG, PNG, JPEG, MEM, GNMFile,
GNMDatabase, ESRI Shapefile, GML, GeoJSON, GeoJSONSeq, ESRIJSON, TopoJSON,
GPKG, SQLite, OpenFileGDB and FlatGeobuf. On a JDK other than GraalVM
25.3.4.1, the wasm module runs in an interpreter, more slowly.

## Known limitations

- No GEOS, no libcurl (`/vsicurl/`), no XML parser (GML is write only) and
  no GDAL data files.
- On GraalVM, GDAL sees only the dataset file and its sidecar files. A VRT
  cannot read its sources, and a host file in a `/vsizip/` path or after a
  driver prefix does not open. A read-only close drops a new `.ovr` or
  `.aux.xml`.
- On Node.js, an HTTP response over 50 MiB or 35 s fails.

## License

clj-gdal is under the MIT License. Refer to [LICENSE](LICENSE). The
binaries contain code and data from other projects, for example GDAL, PROJ,
SQLite and the EPSG dataset of IOGP, and no LGPL or GPL library apart from
small glibc wrappers with a link exception. Refer to
[THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES).

This software is based in part on the work of the Independent JPEG Group.
