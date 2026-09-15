(ns store.core
  (:require [store.api :as api]
            [ring.adapter.jetty :as jetty]))

;; Ion entry point: once you deploy with `ion-dev`, point your Ion's
;; HTTP Direct handler at store.core/handler.
(def handler api/app)

(defn -main [& _args]
  (jetty/run-jetty handler {:port 8080 :join? false})
  (println "Store API running on http://localhost:8080"))
