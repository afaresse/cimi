(ns eu.stratuslab.cimi.cb.bootstrap
  "Provides the utility to provide the necessary views and objects
   in the Couchbase database for minimal operation of the CIMI 
   service."

  (:require 
    [clojure.tools.logging :as log]
    [eu.stratuslab.cimi.resources.cloud-entry-point :as cep]
    [eu.stratuslab.cimi.cb.views :as views]))

(defn create-views
  "Ensure that the views necessary for searching the database
   are available."
  [cb-client]
  (if (views/add-design-doc cb-client)
    (log/info "design document added to database")
    (log/info "design document NOT added to database")))

(defn create-cep
  "Checks to see if the CloudEntryPoint exists in the database;
   if not, it will create one.  The CloudEntryPoint is the core
   resource of the service and must exist."
  [cb-client]
  (try 
    (cep/add cb-client)
    (log/info "created CloudEntryPoint")
    (catch Exception e
      (log/warn "could not create CloudEntryPoint:" (.getMessage e))
      (try
        (cep/retrieve cb-client)
        (log/info "CloudEntryPoint exists")
        (catch Exception e
          (log/error "problem retrieving CloudEntryPoint:" (.getMessage e)))))))

(defn bootstrap
  "Bootstraps the Couchbase database by creating the CloudEntryPoint
   and inserting the design document with views, as necessary."
  [cb-client]
  (create-cep cb-client)
  (create-views cb-client))
