(ns store.products
  (:require [datomic.client.api :as d]
            [store.db :as db]))

(defn create-product!
  "sku, name, price-cents are required. description/category/image-path are optional."
  [{:keys [sku name price-cents description category image-path]}]
  (when (or (nil? sku) (nil? name) (nil? price-cents))
    (throw (ex-info "sku, name, and price-cents are required"
                     {:sku sku :name name :price-cents price-cents})))
  (let [tx-data [(cond-> {:product/sku sku
                          :product/name name
                          :product/price-cents price-cents
                          :product/active? true}
                   description (assoc :product/description description)
                   category (assoc :product/category category)
                   image-path      (assoc :product/image-path image-path))]]
    (d/transact (db/conn) {:tx-data tx-data})))

(defn update-product!
  "Partial update by sku — only the keys you pass in are changed.
   e.g. (update-product! \"ABC-123\" {:product/price-cents 2500})"
  [sku updates]
  (d/transact (db/conn) {:tx-data [(assoc updates :product/sku sku)]}))

(defn deactivate-product!
  "Soft delete — flips active? to false so past orders still resolve
   this product correctly, instead of retracting the entity."
  [sku]
  (d/transact (db/conn) {:tx-data [{:product/sku sku :product/active? false}]}))

(defn get-product [db sku]
  (d/pull db '[:product/sku :product/name :product/description
               :product/price-cents :product/image-path :product/active?]
          [:product/sku sku]))

(defn list-active-products
  "Note: `(pull ?p [...])` comes back wrapped in a one-element tuple per
   row under the Client API's find-rel-only restriction, so `map first`
   unwraps it instead of a `[... ...]` find-coll spec."
  [db]
  (->> (d/q '[:find (pull ?p [:product/sku :product/name
                               :product/price-cents :product/image-path])
              :in $
              :where [?p :product/active? true]]
            db)
       (map first)
       (sort-by :product/name)))
