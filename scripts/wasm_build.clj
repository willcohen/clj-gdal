;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns wasm-build
  "The wasm build of libgdal.{mjs,wasm}, one module with one thread for the
   browser, Node and GraalVM. A page that loads it needs no COOP or COEP
   headers."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cljs-build :refer [pkg-dir]]
            [clojure.string :as str]
            [lib-build]
            [net.willcohen.gdal.fndefs :as fndefs]
            [net.willcohen.native.build :as nb]
            [sources]))

;; fndefs does not bind these fns. The clj-native runtime and the handler
;; call _malloc and _free. network.clj and the handler call
;; gdal_setup_http_callback of src/c/gdal_http_stub.c.
(def ^:private runtime-exports ["_malloc" "_free" "_gdal_setup_http_callback"])

;; The wasm exports each fn that the cljs and GraalVM surfaces bind, and no
;; other GDAL fn.
(def ^:private exported-functions
  (into (vec (sort (map #(str "_" (name %)) (keys fndefs/fndefs))))
        runtime-exports))

;; The worker methods of handler-classification and the GraalVM heap fns of
;; clj-native use these.
(def ^:private exported-runtime-methods
  ["ccall" "UTF8ToString" "stringToUTF8" "lengthBytesUTF8" "FS" "ENV"
   "getValue" "setValue"
   "HEAP8" "HEAPU8" "HEAP16" "HEAPU16" "HEAP32" "HEAPU32" "HEAPF32" "HEAPF64"])

(defn- gdal-build-dir []
  (str (sources/dir :gdal) "/build-wasm"))

(defn- gdal-archive []
  (str (gdal-build-dir) "/libgdal.a"))

(defn- build-gdal!
  "Build GDAL as a wasm static archive in its source tree."
  []
  (let [opts {:type       :wasm
              :src-dir    (sources/dir :gdal)
              :build-dir  (gdal-build-dir)
              :clean?     false
              :cmake-args (-> (lib-build/gdal-cmake-args :wasm)
                              (into (lib-build/cmake-flags :wasm "-fexceptions" "-D_LARGEFILE64_SOURCE=1"
                                                           "-D_FILE_OFFSET_BITS=64"))
                              (into [;; sqlite has no mutex, because it has
                                     ;; SQLITE_THREADSAFE=0.
                                     "-DACCEPT_MISSING_SQLITE3_MUTEX_ALLOC=ON"
                                     "-DBUILD_SHARED_LIBS=OFF"
                                     ;; The emscripten toolchain sets the prefix
                                     ;; to its cache dir, and GDAL compiles its
                                     ;; data dirs from the prefix. The native
                                     ;; builds use /usr/local.
                                     "-DCMAKE_INSTALL_PREFIX=/usr/local"]))}]
    (nb/build-once! (gdal-archive) opts #(nb/build-cmake-library opts))))

(defn- emscripten-cache []
  (str/trim (:out (p/shell {:out :string} "em-config" "CACHE"))))

(defn- link!
  "Compile the C HTTP stub, and link libgdal.{mjs,wasm} into the npm package
   dir, where the jar and the npm tarball take them."
  []
  (let [out-dir "target/wasm-link"
        stub    (str out-dir "/gdal_http_stub.o")]
    (fs/create-dirs out-dir)
    (nb/emcc-compile {:source       "src/c/gdal_http_stub.c"
                      :output       stub
                      :include-dirs [(str (gdal-build-dir) "/port")
                                     (str (sources/dir :gdal) "/port")]})
    (nb/emcc-link {:build-dir                "."
                   :output-name              (str out-dir "/libgdal.mjs")
                   :objects                  (concat [stub (gdal-archive)]
                                                     (lib-build/dep-archives :wasm))
                   :exported-functions       exported-functions
                   :exported-runtime-methods exported-runtime-methods
                   :module-name              "createGdalModule"
                   :environment              "node,web,worker"
                   :optimization             "-O3"
                   :force-filesystem?        true})
    (lib-build/check-no-build-paths! (str out-dir "/libgdal.wasm")
                                     [(str (fs/cwd)) (emscripten-cache)])
    (doseq [f ["libgdal.mjs" "libgdal.wasm"]]
      (fs/copy (fs/path out-dir f) (fs/path pkg-dir f) {:replace-existing true}))
    ;; The layout of proj.db must agree with the PROJ code in the module.
    (fs/copy (lib-build/proj-db :wasm) (fs/path pkg-dir "proj.db") {:replace-existing true})))

(defn build!
  "Build the wasm deps, GDAL and the module."
  []
  (lib-build/build-deps! :wasm)
  (build-gdal!)
  (link!))
