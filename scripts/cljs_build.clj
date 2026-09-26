;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns cljs-build
  "The squint compile of the gdal-wasm package, and its generated handler."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [native-dev]
            [net.willcohen.gdal.fndefs :as fndefs]
            [net.willcohen.native.build :as nb]
            [net.willcohen.native.gen-handler :as gh]))

(def pkg-dir "src/cljc/net/willcohen/gdal")

;; macros.cljc before gdal.cljc, because the macro reads fndefs as it expands.
(def ^:private sources ["fndefs.cljc" "macros.cljc" "wasm.cljc" "gdal.cljc"])

;; squint emits the import of the clj-native macros as a path into the
;; node_modules of this src tree, which a consumer of the tarball does not
;; have. The import is dead code, but it must resolve.
(def ^:private macros-import-rewrite
  {"./node_modules/ffi-wasm/src/cljc/net/willcohen/native/macros.mjs" "ffi-wasm"})

;; :busy-methods sets only the trace fields, because the handler runtime
;; serializes each call.
(def ^:private handler-classification
  {:overrides-import-path "./gdal-handler-overrides.mjs"
   :exposed-methods       [:ccall :stage_files :read_features]
   :busy-methods          [:ccall :stage_files :read_features]
   :fingerprint-fields    [:dbBytes]
   :fingerprint-prefix    "gdal"})

(defn- gen-handler! []
  (gh/write-handler! fndefs/fndefs handler-classification
                     (str pkg-dir "/gdal-handler.mjs")))

(defn compile! []
  (gen-handler!)
  (p/shell {:dir pkg-dir} "npm" "install" "--no-audit" "--no-fund")
  (native-dev/install-ffi-wasm! pkg-dir)
  (nb/ensure-consumer-squint-edn! pkg-dir)
  (doseq [source sources]
    (nb/squint-compile! pkg-dir source))
  (nb/rewrite-import-specifiers! (fs/path pkg-dir "macros.mjs") macros-import-rewrite))
