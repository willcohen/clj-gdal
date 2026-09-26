;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.network
  "The HTTP callback of GDAL on the JVM. Because the build has no libcurl,
   each CPLHTTPFetch sends its request through *transport*."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [net.willcohen.gdal.fndefs :as fndefs]
            [net.willcohen.gdal.gdal :as gdal]
            [net.willcohen.gdal.wasm :as gwasm]
            [net.willcohen.native.callbacks :as cb]
            [net.willcohen.native.ffi-mem :as ffi-mem]
            [net.willcohen.native.graal-wasm :as nw]
            [net.willcohen.native.http :as http]
            [tech.v3.datatype.ffi :as dt-ffi])
  (:import [org.graalvm.polyglot Value]
           [org.graalvm.polyglot.proxy ProxyExecutable]))

(def ^:dynamic *transport*
  "The fn that sends each HTTP request of GDAL, from {:url :headers} to
   {:status :content-type :body-bytes}. A nil :status is 200. A throw, or a
   status that is not 2xx, fails the request. Bind it around each GDAL call
   that sends a request, for example the open and each read."
  (fn [request]
    (let [{:keys [status headers body-bytes]} (http/fetch request)]
      {:status status
       :content-type (get headers "content-type")
       :body-bytes body-bytes})))

;; A strong reference, because GDAL keeps only the fn pointer of the callback.
(defonce ^:private callback-holder (atom nil))

;; CPLHTTPFetchCallbackFunc in cpl_http.h:
;;   CPLHTTPResult *(*)(const char *pszURL, CSLConstList papszOptions,
;;                      GDALProgressFunc pfnProgress, void *pProgressArg,
;;                      CPLHTTPFetchWriteFunc pfnWrite, void *pWriteArg,
;;                      void *pUserData)
(def ^:private fetch-iface
  (delay (cb/define-callback-interface
           :pointer [:pointer :pointer :pointer :pointer :pointer :pointer :pointer])))

(defn- cpl-malloc-addr ^long [n]
  (ffi-mem/ptr-addr (gdal/cpl-malloc (long n))))

(defn- alloc-cstring
  "Copy `s` into a NUL-terminated buffer from CPLMalloc.
   Returns its address, or 0 for nil."
  ^long [s]
  (ffi-mem/alloc-cstring cpl-malloc-addr s))

(def ^:private cple-app-defined 1)

(defn- failure
  "The GDAL error text of `response`, or nil for a success. Status 0 is no
   response. Each text is as on the libcurl path of GDAL (cpl_http.cpp)."
  [{:keys [status error]}]
  (cond
    error                                   (str "HTTP fetch failed: " error)
    (= 0 status)                            "HTTP fetch failed"
    (and status (not (<= 200 status 299))) (str "HTTP error code : " status)))

;; The error state carries the reason, because the GeoJSON reader returns
;; NULL for an empty body before it reads nStatus (ogrgeojsonutils.cpp).
(defn- fill-result!
  "Write `response` as a 64-bit CPLHTTPResult (cpl_http.h) at
   `result-addr`. Each buffer comes from CPLMalloc, because
   CPLHTTPDestroyResult frees it."
  [result-addr {:keys [content-type body-bytes] :as response}]
  (let [err       (failure response)
        len       (if (and (nil? err) body-bytes) (alength ^bytes body-bytes) 0)
        ;; A NUL follows the body, as on the libcurl path of GDAL, because
        ;; GDAL reads the body as a C string (ogrgeojsondriver.cpp).
        data-addr (if (pos? len)
                    (let [a (cpl-malloc-addr (inc len))]
                      (ffi-mem/copy-bytes! a ^bytes body-bytes)
                      (ffi-mem/put-byte! a len 0)
                      a)
                    0)]
    (when err
      (gdal/cpl-error-set-state fndefs/CE_Failure cple-app-defined err))
    (ffi-mem/put-i32! result-addr 0 (if err 1 0))                            ; nStatus
    (ffi-mem/put-ptr! result-addr 8 (if err 0 (alloc-cstring content-type))) ; pszContentType
    (ffi-mem/put-ptr! result-addr 16 (if err (alloc-cstring err) 0))         ; pszErrBuf
    (ffi-mem/put-i32! result-addr 24 len)                                    ; nDataLen
    (ffi-mem/put-i32! result-addr 28 (if (pos? len) (inc len) 0))            ; nDataAlloc
    (ffi-mem/put-ptr! result-addr 32 data-addr)                              ; pabyData
    (ffi-mem/put-ptr! result-addr 40 0)                                      ; papszHeaders
    (ffi-mem/put-i32! result-addr 48 0)                                      ; nMimePartCount
    (ffi-mem/put-i32! result-addr 52 0)                                      ; alignment pad
    (ffi-mem/put-ptr! result-addr 56 0)                                      ; pasMimePart
    result-addr))

(defn- option-value
  "The value of `k` in the KEY=VALUE strings of `options`, or nil."
  [options k]
  (let [prefix (str k "=")]
    (some #(when (str/starts-with? % prefix) (subs % (count prefix))) options)))

(defn- header-map
  "The map of the \"Name: value\" strings `lines`. A line with no name goes."
  [lines]
  (into {}
        (keep (fn [line]
                (let [i (str/index-of line ":")]
                  (when (and i (pos? (long i)))
                    [(str/trim (subs line 0 i)) (str/trim (subs line (inc (long i))))]))))
        lines))

(defn- comma-tokens
  "The tokens of `s` between commas, as CSLTokenizeString2 with
   CSLT_HONOURSTRINGS gives them."
  [^String s]
  (let [n (.length s)]
    (loop [i 0, in-quotes false, tok (StringBuilder.), out []]
      (if (>= i n)
        (cond-> out (pos? (.length tok)) (conj (str tok)))
        (let [c (.charAt s i)]
          (cond
            (and in-quotes (= c \\) (< (inc i) n) (#{\" \\} (.charAt s (inc i))))
            (recur (+ i 2) in-quotes (.append tok (.charAt s (inc i))) out)

            (= c \") (recur (inc i) (not in-quotes) tok out)

            (and (not in-quotes) (= c \,))
            (recur (inc i) false (StringBuilder.) (cond-> out (pos? (.length tok)) (conj (str tok))))

            :else (recur (inc i) in-quotes (.append tok c) out)))))))

(defn- header-lines
  "The header lines that the libcurl path of GDAL sends for `headers`
   (cpl_http.cpp): CRLF lines, a comma list, or one header with commas, for
   example \"Accept: text/plain, application/json\"."
  [^String headers]
  (cond
    (nil? headers)                 nil
    (str/includes? headers "\r\n") (str/split headers #"[\r\n]+")
    (when-let [i (str/index-of headers ",")]
      (not (str/includes? (subs headers i) ":")))
    [headers]
    :else                          (comma-tokens headers)))

(defn- ffi-fetch
  "Send the request of `url-ptr` through `transport` from the FFI callback.
   Returns a new CPLHTTPResult."
  [transport url-ptr papsz-ptr]
  (let [result-addr (cpl-malloc-addr 64)]
    (try
      (let [options (ffi-mem/read-string-array (if papsz-ptr (ffi-mem/ptr-addr papsz-ptr) 0))]
        (fill-result! result-addr
                      (if (option-value options "CLOSE_PERSISTENT")
                        {:status 200}
                        (transport {:url     (dt-ffi/c->string url-ptr)
                                    :headers (header-map
                                              (header-lines
                                               (or (option-value options "HEADERS")
                                                   (gdal/cpl-get-config-option "GDAL_HTTP_HEADERS" nil))))}))))
      (catch Throwable e
        (log/error e "The CPLHTTPFetch callback failed")
        (fill-result! result-addr {:error (or (.getMessage e) "callback error")})))
    (dt-ffi/->pointer result-addr)))

(defn- setup-ffi-callback! [transport]
  (let [callback (cb/register-callback!
                  @fetch-iface
                  (fn [url-ptr papsz-ptr _progress _progress-arg _write _write-arg _user]
                    (ffi-fetch transport url-ptr papsz-ptr)))]
    (reset! callback-holder callback)
    (gdal/cplhttp-set-fetch-callback (:ptr callback) nil)))

(defn- pack-response
  "The response in the layout that the C stub reads (gdal_http_stub.c)."
  ^bytes [status content-type ^bytes body-bytes]
  (let [ctype (.getBytes (str content-type) "UTF-8")
        body  (or body-bytes (byte-array 0))
        buf   (.order (java.nio.ByteBuffer/allocate (+ 12 (alength ctype) (alength body)))
                      java.nio.ByteOrder/LITTLE_ENDIAN)]
    (.putInt buf (int (or status 200)))
    (.putInt buf (alength ctype))
    (.put buf ctype)
    (.putInt buf (alength body))
    (.put buf body)
    (.array buf)))

(defn- graal-fetch-executable
  "The GraalVM callback, from the addresses of the URL and of the CRLF
   header lines (or 0) to the address of a pack-response on the heap of
   `wasm-context`, or 0 for no response. The C stub frees it."
  [transport wasm-context]
  (reify ProxyExecutable
    (execute [_ args]
      (try
        (nw/with-wasm-context wasm-context
          (let [module       (nw/get-module wasm-context)
                url          (nw/utf8->string module (nw/value->long (aget args 0)))
                headers-addr (nw/value->long (aget args 1))
                headers      (header-map (when-not (zero? headers-addr)
                                           (str/split (nw/utf8->string module headers-addr) #"\r\n")))
                {:keys [status content-type body-bytes]} (transport {:url url :headers headers})]
            (if (= 0 status)
              0
              (let [packed (pack-response status content-type body-bytes)
                    ptr    (nw/address-as-int (nw/malloc (alength packed)))]
                (gwasm/heap-write! module ptr packed)
                ptr))))
        (catch Throwable e
          (log/error e "The CPLHTTPFetch callback failed")
          0)))))

(defn- setup-graal-callback! [transport]
  (let [wasm-context  (nw/library-context :net.willcohen.gdal)
        ^Value module (nw/get-module wasm-context)
        callback      (graal-fetch-executable transport wasm-context)]
    (nw/put-js-globals! (.getContext module) {"__gdal_http_fetch" callback})
    (nw/ccall module "gdal_setup_http_callback" "number" [] [])))

(defn setup-http-callback!
  "Install the CPLHTTPFetch callback for the active backend. gdal/init!
   calls it. On GraalVM, it serves the module of the bound *wasm-context*,
   which needs its own polyglot Context, or else the GDAL module."
  []
  ;; Pass the Var, because a Var call reads the thread binding at call
  ;; time, and a later `binding` of *transport* then reaches the callback.
  (if (gdal/graal?)
    (setup-graal-callback! #'*transport*)
    (setup-ffi-callback! #'*transport*))
  nil)
