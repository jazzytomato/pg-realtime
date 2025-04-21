(ns pg-realtime.utils
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(defn hash-results
  "Create a hash of query results to detect changes."
  [results]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.getBytes (str results) "UTF-8")]
    (.update digest bytes)
    (let [hash-bytes (.digest digest)]
      (apply str (map #(format "%02x" (bit-and % 0xff)) hash-bytes)))))

(defn table->kw [table]
  (let [table (str/replace table #"public\." "")]
    (if (.contains table ".")
      (keyword (first (str/split table #"\.")) (second (str/split table #"\.")))
      (keyword table))))

(defn table-kw->str [table]
  (if-let [table-schema (namespace table)]
    (str table-schema "." (name table))
    (name table)))

(defn op->kw [op]
  (-> op (str/lower-case) keyword))

(defmacro def-sql
  "Defines a var whose value is the contents of a SQL file located in resources/sql."
  {:clj-kondo/lint-as 'clojure.core/def}
  [name filename]
  `(def ~name (slurp (io/resource (str "sql/" ~filename)))))

(defn render-sql
  "Replaces placeholders in the SQL template with provided values.
   Placeholders are designated as TEMPLATE_key and replaced with corresponding string values."
  [sql-template replacements]
  (reduce (fn [acc [k v]]
            (str/replace acc (re-pattern (str "TEMPLATE_" (name k))) (str v)))
          sql-template
          replacements))