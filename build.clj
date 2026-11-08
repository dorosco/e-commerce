(ns build
  (:require [clojure.tools.build.api :as b]))

;; Configuration constants
(def lib 'dashey/ecommerce-app)
(def version "1.0.0")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean [_]
  (println "Cleaning target folder...")
  (b/delete {:path "target"}))

(defn uber [_]
  ;; 1. Clean previous builds
  (clean nil)
  
  ;; 2. Copy source and resource files to the classes directory
  (println "Copying sources and resources...")
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  
  ;; 3. Compile Clojure source files to Java bytecode (AOT compilation)
  (println "Compiling Clojure sources (AOT)...")
  (b/compile-clj {:basis     basis
                  :src-dirs  ["src"]
                  :class-dir class-dir
                  ;; Make sure this matches your project's primary entry point namespace
                  :main      'store.main}) 

  ;; 4. Bundle everything into the standalone Uberjar
  (println (str "Building Uberjar: " uber-file))
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     basis
           ;; This sets the Main-Class in the JAR manifest so 'java -jar' knows what to run
           :main      'store.main})
  
  (println "Build complete!"))
