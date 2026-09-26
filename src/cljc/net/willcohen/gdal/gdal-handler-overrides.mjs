// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

// The worker side of the GDAL handler. ffi-wasm/handler comes as ctx.ffi,
// because a module worker ignores the importmap of the page.

let ffi = null;
let module = null;
let httpSyncFetch = null;

// bb build:wasm and a bundle put libgdal.mjs next to this file.
const WASM_DIR_CANDIDATES = [['.']];

// The layout of gdal_http_stub.c, in a buffer from _malloc, because the stub
// frees it. 0 for a failed _malloc.
function packHttpResponse(mod, status, contentType, bodyBytes) {
  const ctype = new TextEncoder().encode(contentType || '');
  const total = 12 + ctype.length + bodyBytes.length;
  const ptr = mod._malloc(total);
  if (!ptr) return 0;
  // Read HEAPU8 after _malloc, because a memory growth replaces the view.
  const heap = mod.HEAPU8;
  const view = new DataView(heap.buffer, ptr, total);
  view.setInt32(0, status, true);
  view.setInt32(4, ctype.length, true);
  heap.set(ctype, ptr + 8);
  view.setInt32(8 + ctype.length, bodyBytes.length, true);
  heap.set(bodyBytes, ptr + 12 + ctype.length);
  return ptr;
}

function toBytes(value, name) {
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  if (Array.isArray(value)) return new Uint8Array(value);
  throw new Error(`stage_files: ${name} is not a typed array, an ArrayBuffer or an Array`);
}

// As the JVM stage-files!. GDAL takes the VSIMalloc buffer only when
// VSIFileFromMemBuffer succeeds.
function stageVsimem(mod, files, dir) {
  const trimmed = dir.endsWith('/') ? dir.slice(0, -1) : dir;
  const out = {};
  for (const [name, value] of Object.entries(files)) {
    const bytes = toBytes(value, name);
    const path = `${trimmed}/${name}`;
    mod._CPLErrorReset();
    let buf = 0;
    if (bytes.length > 0) {
      buf = mod._VSIMalloc(bytes.length);
      if (!buf) throw new Error(`VSIMalloc failed for ${path}`);
      mod.HEAPU8.set(bytes, buf);
    }
    const fp = mod.ccall('VSIFileFromMemBuffer', 'number', ['string', 'number', 'number', 'number'],
                         [path, buf, BigInt(bytes.length), 1]);
    if (!fp) {
      if (buf) mod._VSIFree(buf);
      throw new Error(`VSIFileFromMemBuffer failed for ${path}`);
    }
    mod._VSIFCloseL(fp);
    out[name] = path;
  }
  return out;
}

// The headers of the CRLF lines of gdal_http_stub.c, as header-map in
// network.clj.
function requestHeaders(headerLines) {
  const headers = {};
  for (const line of headerLines.split('\r\n')) {
    const i = line.indexOf(':');
    if (i > 0) headers[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return headers;
}

// A slot is a buffer arg of a ccall (slot-extras in gdal.cljc). The ccall
// allocates, fills, reads and frees each slot in one worker message, because
// each message costs about 30 µs. It resets the GDAL error first, because a
// helper checks the error of each call with a slot.
//
// A slot has `argIdx` and `size`, and one of:
//   bytes                      copied into the slot before the call
//   bytes, indirect            as bytes, and the arg is a char** to the slot
//   read: 'values', view, n    n values of the heap view, for example HEAPF64
//   read: 'ptr'                one pointer
//   read: 'vsi-string'         a char* that GDAL allocated, read and VSIFree'd
function readSlot(mod, slot, ptr) {
  switch (slot.read) {
    case 'values': {
      const heap = mod[slot.view];
      const start = ptr / heap.BYTES_PER_ELEMENT;
      return heap.slice(start, start + slot.n);
    }
    case 'ptr':
      return mod.HEAPU32[ptr >> 2];
    case 'vsi-string': {
      const str = mod.HEAPU32[ptr >> 2];
      if (!str) return '';
      try { return mod.UTF8ToString(str); } finally { mod._VSIFree(str); }
    }
    default:
      return undefined;
  }
}

function readStringList(mod, ptr) {
  const strs = [];
  for (let p = ptr; p && mod.HEAPU32[p >> 2]; p += 4) strs.push(mod.UTF8ToString(mod.HEAPU32[p >> 2]));
  return strs;
}

// The :read-result of a fn-def (fndefs.cljc) says how to read the C result:
//   owned-string        a char* for the caller, read and VSIFree'd, or null
//   string-list         a char** that GDAL keeps, read into an array
//   owned-string-list   a char** for the caller, read and CSLDestroy'd
//   bytes               a GByte* that GDAL keeps, of the length in the first
//                       out slot (1 or 2 32-bit words), copied, or null
function readResult(mod, kind, ptr, out) {
  switch (kind) {
    case 'owned-string':
      if (!ptr) return null;
      try { return mod.UTF8ToString(ptr); } finally { mod._VSIFree(ptr); }
    case 'string-list':
      return readStringList(mod, ptr);
    case 'owned-string-list':
      try { return readStringList(mod, ptr); } finally { if (ptr) mod._CSLDestroy(ptr); }
    case 'bytes': {
      if (!ptr) return null;
      const words = out[0];
      if (!words) throw new Error('gdal-handler: a bytes result needs a length out slot');
      const n = words[0] + (words.length > 1 ? words[1] * 2 ** 32 : 0);
      return mod.HEAPU8.slice(ptr, ptr + n);
    }
    default:
      throw new Error(`gdal-handler: unknown read-result ${kind}`);
  }
}

// Returns the C result, or {result, out} with one element of `out` for each
// slot when a slot reads a value. A :read-result replaces the C result.
function ccall(fnName, returnType, argTypes, args, extra) {
  const mod = module;
  const slots = extra?.slots ?? [];
  const kind = extra?.result;
  if (slots.length === 0 && !kind) return mod.ccall(fnName, returnType, argTypes, args);
  const ptrs = [];
  const alloc = (size) => {
    const ptr = mod._malloc(size);
    if (!ptr) throw new Error(`gdal-handler: malloc of ${size} bytes failed`);
    ptrs.push(ptr);
    return ptr;
  };
  try {
    const callArgs = args.slice();
    const slotPtrs = slots.map((slot) => {
      const size = Math.max(1, slot.size);
      const ptr = alloc(size);
      if (slot.bytes) mod.HEAPU8.set(slot.bytes, ptr);
      else mod.HEAPU8.fill(0, ptr, ptr + size);
      if (slot.indirect) {
        const cell = alloc(4);
        mod.HEAPU32[cell >> 2] = ptr;
        callArgs[slot.argIdx] = cell;
      } else {
        callArgs[slot.argIdx] = ptr;
      }
      return ptr;
    });
    mod._CPLErrorReset();
    const cResult = mod.ccall(fnName, returnType, argTypes, callArgs);
    const out = slots.map((slot, i) => readSlot(mod, slot, slotPtrs[i]));
    const result = kind ? readResult(mod, kind, cResult, out) : cResult;
    return out.every((v) => v === undefined) ? result : { result, out };
  } finally {
    for (const ptr of ptrs) mod._free(ptr);
  }
}

// ogr_core.h
const OFTInteger = 0;
const OFTReal = 2;
const OFTInteger64 = 12;
const wkbNDR = 1;

function readField(mod, feat, i, type) {
  switch (type) {
    case OFTInteger: return mod._OGR_F_GetFieldAsInteger(feat, i);
    case OFTReal: return mod._OGR_F_GetFieldAsDouble(feat, i);
    case OFTInteger64: return mod._OGR_F_GetFieldAsInteger64(feat, i);
    default: return mod.UTF8ToString(mod._OGR_F_GetFieldAsString(feat, i));
  }
}

// The little-endian WKB of the geometry of `feat`, null for no geometry, or
// {err} for a failed export.
function readWkb(mod, feat) {
  const geom = mod._OGR_F_GetGeometryRef(feat);
  if (!geom) return null;
  const n = mod._OGR_G_WkbSize(geom);
  const ptr = mod._malloc(Math.max(1, n));
  if (!ptr) throw new Error(`gdal-handler: malloc of ${n} bytes failed`);
  try {
    mod._CPLErrorReset();
    const err = mod._OGR_G_ExportToWkb(geom, wkbNDR, ptr);
    return err ? { err } : mod.HEAPU8.slice(ptr, ptr + n);
  } finally {
    mod._free(ptr);
  }
}

// Each feature of `layer` as {fid, fields, wkb}, for read-vector-features!
// of gdal.cljc. A failed export stops the read, and `failure` gives its
// OGRErr and the FID, because the feature is then destroyed.
function readFeatures(mod, layer) {
  const defn = mod._OGR_L_GetLayerDefn(layer);
  const schema = [];
  for (let i = 0, n = mod._OGR_FD_GetFieldCount(defn); i < n; i++) {
    const fd = mod._OGR_FD_GetFieldDefn(defn, i);
    schema.push([mod.UTF8ToString(mod._OGR_Fld_GetNameRef(fd)), mod._OGR_Fld_GetType(fd)]);
  }
  mod._OGR_L_ResetReading(layer);
  const features = [];
  for (let feat = mod._OGR_L_GetNextFeature(layer); feat; feat = mod._OGR_L_GetNextFeature(layer)) {
    try {
      const fields = {};
      schema.forEach(([name, type], i) => {
        fields[name] = mod._OGR_F_IsFieldSetAndNotNull(feat, i) === 1
          ? readField(mod, feat, i, type)
          : null;
      });
      const wkb = readWkb(mod, feat);
      if (wkb?.err) return { features, failure: { err: wkb.err, fid: mod._OGR_F_GetFID(feat) } };
      features.push({ fid: mod._OGR_F_GetFID(feat), fields, wkb });
    } finally {
      mod._OGR_F_Destroy(feat);
    }
  }
  return { features };
}

export async function init(initArgs, ctx) {
  ffi = ctx.ffi;
  const args = initArgs ?? {};
  if (!args.dbBytes) {
    throw new Error('gdal-handler: missing required initArgs.dbBytes');
  }
  const { factory: createGdalModule, locateFile } = await ffi.loadEmscriptenModule(
    import.meta.url, { name: 'libgdal.mjs', candidates: WASM_DIR_CANDIDATES });
  const dbBytes = args.dbBytes instanceof Uint8Array
    ? args.dbBytes
    : new Uint8Array(args.dbBytes);
  // Before C code runs, because PROJ caches its search paths at the first
  // PJ_CONTEXT. An empty GDAL_DATA stops a warning at each GPKG create.
  // gdal-graal-loader.mjs has the same preRun.
  const opts = { locateFile };
  opts.preRun = [function () {
    opts.FS.mkdir('/proj');
    opts.FS.writeFile('/proj/proj.db', dbBytes);
    opts.ENV.PROJ_DATA = '/proj';
    opts.FS.mkdir('/gdal');
    opts.ENV.GDAL_DATA = '/gdal';
  }];
  module = await createGdalModule(opts);
  ctx.attachEmscriptenModule(module);
  module.ccall('GDALAllRegister', null, [], []);

  // The C stub calls this synchronously. The http-bridge of ffi-wasm waits
  // for a fetch worker on Node, and sends a synchronous XHR in a browser.
  httpSyncFetch = await ffi.createSyncFetch();
  globalThis.__gdal_http_fetch = (urlPtr, headersPtr) => {
    try {
      const url = module.UTF8ToString(urlPtr);
      const headers = requestHeaders(headersPtr ? module.UTF8ToString(headersPtr) : '');
      const resp = httpSyncFetch(url, { method: 'GET', headers });
      if (resp.status === 0) return 0;
      const contentType = resp.headers['content-type'] ?? 'application/json';
      return packHttpResponse(module, resp.status, contentType, resp.bodyBytes);
    } catch (e) {
      console.error('gdal-handler: the CPLHTTPFetch callback failed', e);
      return 0;  // The C stub gives GDAL the error "HTTP fetch failed" for 0.
    }
  };
  module.ccall('gdal_setup_http_callback', 'number', [], []);
}

export const methods = (ffiNs) => ({
  ccall,
  stage_files: (files, dir) =>
    (dir.startsWith('/vsimem/') ? stageVsimem : ffiNs.stageFiles)(module, files, dir),
  read_features: (layer) => readFeatures(module, layer),
});

// worker-router calls destroy when the pool stops, to stop the fetch worker
// of init. The handler has no `shutdown` method, because each call releases
// one reference, and a second release can stop the fetch worker of another
// library.
export function destroy() {
  return ffi?.moduleDestroy();
}
