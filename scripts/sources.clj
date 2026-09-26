;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns sources
  "The versions and source archives of the wasm and native builds.
   The two builds use the same sources."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [net.willcohen.native.build :as nb]))

(def ^:private gdal-version "3.11.5")
(def ^:private proj-version "9.9.0")

;; The sha256 of each archive. The PROJ and GDAL archives agree with the md5
;; files of their releases. libtiff has a good signature from Even Rouault.
;; zlib agrees with the zlib.net copy. The sqlite value is the one of the
;; archive that this repo used first, because the sqlite download page lists
;; only the current release.
(def ^:private archives
  {:zlib    {:url "https://github.com/madler/zlib/releases/download/v1.3.2/zlib-1.3.2.tar.gz"
             :sha256 "bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16"}
   :sqlite  {:url "https://sqlite.org/2026/sqlite-autoconf-3510200.tar.gz"
             :sha256 "fbd89f866b1403bb66a143065440089dd76100f2238314d92274a082d4f2b7bb"}
   :libtiff {:url "https://download.osgeo.org/libtiff/tiff-4.7.1.tar.gz"
             :sha256 "f698d94f3103da8ca7438d84e0344e453fe0ba3b7486e04c5bf7a9a3fabe9b69"}
   :proj    {:url (str "https://download.osgeo.org/proj/proj-" proj-version ".tar.gz")
             :sha256 "791a0610547eeabb17006cfd49cdbd2034f3240f47ed5e88a1031811f4e2bcf3"}
   :gdal    {:url (str "https://github.com/OSGeo/gdal/releases/download/v" gdal-version
                       "/gdal-" gdal-version ".tar.gz")
             :sha256 "34be6252db27c3317d1a9c61791f5a576df6821cbc5b58f6c1de46c352d3f8cf"}})

(defn- file-name [lib]
  (fs/file-name (get-in archives [lib :url])))

(defn dir
  "The absolute path of the extracted source tree of `lib`, such as :proj."
  [lib]
  (str (fs/absolutize (fs/path "target" (str/replace (file-name lib) #"\.tar\.gz$" "")))))

(defn download!
  "Download each source archive into target/, and check and extract it.
   The check is the sha256. An archive or a source tree that is already in
   target/ stays."
  []
  (doseq [[lib {:keys [url sha256]}] archives]
    (nb/download-archive url (fs/path "target" (file-name lib)) {:sha256 sha256})
    (nb/extract-archive (file-name lib) (fs/file-name (dir lib)) "target")))
