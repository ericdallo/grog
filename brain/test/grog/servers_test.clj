(ns grog.servers-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.catalog :as catalog]
            [grog.config :as config]
            [grog.connections :as connections]
            [grog.mcp :as mcp]
            [grog.registry :as registry]
            [grog.servers :as servers]
            [grog.test-helpers :as h]))

(defn ^:private context []
  (assistant/new-context (-> config/defaults
                             (assoc :state-dir (h/temp-dir))
                             (assoc-in [:integrations :runner] "/run/grog-mcp/runner.sock"))))

(defn ^:private connected!
  "Connection `entry`, with `secrets`: its id."
  [ctx entry secrets]
  (let [config @(:config ctx)
        id (catalog/add! config entry)]
    (connections/save! (config/state-dir config) {:id id :integration id :name "it"} secrets)
    id))

(defn ^:private ex-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest a-package-on-first-use
  (let [ctx (context)
        id (connected! ctx (registry/package-entry "npm:fake@1.0.0") {:values []})
        fakes (atom [])
        dying? (atom false)
        call (fn [n _]
               (when @dying? ((:close! (last @fakes))))
               {:content [{:type "text" :text (str "ran " n)}]})]
    (with-redefs [servers/transport! (fn [_ launch _]
                                       (is (= {:registryType "npm" :identifier "fake" :version "1.0.0"
                                               :packageArguments [] :env {}}
                                              (:request launch)))
                                       (let [fake (h/fake-stdio-mcp! {:tools [{:name "a"}] :call call})]
                                         (swap! fakes conj fake)
                                         (:transport fake)))]
      (try
        (testing "nothing runs before it's used"
          (is (= #{} (servers/running ctx))))
        (testing "started on the first call, then kept"
          (is (= "ran a" (mcp/text-of (servers/call! ctx id "a" {}))))
          (is (= "ran a" (mcp/text-of (servers/call! ctx id "a" {:x 1}))))
          (is (= 1 (count @fakes)))
          (is (= #{id} (servers/running ctx))))
        (testing "a server that goes away during a call: not called again, started again on the next"
          (reset! dying? true)
          (is (= ::mcp/closed (ex-type #(servers/call! ctx id "a" {}))))
          (is (= 3 (count (filter #(= "tools/call" (:method %)) @(:received (last @fakes))))) "the two before, and this one")
          (reset! dying? false)
          (is (= "ran a" (mcp/text-of (servers/call! ctx id "a" {}))))
          (is (= 2 (count @fakes))))
        (testing "stopped once unused for a while"
          (is (= [] (servers/stop-idle! ctx)))
          (with-redefs [servers/idle-ms 0]
            (is (= [id] (servers/stop-idle! ctx))))
          (is (= #{} (servers/running ctx))))
        (finally
          (servers/stop-all! ctx))))))

(deftest what-isnt-connected
  (let [ctx (context)]
    (is (= ::servers/not-connected (ex-type #(servers/call! ctx "nope" "a" {}))))))

(deftest tokens-kept-alive
  (let [refuse (atom false)
        server (h/start-mcp! {:tools [{:name "a"}] :oauth {} :refuse-refresh refuse})
        ctx (context)
        config @(:config ctx)
        dir (config/state-dir config)
        auth {:issuer (:base server) :token-endpoint (str (:base server) "/token") :resource (:url server)
              :client-id "c" :auth-method "none" :access-token "old" :refresh-token "rt-0"
              :expires-at (- (System/currentTimeMillis) 1000)}
        id (connected! ctx (registry/url-entry (:url server)) {:values [] :auth auth})]
    (try
      (testing "an expired token is refreshed, and kept"
        (is (= "ran a" (mcp/text-of (servers/call! ctx id "a" {}))))
        (is (= ["at-1" "rt-1"] ((juxt :access-token :refresh-token) (:auth (connections/secrets dir id)))))
        (is (= "connected" (:status (connections/by-id dir id)))))
      (testing "a refresh refused: signed out"
        (servers/stop-all! ctx)
        (connections/save! dir (connections/by-id dir id) {:values [] :auth (assoc auth :refresh-token "rt-9")})
        (reset! refuse true)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sign in to 127\.0\.0\.1 again"
                              (servers/call! ctx id "a" {})))
        (is (= "signed-out" (:status (connections/by-id dir id))))
        (is (= ::servers/signed-out (ex-type #(servers/call! ctx id "a" {}))) "until signed in again"))
      (finally
        (servers/stop-all! ctx)
        ((:stop! server))))))

(deftest the-tools-kept
  (let [ctx (context)
        config @(:config ctx)
        listed (atom [{:name "a" :inputSchema {:type "object" :properties {:a/b {:type "string"}}}}])
        id (connected! ctx (registry/package-entry "npm:fake@1.0.0") {:values []})
        fake (atom nil)]
    (with-redefs [servers/transport! (fn [_ _ _]
                                       (reset! fake (h/fake-stdio-mcp! {:tools #(deref listed)}))
                                       (:transport @fake))]
      (try
        (is (nil? (servers/cached-tools config id)))
        (testing "refreshed after a call once an hour old (never before)"
          (servers/call! ctx id "a" {})
          (is (h/eventually 5000 #(= ["a"] (map :name (servers/cached-tools config id)))))
          (is (= {:type "object" :properties {:a/b {:type "string"}}} (:inputSchema (first (servers/cached-tools config id))))
              "as the server sent them")
          (is (= {:name "fake" :version "1.0"} (:server (servers/cached config id)))))
        (testing "and when the server says they changed"
          (reset! listed [{:name "b"}])
          (let [client (get-in @(:servers ctx) [:clients id :client])]
            ((get-in client [:opts :on-tools-changed])))
          (is (h/eventually 5000 #(= ["b"] (map :name (servers/cached-tools config id))))))
        (testing "forgotten"
          (servers/forget-tools! config id)
          (is (nil? (servers/cached-tools config id))))
        (is (not (str/blank? id)))
        (finally
          (servers/stop-all! ctx))))))
