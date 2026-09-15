(ns store.inventory
  (:require [datomic.client.api :as d]
            [store.db :as db])
  (:import [java.util Date]))

(def valid-reasons #{:restock :sale :adjustment})

(defn record-change!
  "Records a signed inventory change against a product's sku — e.g. +50 for
   a restock or -1 for a sale. Current stock is derived by summing every
   change ever recorded, rather than kept as a mutable counter, so the
   inventory ledger doubles as a full history of what happened and why."
  [sku delta reason]
  (when-not (valid-reasons reason)
    (throw (ex-info "reason must be :restock, :sale, or :adjustment" {:reason reason})))
  (when (zero? delta)
    (throw (ex-info "delta must be non-zero" {:delta delta})))
  (d/transact (db/conn)
              {:tx-data [{:inventory/product      [:product/sku sku]
                          :inventory/quantity      delta
                          :inventory/reason        reason
                          :inventory/recorded-at   (Date.)}]}))

(defn restock! [sku qty]
  (when-not (pos-int? qty)
    (throw (ex-info "restock quantity must be a positive integer" {:qty qty})))
  (record-change! sku qty :restock))

(defn adjust!
  "For corrections — damaged goods, recounts, etc. delta can be positive
   or negative."
  [sku delta]
  (record-change! sku delta :adjustment))

(defn current-stock
  "Sums every inventory change recorded for a product. Returns 0 for a
   product with no inventory history yet.

   Note: the Datomic Client API only supports find-rel — no find-scalar
   (`.`) or find-coll (`...`) — so the aggregate comes back as a set of
   tuples like #{[42]} (or #{} if nothing matched) and we pull the value
   out ourselves."
  [db sku]
  (or (ffirst (d/q '[:find (sum ?qty)
                      :in $ ?sku
                      :where
                      [?p :product/sku ?sku]
                      [?i :inventory/product ?p]
                      [?i :inventory/quantity ?qty]]
                    db sku))
      0))

(defn stock-history
  "All inventory events for a product, oldest first — restocks, sales,
   and adjustments, exactly as they happened.

   Same find-rel restriction as current-stock: `(pull ?i [...])` comes
   back wrapped in a one-element tuple per row, so we `map first` to
   unwrap it instead of using a `[... ...]` find-coll spec."
  [db sku]
  (->> (d/q '[:find (pull ?i [:inventory/quantity :inventory/reason
                               :inventory/recorded-at])
              :in $ ?sku
              :where
              [?p :product/sku ?sku]
              [?i :inventory/product ?p]]
            db sku)
       (map first)
       (sort-by :inventory/recorded-at)))

(defn low-stock
  "Active products whose current stock is at or below threshold. Fine at
   this scale (walks every active product); if the catalog grows into the
   thousands this is worth turning into a single aggregating query."
  [db threshold]
  (->> (d/q '[:find ?sku ?name
              :where
              [?p :product/active? true]
              [?p :product/sku ?sku]
              [?p :product/name ?name]]
            db)
       (map (fn [[sku name]] {:sku sku :name name :stock (current-stock db sku)}))
       (filter #(<= (:stock %) threshold))
       (sort-by :stock)))
