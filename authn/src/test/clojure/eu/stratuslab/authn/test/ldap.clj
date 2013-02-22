(ns eu.stratuslab.authn.test.ldap
  (:use clojure.test
        eu.stratuslab.authn.ldap)
  (:require
    [clj-ldap.client :as ldap]
    [clojure.string :as str]
    [eu.stratuslab.authn.test.ldap-server :as server])
  (:import [com.unboundid.ldap.sdk LDAPException]))

(deftest correct-user-filter
  (let [params {:user-object-class "a"
                :user-id-attr "b"
                :username "c"}
        result (user-filter params)]
    (is (= result "(&(objectClass=a)(b=c))"))))

(deftest correct-role-filter
  (let [params {:role-object-class "a"
                :role-member-attr "b"
                :user-dn "c"}
        result (role-filter params)]
    (is (= result "(&(objectClass=a)(b=c))"))))

(deftest invalid-user-filter-combinations
  (let [values ["a" "b" "c" "" " " nil]]
    (do
      (for [a values b values c values]
        (let [params {:user-object-class a
                      :user-id-attr b
                      :username c}]
          (if (some str/blank? [a b c])
            (is (thrown? IllegalArgumentException (user-filter params)))))))))

(deftest missing-user-filter-parameters
  (let [values ["a" nil]]
    (do
      (for [a values b values c values]
        (let [params {:user-object-class a
                      :user-id-attr b
                      :username c}
              params (into {} (filter (fn [[k v]] v) params))]
          (if (not= 3 (count params))
            (is (thrown? IllegalArgumentException (user-filter params)))
            (is (user-filter params))))))))

(deftest invalid-role-filter-combinations
  (let [values ["a" "b" "c" "" " " nil]]
    (do
      (for [a values b values c values]
        (let [params {:role-object-class a
                      :role-member-attr b
                      :user-dn c}]
          (if (some str/blank? [a b c])
            (is (thrown? IllegalArgumentException (role-filter params)))))))))

(deftest missing-role-filter-parameters
  (let [values ["a" nil]]
    (do
      (for [a values b values c values]
        (let [params {:role-object-class a
                      :role-member-attr b
                      :user-dn c}
              params (into {} (filter (fn [[k v]] v) params))]
          (if (not= 3 (count params))
            (is (thrown? IllegalArgumentException (user-filter params)))
            (is (role-filter params))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Tests are run over a variety of connection types
(def port* 1389)
(def ssl-port* 1636)
(def ^:dynamic *connections* nil)
(def ^:dynamic *conn* nil)

;; Tests concentrate on a single object class
(def base* "ou=people,dc=alienscience,dc=org,dc=uk")
(def dn*  (str "cn=%s," base*))
(def object-class* #{"top" "person"})

;; Variable to catch side effects
(def ^:dynamic *side-effects* nil)

;; Result of a successful write
(def success*      {:code 0 :name "success"})

;; People to test with
(def person-a*
     {:dn (format dn* "testa")
      :object {:objectClass object-class*
               :cn "testa"
               :sn "a"
               :description "description a"
               :telephoneNumber "000000001"
               :userPassword "passa"}})

(def person-b*
     {:dn (format dn* "testb")
      :object {:objectClass object-class*
               :cn "testb"
               :sn "b"
               :description "description b"
               :telephoneNumber ["000000002" "00000003"]
               :userPassword "passb"}})

(def person-c*
     {:dn (format dn* "testc")
      :object {:objectClass object-class*
               :cn "testc"
               :sn "c"
               :description "description c"
               :telephoneNumber "000000004"
               :userPassword "passc"}})

(defn- connect-to-server
  "Opens a sequence of connection pools on the localhost server with the
   given ports"
  [port ssl-port]
  [
   (ldap/connect {:host {:port port}})
   (ldap/connect {:host {:address "localhost"
                         :port port}
                  :num-connections 4})
   (ldap/connect {:host (str "localhost:" port)})
   (ldap/connect {:ssl? true
                  :host {:port ssl-port}})
   (ldap/connect {:host {:port port}
                  :connect-timeout 1000
                  :timeout 5000})
   (ldap/connect {:host [(str "localhost:" port)
                         {:port ssl-port}]})
   (ldap/connect {:host [(str "localhost:" ssl-port)
                         {:port ssl-port}]
                  :ssl? true
                  :num-connections 5})  
   ])


(defn- test-server
  "Setup server"
  [f]
  (server/start! port* ssl-port*)
  (binding [*connections* (connect-to-server port* ssl-port*)]
    (f))
  (server/stop!))

(defn- test-data
  "Provide test data"
  [f]
  (doseq [connection *connections*]
    (binding [*conn* connection]
      (try
        (ldap/add *conn* (:dn person-a*) (:object person-a*))
        (ldap/add *conn* (:dn person-b*) (:object person-b*))
        (catch Exception e))
      (f)
      (try
        (ldap/delete *conn* (:dn person-a*))
        (ldap/delete *conn* (:dn person-b*))
        (catch Exception e)))))

(use-fixtures :each test-data)
(use-fixtures :once test-server)

(deftest test-get
  (is (= (ldap/get *conn* (:dn person-a*))
         (assoc (:object person-a*) :dn (:dn person-a*))))
  (is (= (ldap/get *conn* (:dn person-b*))
         (assoc (:object person-b*) :dn (:dn person-b*))))
  (is (= (ldap/get *conn* (:dn person-a*) [:cn :sn])
         {:dn (:dn person-a*)
          :cn (-> person-a* :object :cn)
          :sn (-> person-a* :object :sn)})))

(deftest test-add-delete
  (is (= (ldap/add *conn* (:dn person-c*) (:object person-c*))
         success*))
  (is (= (ldap/get *conn* (:dn person-c*))
         (assoc (:object person-c*) :dn (:dn person-c*))))
  (is (= (ldap/delete *conn* (:dn person-c*))
         success*))
  (is (nil? (ldap/get *conn* (:dn person-c*)))))

(deftest test-modify-add
  (is (= (ldap/modify *conn* (:dn person-a*)
                      {:add {:objectClass "residentialperson"
                             :l "Hollywood"}})
         success*))
  (is (= (ldap/modify
          *conn* (:dn person-b*)
          {:add {:telephoneNumber ["0000000005" "0000000006"]}})
         success*))
  (let [new-a (ldap/get *conn* (:dn person-a*))
        new-b (ldap/get *conn* (:dn person-b*))
        obj-a (:object person-a*)
        obj-b (:object person-b*)]
    (is  (= (:objectClass new-a)
            (conj (:objectClass obj-a) "residentialPerson")))
    (is (= (:l new-a) "Hollywood"))
    (is (= (set (:telephoneNumber new-b))
           (set (concat (:telephoneNumber obj-b)
                        ["0000000005" "0000000006"]))))))

(deftest test-modify-delete
  (let [b-phonenums (-> person-b* :object :telephoneNumber)]
    (is (= (ldap/modify *conn* (:dn person-a*)
                        {:delete {:description :all}})
           success*))
    (is (= (ldap/modify *conn* (:dn person-b*)
                        {:delete {:telephoneNumber (first b-phonenums)}})
           success*))
    (is (= (ldap/get *conn* (:dn person-a*))
           (-> (:object person-a*)
               (dissoc :description)
               (assoc :dn (:dn person-a*)))))
    (is (= (ldap/get *conn* (:dn person-b*))
           (-> (:object person-b*)
               (assoc :telephoneNumber (second b-phonenums))
               (assoc :dn (:dn person-b*)))))))

(deftest test-modify-replace
  (let [new-phonenums (-> person-b* :object :telephoneNumber)]
    (is (= (ldap/modify *conn* (:dn person-a*)
                        {:replace {:telephoneNumber new-phonenums}})
           success*))
    (is (= (ldap/get *conn* (:dn person-a*))
           (-> (:object person-a*)
               (assoc :telephoneNumber new-phonenums)
               (assoc :dn (:dn person-a*)))))))

(deftest test-modify-all
  (let [b (:object person-b*)
        b-phonenums (:telephoneNumber b)]
    (is (= (ldap/modify *conn* (:dn person-b*)
                        {:add {:telephoneNumber "0000000005"}
                         :delete {:telephoneNumber (second b-phonenums)}
                         :replace {:description "desc x"}})
           success*))
    (let [new-b (ldap/get *conn* (:dn person-b*))]
      (is (= (set (:telephoneNumber new-b))
             (set [(first b-phonenums) "0000000005"])))
      (is (= (:description new-b) "desc x")))))


(deftest test-search
  (is (= (set (map :cn
                   (ldap/search *conn* base* {:attributes [:cn]})))
         (set [nil "testa" "testb" "Saul Hazledine"])))
  (is (= (set (map :cn
                   (ldap/search *conn* base*
                                {:attributes [:cn] :filter "cn=test*"})))
         (set ["testa" "testb"])))
  (binding [*side-effects* #{}]
    (ldap/search! *conn* base* {:attributes [:cn :sn] :filter "cn=test*"}
                  (fn [x]
                    (set! *side-effects*
                          (conj *side-effects* (dissoc x :dn)))))
    (is (= *side-effects*
           (set [{:cn "testa" :sn "a"}
                 {:cn "testb" :sn "b"}])))))