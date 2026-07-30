(ns bitcoin.node.core
  "Bitcoin Core JSON-RPC backend with loopback-by-default transport,
  cookie authentication, method allowlisting, response correlation, and
  secret-free errors."
  (:require [bitcoin.node.descriptor :as descriptor]
            [bitcoin.node.protocol :as node]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.net InetAddress URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util Base64 UUID]))

(def allowed-methods
  #{"getblockchaininfo" "getdescriptorinfo" "deriveaddresses"
    "scantxoutset"})

(def default-max-response-bytes (* 32 1024 1024))
(def max-scan-descriptors 100)
(def max-derived-addresses 1000)

(declare rpc!)

(defn- expanded-file [path]
  (when path
    (io/file
     (if (str/starts-with? path "~/")
       (str (System/getProperty "user.home") (subs path 1))
       path))))

(defn credential
  "Resolve short-lived Core cookie first, then explicit environment variables.
  Returned credentials must never enter application state or logs."
  [configuration]
  (let [cookie (expanded-file (:cookie-file configuration))]
    (cond
      (and cookie (.isFile cookie))
      (let [[username password] (str/split (str/trim (slurp cookie)) #":" 2)]
        (when (and (seq username) (seq password))
          {:username username :password password :source :cookie}))

      :else
      (let [username (or (:username configuration)
                         (some-> (:username-env configuration) System/getenv))
            password (some-> (:password-env configuration) System/getenv)]
        (when (and (seq username) (seq password))
          {:username username :password password
           :source :environment})))))

(defn endpoint
  "Validate an RPC endpoint. Remote RPC requires an explicit opt-in; userinfo,
  query strings, and fragments are never accepted."
  [configuration]
  (let [value (:url configuration)
        uri (when value (URI/create value))
        host (some-> uri .getHost)]
    (when-not (and uri (#{"http" "https"} (.getScheme uri))
                   host (nil? (.getUserInfo uri)) (nil? (.getQuery uri))
                   (nil? (.getFragment uri))
                   (or (true? (:allow-remote? configuration))
                       (.isLoopbackAddress (InetAddress/getByName host))))
      (throw (ex-info "Bitcoin Core RPC endpoint is not allowed."
                      {:type :bitcoin.node/invalid-endpoint})))
    uri))

(defn- valid-descriptor? [value]
  (and (string? value)
       (<= 8 (count value) descriptor/max-descriptor-length)))

(defn- validate-range! [range-value]
  (when range-value
    (let [[start end :as values] range-value]
      (when-not (and (= 2 (count values))
                     (nat-int? start) (nat-int? end)
                     (<= start end)
                     (< (- end start) max-derived-addresses))
        (throw (ex-info "Bitcoin address derivation range is invalid."
                        {:type :bitcoin.node/invalid-range}))))))

(defn- validate-scan! [descriptors]
  (when-not (and (sequential? descriptors)
                 (<= 1 (count descriptors) max-scan-descriptors)
                 (every? valid-descriptor? descriptors))
    (throw (ex-info "Bitcoin descriptor scan request is invalid."
                    {:type :bitcoin.node/invalid-scan}))))

(defn- read-limited [^InputStream input limit]
  (with-open [input input
              output (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [total 0]
        (let [read (.read input buffer)]
          (if (neg? read)
            (.toString output StandardCharsets/UTF_8)
            (let [next-total (+ total read)]
              (when (> next-total limit)
                (throw (ex-info "Bitcoin Core RPC response is too large."
                                {:type :bitcoin.node/response-too-large
                                 :limit limit})))
              (.write output buffer 0 read)
              (recur next-total))))))))

(defn http-transport
  [{:keys [configuration uri authorization request]}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout
                    (Duration/ofSeconds
                     (long (or (:connect-timeout-seconds configuration) 5))))
                   .build)
        http-request
        (-> (HttpRequest/newBuilder uri)
            (.timeout
             (Duration/ofSeconds
              (long (or (:timeout-seconds configuration) 30))))
            (.header "Content-Type" "application/json")
            (.header "Authorization" authorization)
            (.POST (HttpRequest$BodyPublishers/ofString
                    (json/write-str request)))
            .build)
        response (.send client http-request
                        (HttpResponse$BodyHandlers/ofInputStream))]
    {:status (.statusCode response)
     :body (read-limited
            (.body response)
            (long (or (:max-response-bytes configuration)
                      default-max-response-bytes)))}))

(defrecord CoreBackend [configuration transport]
  node/NodeBackend
  (configured? [_]
    (boolean (and (:url configuration) (credential configuration))))
  (node-status [this]
    (let [result (rpc! this "getblockchaininfo" [])]
      {:status :connected :chain (:chain result)
       :blocks (:blocks result) :headers (:headers result)
       :verification-progress (:verificationprogress result)
       :initial-block-download? (:initialblockdownload result)
       :pruned? (:pruned result)
       :prune-height (:pruneheight result)
       :size-on-disk (:size_on_disk result)}))
  (descriptor-info [this value]
    (when-not (valid-descriptor? value)
      (throw (ex-info "Bitcoin descriptor is invalid."
                      {:type :bitcoin.node/invalid-descriptor})))
    (descriptor/validate-info
     (rpc! this "getdescriptorinfo" [value])))
  (derive-addresses [this value range-value]
    (when-not (valid-descriptor? value)
      (throw (ex-info "Bitcoin descriptor is invalid."
                      {:type :bitcoin.node/invalid-descriptor})))
    (validate-range! range-value)
    (rpc! this "deriveaddresses"
          (cond-> [value] range-value (conj range-value))))
  (scan-descriptors [this descriptors]
    (validate-scan! descriptors)
    (let [result (rpc! this "scantxoutset" ["start" (vec descriptors)])]
      {:success? (true? (:success result))
       :height (:height result)
       :best-block (:bestblock result)
       :total-amount (:total_amount result)
       :unspents (vec (:unspents result))})))

(defn backend
  ([configuration] (backend configuration http-transport))
  ([configuration transport]
   (->CoreBackend configuration transport)))

(defn rpc!
  [backend method params]
  (when-not (contains? allowed-methods method)
    (throw (ex-info "Bitcoin Core RPC method is denied."
                    {:type :bitcoin.node/method-denied :method method})))
  (when-not (node/configured? backend)
    (throw (ex-info "Bitcoin Core RPC is not configured."
                    {:type :bitcoin.node/not-configured})))
  (let [configuration (:configuration backend)
        {:keys [username password]} (credential configuration)
        uri (endpoint configuration)
        request-id (str (UUID/randomUUID))
        authorization
        (str "Basic "
             (.encodeToString
              (Base64/getEncoder)
              (.getBytes (str username ":" password)
                         StandardCharsets/UTF_8)))
        response
        ((:transport backend)
         {:configuration configuration :uri uri
          :authorization authorization
          :request {:jsonrpc "2.0" :id request-id
                    :method method :params params}})
        payload (try
                  (json/read-str (:body response) :key-fn keyword)
                  (catch Exception _
                    (throw (ex-info "Bitcoin Core RPC returned invalid JSON."
                                    {:type :bitcoin.node/invalid-response
                                     :status (:status response)}))))]
    (when (or (not= 200 (:status response))
              (:error payload)
              (not= request-id (:id payload)))
      (throw (ex-info
              (or (get-in payload [:error :message])
                  "Bitcoin Core RPC failed.")
              {:type :bitcoin.node/rpc-failed
               :status (:status response)
               :code (get-in payload [:error :code])})))
    (:result payload)))
