(ns store.db
  (:require [datomic.client.api :as d]
            [store.schema :as schema]))

(def db-name "store")

(defn client
  "Returns a Datomic client. Controlled by the STORE_ENV env var:
     - \"dev-local\" (default) — free, local, no AWS needed. Good for all
       day-to-day development.
     - \"cloud\" — talks to a real Datomic Cloud system. Use this only when
       running as a deployed Ion, or for a final pre-launch check."
  []
  (case (or (System/getenv "STORE_ENV") "dev-local")
    "dev-local"
    (d/client {:server-type :dev-local
               :system "store-dev"})

    "cloud"
    ;; Fill these in to match your actual Datomic Cloud system once it's
    ;; provisioned — see the Datomic Cloud "Client API" docs for the exact
    ;; endpoint format for your region/system name.
    (d/client {:server-type :ion
               :region "us-east-1"
               :system "store-prod"
               :query-group "store-prod"
               :endpoint "http://entry.store-prod.us-east-1.datomic.net:8182/"
               :proxy-port 8182})))

(defn ensure-db!
  "Creates the database if it doesn't already exist. Safe to call repeatedly."
  []
  (d/create-database (client) {:db-name db-name}))

(defn conn []
  (d/connect (client) {:db-name db-name}))

(defn install-schema!
  "Run once against a fresh database (or again after adding new schema —
   Datomic schema installs are additive and idempotent)."
  []
  (d/transact (conn) {:tx-data schema/all-schema}))
