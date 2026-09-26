;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.wkb-test
  "Check the WKB of GeoJSON polygons on the JVM and in Node.
   support/polygon-wkb encodes the expected WKB, because GDAL keeps the vertex
   order of a GeoJSON ring. The Node process wkb_runner.mjs reads the same
   GeoJSON with gdal-wasm."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [net.willcohen.gdal.support :as support])
  (:import [java.io BufferedReader BufferedWriter]
           [java.util Base64]
           [java.util.concurrent TimeUnit]))

(def ^:private ^:dynamic *runner* nil)

(defn- read-line-within
  "Read one line from `reader`. Throw when none comes in 60 s, as when the
   wasm start of the runner stops."
  [^BufferedReader reader]
  (let [line (deref (future (.readLine reader)) 60000 ::timeout)]
    (cond
      (= ::timeout line) (throw (ex-info "The Node runner did not answer in 60 s" {}))
      (nil? line)        (throw (ex-info "The Node runner closed its stdout" {}))
      :else              line)))

(defn- with-runner
  "Start wkb_runner.mjs, and call `f` after its ready line.
   The stderr of the runner goes to the stderr of this JVM."
  [f]
  (let [proc   (-> (ProcessBuilder. ["node" "test/cljc/net/willcohen/gdal/wkb_runner.mjs"])
                   (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                   (.start))
        writer (io/writer (.getOutputStream proc))
        reader (io/reader (.getInputStream proc))]
    (try
      (read-line-within reader)
      (binding [*runner* {:writer writer :reader reader}] (f))
      (finally
        ;; At the end of stdin, the runner calls gdal.shutdown() and exits.
        (.close ^BufferedWriter writer)
        (when-not (.waitFor proc 30 TimeUnit/SECONDS)
          (.destroyForcibly proc))))))

(use-fixtures :once support/with-backend with-runner)

(defn- node-wkbs [geojson]
  (let [{:keys [^BufferedWriter writer reader]} *runner*]
    (.write writer (json/write-str {:geojson geojson}))
    (.newLine writer)
    (.flush writer)
    (let [reply (json/read-str (read-line-within reader) :key-fn keyword)]
      (when-not (:ok reply)
        (throw (ex-info "The Node runner failed" reply)))
      (mapv #(vec (.decode (Base64/getDecoder) ^String %)) (:wkbs reply)))))

(defn- jvm-wkbs [geojson]
  (support/call-with-temp-dir
   "gdal-wkb"
   (fn [dir]
     (let [path (support/path-in dir "in.geojson")]
       (spit path geojson)
       (mapv (comp vec :wkb) (support/layer-records path))))))

(defn- feature-collection [rings]
  (json/write-str {:type "FeatureCollection"
                   :features (for [ring rings]
                               {:type "Feature"
                                :properties {}
                                :geometry {:type "Polygon" :coordinates [ring]}})}))

(defn- expected-wkbs [rings]
  (mapv (comp vec support/polygon-wkb) rings))

(deftest tiny-geojson
  (let [geojson (slurp support/geojson-path)]
    (is (= support/tiny-wkbs (jvm-wkbs geojson)) "JVM")
    (is (= support/tiny-wkbs (node-wkbs geojson)) "Node")))

;; Integer corners keep the JSON text of each coordinate exact. The limits
;; keep each corner in the range of EPSG:4326.
(def ^:private gen-ring
  (gen/let [x0   (gen/choose -179 169)
            y0   (gen/choose -89 79)
            side (gen/choose 1 10)]
    (support/square-ring x0 y0 side)))

(defspec generated-polygons 50
  (prop/for-all [rings (gen/vector gen-ring 1 4)]
    (let [geojson  (feature-collection rings)
          expected (expected-wkbs rings)]
      (= expected (jvm-wkbs geojson) (node-wkbs geojson)))))
