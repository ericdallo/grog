(ns grog.mcp-connect-test
  "Connecting MCP servers of the catalog through sessions, against fakes:
  a package through a faked runner, a remote, a remote signing in."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.catalog :as catalog]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.connections :as connections]
            [grog.integrations :as integrations]
            [grog.mcp-connect :as mcp-connect]
            [grog.oauth :as oauth]
            [grog.registry :as registry]
            [grog.servers :as servers]
            [grog.test-helpers :as h]))

(def ^:private relay "https://relay.example/oauth/")

(defn ^:private context [& [runner]]
  (assistant/new-context (-> config/defaults
                             (assoc :state-dir (h/temp-dir))
                             (update :integrations merge {:host "127.0.0.1" :port 0 :relay relay :runner runner}))))

(def ^:private tools
  [{:name "forecast" :description "The forecast." :inputSchema {:type "object"}}
   {:name "alerts" :inputSchema {:type "object"}}])

(defn ^:private session [ctx]
  (connect/view @(:connect ctx)))

(defn ^:private status-becomes [ctx status]
  (h/eventually 10000 #(= status (:status (session ctx)))))

(defn ^:private submit! [ctx what body]
  ((get-in @(:connect ctx) [:handlers :submit what]) body))

(def ^:private weather
  (-> (registry/package-entry "npm:@fake/weather@1.0.0")
      (assoc-in [:packages 0 :environmentVariables]
                [{:name "API_KEY" :isSecret true :isRequired true}
                 {:name "UNITS" :choices ["metric" "imperial"] :default "metric"}])))

(deftest connecting-a-package
  (let [ctx (context "/run/grog-mcp/runner.sock")
        config @(:config ctx)
        dir (config/state-dir config)
        launched (atom [])
        id (catalog/add! config weather)]
    (with-redefs [servers/transport! (fn [_ launch _]
                                       (swap! launched conj launch)
                                       (:transport (h/fake-stdio-mcp! {:tools tools :server-info {:name "weather-mcp"}})))]
      (try
        (testing "its integration"
          (is (= {:id "fake-weather" :title "@fake/weather" :heading "Connect @fake/weather"
                  :about "an MCP server the user added" :multiple false :mcp true :added true :cloud false
                  :connections []}
                 (select-keys (last (integrations/summary config))
                              [:id :title :heading :about :multiple :mcp :added :cloud :connections]))))
        (testing "a form first, its secret marked so"
          (let [{:keys [step]} (integrations/open! ctx id nil)]
            (is (= "form" (:kind step)))
            (is (= [{:id "f1" :label "API_KEY" :secret true}
                    {:id "f2" :label "UNITS" :default "metric" :type "choice"
                     :choices [{:value "metric" :label "metric"} {:value "imperial" :label "imperial"}]}]
                   (:fields step)))))
        (testing "then grog starts it, with what was entered"
          (submit! ctx "form" {:f1 "s3cret" :f2 "imperial"})
          (is (status-becomes ctx "done"))
          (is (= "weather-mcp" (:connected (session ctx))))
          (is (= {"API_KEY" "s3cret" "UNITS" "imperial"} (get-in (first @launched) [:request :env]))))
        (testing "connected, its secrets apart"
          (is (= {:id id :integration id :name "weather-mcp" :status "connected"}
                 (dissoc (connections/by-id dir id) :added-at)))
          (is (= [{:kind "env" :name "API_KEY" :value "s3cret"} {:kind "env" :name "UNITS" :value "imperial"}]
                 (:values (connections/secrets dir id))))
          (is (not (str/includes? (slurp (connections/file dir)) "s3cret")))
          (is (= ["forecast" "alerts"] (map :name (servers/cached-tools config id))))
          (is (= [{:id id :name "weather-mcp" :status "connected" :detail "2 tools"}]
                 (map #(dissoc % :added-at) (:connections (last (integrations/summary config)))))))
        (testing "nothing left running: it starts on first use"
          (is (= #{} (servers/running ctx))))
        (testing "removed, it's disconnected and forgotten"
          (mcp-connect/remove! ctx id)
          (is (nil? (connections/by-id dir id)))
          (is (nil? (connections/secrets dir id)))
          (is (nil? (servers/cached-tools config id)))
          (is (nil? (catalog/entry config id))))
        (finally
          (connect/stop! ctx))))))

(deftest a-package-without-the-runner
  (let [ctx (context)
        id (catalog/add! @(:config ctx) (registry/package-entry "npm:plain@1.0.0"))]
    (try
      (integrations/open! ctx id nil)
      (is (status-becomes ctx "failed"))
      (is (str/includes? (:error (session ctx)) "runner isn't set"))
      (finally
        (connect/stop! ctx)))))

(deftest connecting-a-remote
  (let [server (h/start-mcp! {:tools tools})
        ctx (context)
        config @(:config ctx)
        id (catalog/add! config (registry/url-entry (:url server)))]
    (try
      (is (false? (:cloud (last (integrations/summary config)))) "on loopback: at home")
      (integrations/open! ctx id nil)
      (is (status-becomes ctx "done"))
      (is (= "fake" (:connected (session ctx))))
      (is (= ["forecast" "alerts"] (map :name (servers/cached-tools config id))))
      (finally
        (connect/stop! ctx)
        ((:stop! server))))))

(defn ^:private query [url]
  (oauth/form-decode (second (str/split url #"\?" 2))))

(defn ^:private come-back!
  "The browser coming back to grog with `params`, through the relay page."
  [ctx params]
  (:body (http/get (str "http://" (connect/address @(:connect ctx)) "/c/callback?" (oauth/form-encode params)))))

(deftest signing-in-to-a-remote
  (let [server (h/start-mcp! {:tools tools :oauth {:cimd? true}})
        ctx (context)
        config @(:config ctx)
        dir (config/state-dir config)
        id (catalog/add! config (registry/url-entry (:url server)))]
    (try
      (integrations/open! ctx id nil)
      (testing "the browser signs in, grog's client ID document its client"
        (is (h/eventually 10000 #(= "oauth" (get-in (session ctx) [:step :kind]))))
        (let [url (get-in @(:connect ctx) [:step :page :url])
              {:keys [client_id redirect_uri resource state scope]} (query url)]
          (is (str/starts-with? url (str (:base server) "/authorize?")))
          (is (= [(str relay "client.json") (str relay "callback/") (:url server) "tools"]
                 [client_id redirect_uri resource scope]))
          (testing "and comes back through the relay page"
            (is (str/includes? (come-back! ctx {:code "good-code" :state state :iss (:base server)}) "Signed in"))
            (is (status-becomes ctx "done")))))
      (testing "connected with its token"
        (let [{:keys [auth clients revocation-endpoint]} (connections/secrets dir id)]
          (is (= ["at-1" "rt-1"] [(:access-token auth) (:refresh-token auth)]))
          (is (nil? clients) "grog's client ID document needs nothing kept")
          (is (= (str (:base server) "/revoke") revocation-endpoint)))
        (is (= ["forecast" "alerts"] (map :name (servers/cached-tools config id))))
        (is (some #(= "Bearer at-1" (get-in % [:headers "authorization"])) @(:requests server))))
      (testing "disconnected: its token revoked, its secrets and tools forgotten"
        (is (= {:id id :disconnected true} (integrations/disconnect! ctx id)))
        (is (some #(and (= "/revoke" (:uri %)) (str/includes? (:body %) "token=rt-1")) @(:requests server)))
        (is (nil? (connections/secrets dir id)))
        (is (nil? (servers/cached-tools config id))))
      (finally
        (connect/stop! ctx)
        ((:stop! server))))))

(deftest a-client-the-user-made
  (let [server (h/start-mcp! {:tools tools :oauth {}})
        ctx (context)
        config @(:config ctx)
        id (catalog/add! config (registry/url-entry (:url server)))]
    (try
      (integrations/open! ctx id nil)
      (testing "no way to register: a form asks for one"
        (is (h/eventually 10000 #(= "form" (get-in (session ctx) [:step :kind]))))
        (is (= ["client-id" "client-secret"] (map :id (get-in (session ctx) [:step :fields])))))
      (submit! ctx "form" {:client-id "mine" :client-secret "shh"})
      (let [{:keys [client_id state]} (query (get-in @(:connect ctx) [:step :page :url]))]
        (is (= "mine" client_id))
        (come-back! ctx {:code "good-code" :state state :iss (:base server)})
        (is (status-becomes ctx "done")))
      (is (= [{:client-id "mine" :client-secret "shh" :auth-method "client_secret_basic" :issuer (:base server) :how "own"}]
             (:clients (connections/secrets (config/state-dir config) id))))
      (finally
        (connect/stop! ctx)
        ((:stop! server))))))

(deftest a-sign-in-refused
  (let [server (h/start-mcp! {:tools tools :oauth {:dcr? true}})
        ctx (context)
        config @(:config ctx)
        id (catalog/add! config (registry/url-entry (:url server)))]
    (try
      (integrations/open! ctx id nil)
      (is (h/eventually 10000 #(= "oauth" (get-in (session ctx) [:step :kind]))))
      (let [{:keys [client_id state]} (query (get-in @(:connect ctx) [:step :page :url]))]
        (is (= "registered-client" client_id) "registered, as it can't take grog's document")
        (come-back! ctx {:code "bad-code" :state state :iss (:base server)})
        (is (status-becomes ctx "failed"))
        (is (str/includes? (:error (session ctx)) "refused")))
      (is (nil? (connections/by-id (config/state-dir config) id)))
      (is (not (fs/exists? (fs/path (config/state-dir config) "tools"))))
      (finally
        (connect/stop! ctx)
        ((:stop! server))))))
