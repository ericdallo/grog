(ns grog.registry
  "MCP servers the user adds (plans/integrations.md, the catalog): found in
  the official MCP Registry (`search!`, `fetch!`), or given as a package
  (`package-entry`) or an address (`url-entry`). Each is a `server.json`,
  as the registry has it: parsed JSON, keyword keys, no case conversion.

  What grog can run of one (`runnable`):

    a package   from npm, run with npx, or from PyPI, run with uvx, over
                stdio, its version pinned (\"1.2.3\", not \"latest\" nor
                \"^1.2\"), from the public registry
    a remote    over streamable HTTP

  Not OCI images, NuGet, MCPB bundles or SSE remotes. The registry speaks
  its v0.1 API:

    GET /v0.1/servers?search=&version=latest&limit=   its servers, by name
    GET /v0.1/servers/<name>/versions/latest          one's latest version,
                                                      its name URL-encoded

  Both answer servers as {\"server\": its server.json, \"_meta\":
  {\"io.modelcontextprotocol.registry/official\": {\"status\" \"isLatest\"
  ...}}}, the list in {\"servers\" [...], \"metadata\": {\"nextCursor\"
  \"count\"}}."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.log :as log])
  (:import (java.net URI URLEncoder)))

(def default-url "https://registry.modelcontextprotocol.io")

;; How long reaching the registry may take.
(def ^:private connect-timeout-ms 10000)

;; How long fetching a server may take.
(def ^:private fetch-timeout-ms 10000)

;; How long a search may take: the registry searches slowly, often taking
;; 20 s or more.
(def ^:private search-timeout-ms 30000)

;; How many servers a search answers.
(def ^:private search-limit 20)

;; How long a short id may be.
(def ^:private id-max 40)

(def ^:private official-key :io.modelcontextprotocol.registry/official)

(def ^:private npm-identifier #"(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")

(def ^:private pypi-identifier #"[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?")

(def ^:private exact-version #"[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")

(def ^:private kinds
  "The packages grog can run: how they're run, and their public registry."
  {"npm" {:runtime "npx" :registry "https://registry.npmjs.org" :identifier npm-identifier}
   "pypi" {:runtime "uvx" :registry "https://pypi.org" :identifier pypi-identifier}})

(def ^:private client
  (delay (http/client (assoc-in http/default-client-opts [:connect-timeout] connect-timeout-ms))))

(defn ^:private encode
  "`s` percent-encoded for a URL's path or query."
  [s]
  (str/replace (URLEncoder/encode (str s) "UTF-8") "+" "%20"))

(defn ^:private base [url]
  (str/replace (str url) #"/+$" ""))

(defn ^:private get!
  "GETs `url`: {:status :body}, the body parsed as JSON, or nil when it
  isn't. Throws ::unreachable when the registry can't be reached."
  [url timeout-ms]
  (let [resp (try (http/get url {:client @client
                                 :headers {"accept" "application/json"}
                                 :throw false
                                 :timeout timeout-ms})
                  (catch java.io.IOException e
                    (log/info "registry: couldn't reach it" (.getName (class e)))
                    (throw (ex-info "Couldn't reach the MCP Registry." {:type ::unreachable}))))]
    {:status (:status resp)
     :body (try (json/parse-string (:body resp) true) (catch Exception _ nil))}))

(defn ^:private bad-answer [what]
  (ex-info (str "The MCP Registry answered " what " in a way that makes no sense.") {:type ::bad-answer}))

(defn ^:private pinned?
  "Whether `version` names one version: not a tag (\"latest\"), a range
  (\"^1.0\", \">=2\") or a wildcard (\"1.x\")."
  [version]
  (let [version (str version)
        core (first (str/split version #"[-+]"))]
    (boolean (and (re-matches exact-version version)
                  (re-find #"^v?[0-9]" version)
                  (not-any? #{"x" "X"} (str/split (str core) #"\."))))))

(defn ^:private package-problem
  "Why grog can't run `pkg`, or nil when it can."
  [{:keys [registryType registryBaseUrl identifier version runtimeHint transport]}]
  (let [{:keys [runtime registry] :as kind} (get kinds registryType)]
    (cond
      (= "oci" registryType) "an OCI image"
      (not kind) (str "a " (or registryType "package of no known kind") " package")
      (and runtimeHint (not= runtime runtimeHint)) (str "a " registryType " package run with " runtimeHint)
      (not= "stdio" (:type transport)) (str "a " registryType " package served over " (or (:type transport) "nothing said"))
      (and registryBaseUrl (not= registry (base registryBaseUrl))) (str "a " registryType " package from " registryBaseUrl)
      (not (re-matches (:identifier kind) (str identifier))) (str "a " registryType " package named \"" identifier "\"")
      (not (pinned? version)) (str "a " registryType " package of version \"" version "\", which isn't pinned"))))

(defn ^:private remote-problem
  "Why grog can't use `remote`, or nil when it can."
  [{:keys [type url]}]
  (cond
    (= "sse" type) "an SSE remote"
    (not= "streamable-http" type) (str "a remote over " (or type "nothing said"))
    (str/blank? url) "a remote without an address"))

(defn runnable
  "`server` (a server.json) reduced to what grog can run: its first package
  grog can run, if any, and its streamable HTTP remotes, if any. Throws
  ::unsupported saying why when there's neither."
  [server]
  (let [pkg (first (remove package-problem (:packages server)))
        remotes (vec (remove remote-problem (:remotes server)))
        problems (concat (keep package-problem (:packages server)) (keep remote-problem (:remotes server)))]
    (when-not (or pkg (seq remotes))
      (throw (ex-info (if (seq problems)
                        (str "It can't be run here: it only comes as " (str/join ", or " (distinct problems)) ".")
                        "It can't be run here: it has neither a package nor a remote.")
                      {:type ::unsupported :problems (vec (distinct problems))})))
    (cond-> (dissoc server :packages :remotes)
      pkg (assoc :packages [pkg])
      (seq remotes) (assoc :remotes remotes))))

(defn ^:private runs
  "How grog runs `server`: \"npm\", \"pypi\", \"remote\", or nil when it
  can't."
  [server]
  (when-let [s (try (runnable server) (catch clojure.lang.ExceptionInfo _ nil))]
    (or (get-in s [:packages 0 :registryType]) "remote")))

(defn ^:private listed?
  "Whether a server the registry listed is its latest version, and active.
  What it didn't say counts as yes."
  [entry]
  (let [{:keys [isLatest status]} (get-in entry [:_meta official-key])]
    (and (not (false? isLatest))
         (or (nil? status) (= "active" status)))))

(defn ^:private summary [server]
  {:name (:name server)
   :title (:title server)
   :description (:description server)
   :version (:version server)
   :runs (runs server)})

(defn search!
  "Up to 20 servers of the registry at `base-url` whose name holds `text`,
  for a list on a screen: [{:name :title :description :version :runs}],
  their latest active versions only, :runs saying how grog would run each
  (\"npm\", \"pypi\", \"remote\"), nil for those it can't. Throws
  ::unreachable or ::bad-answer."
  [base-url text]
  (let [url (str (base base-url) "/v0.1/servers?version=latest&limit=" search-limit
                 (when-not (str/blank? text) (str "&search=" (encode (str/trim text)))))
        {:keys [status body]} (get! url search-timeout-ms)]
    (when-not (and (= 200 status) (map? body) (sequential? (:servers body)))
      (throw (bad-answer "a search")))
    (->> (:servers body)
         (filter #(and (map? %) (map? (:server %)) (:name (:server %))))
         (filter listed?)
         (map (comp summary :server))
         (take search-limit)
         vec)))

(defn fetch!
  "The latest server.json of the server called `name` in the registry at
  `base-url`. Throws ::not-found, ::unreachable or ::bad-answer."
  [base-url name]
  (let [url (str (base base-url) "/v0.1/servers/" (encode name) "/versions/latest")
        {:keys [status body]} (get! url fetch-timeout-ms)]
    (cond
      (= 404 status) (throw (ex-info (str "The MCP Registry has no server called " name ".") {:type ::not-found}))
      (not= 200 status) (throw (bad-answer (str "for " name)))
      (not (and (map? body) (map? (:server body)) (string? (:name (:server body))))) (throw (bad-answer (str "for " name)))
      :else (:server body))))

(defn ^:private bad-spec [spec why]
  (ex-info (str "\"" spec "\" isn't a package to run: " why) {:type ::bad-spec}))

(defn ^:private split-spec
  "[kind identifier version] of a package spec, nil where it says none."
  [spec]
  (let [[_ kind rest] (re-matches #"(npm|pypi):(.*)" spec)]
    (case kind
      "npm" (let [at (str/last-index-of rest "@")]
              (if (and at (pos? at))
                [kind (subs rest 0 at) (subs rest (inc at))]
                [kind rest nil]))
      "pypi" (let [[identifier version] (str/split rest #"==" 2)]
               [kind identifier version])
      nil)))

(defn package-entry
  "A minimal server.json for the package `spec` names: \"npm:name@1.2.3\",
  \"npm:@scope/name@1.2.3\" or \"pypi:name==1.2.3\", its version pinned.
  Throws ::bad-spec saying why when it isn't one."
  [spec]
  (let [spec (str/trim (str spec))
        [kind identifier version] (split-spec spec)
        {:keys [runtime] :as k} (get kinds kind)]
    (cond
      (not kind) (throw (bad-spec spec "say npm:name@version or pypi:name==version."))
      (not (re-matches (:identifier k) (str identifier))) (throw (bad-spec spec (str "\"" identifier "\" isn't a " kind " package name.")))
      (str/blank? version) (throw (bad-spec spec "say which version, so it doesn't change on its own."))
      (not (pinned? version)) (throw (bad-spec spec (str "\"" version "\" isn't one version, so it could change on its own."))))
    {:name (str "local." kind "/" (-> identifier str/lower-case (str/replace #"^@" "") (str/replace "/" "-")))
     :title identifier
     :version version
     :packages [{:registryType kind
                 :identifier identifier
                 :version version
                 :runtimeHint runtime
                 :transport {:type "stdio"}}]}))

(defn ^:private private-address?
  "Whether the address of `octets` (4 or 16, unsigned) is private, loopback
  or link-local."
  [[a b :as octets]]
  (if (= 4 (count octets))
    (or (= 10 a)
        (and (= 172 a) (<= 16 b 31))
        (and (= 192 a) (= 168 b))
        (= 127 a)
        (and (= 169 a) (= 254 b)))
    (or (= (concat (repeat 15 0) [1]) octets)
        (= 0xfc (bit-and 0xfe a))
        (and (= 0xfe a) (= 0x80 (bit-and 0xc0 b))))))

(defn ^:private ipv4-octets
  "The 4 octets of the IPv4 address `s`, or nil when it isn't one."
  [s]
  (when (re-matches #"\d{1,3}(\.\d{1,3}){3}" s)
    (let [octets (mapv parse-long (str/split s #"\."))]
      (when (every? #(<= 0 % 255) octets) octets))))

(defn ^:private ipv6-octets
  "The 16 octets of the IPv6 address `s` (without brackets or a zone), or
  nil when it isn't one."
  [s]
  (let [groups #(if (str/blank? %) [] (str/split % #":" -1))
        [left right :as halves] (str/split s #"::" -1)
        left (groups left)
        right (groups right)
        missing (- 8 (count left) (count right))
        all (case (count halves)
              1 (when (= 8 (count left)) left)
              2 (when (pos? missing) (concat left (repeat missing "0") right))
              nil)]
    (when (and all (every? #(re-matches #"[0-9a-fA-F]{1,4}" %) all))
      (mapcat (fn [group]
                (let [n (Integer/parseInt group 16)]
                  [(bit-shift-right n 8) (bit-and n 0xff)]))
              all))))

(defn ^:private literal-octets
  "The octets of `host` when it's an IP address, or nil. Names aren't
  looked up."
  [host]
  (if-let [[_ v6] (re-matches #"\[([^%\]]*)(%[^\]]*)?\]" host)]
    (ipv6-octets v6)
    (ipv4-octets host)))

(defn ^:private private-host?
  "Whether `host` is on the local network: a private, loopback or
  link-local address, or a .local name."
  [host]
  (let [host (str/lower-case host)]
    (boolean (or (= "localhost" host)
                 (str/ends-with? host ".local")
                 (some-> (literal-octets host) private-address?)))))

(defn ^:private bad-url [url why]
  (ex-info (str "\"" url "\" isn't an MCP server's address: " why) {:type ::bad-url}))

(defn ^:private url-name
  "A server.json name for the server at `uri`: its host, port and path."
  [^URI uri]
  (let [where (str (.getHost uri) (when (pos? (.getPort uri)) (str "-" (.getPort uri))) (.getRawPath uri))
        segment (-> where str/lower-case (str/replace #"[^a-z0-9._-]+" "-") (str/replace #"^-+|-+$" ""))]
    (str "local.url/" segment)))

(defn url-entry
  "A minimal server.json for the MCP server at `url`, over streamable HTTP.
  https anywhere, http only on the local network. Throws ::bad-url saying
  why when it isn't one."
  [url]
  (let [url (str/trim (str url))
        uri (try (URI. url) (catch Exception _ nil))
        scheme (some-> uri .getScheme str/lower-case)
        host (some-> uri .getHost)]
    (cond
      (not (#{"http" "https"} scheme)) (throw (bad-url url "it should start with https://."))
      (str/blank? host) (throw (bad-url url "it names no host."))
      (.getRawUserInfo uri) (throw (bad-url url "it shouldn't hold a user or a password."))
      (and (= "http" scheme) (not (private-host? host)))
      (throw (bad-url url "plain http is only for the local network: use https.")))
    {:name (url-name uri)
     :title host
     :version "0.0.0"
     :remotes [{:type "streamable-http" :url url}]}))

(defn id-of
  "grog's short id for `server`: its name's last segment, in lowercase
  letters, digits and dashes, 40 characters at most, never empty:
  \"io.github.foo/weather-mcp\" is \"weather-mcp\"."
  [server]
  (let [segment (last (str/split (str (:name server)) #"/"))
        id (-> (str segment)
               str/lower-case
               (str/replace #"[^a-z0-9]+" "-")
               (str/replace #"^-+|-+$" ""))
        id (str/replace (subs id 0 (min id-max (count id))) #"-+$" "")]
    (if (str/blank? id) "server" id)))
