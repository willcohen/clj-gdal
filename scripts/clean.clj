;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns clean
  "Delete each file that a build task or a test task writes.
   node_modules stays."
  (:require [babashka.fs :as fs]
            [cljs-build :refer [pkg-dir]]
            [release]))

(def ^:private generated
  ["target"
   ;; dtype-next writes the classes of the FFI library here on each FFI run.
   "classes"
   "test/cljc/dist"
   "test/browser/dist"
   "test/browser/test-results"])

;; Only these files go, because the package dir also holds the sources.
(def ^:private package-outputs
  (concat ["gdal.mjs" "fndefs.mjs" "wasm.mjs" "macros.mjs" "gdal-handler.mjs"
           "libgdal.mjs" "libgdal.wasm" "proj.db"]
          release/npm-docs))

(defn clean! []
  (doseq [path (concat generated
                       (map str (fs/list-dir "resources" "{darwin,linux}-*"))
                       (map #(str pkg-dir "/" %) package-outputs)
                       (map str (fs/glob "test/cljc/net" "**_test.mjs")))
          :when (fs/exists? path)]
    (println "Deleting" path)
    (fs/delete-tree path)))
