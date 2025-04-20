(ns pg-realtime.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jsonista.core :as json]
            [pg-realtime.fixtures :as tf]
            [pg-realtime.core :as sut]
            [pg.core :as pg])
  (:import (java.time LocalDate LocalDateTime LocalTime OffsetDateTime)))

(def default-refresh-fn
  (fn [_conn _result _notification-data]
    :tracked-columns))

;; -------------------------------------------------------------------
;; Default refresh behavior
;; -------------------------------------------------------------------
(deftest default-refresh-behavior
  (let [conn nil
        result [{:id 1 :name "Maddy" :status "active"}]
        watched-columns {:users #{:id :name :status}
                         :orders #{:id :status}}
        refresh? default-refresh-fn]
    (testing "insert operations on tracked columns trigger refresh"
      (let [notification-data {:table :users
                               :operation :insert
                               :changes {:id     [nil 2]
                                         :name   [nil "Seb"]
                                         :status [nil "active"]}
                               :row     {:id 2 :name "Seb" :status "active"}}]
        (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

    (testing "delete operations on tracked columns trigger refresh"
      (let [notification-data {:table :users
                               :operation :delete
                               :changes {:id     [1 nil]
                                         :name   ["Maddy" nil]
                                         :status ["active" nil]}
                               :row     {:id 1 :name "Maddy" :status "active"}}]
        (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

    (testing "update operations on tracked columns trigger refresh"
      (let [notification-data {:table :users
                               :operation :update
                               :changes {:status ["active" "inactive"]}
                               :row     {:id 1 :name "Maddy" :status "inactive"}}]
        (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

    (testing "no tracked column changes suppress refresh"
      (let [notification-data {:table :users
                               :operation :update
                               :changes {:age [25 26]}
                               :row     {:id 1 :name "Maddy" :status "active" :age 26}}]
        (is (not (sut/should-refresh? conn result watched-columns refresh? notification-data)))))))

;; -------------------------------------------------------------------
;; Refresh with literal-match map
;; -------------------------------------------------------------------
(deftest literal-refresh-map-behavior
  (testing "matches literal value when tracked column changes"
    (let [conn nil
          result [{:id 1 :name "Maddy"}]
          watched-columns {:users #{:id :name}}
          refresh? {:users {:id 1}}
          notification-data {:table :users
                             :operation :update
                             :changes {:name ["Maddy" "Madeline"]}
                             :row     {:id 1 :name "Madeline"}}]
      (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

  (testing "matches literal nil when tracked column changes"
    (let [conn nil
          result [{:id 1 :name "Maddy" :status nil}]
          watched-columns {:users #{:id :name :status}}
          refresh? {:users {:status nil}}
          notification-data {:table :users
                             :operation :update
                             :changes {:name ["Maddy" "Madeline"]}
                             :row     {:id 1 :name "Madeline" :status nil}}]
      (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

  (testing "literal mismatch prevents refresh even if tracked column changes"
    (let [conn nil
          result [{:id 1 :name "Maddy"}]
          watched-columns {:users #{:id :name}}
          refresh? {:users {:id 1}}
          notification-data {:table :users
                             :operation :update
                             :changes {:name ["Sebastian" "Seb"]}
                             :row     {:id 2 :name "Seb"}}]
      (is (not (sut/should-refresh? conn result watched-columns refresh? notification-data)))))

  (testing "literal-match but no tracked columns changed suppresses refresh"
    (let [conn nil
          result [{:id 1 :name "Maddy"}]
          watched-columns {:users #{:id :name}}
          refresh? {:users {:id 1}}
          notification-data {:table :users
                             :operation :update
                             :changes {:status ["active" "inactive"]}
                             :row     {:id 1 :name "Maddy" :status "inactive"}}]
      (is (not (sut/should-refresh? conn result watched-columns refresh? notification-data))))))

;; -------------------------------------------------------------------
;; Refresh with result-lookup map
;; -------------------------------------------------------------------
(deftest result-lookup-refresh-map-behavior
  (let [conn nil
        watched-columns {:users #{:id :name :group_id}
                         :groups #{:id :name}}
        result [{:id 1 :name "Maddy" :group_id 1 :group_name "Group A"}]]
    (testing "lookup against static result value"
      (let [refresh? {:users {:id :result/id}}
            notification-data {:table :users
                               :operation :update
                               :changes {:name ["Madeline" "Maddy"]}
                               :row     {:id 1 :name "Maddy"}}]
        (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

    (testing "insert matching lookup triggers refresh"
      (let [refresh? {:users {:group_id :result/group_id}}
            notification-data {:table :users
                               :operation :insert
                               :changes {:id     [nil 2]
                                         :name   [nil "Seb"]
                                         :group_id [nil 1]}
                               :row     {:id 2 :name "Seb" :group_id 1}}]
        (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

    (testing "lookup not found in result suppresses refresh"
      (let [refresh? {:users {:group_id :result/group_id}}
            empty-result []
            notification-data {:table :users
                               :operation :insert
                               :changes {:id     [nil 2]
                                         :name   [nil "Seb"]
                                         :group_id [nil 1]}
                               :row     {:id 2 :name "Seb" :group_id 1}}]
        (is (not (sut/should-refresh? conn empty-result watched-columns refresh? notification-data)))))))

;; -------------------------------------------------------------------
;; Refresh when table not specified in map
;; -------------------------------------------------------------------
(deftest table-unspecified-in-refresh-map
  (let [conn nil
        watched-columns {:users #{:id :name}
                         :orders #{:id :status}}
        result [{:id 1 :name "Maddy" :order_status "pending"}]
        refresh? {:orders {:id 1}}
        notification-data {:table :users
                           :operation :update
                           :changes {:name ["Maddy" "Madeline"]}
                           :row {:id 1 :name "Madeline"}}]
    (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

;; -------------------------------------------------------------------
;; Decode notification tests
;; -------------------------------------------------------------------

(deftest decode-notification-test
  (testing "insert notification decoding"
    (let [notification-data {:table      "public.users"
                             :operation  "INSERT"
                             :row        {"id"   {:value "1"   :oid "23"}
                                          "name" {:value "Alice" :oid "25"}}
                             :hashed     []
                             :old_values nil}
          msg (json/write-value-as-string notification-data)
          result (sut/decode-notification msg)]
      (is (= (:table result) :users))
      (is (= (:operation result) :insert))
      (is (= (:row result) {:id 1 :name "Alice"}))
      (is (= (:changes result)
             {:id   [nil 1]
              :name [nil "Alice"]}))
      (is (= (:hashed result) #{}))))

  (testing "update notification decoding"
    (let [notification-data {:table      "myschema.orders"
                             :operation  "UPDATE"
                             :row        {"id"     {:value "42"      :oid "23"}
                                          "status" {:value "shipped" :oid "25"}}
                             :old_values {"status" {:value "processing" :oid "25"}}
                             :hashed     []}
          msg (json/write-value-as-string notification-data)
          result (sut/decode-notification msg)]
      (is (= (:table result) :myschema/orders))
      (is (= (:operation result) :update))
      (is (= (:row result) {:id 42 :status "shipped"}))
      (is (= (:changes result)
             {:status ["processing" "shipped"]}))
      (is (= (:hashed result) #{}))))

  (testing "delete notification decoding"
    (let [notification-data {:table      "products"
                             :operation  "DELETE"
                             :row        {"id"    {:value "100"  :oid "23"}
                                          "price" {:value "9.99" :oid "1700"}}
                             :hashed     []
                             :old_values nil}
          msg (json/write-value-as-string notification-data)
          result (sut/decode-notification msg)]
      (is (= (:table result) :products))
      (is (= (:operation result) :delete))
      (is (= (:row result) {:id 100 :price 9.99M}))
      (is (= (:changes result)
             {:id    [100 nil]
              :price [9.99M nil]}))
      (is (= (:hashed result) #{}))))

  (testing "error in notification throws"
    (let [notification-data {:table      "any"
                             :operation  "INSERT"
                             :row        {}
                             :hashed     []
                             :error      "something went wrong"}
          msg (json/write-value-as-string notification-data)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Error in notification trigger"
                            (sut/decode-notification msg)))))

  (testing "all supported OIDs decode without error"
    (let [oids [{:oid 21 :val "123"           :expected 123              :description "int2"}
                {:oid 23 :val "456"           :expected 456              :description "int4"}
                {:oid 26 :val "789"           :expected 789              :description "int4"}
                {:oid 20 :val "1024"          :expected 1024             :description "int8"}
                {:oid 1700 :val "101112"      :expected 101112M          :description "numeric"}
                {:oid 700   :val "3.14"       :expected (float 3.14)     :description "float4"}
                {:oid 701   :val "2.71828"    :expected (double 2.71828) :description "float8"}
                {:oid 1043  :val "foo"        :expected "foo"            :description "varchar"}
                {:oid 25    :val "bar baz"    :expected "bar baz"        :description "text"}
                {:oid 19    :val "x"          :expected "x"              :description "name"}
                {:oid 1042  :val "yz"         :expected "yz"             :description "bpchar"}
                {:oid 18    :val "c"          :expected \c               :description "char"}
                {:oid 2950  :val "550e8400-e29b-41d4-a716-446655440000" :expected (parse-uuid "550e8400-e29b-41d4-a716-446655440000") :description "uuid"}
                {:oid 114   :val "{\"a\":1}"  :expected {:a 1}  :description "json"}
                {:oid 3802  :val "{\"b\":2}"  :expected {:b 2}  :description "jsonb"}
                {:oid 16    :val "t"          :expected true    :description "bool"}
                {:oid 1082  :val "2025-04-19" :expected (LocalDate/parse "2025-04-19") :description "date"}
                {:oid 1083  :val "12:34:56"   :expected (LocalTime/parse "12:34:56")   :description "time"}
                {:oid 1114  :val "2025-04-19 13:14:15" :expected (LocalDateTime/parse "2025-04-19T13:14:15") :description "timestamp"}
                {:oid 1184  :val "2025-04-19 14:15:16+00" :expected (OffsetDateTime/parse "2025-04-19T14:15:16Z") :description "timestamptz"}
                {:oid 1005  :val "{1,2,3}"       :expected [1 2 3]          :description "int2[]"}
                {:oid 1007  :val "{4,5,6}"       :expected [4 5 6]          :description "int4[]"}
                {:oid 1016  :val "{7,8,9}"       :expected [7 8 9]          :description "int8[]"}
                {:oid 1231  :val "{10.1,11.2}"   :expected [10.1M 11.2M]    :description "numeric[]"}
                {:oid 1009  :val "{\"a\",\"b\"}" :expected ["a" "b"]        :description "text[]"}]]
      (doseq [{:keys [oid val description expected]} oids]
        (let [notification-data {:table      "t"
                                 :operation  "INSERT"
                                 :row        {"col" {:value val :oid (str oid)}}
                                 :hashed     []
                                 :old_values nil}
              msg (json/write-value-as-string notification-data)
              result (sut/decode-notification msg)]

          (is (= expected (-> result :row :col))
              (str "OID " oid "(" description ") did not decode. Result: " (-> result :row :col)
                   ", Expected: " expected)))))))

;; -------------------------------------------------------------------
;; DB tests
;; -------------------------------------------------------------------

(use-fixtures :once tf/db-fixture)
#_(use-fixtures :each tf/reset-db-fixture)

(deftest query-parsing-test
  (pg/query tf/*db-conn* sut/parse-query-sql)

  (testing "Simple query parsing"
    (pg/execute tf/*db-conn* "CREATE TABLE users (id SERIAL PRIMARY KEY, name TEXT, email TEXT);")

    (let [result (#'sut/parse-query tf/*db-conn* "SELECT * FROM users")]
      (is (= #{:users} (:watched-tables result)))
      (is (contains? (:watched-columns result) :users))))

  (testing "JOIN query parsing"
    (pg/execute tf/*db-conn* "CREATE TABLE orders (id SERIAL PRIMARY KEY, user_id INTEGER, amount DECIMAL);")

    (let [result (#'sut/parse-query tf/*db-conn* "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id")]
      (is (= #{:users :orders} (:watched-tables result)))
      (is (= #{:id :name} (get-in result [:watched-columns :users])))
      (is (= #{:user_id :amount} (get-in result [:watched-columns :orders])))))

  (testing "Schema-qualified table parsing"
    (pg/execute tf/*db-conn* "CREATE SCHEMA test_schema;")
    (pg/execute tf/*db-conn* "CREATE TABLE test_schema.test_table (id SERIAL PRIMARY KEY, data TEXT);")

    (let [result (#'sut/parse-query tf/*db-conn* "SELECT * FROM test_schema.test_table")]
      (is (= #{:test_schema/test_table} (:watched-tables result)))))

  (testing "Subquery parsing"
    (let [result (#'sut/parse-query tf/*db-conn* "SELECT * FROM (SELECT * FROM orders) AS subquery")]
      (is (= #{:orders} (:watched-tables result)))
      (is (= #{:id :user_id :amount} (get-in result [:watched-columns :orders])))))

  (testing "Parameterized query parsing"
    (let [result (#'sut/parse-query tf/*db-conn* "SELECT * FROM users WHERE id = $1 OR email IN ($2) OR COALESCE($3, $4) = $5")]
      (is (= #{:users} (:watched-tables result)))
      (is (= #{:id :name :email} (get-in result [:watched-columns :users]))))))

;; -------------------------------------------------------------------
;; Error handling tests
;; -------------------------------------------------------------------

(deftest error-handling-test
  (pg/execute tf/*db-conn* "CREATE TABLE error_test (id SERIAL PRIMARY KEY, value TEXT);")

  (testing "Query execution error handling"
    (let [error-received (atom nil)
          custom-error-handler (fn [e _]
                                 (reset! error-received e))

          _ (sut/start! tf/*db-config* {})

          _ (sut/sub ::error-test
                     tf/*db-conn*
                     "SELECT * FROM error_test"
                     {:error-handler custom-error-handler})]

      (pg/execute tf/*db-conn* "INSERT INTO error_test (value) VALUES ('valid data')")
      (pg/execute tf/*db-conn* "DROP TABLE error_test;")

      (tf/wait-for-condition (fn [] @error-received)
                             :message "Error handler should have been called")

      (is (re-find #"relation \"error_test\" does not exist" (.getMessage @error-received)))

      (sut/unsub ::error-test)
      (sut/shutdown!)))

  #_(testing "Recovery after errors"
      (pg/execute tf/*db-conn* "CREATE TABLE error_test (id SERIAL PRIMARY KEY, value TEXT);")
      (sut/start! tf/*db-config* {})

      (let [test-atom (sut/sub ::recovery-test
                               tf/*db-conn*
                               "SELECT * FROM error_test")]

      ;; Insert valid data
        (pg/execute tf/*db-conn* "INSERT INTO error_test (value) VALUES ('valid data')")

        (tf/wait-for-condition #(= 1 (count @test-atom))
                               :message "Valid data not received")

      ;; break the table temporarily
        (pg/execute tf/*db-conn* "ALTER TABLE error_test ADD COLUMN temp INTEGER NOT NULL")

        (pg/execute tf/*db-conn* "UPDATE error_test SET value = 'should error'")

        (pg/execute tf/*db-conn* "ALTER TABLE error_test ALTER COLUMN temp DROP NOT NULL")
        (pg/execute tf/*db-conn* "UPDATE error_test SET temp = 0")

        (pg/execute tf/*db-conn* "INSERT INTO error_test (value, temp) VALUES ('recovery data', 1)")

        (tf/wait-for-condition #(= 2 (count @test-atom))
                               :message "System didn't recover after error"
                               :timeout-ms 3000)

        (sut/unsub ::recovery-test)))

  (sut/shutdown!))