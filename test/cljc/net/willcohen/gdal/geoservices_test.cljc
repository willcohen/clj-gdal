;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

;; The ESRIJSON fixtures hold three features on two pages: a count of three
;; shows that the paging loop of the driver ran. The cljs tests serve the
;; fixtures from a local HttpServer, because the worker has no *transport*.

(ns net.willcohen.gdal.geoservices-test
  (:require
   #?@(:clj [[clojure.test :refer [deftest is use-fixtures]]
             [clojure.java.io :as io]
             [clojure.string :as str]
             [net.willcohen.gdal.fndefs :as fndefs]
             [net.willcohen.gdal.gdal :as gdal]
             [net.willcohen.gdal.network :as network]
             [net.willcohen.gdal.support :as support]
             [net.willcohen.native.ffi-mem :as ffi-mem]
             [tech.v3.datatype.ffi :as dt-ffi]]
       :cljs [[cljs.test :refer [deftest is]]
              [clojure.string :as str]
              ["node:http" :as http]
              ["node:fs" :as fs]
              ["../../../../../src/cljc/net/willcohen/gdal/gdal.mjs" :as gdal]
              ["../../../../../src/cljc/net/willcohen/gdal/fndefs.mjs" :as fndefs]
              ["./support.mjs" :as support]
              ["../../../dist/test_runner.mjs"
               :refer [run_tests_and_exit_BANG_]]])))

(def ^:private fixture-dir "test/fixtures")

(def ^:private query
  "/FeatureServer/0/query?where=1%3D1&outFields=*&f=json&orderByFields=OBJECTID+ASC")

(def ^:private geojson-url-path "/tiny.geojson")

(defn- fixture-file-for [url]
  (cond
    (str/ends-with? url geojson-url-path) "tiny.geojson"
    (str/includes? url "resultOffset=2")  "geoservices/page2.json"
    :else                                 "geoservices/page1.json"))

;; A one-ring ESRIJSON polygon reads as a Polygon.
(defn- polygon-wkb? [wkb]
  (and (some? wkb) (= fndefs/wkbNDR (aget wkb 0)) (= fndefs/wkbPolygon (aget wkb 1))))

#?(:clj
   (do
     (use-fixtures :once support/with-backend)

     (def ^:private mock-esri-url (str "ESRIJSON:http://mock.test" query))

     (def ^:private mock-geojson-url (str "GeoJSON:http://mock.test" geojson-url-path))

     ;; init! can run more than one time. GDAL forbids a new callback during a
     ;; fetch, and on FFI each callback is an upcall stub that is never freed.
     (deftest a-second-init-installs-no-callback
       (let [n (atom 0)]
         (with-redefs [network/setup-http-callback! #(swap! n inc)]
           (gdal/init!))
         (is (zero? @n))))

     (defn- read-fixture ^bytes [name]
       (java.nio.file.Files/readAllBytes (.toPath (io/file fixture-dir name))))

     (defn- fixture-transport [{:keys [url]}]
       {:status 200
        :content-type "application/json"
        :body-bytes (read-fixture (fixture-file-for url))})

     (defn- check-three-polygons [url]
       (let [records (support/layer-records url)]
         (is (= 3 (count records)) "3 features across 2 pages")
         (is (= ["A" "B" "C"] (support/field-values records "NAME")))
         (is (= [1 2 3] (mapv :fid records)) "the FID comes from OBJECTID")
         (is (every? (comp polygon-wkb? :wkb) records) "polygon WKB")))

     (defn- check-tiny-geojson [url]
       (let [records (support/layer-records url)]
         (is (= ["a" "b" "c"] (support/field-values records "name")))
         (is (= support/tiny-wkbs (mapv (comp vec :wkb) records))
             "the little-endian polygon WKB of tiny.geojson")))

     (deftest geoservices-paging-via-fixture-callback
       (binding [network/*transport* fixture-transport]
         (check-three-polygons mock-esri-url)))

     (deftest geojson-url-via-fixture-callback
       (binding [network/*transport* fixture-transport]
         (check-tiny-geojson mock-geojson-url)))

     (defn- headers-of-a-read
       "The :headers of the request of a GeoJSON URL read, with the config
        option GDAL_HTTP_HEADERS set to `config`."
       [config]
       (let [request (atom nil)]
         (gdal/cpl-set-config-option "GDAL_HTTP_HEADERS" config)
         (try
           (binding [network/*transport* #(do (reset! request %) (fixture-transport %))]
             (support/layer-records mock-geojson-url))
           (finally
             (gdal/cpl-set-config-option "GDAL_HTTP_HEADERS" nil)))
         (:headers @request)))

     ;; The GeoJSON driver gives GDAL_HTTP_HEADERS, and an Accept header, to
     ;; CPLHTTPFetch as the HEADERS option (ogrgeojsonutils.cpp).
     (deftest the-headers-option-reaches-the-transport
       (is (= {"Accept" "text/plain, application/json"} (headers-of-a-read nil)))
       (is (= {"X-Test" "a" "Authorization" "Bearer t" "Accept" "text/plain, application/json"}
              (headers-of-a-read "X-Test: a\r\nAuthorization: Bearer t"))))

     ;; The ESRIJSON count request has no HEADERS option (ogrgeojsondriver.cpp
     ;; GetFeatureCount). The libcurl path of GDAL then sends the config
     ;; option, a comma list with quoted values or CRLF lines (cpl_http.cpp).
     (deftest the-config-headers-reach-a-request-with-no-headers-option
       (let [requests (atom [])]
         (gdal/cpl-set-config-option "GDAL_HTTP_HEADERS" "Authorization: Bearer t,X-Test: \"a,b\"")
         (try
           (binding [network/*transport* #(do (swap! requests conj %) (fixture-transport %))]
             (support/call-with-dataset
              (support/open-vector mock-esri-url)
              #(is (= 3 (gdal/layer-feature-count (gdal/gdal-dataset-get-layer % 0))))))
           (finally
             (gdal/cpl-set-config-option "GDAL_HTTP_HEADERS" nil)))
         (is (= [{"Authorization" "Bearer t" "X-Test" "a,b"}]
                (->> @requests
                     (filter #(str/includes? (:url %) "returnCountOnly=true"))
                     (mapv :headers))))))

     (deftest a-response-with-no-status-is-a-success
       (binding [network/*transport* #(dissoc (fixture-transport %) :status)]
         (check-three-polygons mock-esri-url)))

     (defn- open-error
       "Open the mock FeatureServer through `transport`.
        Returns the last GDAL error message."
       [transport]
       (gdal/cpl-error-reset)
       (binding [network/*transport* transport]
         (let [ds (gdal/gdal-open-ex mock-esri-url fndefs/GDAL_OF_VECTOR nil nil nil)]
           (is (nil? ds) "the open fails")
           (some-> ds gdal/gdal-close)
           (gdal/cpl-get-last-error-msg))))

     (deftest a-failed-request-gives-its-status
       (is (str/includes? (open-error (constantly {:status 404})) "HTTP error code : 404")))

     ;; http/fetch gives status 0 when no response comes.
     (deftest a-status-of-0-gives-a-failed-fetch
       (is (str/includes? (open-error (constantly {:status 0})) "HTTP fetch failed")))

     (deftest a-failed-transport-gives-an-error
       (is (str/includes? (open-error (fn [_] (throw (ex-info "no route" {}))))
                          "HTTP fetch failed")))

     ;; GDAL reads the body as a C string (ogrgeojsondriver.cpp), and its
     ;; libcurl path ends the body with a NUL. The test is for FFI only,
     ;; because on GraalVM the C stub fills the result.
     (deftest ffi-body-ends-with-a-nul
       (when-not (gdal/graal?)
         (let [result (ffi-mem/ptr-addr (gdal/cpl-malloc 64))
               malloc @#'network/cpl-malloc-addr]
           ;; Each new byte is 0xFF, thus only fill-result! can put a 0 after
           ;; the body.
           (with-redefs-fn {#'network/cpl-malloc-addr
                            (fn ^long [n]
                              (let [a (long (malloc n))]
                                (dotimes [i n] (ffi-mem/put-byte! a i -1))
                                a))}
             #(#'network/fill-result! result {:status 200 :content-type "application/json"
                                              :body-bytes (.getBytes "abc" "UTF-8")}))
           (is (= 3 (ffi-mem/rd-i32 (+ result 24))) "nDataLen")
           (is (= 4 (ffi-mem/rd-i32 (+ result 28))) "nDataAlloc")
           (is (= "abc" (ffi-mem/rd-cstr (+ result 32))) "pabyData")
           (run! #(gdal/vsi-free (dt-ffi/->pointer %))
                 [(ffi-mem/rd-addr (+ result 32)) (ffi-mem/rd-addr (+ result 8)) result]))))

     (defn- start-fixture-server []
       (let [server (com.sun.net.httpserver.HttpServer/create
                     (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
         (.createContext
          server "/"
          (reify com.sun.net.httpserver.HttpHandler
            (handle [_ exchange]
              (let [uri (str (.getRequestURI exchange))
                    body (read-fixture (fixture-file-for uri))]
                (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
                (.sendResponseHeaders exchange 200 (alength body))
                (doto (.getResponseBody exchange) (.write body) (.close))))))
         (.start server)
         server))

     (defn- call-with-fixture-server
       "Call `f` with the base URL of a local HttpServer for the fixtures."
       [f]
       (let [server (start-fixture-server)]
         (try
           (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
           (finally
             (.stop server 0)))))

     (deftest geoservices-paging-via-http-server
       (call-with-fixture-server #(check-three-polygons (str "ESRIJSON:" % query))))

     (deftest geojson-url-via-http-server
       (call-with-fixture-server #(check-tiny-geojson (str "GeoJSON:" % geojson-url-path))))))

#?(:cljs
   (do
     (defn- read-fixture [name]
       (.readFileSync fs (str fixture-dir "/" name)))

     (defn- headers-geojson
       "A GeoJSON FeatureCollection with one feature, whose properties are
        the request headers `headers`."
       [headers]
       (js/JSON.stringify
        #js {:type "FeatureCollection"
             :features #js [#js {:type "Feature"
                                 :properties headers
                                 :geometry #js {:type "Point" :coordinates #js [0 0]}}]}))

     (defn- start-server []
       (js/Promise.
        (fn [resolve _reject]
          (let [server (.createServer
                        http
                        (fn [req res]
                          (let [body (if (str/ends-with? (.-url req) "/headers.geojson")
                                       (headers-geojson (.-headers req))
                                       (read-fixture (fixture-file-for (.-url req))))]
                            (.writeHead res 200 #js {"Content-Type" "application/json"})
                            (.end res body))))]
            (.listen server 0 "127.0.0.1" (fn [] (resolve server)))))))

     (defn- ^:async call-with-server
       "Call `f` with the base URL of the server of start-server.
        Closes the server when the promise of `f` settles."
       [f]
       (let [server (await (start-server))]
         (try
           (await (f (str "http://127.0.0.1:" (.-port (.address server)))))
           (finally
             (.close server)))))

     (deftest ^:async geoservices-paging-node-wasm
       (let [records (await (call-with-server #(support/layerRecords (str "ESRIJSON:" % query))))]
         (is (= 3 (count records)) "3 features across 2 pages")
         (is (= ["A" "B" "C"] (support/fieldValues records "NAME")))
         (is (= ["1" "2" "3"] (mapv #(str (.-fid %)) records)) "the FID comes from OBJECTID")
         (is (every? #(polygon-wkb? (.-wkb %)) records) "polygon WKB")))

     (deftest ^:async geojson-url-node-wasm
       (let [records (await (call-with-server
                             #(support/layerRecords (str "GeoJSON:" % geojson-url-path))))]
         (is (= ["a" "b" "c"] (support/fieldValues records "name")))
         (is (= (mapv #(vec (support/squareWkb % % 1)) [0 2 4]) (mapv #(vec (.-wkb %)) records))
             "the little-endian polygon WKB of tiny.geojson")))

     (deftest ^:async the-headers-option-reaches-the-server
       (await (gdal/cpl_set_config_option "GDAL_HTTP_HEADERS" "X-Test: a"))
       (try
         (let [records (await (call-with-server
                               #(support/layerRecords (str "GeoJSON:" % "/headers.geojson"))))
               fields  (.-fields (aget records 0))]
           (is (= "a" (aget fields "x-test")))
           (is (= "text/plain, application/json" (aget fields "accept"))))
         (finally
           (await (gdal/cpl_set_config_option "GDAL_HTTP_HEADERS" nil)))))

     ;; A closed port gives no response, and the fetch worker gives status 0.
     (deftest ^:async a-request-with-no-response-gives-a-failed-fetch
       (let [server (await (start-server))
             port   (.-port (.address server))]
         (await (js/Promise. (fn [resolve _reject] (.close server resolve))))
         (await (gdal/cpl_error_reset))
         (is (nil? (await (gdal/gdal_open_ex (str "ESRIJSON:http://127.0.0.1:" port query)
                                             fndefs/GDAL_OF_VECTOR nil nil nil)))
             "the open fails")
         (is (.includes (str (await (gdal/cpl_get_last_error_msg))) "HTTP fetch failed"))))

     (run_tests_and_exit_BANG_ gdal/shutdown_BANG_ "net.willcohen.gdal.geoservices-test")))
