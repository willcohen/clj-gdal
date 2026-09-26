;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

;; tiny.gpkg has the features a, b and c with the FIDs 1, 2 and 3. Each is a
;; unit square: a at (0 0), b at (2 2) and c at (4 4).

(ns net.willcohen.gdal.vector-query-test
  (:require
   #?@(:clj  [[clojure.test :refer [deftest is use-fixtures]]
              [clojure.string :as str]
              [net.willcohen.gdal.fndefs :as fndefs]
              [net.willcohen.gdal.gdal :as gdal]
              [net.willcohen.gdal.support :as support]]
       :cljs [[cljs.test :refer [deftest is]]
              ["../../../../../src/cljc/net/willcohen/gdal/gdal.mjs" :as gdal]
              ["../../../../../src/cljc/net/willcohen/gdal/fndefs.mjs" :as fndefs]
              ["./support.mjs" :as support]
              ["../../../dist/test_runner.mjs"
               :refer [run_tests_and_exit_BANG_]]])))

(def ^:private square-wkt "POLYGON ((0 0,1 0,1 1,0 1,0 0))")

#?(:clj
   (do
     (use-fixtures :once support/with-backend)

     (defn- names [layer]
       (support/field-values (gdal/read-vector-features! layer) "name"))

     (deftest attribute-and-spatial-filters
       (support/call-with-layer
        support/gpkg-path
        (fn [layer]
          (is (zero? (gdal/ogr-l-set-attribute-filter layer "name = 'b'")))
          (is (= ["b"] (names layer)))
          (gdal/ogr-l-set-attribute-filter layer nil)
          (gdal/ogr-l-set-spatial-filter-rect layer 3.5 3.5 5.5 5.5)
          (is (= ["c"] (names layer)))
          (gdal/ogr-l-set-spatial-filter layer nil)
          (is (= ["a" "b" "c"] (names layer)) "no filter"))))

     (deftest execute-sql
       (support/call-with-dataset
        (support/open-vector support/gpkg-path)
        (fn [ds]
          (let [rs (gdal/gdal-dataset-execute-sql ds "SELECT name FROM tiny WHERE name <> 'a'" nil nil)]
            (try
              (is (= ["b" "c"] (names rs)))
              (finally
                (gdal/gdal-dataset-release-result-set ds rs)))))))

     (deftest layer-lookups
       (support/call-with-dataset
        (support/open-vector support/gpkg-path)
        (fn [ds]
          (let [layer (gdal/gdal-dataset-get-layer-by-name ds "tiny")]
            (is (= "tiny" (gdal/ogr-l-get-name layer)))
            (is (= fndefs/wkbPolygon (gdal/ogr-l-get-geom-type layer)))
            (is (= fndefs/wkbPolygon (gdal/ogr-fd-get-geom-type (gdal/ogr-l-get-layer-defn layer))))
            (is (= "id" (gdal/ogr-l-get-fid-column layer)))
            (is (= 1 (gdal/ogr-l-test-capability layer "RandomRead")))
            (is (nil? (gdal/gdal-dataset-get-layer-by-name ds "missing")))
            (let [feature (gdal/ogr-l-get-feature layer 2)]
              (try
                (is (= "b" (gdal/ogr-f-get-field-as-string
                            feature (gdal/ogr-f-get-field-index feature "name"))))
                (finally
                  (gdal/ogr-f-destroy feature))))))))

     (deftest layer-extent
       (support/call-with-layer
        support/gpkg-path
        #(is (= {:min-x 0.0 :max-x 5.0 :min-y 0.0 :max-y 5.0} (gdal/layer-extent %)))))

     (deftest geometry-to-wkb
       (let [geom (gdal/geometry-from-wkt square-wkt)]
         (try
           (is (= (first support/tiny-wkbs) (vec (gdal/geometry->wkb geom))))
           (finally
             (gdal/ogr-g-destroy-geometry geom)))))

     (defn- wkb-type
       "The geometry type of the little-endian WKB byte[] `wkb`."
       [^bytes wkb]
       (Integer/toUnsignedLong
        (.getInt (.order (java.nio.ByteBuffer/wrap wkb) java.nio.ByteOrder/LITTLE_ENDIAN) 1)))

     ;; The old-style WKB of a Z geometry sets the 0x80000000 flag, and ISO WKB
     ;; adds 1000 to the type. A 2D geometry gives the same bytes.
     (deftest geometry-to-iso-wkb
       (let [geom   (gdal/geometry-from-wkt "POINT Z (1 2 3)")
             square (gdal/geometry-from-wkt square-wkt)]
         (try
           (is (= 0x80000001 (wkb-type (gdal/geometry->wkb geom))))
           (is (= 1001 (wkb-type (gdal/geometry->iso-wkb geom))))
           (is (= 0 (first (gdal/geometry->iso-wkb geom fndefs/wkbXDR))) "big-endian")
           (is (= (first support/tiny-wkbs) (vec (gdal/geometry->iso-wkb square))))
           (finally
             (gdal/ogr-g-destroy-geometry square)
             (gdal/ogr-g-destroy-geometry geom)))))

     (deftest feature-to-iso-wkb
       (support/call-with-layer
        support/gpkg-path
        (fn [layer]
          (let [feature (gdal/ogr-l-get-feature layer 1)]
            (try
              (gdal/ogr-f-set-geometry-directly feature (gdal/geometry-from-wkt "POINT Z (1 2 3)"))
              (is (= 0x80000001 (wkb-type (gdal/feature->wkb feature))))
              (is (= 1001 (wkb-type (gdal/feature->iso-wkb feature))))
              (finally
                (gdal/ogr-f-destroy feature)))))))

     (deftest geometry-text
       (let [geom (gdal/geometry-from-wkt square-wkt)]
         (try
           (is (= fndefs/wkbPolygon (gdal/ogr-g-get-geometry-type geom)))
           (is (= square-wkt (gdal/geometry->wkt geom)))
           (let [json  (gdal/geometry->json geom)
                 geom2 (gdal/ogr-g-create-geometry-from-json json)]
             (try
               (is (str/includes? json "\"Polygon\""))
               (is (= square-wkt (gdal/geometry->wkt geom2)) "the GeoJSON gives the same geometry")
               (finally
                 (gdal/ogr-g-destroy-geometry geom2))))
           (finally
             (gdal/ogr-g-destroy-geometry geom))))
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"OGR_G_CreateFromWkt failed"
                             (gdal/geometry-from-wkt "POLYGON ((0 0")))
       (is (nil? (gdal/ogr-g-create-geometry-from-json "{")) "bad JSON gives NULL"))))

#?(:cljs
   (do
     (defn- ^:async names [layer]
       (support/fieldValues (await (gdal/read_vector_features_BANG_ layer)) "name"))

     (deftest ^:async attribute-and-spatial-filters
       (let [ds    (await (gdal/open_from_disk_BANG_ support/gpkg-path fndefs/GDAL_OF_VECTOR))
             layer (await (gdal/gdal_dataset_get_layer ds 0))]
         (is (= 0 (await (gdal/ogr_l_set_attribute_filter layer "name = 'b'"))))
         (is (= ["b"] (await (names layer))))
         (await (gdal/ogr_l_set_attribute_filter layer nil))
         (await (gdal/ogr_l_set_spatial_filter_rect layer 3.5 3.5 5.5 5.5))
         (is (= ["c"] (await (names layer))))
         (await (gdal/ogr_l_set_spatial_filter layer nil))
         (is (= ["a" "b" "c"] (await (names layer))) "no filter")
         (await (gdal/gdal_close ds))))

     (deftest ^:async execute-sql
       (let [ds (await (gdal/open_from_disk_BANG_ support/gpkg-path fndefs/GDAL_OF_VECTOR))
             rs (await (gdal/gdal_dataset_execute_sql
                        ds "SELECT name FROM tiny WHERE name <> 'a'" nil nil))]
         (is (= ["b" "c"] (await (names rs))))
         (await (gdal/gdal_dataset_release_result_set ds rs))
         (await (gdal/gdal_close ds))))

     (deftest ^:async layer-lookups
       (let [ds      (await (gdal/open_from_disk_BANG_ support/gpkg-path fndefs/GDAL_OF_VECTOR))
             layer   (await (gdal/gdal_dataset_get_layer_by_name ds "tiny"))
             feature (await (gdal/ogr_l_get_feature layer 2))]
         (is (= fndefs/wkbPolygon (await (gdal/ogr_l_get_geom_type layer))))
         (is (= "id" (await (gdal/ogr_l_get_fid_column layer))))
         (is (= 1 (await (gdal/ogr_l_test_capability layer "RandomRead"))))
         (is (nil? (await (gdal/gdal_dataset_get_layer_by_name ds "missing"))))
         (is (= "b" (await (gdal/ogr_f_get_field_as_string
                            feature (await (gdal/ogr_f_get_field_index feature "name"))))))
         (await (gdal/ogr_f_destroy feature))
         (await (gdal/gdal_close ds))))

     (deftest ^:async layer-extent
       (let [ds (await (gdal/open_from_disk_BANG_ support/gpkg-path fndefs/GDAL_OF_VECTOR))
             e  (await (gdal/layer_extent (await (gdal/gdal_dataset_get_layer ds 0))))]
         (is (= [0 5 0 5] (mapv #(aget e %) ["min-x" "max-x" "min-y" "max-y"])))
         (await (gdal/gdal_close ds))))

     (deftest ^:async geometry-to-wkb
       (let [geom (await (gdal/geometry_from_wkt square-wkt))
             wkb  (await (gdal/geometry__GT_wkb geom))]
         (is (= (vec (support/squareWkb 0 0 1)) (vec wkb)))
         (await (gdal/ogr_g_destroy_geometry geom))))

     (defn- wkb-type
       "The geometry type of the little-endian WKB Uint8Array `wkb`."
       [wkb]
       (.getUint32 (js/DataView. (.-buffer wkb) (.-byteOffset wkb)) 1 true))

     (deftest ^:async geometry-to-iso-wkb
       (let [geom   (await (gdal/geometry_from_wkt "POINT Z (1 2 3)"))
             square (await (gdal/geometry_from_wkt square-wkt))]
         (is (= 0x80000001 (wkb-type (await (gdal/geometry__GT_wkb geom)))))
         (is (= 1001 (wkb-type (await (gdal/geometry__GT_iso_wkb geom)))))
         (is (= 0 (aget (await (gdal/geometry__GT_iso_wkb geom fndefs/wkbXDR)) 0)) "big-endian")
         (is (= (vec (await (gdal/geometry__GT_wkb square)))
                (vec (await (gdal/geometry__GT_iso_wkb square)))))
         (await (gdal/ogr_g_destroy_geometry square))
         (await (gdal/ogr_g_destroy_geometry geom))))

     (deftest ^:async feature-to-iso-wkb
       (let [ds      (await (gdal/open_from_disk_BANG_ support/gpkg-path fndefs/GDAL_OF_VECTOR))
             feature (await (gdal/ogr_l_get_feature (await (gdal/gdal_dataset_get_layer ds 0)) 1))]
         (await (gdal/ogr_f_set_geometry_directly
                 feature (await (gdal/geometry_from_wkt "POINT Z (1 2 3)"))))
         (is (= 0x80000001 (wkb-type (await (gdal/feature__GT_wkb feature)))))
         (is (= 1001 (wkb-type (await (gdal/feature__GT_iso_wkb feature)))))
         (await (gdal/ogr_f_destroy feature))
         (await (gdal/gdal_close ds))))

     (deftest ^:async geometry-text
       (let [geom  (await (gdal/geometry_from_wkt square-wkt))
             json  (await (gdal/geometry__GT_json geom))
             geom2 (await (gdal/ogr_g_create_geometry_from_json json))]
         (is (= fndefs/wkbPolygon (await (gdal/ogr_g_get_geometry_type geom))))
         (is (= square-wkt (await (gdal/geometry__GT_wkt geom))))
         (is (.includes json "\"Polygon\""))
         (is (= square-wkt (await (gdal/geometry__GT_wkt geom2))) "the GeoJSON gives the same geometry")
         (await (gdal/ogr_g_destroy_geometry geom2))
         (await (gdal/ogr_g_destroy_geometry geom))
         (is (.includes (await (support/rejectionMessage (gdal/geometry_from_wkt "POLYGON ((0 0")))
                        "OGR_G_CreateFromWkt failed"))
         (is (nil? (await (gdal/ogr_g_create_geometry_from_json "{"))) "bad JSON gives NULL")))

     (run_tests_and_exit_BANG_ gdal/shutdown_BANG_ "net.willcohen.gdal.vector-query-test")))
