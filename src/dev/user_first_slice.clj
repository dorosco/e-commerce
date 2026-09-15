(ns user
  "Scratch namespace for REPL-driven development.
   Start a REPL with the :dev alias, then eval forms here one at a time."
  (:require [store.db :as db]
            [store.products :as products]
            [store.api :as api]
            [datomic.client.api :as d]
            [ring.adapter.jetty :as jetty]))

(comment
  ;; 1. One-time setup for a fresh dev-local database
  (db/ensure-db!)
  (db/install-schema!)

  ;; 2. Add a couple of products to play with
  (products/create-product! {:sku "MUG-001"
                              :name "Ceramic mug"
                              :price-cents 1800
                              :description "12oz glazed ceramic mug"})
  (products/create-product! {:sku "TOTE-001"
                              :name "Canvas tote bag"
                              :price-cents 2200})

  ;; 3. Query them back
  (products/list-active-products (d/db (db/conn)))
  (products/get-product (d/db (db/conn)) "MUG-001")

  ;; 4. Run the API locally and hit it with curl
  (defonce server (jetty/run-jetty api/app {:port 8080 :join? false}))
  ;; curl http://localhost:8080/api/products
  ;; curl http://localhost:8080/api/products/MUG-001
  (.stop server)
  )
