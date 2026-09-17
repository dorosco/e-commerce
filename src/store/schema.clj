
(ns store.schema
  "All Datomic schema for the store, as data. Install with
   store.db/install-schema! against a fresh database.")

(def product-schema
  [{:db/ident       :product/sku
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stock-keeping unit, unique per product"}

   {:db/ident       :product/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :product/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :product/price-cents
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Price in integer cents — never use floats for money"}

   {:db/ident       :product/image-path
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "path, example images/D001.png"}
   ;; original propuesta por Claude 
   ;; {:db/ident       :product/images
   ;;  :db/valueType   :db.type/string
   ;;  :db/cardinality :db.cardinality/many
   ;;  :db/doc         "S3 object keys for product photos"}

   ;; new version proposed by DeepSeek
   ;; {:db/ident       :product/primary-image
   ;;  :db/valueType   :db.type/ref
   ;;  :db/cardinality :db.cardinality/one
   ;;  :db/doc         "Main image (used for catalog and listings"}

   ;; {:db/ident       :product/images
   ;;  :db/valueType   :db.type/ref
   ;;  :db/cardinality :db.cardinality/many
   ;;  :db/isComponent true
   ;;  :db/doc         "Set of product images (component entities"}

   ;; {:db/ident       :image/url
   ;;  :db/valueType   :db.type/string
   ;;  :db/cardinality :db.cardinality/one
   ;;  :db/doc         "Image path; local relative path in dev, e.g. images/abc.jpg"}

   ;; {:db/ident       :image/caption
   ;;  :db/valueType   :db.type/string
   ;;  :db/cardinality :db.cardinality/one
   ;;  :db/doc         "Optional image caption"}

   ;; {:db/ident       :image/order
   ;;  :db/valueType   :db.type/long
   ;;  :db/cardinality :db.cardinality/one
   ;;  :db/doc         "Display order; lower numbers first"}
   ;;
   ;; end of new version proposed by DeepSeek
   
   ;; Proposed by Claude for Catalog
   ;; Category as string
   {:db/ident       :product/category
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Category of the product"}
   ;;
   ;; End of proposed for Catalog

   
   {:db/ident       :product/active?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "False instead of deleting, so order history stays intact"}])

(def inventory-schema
  [{:db/ident       :inventory/product
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :inventory/quantity
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :inventory/reason
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":restock, :sale, or :adjustment"}

   {:db/ident       :inventory/recorded-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def customer-schema
  [{:db/ident       :customer/email
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :customer/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def order-schema
  [{:db/ident       :order/customer
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order/status
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":pending, :paid, :shipped, or :cancelled"}

   {:db/ident       :order/stripe-session
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order/placed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def order-item-schema
  [{:db/ident       :order-item/order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item/product
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item/qty
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :order-item/price-cents
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Snapshot of the price at time of sale, not a live ref to product"}])

(def all-schema
  (vec (concat product-schema inventory-schema customer-schema
               order-schema order-item-schema)))
