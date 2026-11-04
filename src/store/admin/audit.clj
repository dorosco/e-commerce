(ns store.admin.audit
  "Audit trail from Datomic history. Every UI write is annotated with
   :tx/user, :tx/source and :tx/note (see shop.admin.products/transact!)."
  (:require [datomic.client.api :as d]))

(def tracked
  [:product/sku :product/name :product/category :product/description
   :product/price-cents :product/stock :product/active?])

(defn- old-new
  "Within one transaction an update appears as a retraction (old) plus an
   assertion (new). `keep` (not `some`) so a boolean false isn't lost."
  [rows]
  {:old (first (keep (fn [[_ _ _ _ v op]] (when-not op v)) rows))
   :new (first (keep (fn [[_ _ _ _ v op]] (when op v)) rows))})

(defn- group-events
  "tuples: [tx inst sku attr v op]  ->  newest-first events with who/why attached."
  [db tuples limit]
  (->> tuples
       (group-by first)
       (map (fn [[tx rows]]
              {:tx tx
               :at (second (first rows))
               :changes (->> rows
                             (group-by (juxt #(nth % 2) #(nth % 3)))
                             (map (fn [[[sku attr] rs]] (merge {:sku sku :attr attr} (old-new rs))))
                             (sort-by (juxt :sku (comp str :attr))))}))
       (sort-by :at #(compare %2 %1))
       (take limit)
       (map (fn [e]
              (let [a (d/pull db [:tx/user :tx/note] (:tx e))]
                (assoc e :user (:tx/user a) :note (:tx/note a)))))))

(defn recent-changes
  "Latest changes made through the admin UI (transactions carrying :tx/user)."
  [db limit]
  (group-events
    db
    (d/q '[:find ?tx ?inst ?sku ?attr ?v ?op
           :in $ [?attr ...]
           :where [?a :db/ident ?attr]
                  [?e ?a ?v ?tx ?op]
                  [?tx :tx/user _]
                  [?tx :db/txInstant ?inst]
                  [?e :product/sku ?sku]]
         (d/history db) tracked)
    limit))

(defn product-history
  "Full history of one product, including changes made before the UI existed
   (those have no user: they come from the Excel import or your REPL)."
  [db sku limit]
  (group-events
    db
    (d/q '[:find ?tx ?inst ?sku ?attr ?v ?op
           :in $ ?sku [?attr ...]
           :where [?e :product/sku ?sku]
                  [?a :db/ident ?attr]
                  [?e ?a ?v ?tx ?op]
                  [?tx :db/txInstant ?inst]]
         (d/history db) sku tracked)
    limit))
