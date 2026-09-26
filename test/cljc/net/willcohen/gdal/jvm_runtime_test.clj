;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.jvm-runtime-test
  "Make sure that Truffle JIT-compiles the libgdal wasm guest.
   That needs the org.graalvm.truffle/truffle-runtime artifact and a GraalVM
   JDK, which supplies libgraal. Stock OpenJDK 25 cannot load the optimizing
   runtime (JDK-8364936). Without it, Truffle uses its interpreter, and each
   other test still passes."
  (:require [clojure.test :refer [deftest is]])
  (:import [com.oracle.truffle.api Truffle]
           [org.graalvm.polyglot Engine]))

(def ^:private vendor-version (System/getProperty "java.vendor.version"))

(def ^:private graalvm-jdk?
  (some-> vendor-version (.contains "GraalVM")))

(defn- graal-version
  "The GraalVM release in java.vendor.version, the libgraal of the JDK.
   'GraalVM CE 25.3.4.1+1.1' gives '25.3.4.1'. java.version is different:
   GraalVM 25.3.4.1 has JDK 25.0.4.1."
  [vendor]
  (second (re-find #"(\d+(?:\.\d+){2,})" (or vendor ""))))

(defn- diagnose-interpreted
  "Compare the version of the polyglot artifacts with the JDK libgraal.
   Truffle uses the interpreter for more than one reason, and the runtime name
   does not show which."
  []
  (let [polyglot (with-open [e (Engine/create (into-array String []))]
                   (.getVersion e))
        jdk (graal-version vendor-version)
        head (str "Truffle runtime is 'Interpreted' on GraalVM JDK '"
                  vendor-version "'. ")]
    (if (and polyglot jdk (not= polyglot jdk))
      (str head "The org.graalvm.* artifacts are " polyglot " but the JDK's "
           "libgraal is " jdk ". Truffle needs both at one version. Align the "
           "org.graalvm.* :mvn/version pins in deps.edn with the GraalVM CE "
           "JDK the flake provides, or move the JDK to " polyglot ".")
      (str head "The org.graalvm.* artifacts (" polyglot ") match the JDK, so "
           "the version check is not the cause. Check that "
           "org.graalvm.truffle/truffle-runtime is on the path."))))

(deftest ^:graal optimizing-runtime-active
  (is graalvm-jdk? (str "The GraalVM lane runs on " vendor-version
                        ". Run it in the dev shell, which gives GraalVM CE."))
  (when graalvm-jdk?
    (let [runtime (.getName (Truffle/getRuntime))]
      ;; `is` evaluates its message also when the assertion passes, and an
      ;; Engine is slow to make.
      (is (not= "Interpreted" runtime)
          (when (= "Interpreted" runtime) (diagnose-interpreted))))))
