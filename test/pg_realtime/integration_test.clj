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

      ;; cleanup
      (sut/unsub ::users)
      (sut/shutdown!))))
