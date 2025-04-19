;; clojure
(ns pg-realtime.core-test
  (:require [clojure.test :refer [deftest is]]
            [pg-realtime.core :as sut]))

(def default-refresh-fn (fn [_conn _result _notification-data] :tracked-columns))

;; ----------------------------
;; Default refresh
;; ----------------------------

(deftest should-refresh-when-tracked-columns-are-changed-inserts
  (let [conn nil
        result [{:id 1 :name "Maddy" :status "active"}]
        watched-columns {:users #{:id :name :status} :orders #{:id :status}}
        refresh? default-refresh-fn
        notification-data {:table :users
                           :operation :insert
                           :changes {:id [nil 2]
                                     :name [nil "Seb"]
                                     :status [nil "active"]}
                           :row {:id 2 :name "Seb" :status "active"}}]
    (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

(deftest should-refresh-when-tracked-columns-are-changed-deletes
  (let [conn nil
        result [{:id 1 :name "Maddy" :status "active"}]
        watched-columns {:users #{:id :name :status} :orders #{:id :status}}
        refresh? default-refresh-fn
        notification-data {:table :users
                           :operation :delete
                           :changes {:id [1 nil]
                                     :name ["Maddy" nil]
                                     :status ["active" nil]}
                           :row {:id 1 :name "Maddy" :status "active"}}]
    (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

(deftest should-refresh-when-tracked-columns-are-changed-updates
  (let [conn nil
        result [{:id 1 :name "Maddy" :status "active"}]
        watched-columns {:users #{:id :name :status} :orders #{:id :status}}
        refresh? default-refresh-fn
        notification-data {:table :users
                           :operation :delete
                           :changes {:status ["active" "inactive"]}
                           :row {:id 1 :name "Maddy" :status "inactive"}}]
    (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

(deftest should-not-refresh-when-no-tracked-columns-are-changed
  (let [conn nil
        result [{:id 1 :name "Maddy" :status "active"}]
        watched-columns {:users #{:id :name :status} :orders #{:id :status}}
        refresh? default-refresh-fn
        notification-data {:table :users
                           :operation :update
                           :changes {:age [25 26]}
                           :row {:id 1 :name "Maddy" :status "active" :age 26}}]
    (is (not (sut/should-refresh? conn result watched-columns refresh? notification-data)))))

;; ----------------------------
;; Refresh with literals
;; ----------------------------

(deftest should-refresh-when-refresh-map-matches-row-literal-value
  (let [conn nil
        result [{:id 1 :name "Maddy"}]
        watched-columns {:users #{:id :name}}
        refresh? {:users {:id 1}}
        notification-data {:table :users
                           :operation :update
                           :changes {:name ["Maddy" "Madeline"]}
                           :row {:id 1 :name "Madeline"}}]
    (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

(deftest should-refresh-when-refresh-map-matches-row-literal-nil
  (let [conn nil
        result [{:id 1 :name "Maddy" :status nil}]
        watched-columns {:users #{:id :name :status}}
        refresh? {:users {:status nil}}
        notification-data {:table :users
                           :operation :update
                           :changes {:name ["Maddy" "Madeline"]}
                           :row {:id 1 :name "Madeline" :status nil}}]
    (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

(deftest should-not-refresh-when-refresh-map-doesnt-matches-row-literal
  (let [conn nil
        result [{:id 1 :name "Maddy"}]
        watched-columns {:users #{:id :name}}
        refresh? {:users {:id 1}}
        notification-data {:table :users
                           :operation :update
                           :changes {:name ["Sebastian" "Seb"]}
                           :row {:id 2 :name "Seb"}}]
    (is (not (sut/should-refresh? conn result watched-columns refresh? notification-data)))))

(deftest should-not-refresh-when-refresh-map-matches-row-literal-value-no-tracked-columns
  (let [conn nil
        result [{:id 1 :name "Maddy"}]
        watched-columns {:users #{:id :name}}
        refresh? {:users {:id 1}}
        notification-data {:table :users
                           :operation :update
                           :changes {:status ["active" "inactive"]}
                           :row {:id 1 :name "Madeline" :status "inactive"}}]
    (is (not (sut/should-refresh? conn result watched-columns refresh? notification-data)))))

;; -----------------------------
;; Refresh with lookup in result
;; -----------------------------

(deftest should-refresh-when-refresh-map-matches-row-result-lookup-value
  (let [conn nil
        result [{:id 1 :name "Maddy"}]
        watched-columns {:users #{:id :name}}
        refresh? {:users {:id :result/id}}
        notification-data {:table :users
                           :changes {:name ["Madeline" "Maddy"]}
                           :row {:id 1 :name "Maddy"}}]
    (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

(deftest should-refresh-when-refresh-map-matches-row-inserts
  (let [conn nil
        result [{:id 1 :name "Maddy" :group_id 1 :group_name "Group A"}]
        watched-columns {:users #{:id :name :group_id} :groups #{:id :name}}
        refresh? {:users {:group_id :result/group_id}}
        notification-data {:table :users
                           :operation :insert
                           :changes {:id [nil 2]
                                     :name [nil "Seb"]
                                     :group_id [nil 1]}
                           :row {:id 2 :name "Seb" :group_id 1}}]
    (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))

(deftest should-not-refresh-when-refresh-map-is-not-in-the-result-set
  (let [conn nil
        result []
        watched-columns {:users #{:id :name :group_id} :groups #{:id :name}}
        refresh? {:users {:group_id :result/group_id}}
        notification-data {:table :users
                           :operation :insert
                           :changes {:id [nil 2]
                                     :name [nil "Seb"]
                                     :group_id [nil 1]}
                           :row {:id 2 :name "Seb" :group_id 1}}]
    (is (not (sut/should-refresh? conn result watched-columns refresh? notification-data)))))

;; --------------------------------
;; Refresh with table not specified
;; --------------------------------

(deftest should-refresh-when-refresh-map-does-not-specify-table
  (let [conn nil
        result [{:id 1 :name "Maddy" :order_status "pending"}]
        watched-columns {:users #{:id :name}
                         :orders #{:id :status}}
        refresh? {:orders {:id 1}}
        notification-data {:table :users
                           :operation :update
                           :changes {:name ["Maddy" "Madeline"]}
                           :row {:id 1 :name "Madeline"}}]
    (is (sut/should-refresh? conn result watched-columns refresh? notification-data))))