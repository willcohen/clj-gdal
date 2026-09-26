;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.vector-translate-test
  (:require
   #?@(:clj  [[clojure.test :refer [deftest is use-fixtures]]
              [clojure.java.io :as io]
              [net.willcohen.gdal.gdal :as gdal]
              [net.willcohen.gdal.support :as support]]
       :cljs [[cljs.test :refer [deftest is]]
              ["../../../../../src/cljc/net/willcohen/gdal/gdal.mjs" :as gdal]
              ["../../../../../src/cljc/net/willcohen/gdal/fndefs.mjs" :as fndefs]
              ["./support.mjs" :as support]
              ["../../../dist/test_runner.mjs"
               :refer [run_tests_and_exit_BANG_]]])))

;; [options name]: each filter keeps the one feature `name` of tiny.geojson.
(def ^:private filters
  [[["-where" "name = 'b'"] "b"]
   [["-spat" "1.5" "1.5" "3.5" "3.5"] "b"]
   [["-sql" "SELECT * FROM tiny WHERE id = 3"] "c"]])

#?(:clj
   (do
     (use-fixtures :once support/with-backend)

     (deftest vector-translate-reprojects
       (support/call-with-temp-path
        "out.geojson"
        (fn [dst]
          (support/translate-vector! support/geojson-path dst ["-f" "GeoJSON" "-t_srs" "EPSG:3857"])
          (support/call-with-layer
           dst
           (fn [layer]
             (is (= 3 (gdal/layer-feature-count layer)))
             (is (= "3857" (gdal/osr-get-authority-code (gdal/ogr-l-get-spatial-ref layer) nil))
                 "the output has the EPSG:3857 SRS"))))))

     ;; With no -f, GDAL selects the driver from the extension.
     (deftest vector-translate-with-no-options
       (support/call-with-temp-path
        "out.gpkg"
        (fn [dst]
          (support/translate-vector! support/geojson-path dst [])
          (is (= ["a" "b" "c"] (support/field-values (support/layer-records dst) "name"))))))

     ;; On GraalVM, gdal-close copies the whole tree that GDAL wrote to the host.
     (deftest vector-translate-to-a-directory
       (support/call-with-temp-path
        "out.gdb"
        (fn [gdb]
          (support/translate-vector! support/gpkg-path gdb ["-f" "OpenFileGDB"])
          (is (.isDirectory (io/file gdb)) "out.gdb is a directory on the host")
          (is (= #{"a" "b" "c"} (set (support/field-values (support/layer-records gdb) "name")))))))

     ;; A GML file keeps its schema in a sidecar, out.xsd for out.gml. GDAL
     ;; reads GML only with Expat or Xerces, and the build has neither
     ;; (README.md, Known limitations).
     (deftest vector-translate-to-gml-writes-the-schema
       (support/call-with-temp-dir
        "gdal-vector-gml"
        (fn [dir]
          (let [dst (support/path-in dir "out.gml")]
            (support/translate-vector! support/gpkg-path dst ["-f" "GML"])
            (is (.isFile (io/file dir "out.xsd")))
            (is (thrown? clojure.lang.ExceptionInfo (support/open-vector dst))
                "GML is write only")))))

     (deftest vector-translate-filters
       (support/call-with-temp-dir
        "gdal-vector-filters"
        (fn [dir]
          (doseq [[options want] filters]
            (let [dst (support/path-in dir (str (subs (first options) 1) ".geojson"))]
              (support/translate-vector! support/geojson-path dst (into ["-f" "GeoJSON"] options))
              (is (= [want] (support/field-values (support/layer-records dst) "name"))
                  (pr-str options)))))))

     ;; The spatial index of FlatGeobuf sorts the features.
     (deftest vector-translate-to-flatgeobuf
       (support/call-with-temp-path
        "out.fgb"
        (fn [dst]
          (support/translate-vector! support/geojson-path dst ["-f" "FlatGeobuf"])
          (let [records (support/layer-records dst)]
            (is (= #{"a" "b" "c"} (set (support/field-values records "name"))))
            (is (= (set support/tiny-wkbs) (set (map (comp vec :wkb) records))))))))

     (deftest vector-translate-to-utm
       (support/call-with-temp-path
        "out.gpkg"
        (fn [dst]
          (support/translate-vector! support/geojson-path dst ["-t_srs" "EPSG:32631"])
          (support/call-with-layer
           dst
           #(is (= "32631" (gdal/osr-get-authority-code (gdal/ogr-l-get-spatial-ref %) nil)))))))))

#?(:cljs
   (do
     (defn- ^:async translate-tiny!
       "Translate tiny.geojson into `dst` with the GDAL `options`."
       [dst options]
       (let [src (await (gdal/open_from_disk_BANG_ support/geojson-path fndefs/GDAL_OF_VECTOR))]
         (await (gdal/gdal_close (await (gdal/translate_vector_BANG_ src dst options))))
         (await (gdal/gdal_close src))))

     (defn- ^:async names-of [path]
       (support/fieldValues (await (support/layerRecords path)) "name"))

     (deftest ^:async vector-translate-reprojects
       (let [dst "/work/reprojected.geojson"]
         (await (translate-tiny! dst #js ["-f" "GeoJSON" "-t_srs" "EPSG:3857"]))
         (let [ds       (await (gdal/gdal_open_ex dst fndefs/GDAL_OF_VECTOR nil nil nil))
               layer    (await (gdal/gdal_dataset_get_layer ds 0))
               projjson (js/JSON.parse
                         (await (gdal/srs_export_to_projjson
                                 (await (gdal/ogr_l_get_spatial_ref layer)))))]
           (is (= 3 (js/Number (await (gdal/layer_feature_count layer)))))
           (is (= 3857 (.-code (.-id projjson))) "the output has the EPSG:3857 SRS")
           (await (gdal/gdal_close ds)))))

     (deftest ^:async vector-translate-to-gml-writes-the-schema
       (await (translate-tiny! "/vsimem/gml/out.gml" #js ["-f" "GML"]))
       (is (= ["out.gml" "out.xsd"] (vec (.sort (await (gdal/read_vsi_dir "/vsimem/gml"))))))
       (is (not (await (gdal/gdal_open_ex "/vsimem/gml/out.gml" fndefs/GDAL_OF_VECTOR nil nil nil)))
           "GML is write only")
       (await (gdal/cpl_error_reset)))

     (deftest ^:async vector-translate-filters
       (doseq [[options want] filters]
         (let [dst (str "/work/filter-" (subs (first options) 1) ".geojson")]
           (await (translate-tiny! dst (into ["-f" "GeoJSON"] options)))
           (is (= [want] (await (names-of dst))) (str options)))))

     (deftest ^:async vector-translate-to-flatgeobuf
       (await (translate-tiny! "/work/out.fgb" #js ["-f" "FlatGeobuf"]))
       (is (= ["a" "b" "c"] (.sort (await (names-of "/work/out.fgb"))))))

     (deftest ^:async vector-translate-to-utm
       (await (translate-tiny! "/work/utm.gpkg" #js ["-t_srs" "EPSG:32631"]))
       (let [ds  (await (gdal/gdal_open_ex "/work/utm.gpkg" fndefs/GDAL_OF_VECTOR nil nil nil))
             srs (await (gdal/ogr_l_get_spatial_ref (await (gdal/gdal_dataset_get_layer ds 0))))]
         (is (= "32631" (await (gdal/osr_get_authority_code srs nil))))
         (await (gdal/gdal_close ds))))

     (run_tests_and_exit_BANG_ gdal/shutdown_BANG_ "net.willcohen.gdal.vector-translate-test")))
