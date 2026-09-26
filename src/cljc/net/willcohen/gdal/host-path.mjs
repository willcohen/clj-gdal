// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

// The Node file reads of open-from-disk!.

import { isNode } from 'ffi-wasm/handler-env';

// {files, name, directory, mirror}: each top-level file of the dir
// `hostPath`, or each file next to the file `hostPath` that
// inFamily(name, entry) accepts. `mirror` is the absolute dir as a MEMFS path:
// C:\a\b gives /C/a/b, as on the JVM.
export async function readHostPath(hostPath, inFamily) {
  if (!isNode) {
    throw new Error('readHostPath works only on Node. In a browser, use the load* functions of gdal-wasm/gdal-loader.');
  }
  const { readFile, readdir, stat } = await import('node:fs/promises');
  const { basename, dirname, join, resolve } = await import('node:path');
  const name = basename(hostPath);
  const directory = (await stat(hostPath)).isDirectory();
  const dir = resolve(directory ? hostPath : dirname(hostPath));
  const files = {};
  for (const entry of await readdir(dir)) {
    const file = join(dir, entry);
    // stat follows a symlink, as java.io.File.isFile does on the JVM.
    if ((directory || inFamily(name, entry)) && (await stat(file).catch(() => null))?.isFile()) {
      files[entry] = new Uint8Array(await readFile(file));
    }
  }
  const mirror = dir.replaceAll('\\', '/').replace(/^([A-Za-z]):/, '/$1');
  return { files, name, directory, mirror };
}
