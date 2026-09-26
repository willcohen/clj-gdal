;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.raster-info-test
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

;; A 3 x 2 Byte band, row by row.
(def ^:private pixels [0 1 2 253 254 255])

(def ^:private geo-transform [10.0 1.0 0.0 20.0 0.0 -1.0])

;; README.md names these drivers.
(def ^:private driver-names
  ["VRT" "GTiff" "COG" "PNG" "JPEG" "MEM" "GNMFile" "GNMDatabase" "ESRI Shapefile"
   "GML" "GeoJSON" "GeoJSONSeq" "ESRIJSON" "TopoJSON" "GPKG" "SQLite" "OpenFileGDB"
   "FlatGeobuf"])

#?(:clj
   (do
     (use-fixtures :once support/with-backend)

     (deftest each-driver-of-the-build
       (is (= (set driver-names)
              (set (map #(gdal/gdal-get-driver-short-name (gdal/gdal-get-driver %))
                        (range (gdal/gdal-get-driver-count)))))))

     ;; A MEM band has a block of one full row.
     (deftest nodata-and-block-size
       (let [ds   (gdal/gdal-create (gdal/gdal-get-driver-by-name "MEM") "" 4 3 1 fndefs/GDT_Byte nil)
             band (gdal/gdal-get-raster-band ds 1)]
         (try
           (is (nil? (gdal/band-nodata band)) "a new band has no nodata value")
           (is (zero? (gdal/gdal-set-raster-no-data-value band 255.0)))
           (is (= 255.0 (gdal/band-nodata band)))
           (is (= [4 1] (gdal/band-block-size band)))
           (is (= "Byte" (gdal/gdal-get-data-type-name (gdal/gdal-get-raster-data-type band))))
           (finally
             (gdal/gdal-close ds)))))

     ;; Band b holds the value b.
     (deftest a-three-band-raster
       (let [dst "/vsimem/rgb.tif"
             mem (gdal/gdal-create (gdal/gdal-get-driver-by-name "MEM") "" 4 4 3 fndefs/GDT_Byte nil)]
         (try
           (doseq [b [1 2 3]]
             (gdal/write-raster-band! mem b (repeat 16 b)))
           (gdal/gdal-close (gdal/gdal-create-copy (gdal/gdal-get-driver-by-name "GTiff")
                                                   dst mem 0 nil nil nil))
           (support/call-with-dataset
            (support/open-raster dst)
            (fn [ds]
              (is (= 3 (gdal/gdal-get-raster-count ds)))
              (is (= (repeat 16 3) (seq (gdal/read-raster-band ds 3))))))
           (finally
             (gdal/gdal-close mem)
             (gdal/vsi-unlink dst)))))

     (deftest write-a-raster-band
       (let [ds  (gdal/gdal-create (gdal/gdal-get-driver-by-name "MEM") "" 3 2 1 fndefs/GDT_Byte nil)
             srs (gdal/osr-new-spatial-reference "")]
         (try
           (gdal/write-raster-band! ds 1 pixels)
           (is (= pixels (mapv #(Byte/toUnsignedInt %) (gdal/read-raster-band ds 1))))
           (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes 6 values, not 2"
                                 (gdal/write-raster-band! ds 1 [1 2])))
           (gdal/set-geo-transform! ds geo-transform)
           (is (= geo-transform (vec (gdal/get-geo-transform ds))))
           (gdal/osr-import-from-epsg srs 3857)
           (is (zero? (gdal/gdal-set-projection ds (gdal/srs-export-to-wkt srs))))
           (is (str/includes? (gdal/gdal-get-projection-ref ds) "Pseudo-Mercator"))
           (finally
             (gdal/osr-destroy-spatial-reference srs)
             (gdal/gdal-close ds)))))

     (deftest build-overviews
       (let [ds (gdal/gdal-create (gdal/gdal-get-driver-by-name "MEM") "" 8 8 1 fndefs/GDT_Byte nil)]
         (try
           (gdal/build-overviews! ds "NEAREST" [2 4])
           (let [band (gdal/gdal-get-raster-band ds 1)]
             (is (= 2 (gdal/gdal-get-overview-count band)))
             (is (= [4 2] (mapv #(gdal/gdal-get-raster-band-x-size (gdal/gdal-get-overview band %))
                                (range 2)))))
           (is (thrown-with-msg? clojure.lang.ExceptionInfo #"GDALBuildOverviews failed"
                                 (gdal/build-overviews! ds "NO-SUCH-METHOD" [2])))
           (finally
             (gdal/gdal-close ds)))))

     (deftest dataset-descriptions
       (support/call-with-dataset
        (support/open-raster support/tif-path)
        (fn [ds]
          (let [driver (gdal/gdal-get-dataset-driver ds)]
            (is (= "GTiff" (gdal/gdal-get-driver-short-name driver)))
            (is (= "GeoTIFF" (gdal/gdal-get-driver-long-name driver)))
            (is (str/ends-with? (gdal/gdal-get-description ds) "tiny.tif"))
            (is (some #(str/ends-with? % "tiny.tif") (gdal/file-list ds)))
            (is (= "Area" (get (gdal/metadata ds) "AREA_OR_POINT")))
            (is (= "Area" (gdal/gdal-get-metadata-item ds "AREA_OR_POINT" nil)))
            (is (= {} (gdal/metadata ds "NO_SUCH_DOMAIN")))
            (is (= "4326" (gdal/osr-get-authority-code (gdal/gdal-get-spatial-ref ds) nil)))))))

     (deftest info-text
       (support/call-with-dataset
        (support/open-raster support/tif-path)
        (fn [ds]
          (is (str/includes? (gdal/raster-info ds) "Size is 16, 16"))
          (is (str/includes? (gdal/raster-info ds ["-json"]) "\"size\""))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"-nosuchoption"
                                (gdal/raster-info ds ["-nosuchoption"])))))
       (support/call-with-dataset
        (support/open-vector support/gpkg-path)
        #(is (str/includes? (gdal/vector-info % ["-so" "-al"]) "Feature Count: 3"))))

     (deftest translate-raster-to-png
       (support/call-with-temp-path
        "out.png"
        (fn [dst]
          (support/call-with-dataset
           (support/open-raster support/tif-path)
           #(gdal/gdal-close (gdal/translate-raster! % dst ["-of" "PNG"])))
          (support/call-with-dataset
           (support/open-raster dst)
           (fn [ds]
             (is (= "PNG" (gdal/gdal-get-driver-short-name (gdal/gdal-get-dataset-driver ds))))
             (is (= (map unchecked-byte (range 256)) (seq (gdal/read-raster-band ds 1)))
                 "the pixels of tiny.tif"))))))))

#?(:cljs
   (do
     (deftest ^:async each-driver-of-the-build
       (let [n     (await (gdal/gdal_get_driver_count))
             names (js/Set.)]
         (dotimes [i n]
           (.add names (await (gdal/gdal_get_driver_short_name (await (gdal/gdal_get_driver i))))))
         (is (= (count driver-names) (.-size names)))
         (is (every? #(.has names %) driver-names))))

     (deftest ^:async nodata-and-block-size
       (let [ds   (await (gdal/gdal_create (await (gdal/gdal_get_driver_by_name "MEM"))
                                           "" 4 3 1 fndefs/GDT_Byte nil))
             band (await (gdal/gdal_get_raster_band ds 1))]
         (is (identical? nil (await (gdal/band_nodata band))) "a new band has no nodata value")
         (is (= 0 (await (gdal/gdal_set_raster_no_data_value band 255))))
         (is (= 255 (await (gdal/band_nodata band))))
         (is (= [4 1] (await (gdal/band_block_size band))))
         (is (= "Byte" (await (gdal/gdal_get_data_type_name
                               (await (gdal/gdal_get_raster_data_type band))))))
         (await (gdal/gdal_close ds))))

     (deftest ^:async a-three-band-raster
       (let [mem (await (gdal/gdal_create (await (gdal/gdal_get_driver_by_name "MEM"))
                                          "" 4 4 3 fndefs/GDT_Byte nil))]
         (doseq [b [1 2 3]]
           (await (gdal/write_raster_band_BANG_ mem b (vec (repeat 16 b)))))
         (await (gdal/gdal_close (await (gdal/gdal_create_copy
                                         (await (gdal/gdal_get_driver_by_name "GTiff"))
                                         "/vsimem/rgb.tif" mem 0 nil nil nil))))
         (await (gdal/gdal_close mem))
         (let [ds (await (gdal/gdal_open_ex "/vsimem/rgb.tif" fndefs/GDAL_OF_RASTER nil nil nil))]
           (is (= 3 (await (gdal/gdal_get_raster_count ds))))
           (is (= (vec (repeat 16 3)) (vec (await (gdal/read_raster_band ds 3)))))
           (await (gdal/gdal_close ds))
           (await (gdal/vsi_unlink "/vsimem/rgb.tif")))))

     (deftest ^:async write-a-raster-band
       (let [ds  (await (gdal/gdal_create (await (gdal/gdal_get_driver_by_name "MEM"))
                                          "" 3 2 1 fndefs/GDT_Byte nil))
             _   (await (gdal/write_raster_band_BANG_ ds 1 pixels))
             msg (await (support/rejectionMessage (gdal/write_raster_band_BANG_ ds 1 #js [1 2])))]
         (is (= pixels (vec (await (gdal/read_raster_band ds 1)))))
         (is (.includes (str msg) "takes 6 values, not 2"))
         (await (gdal/set_geo_transform_BANG_ ds geo-transform))
         (is (= geo-transform (vec (await (gdal/get_geo_transform ds)))))
         (let [srs (await (gdal/osr_new_spatial_reference ""))]
           (await (gdal/osr_import_from_epsg srs 3857))
           (is (= 0 (await (gdal/gdal_set_projection ds (await (gdal/srs_export_to_wkt srs))))))
           (is (.includes (await (gdal/gdal_get_projection_ref ds)) "Pseudo-Mercator"))
           (await (gdal/osr_destroy_spatial_reference srs)))
         (await (gdal/gdal_close ds))))

     (deftest ^:async build-overviews
       (let [ds   (await (gdal/gdal_create (await (gdal/gdal_get_driver_by_name "MEM"))
                                           "" 8 8 1 fndefs/GDT_Byte nil))
             _    (await (gdal/build_overviews_BANG_ ds "NEAREST" #js [2 4]))
             band (await (gdal/gdal_get_raster_band ds 1))
             msg  (await (support/rejectionMessage
                          (gdal/build_overviews_BANG_ ds "NO-SUCH-METHOD" #js [2])))]
         (is (= 2 (await (gdal/gdal_get_overview_count band))))
         (is (= 2 (await (gdal/gdal_get_raster_band_x_size (await (gdal/gdal_get_overview band 1))))))
         (is (.includes (str msg) "GDALBuildOverviews failed"))
         (await (gdal/gdal_close ds))))

     (deftest ^:async dataset-descriptions
       (let [ds     (await (gdal/open_from_disk_BANG_ support/tif-path fndefs/GDAL_OF_RASTER))
             driver (await (gdal/gdal_get_dataset_driver ds))]
         (is (= "GTiff" (await (gdal/gdal_get_driver_short_name driver))))
         (is (= "GeoTIFF" (await (gdal/gdal_get_driver_long_name driver))))
         (is (.endsWith (await (gdal/gdal_get_description ds)) "tiny.tif"))
         (is (.some (await (gdal/file_list ds)) #(.endsWith % "tiny.tif")))
         (is (= "Area" (aget (await (gdal/metadata ds)) "AREA_OR_POINT")))
         (is (= "Area" (await (gdal/gdal_get_metadata_item ds "AREA_OR_POINT" nil))))
         (is (= {} (await (gdal/metadata ds "NO_SUCH_DOMAIN"))))
         (is (= "4326" (await (gdal/osr_get_authority_code (await (gdal/gdal_get_spatial_ref ds)) nil))))
         (await (gdal/gdal_close ds))))

     (deftest ^:async info-text
       (let [tif  (await (gdal/open_from_disk_BANG_ support/tif-path fndefs/GDAL_OF_RASTER))
             gpkg (await (gdal/open_from_disk_BANG_ support/gpkg-path fndefs/GDAL_OF_VECTOR))]
         (is (.includes (await (gdal/raster_info tif)) "Size is 16, 16"))
         (is (.includes (await (gdal/raster_info tif #js ["-json"])) "\"size\""))
         (is (.includes (await (support/rejectionMessage (gdal/raster_info tif #js ["-nosuchoption"])))
                        "-nosuchoption"))
         (is (.includes (await (gdal/vector_info gpkg #js ["-so" "-al"])) "Feature Count: 3"))
         (await (gdal/gdal_close gpkg))
         (await (gdal/gdal_close tif))))

     (deftest ^:async translate-raster-to-png
       (let [src (await (gdal/open_from_disk_BANG_ support/tif-path fndefs/GDAL_OF_RASTER))]
         (await (gdal/gdal_close (await (gdal/translate_raster_BANG_ src "/work/out.png" #js ["-of" "PNG"]))))
         (await (gdal/gdal_close src))
         (let [ds (await (gdal/gdal_open_ex "/work/out.png" fndefs/GDAL_OF_RASTER nil nil nil))]
           (is (= "PNG" (await (gdal/gdal_get_driver_short_name (await (gdal/gdal_get_dataset_driver ds))))))
           (is (= (vec (range 256)) (vec (await (gdal/read_raster_band ds 1)))) "the pixels of tiny.tif")
           (await (gdal/gdal_close ds)))))

     (run_tests_and_exit_BANG_ gdal/shutdown_BANG_ "net.willcohen.gdal.raster-info-test")))
