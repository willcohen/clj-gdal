// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

import { isNode } from 'ffi-wasm/handler-env';

// The proj.db bytes for the init args of each worker. isNode, because a Web
// Worker has no window.
export async function loadGdalResources() {
  const url = new URL('./proj.db', import.meta.url);
  if (isNode) {
    const { readFile } = await import('node:fs/promises');
    return { projDb: new Uint8Array(await readFile(url)) };
  }
  const resp = await fetch(url);
  if (!resp.ok) {
    throw new Error(`gdal-loader: fetch ${url} returned ${resp.status}`);
  }
  return { projDb: new Uint8Array(await resp.arrayBuffer()) };
}

const blobBytes = async (blob) => new Uint8Array(await blob.arrayBuffer());

const extOf = (name) => name.slice(name.lastIndexOf('.') + 1).toLowerCase();

// [[name, blob], ...] for a non-empty array of File inputs. Each input must
// have a `.name`: wrap a Blob as new File([blob], name).
function namedBlobs(fnName, blobs) {
  if (!Array.isArray(blobs) || blobs.length === 0) {
    throw new Error(`${fnName}: blobs must be a non-empty array`);
  }
  return blobs.map((b) => {
    const name = b?.name;
    if (typeof name !== 'string' || name.length === 0) {
      throw new Error(`${fnName}: a blob has no name (give File objects)`);
    }
    return [name, b];
  });
}

async function filesOf(entries) {
  const files = {};
  for (const [name, b] of entries) files[name] = await blobBytes(b);
  return files;
}

// {files, name} for stage_files_BANG_ from the File inputs of a shapefile.
// It needs .shp, .shx and .dbf, takes .prj and .cpg, and ignores each other
// extension, in any case. `name` is the .shp file.
export async function loadShapeFamilyFromFiles(blobs) {
  const entries = namedBlobs('loadShapeFamilyFromFiles', blobs)
    .filter(([name]) => ['shp', 'shx', 'dbf', 'prj', 'cpg'].includes(extOf(name)));
  for (const ext of ['shp', 'shx', 'dbf']) {
    if (!entries.some(([name]) => extOf(name) === ext)) {
      throw new Error('loadShapeFamilyFromFiles: required sibling .' + ext + ' missing from blobs');
    }
  }
  const [shp] = entries.find(([name]) => extOf(name) === 'shp');
  return { files: await filesOf(entries), name: shp };
}

// {files, name} for stage_files_BANG_ from one File, or from a Blob and
// `name`.
export async function loadBytesFromBlob(blob, { name } = {}) {
  const basename = name ?? blob?.name;
  if (typeof basename !== 'string' || basename.length === 0) {
    throw new Error('loadBytesFromBlob: missing name (pass `name` for raw Blob)');
  }
  return { files: { [basename]: await blobBytes(blob) }, name: basename };
}

// {files} for stage_files_BANG_ from the File inputs of one flat dir, as a
// webkitdirectory input gives them. Stage them in a dir with the name of the
// dataset, for example /work/tiny.gdb.
export async function loadDirectoryFromFiles(blobs) {
  return { files: await filesOf(namedBlobs('loadDirectoryFromFiles', blobs)) };
}
