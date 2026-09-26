;; Copyright (c) 2026 Will Cohen
;;
;; Part of clj-gdal, under the MIT License.
;; See LICENSE for license information.
;; SPDX-License-Identifier: MIT

(ns build
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'net.willcohen/gdal)
(def native-lib 'net.willcohen/gdal-native)
(def ^:private pkg-dir "src/cljc/net/willcohen/gdal")
;; The jars read the version from package.json, because npm reads it only
;; from there.
(def version
  (second (re-find #"\"version\": \"([^\"]+)\""
                   (slurp (str pkg-dir "/package.json")))))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def pom-file (format "%s/META-INF/maven/%s/%s/pom.xml" class-dir (namespace lib) (name lib)))
(def native-pom-file (format "target/%s-%s.pom" (name native-lib) version))

;; A profile of the gdal pom adds the gdal-native jar of the JVM host, because
;; Clojars takes an upload of 30 MB or less, and one jar with each native lib
;; is about 60 MB.
(def native-jars (edn/read-string (slurp "scripts/native-jars.edn")))

(defn- native-lib-path
  "The path of the lib of a resource dir, relative to resources/."
  [dir]
  (str dir "/" (if (str/starts-with? dir "darwin-") "libgdal.dylib" "libgdal.so")))

(defn- native-jar-file [classifier]
  (format "target/%s-%s-%s.jar" (name native-lib) version classifier))

(def ^:private basis (delay (b/create-basis {:project "deps.edn"})))

;; A new clone does not have these build outputs, because git ignores them.
;; A jar without them loads, but the first GDAL call causes an error.
(def ^:private required-resources
  (concat (map #(str pkg-dir "/" %) ["libgdal.mjs" "libgdal.wasm" "proj.db"])
          (for [{:keys [dirs]} native-jars, dir dirs]
            (str "resources/" (native-lib-path dir)))))

(defn- clean! []
  ;; Do not delete all of target/. It also holds the wasm source and build
  ;; trees, which take almost one hour to make again.
  (run! #(b/delete {:path %})
        (list* class-dir "target/native-classes" jar-file native-pom-file
               (map (comp native-jar-file :classifier) native-jars))))

(def ^:private notices-comment
  "The license of the code of clj-gdal. The native library, libgdal.wasm and proj.db contain third-party code and data under other licenses: see META-INF/THIRD-PARTY-NOTICES in the jar.")

(def ^:private repo-url "https://github.com/willcohen/clj-gdal")

(def ^:private pom-data
  [[:url repo-url]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/license/mit"]
     [:distribution "repo"]
     [:comments notices-comment]]]
   [:developers
    [:developer
     [:name "Will Cohen"]]]
   [:scm
    [:url repo-url]
    [:tag version]]])

(defn- profile [{:keys [classifier os arch]}]
  [:profile
   [:id classifier]
   [:activation [:os os [:arch arch]]]
   [:dependencies
    [:dependency
     [:groupId (namespace native-lib)]
     [:artifactId (name native-lib)]
     [:version version]
     [:classifier classifier]]]])

(defn- pom! []
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src/clj" "src/cljc"]
                :pom-data (conj pom-data
                                [:description "GDAL/OGR for the JVM: a native libgdal on macOS Apple Silicon and Linux amd64 and arm64, and GDAL compiled to WebAssembly on GraalVM for other platforms."]
                                (into [:profiles] (map profile native-jars)))}))

;; Clojars refuses a pom of packaging jar when no jar with no classifier
;; comes with it.
(defn- native-pom []
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
       "  <modelVersion>4.0.0</modelVersion>\n"
       "  <groupId>" (namespace native-lib) "</groupId>\n"
       "  <artifactId>" (name native-lib) "</artifactId>\n"
       "  <version>" version "</version>\n"
       "  <packaging>pom</packaging>\n"
       "  <name>" (name native-lib) "</name>\n"
       "  <description>The native libgdal of net.willcohen/gdal, one classifier jar for each platform: darwin-aarch64, linux-amd64 and linux-aarch64.</description>\n"
       "  <url>" repo-url "</url>\n"
       "  <licenses>\n"
       "    <license>\n"
       "      <name>MIT License</name>\n"
       "      <url>https://opensource.org/license/mit</url>\n"
       "      <distribution>repo</distribution>\n"
       "      <comments>" notices-comment "</comments>\n"
       "    </license>\n"
       "  </licenses>\n"
       "  <scm>\n"
       "    <url>" repo-url "</url>\n"
       "    <tag>" version "</tag>\n"
       "  </scm>\n"
       "</project>\n"))

;; The native libs and the wasm module contain third-party code, and its
;; licenses must go with each copy.
(defn- copy-notices! [dir]
  (doseq [f ["LICENSE" "THIRD-PARTY-NOTICES"]]
    (b/copy-file {:src f :target (str dir "/META-INF/" f)})))

(defn- gdal-jar! []
  (b/copy-dir {:src-dirs ["src/cljc"]
               :target-dir class-dir
               :include "net/willcohen/gdal/{*.cljc,libgdal.mjs,libgdal.wasm,gdal-graal-loader.mjs,proj.db}"})
  (b/copy-dir {:src-dirs ["src/clj"]
               :target-dir class-dir})
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir
               :include "clj-kondo.exports/**"})
  (copy-notices! class-dir)
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn- native-jar! [{:keys [classifier dirs]}]
  (let [dir (str "target/native-classes/" classifier)]
    (doseq [d dirs]
      (b/copy-file {:src (str "resources/" (native-lib-path d))
                    :target (str dir "/" (native-lib-path d))}))
    (copy-notices! dir)
    (b/jar {:class-dir dir
            :jar-file (native-jar-file classifier)})))

(defn jar
  "Build the gdal jar and pom, and the gdal-native pom and classifier jars."
  [_]
  (when-let [missing (seq (remove #(.exists (io/file %)) required-resources))]
    (throw (ex-info (str "Missing " (str/join ", " missing) ". Build them first with bb build:native and bb build:wasm.")
                    {:missing (vec missing)})))
  (clean!)
  (pom!)
  (gdal-jar!)
  (run! native-jar! native-jars)
  (spit native-pom-file (native-pom)))

(defn- deploy! [repository]
  ;; pomegranate, not deps-deploy, because deps-deploy reads the classifier
  ;; from the file name with \p{Alnum}*, and deploys a linux-amd64 jar as the
  ;; main jar.
  ;;
  ;; gdal-native goes first. Clojars refuses a second deploy of a version,
  ;; and a gdal pom whose profiles name a missing gdal-native does not
  ;; resolve on a host that a profile selects.
  (aether/deploy :coordinates [native-lib version]
                 :artifact-map (into {[:extension "pom"] native-pom-file}
                                     (for [{:keys [classifier]} native-jars]
                                       [[:classifier classifier :extension "jar"]
                                        (native-jar-file classifier)]))
                 :repository repository)
  (aether/deploy :coordinates [lib version]
                 :artifact-map {[:extension "jar"] jar-file
                                [:extension "pom"] pom-file}
                 :repository repository))

(defn deploy-file-repo
  "Deploy the jars of `jar` to the Maven repository in the dir `:dir`."
  [{:keys [dir]}]
  (deploy! {"file-repo" {:url (str (.toURI (io/file dir)))}}))

(defn deploy-clojars
  "Deploy the jars of `jar` to Clojars.
   The user is CLOJARS_USERNAME, and CLOJARS_PASSWORD is its deploy token."
  [_]
  (deploy! {"clojars" {:url "https://repo.clojars.org/"
                       :username (System/getenv "CLOJARS_USERNAME")
                       :password (System/getenv "CLOJARS_PASSWORD")}}))
