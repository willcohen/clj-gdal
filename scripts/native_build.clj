;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns native-build
  "The native builds of libgdal.
   darwin-aarch64 builds on a Mac, and zig builds the four Linux resource dirs
   on any host. GDAL links the static libproj.a, and libproj.a embeds proj.db,
   because libgdal must load with no dynamic library but the ones of the
   system: the JVM extracts it from the jar as one file."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [lib-build]
            [net.willcohen.native.build :as nb]
            [sources]))

(def dirs
  "The resource dir of each native lib that the jars ship."
  (set (mapcat :dirs (edn/read-string (slurp "scripts/native-jars.edn")))))

(defn- darwin? [dir]
  (str/starts-with? dir "darwin-"))

(defn- require-host! [dir]
  (when-not (dirs dir)
    (throw (ex-info (str "No native build for " dir) {:dir dir :known (sort dirs)})))
  (let [{:keys [os arch]} (nb/detect-host-platform)]
    (when (and (darwin? dir) (not (and (= :darwin os) (= :aarch64 arch))))
      (throw (ex-info (str "darwin-aarch64 builds only on darwin-aarch64. This host is "
                           (name os) "-" (name arch) ".")
                      {:os os :arch arch})))))

(defn- lib-name [dir]
  (if (darwin? dir) "libgdal.dylib" "libgdal.so"))

(defn- gdal-build-dir [dir]
  (str (sources/dir :gdal) "/build-" dir))

(defn- link-once!
  "Call link! unless lib is up to date with opts and with the modification
   time of each archive that it links. build-once! compares only the args."
  [lib opts archives link!]
  (nb/build-once! lib
                  (assoc opts :archives (mapv (fn [a] [a (fs/file-time->millis (fs/last-modified-time a))])
                                              archives))
                  link!))

(defn- build-gdal!
  "Build libgdal for `dir` in the GDAL source tree, and return its path."
  [dir]
  (let [lib  (str (gdal-build-dir dir) "/" (lib-name dir))
        zig  (lib-build/zig-toolchain! dir)
        opts {:type       :native
              :src-dir    (sources/dir :gdal)
              :build-dir  (gdal-build-dir dir)
              :clean?     false
              :cmake-args (cond-> (-> (lib-build/gdal-cmake-args dir)
                                      (into (lib-build/cmake-flags dir))
                                      (into ["-DBUILD_SHARED_LIBS=ON"
                                             ;; libproj.a has unresolved sqlite, libtiff
                                             ;; and zlib symbols, and the internal
                                             ;; libraries of GDAL have other names. -s,
                                             ;; because zig puts the debug info of
                                             ;; libc++ into the lib.
                                             (str "-DCMAKE_SHARED_LINKER_FLAGS="
                                                  (str/join " " (cond-> (lib-build/proj-dep-archives dir)
                                                                  zig (conj "-s"))))]))
                            zig (-> (into (:cmake-args zig))
                                    (conj "-DCMAKE_SKIP_RPATH=ON")))}]
    (link-once! lib opts (lib-build/dep-archives dir) #(nb/build-cmake-library opts))
    lib))

(defn- forbidden-symbols
  "Each GEOS or libcurl symbol in lib."
  [dir lib]
  (->> (:out (apply p/shell {:out :string} (if (darwin? dir) ["nm" lib] ["readelf" "-Ws" lib])))
       str/split-lines
       (filter #(re-find #"\s_?(GEOS|curl_easy)" %))))

(defn- check-lib!
  "Stop the build for a lib that loads a library that its dir forbids.
   It also stops for a GEOS or libcurl symbol, or a path of the repo, in the
   lib."
  [dir lib]
  (if (darwin? dir)
    (nb/check-darwin-lib! lib)
    (nb/check-linux-lib! dir lib))
  (lib-build/check-no-build-paths! lib [(str (fs/cwd))])
  (when-let [symbols (seq (take 10 (forbidden-symbols dir lib)))]
    (throw (ex-info (str lib " contains GEOS or libcurl.") {:lib lib :symbols (vec symbols)}))))

(defn- stage-lib!
  "Copy lib to resources/<dir>, where the jar build gets it.
   fs/copy copies the file at the end of the symlink chain."
  [dir lib]
  (let [dest (str "resources/" dir "/" (lib-name dir))]
    (fs/create-dirs (fs/parent dest))
    (fs/copy lib dest {:replace-existing true})
    (println (str "=== copied " lib " to " dest " ==="))))

(defn build!
  "Build libgdal for the resource `dir`, check it, and copy it to resources/."
  [dir]
  (require-host! dir)
  (lib-build/build-deps! dir)
  (let [lib (build-gdal! dir)]
    (check-lib! dir lib)
    (stage-lib! dir lib)))
