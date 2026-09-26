;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.support
  "Helpers for the JVM tests. CLJ_GDAL_BACKEND=graal selects the GraalVM
   backend, and any other value the FFI backend. Each backend runs in its own
   JVM, because the backend is process-global."
  (:require [clojure.java.io :as io]
            [net.willcohen.gdal.gdal :as gdal]
            [net.willcohen.gdal.fndefs :as fndefs]))

(def ^:private graal-lane? (= "graal" (System/getenv "CLJ_GDAL_BACKEND")))

(defonce ^:private started
  (delay (when graal-lane? (gdal/force-graal!))
         (gdal/init!)
         (when (not= graal-lane? (gdal/graal?))
           (throw (ex-info "init! did not select the backend of this lane"
                           {:graal-lane? graal-lane? :graal? (gdal/graal?)})))))

(defn with-backend
  "A :once fixture that starts the backend of this lane."
  [f]
  @started
  (f))

(def gpkg-path "test/fixtures/tiny.gpkg")
(def geojson-path "test/fixtures/tiny.geojson")
(def tif-path "test/fixtures/tiny.tif")

(defn call-with-temp-dir
  "Call `f` with a new temp directory, as a File.
   Delete the directory and its files after `f`."
  [prefix f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      prefix (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (f dir)
         (finally (run! #(.delete ^java.io.File %) (reverse (file-seq dir)))))))

(defn path-in
  "The absolute path of `file-name` in the directory `dir`."
  [dir file-name]
  (.getAbsolutePath (io/file dir file-name)))

(defn call-with-temp-path
  "Call `f` with the absolute path of `file-name` in a new temp directory.
   Delete the directory and its files after `f`."
  [file-name f]
  (call-with-temp-dir "clj-gdal" #(f (path-in % file-name))))

(defn open-dataset
  "Open `path` with the GDAL_OF_* `flags`. Throws when GDAL returns NULL."
  [path flags]
  (or (gdal/gdal-open-ex path flags nil nil nil)
      (throw (ex-info "gdal-open-ex returned NULL" {:path path :flags flags}))))

(defn open-vector [path] (open-dataset path fndefs/GDAL_OF_VECTOR))

(defn open-raster [path] (open-dataset path fndefs/GDAL_OF_RASTER))

(defn call-with-dataset
  "Call `(f ds)`, then close `ds`, also after an exception."
  [ds f]
  (try (f ds)
       (finally (gdal/gdal-close ds))))

(defn call-with-layer
  "Call `(f layer)` with layer 0 of the vector dataset at `path`. Close the
   dataset after `f`."
  [path f]
  (call-with-dataset (open-vector path) #(f (gdal/gdal-dataset-get-layer % 0))))

(defn layer-records
  "The records of layer 0 of the vector dataset at `path`."
  [path]
  (call-with-layer path gdal/read-vector-features!))

(defn translate-vector!
  "Translate the vector dataset at `src-path` into `dst`. Closes the source
   and the result."
  [src-path dst options]
  (call-with-dataset (open-vector src-path)
                     #(gdal/gdal-close (gdal/translate-vector! % dst options))))

(defn wkb-round-trip
  "The WKB, as a vector, of a geometry made from the WKB of the first
   feature of tiny.gpkg."
  []
  (let [geom (gdal/geometry-from-wkb (:wkb (first (layer-records gpkg-path))))]
    (try (vec (gdal/geometry->wkb geom))
         (finally (gdal/ogr-g-destroy-geometry geom)))))

(defn field-values
  "The values of field `field-name` in `records`, in order."
  [records field-name]
  (mapv #(get-in % [:fields field-name]) records))

(defn square-ring
  "The closed ring of the square of `side` at (x0, y0), counterclockwise."
  [x0 y0 side]
  [[x0 y0] [(+ x0 side) y0] [(+ x0 side) (+ y0 side)] [x0 (+ y0 side)] [x0 y0]])

(defn polygon-wkb
  "The little-endian WKB of a polygon with the one ring `ring`.
   `ring` is a seq of [x y] points."
  ^bytes [ring]
  (let [buf (.order (java.nio.ByteBuffer/allocate (+ 13 (* 16 (count ring))))
                    java.nio.ByteOrder/LITTLE_ENDIAN)]
    (.put buf (byte fndefs/wkbNDR))
    (.putInt buf (int fndefs/wkbPolygon))
    (.putInt buf 1)
    (.putInt buf (count ring))
    (doseq [[x y] ring]
      (.putDouble buf (double x))
      (.putDouble buf (double y)))
    (.array buf)))

(def tiny-wkbs
  "The WKB of the features a, b and c of tiny.geojson, as vectors.
   tiny.gpkg holds the same WKB."
  (mapv #(vec (polygon-wkb (square-ring % % 1))) [0 2 4]))
