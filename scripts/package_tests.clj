;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns package-tests
  "Tests of the jars and the npm tarball, as a user gets them."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [cljs-build :refer [pkg-dir]]
            [clojure.string :as str]
            [native-dev]
            [release]))

(defn root-files
  "The name of each file at the root of `jar`. A clj-gdal jar has none,
   because clj-proj on FFI copies the first proj.db at the class path root."
  [jar]
  (with-open [z (java.util.zip.ZipFile. (str jar))]
    (->> (enumeration-seq (.entries z))
         (map #(.getName ^java.util.zip.ZipEntry %))
         (remove #(str/includes? % "/"))
         vec)))

(defn- check-no-root-files! [repo]
  (doseq [jar (fs/glob repo "**.jar")]
    (when-let [names (seq (root-files jar))]
      (throw (ex-info (str (fs/file-name jar) " has files at the class path root: "
                           (str/join " " names))
                      {:jar (str jar) :files (vec names)})))))

(defn test-jar!
  "Install the jars in a repository in target/jar-test, and run
   test/jar/downstream_check.clj in a new project on each backend. With
   CLJ_GDAL_JAR_TEST_PROJ=1, the project also has clj-proj."
  []
  (let [proj? (= "1" (System/getenv "CLJ_GDAL_JAR_TEST_PROJ"))
        dir  (fs/absolutize (fs/path "target" "jar-test"))
        repo (fs/path dir "repo")
        m2   (fs/path dir "m2")]
    ;; Each net.willcohen jar goes, because a version stays the same from one
    ;; run to the next, and an old copy in m2 hides the new one.
    (run! fs/delete-tree [repo (fs/path m2 "net" "willcohen") (fs/path dir ".cpcache")])
    (fs/create-dirs dir)
    (p/shell "clojure" "-T:build" "deploy-file-repo" ":dir" (pr-str (str repo)))
    (check-no-root-files! repo)
    (spit (str (fs/path dir "deps.edn"))
          (pr-str {:mvn/local-repo (str m2)
                   ;; ~/.m2 as a repository gives its copies of the other
                   ;; deps with no download.
                   :mvn/repos {"jar-test" {:url (str (.toUri repo))}
                               "home-m2" {:url (str (.toUri (fs/path (fs/home) ".m2" "repository")))}}
                   :deps (merge {'net.willcohen/gdal {:mvn/version (release/version)}}
                                ;; The first clj-proj on clj-native 0.0.3.
                                (when proj? {'net.willcohen/proj {:mvn/version "0.1.0"}})
                                (native-dev/deps))}))
    ;; downstream_check.clj fails on ffi when the pom profile did not add the
    ;; gdal-native jar of this host, because init! then uses GraalVM.
    (doseq [backend ["ffi" "graal"]]
      (println (str "Jar check on the " backend " backend..."))
      (p/shell {:dir (str dir)} "clojure" "-J--enable-native-access=ALL-UNNAMED"
               "-M" (str (fs/absolutize "test/jar/downstream_check.clj"))
               backend (str (fs/absolutize "test/fixtures/tiny.gpkg"))
               (if proj? "proj" "no-proj")))))

(defn test-npm!
  "Pack the npm tarball, and run test/npm/test.mjs in target/npm-test with
   ffi-wasm from the registry, or from the clj-native checkout with
   CLJ_NATIVE_DEV=1."
  []
  (release/check-npm-files!)
  (let [dir (fs/absolutize (fs/path "target" "npm-test"))
        tgz (str "gdal-wasm-" (release/version) ".tgz")]
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    (p/shell {:dir pkg-dir} "npm" "pack" "--pack-destination" (str dir))
    (spit (str (fs/path dir "package.json"))
          (json/generate-string {:name "gdal-wasm-npm-test"
                                 :private true
                                 :type "module"
                                 :dependencies {"gdal-wasm" (str "file:./" tgz)}}
                                {:pretty true}))
    (fs/copy "test/npm/test.mjs" (fs/path dir "test.mjs"))
    (p/shell {:dir (str dir)} "npm" "install" "--no-audit" "--no-fund")
    (native-dev/install-ffi-wasm! dir)
    (p/shell {:dir (str dir)} "node" "test.mjs" (str (fs/absolutize "test/fixtures/tiny.gpkg")))))
