(ns pg-realtime.integration-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [pg.core :as pg]
            [pg-realtime.fixtures :as tf]
            [pg-realtime.core :as sut]))

(use-fixtures :once tf/db-fixture)

(deftest basic-subscription-test
  (testing "Basic subscription works with real-time updates"
    ;; Set up a test table
    (pg/execute tf/*db-conn* "CREATE TABLE users (id SERIAL PRIMARY KEY, name TEXT, email TEXT);")
    (pg/execute tf/*db-conn* "INSERT INTO users (name, email) VALUES ($1, $2)"
                {:params ["Test User" "test@example.com"]})

    (sut/start! tf/*db-config* {})

    (let [users-atom (sut/sub ::users tf/*db-conn* "SELECT * FROM users")
          initial-count (count @users-atom)]

      (is (= 1 initial-count))
      (is (= "Test User" (:name (first @users-atom))))

      ;; insert another user to trigger an update
      (pg/execute tf/*db-conn* "INSERT INTO users (name, email) VALUES ($1, $2)"
                  {:params ["Another User" "another@example.com"]})

      (tf/wait-for-condition #(= (inc initial-count) (count @users-atom))
                             :message "Atom didn't update after inserting a new user")

      (is (= 2 (count @users-atom)))
      (is (some #(= "Another User" (:name %)) @users-atom))

      ;; update existing rows work
      (pg/execute tf/*db-conn* "UPDATE users SET name = $1 WHERE email = $2"
                  {:params ["Updated User" "test@example.com"]})

      (tf/wait-for-condition #(some (fn [user] (= "Updated User" (:name user))) @users-atom)
                             :message "Atom didn't update after changing a user's name")

      (pg/execute tf/*db-conn* "DELETE FROM users")
      (tf/wait-for-condition #(= 0 (count @users-atom))
                             :message "Atom didn't update after deleting all users")

      ;; cleanup
      (sut/unsub ::users)
      (sut/shutdown!))))

(deftest complex-join-query-test
  ;; Set up test tables with relationships
  (pg/execute tf/*db-conn* "CREATE SCHEMA myschema;")
  (pg/execute tf/*db-conn* "CREATE TABLE myschema.categories (id SERIAL PRIMARY KEY, name TEXT);")
  (pg/execute tf/*db-conn* "CREATE TABLE myschema.products (id SERIAL PRIMARY KEY, name TEXT, price DECIMAL, category_id INTEGER);")
  (pg/execute tf/*db-conn* "CREATE TABLE myschema.inventory (id SERIAL PRIMARY KEY, product_id INTEGER, quantity INTEGER);")

  ;; Insert test data
  (pg/execute tf/*db-conn* "INSERT INTO myschema.categories (name) VALUES ('Electronics'), ('Books');")
  (pg/execute tf/*db-conn* "INSERT INTO myschema.products (name, price, category_id) VALUES
                     ('Laptop', 1000.00, 1),
                     ('Phone', 500.00, 1),
                     ('Novel', 15.00, 2);")
  (pg/execute tf/*db-conn* "INSERT INTO myschema.inventory (product_id, quantity) VALUES (1, 10), (2, 20), (3, 30);")

  (sut/start! tf/*db-config* {})

  (let [query "SELECT p.id, p.name, p.price, c.id as category_id, c.name as category_name, i.quantity
              FROM myschema.products p
              JOIN myschema.categories c ON p.category_id = c.id
              JOIN myschema.inventory i ON p.id = i.product_id
              WHERE c.name = 'Electronics'"

        products-atom (sut/sub ::electronics
                               tf/*db-conn*
                               query
                               {:refresh? {:products {:category_id :result/category_id}
                                           :inventory {:product_id :result/id}
                                           :categories {:name "Electronics"}}})]

    (is (= 2 (count @products-atom)))
    (is (every? #(= "Electronics" (:category_name %)) @products-atom))

    ;; updates to inventory
    (pg/execute tf/*db-conn* "UPDATE myschema.inventory SET quantity = 15 WHERE product_id = 1")

    (tf/wait-for-condition (fn [] (= 15 (:quantity (first (filter #(= "Laptop" (:name %)) @products-atom)))))
                           :message "Inventory update not reflected")

    ;; update product name
    (pg/execute tf/*db-conn* "UPDATE myschema.products SET name = 'Smartphone' WHERE name = 'Phone'")
    (tf/wait-for-condition #(= "Smartphone" (:name (first @products-atom)))
                           :message "Product name update not reflected")

    ;; moving a product to a different category
    (pg/execute tf/*db-conn* "UPDATE myschema.products SET category_id = 2 WHERE name = 'Smartphone'")

    (tf/wait-for-condition #(= 1 (count @products-atom))
                           :message "Category change not reflected")

    ;; update phone product name again should not refresh the query as it is now in a different category
    (pg/execute tf/*db-conn* "UPDATE myschema.products SET name = 'Smartphone Pro' WHERE name = 'Smartphone'")
    (Thread/sleep 500)
    (is (not (some #(= "Smartphone Pro" (:name %)) @products-atom)))

    ;; adding a new product in the Electronics category
    (pg/execute tf/*db-conn* "INSERT INTO myschema.products (name, price, category_id) VALUES ('Tablet', 300.00, 1)")
    (let [new_product_id (-> (pg/execute tf/*db-conn* "SELECT id FROM myschema.products WHERE name = 'Tablet'" {:first? true})
                             :id)]
      (pg/execute tf/*db-conn* "INSERT INTO myschema.inventory (product_id, quantity) VALUES ($1, 25)" {:params [new_product_id]})

      (tf/wait-for-condition #(= 2 (count @products-atom))
                             :message "New product not added to results")
      (is (some #(= "Tablet" (:name %)) @products-atom)))

    (sut/unsub ::electronics))

  (sut/shutdown!))

