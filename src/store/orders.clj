(ns store.orders
  (:require [datomic.client.api :as d]
            [store.db :as db]
            [store.inventory :as inventory])
  (:import [java.util Date]))

(def valid-statuses #{:pending :paid :shipped :cancelled})

(defn- lookup-item
  "Pulls the current price/active-state for a line item's product, and
   fails fast if the sku doesn't exist or has been deactivated."
  [db {:keys [sku qty]}]
  (when-not (pos-int? qty)
    (throw (ex-info "item quantity must be a positive integer" {:sku sku :qty qty})))
  (let [product (d/pull db [:db/id :product/price-cents :product/active?]
                         [:product/sku sku])]
    (when-not (:product/active? product)
      (throw (ex-info "product not found or inactive" {:sku sku})))
    {:sku sku :qty qty :product product}))

(defn create-order!
  "customer is {:email ... :name ...}; items is a seq of {:sku ... :qty ...}.
   Looks up current prices and snapshots them onto each order-item (so a
   later price change never rewrites the price of a past order), places
   the order and its line items in one transaction, and then records a
   :sale inventory change per line.

   Note: this does not yet check that stock covers the order before
   selling — for a store this size that's an acceptable gap to start with
   (you'll notice a negative stock number and know to restock), but it's
   worth tightening once volume grows. Flagging it here rather than
   quietly leaving it out."
  [{:keys [customer items]}]
  (when (empty? items)
    (throw (ex-info "order must have at least one item" {})))
  (let [conn (db/conn)
        dbv (d/db conn)
        item-lookups (mapv #(lookup-item dbv %) items)
        customer-tempid "new-customer"
        order-tempid "new-order"
        customer-tx {:db/id customer-tempid
                     :customer/email (:email customer)
                     :customer/name (:name customer)}
        order-tx {:db/id order-tempid
                  :order/customer customer-tempid
                  :order/status :pending
                  :order/placed-at (Date.)}
        item-tx (map-indexed
                 (fn [i {:keys [qty product]}]
                   {:db/id (str "item-" i)
                    :order-item/order order-tempid
                    :order-item/product (:db/id product)
                    :order-item/qty qty
                    :order-item/price-cents (:product/price-cents product)})
                 item-lookups)
        {:keys [tempids]} (d/transact conn {:tx-data (into [customer-tx order-tx] item-tx)})]
    (doseq [{:keys [sku qty]} item-lookups]
      (inventory/record-change! sku (- qty) :sale))
    {:order-id (get tempids order-tempid)}))

(defn total-cents
  "Sum of qty * price-cents across an order's line items.

   Note: the Client API only supports find-rel, so the aggregate comes
   back as a set of tuples (e.g. #{[3600]}) rather than a bare scalar —
   `ffirst` pulls the value out."
  [db order-id]
  (or (ffirst (d/q '[:find (sum ?line-total)
                      :in $ ?order
                      :where
                      [?item :order-item/order ?order]
                      [?item :order-item/qty ?qty]
                      [?item :order-item/price-cents ?price]
                      [(* ?qty ?price) ?line-total]]
                    db order-id))
      0))

(defn get-order [db order-id]
  (let [order (d/pull db '[:db/id :order/status :order/placed-at :order/stripe-session
                            {:order/customer [:customer/email :customer/name]}]
                       order-id)
        ;; find-rel only — map first to unwrap each one-element tuple
        ;; instead of using a `[... ...]` find-coll spec
        items (->> (d/q '[:find (pull ?item [:order-item/qty :order-item/price-cents
                                              {:order-item/product [:product/sku :product/name]}])
                          :in $ ?order
                          :where [?item :order-item/order ?order]]
                        db order-id)
                   (map first))]
    (assoc order
           :order/items items
           :order/total-cents (total-cents db order-id))))

(defn list-orders-for-customer
  "Note: `[?o ...]` is a find-coll spec and isn't supported by the Client
   API — use plain `:find ?o` (find-rel) and unwrap each tuple with `map
   first` instead."
  [db email]
  (->> (d/q '[:find ?o
              :in $ ?email
              :where
              [?c :customer/email ?email]
              [?o :order/customer ?c]]
            db email)
       (map first)
       (map #(get-order db %))
       (sort-by :order/placed-at)
       reverse))

(defn update-status!
  "Valid transitions: :pending -> :paid -> :shipped, or -> :cancelled from
   :pending/:paid. Not enforcing the state machine here yet — just
   validating the target status is a real one — since the Stripe webhook
   and any admin action are the only two places that will call this."
  [order-id status]
  (when-not (valid-statuses status)
    (throw (ex-info "invalid order status" {:status status})))
  (d/transact (db/conn) {:tx-data [{:db/id order-id :order/status status}]}))
