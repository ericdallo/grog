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

  An entry saying how it connects (its _meta's connect, grog.catalog/ways:
  Home Assistant) goes its own way instead, each way a list of steps, run in
  order, each adding to the variables the next ones and the entry's
  {templates} use:

    discover  {service field}: what answers on the network as `service`
              (grog.mdns), the first one found the default of the next
              form's `field`
    form      {fields}: fields to fill in (grog.connect/form!), their values
              the variables of their ids; those of format uri, addresses
              (http or https, without a trailing /)
    oauth     {authorize token revoke client scope}: signing in at an
              authorization server on the network (client \"lan\": grog's
              address there its client id, coming back to its page there),
              last

  and the way's headers (\"Bearer {token}\") filled in. Then grog reaches the
  first of the entry's remotes that's there (a 404: the next), and says
  what the entry's errors say when it can't.

  What's connected is a connection (grog.connections) with the
  integration's id, named after the server; its secrets: {:values [{:kind
  :name :value}] (grog.catalog/values), :auth (grog.mcp-auth's),
  :clients [the client grog registered or the user made, with its
  :issuer], :revocation-endpoint}. One connected through its ways: {:way
  :url (the remote that answered) :headers :auth :revocation-endpoint}, as
  they apply. Its tools go to grog.servers' cache.

  Adding and removing servers (`add!`, `remove!`) and searching the
  registry (`search!`) are here too."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.catalog :as catalog]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.connections :as connections]
            [grog.log :as log]
            [grog.mcp :as mcp]
            [grog.mcp-auth :as mcp-auth]
            [grog.mcp-tools :as mcp-tools]
            [grog.mdns :as mdns]
            [grog.oauth :as oauth]
            [grog.registry :as registry]
            [grog.servers :as servers])
  (:import (java.net URI)))

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

(defn ^:private keep!
  "Lists the tools of `client`, connected to `entry`, and keeps the
  connection, called `called`, with `secrets`, unless the session
  `generation` is over: what got connected. Closes the client."
  [ctx generation entry client called secrets]
  (let [config @(:config ctx)
        id (catalog/id-of entry)]
    (try
      (let [tools (mcp/tools client)]
        (when-not (connect/change! ctx generation identity)
          (throw (ex-info "it's over: start again" {:type ::over})))
        (servers/stop! ctx id)
        (connections/save! (config/state-dir config) {:id id :integration id :name called} secrets)
        (servers/save-tools! config id client tools)
        (log/info (format "mcp-connect %s: connected, %d tools" id (count tools)))
        called)
      (finally
        (mcp/close! client)))))

(defn ^:private finish!
  "Connects to `launch` with `headers`, lists its tools, and keeps the
  connection with `secrets`, unless the session `generation` is over: what
  got connected."
  [ctx generation entry launch secrets headers]
  (let [client (servers/connect! @(:config ctx) launch {:headers headers})]
    (keep! ctx generation entry client (named client entry) secrets)))

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

(defn ^:private failed!
  "The session `generation`, connecting `entry`, failed with `e`."
  [ctx generation entry e]
  (log/info (format "mcp-connect %s: failed, %s" (catalog/id-of entry)
                    (name (or (:type (ex-data e)) (.getSimpleName (class e))))))
  (connect/fail! ctx generation (or (ex-message e) (.getSimpleName (class e)))))

(defn ^:private go!
  "Connects `entry` in the background with (f), which returns what got
  connected, or nil when it goes on signing in: the session `generation`
  says how it goes."
  [ctx generation entry f]
  (connect/step! ctx generation {:kind "wait" :note "Connecting…"})
  (future
    (try
      (when-let [connected (f)]
        (connect/done! ctx generation connected))
      (catch Exception e
        (failed! ctx generation entry e)))))

(defn ^:private start!
  "Starts connecting `entry` in the session `generation`: a form first,
  when it needs something."
  [ctx generation entry]
  (let [inputs (catalog/inputs @(:config ctx) entry)]
    (if (seq inputs)
      (connect/form! ctx generation {:fields (catalog/fields inputs)
                                     :submit! #(let [values (catalog/values inputs %)]
                                                 (go! ctx generation entry (fn [] (attempt! ctx generation entry values))))})
      (go! ctx generation entry #(attempt! ctx generation entry [])))))

(def discover-ms
  "How long a discover step looks on the network. Tests redefine it."
  2000)

(defn ^:private said
  "What `entry` says to say when it answers `status`, or nil."
  [entry status]
  (when (catalog/trusted? entry)
    (get-in (catalog/grog-meta entry) [:errors (keyword (str status))])))

(defn ^:private origin-of [url]
  (let [uri (URI. url)]
    (str (.getScheme uri) "://" (.getRawAuthority uri))))

(defn ^:private reach!
  "Connects to the first of `entry`'s remotes, filled in from `vars`, that's
  there (a 404: the next), sending `headers` and the token of `auth`:
  [client url]. Throws ex-info saying why it can't."
  [config entry vars headers auth]
  (let [title (catalog/title entry)
        token (when auth (constantly {"Authorization" (str "Bearer " (:access-token auth))}))]
    (loop [[url & more] (catalog/remote-urls entry vars)]
      (registry/url-entry url)
      (let [outcome (try {:client (servers/connect! config {:kind :remote :url url :headers headers} {:headers token})}
                         (catch clojure.lang.ExceptionInfo e {:error e}))
            e (:error outcome)
            {:keys [type status]} (ex-data e)]
        (cond
          (:client outcome) [(:client outcome) url]
          (and (= 404 status) (seq more)) (recur more)
          (= 404 status) (throw (ex-info (or (said entry 404) (str title " has no MCP server at " url))
                                         {:type ::not-found}))
          (or (= 403 status) (= ::mcp/forbidden type)) (throw (ex-info (or (said entry 403) (ex-message e))
                                                                       {:type ::forbidden}))
          (= ::mcp/unauthorized type) (throw (ex-info (str title " refused the sign-in: try again") {:type ::refused}))
          (= ::mcp/unreachable type) (throw (ex-info (str "grog can't reach " (origin-of url) ": is it on?")
                                                     {:type ::unreachable}))
          :else (throw e))))))

(defn ^:private called
  "What `entry` says got connected is called: the field its name says of
  the JSON at its address, asked with `headers`, or nil."
  [entry vars headers]
  (when-let [{:keys [url field]} (when (catalog/trusted? entry) (:name (catalog/grog-meta entry)))]
    (try
      (let [resp (http/get (catalog/template url vars) {:headers (assoc headers "Accept" "application/json")
                                                        :throw false
                                                        :timeout 5000})
            body (when (= 200 (:status resp)) (json/parse-string (:body resp) true))
            v (when (map? body) (get body (keyword field)))]
        (when (and (string? v) (not (str/blank? v)))
          (short-text v title-max)))
      (catch Exception e
        (log/info "mcp-connect: asking its name failed:" (.getSimpleName (class e)))
        nil))))

(defn ^:private connect-way!
  "Connects `entry` as `way` went, with what its steps gathered (`got`:
  {:vars :auth :revoke}): what got connected."
  [ctx generation entry way {:keys [vars auth revoke]}]
  (let [headers (into {} (for [[k v] (:headers way)] [(name k) (catalog/template v vars)]))
        [client url] (reach! @(:config ctx) entry vars headers auth)
        asking (cond-> headers auth (assoc "Authorization" (str "Bearer " (:access-token auth))))]
    (keep! ctx generation entry client
           (or (called entry vars asking) (catalog/title entry))
           (cond-> {:way (:id way) :url url}
             (seq headers) (assoc :headers headers)
             auth (assoc :auth auth)
             revoke (assoc :revocation-endpoint revoke)))))

(defn ^:private web-address
  "`s`, an address entered or found, trimmed, without its trailing /.
  Throws ex-info, saying it of `label`, when it isn't http or https."
  [label s]
  (let [s (str/replace (str/trim (str s)) #"/+$" "")
        uri (try (URI. s) (catch Exception _ nil))]
    (if (and uri (#{"http" "https"} (some-> (.getScheme uri) str/lower-case)) (not (str/blank? (.getHost uri))))
      s
      (throw (ex-info (str label ": an address starting with http:// or https://") {:type ::bad-address})))))

(defn ^:private found
  "Where the instances found on the network are, the first first:
  [{:name :url}]: their own address inside the home, else the one they
  say, else their first IPv4 address and port."
  [instances]
  (vec (keep (fn [{:keys [txt addresses port] :as instance}]
               (let [txt-of #(some-> (or (get txt %) (get txt (keyword %))) str str/trim not-empty)
                     ipv4 (first (filter #(re-matches #"\d+\.\d+\.\d+\.\d+" (str %)) addresses))
                     url (or (txt-of "internal_url") (txt-of "base_url")
                             (when (and ipv4 port) (str "http://" ipv4 ":" port)))
                     url (try (web-address "" url) (catch Exception _ nil))]
                 (when url
                   {:name (or (txt-of "location_name") (:name instance) (:instance instance)) :url url})))
             instances)))

(defn ^:private found-text [places]
  (str "Found " (str/join ", " (for [{:keys [name url]} (take 3 places)] (str name " at " url)))))

(declare run-steps!)

(defn ^:private discover-step!
  "Looks for `service` on the network, then goes on with the steps `more`,
  what it found the default of the next form's `field`."
  [ctx generation entry way {:keys [service field]} more got]
  (connect/step! ctx generation {:kind "wait" :note (str "Looking for " (catalog/title entry) " on the network…")})
  (future
    (try
      (let [places (found (mdns/browse! service {:timeout-ms discover-ms}))]
        (log/info (format "mcp-connect %s: %d found on the network" (catalog/id-of entry) (count places)))
        (run-steps! ctx generation entry way more
                    (cond-> got
                      (seq places) (assoc-in [:hints field] {:default (:url (first places))
                                                             :description (found-text places)}))))
      (catch Exception e
        (failed! ctx generation entry e)))))

(defn ^:private form-step!
  "Asks for `fields`, then goes on with the steps `more`, their values
  among the variables."
  [ctx generation entry way {:keys [fields]} more got]
  (let [fields (mapv #(merge % (get-in got [:hints (:id %)])) fields)
        uri? (set (keep #(when (= "uri" (:format %)) (:id %)) fields))
        labels (into {} (map (juxt :id :label)) fields)]
    (connect/form! ctx generation
                   {:fields fields
                    :submit! (fn [values]
                               (let [values (into {} (for [[k v] values]
                                                       [k (if (uri? k) (web-address (labels k) v) v)]))]
                                 (run-steps! ctx generation entry way more (update got :vars merge values))))})))

(defn ^:private lan-client-id
  "grog's client id at an authorization server on the network: its own
  address there, where `redirect-uri` comes back to."
  [redirect-uri]
  (let [uri (URI. redirect-uri)]
    (str (.getScheme uri) "://" (.getRawAuthority uri) "/")))

(defn ^:private oauth-step!
  "Signs in at the authorization server on the network the step says, then
  connects."
  [ctx generation entry way {:keys [authorize token revoke client scope]} more got]
  (let [title (catalog/title entry)
        vars (:vars got)
        endpoint #(when % (doto (catalog/template % vars) registry/url-entry))
        [authorize token revoke] (map endpoint [authorize token revoke])]
    (when-not (= "lan" client)
      (throw (ex-info (str "grog can't sign in to " title " that way") {:type ::unknown-client})))
    (when (seq more)
      (throw (ex-info (str "signing in to " title " comes last") {:type ::unknown-step})))
    (connect/oauth! ctx generation
                    {:redirect-uri :lan
                     :authorize-url #(mcp-auth/authorize-url {:metadata {"authorization_endpoint" authorize} :scope scope}
                                                             {:client-id (lan-client-id (:redirect-uri %))}
                                                             %)
                     :note "Signing in…"
                     :finish! (fn [grant]
                                (let [auth (mcp-auth/exchange! {:metadata {"token_endpoint" token} :scope scope}
                                                               {:client-id (lan-client-id (:redirect-uri grant))
                                                                :auth-method "none"}
                                                               grant)]
                                  (connect-way! ctx generation entry way (assoc got :auth auth :revoke revoke))))})))

(defn ^:private run-steps!
  "Takes the session `generation` through `steps` of `way`, what those
  before gathered in `got` ({:vars :hints}), then connects."
  [ctx generation entry way steps got]
  (let [[step & more] steps]
    (case (:step step)
      nil (go! ctx generation entry #(connect-way! ctx generation entry way got))
      "discover" (discover-step! ctx generation entry way step more got)
      "form" (form-step! ctx generation entry way step more got)
      "oauth" (oauth-step! ctx generation entry way step more got)
      (throw (ex-info (str "grog can't " (:step step) " yet") {:type ::unknown-step})))))

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
        how (try (catalog/how config entry) (catch Exception _ nil))
        ways (catalog/ways entry)]
    {:id id
     :title title
     :heading (str "Connect " title)
     :about (cond (not trusted?) "an MCP server the user added"
                  about about
                  :else (short-text (or (:description entry) title) 100))
     :icon (or icon "plug")
     :ways (if ways (mapv #(select-keys % [:id :label]) ways) [{:id "connect" :label "Connect"}])
     :network? (boolean (or ways (= :remote how) (needs-form? config entry)))
     :multiple? false
     :mcp? true
     :added? (not trusted?)
     :cloud? (boolean (and how (catalog/cloud? config entry)))
     :start! (fn [ctx {:keys [generation]} way-id]
               (if-let [way (some #(when (= way-id (:id %)) %) ways)]
                 (run-steps! ctx generation entry way (:steps way) {:vars {}})
                 (start! ctx generation entry)))
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
