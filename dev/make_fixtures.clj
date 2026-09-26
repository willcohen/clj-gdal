;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

;; Writes tiny.shp, tiny.gpkg, tiny.gdb and tiny.tif in test/fixtures from
;; tiny.geojson. No test runs it. After a change, zip the tiny.shp family into
;; tiny.zip again.
;;
;;   nix develop --command clojure -M dev/make_fixtures.clj

(ns make-fixtures
  (:require [clojure.java.io :as io]
            [net.willcohen.gdal.fndefs :as fndefs]
            [net.willcohen.gdal.gdal :as gdal]))

(def fixture-dir "test/fixtures")
(def source-path (str fixture-dir "/tiny.geojson"))

;; remove-stale! deletes :stale first, because a driver fails when its target
;; exists.
(def formats
  [{:driver "ESRI Shapefile"
    :target (str fixture-dir "/tiny.shp")
    :stale (mapv #(str fixture-dir "/tiny" %) [".shp" ".shx" ".dbf" ".prj"])}
   {:driver "GPKG"
    :target (str fixture-dir "/tiny.gpkg")
    :layer "tiny"
    :stale [(str fixture-dir "/tiny.gpkg")]}
   {:driver "OpenFileGDB"
    :target (str fixture-dir "/tiny.gdb")
    :layer "tiny"
    :stale [(str fixture-dir "/tiny.gdb")]}])

(defn- remove-stale!
  [{:keys [stale]}]
  (doseq [path stale :let [f (io/file path)]]
    (run! io/delete-file (.listFiles f))
    (io/delete-file f true)))

(defn- report!
  [{:keys [target]}]
  (let [f (io/file target)]
    (if-let [children (.listFiles f)]
      (do (println " " target)
          (doseq [^java.io.File c (sort children)]
            (println "   " (.getName c) (.length c) "bytes")))
      (println " " target (.length f) "bytes"))))

(defn- write-fixture!
  [{:keys [driver target layer] :as fixture}]
  (remove-stale! fixture)
  (let [source (gdal/gdal-open-ex source-path fndefs/GDAL_OF_VECTOR nil nil nil)
        args (cond-> ["-f" driver]
               layer (into ["-nln" layer]))
        result (gdal/translate-vector! source target args)]
    (gdal/gdal-close result)
    (gdal/gdal-close source)
    (report! fixture)))

(def tif-target (str fixture-dir "/tiny.tif"))

(def wgs84-wkt
  (str "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\","
       "SPHEROID[\"WGS 84\",6378137,298.257223563]],"
       "PRIMEM[\"Greenwich\",0],"
       "UNIT[\"degree\",0.0174532925199433]]"))

(defn- check-cpl-err! [what err]
  (when-not (zero? err)
    (throw (ex-info (str what " failed") {:cpl-err err}))))

(defn- write-tiny-tif! []
  (io/delete-file tif-target true)
  (let [driver (gdal/gdal-get-driver-by-name "GTiff")
        options (gdal/build-csl-options ["TILED=NO"])
        ds (gdal/gdal-create driver tif-target 16 16 1 fndefs/GDT_Byte options)]
    (gdal/csl-destroy options)
    (gdal/set-geo-transform! ds [-180.0 22.5 0.0 90.0 0.0 -11.25])
    (check-cpl-err! "GDALSetProjection" (gdal/gdal-set-projection ds wgs84-wkt))
    (gdal/write-raster-band! ds 1 (range 256))
    (gdal/gdal-close ds)
    (report! {:target tif-target})))

(gdal/init!)

(println "Rebuilding fixtures in" fixture-dir "from" source-path)
(run! write-fixture! formats)
(write-tiny-tif!)
