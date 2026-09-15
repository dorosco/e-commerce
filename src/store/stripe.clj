(ns store.stripe
  (:require [datomic.client.api :as d]
            [store.db :as db]
            [store.orders :as orders]
            [clojure.data.json :as json])
  (:import [com.stripe Stripe]
           [com.stripe.model.checkout Session]
           [com.stripe.net Webhook]
           [com.stripe.exception SignatureVerificationException]
           [com.stripe.param.checkout
            SessionCreateParams
            SessionCreateParams$LineItem
            SessionCreateParams$LineItem$PriceData
            SessionCreateParams$LineItem$PriceData$ProductData
            SessionCreateParams$Mode]))

(defn init!
  "Call once at startup, before any other function in this namespace,
   with your Stripe secret key. Read it from an env var — never commit
   a key to the repo. e.g. (stripe/init! (System/getenv \"STRIPE_SECRET_KEY\"))"
  [secret-key]
  (set! Stripe/apiKey secret-key))

(defn- ->line-item
  "Builds one Stripe Checkout line item from an order-item as returned
   by store.orders/get-order."
  [{:order-item/keys [qty price-cents product]}]
  (-> (SessionCreateParams$LineItem/builder)
      (.setQuantity (long qty))
      (.setPriceData
       (-> (SessionCreateParams$LineItem$PriceData/builder)
           (.setCurrency "usd")
           (.setUnitAmount (long price-cents))
           (.setProductData
            (-> (SessionCreateParams$LineItem$PriceData$ProductData/builder)
                (.setName (:product/name product))
                (.build)))
           (.build)))
      (.build)))

(defn create-checkout-session!
  "Builds a Stripe Checkout Session for an already-placed order and
   records the session id on it. success-url/cancel-url are where
   Stripe sends the customer's browser afterwards.

   Note: the order does NOT move to :paid here, even on the redirect to
   success-url — a browser redirect isn't a trustworthy payment signal
   on its own (the customer could close the tab, or the payment could
   still be processing for some payment methods). Only the webhook
   below, once Stripe confirms the charge server-to-server, marks an
   order as paid."
  [order-id {:keys [success-url cancel-url]}]
  (let [dbv (d/db (db/conn))
        order (orders/get-order dbv order-id)
        base (-> (SessionCreateParams/builder)
                 (.setMode SessionCreateParams$Mode/PAYMENT)
                 (.setSuccessUrl success-url)
                 (.setCancelUrl cancel-url)
                 ;; This is how the webhook below finds its way back to
                 ;; the right order — Stripe echoes it back on every
                 ;; event related to this session.
                 (.setClientReferenceId (str order-id)))
        with-items (reduce (fn [b item] (.addLineItem b (->line-item item)))
                            base
                            (:order/items order))
        session (Session/create (.build with-items))]
    (d/transact (db/conn)
                {:tx-data [{:db/id order-id :order/stripe-session (.getId session)}]})
    {:checkout-url (.getUrl session) :session-id (.getId session)}))

(defn verify-and-parse-event
  "Verifies the webhook signature against webhook-secret — throws
   SignatureVerificationException if it doesn't match, which callers
   should treat as \"reject this request\", not a bug to fix — and
   returns the event as a plain Clojure map, parsed straight from the
   JSON payload.

   We deliberately don't use Stripe's typed Event/deserializer classes
   for reading the data: their exact shape shifts across SDK and Stripe
   API versions, and all we need here are a couple of fields, so parsing
   the raw JSON ourselves is both simpler and more stable over time."
  [payload sig-header webhook-secret]
  (Webhook/constructEvent payload sig-header webhook-secret)
  (json/read-str payload :key-fn keyword))

(defn handle-event!
  "Reacts to a verified, parsed webhook event. Only checkout.session.completed
   does anything; every other event type is a no-op — we still return
   200 for those (see wrap-webhook) so Stripe doesn't keep retrying them.

   Also checks payment_status directly rather than assuming \"completed\"
   means \"paid\" — some payment methods settle asynchronously, in which
   case the session can complete before the money has actually cleared."
  [{:keys [type data]}]
  (when (= type "checkout.session.completed")
    (let [session (:object data)]
      (when (= (:payment_status session) "paid")
        (orders/update-status! (Long/parseLong (:client_reference_id session)) :paid)))))

(defn wrap-webhook
  "Ring middleware that intercepts POST /api/stripe/webhook and handles
   it directly, bypassing the rest of the app entirely — Stripe's
   signature check needs the exact raw request bytes, and once JSON
   middleware (like the muuntaja setup in store.api) has parsed and
   re-encoded a body, there's no guarantee it round-trips byte-for-byte,
   which would silently break signature verification. So this middleware
   has to sit in front of, not inside, the reitit app — see store.core.

   Every other request passes through to handler unchanged."
  [handler webhook-secret]
  (fn [{:keys [uri request-method] :as request}]
    (if (and (= uri "/api/stripe/webhook") (= request-method :post))
      (if (nil? webhook-secret)
        {:status 500 :body "STRIPE_WEBHOOK_SECRET is not set"}
        (let [payload (slurp (:body request))
              sig-header (get-in request [:headers "stripe-signature"])]
          (try
            (handle-event! (verify-and-parse-event payload sig-header webhook-secret))
            {:status 200 :headers {"Content-Type" "application/json"} :body "{}"}
            (catch SignatureVerificationException _
              {:status 400 :body "invalid signature"}))))
      (handler request))))
