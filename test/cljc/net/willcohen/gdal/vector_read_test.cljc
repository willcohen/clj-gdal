;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.vector-read-test
  (:require
   #?@(:clj  [[clojure.java.io :as io]
              [clojure.test :refer [deftest is use-fixtures]]
              [net.willcohen.gdal.gdal :as gdal]
              [net.willcohen.gdal.support :as support]]
       :cljs [[cljs.test :refer [deftest is]]
              ["node:fs" :as fs]
              ["node:os" :as os]
              ["node:path" :as path]
              ["../../../../../src/cljc/net/willcohen/gdal/gdal.mjs" :as gdal]
              ["../../../../../src/cljc/net/willcohen/gdal/fndefs.mjs" :as fndefs]
              ["./support.mjs" :as support]
              ["../../../dist/test_runner.mjs"
               :refer [run_tests_and_exit_BANG_]]])))

;; tiny.shp and its sidecar files.
(def ^:private zip-path "test/fixtures/tiny.zip")

;; Feature "n" has no geometry.
(def ^:private null-geometry-geojson
  (str "{\"type\":\"FeatureCollection\",\"features\":["
       "{\"type\":\"Feature\",\"properties\":{\"name\":\"a\"},"
       "\"geometry\":{\"type\":\"Polygon\","
       "\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}},"
       "{\"type\":\"Feature\",\"properties\":{\"name\":\"n\"},\"geometry\":null}"
       "]}"))

;; Feature "u" has no n or t property, and GDAL leaves those fields unset.
;; Feature "z" has JSON nulls, and GDAL sets those fields to null.
(def ^:private unset-fields-geojson
  (str "{\"type\":\"FeatureCollection\",\"features\":["
       "{\"type\":\"Feature\",\"properties\":{\"name\":\"s\",\"n\":1,\"t\":\"x\"},\"geometry\":null},"
       "{\"type\":\"Feature\",\"properties\":{\"name\":\"u\"},\"geometry\":null},"
       "{\"type\":\"Feature\",\"properties\":{\"name\":\"z\",\"n\":null,\"t\":null},\"geometry\":null}"
       "]}"))

(def ^:private unset-fields
  [{"name" "s" "n" 1 "t" "x"} {"name" "u" "n" nil "t" nil} {"name" "z" "n" nil "t" nil}])

#?(:clj
   (do
     (use-fixtures :once support/with-backend)

     (deftest an-unset-field-reads-as-nil
       (let [path (get (gdal/stage-files! {"unset.geojson" unset-fields-geojson} nil)
                       "unset.geojson")]
         (is (= unset-fields (mapv :fields (support/layer-records path))))))

     (deftest read-vector-features-reads-the-schema-one-time
       (let [n       (atom 0)
             read    gdal/ogr-fd-get-field-defn
             records (with-redefs [gdal/ogr-fd-get-field-defn
                                   (fn [& args] (swap! n inc) (apply read args))]
                       (support/layer-records support/gpkg-path))]
         (is (= (count (:fields (first records))) @n)
             "one read for each field of the layer, and not for each feature")))

     (deftest gpkg-features
       (let [records (support/layer-records support/gpkg-path)]
         (is (= ["a" "b" "c"] (support/field-values records "name")))
         (is (= support/tiny-wkbs (mapv (comp vec :wkb) records))
             "the little-endian polygon WKB of tiny.geojson")))

     ;; GraalVM reads a nil arg as 0. A filtered GeoJSON layer counts -1 with
     ;; force 0.
     (deftest no-force-gives-force-1
       (support/call-with-layer
        support/geojson-path
        (fn [layer]
          (gdal/ogr-l-set-attribute-filter layer "name = 'b'")
          (is (= -1 (gdal/layer-feature-count layer 0)))
          (is (= 1 (gdal/layer-feature-count layer))))))

     (deftest null-geometry-reads-as-nil-wkb
       (let [path  (get (gdal/stage-files! {"null.geojson" null-geometry-geojson}) "null.geojson")
             [a n] (support/layer-records path)]
         (is (= (first support/tiny-wkbs) (vec (:wkb a))))
         (is (= "n" (get-in n [:fields "name"])))
         (is (nil? (:wkb n)) "a feature without geometry has :wkb nil")))

     (deftest stage-files-writes-below-vsimem
       (let [staged (gdal/stage-files! {"staged.geojson" (slurp support/geojson-path)
                                        "empty.bin"      (byte-array 0)})]
         (is (= {"staged.geojson" "/vsimem/work/staged.geojson"
                 "empty.bin"      "/vsimem/work/empty.bin"}
                staged)
             "no dir gives /vsimem/work")
         (is (= ["a" "b" "c"]
                (support/field-values (support/layer-records (get staged "staged.geojson")) "name")))))

     ;; GDAL takes the buffer only when VSIFileFromMemBuffer succeeds.
     (deftest stage-files-frees-the-buffer-that-gdal-does-not-take
       (let [freed (atom 0)
             free  gdal/vsi-free]
         (with-redefs [gdal/vsi-file-from-mem-buffer (constantly nil)
                       gdal/vsi-free (fn [p] (swap! freed inc) (free p))]
           (is (thrown-with-msg? clojure.lang.ExceptionInfo #"VSIFileFromMemBuffer failed"
                                 (gdal/stage-files! {"refused.bin" (byte-array 1)}))))
         (is (= 1 @freed))))

     (deftest vsi-unlink-deletes-a-staged-file
       (let [path (get (gdal/stage-files! {"gone.bin" (byte-array 1)}) "gone.bin")]
         (is (= [0 -1] [(gdal/vsi-unlink path) (gdal/vsi-unlink path)])
             "the second unlink finds no file")))

     (deftest stage-files-of-a-directory-dataset
       (let [files (into {} (map (fn [^java.io.File f]
                                   [(.getName f) (java.nio.file.Files/readAllBytes (.toPath f))]))
                         (.listFiles (io/file "test/fixtures/tiny.gdb")))]
         (gdal/stage-files! files "/vsimem/staged/tiny.gdb")
         (is (= #{"a" "b" "c"}
                (set (support/field-values (support/layer-records "/vsimem/staged/tiny.gdb")
                                           "name"))))))

     (deftest stage-files-writes-only-below-vsimem
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"below /vsimem/"
                             (gdal/stage-files! {"a.bin" (byte-array 1)} "/work"))))

     (deftest read-a-vsimem-file-back
       (let [path (get (gdal/stage-files! {"a.bin" (byte-array [1 2 3 -1])} "/vsimem/readback")
                       "a.bin")]
         (is (= [1 2 3 -1] (vec (gdal/read-vsimem-file path))))
         (is (= ["a.bin"] (gdal/read-vsi-dir "/vsimem/readback")))
         (is (nil? (gdal/read-vsimem-file "/vsimem/readback/missing.bin")))
         (is (zero? (gdal/vsi-unlink path)))))

     (deftest read-an-empty-vsimem-file
       (let [path (get (gdal/stage-files! {"empty.bin" (byte-array 0)} "/vsimem/empty")
                       "empty.bin")
             bs   (gdal/read-vsimem-file path)]
         (is (some? bs) "an empty file is not a missing file")
         (is (zero? (alength ^bytes bs)))
         (is (zero? (gdal/vsi-unlink path)))))

     ;; read-vector-features! destroys the feature before the caller gets the
     ;; exception, thus the ex-data names the FID and holds no pointer.
     (deftest a-failed-wkb-export-names-the-fid
       (let [data (with-redefs [gdal/ogr-g-export-to-wkb (constantly {:result 6})]
                    (try (support/layer-records support/gpkg-path)
                         nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e))))]
         (is (= 1 (:fid data)))
         (is (not-any? #(contains? data %) [:feature :geometry]))))

     ;; The test reads only the names, because OpenFileGDB keeps each polygon
     ;; as a MultiPolygon. The shapefile keeps them in tiny.dbf. One path is
     ;; absolute and one is relative.
     (deftest host-paths
       (doseq [path [(support/path-in "test/fixtures" "tiny.shp")
                     "test/fixtures/tiny.gdb"]]
         (is (= #{"a" "b" "c"} (set (support/field-values (support/layer-records path) "name")))
             (str "the names of " path))))

     ;; README.md: on GraalVM, a host file in a /vsizip/ path does not open.
     (deftest vsizip-of-a-host-file
       (let [path (str "/vsizip/" (support/path-in "test/fixtures" "tiny.zip") "/tiny.shp")]
         (if (gdal/graal?)
           (is (thrown? clojure.lang.ExceptionInfo (support/open-vector path)))
           (is (= ["a" "b" "c"] (support/field-values (support/layer-records path) "name"))))))

     (deftest vsizip-of-a-vsimem-file
       (let [zip (get (gdal/stage-files! {"tiny.zip" (java.nio.file.Files/readAllBytes
                                                      (.toPath (io/file zip-path)))}
                                         "/vsimem/zip")
                      "tiny.zip")]
         (try
           (is (= ["a" "b" "c"]
                  (support/field-values (support/layer-records (str "/vsizip/" zip "/tiny.shp"))
                                        "name")))
           (finally
             (gdal/vsi-unlink zip)))))))

#?(:cljs
   (do
     (defn- ^:async layer-records
       "The records of layer 0 of `ds`."
       [ds]
       (await (gdal/read_vector_features_BANG_ (await (gdal/gdal_dataset_get_layer ds 0)))))

     (defn- ^:async layer-names
       "The sorted names of the features of layer 0 of `ds`."
       [ds]
       (.sort (support/fieldValues (await (layer-records ds)) "name")))

     (deftest ^:async gpkg-features
       (let [ds      (await (gdal/open_from_disk_BANG_ support/gpkg-path fndefs/GDAL_OF_VECTOR))
             records (await (layer-records ds))]
         (is (= ["a" "b" "c"] (support/fieldValues records "name")))
         (is (= (mapv #(vec (support/squareWkb % % 1)) [0 2 4]) (mapv #(vec (.-wkb %)) records))
             "the little-endian polygon WKB of tiny.geojson")
         (await (gdal/gdal_close ds))))

     (deftest ^:async no-force-gives-force-1
       (let [ds    (await (gdal/open_from_disk_BANG_ support/geojson-path fndefs/GDAL_OF_VECTOR))
             layer (await (gdal/gdal_dataset_get_layer ds 0))]
         (await (gdal/ogr_l_set_attribute_filter layer "name = 'b'"))
         (is (= -1 (js/Number (await (gdal/layer_feature_count layer 0)))))
         (is (= 1 (js/Number (await (gdal/layer_feature_count layer)))))
         (await (gdal/gdal_close ds))))

     (deftest ^:async open-from-disk-copies-sidecars-and-directories
       (doseq [path ["test/fixtures/tiny.shp" "test/fixtures/tiny.gdb"]]
         (let [ds (await (gdal/open_from_disk_BANG_ path fndefs/GDAL_OF_VECTOR))]
           (is (= ["a" "b" "c"] (await (layer-names ds))) (str "the names of " path))
           (await (gdal/gdal_close ds)))))

     (defn- ^:async call-with-temp-dir
       "As the JVM support/call-with-temp-dir, with no prefix."
       [f]
       (let [dir (.mkdtempSync fs (.join path (.tmpdir os) "gdal-node-"))]
         (try (await (f dir))
              (finally (.rmSync fs dir #js {:recursive true})))))

     ;; A shapefile and its sidecars, each a symlink. The names are in the .dbf.
     (deftest ^:async open-from-disk-follows-symlinks
       (await (call-with-temp-dir
               (fn ^:async links [dir]
                 (doseq [ext ["shp" "shx" "dbf" "prj"]]
                   (.symlinkSync fs (.resolve path (str "test/fixtures/tiny." ext))
                                 (.join path dir (str "tiny." ext))))
                 (let [ds (await (gdal/open_from_disk_BANG_ (.join path dir "tiny.shp")
                                                            fndefs/GDAL_OF_VECTOR))]
                   (is (= ["a" "b" "c"] (await (layer-names ds))))
                   (await (gdal/gdal_close ds)))))))

     ;; The second x.gpkg is not a GeoPackage. Its copy must not replace the
     ;; bytes that the open first dataset reads.
     (deftest ^:async open-from-disk-of-two-files-with-one-name
       (await (call-with-temp-dir
               (fn ^:async two-dirs [dir]
                 (let [a (.join path dir "a")
                       b (.join path dir "b")]
                   (.mkdirSync fs a)
                   (.mkdirSync fs b)
                   (.copyFileSync fs support/gpkg-path (.join path a "x.gpkg"))
                   (.writeFileSync fs (.join path b "x.gpkg") "not a GeoPackage")
                   (let [ds (await (gdal/open_from_disk_BANG_ (.join path a "x.gpkg")
                                                              fndefs/GDAL_OF_VECTOR))]
                     (await (.catch (gdal/open_from_disk_BANG_ (.join path b "x.gpkg")
                                                               fndefs/GDAL_OF_VECTOR)
                                    (constantly nil)))
                     (is (= ["a" "b" "c"] (await (layer-names ds))))
                     (await (gdal/gdal_close ds))))))))

     (deftest ^:async open-from-disk-throws-when-gdal-cannot-open
       (let [msg (await (support/rejectionMessage
                         (gdal/open_from_disk_BANG_ "test/fixtures/tiny.tif" fndefs/GDAL_OF_VECTOR)))]
         (is (.includes (str msg) (str "GDALOpenEx returned NULL for /work"
                                       (.resolve path "test/fixtures/tiny.tif")))
             "a raster has no vector layer")))

     (deftest ^:async stage-files-default-dir
       (let [staged (await (gdal/stage_files_BANG_ #js {"a.bin" (js/Uint8Array. #js [1 2 3])}))]
         (is (= "/work/a.bin" (aget staged "a.bin")) "no dir gives /work")))

     ;; GDAL reads a /vsimem path from its own memory, not from MEMFS.
     (deftest ^:async stage-files-writes-below-vsimem
       (let [data   (.encode (js/TextEncoder.) unset-fields-geojson)
             staged (await (gdal/stage_files_BANG_ #js {"staged.geojson" data
                                                         "empty.bin"      #js []}
                                                   "/vsimem/js"))
             path   (aget staged "staged.geojson")
             ds     (await (gdal/gdal_open_ex path fndefs/GDAL_OF_VECTOR nil nil nil))]
         (is (= "/vsimem/js/staged.geojson" path))
         (is ds "GDAL opens the /vsimem file")
         (when ds (await (gdal/gdal_close ds)))
         (is (= [0 -1] [(await (gdal/vsi_unlink path)) (await (gdal/vsi_unlink path))])
             "the second unlink finds no file")
         (is (= 0 (await (gdal/vsi_unlink (aget staged "empty.bin")))) "an empty file")))

     (deftest ^:async read-a-vsimem-file-back
       (let [staged (await (gdal/stage_files_BANG_ #js {"a.bin" (js/Uint8Array. #js [1 2 3 255])}
                                                   "/vsimem/readback"))
             path   (aget staged "a.bin")]
         (is (= [1 2 3 255] (vec (await (gdal/read_vsimem_file path)))))
         (is (= ["a.bin"] (vec (await (gdal/read_vsi_dir "/vsimem/readback")))))
         (is (nil? (await (gdal/read_vsimem_file "/vsimem/readback/missing.bin"))))
         (is (= 0 (await (gdal/vsi_unlink path))))))

     (deftest ^:async read-an-empty-vsimem-file
       (let [staged (await (gdal/stage_files_BANG_ #js {"empty.bin" (js/Uint8Array. 0)}
                                                   "/vsimem/empty"))
             path   (aget staged "empty.bin")
             bs     (await (gdal/read_vsimem_file path))]
         (is (not (nil? bs)) "an empty file is not a missing file")
         (is (= 0 (when bs (.-length bs))))
         (is (= 0 (await (gdal/vsi_unlink path))))))

     (deftest ^:async stage-files-rejects-another-vsi-dir
       (let [msg (await (support/rejectionMessage
                         (gdal/stage_files_BANG_ #js {"a.bin" (js/Uint8Array. #js [1])} "/vsizip/x")))]
         (is (.includes (str msg) "not below /vsizip/x"))))

     (deftest ^:async vsizip-of-a-staged-file
       (doseq [dir ["/vsimem/zip" "/work-zip"]]
         (let [staged (await (gdal/stage_files_BANG_ #js {"tiny.zip" (.readFileSync fs zip-path)} dir))
               zip    (aget staged "tiny.zip")]
           (is (= ["a" "b" "c"]
                  (support/fieldValues (await (support/layerRecords (str "/vsizip/" zip "/tiny.shp")))
                                       "name"))
               dir)
           (await (gdal/vsi_unlink zip)))))

     (deftest ^:async vsi-unlink-deletes-a-staged-file
       (let [staged (await (gdal/stage_files_BANG_ #js {"gone.bin" (js/Uint8Array. #js [1])}
                                                   "/work-gone"))
             path   (aget staged "gone.bin")]
         (is (= [0 -1] [(await (gdal/vsi_unlink path)) (await (gdal/vsi_unlink path))])
             "the second unlink finds no file")))

     (deftest ^:async an-unset-field-reads-as-nil
       (let [data   (.encode (js/TextEncoder.) unset-fields-geojson)
             staged (await (gdal/stage_files_BANG_ #js {"unset.geojson" data} "/work-unset"))]
         (is (= unset-fields
                (vec (.map (await (support/layerRecords (aget staged "unset.geojson")))
                           #(.-fields %)))))))

     (deftest ^:async null-geometry-reads-as-nil-wkb
       (let [data    (.encode (js/TextEncoder.) null-geometry-geojson)
             staged  (await (gdal/stage_files_BANG_ #js {"null.geojson" data} "/work-null"))
             [a n]   (await (support/layerRecords (aget staged "null.geojson")))]
         (is (= (vec (support/squareWkb 0 0 1)) (vec (.-wkb a))))
         (is (= "n" (.-name (.-fields n))))
         (is (nil? (.-wkb n)) "a feature without geometry has :wkb nil")))

     (run_tests_and_exit_BANG_ gdal/shutdown_BANG_ "net.willcohen.gdal.vector-read-test")))
