;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

#?(:clj
   (ns net.willcohen.gdal.gdal
     "The Clojure API of GDAL and OGR on the JVM. When GDAL fails, a helper
      throws an ex-info. Its message ends with the GDAL error message."
     (:refer-clojure :exclude [await])
     (:require [clojure.string :as str]
               [net.willcohen.gdal.impl.native :as native]
               [net.willcohen.gdal.fndefs :as fndefs]
               [net.willcohen.gdal.macros :refer [define-all-gdal-public-fns]]
               [net.willcohen.gdal.wasm :as gwasm]
               [net.willcohen.native.platform-state :as nps]
               [net.willcohen.native.graal-wasm :as nw]
               [net.willcohen.native.dispatch :as dispatch]
               [net.willcohen.native.ffi-mem :as ffi-mem]
               [tech.v3.datatype :as dt]
               [tech.v3.datatype.ffi :as dt-ffi]
               [tech.v3.datatype.ffi.ptr-value :as dt-ptr]
               [tech.v3.datatype.native-buffer :as dt-nb]
               [tech.v3.resource :as resource])
     (:import [java.nio ByteBuffer ByteOrder]))
   :cljs
   (ns gdal
     "The ClojureScript API of GDAL and OGR. Each public fn calls GDAL in a
      worker and returns a Promise, which rejects where the JVM fn throws.
      In place of a JVM seq, give a JS array, or for numbers a typed array.
      A byte[] is a Uint8Array, a double[] is a Float64Array, a map is a JS
      object, a vector is a JS array, and a 64-bit integer is a BigInt."
     ;; One module, because the bare name costs a page one importmap entry.
     ;; The aliases keep the names of the JVM branch.
     #_{:clj-kondo/ignore [:duplicate-require]}
     (:require [clojure.string :as str]
               [fndefs :as fndefs]
               [wasm :as wasm]
               [macros :refer [define-all-gdal-public-fns]]
               ["./host-path.mjs" :as host-reader]
               ["ffi-wasm" :as nps]
               ["ffi-wasm" :as pool]
               ["ffi-wasm" :as dispatch])))

;; squint compiles await. A JVM call returns its value.
#?(:clj (defmacro await [x] x))

(def ^:private data-type-heap-types
  {fndefs/GDT_Byte :u8 fndefs/GDT_Int8 :i8 fndefs/GDT_UInt16 :u16 fndefs/GDT_Int16 :i16
   fndefs/GDT_UInt32 :u32 fndefs/GDT_Int32 :i32 fndefs/GDT_Float32 :f32 fndefs/GDT_Float64 :f64})

(def ^:private heap-type-bytes
  {:u8 1 :i8 1 :u16 2 :i16 2 :u32 4 :i32 4 :f32 4 :f64 8})

(defn- heap-type
  [dt]
  (or (get data-type-heap-types dt)
      (throw (ex-info (str "GDAL data type " dt " has no reader: read-raster-band reads Byte,"
                           " Int8, UInt16, Int16, UInt32, Int32, Float32 and Float64")
                      {:dt dt}))))

(defn- check-option-strings!
  "Throw unless `options` is nil or a sequence of strings."
  [options]
  (when (or (string? options) (not (every? string? options)))
    (throw (ex-info "build-csl-options takes a sequence of strings" {:options options}))))

(defn- metadata-map
  "The map of {key -> value} of the \"KEY=VALUE\" strings `kvs`."
  [kvs]
  (reduce (fn [m kv]
            (let [i (str/index-of kv "=")]
              (if (and i (pos? i))
                (assoc m (subs kv 0 i) (subs kv (inc i)))
                m)))
          {}
          kvs))

(defn- check-count!
  "Throw unless `n` is `expected`."
  [fn-name what expected n]
  (when-not (= expected n)
    (throw (ex-info (str fn-name " takes " expected " " what ", not " n)
                    {:expected expected :count n}))))

(defn- check-sources!
  [srcs dst-filename]
  (when-not (and (sequential? srcs) (seq srcs))
    (throw (ex-info "warp-raster! takes a sequence of one or more source datasets, for example [ds]"
                    {:dst dst-filename}))))

(defn- failure-info
  "The ex-info of the failed call `what`, with the GDAL error `no` and
   `msg` when `msg` is not blank."
  [what data no msg]
  (if (str/blank? msg)
    (ex-info what data)
    (ex-info (str what ": " msg) (assoc data :cpl-err-no no :cpl-msg msg))))

(defn- extent-map
  [[min-x max-x min-y max-y]]
  {:min-x min-x :max-x max-x :min-y min-y :max-y max-y})

(defn- date-time-map
  [values]
  (zipmap [:year :month :day :hour :minute :second :tz-flag] values))

(defn- length
  "The count of `xs`, a seq on the JVM, or a JS array or a typed array."
  [xs]
  #?(:clj (count xs) :cljs (.-length xs)))

(def ^:private heap-views
  {:u8 "HEAPU8" :i8 "HEAP8" :u16 "HEAPU16" :i16 "HEAP16" :u32 "HEAPU32" :i32 "HEAP32"
   :f32 "HEAPF32" :f64 "HEAPF64"})

;; A slot is a buffer arg. gdal-call on the JVM and the ccall of the worker
;; (gdal-handler-overrides.mjs) allocate, fill, read and free it.
(defn- in-bytes [bytes] {:slot true :bytes bytes :size (length bytes)})

(def ^:private out-pointer {:slot true :read "ptr" :size 4})

(def ^:private out-vsi-string {:slot true :read "vsi-string" :size 4})

(defn- out-values
  "An out slot for `n` values of the heap view `view`."
  [view n]
  {:slot true :read "values" :view (get heap-views view) :n n
   :size (* n (get heap-type-bytes view))})

(defn- numbers->bytes
  "The little-endian bytes of the numbers `xs` as the heap view `view`, :f64
   or :i32, or in JS also :u32."
  [view xs]
  #?(:clj (let [bb (.order (ByteBuffer/allocate (* (long (heap-type-bytes view)) (count xs)))
                           ByteOrder/LITTLE_ENDIAN)]
            (doseq [x xs]
              (case view
                :f64 (.putDouble bb (double x))
                :i32 (.putInt bb (int x))))
            (.array bb))
     :cljs (js/Uint8Array. (.-buffer (.from (case view
                                               :f64 js/Float64Array
                                               :i32 js/Int32Array
                                               :u32 js/Uint32Array)
                                             xs)))))

(defn- c-string-bytes
  "The UTF-8 bytes of the string `s`, and a NUL."
  [s]
  #?(:clj (let [b (.getBytes (str s) "UTF-8")]
            (java.util.Arrays/copyOf b (inc (alength b))))
     :cljs (let [utf8 (.encode (js/TextEncoder.) s)
                 out  (js/Uint8Array. (inc (.-length utf8)))]
             (.set out utf8)
             out)))

(defn- ones
  "`n` bytes of 0xFF."
  [n]
  #?(:clj (byte-array n (byte -1)) :cljs (.fill (js/Uint8Array. n) 255)))

;; A defonce, because `lib` holds this atom by identity, and a def makes
;; a new atom at each reload.
#?(:clj
   (defonce ^:private implementation (atom nil)))

#?(:clj
   (defonce ^:private force-graal (atom false)))

#?(:clj
   (defn graal?
     "True when init! selected the GraalVM backend."
     []
     (nps/graal? implementation)))

#?(:clj
   (defn force-graal!
     "Make the next init! select the GraalVM backend."
     []
     (nps/force-graal! implementation force-graal)))

#?(:clj
   (defonce ^:private lib
     (dispatch/library {:key :net.willcohen.gdal
                        :fndefs fndefs/fndefs
                        :impl-atom implementation
                        :ffi-impl-ns 'net.willcohen.gdal.impl.native})))

#?(:clj
   (defn- write-bytes!
     "Copy the byte[] `data` to `ptr`, a pointer or a with-scratch buffer."
     [ptr ^bytes data]
     (if (graal?)
       (gwasm/heap-write! (gwasm/graal-module) (nw/address-as-int ptr) data)
       (ffi-mem/copy-bytes! (ffi-mem/ptr-addr ptr) data))))

#?(:clj
   (defn- with-scratch
     "Return `(f buf)` for a new buffer of `nbytes`, and free the buffer
      after `f`. On GraalVM, `f` runs with *wasm-context* bound."
     [nbytes f]
     (if (graal?)
       (nw/with-library-context :net.willcohen.gdal
         (let [buf (nw/malloc nbytes)]
           (try (f buf)
                (finally (nw/free-on-heap buf)))))
       (resource/stack-resource-context
        (f (dt-nb/malloc nbytes {}))))))

#?(:clj
   (defn- buffer-pointer
     "A pointer `offset` bytes into `buf`, a with-scratch buffer."
     [buf offset]
     (let [a (+ (long (if (graal?) (nw/address-as-int buf) (dt-ptr/ptr-value buf)))
                (long offset))]
       (if (graal?)
         (nw/address-as-trackable-pointer a)
         (dt-ffi/->pointer a)))))

#?(:clj
   (def ^:private heap-type-datatypes
     {:u8 :int8 :i8 :int8 :u16 :int16 :i16 :int16 :u32 :int32 :i32 :int32
      :f32 :float32 :f64 :float64}))

;; One bulk copy, because nw/read-heap-array makes one polyglot call for
;; each value: 1M values take about 20 ms, and this takes under 1 ms.
#?(:clj
   (defn- read-heap-values
     "read-values on GraalVM."
     [ptr n view]
     (let [^org.graalvm.polyglot.Value module (gwasm/graal-module)
           ctx    (.getContext module)
           nbytes (int (* (long n) (long (heap-type-bytes view))))
           bs     (byte-array nbytes)
           _      (locking ctx
                    (.readBuffer (.getMember (.getMember module "HEAPU8") "buffer")
                                 (long (nw/address-as-int ptr)) bs 0 nbytes))
           bb     (.order (ByteBuffer/wrap bs) ByteOrder/LITTLE_ENDIAN)]
       (case view
         (:u8 :i8)   bs
         (:u16 :i16) (let [a (short-array n)] (.get (.asShortBuffer bb) a) a)
         (:u32 :i32) (let [a (int-array n)] (.get (.asIntBuffer bb) a) a)
         :f32        (let [a (float-array n)] (.get (.asFloatBuffer bb) a) a)
         :f64        (let [a (double-array n)] (.get (.asDoubleBuffer bb) a) a)))))

#?(:clj
   (defn- read-values
     "The `n` values of the heap view `view` at `ptr`, as a primitive array."
     [ptr n view]
     (cond
       (graal?)          (read-heap-values ptr n view)
       (zero? (long n))  (dt/->array (dt-nb/malloc 0 {:datatype (heap-type-datatypes view)}))
       :else             (dt/->array (dt-nb/wrap-address (ffi-mem/ptr-addr ptr)
                                                         (* (long n) (long (heap-type-bytes view)))
                                                         (heap-type-datatypes view)
                                                         :little-endian nil)))))

#?(:clj
   (defn- read-string-list
     "The strings of the NULL-terminated char** `ptr`, or [] for nil."
     [ptr]
     (cond
       (nil? ptr) []
       (graal?)   (nw/with-library-context :net.willcohen.gdal
                    (nw/string-array-pointer->strs ptr))
       :else      (ffi-mem/read-string-array (ffi-mem/ptr-addr ptr)))))

#?(:clj
   (defn- address->pointer
     [addr]
     (when-not (zero? (long addr))
       (if (graal?)
         (nw/address-as-trackable-pointer addr)
         (dt-ffi/->pointer addr)))))

#?(:clj
   (defn- pointer-address
     ^long [ptr]
     (long (if (graal?) (nw/address-as-int ptr) (ffi-mem/ptr-addr ptr)))))

#?(:clj
   (defn- pointer-bytes
     "The little-endian C array of the pointers `ptrs`: 4 bytes each on
      GraalVM, 8 on FFI."
     ^bytes [ptrs]
     (let [width (if (graal?) 4 8)
           bb    (.order (ByteBuffer/allocate (* width (count ptrs))) ByteOrder/LITTLE_ENDIAN)]
       (doseq [p ptrs]
         (if (= 4 width)
           (.putInt bb (int (pointer-address p)))
           (.putLong bb (pointer-address p))))
       (.array bb))))

;; The slot of a pointer holds 8 zeroed bytes, thus a 4-byte wasm pointer
;; reads as a long too.
#?(:clj
   (defn- read-pointer
     "The pointer at `ptr`, or nil for NULL."
     [ptr]
     (let [^bytes b (read-values ptr 8 :u8)]
       (address->pointer (.getLong (.order (ByteBuffer/wrap b) ByteOrder/LITTLE_ENDIAN))))))

(defn- in-pointers
  "An in slot with the C array of the pointers `ptrs`."
  [ptrs]
  (in-bytes #?(:clj (pointer-bytes ptrs) :cljs (numbers->bytes :u32 ptrs))))

#?(:clj
   (defn- raw-call
     "Call the GDAL C fn of `fn-key` with the vector `args`. A NULL pointer
      gives nil."
     [fn-key args]
     (let [call (partial dispatch/call! lib)]
       (if (graal?)
         (gwasm/host-path-call call fn-key args)
         (call fn-key args)))))

;; The slot code of gdal-call calls these, and define-all-gdal-public-fns
;; defines them. A call through the var lets a test redefine one.
#?(:clj
   (declare vsi-free csl-destroy cpl-error-reset))

#?(:clj
   (defn- take-string
     "The string of the char* `ptr`, or nil for nil. Frees `ptr` with
      vsi-free."
     [ptr]
     (when ptr
       (try
         (if (graal?)
           (nw/with-library-context :net.willcohen.gdal (nw/pointer->string ptr))
           (dt-ffi/c->string ptr))
         (finally
           (vsi-free ptr))))))

#?(:clj
   (def ^:private heap-view-types
     (into {} (map (fn [[k v]] [v k])) heap-views)))

#?(:clj
   (defn- slot? [a] (and (map? a) (:slot a))))

#?(:clj
   (defn- slot-bytes
     "The scratch bytes of `slot`: 8 or more, a multiple of 8."
     ^long [{:keys [bytes read view n]}]
     (let [size (max 8
                     (if bytes (alength ^bytes bytes) 0)
                     (if (= "values" read)
                       (* (long n) (long (heap-type-bytes (heap-view-types view))))
                       0))]
       (* 8 (quot (+ size 7) 8)))))

#?(:clj
   (defn- read-slot
     [ptr {:keys [read view n]}]
     (case read
       "values"     (read-values ptr n (heap-view-types view))
       "ptr"        (read-pointer ptr)
       "vsi-string" (or (take-string (read-pointer ptr)) "")
       nil)))

#?(:clj
   (defn- read-result
     "The value of the C result `ptr` for the :read-result `kind`, as the
      readResult of gdal-handler-overrides.mjs gives it."
     [kind ptr out]
     (case kind
       :owned-string      (take-string ptr)
       :string-list       (read-string-list ptr)
       :owned-string-list (try (read-string-list ptr)
                               (finally (when ptr (csl-destroy ptr))))
       :bytes             (when ptr
                            (let [^ints w (first out)
                                  n       (+ (Integer/toUnsignedLong (aget w 0))
                                             (if (< 1 (alength w))
                                               (bit-shift-left (Integer/toUnsignedLong (aget w 1)) 32)
                                               0))]
                              (read-values ptr n :u8))))))

;; A values slot is not zeroed, because GDAL writes each out value, and a
;; raster out slot can be large.
#?(:clj
   (defn- call-with-slots
     "Call `fn-key` as the ccall of gdal-handler-overrides.mjs does. Each slot
      arg gets a part of one scratch buffer, and a pointer to it, or for
      :indirect a pointer to a cell that holds it, takes its place. The call
      resets the GDAL error first. Returns the result, or {:result :out} with
      one :out element for each slot when a slot has :read. The :read-result
      `kind` replaces the result."
     [fn-key args kind]
     (let [slots   (into [] (keep-indexed (fn [i a] (when (slot? a) [i a]))) args)
           sizes   (mapv (comp slot-bytes second) slots)
           offsets (vec (reductions + 0 sizes))
           cells   (peek offsets)
           total   (+ cells (* 8 (count (filter (comp :indirect second) slots))))]
       (with-scratch (max 8 total)
         (fn [buf]
           (let [[call-args ptrs]
                 (reduce (fn [[call-args ptrs cell] [j [i {:keys [bytes read indirect]}]]]
                           (let [p (buffer-pointer buf (offsets j))]
                             (cond
                               bytes                (write-bytes! p bytes)
                               (not= "values" read) (write-bytes! p (byte-array (sizes j))))
                             (if indirect
                               (let [c (buffer-pointer buf cell)]
                                 (write-bytes! c (.array (.putLong (.order (ByteBuffer/allocate 8)
                                                                           ByteOrder/LITTLE_ENDIAN)
                                                                   (pointer-address p))))
                                 [(assoc call-args i c) (conj ptrs p) (+ cell 8)])
                               [(assoc call-args i p) (conj ptrs p) cell])))
                         [(vec args) [] cells]
                         (map-indexed vector slots))
                 _      (cpl-error-reset)
                 result (raw-call fn-key call-args)
                 out    (mapv (fn [p [_ slot]] (read-slot p slot)) ptrs slots)
                 result (if kind (read-result kind result out) result)]
             (if (some (comp :read second) slots)
               {:result result :out out}
               result)))))))

#?(:clj
   (defn- gdal-call
     "Call the GDAL C fn of `fn-key` with `args`. The slot args and the
      :read-result of its fndef work as in JS. A NULL pointer gives nil."
     [fn-key args]
     (let [kind (:read-result (get fndefs/fndefs fn-key))]
       (if (or kind (some slot? args))
         (call-with-slots fn-key args kind)
         (raw-call fn-key (vec args))))))

#?(:cljs
   (defn ^:async init!
     "Start the worker pool, or use the caller pool :pool of `opts`, and
      return it. A later or concurrent call returns the same pool. Each
      public fn calls init! first."
     ([] (init! nil))
     ([opts]
      (await (wasm/init-workers! opts)))))

;; For JS `gdal.init()`.
#?(:cljs
   (def init init!))

#?(:cljs
   (def shutdown! wasm/shutdown-workers!))

#?(:cljs
   (def shutdown shutdown!))

#?(:cljs
   (def handler-spec wasm/handler-spec))

#?(:cljs
   (defn- slot-extras
     "The :extras-builder hook. Each slot arg goes in :slots with its
      :argIdx, and 0 takes its place. The :read-result of `fn-def` goes as
      :result."
     [fn-def args]
     (let [slots  (vec (keep-indexed (fn [i a] (when (:slot a) (assoc a :argIdx i))) args))
           kind   (:read-result fn-def)
           extras (cond-> {}
                    (seq slots) (assoc :slots slots)
                    kind        (assoc :result kind))]
       (if (empty? extras)
         {:args args}
         {:args (mapv #(if (:slot %) 0 %) args) :extras extras}))))

#?(:cljs
   (defonce ^:private lib
     (dispatch/library {:key :net.willcohen.gdal
                        :fndefs fndefs/fndefs
                        :hooks {:extras-builder slot-extras}})))

#?(:cljs
   (defn- ^:async dispatch-gdal
     "Start the pool, then call the GDAL fn `c-fn-name`, a string key of
      fndefs."
     [c-fn-name args]
     (await (init!))
     (await (dispatch/call! lib c-fn-name args #js {:pool (wasm/current-pool)}))))

;; The kondo hook declares each generated fn here, a second time for the
;; three that gdal-call declares.
#_{:clj-kondo/ignore [:redundant-declare]}
(define-all-gdal-public-fns)

;; GDAL warns at the first GPKG create when GDAL_DATA has no value. Any
;; dir serves, because the build has no data files. The GraalVM loader
;; sets it.
#?(:clj
   (defn- set-gdal-data!
     "Set GDAL_DATA when it has no value."
     []
     (when (str/blank? (cpl-get-config-option "GDAL_DATA" nil))
       (when-let [dir (:path @native/gdal)]
         (cpl-set-config-option "GDAL_DATA" dir)))))

;; The backend that the last init! completed. force-graal! clears
;; `implementation`, and a failed init! does not set this.
#?(:clj
   (defonce ^:private ready (atom nil)))

#?(:clj
   (defn init!
     "Start the FFI backend, or GraalVM after force-graal! or when FFI
      cannot start, and register each driver. A second call does nothing
      until force-graal!."
     []
     (locking ready
       (when-not (and @implementation (= @implementation @ready))
         (nps/try-init! implementation force-graal false
                        native/init-gdal gwasm/init-graal-module!)
         (set-gdal-data!)
         (gdal-all-register)
         ;; A resolve at run time, because network requires this namespace.
         ((requiring-resolve 'net.willcohen.gdal.network/setup-http-callback!))
         (reset! ready @implementation)))
     nil))

(defn- ^:async gdal-failure
  "The failure-info of `what` with the last GDAL error. Resets the error."
  [what data]
  (let [no  (await (cpl-get-last-error-no))
        msg (when-not (zero? no) (await (cpl-get-last-error-msg)))]
    (await (cpl-error-reset))
    (failure-info what data no msg)))

(defn- ^:async check!
  [what data code]
  (when-not (zero? code)
    (throw (await (gdal-failure what (assoc data :err code))))))

#?(:cljs
   (defn- ^:async worker-method
     "Start the pool, then call the handler method `method` with the JS
      array `args` on worker 0."
     [method args]
     (await (init!))
     (await (pool/worker-call (wasm/current-pool) :net.willcohen.gdal method args 0))))

(defn- ^:async read-out-array
  "Return the `n` values of the heap view `view` that `(f slot)` writes to
   an out slot. `f` returns a CPLErr or an OGRErr for check!."
  [what data n view f]
  (let [r (await (f (out-values view n)))]
    (await (check! what data (:result r)))
    (first (:out r))))

(defn- ^:async raster-band
  "Band `band-num` of `dataset`. Throws if the band does not exist."
  [dataset band-num]
  (let [band (await (gdal-get-raster-band dataset band-num))]
    (when (nps/null-ptr? band)
      (throw (await (gdal-failure (str "GDALGetRasterBand returned NULL for band " band-num)
                               {:dataset dataset :band-num band-num}))))
    band))

(defn ^:async read-raster-band
  "Read each pixel of band `band-num` (from 1) of `dataset` into the
   byte[], short[], int[], float[] or double[] of the band type. Byte 255
   reads as -1. Throws for an Int64, UInt64, Float16 or complex band. In JS,
   the array is the typed array of the band type, for example a Uint8Array
   for Byte."
  [dataset band-num]
  (let [band (await (raster-band dataset band-num))
        xs   (await (gdal-get-raster-band-x-size band))
        ys   (await (gdal-get-raster-band-y-size band))
        dt   (await (gdal-get-raster-data-type band))]
    (await (read-out-array "GDALRasterIO failed" {:dataset dataset :band-num band-num}
                        (* xs ys) (heap-type dt)
                        #(gdal-raster-io band fndefs/GF_Read 0 0 xs ys % xs ys dt 0 0)))))

(defn ^:async write-raster-band!
  "Write the numbers `values`, row by row, to each pixel of band
   `band-num` of `dataset`. Give 255 for a Byte, not -1. Throws unless the
   count of `values` is the pixel count."
  [dataset band-num values]
  (let [band (await (raster-band dataset band-num))
        xs   (await (gdal-get-raster-band-x-size band))
        ys   (await (gdal-get-raster-band-y-size band))]
    (check-count! "write-raster-band!" "values" (* xs ys) (length values))
    (await (check! "GDALRasterIO failed" {:dataset dataset :band-num band-num}
                   (await (gdal-raster-io band fndefs/GF_Write 0 0 xs ys
                                          (in-bytes (numbers->bytes :f64 values)) xs ys
                                          fndefs/GDT_Float64 0 0))))))

(defn get-geo-transform
  "Return the geotransform of `dataset` as a double[] of origin-x,
   pixel-width, row-rotation, origin-y, column-rotation and pixel-height."
  [dataset]
  (read-out-array "GDALGetGeoTransform failed" {:dataset dataset} 6 :f64
                  #(gdal-get-geo-transform dataset %)))

(defn ^:async set-geo-transform!
  "Set the geotransform of `dataset` to the 6 numbers `gt`, in the order of
   get-geo-transform."
  [dataset gt]
  (check-count! "set-geo-transform!" "values" 6 (length gt))
  (await (check! "GDALSetGeoTransform failed" {:dataset dataset}
                 (await (gdal-set-geo-transform dataset (in-bytes (numbers->bytes :f64 gt)))))))

(defn ^:async build-overviews!
  "Build an overview of each band of `dataset` for each factor of
   `factors`, for example [2 4], with the resampling method `resampling`,
   for example \"AVERAGE\"."
  [dataset resampling factors]
  (await (check! "GDALBuildOverviews failed" {:dataset dataset :resampling resampling}
                 (await (gdal-build-overviews dataset resampling (length factors)
                                              (in-bytes (numbers->bytes :i32 factors))
                                              0 nil nil nil)))))

(defn ^:async band-nodata
  "Return the nodata value of `band` as a double, or nil when the band has
   none."
  [band]
  ;; An if with nil, because squint gives a tail `when` of a boolean test no
  ;; else branch, and the fn then returns undefined.
  (let [r (await (gdal-get-raster-no-data-value band (out-values :i32 1)))]
    (if (= 1 (aget (first (:out r)) 0))
      (:result r)
      nil)))

(defn ^:async band-block-size
  "Return the natural block size of `band` as [x-size y-size]."
  [band]
  (let [r (await (gdal-get-block-size band (out-values :i32 1) (out-values :i32 1)))]
    [(aget (first (:out r)) 0) (aget (second (:out r)) 0)]))

(defn ^:async build-csl-options
  "Return a GDAL string list (char**) of the strings `options`, or nil for
   none. Free it with csl-destroy. Throws unless `options` is a seq of
   strings."
  [options]
  (check-option-strings! options)
  (loop [xs  (seq options)
         csl nil]
    (if xs
      (recur (next xs) (await (csl-add-string csl (first xs))))
      csl)))

(defn- ^:async call-with-app-options
  "Return `(f opts)` for the app options that `options-new` makes from
   `option-strings`, and free them after. Throws when `options-new` returns
   NULL. In JS, `f` returns a Promise."
  [{:keys [app-name options-new options-free]} option-strings f]
  (let [csl (await (build-csl-options option-strings))]
    (try
      (await (cpl-error-reset))
      (let [opts (await (options-new csl nil))]
        (when (nps/null-ptr? opts)
          (throw (await (gdal-failure (str app-name " options failed")
                                   {:options option-strings}))))
        (try (await (f opts))
             (finally (await (options-free opts)))))
      (finally (await (csl-destroy csl))))))

(defn- ^:async run-app!
  "Run `app` on the datasets `src-vec` into `dst-filename`, and return the
   new dataset. `app` has :run, a C fn with the args of GDALWarp, or :call,
   a fn of dst-filename, src-vec and opts."
  [{:keys [app-name run call] :as app} src-vec dst-filename option-strings]
  (let [n      (length src-vec)
        result (await (call-with-app-options
                       app option-strings
                       (fn ^:async run-with-options [opts]
                         (if call
                           (await (call dst-filename src-vec opts))
                           (await (run dst-filename nil n (in-pointers src-vec) opts nil))))))]
    (when (nps/null-ptr? result)
      (throw (await (gdal-failure (str app-name " returned NULL")
                                  {:dst dst-filename :options option-strings :src-count n}))))
    result))

(defn- ^:async app-info
  "The text of the GDAL info app of `app` for `dataset`."
  [{:keys [app-name run] :as app} dataset option-strings]
  (let [text (await (call-with-app-options app option-strings #(run dataset %)))]
    (when (nil? text)
      (throw (await (gdal-failure (str app-name " returned NULL") {:options option-strings}))))
    text))

(defn translate-vector!
  "Translate `src-dataset` into `dst-filename` as ogr2ogr does with the
   options `option-strings`, for example [\"-f\" \"GeoJSON\"]. Returns the
   new dataset."
  [src-dataset dst-filename option-strings]
  (run-app! {:app-name     "GDALVectorTranslate"
             :run          gdal-vector-translate
             :options-new  gdal-vector-translate-options-new
             :options-free gdal-vector-translate-options-free}
            [src-dataset] dst-filename option-strings))

;; ^:async, because a throw of check-sources! must reject in JS.
(defn ^:async warp-raster!
  "Warp the seq of one or more datasets `src-datasets` into one dataset at
   `dst-filename`, as gdalwarp does with the options `option-strings`, for
   example [\"-t_srs\" \"EPSG:3857\"]. Returns the new dataset."
  [src-datasets dst-filename option-strings]
  (check-sources! src-datasets dst-filename)
  (run-app! {:app-name     "GDALWarp"
             :run          gdal-warp
             :options-new  gdal-warp-app-options-new
             :options-free gdal-warp-app-options-free}
            (vec src-datasets) dst-filename option-strings))

(defn translate-raster!
  "Translate the raster `src-dataset` into `dst-filename` as
   gdal_translate does with the options `option-strings`, for example
   [\"-of\" \"PNG\"]. Returns the new dataset."
  [src-dataset dst-filename option-strings]
  (run-app! {:app-name     "GDALTranslate"
             :call         (fn [dst [src] opts] (gdal-translate dst src opts nil))
             :options-new  gdal-translate-options-new
             :options-free gdal-translate-options-free}
            [src-dataset] dst-filename option-strings))

(defn raster-info
  "Return the text that gdalinfo gives for `dataset` with the options
   `option-strings`, for example [\"-json\"]."
  ([dataset] (raster-info dataset nil))
  ([dataset option-strings]
   (app-info {:app-name     "GDALInfo"
              :run          gdal-info
              :options-new  gdal-info-options-new
              :options-free gdal-info-options-free}
             dataset option-strings)))

(defn vector-info
  "Return the text that ogrinfo gives for `dataset` with the options
   `option-strings`, for example [\"-so\" \"-al\"]."
  ([dataset] (vector-info dataset nil))
  ([dataset option-strings]
   (app-info {:app-name     "GDALVectorInfo"
              :run          gdal-vector-info
              :options-new  gdal-vector-info-options-new
              :options-free gdal-vector-info-options-free}
             dataset option-strings)))

(defn- ^:async export-string
  "Return the string that `(export! slot)` writes to a char** slot.
   `export!` returns an OGRErr. `what` is the C name of the export fn."
  [what export!]
  (let [r (await (export! out-vsi-string))]
    (await (check! (str what " failed") {} (:result r)))
    (first (:out r))))

(defn ^:async srs-export-to-wkt
  "Return `srs` as WKT1, or as OSRExportToWktEx gives it with the seq
   `options`, for example [\"FORMAT=WKT2_2019\"]."
  ([srs]
   (await (export-string "OSRExportToWkt" #(osr-export-to-wkt srs %))))
  ([srs options]
   (let [csl (await (build-csl-options options))]
     (try
       (await (export-string "OSRExportToWktEx" #(osr-export-to-wkt-ex srs % csl)))
       (finally (await (csl-destroy csl)))))))

(defn srs-export-to-projjson
  "Return `srs` as a PROJJSON string."
  [srs]
  (export-string "OSRExportToPROJJSON" #(osr-export-to-projjson srs % nil)))

(defn ^:async transform-points
  "Transform the 2D points of the numbers `xs` and `ys` with the
   OGRCoordinateTransformationH `oct`. Returns {:xs :ys} of double[]. Throws
   if a point fails. EPSG:4326 gives latitude first: refer to
   osr-set-axis-mapping-strategy."
  [oct xs ys]
  (let [n      (length xs)
        in-out #(assoc (out-values :f64 n) :bytes (numbers->bytes :f64 %))]
    (check-count! "transform-points" "ys" n (length ys))
    (let [r (await (oct-transform oct n (in-out xs) (in-out ys) nil))]
      (when (= 0 (:result r))
        (throw (await (gdal-failure "OCTTransform failed" {:point-count n}))))
      {:xs (first (:out r)) :ys (second (:out r))})))

(defn ^:async geometry-from-wkb
  "Make an OGRGeometryH from the byte[] `wkb`, with the
   OGRSpatialReferenceH `srs` or none. Give it to
   ogr-f-set-geometry-directly, or destroy it with ogr-g-destroy-geometry."
  ([wkb] (geometry-from-wkb wkb nil))
  ([wkb srs]
   (let [n (length wkb)
         r (await (ogr-g-create-from-wkb (in-bytes wkb) srs out-pointer n))]
     (await (check! "OGR_G_CreateFromWkb failed" {:n-bytes n} (:result r)))
     (second (:out r)))))

(defn ^:async geometry-from-wkt
  "As geometry-from-wkb, from the string `wkt`."
  ([wkt] (geometry-from-wkt wkt nil))
  ([wkt srs]
   (let [r (await (ogr-g-create-from-wkt (assoc (in-bytes (c-string-bytes wkt)) :indirect true)
                                         srs out-pointer))]
     (await (check! "OGR_G_CreateFromWkt failed" {:wkt wkt} (:result r)))
     (second (:out r)))))

(defn ^:async geometry->json
  "Return the OGRGeometryH `geometry` as a GeoJSON geometry."
  [geometry]
  (let [json (await (ogr-g-export-to-json geometry))]
    (when (nil? json)
      (throw (await (gdal-failure "OGR_G_ExportToJson failed" {:geometry geometry}))))
    json))

(defn geometry->wkt
  "Return the OGRGeometryH `geometry` as WKT."
  [geometry]
  (export-string "OGR_G_ExportToWkt" #(ogr-g-export-to-wkt geometry %)))

;; Each public fn gives `export` at its call, thus a with-redefs of the raw
;; fn applies.
(defn- ^:async export-wkb
  "The WKB byte[] of `geom` from `export`, the raw fn of the C fn
   `c-name`. `data` is the ex-data of a failure."
  [c-name export geom byte-order data]
  (await (read-out-array (str c-name " failed") data (await (ogr-g-wkb-size geom)) :u8
                      #(export geom (or byte-order fndefs/wkbNDR) %))))

(defn- ^:async export-feature-wkb
  "export-wkb of the geometry of `feature`, or nil for no geometry. The
   ex-data of a failure has the :fid."
  [c-name export feature byte-order]
  (let [geom (await (ogr-f-get-geometry-ref feature))]
    (if (nps/null-ptr? geom)
      nil
      ;; No feature pointer, because read-vector-features! destroys it first.
      (await (export-wkb c-name export geom byte-order
                      {:fid (await (ogr-f-get-fid feature))})))))

(defn geometry->wkb
  "Return the OGRGeometryH `geom` as a WKB byte[], with the old-style Z
   types. `byte-order` is wkbNDR (little-endian) by default."
  ([geom] (geometry->wkb geom nil))
  ([geom byte-order]
   (export-wkb "OGR_G_ExportToWkb" ogr-g-export-to-wkb geom byte-order {:geometry geom})))

(defn geometry->iso-wkb
  "As geometry->wkb, with the ISO types for Z and M."
  ([geom] (geometry->iso-wkb geom nil))
  ([geom byte-order]
   (export-wkb "OGR_G_ExportToIsoWkb" ogr-g-export-to-iso-wkb geom byte-order {:geometry geom})))

(defn feature->wkb
  "As geometry->wkb, for the geometry of `feature`, or nil for no
   geometry. The ex-data of a failure has the :fid."
  ([feature] (feature->wkb feature nil))
  ([feature byte-order]
   (export-feature-wkb "OGR_G_ExportToWkb" ogr-g-export-to-wkb feature byte-order)))

(defn feature->iso-wkb
  "As feature->wkb, with the ISO types for Z and M."
  ([feature] (feature->iso-wkb feature nil))
  ([feature byte-order]
   (export-feature-wkb "OGR_G_ExportToIsoWkb" ogr-g-export-to-iso-wkb feature byte-order)))

(defn layer-feature-count
  "Return the feature count of `layer` as a Long. `force` is 1 by default:
   GDAL reads each feature when the driver does not keep the count. With
   force 0, such a driver gives -1."
  ([layer] (layer-feature-count layer nil))
  ([layer force]
   (ogr-l-get-feature-count layer (or force 1))))

(defn ^:async layer-extent
  "Return the extent of `layer` as {:min-x :max-x :min-y :max-y}.
   `force` is 1 by default: GDAL reads each feature when the driver does
   not keep the extent."
  ([layer] (layer-extent layer nil))
  ([layer force]
   (extent-map (await (read-out-array "OGR_L_GetExtent failed" {:layer layer} 4 :f64
                                   #(ogr-l-get-extent layer % (or force 1)))))))

(defn ^:async field-date-time
  "Return field `idx` of `feature` as a map of :year :month :day :hour
   :minute :second :tz-flag, or nil for a null field or a field of another
   type."
  [feature idx]
  (let [r (await (apply ogr-f-get-field-as-date-time feature idx
                        (mapv (fn [_] (out-values :i32 1)) (range 7))))]
    (if (= 1 (:result r))
      (date-time-map (map #(aget % 0) (:out r)))
      nil)))

(defn ^:async set-field-binary!
  "Set field `idx` of `feature` to the byte[] `data`."
  [feature idx data]
  (await (ogr-f-set-field-binary feature idx (length data) (in-bytes data))))

(defn ^:async field-binary
  "Return field `idx` of `feature` as a byte[], or nil when GDAL has no
   bytes for it."
  [feature idx]
  (:result (await (ogr-f-get-field-as-binary feature idx (out-values :i32 1)))))

#?(:clj
   (defn- read-field-by-type
     [feature idx field-type]
     (condp = (long field-type)
       fndefs/OFTInteger   (ogr-f-get-field-as-integer feature idx)
       fndefs/OFTReal      (ogr-f-get-field-as-double feature idx)
       fndefs/OFTInteger64 (ogr-f-get-field-as-integer64 feature idx)
       (ogr-f-get-field-as-string feature idx))))

#?(:clj
   (defn- field-schema
     "The [name type] of each field of the OGRFeatureDefnH `layer-defn`."
     [layer-defn]
     (mapv (fn [i]
             (let [fd (ogr-fd-get-field-defn layer-defn i)]
               [(ogr-fld-get-name-ref fd) (ogr-fld-get-type fd)]))
           (range (ogr-fd-get-field-count layer-defn)))))

#?(:clj
   (defn- feature->fields
     "The map of {field-name -> value} of `feature`, with nil for a field
      that is null or not set. `schema` is the field-schema of its layer."
     [feature schema]
     (into {}
           (map-indexed (fn [i [field-name field-type]]
                          [field-name
                           (when (= 1 (ogr-f-is-field-set-and-not-null feature i))
                             (read-field-by-type feature i field-type))]))
           schema)))

#?(:clj
   (defn- read-and-destroy-feature!
     [feat schema]
     (try
       {:fid    (ogr-f-get-fid feat)
        :fields (feature->fields feat schema)
        :wkb    (export-feature-wkb "OGR_G_ExportToWkb" ogr-g-export-to-wkb feat nil)}
       (finally
         (ogr-f-destroy feat)))))

#?(:clj
   (defn read-vector-features!
     "Read each feature of `layer`, from the first, into a vector of
      {:fid Long :fields {name -> value} :wkb byte[]}. A null field or no
      geometry gives nil."
     [layer]
     (let [schema (field-schema (ogr-l-get-layer-defn layer))]
       (ogr-l-reset-reading layer)
       (loop [out []]
         (if-let [feat (ogr-l-get-next-feature layer)]
           (recur (conj out (read-and-destroy-feature! feat schema)))
           out)))))

#?(:cljs
   (defn ^:async read-vector-features!
     "As the JVM read-vector-features!."
     [layer]
     (let [r (await (worker-method "read_features" #js [layer]))]
       (when-let [failure (.-failure r)]
         (await (check! "OGR_G_ExportToWkb failed" {:fid (.-fid failure)}
                        (.-err failure))))
       (.-features r))))

(defn ^:async metadata
  "Return the metadata of `object`, a dataset, a band or a driver, in the
   default domain or `domain`, as a map. An xml: or json: domain gives an
   incorrect map."
  ([object] (metadata object nil))
  ([object domain]
   (metadata-map (await (gdal-get-metadata object domain)))))

(defn ^:async file-list
  "Return the paths of the files of `dataset` as a vector."
  [dataset]
  (await (gdal-get-file-list dataset)))

(defn ^:async read-vsi-dir
  "Return the names in the directory `path` as a vector."
  [path]
  (await (vsi-read-dir path)))

(defn ^:async read-vsimem-file
  "Return the /vsimem file `path` as a byte[], or nil for no file. The file
   stays."
  [path]
  ;; GDAL gives NULL data for an empty file, and writes the length only for a
  ;; file that exists. Thus a length of all ones means no file.
  (let [r     (await (vsi-get-mem-file-buffer path (assoc (out-values :u32 2) :bytes (ones 8)) 0))
        words (first (:out r))]
    (cond
      (:result r)                                     (:result r)
      (= (aget words 0) #?(:clj -1 :cljs 0xFFFFFFFF)) nil
      :else                                           #?(:clj (byte-array 0) :cljs (js/Uint8Array. 0)))))

#?(:clj
   (defn- write-vsimem-file!
     "Give the byte[] `data` to GDAL as the /vsimem file `path`."
     [path ^bytes data]
     (cpl-error-reset)
     (let [n   (alength data)
           buf (when (pos? n)
                 (or (vsi-malloc n)
                     (throw (gdal-failure "VSIMalloc failed" {:path path :n-bytes n}))))
           _   (some-> buf (write-bytes! data))
           fp  (vsi-file-from-mem-buffer path buf n 1)]
       (when (nil? fp)
         (some-> buf vsi-free)
         (throw (gdal-failure "VSIFileFromMemBuffer failed" {:path path})))
       (check! "VSIFCloseL failed" {:path path} (vsif-close-l fp)))))

#?(:clj
   (defn stage-files!
     "Write `files`, a map of {basename -> byte[] or String}, below the
      /vsimem/ dir `dir`, by default /vsimem/work. Returns
      {basename -> path}. Delete a file with vsi-unlink."
     ([files] (stage-files! files nil))
     ([files dir]
      (let [dir (or dir "/vsimem/work")]
        (when-not (str/starts-with? dir "/vsimem/")
          (throw (ex-info (str "stage-files! writes only below /vsimem/, not " dir)
                          {:dir dir})))
        (reduce-kv
         (fn [out basename content]
           (let [path (str dir "/" basename)]
             (write-vsimem-file! path (if (string? content)
                                        (.getBytes ^String content "UTF-8")
                                        content))
             (assoc out basename path)))
         {}
         files)))))

#?(:cljs
   (defn ^:async stage-files!
     "As the JVM stage-files!, for a typed array, an ArrayBuffer or an
      Array of numbers, not a String. `dir` can also be a MEMFS dir outside
      /vsi, and is /work by default."
     ([files] (stage-files! files nil))
     ([files dir]
      (let [dir (or dir "/work")]
        (when (and (str/starts-with? dir "/vsi") (not (str/starts-with? dir "/vsimem/")))
          (throw (ex-info (str "stage-files! writes to MEMFS or below /vsimem/, not below " dir)
                          {:dir dir})))
        (await (worker-method "stage_files" #js [files dir]))))))

#?(:cljs
   (defn ^:async open-from-disk!
     "Node.js only. Copy the host file or dir `host-path` and its sidecar
      files into MEMFS below /work, and open the copy with gdal-open-ex and
      `open-flags`. The copy stays after gdal-close, and a write changes
      only the copy."
     [host-path open-flags]
     (let [read   (await (.readHostPath host-reader host-path wasm/in-family?))
           target (str "/work" (.-mirror read))
           staged (await (stage-files! (.-files read) target))
           path   (if (.-directory read) target (aget staged (.-name read)))
           ds     (await (gdal-open-ex path open-flags nil nil nil))]
       (when (nps/null-ptr? ds)
         (throw (await (gdal-failure (str "GDALOpenEx returned NULL for " path) {:path path}))))
       ds)))
