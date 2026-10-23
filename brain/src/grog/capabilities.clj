(ns grog.capabilities
  "grog's own tools for what's connected (plans/integrations.md, what the
  model sees): few, voice-friendly names that don't change, whatever is
  behind them. Here `lights`, through the first connected MCP server, in
  catalog order, whose shipped entry maps it and that has the tools of at
  least its on and off; `music` (grog.music) maps its actions the same way.

  An entry maps them in its _meta capabilities: {\"lights\" {\"<action>\"
  {\"tool\" name \"args\" template}}}, the tool matched to the server's as
  grog.mcp-tools/matches? does. In a template, a string that is exactly
  \"{param}\" is the call's argument `param`, as it is, or left out when the
  call has none (or a blank one); any other value stays as written.

  They're grog's own, so trusted: no policy, no asking."
  (:require [clojure.string :as str]
            [grog.catalog :as catalog]
            [grog.config :as config]
            [grog.connections :as connections]
            [grog.mcp :as mcp]
            [grog.mcp-tools :as mcp-tools]
            [grog.servers :as servers]))

(def ^:private actions
  "The actions `lights` can have, in the order the model sees them."
  ["on" "off" "set" "state"])

(def ^:private properties
  "What the model can say with `lights`, by template parameter, in order."
  [["name" {"type" "string" "description" "The light, as the user calls it."}]
   ["area" {"type" "string" "description" "The room or area."}]
   ["brightness" {"type" "integer" "minimum" 0 "maximum" 100 "description" "Brightness, 0 to 100."}]
   ["color" {"type" "string" "description" "A color name."}]
   ["temperature" {"type" "integer" "description" "Color temperature, in kelvin."}]])

(defn ^:private param
  "The parameter a template value stands for, or nil."
  [v]
  (when (string? v)
    (second (re-matches #"\{([A-Za-z0-9_]+)\}" v))))

(defn ^:private absent? [v]
  (or (nil? v) (and (string? v) (str/blank? v))))

(defn fill
  "The arguments `template` makes with the call's `args` (keyword keys):
  each \"{param}\" the argument as it is, or left out when absent or
  blank; the rest as written."
  [template args]
  (into {}
        (keep (fn [[k v]]
                (if-let [p (param v)]
                  (let [a (get args (keyword p))]
                    (when-not (absent? a) [k a]))
                  [k v])))
        template))

(defn params-of
  "The parameters `mapping`'s templates use."
  [mapping]
  (set (keep param (mapcat (comp vals :args) (vals mapping)))))

(defn mapping-of
  "What `entry` maps `capability` (\"lights\", \"music\") to, {action {:tool
  :args}}, only of `known` actions, when it's shipped; else nil."
  [entry capability known]
  (when (catalog/trusted? entry)
    (let [m (get-in (catalog/grog-meta entry) [:capabilities (keyword capability)])]
      (when (map? m)
        (not-empty (into {}
                         (keep (fn [a]
                                 (let [{:keys [tool args] :as one} (get m (keyword a))]
                                   (when (and (map? one) (string? tool) (not (str/blank? tool)))
                                     [a {:tool tool :args (if (map? args) args {})}]))))
                         known))))))

(defn ^:private usable?
  "Whether the server's `tools` have those of `mapping`'s `needed` actions."
  [mapping tools needed]
  (every? #(some->> (get-in mapping [% :tool]) (mcp-tools/find-tool tools)) needed))

(defn sources
  "The connected servers, in catalog order, whose shipped entries map
  `capability` (only its `known` actions), and whose tools include those
  of its `needed` actions: [{:id :entry :mapping}]."
  [config capability known needed]
  (let [connected (set (keep #(when (= "connected" (:status %)) (:id %))
                             (connections/all (config/state-dir config))))]
    (vec (keep (fn [entry]
                 (let [id (catalog/id-of entry)
                       mapping (when (connected id) (mapping-of entry capability known))]
                   (when (and mapping (usable? mapping (servers/cached-tools config id) needed))
                     {:id id :entry entry :mapping mapping})))
               (catalog/entries config)))))

(defn ^:private parameters
  "The schema of `lights` for `mapping`: its actions, and what its
  templates use."
  [mapping]
  (let [used (params-of mapping)]
    {"type" "object"
     "properties" (into {"action" {"type" "string" "enum" (filterv mapping actions)}}
                        (filter (comp used first))
                        properties)
     "required" ["action"]
     "additionalProperties" false}))

(defn ^:private description [title]
  (str "Turns the home's lights on or off, sets their brightness (0 to 100), color or color temperature "
       "(in kelvin), or tells how they are, through " title ". For other devices, use " title "'s own tools."))

(defn detail
  "What a `lights` call does, in a few words: \"off, living room\"."
  [{:keys [action name area brightness color temperature]}]
  (let [parts (remove absent? [action name area
                               (when (some? brightness) (str brightness "%"))
                               color
                               (when (some? temperature) (str temperature " K"))])]
    (when (seq parts) (str/join ", " parts))))

(defn call!
  "Does `args`'s action, `capability`'s, through `source` ({:id :entry
  :mapping}, see `sources`): the result's text."
  [ctx {:keys [id entry mapping]} capability args]
  (let [title (catalog/title entry)
        action (:action args)
        {:keys [tool] :as one} (get mapping (str action))
        found (when one (mcp-tools/find-tool (servers/cached-tools @(:config ctx) id) tool))]
    (when-not found
      (throw (ex-info (str capability " can't " action " through " title) {:type ::unmapped})))
    (try
      (mcp/text-of (servers/call! ctx id (:name found) (fill (:args one) args)))
      (catch clojure.lang.ExceptionInfo e
        (throw (ex-info (str title ": " (ex-message e)) {:type ::failed}))))))

(defn ^:private lights
  "The `lights` tool, through `source`."
  [{:keys [entry mapping] :as source}]
  {:name "lights"
   :description (description (catalog/title entry))
   :parameters (parameters mapping)
   :detail detail
   :run (fn [ctx args _turn] (call! ctx source "lights" args))})

(defn tools
  "The capability tools to offer with `config`: `lights`, when something
  connected can do it (`music` is grog.music's)."
  [config]
  (if-let [source (first (sources config "lights" actions ["on" "off"]))]
    [(lights source)]
    []))
