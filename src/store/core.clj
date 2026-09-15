(ns store.core
  (:require [store.api :as api]
            [store.stripe :as stripe]
            [ring.adapter.jetty :as jetty]))

;; The webhook middleware wraps *around* the reitit app rather than being
;; a route inside it — see store.stripe/wrap-webhook for why.
(def handler
  (stripe/wrap-webhook api/app (System/getenv "STRIPE_WEBHOOK_SECRET")))

;; Ion entry point: once you deploy with `ion-dev`, point your Ion's
;; HTTP Direct handler at store.core/handler.

(defn -main [& _args]
  (stripe/init! (System/getenv "STRIPE_SECRET_KEY"))
  (jetty/run-jetty handler {:port 8080 :join? false})
  (println "Store API running on http://localhost:8080"))
