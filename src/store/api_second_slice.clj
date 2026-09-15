(ns store.api
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja-mw]
            [muuntaja.core :as m]
            [datomic.client.api :as d]
            [store.db :as db]
            [store.products :as products]
            [store.orders :as orders]
            [store.inventory :as inventory]))

(defn list-products-handler [_req]
  (let [dbv (d/db (db/conn))]
    {:status 200
     :body {:products (products/list-active-products dbv)}}))

(defn get-product-handler [{:keys [path-params]}]
  (let [dbv (d/db (db/conn))
        sku (:sku path-params)
        product (products/get-product dbv sku)]
    (if (:product/sku product)
      {:status 200 :body product}
      {:status 404 :body {:error "product not found"}})))

(defn create-product-handler [{:keys [body-params]}]
  (try
    (products/create-product! body-params)
    {:status 201 :body {:sku (:sku body-params)}}
    (catch clojure.lang.ExceptionInfo e
      {:status 400 :body {:error (ex-message e)}})))

(defn update-product-handler [{:keys [path-params body-params]}]
  (products/update-product! (:sku path-params) body-params)
  {:status 200 :body {:sku (:sku path-params)}})

(defn deactivate-product-handler [{:keys [path-params]}]
  (products/deactivate-product! (:sku path-params))
  {:status 204})

(defn create-order-handler [{:keys [body-params]}]
  (try
    {:status 201 :body (orders/create-order! body-params)}
    (catch clojure.lang.ExceptionInfo e
      {:status 400 :body {:error (ex-message e)}})))

(defn get-order-handler [{:keys [path-params]}]
  (let [dbv (d/db (db/conn))
        order-id (Long/parseLong (:id path-params))
        order (orders/get-order dbv order-id)]
    (if (:db/id order)
      {:status 200 :body order}
      {:status 404 :body {:error "order not found"}})))

(defn update-order-status-handler [{:keys [path-params body-params]}]
  (try
    (let [status (keyword (:status body-params))]
      (orders/update-status! (Long/parseLong (:id path-params)) status)
      {:status 200 :body {:status status}})
    (catch clojure.lang.ExceptionInfo e
      {:status 400 :body {:error (ex-message e)}})))

(defn list-customer-orders-handler [{:keys [path-params]}]
  (let [dbv (d/db (db/conn))]
    {:status 200 :body {:orders (orders/list-orders-for-customer dbv (:email path-params))}}))

(defn restock-handler [{:keys [path-params body-params]}]
  (try
    (inventory/restock! (:sku path-params) (:qty body-params))
    {:status 200 :body {:sku (:sku path-params) :restocked (:qty body-params)}}
    (catch clojure.lang.ExceptionInfo e
      {:status 400 :body {:error (ex-message e)}})))

(defn stock-handler [{:keys [path-params]}]
  (let [dbv (d/db (db/conn))
        sku (:sku path-params)]
    {:status 200 :body {:sku sku
                         :current-stock (inventory/current-stock dbv sku)
                         :history (inventory/stock-history dbv sku)}}))

(defn low-stock-handler [{:keys [query-params]}]
  (let [dbv (d/db (db/conn))
        threshold (Long/parseLong (get query-params "threshold" "5"))]
    {:status 200 :body {:low-stock (inventory/low-stock dbv threshold)}}))

;; Note: this route table has no auth on the write endpoints yet — that's
;; fine for local dev, but before this goes anywhere public, every
;; POST/PUT/DELETE below (products, orders, inventory) needs an auth
;; middleware in front of it. The product write routes were flagged
;; earlier; same applies to everything added since.
(def routes
  [["/api/products"
    {:get  {:handler list-products-handler}
     :post {:handler create-product-handler}}]
   ["/api/products/:sku"
    {:get    {:handler get-product-handler}
     :put    {:handler update-product-handler}
     :delete {:handler deactivate-product-handler}}]

   ["/api/orders"
    {:post {:handler create-order-handler}}]
   ["/api/orders/:id"
    {:get {:handler get-order-handler}}]
   ["/api/orders/:id/status"
    {:put {:handler update-order-status-handler}}]
   ["/api/customers/:email/orders"
    {:get {:handler list-customer-orders-handler}}]

   ;; /api/low-stock is its own path, not nested under /api/inventory/:sku
   ;; — reitit's router builds a static trie and won't let a fixed segment
   ;; ("low") and a wildcard param (":sku") share the same position, so
   ;; they need to be genuinely different paths rather than reordered.
   ["/api/low-stock"
    {:get {:handler low-stock-handler}}]
   ["/api/inventory/:sku"
    {:get {:handler stock-handler}}]
   ["/api/inventory/:sku/restock"
    {:post {:handler restock-handler}}]])

(def app
  (ring/ring-handler
   (ring/router routes {:data {:muuntaja m/instance
                                :middleware [muuntaja-mw/format-middleware]}})
   (ring/create-default-handler)))
