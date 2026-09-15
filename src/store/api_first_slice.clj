(ns store.api
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja-mw]
            [muuntaja.core :as m]
            [datomic.client.api :as d]
            [store.db :as db]
            [store.products :as products]))

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

;; Note: this route table has no auth on the write endpoints yet — that's
;; fine for local dev, but before this goes anywhere public, POST/PUT/DELETE
;; on /api/products need an auth middleware in front of them (Phase 1 task).
(def routes
  [["/api/products"
    {:get  {:handler list-products-handler}
     :post {:handler create-product-handler}}]
   ["/api/products/:sku"
    {:get    {:handler get-product-handler}
     :put    {:handler update-product-handler}
     :delete {:handler deactivate-product-handler}}]])

(def app
  (ring/ring-handler
   (ring/router routes {:data {:muuntaja m/instance
                                :middleware [muuntaja-mw/format-middleware]}})
   (ring/create-default-handler)))
