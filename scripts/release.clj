;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns release
  "The jar and npm release.
   package.json holds the version, and build.clj reads it. To change it, run
   npm version <version> --no-git-tag-version in pkg-dir."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [cljs-build :refer [pkg-dir]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [lint]))

(def ^:private package-json (str pkg-dir "/package.json"))

(defn- package []
  (json/parse-string (slurp package-json)))

(defn version []
  (get (package) "version"))

(def npm-docs
  "The root docs that npm ships."
  ["README.md" "LICENSE" "THIRD-PARTY-NOTICES"])

(defn package-files
  "The files list of package.json."
  []
  (get (package) "files"))

(defn stage-npm-docs!
  "Copy npm-docs into the npm package dir."
  []
  (doseq [f npm-docs]
    (fs/copy f (fs/path pkg-dir f) {:replace-existing true})))

(defn check-npm-files!
  "Throw when a file of the files list of package.json is missing.
   Git ignores the wasm, proj.db and the .mjs output, and npm packs with no
   error when a listed file is missing."
  []
  (let [missing (remove #(fs/exists? (fs/path pkg-dir %)) (package-files))]
    (when (seq missing)
      (throw (ex-info (str "Missing from " pkg-dir ": " (str/join ", " missing)
                           ". Run bb build:wasm and bb squint first.")
                      {:missing (vec missing)})))))

(defn- check-remote!
  "Throw unless the git remote origin exists. deploy! pushes the tag there
   after it makes the tag."
  []
  (when-not (zero? (:exit (p/shell {:out :string :err :string :continue true}
                                   "git" "remote" "get-url" "origin")))
    (throw (ex-info "No git remote origin. Add it with: jj git remote add origin <url>" {}))))

(defn- clj-native-mismatch
  "A message when the clj-native of deps.edn and bb.edn and the ffi-wasm
   peer of package.json differ, or nil. bb.edn writes gdal-handler.mjs."
  []
  (let [native   #(get-in (edn/read-string (slurp %)) [:deps 'net.willcohen/native :mvn/version])
        versions {"deps.edn"     (native "deps.edn")
                  "bb.edn"       (native "bb.edn")
                  "package.json" (get-in (package) ["peerDependencies" "ffi-wasm"])}]
    (when (< 1 (count (set (vals versions))))
      (str "clj-native and ffi-wasm differ: "
           (str/join ", " (map (fn [[f v]] (str f " " v)) versions))))))

(defn- check-release!
  "Throw when a release check fails, and print the npm user. deploy! runs
   it before the tag, because npm and Clojars check the login only when they
   publish."
  []
  (when (= "1" (System/getenv "CLJ_NATIVE_DEV"))
    (throw (ex-info "Unset CLJ_NATIVE_DEV: the release gates must test the published clj-native." {})))
  (check-remote!)
  (check-npm-files!)
  (lint/sync-hook! true)
  (let [v        (version)
        mismatch (clj-native-mismatch)
        errors   (cond-> []
                   (not (str/includes? (slurp "CHANGELOG.md") (str "## [" v "]")))
                   (conj (str "CHANGELOG.md has no ## [" v "] section"))
                   mismatch
                   (conj mismatch)
                   (not (and (System/getenv "CLOJARS_USERNAME") (System/getenv "CLOJARS_PASSWORD")))
                   (conj "Set CLOJARS_USERNAME and CLOJARS_PASSWORD, a deploy token."))]
    (when (seq errors)
      (throw (ex-info (str/join "; " errors) {:errors errors}))))
  (p/shell {:dir pkg-dir} "npm" "whoami"))

(defn- check-working-copy!
  "Throw unless the working copy and main have the same files.
   deploy! tags main, but bb deploy builds the jars from the working copy."
  []
  (when-not (str/blank? (:out (p/shell {:out :string}
                                       "jj" "diff" "--summary" "--from" "main" "--to" "@")))
    (throw (ex-info "The working copy is not main. Commit each change, and move main to it." {}))))

(defn dry-run!
  "Show what deploy! would publish, then run each of its checks. Throws when
   a check fails."
  []
  (check-working-copy!)
  (println "Version" (version))
  (p/shell "jj" "log" "-r" "main" "--no-graph")
  (doseq [jar (sort (fs/glob "target" (str "gdal*-" (version) "*.jar")))]
    (p/shell "unzip" "-l" (str jar)))
  (p/shell {:dir pkg-dir} "npm" "publish" "--dry-run")
  (check-release!))

(defn deploy!
  "Tag main with the version, push main and the tag, and publish the gdal
   and gdal-native jars to Clojars and the package to npm. Each check runs
   before the tag."
  []
  (let [v (version)]
    (check-working-copy!)
    (check-release!)
    (p/shell "git" "tag" v "main")
    (p/shell "git" "push" "origin" "main" v)
    (p/shell "clojure" "-T:build" "deploy-clojars")
    (p/shell {:dir pkg-dir} "npm" "publish")))
