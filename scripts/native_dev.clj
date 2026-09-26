;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns native-dev
  "With CLJ_NATIVE_DEV=1, the tasks use the clj-native checkout at
   ../clj-native in place of the published net.willcohen/native and
   ffi-wasm. The :dev alias of deps.edn names the same checkout."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]))

(def ^:private checkout "../clj-native")

(defn- dev? []
  (= "1" (System/getenv "CLJ_NATIVE_DEV")))

(defn aliases
  "The alias string `s`, with :dev first when the checkout is in use.
   For example, \":test\" gives \":dev:test\"."
  [s]
  (if (dev?) (str ":dev" s) s))

(defn deps
  "The :deps entry for net.willcohen/native in a project that gets clj-gdal
   from its pom: the checkout, or an empty map."
  []
  (if (dev?)
    {'net.willcohen/native {:local/root (str (fs/absolutize checkout))}}
    {}))

(defn- tarball!
  "Pack the ffi-wasm of the checkout into target/native-dev, and return the
   tarball path. Its prepack script compiles its .cljc modules first."
  []
  (let [out (fs/absolutize "target/native-dev")]
    (fs/delete-tree out)
    (fs/create-dirs out)
    (p/shell {:dir checkout :out :string} "npm" "pack" "--pack-destination" (str out))
    (str (first (fs/glob out "ffi-wasm-*.tgz")))))

(defn install-ffi-wasm!
  "Install the ffi-wasm of the checkout in the npm project at `dir`, or do
   nothing without CLJ_NATIVE_DEV. It installs a copy, because a link gives a
   second squint-cljs, which does not share state, for example the cljs.test
   registry."
  [dir]
  (when (dev?)
    (p/shell {:dir (str dir)} "npm" "install" "--no-save" "--no-audit" "--no-fund"
             (tarball!))))
