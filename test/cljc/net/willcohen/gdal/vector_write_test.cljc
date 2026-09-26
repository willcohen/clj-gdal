;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.vector-write-test
  (:require
   #?@(:clj  [[clojure.test :refer [deftest is use-fixtures]]
              [clojure.java.io :as io]
              [clojure.string :as str]
              [net.willcohen.gdal.gdal :as gdal]
              [net.willcohen.gdal.fndefs :as fndefs]
              [net.willcohen.gdal.support :as support]]
       :cljs [[cljs.test :refer [deftest is]]
              ["../../../../../src/cljc/net/willcohen/gdal/gdal.mjs" :as gdal]
              ["../../../../../src/cljc/net/willcohen/gdal/fndefs.mjs" :as fndefs]
              ["./support.mjs" :as support]
              ["../../../dist/test_runner.mjs"
               :refer [run_tests_and_exit_BANG_]]])))

(def ^:private field-specs
  [["name" fndefs/OFTString nil]
   ["area" fndefs/OFTReal nil]
   ["id64" fndefs/OFTInteger64 nil]
   ["flag" fndefs/OFTInteger fndefs/OFSTBoolean]])

(def ^:private layer-names ["one" "two"])

;; [i x0 name]: square i has side 10 at (x0, x0), FID 5000000000 + i and
;; id64 3000000000 + i.
(def ^:private squares [[0 0.0 "sq-0"] [1 20.0 ""]])

#?(:clj
   (do
     (use-fixtures :once support/with-backend)

     (defn- write-squares!
       "Write a GPKG of the two squares at `path`."
       [path]
       (let [drv (gdal/gdal-get-driver-by-name "GPKG")
             srs (gdal/osr-new-spatial-reference "")]
         (is (some? drv) "GPKG driver present")
         (is (zero? (long (gdal/osr-import-from-epsg srs 4326))))
         (support/call-with-dataset
          (gdal/gdal-create drv path 0 0 0 fndefs/GDT_Unknown nil)
          (fn [ds]
            (is (some? ds) "GDALCreate produced a vector dataset")
            (let [layer (gdal/gdal-dataset-create-layer ds "squares" srs fndefs/wkbPolygon nil)]
              (is (some? layer) "CreateLayer produced a layer")
              (doseq [[nm ty sub] field-specs]
                (let [fld (gdal/ogr-fld-create nm ty)]
                  (when sub (gdal/ogr-fld-set-sub-type fld sub))
                  (is (zero? (long (gdal/ogr-l-create-field layer fld 1)))
                      (str "CreateField " nm))
                  (gdal/ogr-fld-destroy fld)))
              (is (zero? (long (gdal/gdal-dataset-start-transaction ds 1))))
              (doseq [[i x0 nm] squares]
                (let [feat (gdal/ogr-f-create (gdal/ogr-l-get-layer-defn layer))
                      geom (gdal/geometry-from-wkb
                            (support/polygon-wkb (support/square-ring x0 x0 10.0)) nil)]
                  (is (zero? (long (gdal/ogr-f-set-fid feat (+ 5000000000 i)))))
                  (gdal/ogr-f-set-field-string feat 0 nm)
                  (gdal/ogr-f-set-field-double feat 1 100.0)
                  (gdal/ogr-f-set-field-integer64 feat 2 (+ 3000000000 i))
                  (gdal/ogr-f-set-field-integer feat 3 i)
                  (is (zero? (long (gdal/ogr-f-set-geometry-directly feat geom))))
                  (is (zero? (long (gdal/ogr-l-create-feature layer feat))))
                  (gdal/ogr-f-destroy feat)))
              (is (zero? (long (gdal/gdal-dataset-commit-transaction ds)))))))
         (gdal/osr-destroy-spatial-reference srs)))

     (deftest gpkg-write-round-trip
       (support/call-with-temp-path
        "squares.gpkg"
        (fn [path]
          (write-squares! path)
          (let [recs (support/layer-records path)]
            (is (= [5000000000 5000000001] (mapv :fid recs)) "a FID above 2^31")
            (is (= ["sq-0" ""] (support/field-values recs "name"))
                "an empty string reads as \"\"")
            (is (= [100.0 100.0] (support/field-values recs "area")))
            (is (= [3000000000 3000000001] (support/field-values recs "id64"))
                "an Integer64 above 2^31")
            (is (= [0 1] (support/field-values recs "flag")))
            (is (= (mapv (fn [[_ x0]] (vec (support/polygon-wkb (support/square-ring x0 x0 10.0))))
                         squares)
                   (mapv (comp vec :wkb) recs))
                "the WKB of each square"))
          ;; GDAL makes the spatial index a virtual table of the sqlite
          ;; R-tree module. The schema text is in the file.
          (is (str/includes? (slurp path :encoding "ISO-8859-1") "USING rtree")
              "squares.gpkg has an R-tree spatial index"))))

     (deftest gpkg-write-over-an-existing-file
       (support/call-with-temp-path
        "squares.gpkg"
        (fn [path]
          (io/copy (io/file support/gpkg-path) (io/file path))
          (write-squares! path)
          (is (= ["sq-0" ""] (support/field-values (support/layer-records path) "name"))
              "the new file replaces the 3 features of tiny.gpkg"))))

     (deftest two-layers-in-one-gpkg
       (let [path "/vsimem/two-layers.gpkg"]
         (try
           (support/call-with-dataset
            (gdal/gdal-create (gdal/gdal-get-driver-by-name "GPKG") path 0 0 0 fndefs/GDT_Unknown nil)
            (fn [ds]
              (doseq [layer-name layer-names]
                (gdal/gdal-dataset-create-layer ds layer-name nil fndefs/wkbPoint nil))))
           (support/call-with-dataset
            (support/open-vector path)
            (fn [ds]
              (is (= 2 (gdal/gdal-dataset-get-layer-count ds)))
              (is (= layer-names (mapv #(gdal/ogr-l-get-name (gdal/gdal-dataset-get-layer ds %))
                                       (range 2))))
              (is (= "two" (gdal/ogr-l-get-name (gdal/gdal-dataset-get-layer-by-name ds "two"))))))
           (finally
             (gdal/vsi-unlink path)))))))

#?(:cljs
   (do
     (deftest ^:async gpkg-write-round-trip
       (let [path  "/tmp/squares.gpkg"
             drv   (await (gdal/gdal_get_driver_by_name "GPKG"))
             ds    (await (gdal/gdal_create drv path 0 0 0 fndefs/GDT_Unknown nil))
             srs   (await (gdal/osr_new_spatial_reference ""))
             _     (is (= 0 (await (gdal/osr_import_from_epsg srs 4326))))
             layer (await (gdal/gdal_dataset_create_layer ds "squares" srs
                                                          fndefs/wkbPolygon nil))]
         (is drv "GPKG driver present")
         (is ds "GDALCreate produced a vector dataset")
         (is layer "CreateLayer produced a layer")
         (doseq [[nm ty sub] field-specs]
           (let [fld (await (gdal/ogr_fld_create nm ty))]
             (when sub (await (gdal/ogr_fld_set_sub_type fld sub)))
             (is (= 0 (await (gdal/ogr_l_create_field layer fld 1)))
                 (str "CreateField " nm))
             (await (gdal/ogr_fld_destroy fld))))
         (let [fdefn (await (gdal/ogr_l_get_layer_defn layer))]
           (is (= 0 (await (gdal/gdal_dataset_start_transaction ds 1))))
           (doseq [[i x0 nm] squares]
             (let [feat (await (gdal/ogr_f_create fdefn))
                   geom (await (gdal/geometry_from_wkb (support/squareWkb x0 x0 10.0)))]
               (is (= 0 (await (gdal/ogr_f_set_fid feat (+ 5000000000 i)))))
               (await (gdal/ogr_f_set_field_string feat 0 nm))
               (await (gdal/ogr_f_set_field_double feat 1 100.0))
               (await (gdal/ogr_f_set_field_integer64 feat 2 (+ 3000000000 i)))
               (await (gdal/ogr_f_set_field_integer feat 3 i))
               (is (= 0 (await (gdal/ogr_f_set_geometry_directly feat geom))))
               (is (= 0 (await (gdal/ogr_l_create_feature layer feat))))
               (await (gdal/ogr_f_destroy feat))))
           (is (= 0 (await (gdal/gdal_dataset_commit_transaction ds)))))
         (await (gdal/osr_destroy_spatial_reference srs))
         (await (gdal/gdal_close ds))
         (let [recs (await (support/layerRecords path))]
           (is (= ["5000000000" "5000000001"] (mapv #(str (.-fid %)) recs))
               "a FID above 2^31")
           (is (= ["sq-0" ""] (support/fieldValues recs "name")) "an empty string reads as \"\"")
           (is (= [100 100] (support/fieldValues recs "area")))
           (is (= ["3000000000" "3000000001"] (mapv str (support/fieldValues recs "id64")))
               "an Integer64 above 2^31")
           (is (= [0 1] (support/fieldValues recs "flag")))
           (is (= (mapv (fn [[_ x0]] (vec (support/squareWkb x0 x0 10.0))) squares)
                  (mapv #(vec (.-wkb %)) recs))
               "the WKB of each square"))))

     (deftest ^:async two-layers-in-one-gpkg
       (let [path "/vsimem/two-layers.gpkg"
             ds   (await (gdal/gdal_create (await (gdal/gdal_get_driver_by_name "GPKG"))
                                           path 0 0 0 fndefs/GDT_Unknown nil))]
         (doseq [layer-name layer-names]
           (await (gdal/gdal_dataset_create_layer ds layer-name nil fndefs/wkbPoint nil)))
         (await (gdal/gdal_close ds))
         (let [ds (await (gdal/gdal_open_ex path fndefs/GDAL_OF_VECTOR nil nil nil))]
           (is (= 2 (await (gdal/gdal_dataset_get_layer_count ds))))
           (is (= "one" (await (gdal/ogr_l_get_name (await (gdal/gdal_dataset_get_layer ds 0))))))
           (is (= "two" (await (gdal/ogr_l_get_name
                                (await (gdal/gdal_dataset_get_layer_by_name ds "two"))))))
           (await (gdal/gdal_close ds)))
         (await (gdal/vsi_unlink path))))

     (run_tests_and_exit_BANG_ gdal/shutdown_BANG_ "net.willcohen.gdal.vector-write-test")))
