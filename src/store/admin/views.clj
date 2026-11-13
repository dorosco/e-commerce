(ns store.admin.views
  "Server-rendered pages (Hiccup 2 escapes all text by default) and htmx fragments.
   The interface is in Spanish; change the strings here if you prefer another language."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [ring.middleware.anti-forgery :as af]
            [ring.util.codec :as codec]
            [store.admin.products :as products]
            [store.tool-api :as tool])
  (:import (java.time ZoneId)
           (java.time.format DateTimeFormatter)))

(def ^:private css
  "body{font:15px/1.45 system-ui,sans-serif;margin:0;background:#f5f6f8;color:#1f2933}
header{display:flex;justify-content:space-between;align-items:center;background:#1f2933;color:#fff;padding:.6rem 1rem}
header nav{display:flex;gap:1rem;align-items:center}header a{color:#cbd5e1;text-decoration:none}header a:hover{color:#fff}
header form{display:flex;gap:.6rem;align-items:center;margin:0}
main{max-width:1100px;margin:1.2rem auto;padding:0 1rem}
table{width:100%;border-collapse:collapse;background:#fff}
th,td{padding:.45rem .6rem;border-bottom:1px solid #e5e7eb;text-align:left;vertical-align:middle}
th{background:#eef0f3;font-size:.85rem}td.num{text-align:right}.mono{font-family:ui-monospace,monospace}
button,.button{font:inherit;padding:.3rem .7rem;border:1px solid #2563eb;background:#2563eb;color:#fff;border-radius:6px;cursor:pointer;text-decoration:none;display:inline-block}
button.secondary{background:#fff;color:#2563eb}
input,select{font:inherit;padding:.3rem .5rem;border:1px solid #cbd5e1;border-radius:6px}
.badge{padding:.1rem .5rem;border-radius:999px;font-size:.8rem;background:#e5e7eb}
.badge.in_stock{background:#d1fae5}.badge.low_stock{background:#fef3c7}
.badge.out_of_stock,.badge.unavailable{background:#fee2e2}
.error{color:#b91c1c}.muted{color:#6b7280}.actions{white-space:nowrap}.actions>*{margin-right:.3rem}
tr.editing{background:#fffbeb}
.login{max-width:320px;margin:4rem auto;background:#fff;padding:1.5rem;border-radius:10px}
.login input{width:100%;box-sizing:border-box;margin-bottom:.8rem}
section{background:#fff;padding:1rem 1.2rem;margin-bottom:1rem;border-radius:8px}
.toolbar{display:flex;gap:.6rem;margin-bottom:.8rem}.toolbar input[type=search]{flex:1}
.htmx-request{opacity:.6}")

(defn- token [] af/*anti-forgery-token*)

(defn- csrf-field []
  [:input {:type "hidden" :name "__anti-forgery-token" :value (token)}])

(defn fragment [x] (str (h/html x)))

(defn layout [{:keys [title user]} & content]
  (str "<!DOCTYPE html>"
       (h/html
         [:html {:lang "es"}
          [:head
           [:meta {:charset "utf-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
           [:title (str title " · Tienda")]
           [:style (h/raw css)]
           [:script {:src "/htmx.min.js"}]]
          ;; htmx sends the CSRF token on every request it makes
          [:body {:hx-headers (json/generate-string {"X-CSRF-Token" (token)})}
           (when user
             [:header
              [:nav [:strong "Tienda"]
               [:a {:href "/products"} "Productos"]
               [:a {:href "/audit"} "Historial"]
               [:a {:href "/data"} "Importar / Exportar"]]
              [:form {:method "post" :action "/logout"}
               (csrf-field) [:span user] [:button.secondary "Salir"]]])
           [:main content]]])))

;; ---------------------------------------------------------------------------
;; Login
;; ---------------------------------------------------------------------------

(defn login-page [& [message]]
  (layout {:title "Ingresar"}
          [:form.login {:method "post" :action "/login"}
           [:h2 "Ingresar"]
           (when message [:p.error message])
           (csrf-field)
           [:label {:for "username"} "Usuario"]
           [:input {:id "username" :name "username" :autocomplete "username" :required true :autofocus true}]
           [:label {:for "password"} "Contraseña"]
           [:input {:id "password" :name "password" :type "password" :autocomplete "current-password" :required true}]
           [:button "Entrar"]]))

;; ---------------------------------------------------------------------------
;; Products
;; ---------------------------------------------------------------------------

(def ^:private avail-label
  {"in_stock" "En stock" "low_stock" "Poco stock" "out_of_stock" "Agotado" "unavailable" "No disponible"})

(defn- row-id [p] (str "row-" (str/replace (:product/sku p) #"[^A-Za-z0-9_-]" "_")))
(defn- sku-url [p] (str "/products/" (codec/url-encode (:product/sku p))))
(defn- money [c] (if c (str tool/currency " " (products/cents->str c)) "–"))

(defn product-row [p]
  (let [id (row-id p) url (sku-url p) av (tool/availability p)]
    [:tr {:id id}
     [:td.mono (:product/sku p)]
     [:td (:product/name p)]
     [:td (:product/category p)]
     [:td.num (money (:product/price-cents p))]
     [:td.num (if (nil? (:product/stock p)) "–" (:product/stock p))]
     [:td [:span {:class (str "badge " av)} (avail-label av)]]
     [:td.actions
      [:button {:hx-get (str url "/edit") :hx-target "closest tr" :hx-swap "outerHTML"} "Editar"]
      [:button.secondary {:hx-post (str url "/toggle") :hx-target (str "#" id) :hx-swap "outerHTML"}
       (if (true? (:product/active? p)) "Desactivar" "Activar")]
      [:a {:href (str url "/history")} "Historial"]]]))

(defn product-edit-row [p & [error]]
  (let [id (row-id p) url (sku-url p)]
    [:tr.editing {:id id}
     [:td.mono (:product/sku p)]
     [:td (:product/name p)]
     [:td (:product/category p)]
     [:td [:input {:name "price" :value (products/cents->str (:product/price-cents p))
                   :inputmode "decimal" :size 8 :aria-label "Precio"}]
      [:input {:type "hidden" :name "old-price" :value (:product/price-cents p)}]]
     [:td [:input {:name "stock" :value (:product/stock p) :inputmode "numeric" :size 5 :aria-label "Stock"}]
      [:input {:type "hidden" :name "old-stock" :value (:product/stock p)}]]
     [:td (when error [:span.error error])]
     [:td.actions
      [:button {:hx-post url :hx-include "closest tr" :hx-target (str "#" id) :hx-swap "outerHTML"} "Guardar"]
      [:button.secondary {:hx-get (str url "/row") :hx-target (str "#" id) :hx-swap "outerHTML"} "Cancelar"]]]))

(defn- page-url [q status page]
  (str "/products?" (codec/form-encode {"q" (or q "") "status" (or status "") "page" page})))

(defn- pager-link [q status page label]
  (let [u (page-url q status page)]
    [:a {:href u :hx-get u :hx-target "#results" :hx-swap "outerHTML" :hx-push-url "true"} label]))

(defn products-fragment [{:keys [rows total page pages q status]}]
  [:div#results
   [:p.muted (str total " productos")]
   [:table
    [:thead [:tr (for [c ["SKU" "Nombre" "Categoría" "Precio" "Stock" "Estado" ""]] [:th c])]]
    [:tbody (map product-row rows)]]
   (when (> pages 1)
     [:p
      (when (> page 1) (pager-link q status (dec page) "← Anterior"))
      (str "  Página " page " de " pages "  ")
      (when (< page pages) (pager-link q status (inc page) "Siguiente →"))])])

(defn products-page [ctx {:keys [q status] :as data}]
  (layout (assoc ctx :title "Productos")
          [:h1 "Productos"]
          ;; Typing (after 300 ms) or changing the filter refreshes only the results.
          ;; Without JavaScript the form still works as a normal GET.
          [:form.toolbar {:method "get" :action "/products" :hx-get "/products" :hx-target "#results"
                          :hx-swap "outerHTML" :hx-push-url "true" :hx-trigger "input delay:300ms, change"}
           [:input {:type "search" :name "q" :value q :placeholder "Buscar por SKU, nombre o categoría" :autofocus true}]
           [:select {:name "status"}
            (for [[v l] [["" "Todos"] ["active" "Activos"] ["low" "Poco stock"] ["inactive" "Inactivos / agotados"]]]
              [:option {:value v :selected (= v (or status ""))} l])]]
          (products-fragment data)))

;; ---------------------------------------------------------------------------
;; Audit
;; ---------------------------------------------------------------------------

(def ^:private attr-label
  {:product/price-cents "precio" :product/stock "stock" :product/active? "activo" :product/name "nombre"
   :product/category "categoría" :product/description "descripción" :product/sku "sku"})

(def ^:private lima
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm") (.withZone (ZoneId/of "America/Lima"))))

(defn- fmt-at [^java.util.Date d] (.format ^DateTimeFormatter lima (.toInstant d)))

(defn- fmt-val [attr v]
  (cond (nil? v)                       "–"
        (= attr :product/price-cents)  (money v)
        (boolean? v)                   (if v "sí" "no")
        :else                          (str v)))

(defn changes-table [events]
  [:table
   [:thead [:tr (for [c ["Fecha (Lima)" "Quién" "Producto" "Cambio" "Nota"]] [:th c])]]
   [:tbody
    (for [e events, c (:changes e)]
      [:tr
       [:td (fmt-at (:at e))]
       [:td (or (:user e) [:span.muted "sistema / importación Excel"])]
       [:td.mono (:sku c)]
       [:td (attr-label (:attr c)) ": " (fmt-val (:attr c) (:old c)) " → " (fmt-val (:attr c) (:new c))]
       [:td.muted (:note e)]])]])

(defn audit-page [ctx events]
  (layout (assoc ctx :title "Historial")
          [:h1 "Últimos cambios"]
          [:p.muted "Cambios hechos desde esta interfaz."]
          (changes-table events)))

(defn history-page [ctx sku events]
  (layout (assoc ctx :title (str "Historial " sku))
          [:p [:a {:href "/products"} "← Productos"]]
          [:h1 "Historial de " [:span.mono sku]]
          (changes-table events)))

;; ---------------------------------------------------------------------------
;; Import / export
;; ---------------------------------------------------------------------------

(defn data-page [ctx & [message]]
  (layout (assoc ctx :title "Importar / Exportar")
          [:h1 "Importar / Exportar"]
          (when message [:p.error message])
          [:section
           [:h2 "Exportar productos"]
           [:p "Descarga el catálogo en CSV para abrirlo en Excel. Si Excel junta todo en una sola columna, usa la versión con punto y coma."]
           [:a.button {:href "/export/products.csv?sep=,"} "CSV (coma)"] " "
           [:a.button {:href "/export/products.csv?sep=;"} "CSV (punto y coma)"]]
          [:section
           [:h2 "Importar desde CSV"]
           [:p "Columnas: " [:code "sku"] " (obligatoria), " [:code "price"] ", " [:code "stock"]
            ". Opcionales: " [:code "name"] ", " [:code "category"] ", " [:code "description"] ", " [:code "active"]
            ". Los SKU nuevos necesitan " [:code "name"] " y " [:code "price"]
            ". Verás una vista previa antes de aplicar nada."]
           [:form {:method "post" :action "/import" :enctype "multipart/form-data"}
            (csrf-field)
            [:input {:type "file" :name "file" :accept ".csv,text/csv" :required true}] " "
            [:button "Ver vista previa"]]]))

(def ^:private status-label {:update "actualizar" :create "nuevo" :error "ERROR"})

(defn import-preview-page [ctx filename {:keys [fatal items counts]} token]
  (let [errors (:error counts 0)
        todo   (+ (:update counts 0) (:create counts 0))]
    (layout (assoc ctx :title "Vista previa")
            [:h1 "Vista previa de importación"]
            [:p.muted filename]
            (if fatal
              [:p.error fatal]
              [:div
               [:p (str (:update counts 0) " actualizaciones · " (:create counts 0) " nuevos · "
                        (:unchanged counts 0) " sin cambios · " errors " con errores")]
               [:table
                [:thead [:tr (for [c ["Fila" "SKU" "Acción" "Detalle"]] [:th c])]]
                [:tbody
                 (for [i (take 300 (remove #(= :unchanged (:status %)) items))]
                   [:tr {:class (when (= :error (:status i)) "error")}
                    [:td (:line i)] [:td.mono (:sku i)] [:td (status-label (:status i))]
                    [:td (str/join "; " (or (:errors i) (:diff i)))]])]]
               (cond
                 (pos? errors) [:p.error "Corrige las filas con error y sube el archivo otra vez. No se aplicó nada."]
                 (zero? todo)  [:p "No hay cambios que aplicar."]
                 :else
                 [:form {:method "post" :action "/import/confirm"}
                  (csrf-field)
                  [:input {:type "hidden" :name "token" :value token}]
                  [:button (str "Aplicar " todo " cambios")] " "
                  [:a {:href "/data"} "Cancelar"]])]))))

(defn import-result-page [ctx {:keys [ok error]}]
  (layout (assoc ctx :title "Importación")
          [:h1 "Importación"]
          (if error
            [:p.error error]
            [:p (str "Listo: " ok " productos actualizados o creados.")])
          [:p [:a {:href "/products"} "Ver productos"] " · " [:a {:href "/audit"} "Ver historial"]]))
