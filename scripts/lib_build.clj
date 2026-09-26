;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns lib-build
  "The builds of sqlite, zlib, libtiff and PROJ that libgdal links, and the
   GDAL cmake args. Each fn takes the target: :wasm, or a resource dir such as
   \"linux-amd64-musl\". Each target gets its features from the same flag
   lists."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [net.willcohen.native.build :as nb]
            [sources]))

(defn- target-path [kind lib target]
  (str (fs/absolutize (fs/path "target" kind (str (name lib) "-" (name target))))))

(defn- install-dir [lib target]
  (target-path "install" lib target))

(defn- build-dir [lib target]
  (target-path "build" lib target))

(def ^:private archive-names
  {:sqlite "libsqlite3.a" :libtiff "libtiff.a" :zlib "libz.a"})

(defn- archive [lib target]
  (str (install-dir lib target) "/lib/" (archive-names lib)))

(defn- linux? [target]
  (and (string? target) (str/starts-with? target "linux-")))

(defn zig-toolchain!
  "Write and return the zig toolchain of a Linux target, or nil."
  [target]
  (when (linux? target)
    (nb/zig-toolchain! target (fs/absolutize (fs/path "target" "zig" target)))))

(defn- lib-type [target]
  (if (= :wasm target) :wasm :native))

;; __FILE__ and the debug info hold each source path. The map makes a path
;; relative to target/, thus a binary holds no path of the build machine.
(def ^:private prefix-map
  (str "-ffile-prefix-map=" (fs/absolutize "target") "/="))

;; The zig cc and c++ wrappers of clj-native 0.0.3 pass
;; -fsanitize-trap=undefined, which puts a UBSan trap (ud1) at each check at
;; each -O level. A trap stopped the JVM in the libtiff of clj-proj. The last
;; flag wins. Remove it when clj-native gives the trap flag to -O0 only.
(defn- no-ubsan [target]
  (when (linux? target) ["-fno-sanitize=undefined"]))

;; These static libs need -fPIC, because libgdal.so links them.
(defn- cflags [target & flags]
  (str/join " " (concat ["-O2" prefix-map] (when (linux? target) ["-fPIC"]) flags
                        (no-ubsan target))))

(defn cmake-flags
  "The CMAKE_C_FLAGS and CMAKE_CXX_FLAGS args of `target`: the prefix map,
   then `flags`."
  [target & flags]
  (let [v (str/join " " (concat [prefix-map] flags (no-ubsan target)))]
    [(str "-DCMAKE_C_FLAGS=" v) (str "-DCMAKE_CXX_FLAGS=" v)]))

(defn check-no-build-paths!
  "Throw when `file` contains one of `paths`, such as the repo dir."
  [file paths]
  (let [text  (String. (fs/read-all-bytes file) "ISO-8859-1")
        found (filterv #(str/includes? text %) paths)]
    (when (seq found)
      (throw (ex-info (str file " contains a build path: " (str/join ", " found))
                      {:file (str file) :paths found})))))

(defn- with-zig [target opts]
  (if-let [{:keys [env host]} (zig-toolchain! target)]
    (-> opts
        (update :env merge env)
        (update :configure-args conj (str "--host=" host)))
    opts))

;; The GPKG driver of GDAL uses sqlite3_column_table_name
;; (SQLITE_ENABLE_COLUMN_METADATA) for views, and it writes the spatial
;; index of a layer as an R-tree (SQLITE_ENABLE_RTREE).
(def ^:private sqlite-defines
  ["-DSQLITE_ENABLE_COLUMN_METADATA" "-DSQLITE_ENABLE_RTREE"])

;; The wasm module has one thread and no dlopen. A wasm long double is a
;; 128-bit type in software.
(def ^:private sqlite-wasm-defines
  ["-DSQLITE_THREADSAFE=0" "-DSQLITE_OMIT_LOAD_EXTENSION" "-DSQLITE_LONGDOUBLE_TYPE=double"])

(defn- build-sqlite! [target]
  (let [install (install-dir :sqlite target)
        opts    (with-zig target
                  {:type           (lib-type target)
                   :build-dir      (build-dir :sqlite target)
                   :install-dir    install
                   :configure-script (str (sources/dir :sqlite) "/configure")
                   :configure-args ["--disable-shared" "--enable-static"
                                    "--disable-editline" "--disable-readline"]
                   :env            {"CFLAGS" (apply cflags target
                                                    (concat sqlite-defines
                                                            (when (= :wasm target)
                                                              sqlite-wasm-defines)))}})]
    (nb/build-once! (archive :sqlite target) opts
                    #(nb/build-autotools-library opts))))

(defn- patch-zlib-makefile!
  "Change the zlib Makefile to use emar.
   zlib's configure ignores AR and RANLIB for wasm and uses libtool. Replace
   the /usr/bin/libtool form before the bare form, to prevent the result
   /usr/bin/emar."
  []
  (let [mf (str (sources/dir :zlib) "/Makefile")]
    (spit mf (-> (slurp mf)
                 (str/replace #"(?m)^AR=.*$" "AR=emar")
                 (str/replace #"(?m)^ARFLAGS=.*$" "ARFLAGS=rcs")
                 (str/replace #"(?m)^RANLIB=.*$" "RANLIB=emranlib")
                 (str/replace "/usr/bin/libtool -o" "emar rcs")
                 (str/replace "libtool -o" "emar rcs")))))

;; zlib builds only in its source tree. Its configure takes no --host, and
;; reads CHOST in place of uname, which names the build machine.
(defn- build-zlib! [target]
  (let [zig  (zig-toolchain! target)
        opts {:type           (lib-type target)
              :build-dir      (sources/dir :zlib)
              :in-tree?       true
              :install-dir    (install-dir :zlib target)
              :configure-args ["--static"]
              :env            (cond-> {"CFLAGS" (cflags target)}
                                zig (merge (:env zig) {"CHOST" (:host zig)}))}]
    ;; :post-configure stays out of opts, because build-once! compares the
    ;; pr-str of opts, and a fn prints its identity.
    (nb/build-once! (archive :zlib target) opts
                    #(nb/build-autotools-library
                      (cond-> opts (= :wasm target) (assoc :post-configure patch-zlib-makefile!))))))

;; PROJ reads its grids with libtiff, and a grid uses Deflate or no
;; compression. GDAL has its own libtiff. The configure args turn off each
;; other codec, because the dev shell can supply its library, and the
;; native libgdal must load with no other dynamic library.
(defn- build-libtiff! [target]
  (let [install (install-dir :libtiff target)
        zlib    (install-dir :zlib target)
        opts    (with-zig target
                  {:type           (lib-type target)
                   :build-dir      (build-dir :libtiff target)
                   :install-dir    install
                   :configure-script (str (sources/dir :libtiff) "/configure")
                   :configure-args ["--disable-shared" "--enable-static"
                                    "--disable-tools" "--disable-contrib" "--disable-tests"
                                    "--disable-docs" "--disable-cxx"
                                    "--disable-jpeg" "--disable-old-jpeg" "--disable-jbig"
                                    "--disable-lerc" "--disable-lzma" "--disable-zstd"
                                    "--disable-webp" "--disable-libdeflate"
                                    (str "--with-zlib-include-dir=" zlib "/include")
                                    (str "--with-zlib-lib-dir=" zlib "/lib")]
                   :env            {"CFLAGS" (cflags target)}})]
    (nb/build-once! (archive :libtiff target) opts
                    #(nb/build-autotools-library opts))))

(defn- proj-lib [target]
  (str (build-dir :proj target) "/lib/libproj.a"))

(defn proj-db
  "The path of the proj.db that the PROJ build of `target` makes."
  [target]
  (str (build-dir :proj target) "/data/proj.db"))

;; The native libgdal embeds proj.db in libproj.a. The wasm loaders install
;; proj.db into MEMFS from a separate file.
(defn- build-proj! [target]
  (let [sqlite  (install-dir :sqlite target)
        libtiff (install-dir :libtiff target)
        zlib    (install-dir :zlib target)
        zig     (zig-toolchain! target)
        opts    {:type       (lib-type target)
                 :src-dir    (sources/dir :proj)
                 :build-dir  (build-dir :proj target)
                 :cmake-args (cond-> (into ["-DBUILD_APPS=OFF"
                                            "-DBUILD_TESTING=OFF"
                                            "-DBUILD_SHARED_LIBS=OFF"
                                            "-DCMAKE_BUILD_TYPE=Release"
                                            "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
                                            "-DNLOHMANN_JSON_ORIGIN=internal"
                                            "-DENABLE_CURL=OFF"
                                            "-DENABLE_NETWORK=ON"
                                            (str "-DEMBED_RESOURCE_FILES=" (if (= :wasm target) "OFF" "ON"))
                                            (str "-DSQLite3_INCLUDE_DIR=" sqlite "/include")
                                            (str "-DSQLite3_LIBRARY=" (archive :sqlite target))
                                            (str "-DTIFF_INCLUDE_DIR=" libtiff "/include")
                                            (str "-DTIFF_LIBRARY=" (archive :libtiff target))
                                            (str "-DZLIB_INCLUDE_DIR=" zlib "/include")
                                            (str "-DZLIB_LIBRARY=" (archive :zlib target))]
                                           (cmake-flags target))
                               (= :wasm target) (conj "-DENABLE_EMSCRIPTEN_FETCH=OFF")
                               ;; PROJ compiles the embedded proj.db as an
                               ;; object library, which
                               ;; CMAKE_POSITION_INDEPENDENT_CODE does not reach.
                               zig (-> (into (:cmake-args zig))
                                       (conj "-DPROJ_OBJECT_LIBRARIES_POSITION_INDEPENDENT_CODE=ON")))}]
    (nb/build-once! (proj-lib target) opts
                    #(nb/build-cmake-library opts))))

(defn build-deps!
  "Build sqlite, zlib, libtiff and PROJ for `target`."
  [target]
  (build-sqlite! target)
  (build-zlib! target)
  (build-libtiff! target)
  (build-proj! target))

(defn proj-dep-archives
  "The static archives that libproj.a needs for `target`, in link order."
  [target]
  (mapv #(archive % target) [:sqlite :libtiff :zlib]))

(defn dep-archives
  "The static archives that GDAL links for `target`, in link order."
  [target]
  (cons (proj-lib target) (proj-dep-archives target)))

(defn gdal-cmake-args
  "The GDAL cmake args that the two targets share.
   GDAL_USE_EXTERNAL_LIBS=OFF keeps out each library that this list does not
   name, for example GEOS and libcurl."
  [target]
  (let [sqlite (install-dir :sqlite target)]
    [;; The GDAL build tree stays for an incremental build. Without a fresh
     ;; cache, a -D flag that goes from this list keeps its old value there.
     "--fresh"
     ;; GDAL tests #embed with `#embed __FILE__`, after its own compiler
     ;; version check. The prefix map makes __FILE__ relative to target/, and
     ;; the test cannot open it. The result then says no #embed, and
     ;; EMBED_RESOURCE_FILES stops the configure.
     "-D_TEST_SHARP_EMBED=ON"
     ;; The value is one directory, because FindPROJ reads
     ;; ${PROJ_INCLUDE_DIR}/proj.h as a literal path.
     (str "-DPROJ_INCLUDE_DIR=" (sources/dir :proj) "/src")
     (str "-DPROJ_LIBRARY=" (proj-lib target))
     ;; GDAL_USE_EXTERNAL_LIBS=OFF also stops the search for sqlite. Without
     ;; these three args, cmake removes GPKG from the build and gives no
     ;; error.
     "-DGDAL_USE_SQLITE3=ON"
     (str "-DSQLite3_INCLUDE_DIR=" sqlite "/include")
     (str "-DSQLite3_LIBRARY=" (archive :sqlite target))
     "-DGDAL_USE_EXTERNAL_LIBS=OFF"
     "-DGDAL_USE_INTERNAL_LIBS=ON"
     "-DGDAL_USE_GEOS=OFF"
     "-DGDAL_USE_CURL=OFF"
     "-DGDAL_BUILD_OPTIONAL_DRIVERS=OFF"
     "-DOGR_BUILD_OPTIONAL_DRIVERS=OFF"
     "-DGDAL_ENABLE_DRIVER_GTIFF=ON"
     "-DGDAL_ENABLE_DRIVER_COG=ON"
     "-DGDAL_ENABLE_DRIVER_PNG=ON"
     "-DGDAL_ENABLE_DRIVER_JPEG=ON"
     "-DOGR_ENABLE_DRIVER_GPKG=ON"
     "-DOGR_ENABLE_DRIVER_FLATGEOBUF=ON"
     "-DOGR_ENABLE_DRIVER_GML=ON"
     "-DOGR_ENABLE_DRIVER_SHAPE=ON"
     "-DOGR_ENABLE_DRIVER_OPENFILEGDB=ON"
     "-DBUILD_APPS=OFF"
     "-DBUILD_TESTING=OFF"
     "-DBUILD_PYTHON_BINDINGS=OFF"
     "-DBUILD_JAVA_BINDINGS=OFF"
     "-DEMBED_RESOURCE_FILES=ON"
     "-DUSE_ONLY_EMBEDDED_RESOURCE_FILES=ON"
     "-DCMAKE_BUILD_TYPE=Release"]))
