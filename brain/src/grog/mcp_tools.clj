(ns grog.mcp-tools
  "The tools of connected MCP servers, as grog's tools for the model
  (grog.tools, plans/integrations.md, what the model sees): those of each
  connection's last tool list (grog.servers/cached-tools) that are shown,
  named <prefix>__<tool>, 64 characters at most.

  What the tools' annotations say, which only counts for entries shipped
  with grog (grog.catalog/trusted?); MCP's defaults otherwise:
  readOnlyHint false, destructiveHint true, openWorldHint true.

    read-only   readOnlyHint
    open-world  unless trusted and openWorldHint is false: what it answers
                may carry someone else's text (told, not guarded; grog
                learns nothing from it, grog.learning)
    destructive not read-only and destructiveHint: asks first by default

  Each tool's policy: \"allow\", \"ask\" (grog.asks) or \"deny\", by default
  \"ask\" for destructive ones and \"allow\" for the rest, over which the
  shipped entry's _meta policy goes. Shown by default: those the shipped
  entry's _meta show names, else the first `shown-max`. The entry's names
  match the server's as `matches?` says. The user's choices
  win, kept in <state-dir>/tools.json: {\"<connection>\" {\"<tool>\"
  {\"mode\" \"ask\", \"show\" true}}}, tool names as the servers have them."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.asks :as asks]
            [grog.catalog :as catalog]
            [grog.config :as config]
            [grog.connections :as connections]
            [grog.log :as log]
            [grog.mcp :as mcp]
            [grog.servers :as servers]
            [grog.state :as state])
  (:import (java.security MessageDigest)))

(def modes
  "What a tool's policy can be."
  #{"allow" "ask" "deny"})

(def shown-max
  "How many of a server's tools the model sees, by default, when its entry
  doesn't say which."
  40)

(def ^:private name-max 64)
(def ^:private prefix-max 32)
(def ^:private description-max 1024)

;; Changes to the user's choices, one at a time.
(def ^:private lock (Object.))

(defn ^:private sanitize [s]
  (str/replace (str s) #"[^A-Za-z0-9_-]" "_"))

(defn prefix
  "What the names of `entry`'s tools start with: its _meta prefix, or its
  id, as a tool name may have it."
  [entry]
  (let [p (sanitize (or (:prefix (catalog/grog-meta entry)) (catalog/id-of entry)))]
    (subs p 0 (min prefix-max (count p)))))

(defn ^:private hash4
  "4 hex digits of `s`'s SHA-1."
  [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-1") (.getBytes s "UTF-8"))]
    (format "%02x%02x" (bit-and (aget d 0) 0xff) (bit-and (aget d 1) 0xff))))

(defn ^:private hashed
  "<prefix>__<tool>_<hash>, the tool cut to fit."
  [prefix tool]
  (let [part (sanitize tool)
        room (- name-max (count prefix) 2 5)]
    (str prefix "__" (subs part 0 (min room (count part))) "_" (hash4 (str prefix "__" tool)))))

(defn tool-names
  "The names the model sees for `tools` (their names on the server), in
  order: <prefix>__<tool>, what a name can't have as _, at most 64
  characters (cut, with a hash of the whole name), never twice the same."
  [prefix tools]
  (let [plain (map #(let [n (str prefix "__" (sanitize %))]
                      (if (<= (count n) name-max) n (hashed prefix %)))
                   tools)
        counts (frequencies plain)]
    (mapv (fn [n tool] (if (> (counts n) 1) (hashed prefix tool) n)) plain tools)))

(defn matches?
  "Whether a server's tool called `tool-name` is the one a catalog entry
  calls `catalog-name`: the same, or ending in __<catalog-name>, as some
  servers' names change by version (Home Assistant's HassTurnOn,
  intent__HassTurnOn, device-control__intent__HassTurnOn)."
  [catalog-name tool-name]
  (let [c (str catalog-name)
        t (str tool-name)]
    (and (not (str/blank? c))
         (or (= c t) (str/ends-with? t (str "__" c))))))

(defn find-tool
  "The first of `tools` (as servers send them) that is `catalog-name`
  (`matches?`), the same name first, or nil."
  [tools catalog-name]
  (or (some #(when (= (str catalog-name) (:name %)) %) tools)
      (some #(when (matches? catalog-name (:name %)) %) tools)))

(defn ^:private key-name
  "A key of a parsed entry as it was written."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn ^:private policy-of
  "What the entry's _meta `policy` says of the server's tool `tool-name`:
  its own name's, else the first that `matches?` it, or nil."
  [policy tool-name]
  (or (get policy (keyword tool-name))
      (some (fn [[k v]] (when (matches? (key-name k) tool-name) v)) policy)))

(defn guards
  "What `tool`'s annotations mean, trusted (`trusted?`) or not: {:read-only
  :open-world :destructive}."
  [trusted? tool]
  (let [{:keys [readOnlyHint destructiveHint openWorldHint]} (:annotations tool)
        read-only (true? readOnlyHint)]
    {:read-only read-only
     :open-world (if trusted? (not (false? openWorldHint)) true)
     :destructive (and (not read-only) (not (false? destructiveHint)))}))

(defn ^:private choices-file [config]
  (str (fs/path (config/state-dir config) "tools.json")))

(defn ^:private choices
  "The user's choices, {connection {tool {\"mode\" \"show\"}}}, or {}."
  [config]
  (let [f (choices-file config)]
    (if (fs/exists? f)
      (try
        (let [m (json/parse-string (slurp f))]
          (if (map? m) m {}))
        (catch Exception e
          (log/info "mcp-tools: ignoring tools.json:" (.getSimpleName (class e)))
          {}))
      {})))

(defn ^:private write-choices! [config m]
  (state/write! (choices-file config) (json/generate-string m {:pretty true})))

(defn ^:private tool-title [tool]
  (or (not-empty (:title tool)) (not-empty (get-in tool [:annotations :title])) (:name tool)))

(defn ^:private settings-of
  "The settings of the tools of connection `id`, of `entry`: [{:name :title
  :description :mode :default-mode :shown :default-shown :read-only
  :destructive :open-world}], in the server's order."
  [config id entry tools]
  (let [trusted? (catalog/trusted? entry)
        {:keys [policy show]} (when trusted? (catalog/grog-meta entry))
        shown-names (when (sequential? show) (map str show))
        mine (get (choices config) id {})]
    (vec (map-indexed
          (fn [i {:keys [name description] :as tool}]
            (let [{:keys [read-only destructive open-world]} (guards trusted? tool)
                  default-mode (or (modes (policy-of policy name)) (if destructive "ask" "allow"))
                  default-shown (if shown-names (boolean (some #(matches? % name) shown-names)) (< i shown-max))
                  {:strs [mode show]} (get mine name)]
              {:name name
               :title (tool-title tool)
               :description (or description "")
               :mode (or (modes mode) default-mode)
               :default-mode default-mode
               :shown (if (boolean? show) show default-shown)
               :default-shown default-shown
               :read-only read-only
               :destructive destructive
               :open-world open-world}))
          tools))))

(defn ^:private mcp-connection
  "Connection `id`, when it's an MCP server's: [entry tools]. Throws ex-info
  otherwise."
  [config id]
  (let [entry (catalog/entry config id)
        connection (connections/by-id (config/state-dir config) id)]
    (when-not (and entry connection)
      (throw (ex-info (str "There's no MCP server connected as " (pr-str id) ".") {:type ::unknown})))
    [entry (or (servers/cached-tools config id) [])]))

(defn tool-settings
  "The settings of the tools of connection `id`, an MCP server's (see
  `settings-of`). Throws ex-info when it isn't one."
  [config id]
  (let [[entry tools] (mcp-connection config id)]
    (settings-of config id entry tools)))

(defn set-tool!
  "Sets what the user chose for `tool` of connection `id`: its :mode (one
  of `modes`, nil for the default) and whether it's shown (:show, nil for
  the default), those given only. Returns its settings then. Throws ex-info
  saying what's wrong."
  [config id tool {:keys [mode show] :as chosen}]
  (let [[entry tools] (mcp-connection config id)]
    (when-not (some #(= tool (:name %)) tools)
      (throw (ex-info (str (catalog/title entry) " has no tool called " (pr-str tool) ".") {:type ::unknown-tool})))
    (when (and (some? mode) (not (modes mode)))
      (throw (ex-info "mode: allow, ask or deny" {:type ::bad-mode})))
    (when (and (some? show) (not (boolean? show)))
      (throw (ex-info "show: true or false" {:type ::bad-show})))
    (locking lock
      (let [change (fn [m k v present?]
                     (cond (not present?) m
                           (nil? v) (dissoc m k)
                           :else (assoc m k v)))
            all (choices config)
            mine (-> (get-in all [id tool] {})
                     (change "mode" mode (contains? chosen :mode))
                     (change "show" show (contains? chosen :show)))]
        (write-choices! config (if (empty? mine)
                                 (let [rest (dissoc (get all id) tool)]
                                   (if (empty? rest) (dissoc all id) (assoc all id rest)))
                                 (assoc-in all [id tool] mine)))))
    (some #(when (= tool (:name %)) %) (tool-settings config id))))

(defn forget!
  "Forgets what the user chose for the tools of connection `id`."
  [config id]
  (locking lock
    (let [all (choices config)]
      (when (contains? all id)
        (write-choices! config (dissoc all id))))))

(defn ^:private parameters
  "A tool's input schema as the model takes it: an object, without
  $schema."
  [{:keys [inputSchema]}]
  (if (and (map? inputSchema) (= "object" (:type inputSchema)))
    (dissoc inputSchema :$schema)
    {:type "object" :properties {}}))

(defn ^:private description [integration title tool-description]
  (let [s (str integration ": " title "." (when-not (str/blank? tool-description) (str " " tool-description)))]
    (if (> (count s) description-max) (str (subs s 0 (dec description-max)) "…") s)))

(defn ^:private card-detail
  "What a tool would do, in a few words, for grog's card."
  [{:keys [destructive]}]
  (if destructive "It can change or delete things there." "It changes things there."))

(defn ^:private call!
  "Calls `tool` of connection `id`: the result's text, for the model."
  [ctx id integration tool args]
  (try
    (mcp/text-of (servers/call! ctx id tool args))
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (str integration ": " (ex-message e)) {:type ::failed})))))

(defn ^:private as-tool
  "The grog tool for the MCP tool of `settings`, named `tool-name`, of
  connection `id`."
  [id integration tool-name {:keys [name title mode open-world] :as settings}]
  {:name tool-name
   :description (description integration title (:description settings))
   :parameters (:parameters settings)
   :detail (constantly title)
   :open-world open-world
   :run (fn [ctx args turn]
          (case mode
            "deny" (throw (ex-info (str "the user doesn't let " (:name @(:config ctx)) " use it") {:type ::denied}))
            "ask" (asks/ask! ctx turn {:tool tool-name :args args :integration integration :title title
                                       :detail (card-detail settings)}
                             #(call! ctx id integration name args))
            (call! ctx id integration name args)))})

(defn tools
  "The tools the model sees of the MCP servers connected with `config`."
  [config]
  (vec (for [{:keys [id status]} (connections/all (config/state-dir config))
             :when (= "connected" status)
             :let [entry (catalog/entry config id)
                   listed (when entry (servers/cached-tools config id))]
             :when (seq listed)
             :let [integration (catalog/title entry)
                   names (tool-names (prefix entry) (map :name listed))]
             [tool-name tool settings] (map vector names listed (settings-of config id entry listed))
             :when (:shown settings)]
         (as-tool id integration tool-name (assoc settings :parameters (parameters tool))))))
