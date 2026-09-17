(ns user
  "Scratch namespace for REPL-driven development.
   Start a REPL with the :dev alias, then eval forms here one at a time."
  (:require [store.db :as db]
            [store.products :as products]
            [store.inventory :as inventory]
            [store.orders :as orders]
            [store.stripe :as stripe]
            [store.core :as core]
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
  (products/update-product! "MUG-001" {:product/price-cents 2500})

  (products/update-product! "MUG-001" {:product/category "Aretes"})
  (products/update-product! "MUG-001" {:product/image-path "images/D001.png"})

  (products/update-product! "TOTE-001" {:product/category "Collares"})
  (products/update-product! "TOTE-001" {:product/image-path "images/D002.png"})

  (products/update-product! "HAT-001" {:product/category "Pulseras"})
  (products/update-product! "HAT-001" {:product/image-path "images/D003.png"})


  (products/create-product! {:sku "MUG-002"
                             :name "Arete casual dorado"
                             :price-cents 2600})
  (products/update-product! "MUG-002" {:product/category "Aretes"})
  (products/update-product! "MUG-002" {:product/image-path "images/D004.png"})


  
  (products/create-product! {:sku "TOTE-002"
                             :name "Collar casual plateado"
                             :price-cents 2100})
  (products/update-product! "TOTE-002" {:product/category "Collares"})
  (products/update-product! "TOTE-002" {:product/image-path "images/D005.png"})


  (products/create-product! {:sku "HAT-002"
                             :name "Pulsera elegante dorada"
                             :price-cents 1600})
  (products/update-product! "HAT-002" {:product/category "Pulseras"})
  (products/update-product! "HAT-002" {:product/image-path "images/D006.png"})
  
    ;; (update-product! \"ABC-123\" {:product/price-cents 2500})


  (products/create-product! {:sku "MUG-003"
                             :name "Nuevo MUG"
                             :price-cents 4200
                             :category "Aretes"
                             :image-path "images/D001.png"})

  (products/create-product! {:sku "TOTE-003"
                             :name "Nuevo MUG"
                             :price-cents 4600
                             :category "Collares"
                             :image-path "images/D002.png"})

  (products/create-product! {:sku "HAT-003"
                             :name "Nuevo Hat"
                             :price-cents 4600
                             :category "Pulseras"
                             :image-path "images/D003.png"})
  
  (products/get-product (d/db (db/conn)) "HAT-003")

  (products/create-product! {:sku "MUG-004"
                             :name "Nuevo MUG"
                             :price-cents 4600
                             :description "Descripcion de nuevo MUG"
                             :category "Pulseras"
                             :image-path "images/D003.png"})

  (products/get-product (d/db (db/conn)) "MUG-001")

;; nuevo con precio corregido
  (products/create-product! {:sku "TOTE-004"
                             :name "Nuevo TOTE creado con precio ok"
                             :price-cents 8200
                             :description "Descripcion de nuevo TOTE"
                             :category "Collares"
                             :image-path "images/D006.png"})

  (products/get-product (d/db (db/conn)) "TOTE-004")

  
  
  ;; 3. Query them back
  (products/list-active-products (d/db (db/conn)))
  (products/get-product (d/db (db/conn)) "MUG-001")

  ;; 4. Stock the mugs, then check current stock and full history
  (inventory/restock! "MUG-001" 20)
  (inventory/current-stock (d/db (db/conn)) "MUG-001")
  (inventory/stock-history (d/db (db/conn)) "MUG-001")
  (inventory/low-stock (d/db (db/conn)) 5) ;; nothing yet, stock is 20

  ;; 5. Place an order — snapshots current prices, creates the order +
  ;;    line items, and records a :sale inventory change per line
  (def result
    (orders/create-order! {:customer {:email "jane@example.com" :name "Jane Doe"}
                            :items [{:sku "MUG-001" :qty 2}
                                    {:sku "TOTE-001" :qty 1}]}))
  (inventory/current-stock (d/db (db/conn)) "MUG-001") ;; down to 18

  (orders/get-order (d/db (db/conn)) (:order-id result))
  (orders/list-orders-for-customer (d/db (db/conn)) "jane@example.com")
  (orders/update-status! (:order-id result) :paid)

  ;; 6. Run the API locally and hit it with curl. Use store.core/handler,
  ;;    not store.api/app directly — that's the version with the Stripe
  ;;    webhook middleware wrapped around it (see store.core).
  (stripe/init! (System/getenv "STRIPE_SECRET_KEY"))
  (defonce server (jetty/run-jetty core/handler {:port 8080 :join? false}))
  ;; curl http://localhost:8080/api/products
  ;; curl http://localhost:8080/api/products/MUG-001
  (.stop server)

  ;; 7. Or drive a checkout session straight from the REPL instead of curl
  (stripe/create-checkout-session!
   (:order-id result)
   {:success-url "http://localhost:8080/thanks"
    :cancel-url "http://localhost:8080/cart"})
  ;; open the returned :checkout-url in a browser, pay with the Stripe
  ;; test card 4242 4242 4242 4242 (any future expiry, any CVC), and
  ;; with `stripe listen --forward-to localhost:8080/api/stripe/webhook`
  ;; running in another terminal, the order below should flip to :paid
  (orders/get-order (d/db (db/conn)) (:order-id result))
  )
