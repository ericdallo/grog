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
            [grog.log :as log]
            [grog.mcp :as mcp]
            [grog.mcp-connect :as mcp-connect]
            [grog.mdns :as mdns]
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

(defn ^:private ha-instance
  "Home Assistant as grog.mdns finds it, its TXT as `txt` says."
  [txt]
  {:instance "Home._home-assistant._tcp.local" :name "Home" :host "abc.local." :port 8123
   :addresses ["192.168.15.50" "fe80::1"] :txt (merge {"location_name" "Home" "uuid" "abc" "version" "2026.10.0"} txt)})

(defn ^:private finding
  "A fake grog.mdns/browse! finding `instances` as `_home-assistant._tcp`."
  [instances]
  (fn [service {:keys [timeout-ms]}]
    (is (= ["_home-assistant._tcp" 2000] [service timeout-ms]))
    instances))

(defn ^:private ha-step [ctx kind]
  (h/eventually 10000 #(= kind (get-in (session ctx) [:step :kind]))))

(defn ^:private ha-integration [config]
  (some #(when (= "home-assistant" (:id %)) %) (integrations/summary config)))

(deftest home-assistant-ways
  (let [ctx (context)
        config @(:config ctx)]
    (try
      (testing "shipped with grog: its ways, signing in first"
        (is (= {:id "home-assistant" :title "Home Assistant" :heading "Connect Home Assistant"
                :about "the lights and the rest of the home" :icon "home"
                :ways [{:id "sign-in" :label "Sign in to Home Assistant"}
                       {:id "token" :label "Paste a long-lived token instead"}]
                :multiple false :mcp true :added false :cloud false :connections []}
               (ha-integration config))))
      (testing "it looks on the network first"
        (let [release (promise)]
          (with-redefs [mdns/browse! (fn [_ _] @release [])]
            (is (= {:kind "wait" :note "Looking for Home Assistant on the network…"}
                   (:step (integrations/open! ctx "home-assistant" nil))))
            (deliver release true)
            (testing "then asks for its address, nothing found"
              (is (ha-step ctx "form"))
              (is (= [{:id "url" :label "Its address" :example "http://homeassistant.local:8123" :format "uri"}]
                     (get-in (session ctx) [:step :fields])))))))
      (testing "what it found filled in"
        (with-redefs [mdns/browse! (finding [(ha-instance {"internal_url" "http://homeassistant.local:8123/"})
                                             (assoc (ha-instance {"internal_url" "" "base_url" ""}) :name "Cabin")
                                             (ha-instance {"location_name" "Old"})])]
          (integrations/open! ctx "home-assistant" "token")
          (is (ha-step ctx "form"))
          (is (= [{:id "url" :label "Its address" :example "http://homeassistant.local:8123" :format "uri"
                   :default "http://homeassistant.local:8123"
                   :description (str "Found Home at http://homeassistant.local:8123, Home at http://192.168.15.50:8123, "
                                     "Old at http://192.168.15.50:8123")}
                  {:id "token" :label "A long-lived access token (in Home Assistant: your profile, Security)" :secret true}]
                 (get-in (session ctx) [:step :fields])))
          (testing "an address that isn't one"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Its address: an address starting with http:// or https://"
                                  (submit! ctx "form" {:url "ftp://homeassistant.local" :token "t"}))))))
      (finally
        (connect/stop! ctx)))))

(defn ^:private browse-to!
  "The browser at `url`, Home Assistant's sign-in, which sends it on to
  grog's page on the network: what grog shows it."
  [url]
  (let [resp (http/get url {:client (http/client {:follow-redirects :never}) :throw false})
        location (get-in resp [:headers "location"])]
    (is (= 302 (:status resp)))
    (:body (http/get location))))

(deftest signing-in-to-home-assistant
  (let [ha (h/start-ha!)
        ctx (context)
        config @(:config ctx)
        dir (config/state-dir config)
        logged (atom [])
        id "home-assistant"]
    (with-redefs [mdns/browse! (finding [(ha-instance {"internal_url" (str (:url ha) "/")})])
                  log/info (fn [& parts] (swap! logged conj (str/join " " parts)))]
      (try
        (integrations/open! ctx id nil)
        (testing "its address found, then entered"
          (is (ha-step ctx "form"))
          (is (= (:url ha) (get-in (session ctx) [:step :fields 0 :default])))
          (submit! ctx "form" {:url (str " " (:url ha) "/ ")}))
        (testing "signing in at Home Assistant, grog's address on the network its client"
          (is (ha-step ctx "oauth"))
          (let [url (get-in @(:connect ctx) [:step :page :url])
                grog (str "http://" (connect/address @(:connect ctx)) "/")
                {:keys [client_id redirect_uri code_challenge_method] :as q} (query url)]
            (is (str/starts-with? url (str (:url ha) "/auth/authorize?")))
            (is (= [grog (str grog "c/callback") "S256"] [client_id redirect_uri code_challenge_method]))
            (is (not (contains? q :resource)))
            (is (str/includes? (browse-to! url) "Signed in"))
            (is (status-becomes ctx "done"))
            (is (= "Home" (:connected (session ctx))))
            (testing "connected, called as Home Assistant calls the home"
              (is (= {:id id :integration id :name "Home" :status "connected"}
                     (dissoc (connections/by-id dir id) :added-at))))
            (testing "its tokens apart, with the address that answered"
              (let [{:keys [auth] :as secrets} (connections/secrets dir id)]
                (is (= {:way "sign-in" :url (str (:url ha) "/api/mcp/assist")
                        :revocation-endpoint (str (:url ha) "/auth/revoke")}
                       (dissoc secrets :auth)))
                (is (= {:token-endpoint (str (:url ha) "/auth/token") :client-id grog :auth-method "none"
                        :access-token "at-1" :refresh-token "rt-1"}
                       (select-keys auth [:token-endpoint :client-id :auth-method :access-token :refresh-token])))
                (is (< (System/currentTimeMillis) (:expires-at auth)))))))
        (testing "its tools kept, nothing secret in integrations.json or the logs"
          (is (= (map :name h/ha-tools) (map :name (servers/cached-tools config id))))
          (is (not-any? #(str/includes? (slurp (connections/file dir)) %) ["at-1" "rt-1" "code-"]))
          (is (seq @logged))
          (is (not-any? #(re-find #"at-1|rt-1|code-1|Kitchen" %) @logged)))
        (testing "a call later, to the address that answered, with its token"
          (is (str/includes? (mcp/text-of (servers/call! ctx id "intent__HassTurnOn" {:name "Kitchen"})) "success"))
          (is (= [{:uri "/api/mcp/assist" :token "at-1" :name "intent__HassTurnOn" :arguments {:name "Kitchen"}}]
                 @(:calls ha))))
        (testing "its token refreshed once it expires, keeping the refresh token"
          (servers/stop-all! ctx)
          (let [secrets (connections/secrets dir id)]
            (connections/save! dir (connections/by-id dir id) (assoc-in secrets [:auth :expires-at] 0)))
          (servers/call! ctx id "intent__HassTurnOff" {:name "Kitchen"})
          (is (= "at-2" (:token (last @(:calls ha)))))
          (let [{:keys [auth]} (connections/secrets dir id)]
            (is (= ["at-2" "rt-1"] [(:access-token auth) (:refresh-token auth)])))
          (let [refreshed (last (filter #(= "/auth/token" (:uri %)) @(:requests ha)))]
            (is (= {:grant_type "refresh_token" :refresh_token "rt-1" :client_id (get-in (connections/secrets dir id) [:auth :client-id])}
                   (oauth/form-decode (:body refreshed))))))
        (testing "disconnected: its refresh token revoked, the rest forgotten"
          (is (= {:id id :disconnected true} (integrations/disconnect! ctx id)))
          (is (= ["rt-1"] @(:revoked ha)))
          (is (nil? (connections/secrets dir id)))
          (is (nil? (servers/cached-tools config id))))
        (finally
          (servers/stop-all! ctx)
          (connect/stop! ctx)
          ((:stop! ha)))))))

(defn ^:private with-token!
  "Connects Home Assistant at `url` with `token`, nothing found on the
  network: the session once it's over."
  [ctx url token]
  (with-redefs [mdns/browse! (finding [])]
    (integrations/open! ctx "home-assistant" "token")
    (is (ha-step ctx "form"))
    (submit! ctx "form" {:url url :token token})
    (h/eventually 10000 #(#{"done" "failed"} (:status (session ctx))))
    (session ctx)))

(deftest a-long-lived-token
  (let [ha (h/start-ha!)
        ctx (context)
        config @(:config ctx)
        dir (config/state-dir config)
        id "home-assistant"]
    (try
      (testing "connected with it"
        (is (= ["done" "Home"] ((juxt :status :connected) (with-token! ctx (:url ha) "llat-1"))))
        (is (= {:way "token" :url (str (:url ha) "/api/mcp/assist") :headers {:Authorization "Bearer llat-1"}}
               (connections/secrets dir id)))
        (is (not (str/includes? (slurp (connections/file dir)) "llat-1"))))
      (testing "and called with it"
        (servers/call! ctx id "light__HassLightSet" {:name "Kitchen" :brightness 40})
        (is (= [{:uri "/api/mcp/assist" :token "llat-1" :name "light__HassLightSet"
                 :arguments {:name "Kitchen" :brightness 40}}]
               @(:calls ha))))
      (testing "an older Home Assistant: its other address"
        (servers/stop-all! ctx)
        (reset! (:switches ha) #{:no-assist})
        (is (= "done" (:status (with-token! ctx (:url ha) "llat-1"))))
        (is (= (str (:url ha) "/api/mcp") (:url (connections/secrets dir id))))
        (is (= ["HassTurnOn" "HassTurnOff" "HassLightSet" "GetLiveContext"] (map :name (servers/cached-tools config id))))
        (servers/call! ctx id "HassTurnOn" {:name "Kitchen"})
        (is (= "/api/mcp" (:uri (last @(:calls ha))))))
      (finally
        (servers/stop-all! ctx)
        (connect/stop! ctx)
        ((:stop! ha))))))

(deftest what-home-assistant-says-no-to
  (let [ha (h/start-ha!)
        ctx (context)
        dir (config/state-dir @(:config ctx))
        failed-with (fn [url token]
                      (let [{:keys [status error]} (with-token! ctx url token)]
                        (is (= "failed" status))
                        error))]
    (try
      (testing "no MCP server set up"
        (reset! (:switches ha) #{:no-mcp})
        (is (= (str "Home Assistant has no MCP server yet: add the Model Context Protocol Server integration there "
                    "(Settings, Devices & services, Add integration), then connect again.")
               (failed-with (:url ha) "llat-1"))))
      (testing "only for its admins"
        (reset! (:switches ha) #{:no-assist :forbidden})
        (is (= "Home Assistant only lets its admins do that: sign in as an admin, or update Home Assistant."
               (failed-with (:url ha) "llat-1"))))
      (testing "a token it doesn't know"
        (reset! (:switches ha) #{})
        (is (= "Home Assistant refused the sign-in: try again" (failed-with (:url ha) "nope"))))
      (testing "nothing there"
        (let [url (str "http://127.0.0.1:" (h/free-port))]
          (is (= (str "grog can't reach " url ": is it on?") (failed-with url "llat-1")))))
      (is (nil? (connections/by-id dir "home-assistant")))
      (finally
        (connect/stop! ctx)
        ((:stop! ha))))))
