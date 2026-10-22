(ns grog.mcp-connect
  "Connecting an MCP server of the catalog (grog.catalog), like any
  integration (grog.connect, plans/integrations.md): what it needs from
  the user in a form, if anything (its environment variables, headers, the
  variables of its address); then grog starts it (a package, through the
  runner) or reaches it (a remote), and lists its tools. A remote that wants
  grog to sign in gets MCP's authorization: its authorization server found
  (grog.mcp-auth), a client there (grog's client ID metadata document,
  dynamic registration, or one the user made, asked in a form), and the
  browser signs in, coming back through the relay page (`integrations.relay`)
  to grog on the network.

  What's connected is a connection (grog.connections) with the
  integration's id, named after the server; its secrets: {:values [{:kind
  :name :value}] (grog.catalog/values), :auth (grog.mcp-auth's),
  :clients [the client grog registered or the user made, with its
  :issuer], :revocation-endpoint}. Its tools go to grog.servers' cache.

  Adding and removing servers (`add!`, `remove!`) and searching the
  registry (`search!`) are here too."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [grog.catalog :as catalog]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.connections :as connections]
            [grog.log :as log]
            [grog.mcp :as mcp]
            [grog.mcp-auth :as mcp-auth]
            [grog.mcp-tools :as mcp-tools]
            [grog.oauth :as oauth]
            [grog.registry :as registry]
            [grog.servers :as servers]))

;; How long the screen's texts of an entry the user added get.
(def ^:private title-max 60)

(defn ^:private short-text [s n]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if (> (count s) n) (str (subs s 0 (dec n)) "…") s)))

(defn ^:private named
  "What got connected is called: the server's own title or name, else the
  entry's."
  [client entry]
  (let [{:keys [title name]} (:server-info (mcp/info client))]
    (short-text (or (not-empty title) (not-empty name) (catalog/title entry)) title-max)))

(defn ^:private finish!
  "Connects to `launch` with `headers`, lists its tools, and keeps the
  connection with `secrets`, unless the session `generation` is over: what
  got connected."
  [ctx generation entry launch secrets headers]
  (let [config @(:config ctx)
        id (catalog/id-of entry)
        client (servers/connect! config launch {:headers headers})]
    (try
      (let [tools (mcp/tools client)
            name (named client entry)]
        (when-not (connect/change! ctx generation identity)
          (throw (ex-info "it's over: start again" {:type ::over})))
        (servers/stop! ctx id)
        (connections/save! (config/state-dir config) {:id id :integration id :name name} secrets)
        (servers/save-tools! config id client tools)
        (log/info (format "mcp-connect %s: connected, %d tools" id (count tools)))
        name)
      (finally
        (mcp/close! client)))))

(defn ^:private sign-in!
  "Takes the session `generation` through signing in at the authorization
  server `discovery` found, as `client`."
  [ctx generation entry launch values discovery client]
  (let [relay (get-in @(:config ctx) [:integrations :relay])]
    (connect/oauth! ctx generation
                    {:redirect-uri {:relay relay}
                     :authorize-url #(mcp-auth/authorize-url discovery client %)
                     :issuer (:issuer discovery)
                     :iss-required? (mcp-auth/iss-required? discovery)
                     :note "Signing in…"
                     :finish! (fn [grant]
                                (let [auth (mcp-auth/exchange! discovery client grant)]
                                  (finish! ctx generation entry launch
                                           (cond-> {:values values :auth auth}
                                             (#{"registered" "own"} (:how client)) (assoc :clients [client])
                                             (get-in discovery [:metadata "revocation_endpoint"])
                                             (assoc :revocation-endpoint (get-in discovery [:metadata "revocation_endpoint"])))
                                           (constantly {"Authorization" (str "Bearer " (:access-token auth))}))))})))

(defn ^:private authorize!
  "Takes the session `generation` through MCP's authorization, the server
  having answered `challenge`."
  [ctx generation entry launch values challenge]
  (let [config @(:config ctx)
        relay (get-in config [:integrations :relay])
        id (catalog/id-of entry)
        discovery (mcp-auth/discover! (:url launch) challenge)
        saved (some #(when (= (:issuer discovery) (:issuer %)) %)
                    (:clients (connections/secrets (config/state-dir config) id)))
        register #(mcp-auth/register! discovery {:client-id-url (str relay "client.json")
                                                 :redirect-uri (str relay "callback/")
                                                 :client %})]
    (try
      (sign-in! ctx generation entry launch values discovery (register saved))
      (catch clojure.lang.ExceptionInfo e
        (if (= ::mcp-auth/no-registration (:type (ex-data e)))
          (connect/form! ctx generation
                         {:fields [{:id "client-id" :label "Client id"
                                    :description (str "Signing in at " (:issuer discovery)
                                                      " takes a client made there for grog: its id, and its secret"
                                                      " if it has one. It sends the browser back to "
                                                      relay "callback/.")}
                                   {:id "client-secret" :label "Client secret" :secret true :optional true}]
                          :submit! #(sign-in! ctx generation entry launch values discovery
                                              (register {:client-id (% "client-id")
                                                         :client-secret (% "client-secret")}))})
          (throw e))))))

(defn ^:private attempt!
  "Connects `entry` with `values` in the session `generation`: what got
  connected, or nil when it goes on signing in."
  [ctx generation entry values]
  (let [launch (catalog/launch @(:config ctx) entry values)]
    (try
      (finish! ctx generation entry launch {:values values} nil)
      (catch clojure.lang.ExceptionInfo e
        (if (and (= :remote (:kind launch)) (= ::mcp/unauthorized (:type (ex-data e))))
          (do (authorize! ctx generation entry launch values (:challenge (ex-data e)))
              nil)
          (throw e))))))

(defn ^:private go!
  "Connects `entry` with `values` in the background: the session
  `generation` says how it goes."
  [ctx generation entry values]
  (connect/step! ctx generation {:kind "wait" :note "Connecting…"})
  (future
    (try
      (when-let [name (attempt! ctx generation entry values)]
        (connect/done! ctx generation name))
      (catch Exception e
        (log/info (format "mcp-connect %s: failed, %s" (catalog/id-of entry)
                          (name (or (:type (ex-data e)) (.getSimpleName (class e))))))
        (connect/fail! ctx generation (or (ex-message e) (.getSimpleName (class e))))))))

(defn ^:private start!
  "Starts connecting `entry` in the session `generation`: a form first,
  when it needs something."
  [ctx generation entry]
  (let [inputs (catalog/inputs @(:config ctx) entry)]
    (if (seq inputs)
      (connect/form! ctx generation {:fields (catalog/fields inputs)
                                     :submit! #(go! ctx generation entry (catalog/values inputs %))})
      (go! ctx generation entry []))))

(defn ^:private revoke!
  "Asks the authorization server to forget the tokens in `secrets`, if it
  can: best effort."
  [{:keys [auth revocation-endpoint]}]
  (when-let [token (and revocation-endpoint (or (:refresh-token auth) (:access-token auth)))]
    (try
      (http/post revocation-endpoint
                 {:headers {"content-type" "application/x-www-form-urlencoded"}
                  :body (oauth/form-encode (cond-> {"token" token "client_id" (:client-id auth)}
                                             (:client-secret auth) (assoc "client_secret" (:client-secret auth))))
                  :throw false
                  :timeout 5000})
      (catch Exception e
        (log/info "mcp-connect: revoking failed:" (.getSimpleName (class e)))))))

(defn disconnect!
  "Disconnects connection `id`: stops its server, revokes its tokens when
  its authorization server can, forgets its secrets and its tools."
  [ctx id]
  (let [config @(:config ctx)
        dir (config/state-dir config)]
    (servers/stop! ctx id)
    (some-> (connections/secrets dir id) revoke!)
    (connections/remove! dir id)
    (servers/forget-tools! config id)
    (log/info "mcp-connect" id "disconnected")
    {:id id :disconnected true}))

(defn ^:private connections-of
  "The connection of integration `id`, if there's one."
  [config id]
  (if-let [c (connections/by-id (config/state-dir config) id)]
    (let [n (count (servers/cached-tools config id))]
      [(assoc (select-keys c [:id :name :status :added-at]) :detail (str n (if (= 1 n) " tool" " tools")))])
    []))

(defn ^:private needs-form? [config entry]
  (boolean (seq (try (catalog/inputs config entry) (catch Exception _ nil)))))

(defn integration
  "The integration connecting `entry` (grog.integrations), as `config`
  runs it. What the user added only shows its title, cut short: what's in
  it isn't grog's."
  [config entry]
  (let [id (catalog/id-of entry)
        trusted? (catalog/trusted? entry)
        {:keys [about icon]} (when trusted? (catalog/grog-meta entry))
        title (short-text (catalog/title entry) title-max)
        how (try (catalog/how config entry) (catch Exception _ nil))]
    {:id id
     :title title
     :heading (str "Connect " title)
     :about (cond (not trusted?) "an MCP server the user added"
                  about about
                  :else (short-text (or (:description entry) title) 100))
     :icon (or icon "plug")
     :ways [{:id "connect" :label "Connect"}]
     :network? (or (= :remote how) (needs-form? config entry))
     :multiple? false
     :mcp? true
     :added? (not trusted?)
     :cloud? (boolean (and how (catalog/cloud? config entry)))
     :start! (fn [ctx {:keys [generation]} _] (start! ctx generation entry))
     :connections #(connections-of % id)
     :disconnect! disconnect!}))

(defn integrations
  "The integrations of the catalog's entries with `config`."
  [config]
  (mapv #(integration config %) (catalog/entries config)))

(defn search!
  "Servers of the official MCP Registry whose name holds `text`
  (grog.registry/search!)."
  [text]
  (registry/search! registry/default-url text))

(defn add!
  "Adds the server `what` says: {:name} in the official MCP Registry,
  {:package \"npm:name@1.2.3\"|\"pypi:name==1.2.3\"} or {:url}. Returns its
  integration id. Throws ex-info saying why it can't."
  [config {:keys [name package url]}]
  (let [entry (cond
                (not (str/blank? name)) (registry/fetch! registry/default-url name)
                (not (str/blank? package)) (registry/package-entry package)
                (not (str/blank? url)) (registry/url-entry url)
                :else (throw (ex-info "Say which server: its name in the MCP Registry, a package or an address."
                                      {:type ::nothing})))]
    (catalog/add! config entry)))

(defn remove!
  "Removes the server the user added as `id`, disconnecting it first, and
  forgetting their choices for its tools. Throws ex-info when it's not one
  they added."
  [ctx id]
  (let [config @(:config ctx)
        entry (catalog/entry config id)]
    (if-not (and entry (catalog/added? entry))
      (catalog/remove! config id)
      (do (when (connections/by-id (config/state-dir config) id)
            (disconnect! ctx id))
          (mcp-tools/forget! config id)
          (catalog/remove! config id)))))
