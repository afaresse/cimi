(ns eu.stratuslab.cimi.resources.machine-configuration
  "Utilities for managing the CRUD features for machine configurations."
  (:require 
    [clojure.set :as set]
    [couchbase-clj.client :as cbc]
    [eu.stratuslab.cimi.resources.common :as common]
    [eu.stratuslab.cimi.resources.utils :as utils]
    [compojure.core :refer :all]
    [compojure.route :as route]
    [compojure.handler :as handler]
    [compojure.response :as response]
    [clojure.tools.logging :refer [debug info error]]))

(def ^:const resource-type "MachineConfiguration")

(def ^:const resource-uri (str "http://schemas.dmtf.org/cimi/1/" resource-type))

(def ^:const resource-base-url (str "/" resource-type))

(def mc-attributes
  "These are the attributes specific to a MachineConfiguration."
  #{:cpu :memory :disks :cpuArch})

(def attributes
  "These are the attributes allowed for a MachineConfiguration."
  (set/union common/attributes mc-attributes))

(def immutable-attributes
  "These are the attributes for a MachineConfiguration that cannot
   be modified."
  (set/union common/immutable-attributes mc-attributes))

(defn strip-unknown-attributes [m]
  (select-keys m attributes))

(defn strip-immutable-attributes [m]
  (let [ks (set/difference (set (keys m)) immutable-attributes)]
    (select-keys m ks)))

(defn create
  "Creates a new MachineConfiguration from the given data."
  [cb-client]
  
  (let [record (->> 
                 {:id resource-base-url
                  :name resource-type
                  :description "StratusLab Cloud"
                  :resourceURI resource-uri}
                 (utils/set-time-attributes))]
    (cbc/add-json cb-client resource-base-url record)))

(defn retrieve
  "Returns the data associated with the CloudEntryPoint.  There is
  exactly one such entry in the database.  The identifier is the root
  resource name '/'.  The baseURI must be passed as this is taken from 
  the ring request."
  [req]
  (let [baseURI (:base-uri req)
        cb-client (:cb-client req)
        doc (cbc/get-json cb-client resource-base-url)]
    (assoc doc :baseURI (:baseURI req))))

(defn update
  "Update the cloud entry point attributes.  Note that only the common
  resource attributes can be updated.  The active resource collections
  cannot be changed.  For correct behavior, the cloud entry point must
  have been previously initialized.  Returns nil."
  [req]
  (let [cb-client (:cb-client req)
        update (->> req
                 (strip-unknown-attributes)
                 (strip-immutable-attributes)
                 (utils/set-time-attributes))
        current (cbc/get-json cb-client resource-base-url)
        newdoc (merge current update)]
    (cbc/set-json cb-client resource-base-url newdoc)))

(defn delete
  "Deletes the named machine configuration."
  [req]
  (let [id "dummy"
        cb-client (:cb-client req)
        resource-uri (str resource-base-url "/" id)]
    (cbc/delete cb-client resource-base-url)))

(defroutes resource-routes
  (POST resource-base-url {:as req} {:body (create req)})
  (GET resource-base-url {:as req} {:body (list req)})
  (GET (str resource-base-url "/:id") {:as req} {:body (retrieve req)})
  (PUT (str resource-base-url "/:id") {:as req} (update req) {})
  (DELETE (str resource-base-url "/:id") {:as req} (delete req) {}))