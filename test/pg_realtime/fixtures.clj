(ns pg-realtime.fixtures
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [pg.core :as pg])
  (:import (org.testcontainers.containers PostgreSQLContainer)))

(def ^:dynamic *db-container* nil)
(def ^:dynamic *db-conn* nil)
(def ^:dynamic *db-config* nil)

(defn wait-for-condition
  "Wait for condition to be true, checking every interval-ms.
   Throws an exception if timeout-ms is reached."
  [condition & {:keys [timeout-ms interval-ms message]
                :or {timeout-ms 5000
                     interval-ms 100
                     message "Timed out waiting for condition"}}]
  (let [start-time (System/currentTimeMillis)]
    (loop []
      (if (condition)
        true
        (if (< (- (System/currentTimeMillis) start-time) timeout-ms)
          (do
            (Thread/sleep interval-ms)
            (recur))
          (throw (Exception. message)))))))

(defn db-fixture [f]
  (let [container (-> (PostgreSQLContainer. "postgres:14")
                      (.withDatabaseName "test")
                      (.withUsername "test")
                      (.withPassword "test"))]
    (try
      (.start container)
      (let [db-config {:host (.getHost container)
                       :port (.getMappedPort container 5432)
                       :user (.getUsername container)
                       :password (.getPassword container)
                       :database (.getDatabaseName container)}]
        (binding [*db-container* container
                  *db-config* db-config
                  *db-conn* (pg/connect db-config)]
          (f)))
      (finally
        (.stop container)))))

(defn reset-db-fixture [f]
  (when *db-conn*
    (pg/execute *db-conn* "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"))
  (f))

(use-fixtures :once db-fixture)

;; verify container setup
(deftest testcontainer-setup-test
  (testing "Testcontainer setup is working"
    (is *db-container* "Container is set up")
    (is *db-conn* "Connection is established")
    (is *db-config* "DB config is available")

    (let [result (pg/execute *db-conn* "SELECT 1 as one")]
      (is (= 1 (count result)))
      (is (= 1 (:one (first result)))))))