(ns grog.provider
  "The LLM providers grog can use, and calls to the configured one.

  A provider is a map of functions taking the provider's settings
  (`providers.<id>` plus :state-dir, see `grog.config/provider-config`):

    :name           for messages, e.g. \"OpenAI\"
    :stream-reply!  (fn [cfg request on-text on-tool]): answers `request`
                    ({:system :messages :location :timezone :tools :call-tool}),
                    calling `on-text` with each chunk of text, and `on-tool`
                    with {:name} when the model starts using a tool (like web
                    search, or one of `tools`). `tools` are grog's own
                    ([{:name :description :parameters}], parameters as a JSON
                    schema, see grog.tools): when the model calls one, the
                    provider runs it with (call-tool name args), which returns
                    what to tell the model, and asks it again with that.
                    Returns {:text :stop-reason :usage :quota}: :usage
                    {:auth :model :input-tokens :cached-tokens :output-tokens
                    :web-searches :usd} (:usd: what an API key paid, nil when
                    unknown), :quota like :quota's, when the answer told.
    :describe       (fn [cfg]): how it would be called now, for `grog status`:
                    {:auth :model ...}
    :quota          optional, (fn [cfg]): how much of the subscription's
                    usage limits is used, when it uses one: {:plan
                    :updated-at :windows [{:id :label :used-percent
                    :resets-at :window-minutes}]} (epoch seconds), else nil.
    :models         optional, (fn [cfg]): the models on offer, for `grog models`:
                    [{:id :description :current} ...]
    :model-path     optional, (fn [cfg]): where in its settings the model it
                    calls now is, which the `settings` tool changes; [:model]
                    when it doesn't say.
    :model-paths    optional: every place in its settings a model it calls
                    can be (e.g. the API's and the subscription's), which the
                    `settings` tool keeps track of, :model-path's among them.
    :login          optional: signing in to a subscription, see `grog.login`.
    :validate       optional, (fn [cfg]): what's wrong with its settings, as
                    messages (see `grog.config/check`), checked at startup.

  Adding a provider: a namespace under grog.providers defining such a map, its
  settings in `grog.config/defaults`, and an entry in `providers`."
  (:require [clojure.string :as str]
            [grog.config :as config]
            [grog.providers.openai :as openai]))

;; Vars, so redefinitions made through nREPL take effect right away.
(def providers
  {"openai" #'openai/provider})

(defn get-provider
  "The provider `id`. Throws, saying which ones exist, when there's none."
  [id]
  (if-let [provider (get providers id)]
    @provider
    (throw (ex-info (format "Unknown provider %s; available: %s."
                            (pr-str id) (str/join ", " (keys providers)))
                    {:type ::unknown-provider}))))

(defn problems
  "What's wrong with the provider settings of `config`."
  [config]
  (let [available (str/join ", " (keys providers))
        id (:provider config)
        unknown (remove (set (map keyword (keys providers))) (keys (:providers config)))]
    (concat
     (when (seq unknown)
       [(format "providers: unknown %s; available: %s" (str/join ", " (map name unknown)) available)])
     (if-let [provider (get providers id)]
       (when-let [validate (:validate @provider)]
         (validate (config/provider-config config)))
       [(format "provider: unknown provider %s; available: %s" (pr-str id) available)]))))

(defn ^:private call [config k & args]
  (let [id (:provider config)
        f (or (get (get-provider id) k)
              (throw (ex-info (format "The %s provider doesn't support that." id)
                              {:type ::unsupported})))]
    (apply f (config/provider-config config) args)))

(defn stream-reply!
  "Streams the configured provider's answer to `request`, see the ns doc."
  ([config request on-text]
   (stream-reply! config request on-text (fn [_])))
  ([config request on-text on-tool]
   (call config :stream-reply! request on-text on-tool)))

(defn describe
  "The configured provider, model and credentials, for `grog status`."
  [config]
  (merge {:provider (:provider config)} (call config :describe)))

(defn describe-quietly
  "Like `describe`, but only {:provider} when it can't tell, rather than
  throwing."
  [config]
  (try (describe config)
       (catch Exception _ {:provider (:provider config)})))

(defn models
  "The models the configured provider offers."
  [config]
  (call config :models))

(defn quota
  "How much of the configured provider's subscription limits is used, or nil
  (no subscription, or the provider doesn't tell)."
  [config]
  (when (:quota (get-provider (:provider config)))
    (call config :quota)))

(defn model-path
  "Where in `config` the model the configured provider calls now is (see
  :model-path)."
  [config]
  (let [id (:provider config)
        f (some-> (get providers id) deref :model-path)]
    (into [:providers (keyword id)] (if f (f (config/provider-config config)) [:model]))))

(defn model-paths
  "Every place in `config` a model of the configured provider can be (see
  :model-paths), the one it calls now first."
  [config]
  (let [id (:provider config)
        current (model-path config)]
    (into [current]
          (comp (map #(into [:providers (keyword id)] %)) (remove #{current}))
          (some-> (get providers id) deref :model-paths))))

(defn model
  "The model the configured provider calls now."
  [config]
  (get-in config (model-path config)))
