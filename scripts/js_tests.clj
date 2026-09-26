;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns js-tests
  "Compile the cljs.test mirrors in test/cljc, and run them under Node.
   They run against the squint output of the source tree."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cljs-build :refer [pkg-dir]]
            [net.willcohen.native.build :as nb]))

(def ^:private dir "test/cljc/net/willcohen/gdal")

(defn- sources []
  (sort (map (comp str fs/file-name) (fs/glob dir "*_test.cljc"))))

(defn compile! []
  (nb/stage-test-deps!
   {:native-dist (str (fs/path pkg-dir "node_modules/ffi-wasm/dist"))})
  ;; The compiled tests and gdal.mjs must come from the same squint.
  (let [squint (str (fs/absolutize (fs/path pkg-dir "node_modules/.bin/squint")))]
    (doseq [source (sources)]
      (nb/squint-compile! dir source {:binary squint}))))

(defn- run-node!
  "Run the Node script `file`, and throw when it fails or runs for more
   than 180 s. Node 26 can stop in process.exit while it joins a V8
   platform thread."
  [file]
  (let [proc   (p/process {:inherit true} "node" file)
        result (deref proc 180000 ::timeout)]
    (cond
      (= ::timeout result)
      (do (p/destroy-tree proc)
          (throw (ex-info (str file " did not exit in 180 s") {:file file})))

      (not (zero? (:exit result)))
      (throw (ex-info (str file " failed") {:file file :exit (:exit result)})))))

(defn run-all! []
  (p/shell {:dir "test/cljc"} "npm" "install" "--no-audit" "--no-fund")
  (doseq [source (sources)]
    (run-node! (str (fs/path dir (str (fs/strip-ext source) ".mjs"))))))
