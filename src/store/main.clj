(ns store.main
  "Terminal entry point:  clj -M -m store.main
   Starts the admin UI (port 8080) and the tool API (port 9090) in ONE JVM,
   because Datomic Local runs in-process and only one JVM may open a given
   storage directory."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [store.admin.handler :as admin]
            [store.tool-api :as tool-api])
  (:gen-class))

(defn- env
  ([k] (let [v (System/getenv k)]
         (when (str/blank? v)
           (throw (ex-info (str "Missing environment variable " k) {:var k})))
         v))
  ([k default] (let [v (System/getenv k)] (if (str/blank? v) default v))))

(defn start!
  "Connects and starts both servers. Returns {:conn :admin :tool-api}."
  [{:keys [storage-dir system db-name session-key-hex tool-token
           admin-port tool-port secure-cookie?]}]
  (let [client (d/client {:server-type :datomic-local
                          :system      system
                          :storage-dir storage-dir})
        _      (d/create-database client {:db-name db-name})   ; no-op if it already exists
        conn   (d/connect client {:db-name db-name})
        adm    (admin/start-admin! conn {:port admin-port
                                         :session-key-hex session-key-hex
                                         :secure-cookie? secure-cookie?})
        tool   (tool-api/start-tool-api! conn {:port tool-port :token tool-token})]
    (println (str "Admin UI  : http://localhost:" admin-port))
    (println (str "Tool API  : http://localhost:" tool-port "  (internal only)"))
    {:conn conn :admin adm :tool-api tool}))

(defn -main [& _]
  (try
    (let [servers (start! {:storage-dir     (env "DATOMIC_STORAGE_DIR")
                           :system          (env "DATOMIC_SYSTEM" "store-dev")
                           :db-name         (env "DATOMIC_DB" "store")
                           :session-key-hex (env "ADMIN_SESSION_KEY")
                           :tool-token      (env "TOOL_API_TOKEN")
                           :admin-port      (parse-long (env "ADMIN_PORT" "8080"))
                           :tool-port       (parse-long (env "TOOL_API_PORT" "9090"))
                           ;; set SECURE_COOKIE=false ONLY for plain-http local development
                           :secure-cookie?  (not= "false" (env "SECURE_COOKIE" "true"))})]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (some-> (:admin servers) .stop)
                                   (some-> (:tool-api servers) .stop)))))
    ;; Jetty's threads keep the JVM alive; nothing else to do here.
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println "Cannot start:" (ex-message e)))
      (System/exit 1))))
