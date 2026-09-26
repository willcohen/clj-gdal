;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns native-build-test
  "Tests of the native build scripts. bb test:bb runs them."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [lib-build]
            [native-build]))

(deftest libgdal-links-again-after-an-archive-changes
  (fs/with-temp-dir [dir {}]
    (let [archive (str (fs/path dir "libproj.a"))
          lib     (str (fs/path dir "libgdal.dylib"))
          links   (atom 0)
          link!   #(do (swap! links inc) (spit lib "lib"))
          link    #(#'native-build/link-once! lib {:cmake-args ["-DX=1"]} [archive] link!)]
      (spit archive "a")
      (link)
      (link)
      (is (= 1 @links) "nothing changed")
      (fs/set-last-modified-time archive (+ 2000 (fs/file-time->millis (fs/last-modified-time archive))))
      (link)
      (is (= 2 @links) "the archive changed"))))

(deftest a-binary-with-a-build-path-fails-the-check
  (fs/with-temp-dir [dir {}]
    (let [lib (str (fs/path dir "libgdal.so"))]
      (spit lib (str "\u0000" (fs/path dir "target" "gdal-3.11.5" "gcore" "gdal_misc.cpp") "\u0000"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"build path"
                            (lib-build/check-no-build-paths! lib [(str dir)])))
      (spit lib "\u0000gdal-3.11.5/gcore/gdal_misc.cpp\u0000")
      (is (nil? (lib-build/check-no-build-paths! lib [(str dir)]))))))

(deftest a-zig-build-turns-off-ubsan-last
  (is (str/ends-with? (#'lib-build/cflags "linux-amd64" "-DX") " -fno-sanitize=undefined"))
  (is (every? #(str/ends-with? % " -fno-sanitize=undefined")
              (lib-build/cmake-flags "linux-aarch64-musl" "-DX")))
  (is (not-any? #(str/includes? % "sanitize")
                (concat [(#'lib-build/cflags :wasm) (#'lib-build/cflags "darwin-aarch64")]
                        (lib-build/cmake-flags :wasm "-fexceptions")
                        (lib-build/cmake-flags "darwin-aarch64")))))
