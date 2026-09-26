;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.bindings-test
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
              ;; The squint-cljs copy of gdal.mjs. ex-data of a different copy
              ;; gives nil, because its ExceptionInfo is a different class.
              ["../../../../../src/cljc/net/willcohen/gdal/node_modules/squint-cljs/core.js"
               :as gdal-squint]
              ;; The ffi-wasm module of gdal.mjs, for a pool that gdal.mjs
              ;; accepts.
              ["../../../../../src/cljc/net/willcohen/gdal/node_modules/ffi-wasm/dist/ffi-wasm.mjs"
               :as ffi-wasm]
              ["./support.mjs" :as support]
              ["../../../dist/test_runner.mjs"
               :refer [run_tests_and_exit_BANG_]]])))

#?(:clj
   (do
     (use-fixtures :once support/with-backend)

     ;; Without the check, a short call throws on FFI and pads each missing
     ;; arg with 0 on GraalVM.
     (deftest a-generated-fn-checks-its-arg-count
       (is (thrown-with-msg? clojure.lang.ArityException #"gdal/gdal-open-ex"
                             (gdal/gdal-open-ex support/gpkg-path fndefs/GDAL_OF_VECTOR)))
       (is (= '([filename open-flags allowed-drivers open-options sibling-files])
              (:arglists (meta #'gdal/gdal-open-ex))))
       (is (str/includes? (:doc (meta #'gdal/gdal-open-ex)) "GDALOpenEx")))

     (deftest version-info
       (is (= "3.11.5" (gdal/gdal-version-info "RELEASE_NAME"))))

     ;; As in JS, the raw fn of a :read-result fndef reads its result and
     ;; frees what the caller owns.
     (deftest a-read-result-fn-gives-the-value
       (let [paths (gdal/stage-files! {"a.txt" "x"} "/vsimem/read-result")
             geom  (gdal/geometry-from-wkt "POINT (1 2)")]
         (try
           (is (= ["a.txt"] (gdal/vsi-read-dir "/vsimem/read-result")))
           (is (some #{"DMD_LONGNAME=GeoTIFF"}
                     (gdal/gdal-get-metadata (gdal/gdal-get-driver-by-name "GTiff") nil)))
           (is (str/includes? (gdal/ogr-g-export-to-json geom) "\"Point\""))
           (finally
             (gdal/ogr-g-destroy-geometry geom)
             (gdal/vsi-unlink (get paths "a.txt"))))))

     (deftest config-option
       (try
         (is (nil? (gdal/cpl-get-config-option "CLJ_GDAL_TEST_OPTION" nil)))
         (is (= "no" (gdal/cpl-get-config-option "CLJ_GDAL_TEST_OPTION" "no"))
             "the default of an option that is not set")
         (gdal/cpl-set-config-option "CLJ_GDAL_TEST_OPTION" "yes")
         (is (= "yes" (gdal/cpl-get-config-option "CLJ_GDAL_TEST_OPTION" nil)))
         (finally
           (gdal/cpl-set-config-option "CLJ_GDAL_TEST_OPTION" nil))))

     (deftest init-sets-gdal-data
       (is (not (str/blank? (gdal/cpl-get-config-option "GDAL_DATA" nil)))))

     ;; C takes NULL here: OSRNewSpatialReference gives an empty SRS, and a
     ;; destroy fn does nothing.
     (deftest nil-where-c-takes-null
       (let [srs (gdal/osr-new-spatial-reference nil)]
         (is (some? srs) "an empty SRS")
         (gdal/osr-destroy-spatial-reference srs))
       (is (nil? (gdal/ogr-g-destroy-geometry nil))))

     (deftest gdal-close-gives-the-cplerr
       (is (= 0 (gdal/gdal-close (support/open-vector support/gpkg-path))) "a dataset of a host path")
       (is (= 0 (gdal/gdal-close (gdal/gdal-create (gdal/gdal-get-driver-by-name "MEM")
                                                   "/vsimem/close" 1 1 1 fndefs/GDT_Byte nil)))
           "a dataset with no host path"))

     ;; FFI cannot write there, and GraalVM must fail at the open too, because
     ;; a later gdal-close loses the MEMFS write.
     (deftest a-host-path-that-is-not-writable-does-not-open-for-a-write
       (support/call-with-temp-dir
        "gdal-read-only"
        (fn [^java.io.File dir]
          (let [gpkg (io/file dir "tiny.gpkg")]
            (io/copy (io/file support/gpkg-path) gpkg)
            (.setWritable gpkg false)
            (.setWritable dir false)
            (try
              ;; No check as root, because root writes a file with no write
              ;; bit, as in the Alpine CI container.
              (when-not (.canWrite dir)
                (is (nil? (gdal/gdal-create (gdal/gdal-get-driver-by-name "GTiff")
                                            (support/path-in dir "new.tif") 1 1 1 fndefs/GDT_Byte nil))
                    "a new file in a dir that is not writable")
                (is (nil? (gdal/gdal-open-ex (.getPath gpkg)
                                             (bit-or fndefs/GDAL_OF_VECTOR fndefs/GDAL_OF_UPDATE)
                                             nil nil nil))
                    "an update open of a file that is not writable"))
              (finally (.setWritable dir true)))))))

     (deftest create-copy
       (support/call-with-temp-path
        "copy.tif"
        (fn [path]
          (support/call-with-dataset
           (support/open-raster support/tif-path)
           (fn [src]
             (let [dst (gdal/gdal-create-copy (gdal/gdal-get-driver-by-name "GTiff")
                                              path src 0 nil nil nil)]
               (is (some? dst) "gdal-create-copy returned a dataset")
               (gdal/gdal-close dst))
             (support/call-with-dataset
              (support/open-raster path)
              #(is (= (vec (gdal/read-raster-band src 1)) (vec (gdal/read-raster-band % 1)))
                   "the copy has the pixels of tiny.tif")))))))

     ;; GDAL deletes the files of the old dataset first, and on GraalVM
     ;; gdal-close must delete them on the host too.
     (deftest create-copy-over-a-dataset-deletes-its-old-sidecars
       (support/call-with-temp-dir
        "gdal-overwrite"
        (fn [dir]
          (let [path  (support/path-in dir "out.tif")
                aux   (io/file dir "out.tif.aux.xml")
                copy! #(gdal/gdal-close (gdal/gdal-create-copy (gdal/gdal-get-driver-by-name "GTiff")
                                                               path % 0 nil nil nil))]
            (support/call-with-dataset
             (support/open-raster support/tif-path)
             (fn [src]
               (copy! src)
               (spit aux "<PAMDataset><Metadata><MDI key=\"OLD\">1</MDI></Metadata></PAMDataset>")
               (copy! src)))
            (is (not (.exists aux)))))))

     (deftest build-csl-options
       (is (nil? (gdal/build-csl-options [])))
       (is (nil? (gdal/build-csl-options nil)))
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a sequence of strings"
                             (gdal/build-csl-options ["-t_srs" nil])))
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a sequence of strings"
                             (gdal/build-csl-options "-of GTiff"))
           "a string is not a sequence of options"))

     (deftest geometry-from-wkb-round-trip
       (is (= (first support/tiny-wkbs) (support/wkb-round-trip))))

     (deftest helper-errors-carry-their-data
       (let [ds   (gdal/gdal-create (gdal/gdal-get-driver-by-name "MEM") "" 1 1 1 fndefs/GDT_Byte nil)
             data (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))]
         (try
           (is (= 3 (:err (data #(gdal/get-geo-transform ds)))) "the CPLErr of a failed call")
           (is (= 1 (:src-count (data #(gdal/warp-raster! [ds] "/vsimem/no-driver.tif"
                                                          ["-of" "NoSuchFormat"]))))
               "the source count of an app that returns NULL")
           (finally (gdal/gdal-close ds)))))

     (deftest helpers-throw-with-the-gdal-message
       (support/call-with-temp-dir
        "gdal-failure"
        (fn [dir]
          (let [gpkg (support/open-vector support/gpkg-path)
                tif  (support/open-raster support/tif-path)
                json (support/path-in dir "out.json")
                out  (support/path-in dir "out.tif")]
            (try
              (doseq [[what expected f]
                      [["translate-vector! -f" "NoSuchFormat"
                        #(gdal/translate-vector! gpkg json ["-f" "NoSuchFormat"])]
                       ["translate-vector! option" "-nosuchoption"
                        #(gdal/translate-vector! gpkg json ["-nosuchoption"])]
                       ["warp-raster! option" "-nosuchoption"
                        #(gdal/warp-raster! [tif] out ["-nosuchoption"])]]]
                (let [e (try (f) (ex-info "no exception" {})
                             (catch clojure.lang.ExceptionInfo e e))]
                  (is (str/includes? (str (ex-message e)) expected)
                      (str what ": the message has the GDAL error message"))
                  (is (str/includes? (str (:cpl-msg (ex-data e))) expected)
                      (str what ": the data has the GDAL error message"))
                  (is (zero? (long (gdal/cpl-get-last-error-no)))
                      (str what ": the helper resets the GDAL error"))))
              ;; GDALWarp prints this error and calls no CPLError.
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"GDALWarp returned NULL"
                                    (gdal/warp-raster! [tif] out ["-of" "NoSuchFormat"])))
              (finally
                (gdal/gdal-close tif)
                (gdal/gdal-close gpkg)))))))

     ;; Each of these fails with no CPLError of its own.
     (deftest a-helper-failure-shows-no-earlier-gdal-error
       (let [drv (gdal/gdal-get-driver-by-name "MEM")
             ds  (gdal/gdal-create drv "" 1 1 1 fndefs/GDT_Byte nil)
             srs (gdal/osr-new-spatial-reference nil)]
         (try
           (doseq [[what f] [["get-geo-transform" #(gdal/get-geo-transform ds)]
                             ["geometry-from-wkb" #(gdal/geometry-from-wkb (byte-array [1 2 3]))]
                             ["srs-export-to-wkt" #(gdal/srs-export-to-wkt srs)]
                             ["warp-raster! -of" #(gdal/warp-raster! [ds] "/vsimem/stale.tif"
                                                                     ["-of" "NoSuchFormat"])]]]
             (gdal/cpl-error-set-state fndefs/CE_Failure 1 "an earlier error")
             (let [msg (try (f) "no exception"
                            (catch clojure.lang.ExceptionInfo e (ex-message e)))]
               (is (and (not= "no exception" msg) (not (str/includes? msg "an earlier error")))
                   (str what ": " msg))))
           (finally
             (gdal/cpl-error-reset)
             (gdal/osr-destroy-spatial-reference srs)
             (gdal/gdal-close ds)))))))

#?(:cljs
   (do
     (deftest ^:async a-generated-fn-checks-its-arg-count
       (is (= "GDALOpenEx takes 5 args, not 2"
              (await (support/rejectionMessage
                      (gdal/gdal_open_ex support/gpkg-path fndefs/GDAL_OF_VECTOR))))))

     (deftest ^:async version-info
       (is (= "3.11.5" (await (gdal/gdal_version_info "RELEASE_NAME")))))

     (defn- ^:async pool-config-option
       "The value of the config option `k` in the gdal module of `the-pool`,
        read with a ccall that does not go through gdal.mjs."
       [the-pool k]
       (await (ffi-wasm/worker_call the-pool :net.willcohen.gdal "ccall"
                                    #js ["CPLGetConfigOption" "string" #js ["string" "string"]
                                         #js [k nil]]
                                    0)))

     ;; A config option is in the module of one worker. Thus a value that
     ;; gdal.mjs sets and the caller pool reads shows where the call ran.
     (deftest ^:async a-caller-pool-runs-the-gdal-calls
       ;; init! ignores :pool while gdal-wasm has a pool of its own.
       (await (gdal/shutdown_BANG_))
       (let [registry (ffi-wasm/init_workload_pool_BANG_ {:size 1})]
         (ffi-wasm/register_handler_BANG_ registry :compute :net.willcohen.gdal
                                          (await (gdal/handler_spec)))
         (let [the-pool (await (ffi-wasm/ensure_pool_BANG_ registry))]
           (try
             (await (gdal/init_BANG_ {:pool the-pool}))
             (await (gdal/cpl_set_config_option "CLJ_GDAL_POOL_TEST" "caller"))
             (is (= "caller" (await (pool-config-option the-pool "CLJ_GDAL_POOL_TEST")))
                 "gdal.mjs calls run in the caller pool")
             (await (gdal/shutdown_BANG_))
             (is (= "caller" (await (pool-config-option the-pool "CLJ_GDAL_POOL_TEST")))
                 "shutdown! leaves the caller pool running")
             (finally
               (await (ffi-wasm/shutdown_pool_BANG_ registry)))))))

     (deftest ^:async build-csl-options
       (is (nil? (await (gdal/build_csl_options #js []))))
       (is (nil? (await (gdal/build_csl_options nil))))
       (let [msg (await (support/rejectionMessage (gdal/build_csl_options #js ["-t_srs" nil])))]
         (is (.includes (str msg) "a sequence of strings")))
       ;; squint iterates the chars of a string, and each char is a string.
       (let [msg (await (support/rejectionMessage (gdal/build_csl_options "-of GTiff")))]
         (is (.includes (str msg) "a sequence of strings") "a string is not a sequence of options")))

     (deftest ^:async config-option
       (is (= "" (await (gdal/cpl_get_config_option "CLJ_GDAL_TEST_OPTION" nil)))
           "an option that is not set reads as \"\" in JavaScript")
       (is (= "no" (await (gdal/cpl_get_config_option "CLJ_GDAL_TEST_OPTION" "no")))
           "the default of an option that is not set")
       (await (gdal/cpl_set_config_option "CLJ_GDAL_TEST_OPTION" "yes"))
       (is (= "yes" (await (gdal/cpl_get_config_option "CLJ_GDAL_TEST_OPTION" nil))))
       (await (gdal/cpl_set_config_option "CLJ_GDAL_TEST_OPTION" nil)))

     (deftest ^:async loader-sets-gdal-data
       (is (= "/gdal" (await (gdal/cpl_get_config_option "GDAL_DATA" nil)))))

     ;; GDAL warns one time for each module, thus the test starts a new one.
     (deftest ^:async no-gdal-data-warning
       (await (gdal/shutdown_BANG_))
       (let [drv (await (gdal/gdal_get_driver_by_name "GPKG"))
             ds  (await (gdal/gdal_create drv "/tmp/no_warning.gpkg" 0 0 0
                                          fndefs/GDT_Unknown nil))]
         (is ds "GDALCreate made the GPKG")
         (is (not (.includes (str (await (gdal/cpl_get_last_error_msg))) "GDAL_DATA"))
             "no GDAL_DATA warning at a GPKG create")
         (await (gdal/gdal_close ds))))

     (deftest ^:async create-copy
       (let [src (await (gdal/open_from_disk_BANG_ support/tif-path fndefs/GDAL_OF_RASTER))
             drv (await (gdal/gdal_get_driver_by_name "GTiff"))
             dst (await (gdal/gdal_create_copy drv "/work/copy.tif" src 0 nil nil nil))]
         (is dst "gdal-create-copy returned a dataset")
         (is (= 0 (await (gdal/gdal_close dst))) "gdal-close gives the CPLErr")
         (let [ds (await (gdal/gdal_open_ex "/work/copy.tif" fndefs/GDAL_OF_RASTER nil nil nil))]
           (is (= (await (gdal/read_raster_band src 1))
                  (await (gdal/read_raster_band ds 1)))
               "the copy has the pixels of tiny.tif")
           (await (gdal/gdal_close ds)))
         (await (gdal/gdal_close src))))

     (defn- ^:async check-failure! [what expected f]
       (let [e (await (.then (f) (constantly nil) identity))]
         (is (.includes (str (some-> e .-message)) expected)
             (str what ": the message has the GDAL error message"))
         (is (.includes (str (some-> (gdal-squint/ex_data e) (aget "cpl-msg"))) expected)
             (str what ": the data has the GDAL error message"))
         (is (= 0 (await (gdal/cpl_get_last_error_no)))
             (str what ": the helper resets the GDAL error"))))

     (defn- ^:async error-data
       "The ex-data of the rejection of the promise `p`."
       [p]
       (gdal-squint/ex_data (await (.then p (constantly nil) identity))))

     (deftest ^:async helper-errors-carry-their-data
       (let [drv (await (gdal/gdal_get_driver_by_name "MEM"))
             ds  (await (gdal/gdal_create drv "" 1 1 1 fndefs/GDT_Byte nil))]
         (is (= 3 (some-> (await (error-data (gdal/get_geo_transform ds))) (aget "err")))
             "the CPLErr of a failed call")
         (is (= 1 (some-> (await (error-data (gdal/warp_raster_BANG_
                                              #js [ds] "/work/no-driver.tif"
                                              #js ["-of" "NoSuchFormat"])))
                          (aget "src-count")))
             "the source count of an app that returns NULL")
         (await (gdal/gdal_close ds))))

     (deftest ^:async helpers-throw-with-the-gdal-message
       (let [gpkg (await (gdal/open_from_disk_BANG_ support/gpkg-path fndefs/GDAL_OF_VECTOR))
             tif  (await (gdal/open_from_disk_BANG_ support/tif-path fndefs/GDAL_OF_RASTER))]
         (await (check-failure! "translate-vector! -f" "NoSuchFormat"
                                #(gdal/translate_vector_BANG_
                                  gpkg "/work/out.json" #js ["-f" "NoSuchFormat"])))
         (await (check-failure! "translate-vector! option" "-nosuchoption"
                                #(gdal/translate_vector_BANG_
                                  gpkg "/work/out.json" #js ["-nosuchoption"])))
         (await (check-failure! "warp-raster! option" "-nosuchoption"
                                #(gdal/warp_raster_BANG_
                                  #js [tif] "/work/out.tif" #js ["-nosuchoption"])))
         ;; GDALWarp prints this error and calls no CPLError.
         (is (.includes (await (support/rejectionMessage
                                (gdal/warp_raster_BANG_ #js [tif] "/work/out.tif"
                                                        #js ["-of" "NoSuchFormat"])))
                        "GDALWarp returned NULL"))
         (await (gdal/gdal_close tif))
         (await (gdal/gdal_close gpkg))))

     ;; Each of these fails with no CPLError of its own.
     (deftest ^:async a-helper-failure-shows-no-earlier-gdal-error
       (let [drv (await (gdal/gdal_get_driver_by_name "MEM"))
             ds  (await (gdal/gdal_create drv "" 1 1 1 fndefs/GDT_Byte nil))
             srs (await (gdal/osr_new_spatial_reference nil))]
         (doseq [[what f] [["get-geo-transform" #(gdal/get_geo_transform ds)]
                           ["geometry-from-wkb" #(gdal/geometry_from_wkb (js/Uint8Array. #js [1 2 3]))]
                           ["srs-export-to-wkt" #(gdal/srs_export_to_wkt srs)]
                           ["warp-raster! -of" #(gdal/warp_raster_BANG_ #js [ds] "/work/stale.tif"
                                                                         #js ["-of" "NoSuchFormat"])]]]
           (await (gdal/cpl_error_set_state fndefs/CE_Failure 1 "an earlier error"))
           (let [msg (await (support/rejectionMessage (f)))]
             (is (and (not= "no error" msg) (not (.includes (str msg) "an earlier error")))
                 (str what ": " msg))))
         (await (gdal/cpl_error_reset))
         (await (gdal/osr_destroy_spatial_reference srs))
         (await (gdal/gdal_close ds))))

     (run_tests_and_exit_BANG_ gdal/shutdown_BANG_ "net.willcohen.gdal.bindings-test")))
