;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns net.willcohen.gdal.impl.native
  "The FFI load of libgdal from resources/<os>-<arch>/, or on musl from
   <os>-<arch>-musl/. The lib needs no other file, because it embeds PROJ and
   proj.db."
  (:require [net.willcohen.native.platform :as nplatform]
            [net.willcohen.gdal.fndefs :as fndefs]))

(def ^:private fn-defs (nplatform/rehydrate-fn-defs fndefs/fndefs))

;; An empty map means that the extraction failed. init-gdal then throws, and
;; init! uses the GraalVM backend.
(def gdal
  (atom (nplatform/extract-and-bind-library!
         {:lib-basename "libgdal"
          :tmp-prefix   "gdal"
          :fn-defs-var  #'fn-defs})))

(defn init-gdal []
  (nplatform/init-jdk-library! (:singleton @gdal) (:file @gdal)))

(nplatform/define-library-fns! fn-defs gdal)

;; Each raw fn is private, because it works only on FFI and skips the NULL
;; handling of gdal.cljc. The dispatch of gdal.cljc finds it with
;; ns-resolve, which also finds a private var.
(doseq [fn-key (keys fn-defs)]
  (alter-meta! (ns-resolve 'net.willcohen.gdal.impl.native (symbol (name fn-key)))
               assoc :private true))
