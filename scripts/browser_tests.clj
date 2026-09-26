;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns browser-tests
  "The Playwright tests, on a copy of the package in test/browser/dist. The
   importmap of test.html resolves the other packages from node_modules,
   because no package bundles another. cdn.html loads the package dir
   unchanged from a second origin."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cljs-build :refer [pkg-dir]]
            [net.willcohen.native.build :as nb]
            [release]))

(def ^:private dist "test/browser/dist")

(defn- stage-dist!
  "Make test/browser/dist again: the package files, with each ffi-wasm
   specifier as a relative URL, and the ffi-wasm bundles in dist/ffi-wasm,
   because init-pool! looks for handler.mjs next to ffi-wasm.mjs."
  []
  (let [ffi-wasm-dist (fs/path pkg-dir "node_modules" "ffi-wasm" "dist")
        native-dist   (fs/path dist "ffi-wasm")]
    (fs/delete-tree dist)
    (fs/create-dirs native-dist)
    (doseq [n (remove (set release/npm-docs) (release/package-files))
            :let [dst (fs/path dist n)]]
      (fs/copy (fs/path pkg-dir n) dst)
      (when (= "mjs" (fs/extension n))
        (nb/rewrite-import-specifiers! dst (nb/export-specifier-rewrites "./ffi-wasm/"))))
    (doseq [n nb/bundle-files]
      (fs/copy (fs/path ffi-wasm-dist n) (fs/path native-dist n)))))

(defn run-all! []
  (stage-dist!)
  (p/shell {:dir "test/browser"} "npm" "install" "--no-audit" "--no-fund")
  (p/shell {:dir "test/browser"} "npx" "playwright" "install" "--with-deps" "chromium")
  (p/shell {:dir "test/browser"} "npm" "test"))
