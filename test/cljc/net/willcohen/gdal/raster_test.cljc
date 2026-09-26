;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.raster-test
  (:require
   #?@(:clj  [[clojure.java.io :as io]
              [clojure.string :as str]
              [clojure.test :refer [deftest is use-fixtures]]
              [net.willcohen.gdal.gdal :as gdal]
              [net.willcohen.gdal.fndefs :as fndefs]
              [net.willcohen.gdal.support :as support]
              [net.willcohen.gdal.wasm :as gwasm]]
       :cljs [[cljs.test :refer [deftest is]]
              ["node:fs" :as fs]
              ["../../../../../src/cljc/net/willcohen/gdal/gdal.mjs" :as gdal]
              ["../../../../../src/cljc/net/willcohen/gdal/fndefs.mjs" :as fndefs]
              ["../../../../../src/cljc/net/willcohen/gdal/wasm.mjs" :as gwasm]
              ["./support.mjs" :as support]
              ["../../../dist/test_runner.mjs"
               :refer [run_tests_and_exit_BANG_]]])))

(def ^:private geo-transform [10.0 1.0 0.0 20.0 0.0 -1.0])

;; tiny.tif has the pixel values 0 to 255. Int8 keeps 0 to 127, and the warp
;; clamps each value above.
(def ^:private data-type-cases
  [["Int8"    fndefs/GDT_Int8    #?(:clj byte/1 :cljs js/Int8Array)      (mapv #(min 127 %) (range 256))]
   ["UInt16"  fndefs/GDT_UInt16  #?(:clj short/1 :cljs js/Uint16Array)   (vec (range 256))]
   ["Int16"   fndefs/GDT_Int16   #?(:clj short/1 :cljs js/Int16Array)    (vec (range 256))]
   ["UInt32"  fndefs/GDT_UInt32  #?(:clj int/1 :cljs js/Uint32Array)     (vec (range 256))]
   ["Int32"   fndefs/GDT_Int32   #?(:clj int/1 :cljs js/Int32Array)      (vec (range 256))]
   ["Float32" fndefs/GDT_Float32 #?(:clj float/1 :cljs js/Float32Array)  (vec (range 256))]
   ["Float64" fndefs/GDT_Float64 #?(:clj double/1 :cljs js/Float64Array) (vec (range 256))]])

;; -te gives the square of EPSG:3857, because latitude 90 has no Mercator
;; value.
(def ^:private mercator-options
  ["-t_srs" "EPSG:3857" "-te" "-20037508.34" "-20037508.34" "20037508.34" "20037508.34"
   "-ts" "16" "16"])

;; GDAL reads a world file named for the first and last letters of the
;; extension, or for the whole extension, with a "w" after it.
(deftest a-world-file-is-in-the-family-of-its-raster
  (doseq [[raster world] [["a.tif" "a.tfw"] ["a.tif" "a.tifw"] ["a.tiff" "a.tiffw"]
                          ["a.png" "a.pgw"] ["a.png" "a.pngw"]
                          ["a.jpg" "a.jgw"] ["a.jpg" "a.jpgw"] ["a.jpeg" "a.jpegw"]]]
    (is (gwasm/in-family? raster world) world)))

#?(:clj
   (do
     (use-fixtures :once support/with-backend)

     (defn- warp-tiny!
       "Warp tiny.tif into `path` with the gdalwarp `options`."
       [path options]
       (support/call-with-dataset
        (support/open-raster support/tif-path)
        #(gdal/gdal-close (gdal/warp-raster! [%] path options))))

     (deftest read-tiny-tif
       (support/call-with-dataset
        (support/open-raster support/tif-path)
        (fn [ds]
          (let [pixels (gdal/read-raster-band ds 1)]
            (is (bytes? pixels))
            (is (= (map unchecked-byte (range 256)) (seq pixels))
                "pixel i is i, and a Byte above 127 reads as a negative byte")))))

     (deftest geo-transform-of-tiny-tif
       (support/call-with-dataset
        (support/open-raster support/tif-path)
        #(is (= [-180.0 22.5 0.0 90.0 0.0 -11.25] (vec (gdal/get-geo-transform %))))))

     ;; GDALOpen takes GA_Update, and GraalVM then copies the change back to the
     ;; host at gdal-close.
     (deftest gdal-open-for-update-changes-the-host-file
       (support/call-with-temp-path
        "tiny.tif"
        (fn [path]
          (io/copy (io/file support/tif-path) (io/file path))
          (support/call-with-dataset (gdal/gdal-open path fndefs/GA_Update)
                                     #(gdal/set-geo-transform! % geo-transform))
          (support/call-with-dataset
           (gdal/gdal-open path fndefs/GA_ReadOnly)
           #(is (= geo-transform (vec (gdal/get-geo-transform %))) "GA_ReadOnly")))))

     (deftest read-raster-band-rejects-a-missing-band
       (support/call-with-dataset
        (support/open-raster support/tif-path)
        #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"GDALGetRasterBand returned NULL for band 2"
                               (gdal/read-raster-band % 2)))))

     (deftest warp-to-each-data-type
       (support/call-with-temp-dir
        "gdal-warp-types"
        (fn [dir]
          (doseq [[ot data-type array-class values] data-type-cases]
            (let [path (support/path-in dir (str "tiny-" ot ".tif"))]
              (warp-tiny! path ["-ot" ot "-r" "near"])
              (support/call-with-dataset
               (support/open-raster path)
               (fn [ds]
                 (let [arr (gdal/read-raster-band ds 1)]
                   (is (= data-type (gdal/gdal-get-raster-data-type (gdal/gdal-get-raster-band ds 1)))
                       ot)
                   (is (instance? array-class arr) ot)
                   (is (= values (map long (seq arr))) (str ot ": the pixel values"))))))))))

     (deftest read-raster-band-names-the-types-that-it-reads
       (support/call-with-temp-path
        "tiny-Int64.tif"
        (fn [path]
          (warp-tiny! path ["-ot" "Int64"])
          (support/call-with-dataset
           (support/open-raster path)
           (fn [ds]
             (is (= fndefs/GDT_Int64 (gdal/gdal-get-raster-data-type (gdal/gdal-get-raster-band ds 1))))
             (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                   #"GDAL data type 13 has no reader: read-raster-band reads Byte, Int8, UInt16, Int16, UInt32, Int32, Float32 and Float64"
                                   (gdal/read-raster-band ds 1))))))))

     ;; build-csl-options gives nil for no options, and GDAL takes a NULL argv.
     (deftest warp-with-no-options
       (support/call-with-temp-path
        "copy.tif"
        (fn [path]
          (warp-tiny! path [])
          (support/call-with-dataset
           (support/open-raster path)
           #(is (= (map unchecked-byte (range 256)) (seq (gdal/read-raster-band % 1)))
                "the warp has the pixels of tiny.tif")))))

     (deftest warp-reprojects
       (support/call-with-temp-path
        "tiny-3857.tif"
        (fn [path]
          (warp-tiny! path mercator-options)
          (support/call-with-dataset
           (support/open-raster path)
           (fn [ds]
             (is (str/includes? (str (gdal/gdal-get-projection-ref ds)) "Pseudo-Mercator"))
             (is (< 1 (count (distinct (gdal/read-raster-band ds 1))))
                 "the warp moves the pixels of tiny.tif"))))))

     (deftest warp-raster-rejects-no-source
       (doseq [srcs [[] :not-a-seq]]
         (is (thrown-with-msg? clojure.lang.ExceptionInfo
                               #"takes a sequence of one or more source datasets"
                               (gdal/warp-raster! srcs "unused.tif" [])))))))

#?(:cljs
   (do
     (deftest ^:async gdal-open-for-update
       (let [staged (await (gdal/stage_files_BANG_
                            #js {"tiny.tif" (.readFileSync fs support/tif-path)} "/work-open"))
             path   (aget staged "tiny.tif")
             ds     (await (gdal/gdal_open path fndefs/GA_Update))]
         (is ds "GA_Update")
         (await (gdal/set_geo_transform_BANG_ ds geo-transform))
         (await (gdal/gdal_close ds))
         (let [ds (await (gdal/gdal_open path fndefs/GA_ReadOnly))]
           (is (= geo-transform (vec (await (gdal/get_geo_transform ds)))) "GA_ReadOnly")
           (await (gdal/gdal_close ds)))))

     (deftest ^:async read-raster-band-rejects-a-missing-band
       (let [ds  (await (gdal/open_from_disk_BANG_ support/tif-path fndefs/GDAL_OF_RASTER))
             msg (await (support/rejectionMessage (gdal/read_raster_band ds 2)))]
         (is (.includes (str msg) "GDALGetRasterBand returned NULL for band 2"))
         (await (gdal/gdal_close ds))))

     (defn- ^:async warp-tiny!
       "Warp tiny.tif into `dst` with the gdalwarp `options`, and return the
        open result."
       [dst options]
       (let [src (await (gdal/open_from_disk_BANG_ support/tif-path fndefs/GDAL_OF_RASTER))]
         (try
           (await (gdal/warp_raster_BANG_ #js [src] dst options))
           (finally
             (await (gdal/gdal_close src))))))

     (deftest ^:async warp-to-each-data-type
       (doseq [[ot data-type array-type values] data-type-cases]
         (let [ds  (await (warp-tiny! (str "/vsimem/tiny-" ot ".tif") #js ["-ot" ot "-r" "near"]))
               arr (await (gdal/read_raster_band ds 1))]
           (is (= data-type (await (gdal/gdal_get_raster_data_type
                                    (await (gdal/gdal_get_raster_band ds 1)))))
               ot)
           (is (instance? array-type arr) ot)
           (is (= values (vec arr)) (str ot ": the pixel values"))
           (await (gdal/gdal_close ds)))))

     (deftest ^:async read-raster-band-names-the-types-that-it-reads
       (let [ds (await (warp-tiny! "/vsimem/tiny-Int64.tif" #js ["-ot" "Int64"]))]
         (is (.includes (await (support/rejectionMessage (gdal/read_raster_band ds 1)))
                        "GDAL data type 13 has no reader"))
         (await (gdal/gdal_close ds))))

     (deftest ^:async warp-with-no-options
       (let [ds (await (warp-tiny! "/work/no-options.tif" #js []))]
         (is (= (vec (range 256)) (vec (await (gdal/read_raster_band ds 1))))
             "the warp has the pixels of tiny.tif")
         (await (gdal/gdal_close ds))))

     (deftest ^:async warp-raster-rejects-no-source
       (doseq [srcs [#js [] 1]]
         (is (.includes (await (support/rejectionMessage
                                (gdal/warp_raster_BANG_ srcs "unused.tif" #js [])))
                        "takes a sequence of one or more source datasets"))))

     (deftest ^:async warp-reprojects
       (let [ds (await (warp-tiny! "/work/reprojected.tif" mercator-options))]
         (is (.includes (str (await (gdal/gdal_get_projection_ref ds))) "Pseudo-Mercator")
             "the warped dataset has the EPSG:3857 SRS")
         (is (< 1 (count (distinct (vec (await (gdal/read_raster_band ds 1))))))
             "the warp moves the pixels of tiny.tif")
         (await (gdal/gdal_close ds))))

     (run_tests_and_exit_BANG_ gdal/shutdown_BANG_ "net.willcohen.gdal.raster-test")))
