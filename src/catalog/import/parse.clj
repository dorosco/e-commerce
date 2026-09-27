(ns catalog.import.parse
  (:require [clojure.string :as str])
  (:import [java.math BigDecimal]
           [java.time Instant]))

(def required-fields #{:sku :name :price})

(defn- blank? [s]
  (or (nil? s) (str/blank? (str s))))

(defn- parse-bigdec [s]
  (try
    (when-not (blank? s)
      (-> (str s)
          (str/replace #"[^\d.\-]" "")
          BigDecimal.))
    (catch Exception _ nil)))

;; (defn- parse-long [s]
;;   (try
;;     (when-not (blank? s)
;;       (Long/parseLong (str/replace (str s) #"[^\d\-]" "")))
;;     (catch Exception _ nil)))

;; robust 
(defn- parse-long [s]
  (try
    (when-not (blank? s)
      (cond
        (number? s) (long (Math/round (double s)))
        :else       (-> (str s)
                        (str/trim)
                        (str/replace #"[^\d\-]" "")
                        (Long/parseLong))))
    (catch Exception _ nil)))


(defn- slugify-base [s]
  (-> (str s)
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn- slugify-filename [filename]
  (let [s (str/trim (str filename))
        i (str/last-index-of s ".")]
    (if i
      (let [base (subs s 0 i)
            ext  (subs s i)]           ; includes the dot
        (str (slugify-base base) (str/lower-case ext)))
      (slugify-base s))))

(defn- build-image-url [prefix filename]
  (when-not (blank? filename)
    (str prefix (slugify-filename filename))))

(defn- is-there-stock? [stock]
  (pos? (parse-long stock)))

(defn parse-row
      "Returns {:ok tx-map} or {:error msg} for one sheet row."
      [{:keys [sku name description price category stock image-path row-number] :as row}
       {:keys [image-prefix]}]
      (println "DEBUG image raw:" (pr-str image-path)
               "| type:" (type image-path)
               "| blank?:" (blank? image-path)
               "| row:" row-number)
      (let [missing (->> required-fields
                         (filter #(blank? (get row %)))
                         seq)]
        (cond
         missing
         {:error (format "row %d: missing required fields %s"
                         row-number (pr-str (vec missing)))}

         (nil? (parse-long price))
         {:error (format "row %d: cannot parse price %s"
                         row-number (pr-str price))}

         :else
         {:ok
          (cond-> {:product/sku         (str/trim (str sku))
                                        :product/name        (str/trim (str name))
                                        :product/price-cents (parse-long price)
                                        :product/source-row  (long row-number)
                                        :product/active?     (is-there-stock? stock)
                                        :product/imported-at (java.util.Date/from (java.time.Instant/now))}
                  (not (blank? description)) (assoc :product/description (str/trim description))
                  (not (blank? category))    (assoc :product/category    (str/trim category))
                  (some? (parse-long stock)) (assoc :product/stock       (parse-long stock))
                  (not (blank? image-path))  (assoc :product/image-path  (build-image-url image-prefix image-path)))})))
