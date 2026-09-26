// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

// bootstrap-graal-module! of clj-native calls load with {wasmBinary, projDb},
// each a Uint8Array. Relative imports only, because a polyglot Context
// resolves no package name.

async function load(options) {
  const { default: createGdalModule } = await import('./libgdal.mjs');

  const moduleArgs = {
    wasmBinary: options.wasmBinary.buffer,
    // GraalVM JS has no URL global, and without locateFile, Emscripten calls
    // `new URL(name, import.meta.url)`, also when wasmBinary is set.
    locateFile: (path) => path,
  };

  // The preRun of gdal-handler-overrides.mjs.
  moduleArgs.preRun = [function () {
    moduleArgs.FS.mkdir('/proj');
    moduleArgs.FS.writeFile('/proj/proj.db', options.projDb);
    moduleArgs.ENV.PROJ_DATA = '/proj';
    moduleArgs.FS.mkdir('/gdal');
    moduleArgs.ENV.GDAL_DATA = '/gdal';
  }];

  return createGdalModule(moduleArgs);
}

export { load };
