(ns grog.mcp-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.mcp :as mcp]
            [grog.test-helpers :as h]
            [org.httpkit.server :as http-server])
  (:import (java.io PipedInputStream PipedOutputStream)))

(defn ^:private result [msg r]
  {:jsonrpc "2.0" :id (:id msg) :result r})

(defn ^:private error [msg code message & [data]]
  {:jsonrpc "2.0" :id (:id msg) :error (cond-> {:code code :message message} data (assoc :data data))})

(def ^:private discovered
  {:supportedVersions ["2026-07-28"]
   :capabilities {:tools {:listChanged true}}
   :instructions "Use the lights."
   :ttlMs 0
   :_meta {"io.modelcontextprotocol/serverInfo" {:name "fake" :version "1.0"}}})

(defn ^:private legacy-initialize [msg]
  (result msg {:protocolVersion "2025-11-25"
               :capabilities {:tools {:listChanged true}}
               :serverInfo {:name "old" :version "0.9"}
               :instructions "Old ways."}))

(defn ^:private fake-stdio!
  "A stdio MCP server in this process: `handle` gets {:send :recv :close!}
  and each message grog sends, and answers it (or not) with send. What it
  received is kept in :received."
  [handle]
  (let [server-out (PipedOutputStream.)
        client-in (PipedInputStream. server-out 65536)
        client-out (PipedOutputStream.)
        server-in (PipedInputStream. client-out 65536)
        reader (io/reader server-in :encoding "UTF-8")
        writer (io/writer server-out :encoding "UTF-8")
        received (atom [])
        close-server! #(.close server-out)
        io {:send (fn [m] (locking writer
                            (.write writer (str (json/generate-string m) "\n"))
                            (.flush writer)))
            :recv (fn [] (when-let [line (.readLine reader)]
                           (let [m (json/parse-string line true)]
                             (swap! received conj m)
                             m)))
            :close! close-server!}]
    (future
      (try
        (loop []
          (when-let [m ((:recv io))]
            (handle io m)
            (recur)))
        (catch Exception _ nil)))
    (assoc io
           :received received
           :transport (mcp/stdio-transport {:in client-in
                                            :out client-out
                                            :close! #(do (close-server!) (.close client-out))}))))

(defn ^:private received [fake method]
  (filterv #(= method (:method %)) @(:received fake)))

(defn ^:private eventually [f]
  (loop [n 0]
    (or (f)
        (when (< n 200)
          (Thread/sleep 10)
          (recur (inc n))))))

(defn ^:private thrown-type [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (ex-data e))))

(defn ^:private new-server
  "A handler of a 2026 server: discovery, then `tools` (method → fn of the
  message)."
  [handlers]
  (fn [{:keys [send] :as io} m]
    (if (= "server/discover" (:method m))
      (send (result m discovered))
      (when-let [f (get handlers (:method m))]
        (f io m)))))

(defn ^:private old-server [handlers]
  (fn [{:keys [send] :as io} m]
    (case (:method m)
      "server/discover" (send (error m -32601 "Method not found"))
      "initialize" (send (legacy-initialize m))
      (when-let [f (get handlers (:method m))]
        (f io m)))))

(deftest stdio-discovery
  (let [fake (fake-stdio! (new-server {}))
        client (mcp/connect! (:transport fake) {:client-info {:name "grog" :version "1"}
                                                :on-elicit (constantly {:action "decline"})})]
    (try
      (is (= {:version "2026-07-28"
              :server-info {:name "fake" :version "1.0"}
              :instructions "Use the lights."
              :capabilities {:tools {:listChanged true}}}
             (mcp/info client)))
      (let [[m] (received fake "server/discover")
            meta (get-in m [:params :_meta])]
        (is (= "2026-07-28" (:io.modelcontextprotocol/protocolVersion meta)))
        (is (= {:elicitation {:form {} :url {}}} (:io.modelcontextprotocol/clientCapabilities meta)))
        (is (= {:name "grog" :version "1"} (:io.modelcontextprotocol/clientInfo meta))))
      (is (empty? (received fake "initialize")))
      (is (mcp/alive? client))
      (finally
        (mcp/close! client)
        (mcp/close! client)))
    (is (not (mcp/alive? client)))))

(deftest stdio-legacy-fallback
  (testing "an old server answering -32601 to server/discover"
    (let [fake (fake-stdio! (old-server {}))
          client (mcp/connect! (:transport fake) {})]
      (try
        (is (= {:version "2025-11-25"
                :server-info {:name "old" :version "0.9"}
                :instructions "Old ways."
                :capabilities {:tools {:listChanged true}}}
               (mcp/info client)))
        (is (= {:protocolVersion "2025-11-25" :capabilities {} :clientInfo {:name "grog" :version "dev"}}
               (:params (first (received fake "initialize")))))
        (is (eventually #(seq (received fake "notifications/initialized"))))
        (finally (mcp/close! client)))))

  (testing "an old server ignoring server/discover"
    (let [fake (fake-stdio! (fn [{:keys [send]} m]
                              (when (= "initialize" (:method m))
                                (send (legacy-initialize m)))))
          client (binding [mcp/*detect-timeout-ms* 200]
                   (mcp/connect! (:transport fake) {}))]
      (try
        (is (= "2025-11-25" (:version (mcp/info client))))
        (is (empty? (received fake "notifications/cancelled")))
        (finally (mcp/close! client)))))

  (testing "a new server supporting only an older revision"
    (let [fake (fake-stdio! (fn [{:keys [send]} m]
                              (case (:method m)
                                "server/discover" (send (error m -32022 "Unsupported version" {:supported ["2025-06-18"]}))
                                "initialize" (send (result m {:protocolVersion "2025-06-18" :serverInfo {:name "mid"}}))
                                nil)))
          client (mcp/connect! (:transport fake) {})]
      (try
        (is (= "2025-06-18" (:version (mcp/info client))))
        (finally (mcp/close! client)))))

  (testing "a server speaking only a revision grog doesn't"
    (let [fake (fake-stdio! (fn [{:keys [send]} m]
                              (case (:method m)
                                "server/discover" (send (error m -32601 "nope"))
                                "initialize" (send (result m {:protocolVersion "2024-11-05"}))
                                nil)))]
      (is (= ::mcp/unsupported-version (:type (thrown-type #(mcp/connect! (:transport fake) {})))))))

  (testing "a server dying while asked"
    (let [fake (fake-stdio! (fn [{:keys [close!]} _] (close!)))]
      (is (= ::mcp/closed (:type (thrown-type #(mcp/connect! (:transport fake) {})))))))

  (testing "skipping detection"
    (let [fake (fake-stdio! (old-server {}))
          client (mcp/connect! (:transport fake) {:legacy? true})]
      (try
        (is (= "2025-11-25" (:version (mcp/info client))))
        (is (empty? (received fake "server/discover")))
        (finally (mcp/close! client))))))

(deftest stdio-tools-pages-and-ttl
  (let [fake (fake-stdio! (new-server
                           {"tools/list" (fn [{:keys [send]} m]
                                           (send (result m (if (= "p2" (get-in m [:params :cursor]))
                                                             {:tools [{:name "b" :inputSchema {:type "object"}}] :ttlMs 150}
                                                             {:tools [{:name "a" :inputSchema {:type "object"}}]
                                                              :nextCursor "p2"
                                                              :ttlMs 60000}))))}))
        client (mcp/connect! (:transport fake) {})]
    (try
      (is (= ["a" "b"] (mapv :name (mcp/tools client))))
      (is (= ["a" "b"] (mapv :name (mcp/tools client))))
      (is (= 2 (count (received fake "tools/list"))))
      (is (= {:cursor "p2"} (dissoc (:params (second (received fake "tools/list"))) :_meta)))
      (Thread/sleep 200)
      (mcp/tools client)
      (is (= 4 (count (received fake "tools/list"))))
      (finally (mcp/close! client)))))

(deftest stdio-list-changed
  (let [fake (fake-stdio! (old-server {"tools/list" (fn [{:keys [send]} m]
                                                      (send (result m {:tools [{:name "a"}]})))}))
        changed (promise)
        client (mcp/connect! (:transport fake) {:on-tools-changed #(deliver changed true)})]
    (try
      (mcp/tools client)
      (mcp/tools client)
      (is (= 1 (count (received fake "tools/list"))))
      ((:send fake) {:jsonrpc "2.0" :method "notifications/tools/list_changed"})
      (is (deref changed 2000 false))
      (mcp/tools client)
      (is (= 2 (count (received fake "tools/list"))))
      (finally (mcp/close! client)))))

(deftest stdio-input-required
  (let [asked (atom [])
        fake (fake-stdio! (new-server
                           {"tools/call" (fn [{:keys [send]} m]
                                           (if-let [answers (get-in m [:params :inputResponses])]
                                             (send (result m {:content [{:type "text"
                                                                         :text (str "lit " (get-in answers [:room :content :room])
                                                                                    " " (get-in m [:params :requestState]))}]}))
                                             (send (result m {:resultType "input_required"
                                                              :inputRequests {:room {:method "elicitation/create"
                                                                                     :params {:mode "form"
                                                                                              :message "Which room?"
                                                                                              :requestedSchema {:type "object"
                                                                                                                :properties {:room {:type "string"}}}}}}
                                                              :requestState "opaque-1"}))))}))
        client (mcp/connect! (:transport fake) {:on-elicit (fn [params]
                                                             (swap! asked conj params)
                                                             {:action :accept :content {:room "sala"}})})]
    (try
      (is (= "lit sala opaque-1" (mcp/text-of (mcp/call-tool! client "light" {:on true}))))
      (is (= ["Which room?"] (map :message @asked)))
      (let [[first-call second-call] (received fake "tools/call")]
        (is (not= (:id first-call) (:id second-call)))
        (is (= {:name "light" :arguments {:on true}} (dissoc (:params first-call) :_meta)))
        (is (= {:name "light"
                :arguments {:on true}
                :inputResponses {:room {:action "accept" :content {:room "sala"}}}
                :requestState "opaque-1"}
               (dissoc (:params second-call) :_meta)))
        (is (= "2026-07-28" (get-in second-call [:params :_meta :io.modelcontextprotocol/protocolVersion]))))
      (finally (mcp/close! client))))

  (testing "a server asking forever"
    (let [fake (fake-stdio! (new-server
                             {"tools/call" (fn [{:keys [send]} m]
                                             (send (result m {:resultType "input_required" :requestState "again"})))}))
          client (mcp/connect! (:transport fake) {})]
      (try
        (is (= ::mcp/failed (:type (thrown-type #(mcp/call-tool! client "x" {})))))
        (is (= 9 (count (received fake "tools/call"))))
        (finally (mcp/close! client))))))

(deftest stdio-server-requests
  (let [fake (fake-stdio! (old-server
                           {"tools/call" (fn [{:keys [send recv]} m]
                                           (send {:jsonrpc "2.0" :id "p1" :method "ping"})
                                           (let [pong (recv)]
                                             (send {:jsonrpc "2.0" :id 99 :method "elicitation/create"
                                                    :params {:mode "url" :message "Sign in" :url "https://x" :elicitationId "e1"}})
                                             (let [answer (recv)]
                                               (send {:jsonrpc "2.0" :id 100 :method "sampling/createMessage" :params {}})
                                               (let [refusal (recv)]
                                                 (send (result m {:content [{:type "text"
                                                                             :text (json/generate-string [pong answer refusal])}]}))))))}))
        client (mcp/connect! (:transport fake) {:on-elicit (fn [params] {:action "accept" :url (:url params)})})]
    (try
      (let [[pong answer refusal] (json/parse-string (mcp/text-of (mcp/call-tool! client "x" {})) true)]
        (is (= {:jsonrpc "2.0" :id "p1" :result {}} pong))
        (is (= {:jsonrpc "2.0" :id 99 :result {:action "accept"}} answer))
        (is (= -32601 (get-in refusal [:error :code]))))
      (finally (mcp/close! client)))))

(deftest stdio-timeout-and-eof
  (testing "a call that takes too long is cancelled"
    (let [fake (fake-stdio! (new-server {}))
          client (mcp/connect! (:transport fake) {})]
      (try
        (is (= ::mcp/timeout (:type (thrown-type #(mcp/call-tool! client "slow" {} {:timeout-ms 200})))))
        (let [call (first (received fake "tools/call"))]
          (is (eventually #(seq (received fake "notifications/cancelled"))))
          (is (= {:requestId (:id call) :reason "timed out"}
                 (:params (first (received fake "notifications/cancelled"))))))
        (is (mcp/alive? client))
        (finally (mcp/close! client)))))

  (testing "a server ending fails what waits for it"
    (let [fake (fake-stdio! (new-server {"tools/call" (fn [{:keys [close!]} _] (close!))}))
          client (mcp/connect! (:transport fake) {})]
      (try
        (is (= ::mcp/closed (:type (thrown-type #(mcp/call-tool! client "x" {})))))
        (is (eventually #(not (mcp/alive? client))))
        (is (= ::mcp/closed (:type (thrown-type #(mcp/call-tool! client "x" {})))))
        (finally (mcp/close! client))))))

(defn ^:private body-of [req]
  (some-> (:body req) slurp (json/parse-string true)))

(defn ^:private json-reply [m & [headers]]
  {:status 200
   :headers (merge {"Content-Type" "application/json"} headers)
   :body (json/generate-string m)})

(defn ^:private with-server* [handler f]
  (let [server (h/start-handler! handler)]
    (try (f (:url server))
         (finally ((:stop! server))))))

(def ^:private weather
  {:name "weather"
   :inputSchema {:type "object"
                 :properties {:region {:type "string" :x-mcp-header "Region"}
                              :city {:type "string" :x-mcp-header "City"}
                              :days {:type "integer" :x-mcp-header "Days"}
                              :metric {:type "boolean" :x-mcp-header "Metric"}
                              :where {:type "object"
                                      :properties {:zone {:type "string" :x-mcp-header "Zone"}}}}}})

(deftest http-new-style
  (let [requests (atom [])]
    (with-server*
      (fn [req]
        (let [m (body-of req)]
          (swap! requests conj {:headers (:headers req) :body m})
          (case (:method m)
            "server/discover" (json-reply (result m discovered))
            "tools/list" (json-reply (result m {:tools [weather]}))
            "tools/call" (json-reply (result m {:content [{:type "text" :text "sunny"}]}))
            {:status 400})))
      (fn [url]
        (let [client (mcp/connect! (mcp/http-transport {:url url :headers (constantly {"Authorization" "Bearer t"})}) {})]
          (is (= "2026-07-28" (:version (mcp/info client))))
          (is (= "sunny" (mcp/text-of (mcp/call-tool! client "weather" {:region "us-west"
                                                                        :city "São Paulo"
                                                                        :days 3
                                                                        :metric false
                                                                        :where {:zone " a"}}))))
          (let [discover (first @requests)
                call (last @requests)
                headers (:headers call)]
            (is (= "server/discover" (get-in discover [:headers "mcp-method"])))
            (is (= "2026-07-28" (get headers "mcp-protocol-version")))
            (is (= "Bearer t" (get headers "authorization")))
            (is (= "tools/call" (get headers "mcp-method")))
            (is (= "weather" (get headers "mcp-name")))
            (is (= "us-west" (get headers "mcp-param-region")))
            (is (= "=?base64?U8OjbyBQYXVsbw==?=" (get headers "mcp-param-city")))
            (is (= "3" (get headers "mcp-param-days")))
            (is (= "false" (get headers "mcp-param-metric")))
            (is (= "=?base64?IGE=?=" (get headers "mcp-param-zone")))
            (is (str/includes? (get headers "accept") "text/event-stream"))
            (is (= "2026-07-28" (get-in call [:body :params :_meta :io.modelcontextprotocol/protocolVersion]))))
          (mcp/close! client))))))

(deftest http-sse-response
  (let [changed (promise)]
    (with-server*
      (fn [req]
        (let [m (body-of req)]
          (case (:method m)
            "server/discover" (json-reply (result m discovered))
            "tools/list" (json-reply (result m {:tools []}))
            "tools/call" {:status 200
                          :headers {"Content-Type" "text/event-stream"}
                          :body (str ": keep-alive\n\n"
                                     "data: " (json/generate-string {:jsonrpc "2.0" :method "notifications/progress" :params {}}) "\n\n"
                                     "data: " (json/generate-string {:jsonrpc "2.0" :method "notifications/tools/list_changed"}) "\n\n"
                                     "data: " (json/generate-string (result m {:content [{:type "text" :text "done"}]})) "\n\n")}
            {:status 400})))
      (fn [url]
        (let [client (mcp/connect! (mcp/http-transport {:url url}) {:on-tools-changed #(deliver changed true)})]
          (is (= "done" (mcp/text-of (mcp/call-tool! client "x" {}))))
          (is (deref changed 1000 false))
          (mcp/close! client))))))

(deftest http-legacy-session
  (let [requests (atom [])
        sessions (atom 0)
        expired (atom false)]
    (with-server*
      (fn [req]
        (let [m (body-of req)
              session (get-in req [:headers "mcp-session-id"])]
          (swap! requests conj {:method (:request-method req) :headers (:headers req) :body m})
          (cond
            (= :delete (:request-method req)) {:status 200}
            (= "server/discover" (:method m)) {:status 405 :body ""}
            (= "initialize" (:method m)) (json-reply (legacy-initialize m) {"Mcp-Session-Id" (str "s" (swap! sessions inc))})
            (= "notifications/initialized" (:method m)) {:status 202}
            (and (= "tools/list" (:method m)) (= "s1" session) (not @expired)) (do (reset! expired true) {:status 404})
            (= "tools/list" (:method m)) (json-reply (result m {:tools [{:name "a"}]}))
            :else {:status 400})))
      (fn [url]
        (let [client (mcp/connect! (mcp/http-transport {:url url}) {})]
          (is (= "2025-11-25" (:version (mcp/info client))))
          (is (= ["a"] (mapv :name (mcp/tools client))))
          (is (= 2 @sessions))
          (let [listed (last (filter #(= "tools/list" (get-in % [:body :method])) @requests))]
            (is (= "s2" (get-in listed [:headers "mcp-session-id"])))
            (is (= "2025-11-25" (get-in listed [:headers "mcp-protocol-version"])))
            (is (nil? (get-in listed [:body :params :_meta]))))
          (mcp/close! client)
          (let [delete (last @requests)]
            (is (= :delete (:method delete)))
            (is (= "s2" (get-in delete [:headers "mcp-session-id"])))))))))

(deftest http-legacy-elicitation
  (let [answered (promise)
        sse (fn [m] (str "data: " (json/generate-string m) "\n\n"))]
    (with-server*
      (fn [req]
        (let [m (body-of req)]
          (cond
            (= "server/discover" (:method m)) {:status 404 :headers {"Content-Type" "text/html"} :body "<html>no</html>"}
            (= "initialize" (:method m)) (json-reply (legacy-initialize m))
            (= "notifications/initialized" (:method m)) {:status 202}
            (= 7 (:id m)) (do (deliver answered m) {:status 202})
            (= "tools/call" (:method m))
            (http-server/as-channel
             req
             {:on-open (fn [ch]
                         (http-server/send! ch {:status 200
                                                :headers {"Content-Type" "text/event-stream"}
                                                :body (sse {:jsonrpc "2.0" :id 7 :method "elicitation/create"
                                                            :params {:message "Which room?"
                                                                     :requestedSchema {:type "object"}}})}
                                            false)
                         (future
                           (let [answer (deref answered 5000 nil)]
                             (http-server/send! ch (sse (result m {:content [{:type "text"
                                                                              :text (str "got " (get-in answer [:result :content :room]))}]}))
                                                true))))})
            :else {:status 400})))
      (fn [url]
        (let [client (mcp/connect! (mcp/http-transport {:url url})
                                   {:on-elicit (constantly {:action "accept" :content {:room "sala"}})})]
          (is (= "got sala" (mcp/text-of (mcp/call-tool! client "x" {}))))
          (is (= {:jsonrpc "2.0" :id 7 :result {:action "accept" :content {:room "sala"}}} (deref answered 0 nil)))
          (mcp/close! client))))))

(deftest http-auth-and-failures
  (testing "401 with a challenge"
    (with-server*
      (fn [_]
        {:status 401
         :headers {"WWW-Authenticate" "Bearer resource_metadata=\"https://mcp.example/.well-known/oauth-protected-resource\", scope=\"lights:read lights:write\""}})
      (fn [url]
        (is (= {:type ::mcp/unauthorized
                :challenge {:scheme "bearer"
                            :resource-metadata "https://mcp.example/.well-known/oauth-protected-resource"
                            :scope "lights:read lights:write"}}
               (thrown-type #(mcp/connect! (mcp/http-transport {:url url}) {})))))))

  (testing "403 asking for more scope"
    (with-server*
      (fn [_] {:status 403 :headers {"WWW-Authenticate" "Bearer error=\"insufficient_scope\", scope=\"admin\""}})
      (fn [url]
        (is (= ::mcp/forbidden (:type (thrown-type #(mcp/connect! (mcp/http-transport {:url url}) {}))))))))

  (testing "nothing listening"
    (is (= ::mcp/unreachable
           (:type (thrown-type #(mcp/connect! (mcp/http-transport {:url (str "http://127.0.0.1:" (h/free-port) "/mcp")}) {}))))))

  (testing "something that isn't MCP"
    (with-server*
      (fn [_] {:status 404 :body "not found"})
      (fn [url]
        (is (= ::mcp/failed (:type (thrown-type #(mcp/connect! (mcp/http-transport {:url url}) {})))))))))

(deftest http-invalid-header-annotations
  (let [tool (fn [name properties] {:name name :inputSchema {:type "object" :properties properties}})
        listed [(tool "good" {:a {:type "string" :x-mcp-header "A"}
                              :n {:type "object" :properties {:b {:type "integer" :x-mcp-header "B"}}}})
                (tool "empty" {:a {:type "string" :x-mcp-header ""}})
                (tool "spaced" {:a {:type "string" :x-mcp-header "Re gion"}})
                (tool "twice" {:a {:type "string" :x-mcp-header "Region"}
                               :b {:type "string" :x-mcp-header "region"}})
                (tool "number" {:a {:type "number" :x-mcp-header "A"}})
                (tool "object" {:a {:type "object" :x-mcp-header "A"}})
                (tool "array" {:a {:type "array" :x-mcp-header "A"}})
                (tool "hidden" {:a {:type "array" :items {:type "string" :x-mcp-header "A"}}})
                (tool "plain" {:a {:type "string"}})]]
    (with-server*
      (fn [req]
        (let [m (body-of req)]
          (case (:method m)
            "server/discover" (json-reply (result m discovered))
            "tools/list" (json-reply (result m {:tools listed :ttlMs 60000}))
            {:status 400})))
      (fn [url]
        (let [client (mcp/connect! (mcp/http-transport {:url url}) {})]
          (is (= ["good" "plain"] (mapv :name (mcp/tools client))))
          (is (= (first listed) (first (mcp/tools client))))
          (mcp/close! client))))))

(deftest parse-challenge
  (is (nil? (mcp/parse-challenge nil)))
  (is (nil? (mcp/parse-challenge "Basic realm=\"x\"")))
  (is (= {:scheme "bearer"} (mcp/parse-challenge "Bearer")))
  (is (= {:scheme "bearer"
          :error "invalid_token"
          :error-description "say \"hi\", then go"
          :scope "a"}
         (mcp/parse-challenge "Basic realm=\"x\", bearer error=invalid_token, error_description=\"say \\\"hi\\\", then go\", scope=\"a\""))))

(deftest text-of
  (is (nil? (mcp/text-of nil)))
  (is (= "" (mcp/text-of {:content []})))
  (is (= "one\n\ntwo" (mcp/text-of {:content [{:type "text" :text "one"} {:type "text" :text "two"}]})))
  (is (= "Song (spotify:track:1)\n\n[image]\n\n[audio]\n\nfile text\n\n[file file:///a.bin]"
         (mcp/text-of {:content [{:type "resource_link" :name "Song" :uri "spotify:track:1"}
                                 {:type "image" :data "..." :mimeType "image/png"}
                                 {:type "audio" :data "..." :mimeType "audio/wav"}
                                 {:type "resource" :resource {:uri "file:///a.txt" :text "file text"}}
                                 {:type "resource" :resource {:uri "file:///a.bin" :blob "AAA="}}]})))
  (is (= "{\"temperature\":22}" (mcp/text-of {:content [] :structuredContent {:temperature 22}})))
  (is (= "text wins" (mcp/text-of {:content [{:type "text" :text "text wins"}] :structuredContent {:a 1}})))
  (is (= "error: no such room" (mcp/text-of {:content [{:type "text" :text "no such room"}] :isError true}))))
