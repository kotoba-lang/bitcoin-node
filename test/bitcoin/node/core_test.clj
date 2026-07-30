(ns bitcoin.node.core-test
  (:require [bitcoin.node.core :as core]
            [bitcoin.node.descriptor :as descriptor]
            [bitcoin.node.protocol :as node]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]))

(defn- transport
  [result]
  (fn [{:keys [request]}]
    {:status 200
     :body (json/write-str
            {:jsonrpc "2.0" :id (:id request) :result result})}))

(def config
  {:url "http://127.0.0.1:8332"
   :username "user" :password-env "BITCOIN_NODE_TEST_PASSWORD"})

(deftest endpoint-is-loopback-by-default
  (is (= "127.0.0.1" (.getHost (core/endpoint config))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"not allowed"
       (core/endpoint (assoc config :url "http://example.com:8332"))))
  (is (= "example.com"
         (.getHost
          (core/endpoint
           (assoc config :url "https://example.com:8332"
                  :allow-remote? true)))))
  (is (thrown?
       clojure.lang.ExceptionInfo
       (core/endpoint
        (assoc config :url "http://user:secret@127.0.0.1:8332")))))

(deftest descriptor-policy-rejects-private-and-unsupported-input
  (let [valid {:descriptor
               "tr(xpub/0/*,multi_a(2,xpub-a/0/*,xpub-b/0/*))#02345678"
               :checksum "02345678" :isrange true :issolvable true
               :hasprivatekeys false}]
    (is (= :taproot-multisig (:kind (descriptor/validate-info valid))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Private"
         (descriptor/validate-info (assoc valid :hasprivatekeys true))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not solvable"
         (descriptor/validate-info (assoc valid :issolvable false))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Only Taproot"
         (descriptor/validate-info
          (assoc valid :descriptor "wpkh(xpub/0/*)#02345678"))))))

(deftest core-backend-correlates-and-normalizes-responses
  (with-redefs [core/credential
                (fn [_] {:username "user" :password "secret"
                         :source :test})]
    (let [status-backend
          (core/backend
           config
           (transport {:chain "main" :blocks 10 :headers 12
                       :verificationprogress 0.5
                       :initialblockdownload true :pruned true
                       :pruneheight 0 :size_on_disk 123}))
          status (node/node-status status-backend)]
      (is (= :connected (:status status)))
      (is (= "main" (:chain status)))
      (is (true? (:initial-block-download? status)))
      (is (false? (node/ready? status)))
      (is (false? (node/ready?
                   {:status :connected :initial-block-download? false}))))
    (let [scan-backend
          (core/backend
           config
           (transport {:success true :height 100 :bestblock "abc"
                       :total_amount 0.1 :unspents [{:txid "tx"}]}))
          scan (node/scan-descriptors scan-backend ["tr(key)#02345678"])]
      (is (true? (:success? scan)))
      (is (= 1 (count (:unspents scan)))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"denied"
         (core/rpc! (core/backend config (transport {}))
                    "sendtoaddress" [])))))

(deftest backend-bounds-expensive-operations
  (with-redefs [core/credential
                (fn [_] {:username "user" :password "secret"
                         :source :test})]
    (let [backend (core/backend config (transport []))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"range is invalid"
           (node/derive-addresses
            backend "tr(xpub-placeholder)#02345678" [100 1])))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"range is invalid"
           (node/derive-addresses
            backend "tr(xpub-placeholder)#02345678" [0 1000])))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"scan request is invalid"
           (node/scan-descriptors backend []))))))

(deftest rpc-rejects-uncorrelated-and-invalid-responses
  (with-redefs [core/credential
                (fn [_] {:username "user" :password "secret"
                         :source :test})]
    (let [wrong-id
          (core/backend
           config
           (fn [_]
             {:status 200
              :body (json/write-str
                     {:jsonrpc "2.0" :id "wrong" :result {}})}))
          invalid-json
          (core/backend config (fn [_] {:status 200 :body "not-json"}))]
      (is (= :bitcoin.node/rpc-failed
             (:type (ex-data
                     (try
                       (core/rpc! wrong-id "getblockchaininfo" [])
                       (catch clojure.lang.ExceptionInfo exception
                         exception))))))
      (is (= :bitcoin.node/invalid-response
             (:type (ex-data
                     (try
                       (core/rpc! invalid-json "getblockchaininfo" [])
                       (catch clojure.lang.ExceptionInfo exception
                         exception)))))))))
