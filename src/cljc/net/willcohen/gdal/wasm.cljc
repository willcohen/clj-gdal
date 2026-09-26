;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

#?(:clj
   (ns net.willcohen.gdal.wasm
     "The GraalVM backend of GDAL on the JVM: the wasm module, and the MEMFS
      copy of each host path that GDAL opens."
     (:require [clojure.java.io :as io]
               [clojure.string :as str]
               [net.willcohen.gdal.fndefs :as fndefs]
               [net.willcohen.native.graal-wasm :as nw]))
   :cljs
   (ns wasm
     "The worker pool of the GDAL handler."
     (:require [clojure.string :as str]
               ["./gdal-loader.mjs" :as gdal-loader]
               ["ffi-wasm" :as wp])))

(def ^:private sidecar-extensions
  #{"shx" "dbf" "prj" "cpg" "qix" "sbn" "sbx" "qpj"
    "tfw" "tifw" "tiffw" "pgw" "pngw" "jgw" "jpgw" "jpegw" "gfw" "wld" "xsd" "gfs"})

(defn in-family?
  "True when `file-name` is in the family of `dataset-name`: the dataset
   file, a file with a suffix after its full name (tiny.tif.aux.xml,
   tiny.gpkg-wal), or a sidecar of its stem (tiny.dbf for tiny.shp)."
  [dataset-name file-name]
  (let [dot  (str/last-index-of dataset-name ".")
        stem (if (and dot (pos? dot)) (subs dataset-name 0 dot) dataset-name)]
    (or (= dataset-name file-name)
        (str/starts-with? file-name (str dataset-name "."))
        (str/starts-with? file-name (str dataset-name "-"))
        (and (str/starts-with? file-name (str stem "."))
             (contains? sidecar-extensions
                        (str/lower-case (subs file-name (inc (count stem)))))))))

#?(:clj
   (do
     (defonce gdal-context (nw/create-wasm-context! :net.willcohen.gdal))

     (defn- resource
       "The URL of `file-name` in the package dir on the class path. Throws
        when it is not there."
       ^java.net.URL [file-name]
       (let [rpath (str "net/willcohen/gdal/" file-name)]
         (or (io/resource rpath)
             (throw (ex-info (str "Not on the class path: " rpath) {:resource rpath})))))

     (defn- resource-bytes ^bytes [file-name]
       (with-open [in (.openStream (resource file-name))]
         (.readAllBytes in)))

     (defn init-graal-module!
       "Load the GDAL wasm module into gdal-context, or into the
        :wasm-context and :polyglot-context of `opts`, and return the module
        Value. A second call returns the loaded module."
       ([] (init-graal-module! {}))
       ([{:keys [wasm-context polyglot-context]}]
        ;; A preload, because the loader imports ./libgdal.mjs. proj.db is
        ;; not at the jar root, because clj-proj keeps an older one there.
        (let [ctx (or wasm-context gdal-context)]
          (or (nw/get-module ctx)
              (let [pctx     (or polyglot-context (nw/context))
                    js-bytes #(nw/js-bytes pctx (resource-bytes %))]
                (nw/bootstrap-graal-module!
                 ctx
                 {:loader-module-url   (resource "gdal-graal-loader.mjs")
                  :preload-module-urls [(resource "libgdal.mjs")]
                  :init-opts           {"wasmBinary" (js-bytes "libgdal.wasm")
                                        "projDb"     (js-bytes "proj.db")}
                  :polyglot-context    pctx}))))))

     (defn graal-module
       "The module of the bound *wasm-context*, or else of gdal-context."
       ^org.graalvm.polyglot.Value []
       (nw/get-module (nw/library-context :net.willcohen.gdal)))

     (defn- fs-call
       ^org.graalvm.polyglot.Value [^org.graalvm.polyglot.Value module method & args]
       (.invokeMember (.getMember module "FS") ^String method (object-array args)))

     (defn- js-u8
       "A new JS Uint8Array in the Context of `module` with the bytes of `data`."
       ^org.graalvm.polyglot.Value [^org.graalvm.polyglot.Value module ^bytes data]
       ;; 8 bytes at a time, because a polyglot Value has no bulk write.
       (let [n     (alength data)
             u8    (.newInstance (.getMember (.getBindings (.getContext module) "js")
                                             "Uint8Array")
                                 (object-array [n]))
             buf   (.getMember u8 "buffer")
             le    java.nio.ByteOrder/LITTLE_ENDIAN
             bb    (.order (java.nio.ByteBuffer/wrap data) le)
             whole (- n (rem n 8))]
         (loop [i 0]
           (when (< i whole)
             (.writeBufferLong buf le (long i) (.getLong bb (int i)))
             (recur (+ i 8))))
         (loop [i whole]
           (when (< i n)
             (.writeBufferByte buf (long i) (aget data i))
             (recur (inc i))))
         u8))

     (defn- memfs-write-file!
       "Write `data` to `path` in the MEMFS of `module`."
       [^org.graalvm.polyglot.Value module ^String path ^bytes data]
       (fs-call module "writeFile" path (js-u8 module data)))

     (defn heap-write!
       "Copy `data` into the wasm heap of `module` at the address `addr`."
       [^org.graalvm.polyglot.Value module addr ^bytes data]
       (let [ctx (.getContext module)]
         (locking ctx
           ;; HEAPU8 at each call, because memory growth detaches an old view.
           (.invokeMember (.getMember module "HEAPU8") "set"
                          (object-array [(js-u8 module data) (int addr)])))))

     (defn- memfs-read-file
       ^bytes [^org.graalvm.polyglot.Value module path]
       (let [u8  (fs-call module "readFile" path)
             n   (.asInt (.getMember u8 "length"))
             out (byte-array n)]
         (.readBuffer (.getMember u8 "buffer")
                      (.asLong (.getMember u8 "byteOffset")) out 0 n)
         out))

     (defn- memfs-dir?
       [module path]
       (let [info (fs-call module "analyzePath" path)]
         (and (.asBoolean (.getMember info "exists"))
              (.asBoolean (.getMember (.getMember info "object") "isFolder")))))

     (defn- memfs-names
       [module dir]
       (when (memfs-dir? module dir)
         (let [v (fs-call module "readdir" dir)]
           (remove #{"." ".."}
                   (map #(.asString (.getArrayElement v (long %)))
                        (range (.getArraySize v)))))))

     (defn- parent-path [path]
       (subs path 0 (str/last-index-of path "/")))

     ;; Each entry is [index-of-the-path-arg mode]. :read copies an existing
     ;; host file into MEMFS. :write also copies the result back to the host
     ;; at gdal-close.
     (def ^:private path-args
       {:GDALOpen            [0 :read]
        :GDALOpenEx          [0 :read]
        :GDALCreate          [1 :write]
        :GDALCreateCopy      [1 :write]
        :GDALVectorTranslate [0 :write]
        :GDALWarp            [0 :write]
        :GDALTranslate       [0 :write]})

     ;; {module {dataset-address {:host File :mirror String :write? bool
     ;;                           :staged #{String}}}}
     ;; Each module has its own MEMFS and addresses, also two modules in one
     ;; Context.
     (defonce ^:private host-datasets (atom {}))

     (defn- host-file
       "The host File of the path arg `s`, or nil to pass `s` as is: for a
        /vsi path, a URL, a read path with no host file, or a write path that
        the host cannot write."
       [s write?]
       (when (and (string? s)
                  (not (str/blank? s))
                  (not (str/starts-with? s "/vsi"))
                  (not (str/includes? s "://")))
         (let [f (-> (io/file s) .getAbsoluteFile .toPath .normalize .toFile)]
           (when (cond
                   (not write?) (.exists f)
                   (.exists f)  (.canWrite f)
                   :else        (let [dir (.getParentFile f)]
                                  (and dir (.isDirectory dir) (.canWrite dir))))
             f))))

     (defn- mirror-path
       "The MEMFS path of the copy of the host File `f`: /host and the
        absolute path. C:\\a\\b gives /host/C/a/b."
       [^java.io.File f]
       (let [p (str/replace (.getPath f) "\\" "/")
             p (if-let [[_ drive more] (re-matches #"([A-Za-z]):(.*)" p)]
                 (str "/" drive more)
                 p)]
         (str "/host" (if (str/starts-with? p "/") p (str "/" p)))))

     (defn- stage-host!
       "Copy the host File `f` into MEMFS at `mirror`: a directory with its
        top-level files, or a file with its family. Returns the set of paths
        that it wrote. Skips each path in `held`, a file of an open dataset."
       [module ^java.io.File f mirror held]
       (let [dir?   (.isDirectory f)
             dir    (if dir? mirror (parent-path mirror))
             copies (for [^java.io.File c (.listFiles (if dir? f (.getParentFile f)))
                          :let [path (str dir "/" (.getName c))]
                          :when (and (.isFile c)
                                     (or dir? (in-family? (.getName f) (.getName c)))
                                     (not (held path)))]
                      [path c])]
         (when dir? (fs-call module "mkdirTree" mirror))
         (doseq [[path ^java.io.File c] copies]
           (memfs-write-file! module path (java.nio.file.Files/readAllBytes (.toPath c))))
         (set (map first copies))))

     (defn- mirror-files
       "The MEMFS paths of the copy at `mirror`: each file below a
        directory, or the family of a file."
       [module mirror]
       (if (memfs-dir? module mirror)
         (letfn [(walk [dir]
                   (mapcat #(let [p (str dir "/" %)]
                              (if (memfs-dir? module p) (walk p) [p]))
                           (memfs-names module dir)))]
           (walk mirror))
         (let [dir (parent-path mirror)
               nm  (subs mirror (inc (count dir)))]
           (for [n (memfs-names module dir)
                 :let [p (str dir "/" n)]
                 :when (and (in-family? nm n) (not (memfs-dir? module p)))]
             p))))

     (defn- copy-back!
       "Make the host at `host` match the MEMFS copy at `mirror`: copy each
        changed file, and delete each file of `staged` that GDAL deleted from
        the copy."
       [module ^java.io.File host mirror staged]
       (let [[dir host-dir] (if (memfs-dir? module mirror)
                              [mirror host]
                              [(parent-path mirror) (.getParentFile host)])
             on-host (fn [p] (io/file host-dir (subs p (inc (count dir)))))
             files   (mirror-files module mirror)]
         (doseq [p files
                 :let [data   (memfs-read-file module p)
                       target (on-host p)]
                 :when (not (and (.isFile target)
                                 (java.util.Arrays/equals
                                  data (java.nio.file.Files/readAllBytes (.toPath target)))))]
           (io/make-parents target)
           (io/copy data target))
         (doseq [p (remove (set files) staged)]
           (io/delete-file (on-host p) true))))

     (defn- held-paths
       [module]
       (set (mapcat #(mirror-files module (:mirror %))
                    (vals (get @host-datasets module)))))

     (defn- forget
       "`registry` with no dataset `k` of `module`, and with no empty entry."
       [registry module k]
       ;; An empty entry holds the module and its Context after the Context
       ;; closes.
       (let [left (dissoc (get registry module) k)]
         (if (empty? left) (dissoc registry module) (assoc registry module left))))

     (defn- remove-empty-dirs!
       [module dir]
       (doseq [n (memfs-names module dir)
               :let [p (str dir "/" n)]
               :when (memfs-dir? module p)]
         (remove-empty-dirs! module p))
       (when (empty? (memfs-names module dir))
         (fs-call module "rmdir" dir)))

     (defn- release-mirror!
       "Delete the MEMFS copy at `mirror`, apart from each file of another
        open dataset of `module`."
       [module mirror]
       (let [held (held-paths module)]
         (doseq [p (mirror-files module mirror) :when (not (held p))]
           (fs-call module "unlink" p))
         (when (memfs-dir? module mirror)
           (remove-empty-dirs! module mirror))))

     (defn- update-open?
       "True when a GDALOpen or GDALOpenEx call opens its dataset for update."
       [fn-key args]
       (case fn-key
         :GDALOpen   (= fndefs/GA_Update (long (nth args 1)))
         :GDALOpenEx (pos? (bit-and fndefs/GDAL_OF_UPDATE (long (nth args 1))))
         false))

     (defn- call-on-host-file
       "Call `fn-key` with the MEMFS copy of `host` as the path arg at `ix`."
       [call fn-key args ix write? ^java.io.File host]
       (let [module (graal-module)
             ctx    (.getContext module)
             mirror (mirror-path host)]
         ;; The monitor that with-graal-lock takes on the default Context.
         (locking ctx
           (fs-call module "mkdirTree" (parent-path mirror))
           (try
             (let [staged (when (.exists host)
                            (stage-host! module host mirror (held-paths module)))
                   result (call fn-key (assoc args ix mirror))]
               (if (nil? result)
                 (release-mirror! module mirror)
                 (swap! host-datasets assoc-in [module (nw/address-as-int result)]
                        {:host host :mirror mirror :write? write? :staged staged}))
               result)
             (catch Throwable t
               (release-mirror! module mirror)
               (throw t))))))

     (defn- close-host-dataset!
       "Call GDALClose on `ds` through `call`, and return its CPLErr. For a
        host path, copy a written dataset back, and delete the MEMFS copy."
       [call ds]
       (let [module (graal-module)
             ctx    (.getContext module)
             k      (some-> ds nw/address-as-int)]
         (locking ctx
           (if-let [{:keys [host mirror write? staged]} (get-in @host-datasets [module k])]
             (do (swap! host-datasets forget module k)
                 (try
                   (let [err (call :GDALClose [ds])]
                     (when write? (copy-back! module host mirror staged))
                     err)
                   (finally
                     (release-mirror! module mirror))))
             (call :GDALClose [ds])))))

     (defn host-path-call
       "Return `(call fn-key args)`, with the MEMFS copy of a host file in
        place of the path arg of a fn in path-args. GDALClose copies a written
        copy back to the host."
       [call fn-key args]
       (let [[ix mode] (path-args fn-key)
             write?    (and ix (or (not= :read mode) (update-open? fn-key args)))
             host      (when ix (host-file (nth args ix) write?))]
         (cond
           host                  (call-on-host-file call fn-key args ix write? host)
           (= :GDALClose fn-key) (close-host-dataset! call (first args))
           :else                 (call fn-key args))))))

;; Only the clj-native accessors read the atoms of the wiring, because a
;; squint Atom from a different package instance does not support deref.
#?(:cljs
   (defonce ^:private pool-wiring (wp/make-wiring!)))

#?(:cljs
   (defn current-pool
     "Return the live joint pool, or nil when no pool runs."
     []
     (wp/wiring-pool pool-wiring)))

#?(:cljs
   (defn ^:async handler-spec
     "The {:module :args} spec of the GDAL handler for a caller pool.
      Register it under :net.willcohen.gdal before the pool starts."
     []
     (let [resources (await (.loadGdalResources gdal-loader))]
       {:module (.-href (js/URL. "./gdal-handler.mjs" (.-url js/import.meta)))
        :args   (js-obj "dbBytes" (.-projDb resources))})))

#?(:cljs
   (defn ^:async init-workers!
     "Start the pool, or wire the caller pool :pool of `opts`, and return it.
      A later or concurrent call returns the same pool and ignores its
      `opts`."
     [opts]
     (await
      (if-let [caller-pool (:pool opts)]
        (wp/ensure-wired! pool-wiring {:pool caller-pool})
        ;; One worker, because a dataset and a buffer are valid only in the
        ;; module of the worker that made them.
        (wp/ensure-wired!
         pool-wiring
         {:registry-opts {:size 1}
          :register!
          (fn ^:async register-gdal! [reg]
            (wp/register-handler! reg :compute :net.willcohen.gdal
                                  (await (handler-spec))))})))))

#?(:cljs
   (defn ^:async shutdown-workers!
     "Stop the pool that init-workers! started, to let Node.js exit. A
      caller pool continues to run. A later gdal call starts a new pool."
     []
     (await (wp/shutdown-wiring! pool-wiring))
     nil))
