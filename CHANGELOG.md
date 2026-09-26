# Change Log
This file documents notable changes to this project. This change log uses the
conventions of [keepachangelog.com](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.0.1] - 2026-10-01

### Added
- The first release: `net.willcohen/gdal` on Clojars and `gdal-wasm` on npm,
  with GDAL 3.11.5 and PROJ 9.9.0, on clj-native 0.0.3
  (`net.willcohen/native` and `ffi-wasm`)
- One public function for each GDAL and OGR C function in `fndefs`, which
  checks its arg count on each platform
- Helper functions for features, geometries, fields, rasters, metadata,
  `/vsimem` files, spatial references, coordinate transforms and the GDAL
  apps. Refer to the API section of README.md
- A native `libgdal.dylib` for macOS Apple Silicon and `libgdal.so` for Linux
  amd64 and arm64 (glibc and musl) through the FFM API of JDK 25, and
  `libgdal.wasm` on GraalVM for each other JVM platform
- `gdal-wasm` for Node.js 22 or later and the browser
- HTTP reads for the GeoJSON, ESRIJSON and TopoJSON drivers
- 18 drivers, among them GTiff, COG, GPKG, ESRI Shapefile, OpenFileGDB,
  GeoJSON, ESRIJSON and FlatGeobuf
- clj-kondo hooks in the jar, and THIRD-PARTY-NOTICES in the jar and in the
  npm package

The limits of this release are in the Known limitations section of
README.md.

[Unreleased]: https://github.com/willcohen/clj-gdal/compare/0.0.1...HEAD
[0.0.1]: https://github.com/willcohen/clj-gdal/releases/tag/0.0.1
