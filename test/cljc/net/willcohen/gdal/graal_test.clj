;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.graal-test
  "Tests that only the GraalVM backend can run.
   They cover the MEMFS copy of a host path, and the helpers with more than
   one wasm module. The ^:graal tag keeps them out of bb test:ffi."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [net.willcohen.gdal.gdal :as gdal]
            [net.willcohen.gdal.fndefs :as fndefs]
            [net.willcohen.gdal.network :as network]
            [net.willcohen.gdal.support :as support]
            [net.willcohen.gdal.wasm :as gwasm]
            [net.willcohen.native.graal-wasm :as nw]))

(use-fixtures :once support/with-backend)

(defn- memfs-exists? [path]
  (nw/with-graal-lock
    (let [fs (.getMember (nw/get-module gwasm/gdal-context) "FS")]
      (.asBoolean (.getMember (.invokeMember fs "analyzePath" (object-array [path]))
                              "exists")))))

(defn- mirror
  "The MEMFS path of the copy of the host file at `path`."
  [path]
  (#'gwasm/mirror-path (.getAbsoluteFile (io/file path))))

(deftest ^:graal mirror-path
  (is (= "/host/tmp/a.gpkg" (#'gwasm/mirror-path (io/file "/tmp/a.gpkg"))))
  (is (= "/host/C/data/a.gpkg" (#'gwasm/mirror-path (io/file "C:\\data\\a.gpkg")))
      "a Windows drive letter becomes a directory"))

(deftest ^:graal open-copies-a-host-path-into-memfs
  (doseq [path [support/gpkg-path "test/fixtures/tiny.shp" "test/fixtures/tiny.gdb"]]
    (let [ds (support/open-vector path)]
      (is (memfs-exists? (mirror path)) (str "the copy of " path " is in MEMFS while open"))
      (is (not (memfs-exists? (mirror support/tif-path)))
          "an open copies no file of a different dataset, such as tiny.tif")
      (gdal/gdal-close ds)
      (is (not (memfs-exists? (mirror path))) "gdal-close removes the copy"))))

(deftest ^:graal a-failed-copy-leaves-no-file-in-memfs
  (let [path  "test/fixtures/tiny.shp"
        n     (atom 0)
        write @#'gwasm/memfs-write-file!]
    (with-redefs-fn {#'gwasm/memfs-write-file!
                     (fn [module p data]
                       (when (= 2 (swap! n inc))
                         (throw (ex-info "the second file of the family" {})))
                       (write module p data))}
      #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"the second file"
                             (support/open-vector path))))
    (is (empty? (#'gwasm/mirror-files (gwasm/graal-module) (mirror path)))
        "no file of the family stays in MEMFS")))

(deftest ^:graal a-missing-resource-names-its-path
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Not on the class path: net/willcohen/gdal/no-such-file"
                        (#'gwasm/resource "no-such-file"))))

;; An entry holds its module, and with it the polyglot Context.
(deftest ^:graal close-leaves-no-registry-entry
  (gdal/gdal-close (support/open-vector support/gpkg-path))
  (is (empty? @@#'gwasm/host-datasets)))

(deftest ^:graal close-removes-a-written-copy
  (support/call-with-temp-path
   "out.geojson"
   (fn [path]
     (support/translate-vector! support/gpkg-path path ["-f" "GeoJSON"])
     (is (.isFile (io/file path)) "the result is on the host")
     (is (not (memfs-exists? (mirror path))) "gdal-close removes the MEMFS copy"))))

(deftest ^:graal second-open-keeps-an-update
  (support/call-with-temp-path
   "tiny.gpkg"
   (fn [path]
     (io/copy (io/file support/gpkg-path) (io/file path))
     (support/call-with-dataset
      (support/open-dataset path (bit-or fndefs/GDAL_OF_VECTOR fndefs/GDAL_OF_UPDATE))
      (fn [ds1]
        (let [layer (gdal/gdal-dataset-get-layer ds1 0)
              feat  (gdal/ogr-f-create (gdal/ogr-l-get-layer-defn layer))]
          (gdal/ogr-f-set-field-string feat (gdal/ogr-f-get-field-index feat "name") "d")
          (is (zero? (long (gdal/ogr-l-create-feature layer feat))))
          (gdal/ogr-f-destroy feat)
          (gdal/gdal-close (support/open-vector path)))))
     (is (= 4 (count (support/layer-records path))) "the added feature is on the host"))))

(deftest ^:graal a-work-path-stays-in-memfs
  (#'gwasm/fs-call (gwasm/graal-module) "mkdirTree" "/work")
  (let [staged (gdal/stage-files! {"staged.geojson" (slurp support/geojson-path)} nil)
        dst    "/work/translated.geojson"]
    (support/translate-vector! (get staged "staged.geojson") dst ["-f" "GeoJSON"])
    (is (= 3 (count (support/layer-records dst))) "a /work path stays in MEMFS")))

;; A second clj-native library, such as clj-proj, registers its own context.
;; Then clj-native has no single default context for an unbound heap call.
(deftest ^:graal helpers-with-a-second-registered-context
  (let [k ::second-library]
    (nw/create-wasm-context! k)
    (try
      (is (= (first support/tiny-wkbs) (support/wkb-round-trip))
          "feature->wkb and geometry-from-wkb")
      (let [srs (gdal/osr-new-spatial-reference "")]
        (gdal/osr-import-from-epsg srs 3857)
        (is (str/starts-with? (gdal/srs-export-to-wkt srs) "PROJCS[") "srs-export-to-wkt")
        (gdal/osr-destroy-spatial-reference srs))
      (finally (swap! nw/contexts dissoc k)))))

(defn- call-with-pooled-module
  "Load a second GDAL module into a new polyglot Context, and call `f`.
   The module is as a pool worker holds it, and *wasm-context* is bound to it
   during `f`."
  [f]
  (let [pctx (nw/new-polyglot-context!)
        wc   (nw/->WasmContext :net.willcohen.gdal (atom nil))]
    (try
      (gwasm/init-graal-module! {:wasm-context wc :polyglot-context pctx})
      (nw/with-wasm-context wc
        (gdal/gdal-all-register)
        (f))
      (finally (.close pctx)))))

(deftest ^:graal helpers-in-a-pooled-module
  (call-with-pooled-module
   (fn []
     (let [staged (gdal/stage-files! {"pooled.geojson" (slurp support/geojson-path)} nil)]
       (is (= 3 (count (support/layer-records (get staged "pooled.geojson"))))
           "gdal-open-ex finds the file that stage-files! wrote to the bound module"))
     (is (= (first support/tiny-wkbs) (support/wkb-round-trip))
         "geometry-from-wkb writes the WKB into the bound module"))))

;; The callback of a second module is a global of its own Context.
(deftest ^:graal the-http-callback-of-a-pooled-module
  (call-with-pooled-module
   (fn []
     (network/setup-http-callback!)
     (binding [network/*transport* (fn [_] {:status       200
                                            :content-type "application/json"
                                            :body-bytes   (.getBytes ^String (slurp support/geojson-path)
                                                                     "UTF-8")})]
       (is (= ["a" "b" "c"]
              (support/field-values (support/layer-records "GeoJSON:http://mock.test/tiny.geojson")
                                    "name")))))))

;; The test uses a new module, because GDAL makes the GPKG creation options,
;; and the warning, one time for each module.
(deftest ^:graal no-gdal-data-warning-in-a-new-module
  (support/call-with-temp-path
   "empty.gpkg"
   (fn [path]
     (call-with-pooled-module
      (fn []
        (gdal/cpl-error-reset)
        (let [ds (gdal/gdal-create (gdal/gdal-get-driver-by-name "GPKG") path 0 0 0
                                   fndefs/GDT_Unknown nil)]
          (is (some? ds) "GDALCreate made the GPKG")
          (is (not (str/includes? (str (gdal/cpl-get-last-error-msg)) "GDAL_DATA"))
              "no GDAL_DATA warning at the first GPKG create")
          (gdal/gdal-close ds)))))))

;; A wasm call finds its export only when it runs, thus a fndef added without
;; bb build:wasm fails here. FFI looks up each symbol at init!.
(deftest ^:graal the-module-exports-each-fndef
  (nw/with-graal-lock
    (let [module (nw/get-module gwasm/gdal-context)]
      (is (= [] (->> (keys fndefs/fndefs)
                     (map #(str "_" (name %)))
                     (remove #(.hasMember module %))
                     sort))))))
