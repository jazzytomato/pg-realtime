(ns pg-realtime.throttle-test
  (:require [clojure.test :refer :all]
            [pg-realtime.fixtures :as tf]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [pg.core :as pg]
            [pg-realtime.core :as sut]))

(use-fixtures :once tf/db-fixture)

(deftest throttling-test
  (pg/execute tf/*db-conn* "CREATE TABLE counter (id SERIAL PRIMARY KEY, value INTEGER);")
  (pg/execute tf/*db-conn* "INSERT INTO counter (value) VALUES (0);")

  (sut/start! tf/*db-config* {})

  (testing "Default throttling behavior"
    (let [update-count (atom 0)
          counter-atom (sut/sub ::counter tf/*db-conn* "SELECT * FROM counter")]

      (add-watch counter-atom ::test-watch
                 (fn [_ _ old-val new-val]
                   (when (not= old-val new-val)
                     (swap! update-count inc))))

      (dotimes [i 10]
        (pg/execute tf/*db-conn* "UPDATE counter SET value = $1" {:params [(inc i)]})
        (Thread/sleep 51)) ;; will take > 510ms, with default 500ms throttle

      (tf/wait-for-condition #(= 10 (:value (first @counter-atom)))
                             :message "Counter didn't reach expected value"
                             :timeout-ms 2000)

      (is (= @update-count 2) "Should have updated twice with default throttle")

      (remove-watch counter-atom ::test-watch)
      (sut/unsub ::counter)))

  (testing "Custom throttle time"
    (pg/execute tf/*db-conn* "UPDATE counter SET value = 0") ;; Reset counter

    (let [update-count (atom 0)
          counter-atom (sut/sub ::slow-counter
                                tf/*db-conn*
                                "SELECT * FROM counter"
                                {:throttle-ms 1000})] ;; Long throttle time

      (add-watch counter-atom ::test-watch
                 (fn [_ _ old-val new-val]
                   (when (not= old-val new-val)
                     (swap! update-count inc))))

      ;; rapidly update the counter 5 times
      (dotimes [i 5]
        (pg/execute tf/*db-conn* "UPDATE counter SET value = $1" {:params [(inc i)]}))

      (tf/wait-for-condition #(= 5 (:value (first @counter-atom)))
                             :message "Counter didn't reach expected value"
                             :timeout-ms 2000)

      (is (= @update-count 1) "Should have 1 update with long throttle")

      (remove-watch counter-atom ::test-watch)
      (sut/unsub ::slow-counter)))

  (sut/shutdown!))