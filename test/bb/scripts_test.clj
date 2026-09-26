;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns scripts-test
  "Tests of native-dev, release and package-tests. A test that reads the
   environment or the working directory runs bb in a new process."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [package-tests]))

(defn- run-bb
  "Run `form` in a new bb with the paths and deps of bb.edn."
  [opts form]
  (p/shell (merge {:out :string :err :string :continue true} opts)
           "bb" "--config" (str (fs/absolutize "bb.edn")) "-e" form))

(deftest clj-native-dev-0-keeps-the-published-deps
  (is (= ":test" (:out (run-bb {:extra-env {"CLJ_NATIVE_DEV" "0"}}
                               "(require 'native-dev) (print (native-dev/aliases \":test\"))")))))

(deftest deploy-stops-without-a-remote
  (fs/with-temp-dir [dir {}]
    (p/shell {:dir (str dir) :out :string :err :string} "git" "init")
    (let [r (run-bb {:dir (str dir)} "(require 'release) (#'release/check-remote!)")]
      (is (not (zero? (:exit r))))
      (is (str/includes? (:err r) "No git remote origin") (:err r)))))

(deftest the-release-stops-with-clj-native-dev
  (let [r (run-bb {:extra-env {"CLJ_NATIVE_DEV" "1"}}
                  "(require 'release) (#'release/check-release!)")]
    (is (not (zero? (:exit r))))
    (is (str/includes? (:err r) "CLJ_NATIVE_DEV") (:err r))))

;; bb.edn holds the clj-native that writes the gdal-handler.mjs of the
;; package.
(deftest the-release-stops-when-the-clj-native-versions-differ
  (fs/with-temp-dir [dir {}]
    (let [deps {:deps {'net.willcohen/native {:mvn/version "0.0.3"}}}
          pkg  (fs/path dir "src/cljc/net/willcohen/gdal/package.json")]
      (spit (str (fs/path dir "deps.edn")) (pr-str deps))
      (spit (str (fs/path dir "bb.edn"))
            (pr-str (assoc-in deps [:deps 'net.willcohen/native :mvn/version] "0.0.2")))
      (fs/create-dirs (fs/parent pkg))
      (spit (str pkg) "{\"peerDependencies\": {\"ffi-wasm\": \"0.0.3\"}}")
      (is (str/includes? (:out (run-bb {:dir (str dir)}
                                       "(require 'release) (print (#'release/clj-native-mismatch))"))
                         "bb.edn 0.0.2")))))

;; The dry run must stop where deploy! stops, before it shows what it would
;; publish.
(deftest dry-run-checks-the-working-copy-first
  (fs/with-temp-dir [dir {}]
    (let [r (run-bb {:dir (str dir)} "(require 'release) (release/dry-run!)")]
      (is (not (zero? (:exit r))))
      ;; Without "jj" in the error, the require failed and jj did not run.
      (is (str/includes? (:err r) "\"jj\"") (:err r)))))

(defn- jj! [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string} "jj" args))

;; The CI lint job has no jj.
(deftest deploy-stops-unless-the-working-copy-is-main
  (when (fs/which "jj")
    (fs/with-temp-dir [dir {}]
      (let [a     (str (fs/path dir "a"))
            exit! #(:exit (run-bb {:dir (str dir)}
                                  "(require 'release) (#'release/check-working-copy!)"))]
        (jj! dir "git" "init")
        (spit a "1")
        (jj! dir "commit" "-m" "one")
        (jj! dir "bookmark" "create" "main" "-r" "@-")
        (is (zero? (exit!)) "@ is empty on main")
        (spit a "2")
        (is (not (zero? (exit!))) "@ has a change")
        (jj! dir "commit" "-m" "two")
        (is (not (zero? (exit!))) "@ is empty on a commit after main")))))

(defn- write-jar! [path names]
  (with-open [out (java.util.zip.ZipOutputStream. (io/output-stream (str path)))]
    (doseq [n names]
      (.putNextEntry out (java.util.zip.ZipEntry. ^String n))
      (.closeEntry out))))

(deftest root-files-names-each-file-at-the-class-path-root
  (fs/with-temp-dir [dir {}]
    (let [jar (fs/path dir "x.jar")]
      (write-jar! jar ["META-INF/" "META-INF/MANIFEST.MF" "net/willcohen/gdal/proj.db" "proj.db"])
      (is (= ["proj.db"] (package-tests/root-files (str jar)))))))
