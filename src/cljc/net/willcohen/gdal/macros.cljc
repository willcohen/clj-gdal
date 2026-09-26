;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

#?(:clj
   (ns net.willcohen.gdal.macros
     (:require [net.willcohen.native.macros :as nmac]))
   :cljs
   (ns macros
     ;; A namespace require, because squint resolves it at compile time, and
     ;; the macro calls nmac as it expands. With the "ffi-wasm" specifier, the
     ;; macro expands to nothing, and squint shows no error.
     (:require [fndefs :as pdefs]
               [net.willcohen.native.macros :as nmac])))

(defmacro define-all-gdal-public-fns
  "Define one public fn for each fndefs entry, which checks its arg count:
   an async defn on cljs, and on the JVM a fn that it interns at load time."
  []
  ;; Check the arg count, because a wasm ccall gives 0 for each missing arg.
  #?(:clj `(doseq [[fn-key# fn-def#] net.willcohen.gdal.fndefs/fndefs
                   :let [sym#    (nmac/camel-name->clj-name fn-key#)
                         arity#  (count (:argtypes fn-def#))
                         public# (str (ns-name *ns*) "/" sym#)]]
             (intern *ns*
                     (with-meta sym# {:arglists (list (nmac/fn-def-arg-syms fn-def#))
                                      :doc      (str "The GDAL C function " (name fn-key#)
                                                     ". Refer to the GDAL documentation.")})
                     (fn [& args#]
                       (when-not (= arity# (count args#))
                         (throw (clojure.lang.ArityException. (count args#) public#)))
                       (~'gdal-call fn-key# args#))))
     :cljs (nmac/library-fns-form
            pdefs/fndefs
            {:name-fn nmac/camel-name->clj-name
             :emit-fn
             (fn [fn-name fn-key fn-def]
               (when-not (:jvm-only? fn-def)
                 (let [args (nmac/fn-def-arg-syms fn-def)]
                   `(defn ~(with-meta fn-name {:async true})
                      ~(str "The GDAL C function " (name fn-key) ". Refer to the GDAL documentation.")
                      ~args
                      (~'check-count! ~(name fn-key) "args" ~(count args)
                                      (~'.-length ~'js/arguments))
                      (~'await (~'dispatch-gdal ~fn-key ~args))))))})))
