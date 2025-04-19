(ns pg-realtime.core
  "A library for subscribing to PostgreSQL queries and getting auto-updating atoms."
  (:require [pg.core :as pg]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.core.async :refer [chan go-loop <! >! put! close! timeout alts!]]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [pg-realtime.utils :refer [def-sql render-sql hash-results table->kw table-kw->str op->kw val->str-or-nil]]
            [pg-realtime.throttle :refer [create-throttler]]))

(def-sql trigger-sql "create_trigger.sql")
(def-sql parse-query-sql "parse_query.sql")

(defn- parse-query
  "Parse a SQL query to determine the tables and columns it uses."
  [conn query]
  (let [result (pg/execute conn "SELECT * FROM _pg_realtime_parse_query($1)" {:params [(str/replace query #"\$\d+" "NULL")]})

        tables (->> result
                    (filter #(= "table" (:object_type %)))
                    (map #(-> % :tname table->kw))
                    set)

        columns (->> result
                     (filter #(= "column" (:object_type %)))
                     (group-by #(-> % :tname table->kw))
                     (reduce-kv
                      (fn [acc k v]
                        (assoc acc k (into #{} (map #(-> % :cname keyword) v))))
                      {}))]

    {:watched-tables tables
     :watched-columns columns}))

(defn- ensure-table-trigger-exists
  "Make sure a trigger exists on the table to notify on changes."
  [conn table]
  (let [schema (if (.contains table ".")
                 (first (str/split table #"\."))
                 "public")
        table-name (if (.contains table ".")
                     (second (str/split table #"\."))
                     table)

        replacements {:qualified_table (if (.contains table ".") table (str schema "." table-name))
                      :table_name (-> table (str/replace #"\." "_"))
                      :channel_name    "_pg_realtime_table_changes"}

        sql-to-run (render-sql trigger-sql replacements)]

    (pg/query conn sql-to-run)))

(defn start-notification-poller
  "Spawns a go‑loop that calls `pg/poll-notifications` every `interval-ms`.
   Returns a channel. Close it to stop polling."
  [listener-conn interval-ms]
  (let [stop-ch (chan)]
    (go-loop []
      (let [[_ ch] (alts! [(timeout interval-ms) stop-ch])]
        (when-not (= ch stop-ch)
          (pg/poll-notifications listener-conn)
          (recur))))
    stop-ch))

(defn- execute-query-and-update
  "Execute a query and update the atom if results have changed."
  [conn query opts result-atom last-hash-atom error-handler]
  (try
    (log/info "Executing query:" query)
    (let [result (pg/execute conn query opts)
          result-hash (hash-results result)]
      (when (not= result-hash @last-hash-atom)
        (reset! result-atom result)
        (reset! last-hash-atom result-hash))
      result)
    (catch Exception e
      (error-handler e query)
      @result-atom)))

(defn should-refresh? [conn result watched-columns refresh? {:keys [table changes row] :as notification-data}]
  (let [changed-col-names (set (keys changes))
        watched-table-cols (get watched-columns table)
        tracked-columns-changed? (some changed-col-names watched-table-cols)]

    (log/info "tracked-columns-changed?: " tracked-columns-changed?)

    (when tracked-columns-changed?
      (if (map? refresh?)
        (if-let [filter-map (get refresh? table)]
          (->> filter-map
               (some (fn [[filter-col filter-val]]
                       (let [filter-set (if (and (keyword? filter-val) (= "result" (namespace filter-val)))
                                          (->> (if (vector? result) result [result])
                                               (map (-> filter-val name keyword)) ; lookup value in result
                                               set)
                                          #{filter-val})        ; value is a literal
                             notification-values (->> (get changes filter-col)
                                                      (into #{(get row filter-col)})
                                                      set)]

                         (log/info "filter-set: " filter-set)
                         (log/info "notification-values: " notification-values)

                         (not-empty (set/intersection filter-set notification-values))))))
          true) ;; map not present in filter, default to tracked-columns

        (when-let [refresh-result (refresh? conn result notification-data)]
          (or (= :tracked-columns refresh-result) refresh-result))))))

(defn decode-notification
  "Given a JSON payload string from pg NOTIFY, parse it and
   decode each column in via the pg driver using the OID."
  [msg]
  (let [{:keys [table operation row hashed old_values error] :as payload}
        (json/read-value msg json/keyword-keys-object-mapper)]

    (when error
      (throw (ex-info "Error in notification trigger" {:error error :payload payload})))

    (let [table-kw (table->kw table)
          op-kw (op->kw operation)

          decode-cell
          (fn [{:keys [value oid]}]
            (when value
              (pg.core/decode-txt value (Integer/parseInt oid))))

          decode-map
          (fn [m]
            (->> m
                 (map (fn [[col cell]]
                        [(if (keyword? col) col (keyword col))
                         (decode-cell cell)]))
                 (into {})))

          decoded-row (decode-map row)

          decoded-old-values (decode-map old_values)

          changes (case op-kw
                    :update (reduce-kv (fn [m k v] (assoc m k [v (get decoded-row k)])) {} decoded-old-values)
                    :insert (reduce-kv (fn [m k v] (assoc m k [nil v])) {} decoded-row)
                    :delete (reduce-kv (fn [m k v] (assoc m k [v nil])) {} decoded-row))]

      (cond-> {:table      table-kw
               :operation  op-kw
               :row        decoded-row
               :changes    changes
               :hashed     (set hashed)
               :error      error}))))

;; System state
(defonce ^:private system-state (atom nil))

;; Public API
(defn start!
  "Initialize pg-realtime system. Must be called before using sub.
  Parameters:
    - db-config: Database connection configuration map (see pg2 doc for details).
      The user must have permission to create triggers and functions.
    - opts: Options map containing:
      - :error-handler - Function to handle errors during notification processing.
                         Takes the error as argument. Defaults to logging the error.
      - :notification-buffer - core.async buffer or size of the notification channel buffer (default: 100).
      - :notification-polling-interval-ms - Interval in milliseconds for polling notifications (default: 200)."
  [db-config {:keys [notification-buffer notification-polling-interval-ms error-handler]
              :or {notification-buffer 100
                   notification-polling-interval-ms 200
                   error-handler (fn [e] (log/error e "Error in notification handler"))}}]

  (when-not @system-state
    (let [subscriptions (atom {})       ; ID -> subscription data
          notification-ch (chan notification-buffer)

          sys-conn (pg/connect
                    (assoc db-config
                           :fn-notification (fn [notification]
                                              (put! notification-ch notification))))

          notification-poller (start-notification-poller sys-conn notification-polling-interval-ms)]

      (pg/query sys-conn "CREATE EXTENSION IF NOT EXISTS pgcrypto;")

      (pg/query sys-conn parse-query-sql)

      ;; Main notification processing loop
      (go-loop []
        (when-let [notification (<! notification-ch)]
          (try
            (let [{:keys [table] :as payload} (decode-notification (:message notification))]
              (doseq [[_sub-id {:keys [conn result-atom watched-tables watched-columns refresh? throttler]}] @subscriptions]
                (when (and (contains? watched-tables table)
                           (should-refresh? conn @result-atom watched-columns refresh? payload))
                  (>! throttler :no-args))))
            (catch Exception e
              (error-handler e)))
          (recur)))

      (pg/listen sys-conn "_pg_realtime_table_changes")

      (reset! system-state {:sys-conn sys-conn
                            :subscriptions subscriptions
                            :notification-ch notification-ch
                            :notification-poller notification-poller})

      true)))

(defn shutdown!
  "Shutdown the real-time query system and clean up resources."
  []
  (when-let [{:keys [subscriptions notification-ch sys-conn notification-poller]} @system-state]
    (doseq [[_ {:keys [throttler]}] @subscriptions]
      (close! throttler))

    (close! notification-poller)
    (close! notification-ch)
    (pg/close sys-conn)
    (reset! system-state nil)

    true))

(defn sub
  "Subscribe to a query and return an atom that updates when data changes.
   This will install triggers on the tables used in the query to listen for changes.

   Use `unsub` to stop the subscription and free up resources (which will stop refreshing the query).

   Provide just an `id` to return the atom of an existing subscription.

   Parameters:
   - id: Unique identifier for this subscription.
         Use meaningful/stable names rather than random ones, e.g. :all-users, \"user-123-orders\".
         If a sub with this id already exists, the previous one will be automatically removed.
   - conn: Database connection or pool used to execute the query
   - query: SQL query string, see pg2/execute for details.
   - opts: Options map containing:
    - :throttle-ms - Ensure the query is refreshed at most once every `throttle-ms` milliseconds.
                     This prevents excessive updates on high-traffic tables.
                     Defaults to 500ms.

    - :error-handler - Function to handle errors during query execution.
                       It takes the error and the query as arguments.
                       Defaults to logging the error.

    - :refresh? - Customise when to refresh the query. Can be a map or a function.

                  When not provided, it defaults to a function that checks if any of the columns used
                  in the query have been changed (which is always true for :insert/:delete),
                  which is sufficient for most cases.

                   When using a map, it should be in the format:
                   {:table-name {:column-name value}}
                   Note that the values are coerced to the type OID of the column.

                   To look up a value in the existing result:
                   {:table-name {:column-name :result/column-name}}

                   When using a function, provide a fn that takes 3 args:
                   `conn`, `result` and `notification-data`
                   and returns boolean indicating whether to refresh.

                  `conn` is the database connection used to execute the query.
                  `result` is the current result of the query.
                  `notification-data` is a map with keys:
                    - :operation - The operation that triggered the notification,
                      e.g. :insert, :update, :delete
                    - :table - The table that was changed, e.g. :users.
                      If the schema is not public, it will be in the format :schema/table.
                      For partitioned tables, this is always the parent table.
                    - :row - The row that was changed, in the format:
                      {:column-name value}
                      The values are coerced to the type OID of the column.
                    - :changes - A map of changed columns and their old and new values.
                      The map is in the format:
                      {:column-name [old-value new-value]}
                      For :insert operations, old-value is nil. For :delete, new-value is nil.
                    - :hashed - A set of columns that were hashed in the notification
                      (because of payload size limits).

                   You can also return :tracked-columns instead of a boolean to fall back to
                   the default behavior for a specific table.

    - Any additional options are passed to pg2/execute"
  ([id]
   (when-not @system-state
     (throw (IllegalStateException. "System not initialized. Call start! first.")))
   (-> @system-state :subscriptions deref (get id) :result-atom))
  ([id conn query]
   (sub id conn query {}))
  ([id conn query {:keys [throttle-ms error-handler refresh?]
                   :or {throttle-ms 500
                        refresh? (fn [_conn _result _notification-data] :tracked-columns)} :as opts}]
   (when-not @system-state
     (throw (IllegalStateException. "System not initialized. Call start! first.")))

   (let [{:keys [sys-conn subscriptions]} @system-state
         query-opts (dissoc opts :id :throttle-ms :refresh?)

         {:keys [watched-tables watched-columns]} (parse-query sys-conn query)

         error-handler  (or error-handler
                            (fn [e _]
                              (log/error e "Error executing query:" query)))

         {existing-result-atom :result-atom
          existing-last-hash-atom :last-hash-atom
          existing-throttler :throttler} (get @subscriptions id)

         result-atom (or existing-result-atom (atom nil))
         last-hash-atom (or existing-last-hash-atom (atom nil))

         throttler (create-throttler
                    #(execute-query-and-update conn query query-opts result-atom last-hash-atom error-handler)
                    throttle-ms)]

     (when existing-throttler
       (close! existing-throttler))

     (doseq [table watched-tables]
       (ensure-table-trigger-exists sys-conn (table-kw->str table)))

    ;; 1st exec of the query
     (execute-query-and-update conn query query-opts result-atom last-hash-atom error-handler)

     (swap! subscriptions assoc id
            {:result-atom result-atom
             :last-hash-atom last-hash-atom
             :conn conn
             :sys-conn sys-conn
             :query query
             :opts query-opts
             :watched-tables watched-tables
             :watched-columns watched-columns
             :refresh? refresh?
             :throttler throttler})

     result-atom)))

(defn unsub
  "Unsubscribe from a real-time query subscription & clean up resources.
   This does not remove any installed triggers."
  [id]
  (when-not @system-state
    (throw (IllegalStateException. "System not initialized.")))

  (let [{:keys [subscriptions]} @system-state]
    (when-let [{:keys [throttler]} (get @subscriptions id)]
      (close! throttler)
      (swap! subscriptions dissoc id)

      true)))

(defn destroy-pg-realtime-objects!
  "Cleans up all installed triggers and functions that start with _pg_realtime_."
  [conn]
  (when @system-state
    (throw (IllegalStateException. "System is still running. Call shutdown! first.")))

  (let [trigger-sql "SELECT t.tgname AS trigger_name, c.relname AS table_name
                       FROM pg_trigger t
                       JOIN pg_class c ON t.tgrelid = c.oid
                       LEFT JOIN pg_inherits i ON c.oid = i.inhrelid
                       WHERE i.inhrelid IS NULL
                       AND t.tgname LIKE '_pg_realtime_%';"
        triggers (pg/execute conn trigger-sql)]
    (doseq [{:keys [trigger_name table_name]} triggers]
      (let [drop-trigger-sql (format "DROP TRIGGER IF EXISTS \"%s\" ON \"%s\";" trigger_name table_name)]
        (log/info "Dropping trigger:" trigger_name "on table:" table_name)
        (pg/query conn drop-trigger-sql))))

  (let [function-sql "SELECT proname AS function_name,
                             pg_get_function_identity_arguments(p.oid) AS args
                      FROM pg_proc p
                      WHERE proname LIKE '_pg_realtime_%';"
        functions (pg/query conn function-sql)]
    (doseq [{:keys [function_name args]} functions]
      (let [drop-func-sql (if (or (nil? args) (clojure.string/blank? args))
                            (format "DROP FUNCTION IF EXISTS \"%s\"();" function_name)
                            (format "DROP FUNCTION IF EXISTS \"%s\"(%s);" function_name args))]
        (log/info "Dropping function:" function_name "with args:" args)
        (pg/query conn drop-func-sql))))
  true)

;; Example usage
(comment
  (def db-config {:host "localhost"
                  :port 5433
                  :user "woolly"
                  :password "woolly"
                  :database "woolly"})

  (start! db-config {})

  (def conn (pg/pool "jdbc:postgresql://localhost:5433/woolly?user=woolly&password=woolly"))

  (def my-atom (sub
                conn
                ::chat-msgs
                "select c.id as chan_id, c.name as chan_name, u.id as user_id, m.id, u.display_name as user_name, m.content, m.created_at
                 from msgs m
                 inner join users u on m.sender_id = u.id
                 inner join chans c on m.chan_id = c.id
                 where c.id = '0195b063-a51a-7f13-a7ba-600ed657bf57'"
                {:refresh? {:chans {:id :result/chan_id}
                            :users {:id :result/user_id}
                            :msgs {:chan_id :result/chan_id}}}))

  (add-watch my-atom :watcher1
             (fn [key _atom old-state new-state]
               (println "-- Atom Changed --")
               (println "key:" key)
               (println "old-state:" old-state)
               (println "new-state:" new-state)))

  (pg/execute conn "update msgs set content = 'teffstasdsadqweddss' where id = '01961b27-ff7a-7521-8f38-2cfbac4d386d'")
  (pg/execute conn "update orgs set name = 'test' where id = '0196360e-87b5-7788-a69e-f88dc65b18ad'")

  (pg/execute conn "insert into orgs (name) values ('test7') ")

  (unsub ::chat-msgs)

  (def users-atom (sub conn
                       ::all-users
                       "SELECT id, email, display_name FROM users"))

  ;; Add a watch to react to changes
  (add-watch users-atom ::all-users-atom
             (fn [_ _ _old-state new-state]
               (println "Users data changed: " new-state)))

  (pg/execute conn "update users set display_name = 'johnasd' where id = '0195b061-8c12-7329-873e-67ab19b6e2e3'")

  (pg/execute conn "update users set avatar_url = 'asdasd' where id = '0195b061-8c12-7329-873e-67ab19b6e2e3'")

  (shutdown!)

  (destroy-pg-realtime-objects! db-config))
