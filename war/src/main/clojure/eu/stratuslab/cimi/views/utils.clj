(ns eu.stratuslab.cimi.views.utils
  "General utilities for dealing with resources in Cassandra."
  (:require [clojure.tools.logging :refer [debug info warn error]]
            [clojure.walk :as walk]
            [clj-hector.core :refer [cluster keyspace]]
            [clj-hector.serialize :as serial])
  (:import [java.util UUID]))

;;
;; Default connection parameters if none are specified
;; explicitly in the function calls.  These are
;; appropriate for the embedded cassandra server.
;;
(def ^:dynamic *cassandra-params* ["stratuslab_cimi_cluster" "localhost" 9960])

(defn update-cassandra-params
  [m]
  (alter-var-root (var *cassandra-params*) (constantly m)))

(defn connect
  "Connect to cassandra server using the given connection
   parameters (a vector with the cluster name [string], the
   host name [string], and the port [integer]).  If the
   parameters are not passed as an argument, then the value
   of the dynamic variable *cassandra-params* is used."
  ([]
     (connect *cassandra-params*))

  ([params]
     (try
       (debug "connecting to cassandra: " params)
       (apply cluster params)
       (catch Exception e
         (error "Connection error: " (.getMessage e))
         nil))))

(defn ks
  "Get a cassandra keyspace for the server defined in the
   configuration."
  ([ks-name]
     (keyspace (connect) ks-name))

  ([params ks-name]
     (keyspace (connect params) ks-name)))

(defn create-uuid
  "Provides a randomized UUID as a string."
  []
  (str (UUID/randomUUID)))

(defn ghost?
  "Determine if the given row is a ghost.  That is, has no data
  associated with it.  A row is a map with a single key (the
  row identifier) and a map for the value."
  [row]
  (let [columns (first (vals row))
        values (vals columns)]
    (every? nil? values)))

(defn create-value-serializer-function
  "Creates a value serializer function from a map.  The map should
  contain the column keyword as a key with the serializer keyword as
  the value.  The serializer given with the :default key will be used
  for any column that doesn't have an explicit serializer.  The input
  is a vector of a key value pair.  If the value is not a byte array,
  then it is returned unmodified."
  [m]
  (fn [[key bytes]]
    (if (instance? (Class/forName "[B") bytes)
      (if-let [serializer-name (or (get m key) (:default m))]
        (let [s (serial/serializer serializer-name)]
          [key (.fromBytes s bytes)])
        [key bytes])
      [key bytes])))

(defn create-serialize-values-function
  "Returns a function that recursively transforms all map values from
  byte arrays to clojure values using a serializer based on the key.
  The map provides the mapping between the column name (as a keyword)
  and the serializer (also as a keyword)."
  [serializer-map]
  (let [f (create-value-serializer-function serializer-map)]
    (fn [data]
      (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) data))))

(defn set-time-attributes
  "Sets the updated and created attributes in the request.  If the
  existing? is nil/false, then the created attribute it set;
  otherwise, it is removed from the request."
  [existing? data]
  (let [now (System/currentTimeMillis)]
    (if existing?
      (dissoc (assoc data :updated now) :created)
      (assoc data :updated now :created now))))

(defn property-key
  "Determines if the given keyword has the form :properties-pkey.  If
  so, it returns the value pkey.  If not, it returns nil."
  [kw]
  (second (re-matches #"properties-(.+)" (name kw))))

(defn nest-properties
  "Nest the entries in the map of the form :properties-pkey into a map
  with entries [pkey v] and stored in the :properties key."
  [m]
  (reduce-kv
   (fn [m k v]
     (let [pk (property-key k)
           path (if pk
                  [:properties pk]
                  [k])]
       (assoc-in m path v)))
   {} m))

(defn flatten-properties
  "Take the entries in the :properties map and flatten them into the
  given map with keys like :properties-pkey."
  [m]
  (if-let [props (:properties m)]
    (let [m (dissoc m :properties)
          flat-props (into {} (for [[pk pv] props]
                                [(keyword (str "properties-" pk)) pv]))]
      (merge m flat-props))
    m))