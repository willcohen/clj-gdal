;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

;; Args: ffi or graal, the path of tiny.gpkg, proj or no-proj. bb test:jar runs
;; it with the built clj-gdal jar, and with clj-proj for proj.

(require '[net.willcohen.gdal.gdal :as gdal]
         '[net.willcohen.gdal.fndefs :as fndefs])

(defn- fail! [msg]
  (binding [*out* *err*] (println "FAIL:" msg))
  (System/exit 1))

(let [[backend gpkg with-proj] *command-line-args*]
  (when (= "graal" backend) (gdal/force-graal!))
  (gdal/init!)
  ;; init! uses GraalVM when the native lib does not load. Without this
  ;; check, the ffi run passes on GraalVM.
  (when (not= (= "graal" backend) (gdal/graal?))
    (fail! (str "init! selected the wrong backend for " backend)))
  ;; This create comes before each other GDAL call, because GDAL warns about
  ;; GDAL_DATA only at the first GPKG create of a process.
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "gdal-jar-check" (make-array java.nio.file.attribute.FileAttribute 0)))
        ds  (gdal/gdal-create (gdal/gdal-get-driver-by-name "GPKG")
                              (str dir "/empty.gpkg") 0 0 0 fndefs/GDT_Unknown nil)]
    (when (nil? ds) (fail! "gdal-create returned NULL"))
    (when (.contains (str (gdal/cpl-get-last-error-msg)) "GDAL_DATA")
      (fail! (str "GPKG create warned: " (gdal/cpl-get-last-error-msg))))
    (gdal/gdal-close ds)
    (run! #(.delete ^java.io.File %) (reverse (file-seq dir))))
  ;; On GraalVM, gdal-call copies the host file into MEMFS.
  ;; read-vector-features! uses the wasm heap, and clj-proj on the class path
  ;; registers a second WasmContext.
  (let [ds (gdal/gdal-open-ex gpkg fndefs/GDAL_OF_VECTOR nil nil nil)]
    (when (nil? ds) (fail! "gdal-open-ex returned NULL"))
    (let [records (gdal/read-vector-features! (gdal/gdal-dataset-get-layer ds 0))]
      (when (not= 3 (count records))
        (fail! (str "expected 3 features, got " (count records))))
      (when-not (every? #(= 93 (alength ^bytes (:wkb %))) records)
        (fail! "expected a 93-byte WKB polygon for each feature")))
    (gdal/gdal-close ds))
  (let [srs (gdal/osr-new-spatial-reference "")]
    (when-not (zero? (long (gdal/osr-import-from-epsg srs 3857)))
      (fail! "EPSG:3857 did not load from proj.db"))
    (gdal/osr-destroy-spatial-reference srs))
  ;; The FFI path of clj-proj copies the first proj.db that it finds at the
  ;; class path root. The clj-gdal jar must not put an older proj.db there.
  (when (= "proj" with-proj)
    ((requiring-resolve 'net.willcohen.proj.proj/init!))
    (when-not ((requiring-resolve 'net.willcohen.proj.proj/proj-create-crs-to-crs)
               {:context    ((requiring-resolve 'net.willcohen.proj.proj/context-create))
                :source_crs "EPSG:4326"
                :target_crs "EPSG:3857"})
      (fail! "clj-proj could not make a transformer next to clj-gdal")))
  (println "OK" backend)
  (System/exit 0))
