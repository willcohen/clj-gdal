;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

;; A test that writes uses a GPKG in /vsimem, which each lane has. The layer t
;; has the fields d (DateTime), b (Binary), s (String) and day (Date).

(ns net.willcohen.gdal.field-types-test
  (:require
   #?@(:clj  [[clojure.test :refer [deftest is use-fixtures]]
              [net.willcohen.gdal.fndefs :as fndefs]
              [net.willcohen.gdal.gdal :as gdal]
              [net.willcohen.gdal.support :as support]]
       :cljs [[cljs.test :refer [deftest is]]
              ["../../../../../src/cljc/net/willcohen/gdal/gdal.mjs" :as gdal]
              ["../../../../../src/cljc/net/willcohen/gdal/fndefs.mjs" :as fndefs]
              ["../../../dist/test_runner.mjs"
               :refer [run_tests_and_exit_BANG_]]])))

(def ^:private shp-path "test/fixtures/tiny.shp")

(def ^:private date-time-keys [:year :month :day :hour :minute :second :tz-flag])

(def ^:private date-time
  {:year 2026 :month 9 :day 30 :hour 12 :minute 34 :second 56 :tz-flag 100})

;; read-vector-features! reads a Date or a DateTime field as its OGR string.
(def ^:private date-strings {"d" "2026/09/30 12:34:56+00" "day" "2026/09/30"})

;; OGR_F_SetFieldDateTimeEx takes the seconds as a C float, and GDAL writes
;; the milliseconds when they are not 0.
(def ^:private fractional-date-string "2026/09/30 12:34:56.789+00")

;; The fn uses get, because a squint map is not callable as a fn argument.
(defn- date-time-values [m]
  (mapv #(get m %) date-time-keys))

#?(:clj
   (do
     (use-fixtures :once support/with-backend)

     (defn- create-layer!
       "A GPKG at `path` with the layer t. Returns [dataset layer]."
       [path]
       (let [ds    (gdal/gdal-create (gdal/gdal-get-driver-by-name "GPKG") path 0 0 0
                                     fndefs/GDT_Unknown nil)
             layer (gdal/gdal-dataset-create-layer ds "t" nil fndefs/wkbPoint nil)]
         (doseq [[field-name field-type] [["d" fndefs/OFTDateTime] ["b" fndefs/OFTBinary]
                                          ["s" fndefs/OFTString] ["day" fndefs/OFTDate]]]
           (let [fd (gdal/ogr-fld-create field-name field-type)]
             (gdal/ogr-l-create-field layer fd 1)
             (gdal/ogr-fld-destroy fd)))
         [ds layer]))

     (defn- add-feature!
       "Add a feature to `layer`, and call `(set-fields! feature)` first."
       [layer set-fields!]
       (let [feature (gdal/ogr-f-create (gdal/ogr-l-get-layer-defn layer))]
         (try
           (set-fields! feature)
           (is (zero? (gdal/ogr-l-create-feature layer feature)))
           (finally
             (gdal/ogr-f-destroy feature)))))

     (defn- call-with-feature
       "Call `(f feature)` with the feature of `layer` that has the FID `fid`."
       [layer fid f]
       (let [feature (gdal/ogr-l-get-feature layer fid)]
         (try (f feature)
              (finally (gdal/ogr-f-destroy feature)))))

     (deftest date-time-binary-and-null-fields
       (let [path       "/vsimem/field-types.gpkg"
             [ds layer] (create-layer! path)]
         (try
           (add-feature! layer (fn [f]
                                 (apply gdal/ogr-f-set-field-date-time f 0 (date-time-values date-time))
                                 (apply gdal/ogr-f-set-field-date-time f 3 (date-time-values date-time))
                                 (gdal/set-field-binary! f 1 (byte-array [1 2 3 -1]))
                                 (gdal/ogr-f-set-field-string f 2 "x")
                                 (gdal/ogr-f-set-field-null f 2)))
           (call-with-feature
            layer 1
            (fn [f]
              (is (= date-time (gdal/field-date-time f 0)))
              (is (= [1 2 3 -1] (vec (gdal/field-binary f 1))))
              (is (= 1 (gdal/ogr-f-is-field-null f 2)))
              (is (nil? (gdal/field-date-time f 2)) "a null field")))
           (is (= date-strings (select-keys (:fields (first (gdal/read-vector-features! layer)))
                                            (keys date-strings))))
           (finally
             (gdal/gdal-close ds)
             (gdal/vsi-unlink path)))))

     (deftest fractional-seconds
       (let [path       "/vsimem/fractional-seconds.gpkg"
             [ds layer] (create-layer! path)
             feature    (gdal/ogr-f-create (gdal/ogr-l-get-layer-defn layer))]
         (try
           (apply gdal/ogr-f-set-field-date-time-ex feature 0
                  (date-time-values (assoc date-time :second 56.789)))
           (is (= fractional-date-string (gdal/ogr-f-get-field-as-string feature 0)))
           (finally
             (gdal/ogr-f-destroy feature)
             (gdal/gdal-close ds)
             (gdal/vsi-unlink path)))))

     (deftest set-feature-and-rollback
       (let [path       "/vsimem/rollback.gpkg"
             [ds layer] (create-layer! path)
             s-of-1     (fn [] (call-with-feature layer 1 #(gdal/ogr-f-get-field-as-string % 2)))
             set-s!     (fn [s] (call-with-feature layer 1 (fn [f]
                                                             (gdal/ogr-f-set-field-string f 2 s)
                                                             (gdal/ogr-l-set-feature layer f))))]
         (try
           (add-feature! layer #(gdal/ogr-f-set-field-string % 2 "a"))
           (is (zero? (set-s! "b")))
           (is (= "b" (s-of-1)) "OGR_L_SetFeature rewrites the feature")
           (is (zero? (gdal/gdal-dataset-start-transaction ds 0)))
           (set-s! "c")
           (is (zero? (gdal/gdal-dataset-rollback-transaction ds)))
           (is (= "b" (s-of-1)) "the rollback undoes the change")
           (finally
             (gdal/gdal-close ds)
             (gdal/vsi-unlink path)))))

     ;; tiny.dbf has name String(80) and id Integer(9).
     (deftest field-definitions
       (support/call-with-layer
        shp-path
        (fn [layer]
          (let [fdefn   (gdal/ogr-l-get-layer-defn layer)
                name-fd (gdal/ogr-fd-get-field-defn fdefn 0)
                id-fd   (gdal/ogr-fd-get-field-defn fdefn 1)]
            (is (= [80 9] [(gdal/ogr-fld-get-width name-fd) (gdal/ogr-fld-get-width id-fd)]))
            (is (= 0 (gdal/ogr-fld-get-precision id-fd)))
            (is (= fndefs/OFSTNone (gdal/ogr-fld-get-sub-type id-fd)))))))))

#?(:cljs
   (do
     (defn- ^:async create-layer!
       "As the JVM create-layer!."
       [path]
       (let [ds    (await (gdal/gdal_create (await (gdal/gdal_get_driver_by_name "GPKG")) path 0 0 0
                                            fndefs/GDT_Unknown nil))
             layer (await (gdal/gdal_dataset_create_layer ds "t" nil fndefs/wkbPoint nil))]
         (doseq [[field-name field-type] [["d" fndefs/OFTDateTime] ["b" fndefs/OFTBinary]
                                          ["s" fndefs/OFTString] ["day" fndefs/OFTDate]]]
           (let [fd (await (gdal/ogr_fld_create field-name field-type))]
             (await (gdal/ogr_l_create_field layer fd 1))
             (await (gdal/ogr_fld_destroy fd))))
         [ds layer]))

     (deftest ^:async date-time-binary-and-null-fields
       (let [path       "/vsimem/field-types.gpkg"
             [ds layer] (await (create-layer! path))
             feature    (await (gdal/ogr_f_create (await (gdal/ogr_l_get_layer_defn layer))))]
         (await (apply gdal/ogr_f_set_field_date_time feature 0 (date-time-values date-time)))
         (await (apply gdal/ogr_f_set_field_date_time feature 3 (date-time-values date-time)))
         (await (gdal/set_field_binary_BANG_ feature 1 (js/Uint8Array. #js [1 2 3 255])))
         (await (gdal/ogr_f_set_field_null feature 2))
         (is (= 0 (await (gdal/ogr_l_create_feature layer feature))))
         (await (gdal/ogr_f_destroy feature))
         (let [f  (await (gdal/ogr_l_get_feature layer 1))
               dt (await (gdal/field_date_time f 0))]
           (is (= date-time dt))
           (is (= [1 2 3 255] (vec (await (gdal/field_binary f 1)))))
           (is (= 1 (await (gdal/ogr_f_is_field_null f 2))))
           (is (identical? nil (await (gdal/field_date_time f 2))) "a null field")
           (await (gdal/ogr_f_destroy f)))
         (let [fields (.-fields (first (await (gdal/read_vector_features_BANG_ layer))))]
           (is (= date-strings (select-keys fields (keys date-strings)))))
         (await (gdal/gdal_close ds))
         (await (gdal/vsi_unlink path))))

     (deftest ^:async fractional-seconds
       (let [path       "/vsimem/fractional-seconds.gpkg"
             [ds layer] (await (create-layer! path))
             feature    (await (gdal/ogr_f_create (await (gdal/ogr_l_get_layer_defn layer))))]
         (await (apply gdal/ogr_f_set_field_date_time_ex feature 0
                       (date-time-values (assoc date-time :second 56.789))))
         (is (= fractional-date-string (await (gdal/ogr_f_get_field_as_string feature 0))))
         (await (gdal/ogr_f_destroy feature))
         (await (gdal/gdal_close ds))
         (await (gdal/vsi_unlink path))))

     (deftest ^:async field-definitions
       (let [ds      (await (gdal/open_from_disk_BANG_ shp-path fndefs/GDAL_OF_VECTOR))
             fdefn   (await (gdal/ogr_l_get_layer_defn (await (gdal/gdal_dataset_get_layer ds 0))))
             name-fd (await (gdal/ogr_fd_get_field_defn fdefn 0))
             id-fd   (await (gdal/ogr_fd_get_field_defn fdefn 1))]
         (is (= [80 9] [(await (gdal/ogr_fld_get_width name-fd)) (await (gdal/ogr_fld_get_width id-fd))]))
         (is (= 0 (await (gdal/ogr_fld_get_precision id-fd))))
         (is (= fndefs/OFSTNone (await (gdal/ogr_fld_get_sub_type id-fd))))
         (await (gdal/gdal_close ds))))

     (run_tests_and_exit_BANG_ gdal/shutdown_BANG_ "net.willcohen.gdal.field-types-test")))
