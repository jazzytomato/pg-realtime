(ns pg-realtime.utils-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [pg-realtime.utils :as sut])
  (:import (java.io StringReader)))

(deftest def-sql-macro-test
  (let [sql-content "SELECT * FROM users;"
        resource-fn (fn [_] (StringReader. sql-content))]
    (with-redefs [io/resource resource-fn]
      (sut/def-sql my-query "query.sql")
      (is (= sql-content my-query)))))

(deftest render-sql-test
  (testing "replaces a single placeholder"
    (let [template "SELECT * FROM users WHERE id = TEMPLATE_id;"
          result   (sut/render-sql template {:id 123})]
      (is (= result "SELECT * FROM users WHERE id = 123;"))))

  (testing "replaces multiple placeholders and repeated occurrences"
    (let [template "INSERT INTO orders (user_id, status) VALUES (TEMPLATE_user, 'TEMPLATE_status'); SELECT * FROM orders WHERE status = 'TEMPLATE_status';"
          result   (sut/render-sql template {:user 42 :status "pending"})]
      (is (= result "INSERT INTO orders (user_id, status) VALUES (42, 'pending'); SELECT * FROM orders WHERE status = 'pending';"))))

  (testing "returns original SQL when no placeholders match"
    (let [template "SELECT 1;"
          result   (sut/render-sql template {:foo "bar"})]
      (is (= result "SELECT 1;"))))

  (testing "handles non-string replacement values"
    (let [template "SELECT * FROM products WHERE available = TEMPLATE_available;"
          result   (sut/render-sql template {:available true})]
      (is (= result "SELECT * FROM products WHERE available = true;")))))
