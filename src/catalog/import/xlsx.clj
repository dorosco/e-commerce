(ns catalog.import.xlsx
  (:require [clojure.string :as str]
            [dk.ative.docjure.spreadsheet :as xls]))

(defn- normalize-header [cell]
  (-> (str cell)
      str/trim
      str/lower-case
      keyword))

(defn- row->map [ks row]
  (zipmap ks (map xls/read-cell (xls/cell-seq row))))

(defn- empty-row? [row]
  (every? nil? (xls/cell-seq row)))

(defn read-rows
  "Reads a sheet and returns a seq of maps with normalized header keys.
   Adds :row-number (1-based, excluding header) for diagnostics."
  [file & {:keys [sheet-name]}]
  (let [wb    (xls/load-workbook file)
        sheet (if sheet-name
                (xls/select-sheet sheet-name wb)
                (first (xls/sheet-seq wb)))
        rows  (xls/row-seq sheet)
        [header & data] rows
        ks    (mapv normalize-header (xls/cell-seq header))]
    (->> data
         (remove empty-row?)
         (map-indexed
           (fn [i row]
             (-> (row->map ks row)
                 (assoc :row-number (inc i)))))
         doall)))
