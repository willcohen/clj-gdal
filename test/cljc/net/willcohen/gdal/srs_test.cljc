;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.srs-test
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

;; WGS 84 as WKT1 with no AUTHORITY node, for OSRAutoIdentifyEPSG. Without
;; the AXIS nodes of EPSG:4326, the fn returns OGRERR_UNSUPPORTED_SRS.
(def ^:private wgs84-wkt-without-authority
  (str "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]],"
       "PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433],"
       "AXIS[\"Latitude\",NORTH],AXIS[\"Longitude\",EAST]]"))

;; Longitude 1 at the equator is 111319.49... m east in EPSG:3857.
(def ^:private mercator-x-of-1 111319.49079327357)

(defn- close? [a b] (< -1e-6 (- a b) 1e-6))

(def ^:private utm-31n-proj "+proj=utm +zone=31 +datum=WGS84 +units=m +no_defs")

#?(:clj
   (do
     (use-fixtures :once support/with-backend)

     (defn- epsg-srs [code]
       (doto (gdal/osr-new-spatial-reference "")
         (gdal/osr-import-from-epsg code)))

     (deftest srs-predicates-names-and-clones
       (let [wgs84 (epsg-srs 4326)
             merc  (epsg-srs 3857)
             clone (gdal/osr-clone wgs84)]
         (try
           (is (= [1 0] [(gdal/osr-is-geographic wgs84) (gdal/osr-is-projected wgs84)]))
           (is (= [0 1] [(gdal/osr-is-geographic merc) (gdal/osr-is-projected merc)]))
           (is (= 1 (gdal/osr-is-same wgs84 clone)))
           (is (= 0 (gdal/osr-is-same wgs84 merc)))
           (is (= "WGS 84" (gdal/osr-get-name wgs84)))
           (finally
             (run! gdal/osr-destroy-spatial-reference [wgs84 merc clone])))))

     (deftest srs-export-to-wkt2
       (let [srs (epsg-srs 4326)]
         (try
           (is (str/starts-with? (gdal/srs-export-to-wkt srs) "GEOGCS["))
           (is (str/starts-with? (gdal/srs-export-to-wkt srs ["FORMAT=WKT2_2019"]) "GEOGCRS["))
           (finally
             (gdal/osr-destroy-spatial-reference srs)))))

     (deftest srs-export
       (let [srs (epsg-srs 3857)]
         (try
           (is (re-find #"\"type\"\s*:\s*\"ProjectedCRS\"" (gdal/srs-export-to-projjson srs)))
           (is (= ["EPSG" "3857"] [(gdal/osr-get-authority-name srs nil)
                                   (gdal/osr-get-authority-code srs nil)])
               "a nil target key reads the root SRS")
           (is (= "4326" (gdal/osr-get-authority-code srs "GEOGCS"))
               "a target key reads a node of the SRS")
           (finally
             (gdal/osr-destroy-spatial-reference srs)))))

     ;; On failure, OSRExportToWkt still writes CPLStrdup("") to the
     ;; out-param (ogrspatialreference.cpp).
     (deftest srs-export-frees-the-string-of-a-failure
       (let [srs   (gdal/osr-new-spatial-reference nil)
             free  gdal/vsi-free
             freed (atom 0)]
         (try
           (with-redefs [gdal/vsi-free (fn [p] (swap! freed inc) (free p))]
             (is (thrown-with-msg? clojure.lang.ExceptionInfo #"OSRExportToWkt failed"
                                   (gdal/srs-export-to-wkt srs))
                 "an empty SRS has no WKT"))
           (is (= 1 @freed) "the helper frees the string")
           (finally (gdal/osr-destroy-spatial-reference srs)))))

     (deftest auto-identify-epsg
       (let [srs (gdal/osr-new-spatial-reference wgs84-wkt-without-authority)]
         (try
           (is (nil? (gdal/osr-get-authority-code srs nil)) "no authority yet")
           (is (zero? (gdal/osr-auto-identify-epsg srs)))
           (is (= "4326" (gdal/osr-get-authority-code srs nil)))
           (finally
             (gdal/osr-destroy-spatial-reference srs)))))

     (deftest transform-points-to-web-mercator
       (let [wgs84 (epsg-srs 4326)
             merc  (epsg-srs 3857)]
         (gdal/osr-set-axis-mapping-strategy wgs84 fndefs/OAMS_TRADITIONAL_GIS_ORDER)
         (let [oct (gdal/oct-new-coordinate-transformation wgs84 merc)]
           (try
             (let [{:keys [xs ys]} (gdal/transform-points oct [0 1] [0 0])]
               (is (= [true true true true] (mapv close? [0 mercator-x-of-1 0 0] (concat xs ys)))))
             (is (thrown-with-msg? clojure.lang.ExceptionInfo #"OCTTransform failed"
                                   (gdal/transform-points oct [0] [91]))
                 "latitude 91 has no Mercator y")
             (finally
               (gdal/oct-destroy-coordinate-transformation oct)
               (run! gdal/osr-destroy-spatial-reference [wgs84 merc]))))))

     (deftest srs-from-user-input
       (let [utm  (gdal/osr-new-spatial-reference "")
             proj (gdal/osr-new-spatial-reference "")]
         (try
           (is (zero? (gdal/osr-set-from-user-input utm "EPSG:32631")))
           (is (= "32631" (gdal/osr-get-authority-code utm nil)))
           (is (zero? (gdal/osr-set-from-user-input proj utm-31n-proj)))
           (is (= 1 (gdal/osr-is-same utm proj)) "the PROJ string gives EPSG:32631")
           (finally
             (run! gdal/osr-destroy-spatial-reference [utm proj])))))))

#?(:cljs
   (do
     (defn- ^:async epsg-srs [code]
       (let [srs (await (gdal/osr_new_spatial_reference ""))]
         (await (gdal/osr_import_from_epsg srs code))
         srs))

     (deftest ^:async srs-predicates-names-and-clones
       (let [wgs84 (await (epsg-srs 4326))
             merc  (await (epsg-srs 3857))
             clone (await (gdal/osr_clone wgs84))]
         (is (= 1 (await (gdal/osr_is_geographic wgs84))))
         (is (= 1 (await (gdal/osr_is_projected merc))))
         (is (= 1 (await (gdal/osr_is_same wgs84 clone))))
         (is (= 0 (await (gdal/osr_is_same wgs84 merc))))
         (is (= "WGS 84" (await (gdal/osr_get_name wgs84))))
         (doseq [srs [wgs84 merc clone]]
           (await (gdal/osr_destroy_spatial_reference srs)))))

     (deftest ^:async srs-export-to-wkt2
       (let [srs (await (epsg-srs 4326))]
         (is (.startsWith (await (gdal/srs_export_to_wkt srs)) "GEOGCS["))
         (is (.startsWith (await (gdal/srs_export_to_wkt srs #js ["FORMAT=WKT2_2019"])) "GEOGCRS["))
         (await (gdal/osr_destroy_spatial_reference srs))))

     (deftest ^:async auto-identify-epsg
       (let [srs (await (gdal/osr_new_spatial_reference wgs84-wkt-without-authority))]
         (is (= 0 (await (gdal/osr_auto_identify_epsg srs))))
         (is (= "4326" (await (gdal/osr_get_authority_code srs nil))))
         (await (gdal/osr_destroy_spatial_reference srs))))

     (deftest ^:async transform-points-to-web-mercator
       (let [wgs84 (await (epsg-srs 4326))
             merc  (await (epsg-srs 3857))
             _     (await (gdal/osr_set_axis_mapping_strategy wgs84 fndefs/OAMS_TRADITIONAL_GIS_ORDER))
             oct   (await (gdal/oct_new_coordinate_transformation wgs84 merc))
             r     (await (gdal/transform_points oct #js [0 1] #js [0 0]))
             msg   (await (support/rejectionMessage (gdal/transform_points oct #js [0] #js [91])))]
         (is (= [true true true true]
                (mapv close? [0 mercator-x-of-1 0 0] (concat (.-xs r) (.-ys r)))))
         (is (.includes (str msg) "OCTTransform failed") "latitude 91 has no Mercator y")
         (await (gdal/oct_destroy_coordinate_transformation oct))
         (doseq [srs [wgs84 merc]]
           (await (gdal/osr_destroy_spatial_reference srs)))))

     (deftest ^:async srs-from-user-input
       (let [utm  (await (gdal/osr_new_spatial_reference ""))
             proj (await (gdal/osr_new_spatial_reference ""))]
         (is (= 0 (await (gdal/osr_set_from_user_input utm "EPSG:32631"))))
         (is (= "32631" (await (gdal/osr_get_authority_code utm nil))))
         (is (= 0 (await (gdal/osr_set_from_user_input proj utm-31n-proj))))
         (is (= 1 (await (gdal/osr_is_same utm proj))) "the PROJ string gives EPSG:32631")
         (doseq [srs [utm proj]]
           (await (gdal/osr_destroy_spatial_reference srs)))))

     (run_tests_and_exit_BANG_ gdal/shutdown_BANG_ "net.willcohen.gdal.srs-test")))
