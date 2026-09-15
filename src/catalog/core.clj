(ns catalog.core
  "Generates a printable PDF product catalog from a Datomic database.

   Pipeline: Datomic db -> product maps -> Hiccup HTML -> PDF (openhtmltopdf).

   USAGE (from the REPL, whenever the catalog needs refreshing):

     (require '[catalog.core :as catalog])
     (def conn (d/connect client {:db-name \"your-db-name\"}))
     (catalog/generate-catalog! (d/db conn)
                                 {:output-path \"catalog.pdf\"
                                  :business-name \"Your Store Name\"
                                  :contact-line  \"WhatsApp +51 999 999 999 · @yourstore\"})

   SCHEMA ASSUMPTIONS - adjust `fetch-products` / `product-pull-pattern` to match
   your actual attribute names. I've assumed:

     :product/sku          string, unique
     :product/name         string
     :product/description  string
     :product/price        bigdec (in your local currency, e.g. soles)
     :product/category     string, or ref to an entity with :category/name
     :product/image-bytes  bytes  (OR :product/image-url string - see image->data-uri)
     :product/active?      boolean"
  (:require [datomic.client.api :as d]
            [hiccup2.core :as h]
            [hiccup.page :as hp]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util Base64]
           [java.io ByteArrayOutputStream]
           [com.openhtmltopdf.pdfboxout PdfRendererBuilder]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; 1. Pulling product data out of Datomic
;; ---------------------------------------------------------------------------

(def product-pull-pattern
  "What we ask Datomic for, per product entity. Tweak to match your schema."
  [:product/sku
   :product/name
   :product/description
   :product/price
   :product/category
   :product/image-bytes
   :product/image-url
   :product/active?])

(defn fetch-products
  "Returns a seq of product maps for every active product in `db`.
   `db` is a Datomic db value, e.g. (d/db conn)."
  [db]
  (->> (d/q '[:find (pull ?p pull-pattern)
              :in $ pull-pattern
              :where [?p :product/sku]
                     [?p :product/active? true]]
            db product-pull-pattern)
       (map first)
       (sort-by (juxt :product/category :product/name))))

(defn group-by-category
  "Groups products into a sorted seq of [category-name products] pairs."
  [products]
  (->> products
       (group-by #(or (:product/category %) "Otros"))
       (sort-by key)))

;; ---------------------------------------------------------------------------
;; 2. Image handling - normalize everything to an embeddable data: URI
;; ---------------------------------------------------------------------------

(defn bytes->data-uri [^bytes bs mime-type]
  (str "data:" mime-type ";base64,"
       (.encodeToString (Base64/getEncoder) bs)))

(defn file->data-uri [path mime-type]
  (with-open [out (ByteArrayOutputStream.)]
    (io/copy (io/file path) out)
    (bytes->data-uri (.toByteArray out) mime-type)))

(defn product-image-uri
  "Best-effort: bytes stored directly in Datomic take priority; falls back
   to a local file path/URL string if that's what you're storing instead.
   Returns nil (no image) rather than throwing, so one bad image never
   breaks the whole catalog run."
  [{:keys [product/image-bytes product/image-url]}]
  (try
    (cond
      image-bytes (bytes->data-uri image-bytes "image/jpeg")
      (and image-url (str/starts-with? image-url "http"))
      image-url ;; openhtmltopdf can fetch http(s) directly if network access is available at render time
      image-url (file->data-uri image-url "image/jpeg")
      :else nil)
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; 3. HTML rendering (Hiccup)
;; ---------------------------------------------------------------------------

(defn- money [amount]
  (format "S/ %,.2f" (double amount))) ;; adjust currency symbol/format as needed

(def catalog-css
  "Kept inline so the HTML string is fully self-contained. openhtmltopdf
   understands @page rules for print margins, running footers, and page numbers."
  "
  @page {
    size: A4;
    margin: 2cm 1.5cm 2.5cm 1.5cm;
    @bottom-center {
      content: 'Página ' counter(page) ' de ' counter(pages);
      font-size: 9px; color: #888;
    }
  }
  * { box-sizing: border-box; }
  body { font-family: 'Helvetica', 'Arial', sans-serif; color: #222; font-size: 11px; }
  header.cover { text-align: center; margin-bottom: 24px; }
  header.cover h1 { font-size: 26px; margin-bottom: 4px; }
  header.cover .tagline { color: #666; font-size: 12px; }
  header.cover .generated { color: #999; font-size: 9px; margin-top: 6px; }
  h2.category { font-size: 15px; margin: 22px 0 10px 0; padding-bottom: 4px;
                border-bottom: 1px solid #ddd; page-break-after: avoid; }
  .grid { display: table; width: 100%; }
  .row { display: table-row; }
  .product { display: inline-block; width: 47%; vertical-align: top;
             margin: 0 1.5% 14px 1.5%; page-break-inside: avoid; }
  .product img { width: 100%; height: 130px; object-fit: cover;
                 border: 1px solid #eee; border-radius: 4px; }
  .product .no-image { width: 100%; height: 130px; background: #f4f4f4;
                        border: 1px solid #eee; border-radius: 4px;
                        display: flex; align-items: center; justify-content: center;
                        color: #bbb; font-size: 10px; }
  .product .name { font-weight: bold; font-size: 12px; margin-top: 6px; }
  .product .sku { color: #aaa; font-size: 8px; }
  .product .price { color: #b0442f; font-weight: bold; font-size: 12px; margin-top: 2px; }
  .product .desc { font-size: 9.5px; color: #555; margin-top: 3px; line-height: 1.35; }
  footer.contact { margin-top: 28px; padding-top: 8px; border-top: 1px solid #ddd;
                   text-align: center; font-size: 9.5px; color: #666; }
  ")

(defn render-product [product]
  (let [img (product-image-uri product)]
    [:div.product
     (if img
       [:img {:src img :alt (:product/name product)}]
       [:div.no-image "Sin imagen"])
     [:div.name (:product/name product)]
     [:div.sku (str "SKU: " (:product/sku product))]
     [:div.price (money (:product/price product))]
     (when-let [d (:product/description product)]
       [:div.desc d])]))

(defn render-category [[category-name products]]
  [:section
   [:h2.category category-name]
   [:div.grid (map render-product products)]])

(defn render-catalog-html
  [products {:keys [business-name tagline contact-line]}]
  (let [today (.format (LocalDate/now) (DateTimeFormatter/ofPattern "d 'de' MMMM, yyyy"))]
    (str
     (h/html
      (hp/doctype :html5)
      [:html
       [:head [:meta {:charset "utf-8"}] [:style catalog-css]]
       [:body
        [:header.cover
         [:h1 business-name]
         (when tagline [:div.tagline tagline])
         [:div.generated (str "Catálogo generado el " today)]]
        (map render-category (group-by-category products))
        [:footer.contact contact-line]]]))))

;; ---------------------------------------------------------------------------
;; 4. HTML -> PDF
;; ---------------------------------------------------------------------------

(defn html->pdf! [html-string output-path]
  (with-open [os (io/output-stream output-path)]
    (-> (PdfRendererBuilder.)
        (.useFastMode)
        (.withHtmlContent html-string nil) ;; nil base-uri: fine since images are data: URIs
        (.toStream os)
        (.run))))

;; ---------------------------------------------------------------------------
;; 5. Orchestration - the one function you actually call
;; ---------------------------------------------------------------------------

(defn generate-catalog!
  "db      - a Datomic db value, e.g. (d/db conn)
   opts    - {:output-path \"catalog.pdf\"
              :business-name \"...\"
              :tagline \"...\"            ; optional
              :contact-line \"...\"}"
  [db {:keys [output-path] :as opts}]
  (let [products (fetch-products db)
        html     (render-catalog-html products opts)]
    (html->pdf! html output-path)
    (println (format "Catálogo generado: %s (%d productos)" output-path (count products)))
    output-path))
