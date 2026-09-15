(ns catalog.cli
  "Command-line entry point, so regenerating the catalog is:

     clj -X:generate-catalog

   instead of needing a REPL open. Useful if you want your wife (or a cron
   job, or a button in an admin page later) to be able to trigger this
   without you being at the keyboard.

   TODO: fill in the client config / db-name to match how your app already
   connects to Datomic - this is deliberately left as a stub since that
   part depends on your storage backend (dev-local, Datomic Cloud, etc.)."
  (:require [datomic.client.api :as d]
            [catalog.core :as catalog]))

(defn -main
  [{:keys [db-name output-path business-name tagline contact-line]
    :or   {output-path   "catalog.pdf"
           business-name "Nombre de la Tienda"
           contact-line  "Escríbenos por WhatsApp o Instagram"}}]
  (let [client (d/client {:server-type :dev-local ;; <- change to match your setup
                           :system      "your-system-name"})
        conn   (d/connect client {:db-name (or db-name "your-db-name")})
        db     (d/db conn)]
    (catalog/generate-catalog! db
                                {:output-path   output-path
                                 :business-name business-name
                                 :tagline       tagline
                                 :contact-line  contact-line})))
