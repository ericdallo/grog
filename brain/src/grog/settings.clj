(ns grog.settings
  "Settings changed while grog runs, through the model's `settings` tool:
  kept in settings.json in the state directory, over the settings grog
  started with (the defaults and its settings file), across restarts.
  `grog settings reset` drops them. A new model, or reasoning effort, is
  tried before it's kept: one that doesn't answer would leave grog mute."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.log :as log]
            [grog.provider :as provider]
            [grog.state :as state]
            [grog.usage :as usage]
            [grog.validation :as validation]))

;; What the tool changes, by its argument names.
(def ^:private settable [:name :language :location :timezone :model :reasoning_effort])

;; Those it tries before keeping them.
(def ^:private tried #{:model :reasoning_effort})

(defn ^:private paths
  "Where each setting the tool changes can live in `config`: changes go to
  the first place, all of them are kept, listed and reset (the model can be
  in several, as each of the provider's credentials has its own)."
  [config]
  {:name [[:name]]
   :language [[:language]]
   :location [[:location]]
   :timezone [[:timezone]]
   :model (provider/model-paths config)
   :reasoning_effort [[:providers (keyword (:provider config)) :reasoning-effort]]})

(defn file [config]
  (str (fs/path (config/state-dir config) "settings.json")))

(defn ^:private changed-paths
  "The [setting path] pairs of `overrides` (nil values included)."
  [config overrides]
  (for [[k ps] (paths config)
        path ps
        :when (not= ::absent (get-in overrides path ::absent))]
    [k path]))

(defn apply-overrides
  "`config` with the settings in `overrides` replaced, each a whole (a
  location isn't merged with the configured one)."
  [config overrides]
  (reduce (fn [c [_ path]] (assoc-in c path (get-in overrides path)))
          config
          (changed-paths config overrides)))

(defn ^:private setting-of
  "The setting a problem message is about, without its value: what's safe
  to log."
  [problem]
  (first (str/split problem #":" 2)))

(defn load-overrides
  "The settings changed earlier, over `config`: {} when there are none, or
  when they don't make sense anymore (logged, naming only the settings)."
  [config]
  (let [f (file config)]
    (if-not (fs/exists? f)
      {}
      (try
        (let [saved (config/parse (slurp f))
              overrides (if (map? saved)
                          (reduce (fn [m [_ path]] (assoc-in m path (get-in saved path)))
                                  {}
                                  (changed-paths config saved))
                          (throw (ex-info "not a JSON object" {})))]
          (if-let [bad (seq (validation/problems (apply-overrides config overrides)))]
            (do (log/info (format "ignoring the settings changed earlier in %s, invalid: %s"
                                  f (str/join ", " (distinct (map setting-of bad)))))
                {})
            overrides))
        (catch Exception e
          (log/info (format "ignoring the settings changed earlier in %s: %s" f (.getSimpleName (class e))))
          {})))))

(defn ^:private dissoc-in
  "`m` without `path`, nor the maps that leaves empty."
  [m [k & ks]]
  (if-not ks
    (dissoc m k)
    (let [inner (dissoc-in (get m k) ks)]
      (if (empty? inner) (dissoc m k) (assoc m k inner)))))

(defn ^:private blank->nil [v]
  (if (and (string? v) (str/blank? v)) nil v))

(defn ^:private normalize
  "The tool's value for setting `k`, as grog keeps it."
  [k v]
  (case k
    (:name :model) (some-> v str str/trim)
    :location (when (map? v)
                (not-empty (into {} (for [part [:city :region :country]
                                          :let [s (some-> (get v part) str str/trim)]
                                          :when (seq s)]
                                      [part s]))))
    (blank->nil v)))

(defn current
  "The settings the tool changes, as in `config`, and which of them were
  changed (`overrides`)."
  [config overrides]
  (assoc (into {} (for [[k [path]] (paths config)] [k (get-in config path)]))
         :timezone (config/timezone config)
         :changed (vec (distinct (map (comp name first) (changed-paths config overrides))))))

(defn ^:private offered-models
  "The models the provider offers, [{:id :description}], or nil when it
  can't say."
  [config]
  (try
    (not-empty (mapv #(select-keys % [:id :description]) (provider/models config)))
    (catch Exception _ nil)))

(defn try-model!
  "Asks the model `config` sets for a word, the way turns ask it (with web
  search, when on), counting what that used in the context's usage. Throws
  ex-info saying what went wrong, and which models there are, when it
  doesn't answer."
  [ctx config]
  (let [[answer error] (try
                         [(provider/stream-reply! config
                                                  {:system "Answer with one word."
                                                   :messages [{:role "user" :content "Say OK."}]
                                                   :location (:location config)
                                                   :timezone (config/timezone config)}
                                                  (fn [_]))]
                         (catch Exception e
                           [nil (or (ex-message e) (.getSimpleName (class e)))]))]
    (when answer
      (usage/record! ctx (:usage answer))
      (usage/update-quota! ctx (:quota answer)))
    (when-let [problem (or error (when (str/blank? (:text answer)) "it gave no answer"))]
      (throw (ex-info (str (format "%s didn't work, so nothing changed: %s" (provider/model config) problem)
                           (when-let [models (offered-models config)]
                             (str ". Models on offer: " (str/join ", " (map :id models)))))
                      {:type ::untried})))))

(defn ^:private next-settings
  "The overrides and the settings after the settings `resets` go back to
  how grog started, and `changes` ([setting value]) are made."
  [{:keys [config base-config overrides]} resets changes]
  (let [ps (paths @config)
        reset-paths (mapcat ps resets)]
    [(as-> @overrides o
       (reduce dissoc-in o reset-paths)
       (reduce (fn [o [k v]] (assoc-in o (first (ps k)) v)) o changes))
     ;; Path by path, keeping what was changed live (nREPL).
     (as-> @config c
       (reduce (fn [c path] (assoc-in c path (get-in base-config path))) c reset-paths)
       (reduce (fn [c [k v]] (assoc-in c (first (ps k)) v)) c changes))]))

(defn ^:private check! [config]
  (when-let [bad (seq (validation/problems config))]
    (throw (ex-info (str/join "; " bad) {:type ::invalid}))))

(defn change!
  "Applies the `settings` tool's `args` to the context's settings: the
  settings given change, those listed in `reset` go back to how grog
  started. Checked like at startup, a new model or reasoning effort tried
  (throws ex-info saying what's wrong), then kept. Returns the current
  settings; with nothing to change, the models on offer too."
  [{:keys [config overrides lock] :as ctx} args]
  (let [resets (filter (set settable) (keep #(when (string? %) (keyword %)) (:reset args)))
        changes (for [k settable :when (contains? args k)]
                  [k (normalize k (get args k))])]
    (if (and (empty? resets) (empty? changes))
      (let [models (offered-models @config)]
        (cond-> (current @config @overrides) models (assoc :models models)))
      (do
        ;; Without the lock, which turns' usage and memory take: it asks
        ;; the model.
        (when (some (comp tried first) changes)
          (let [[_ next-config] (next-settings ctx resets changes)]
            (check! next-config)
            (try-model! ctx next-config)))
        (locking lock
          (let [[next-overrides next-config] (next-settings ctx resets changes)]
            (check! next-config)
            (if (empty? next-overrides)
              (fs/delete-if-exists (file next-config))
              (state/write! (file next-config) (config/generate next-overrides {:pretty true})))
            (reset! overrides next-overrides)
            (reset! config next-config)
            (current next-config next-overrides)))))))

(defn changed
  "The names of the settings changed through the tool."
  [{:keys [config overrides]}]
  (:changed (current @config @overrides)))

(defn reset-all!
  "Drops every setting changed through the tool: back to how grog started."
  [ctx]
  (change! ctx {:reset (map name settable)}))

(def tool
  {:name "settings"
   :description (str "Your settings, which you change when the user asks you to: your name (also the word "
                     "you answer to), the language you always answer in, where the home is and its time "
                     "zone, the AI model you run on, and how much you think before answering. Save where the "
                     "home is whenever the user tells you, even if they don't ask you to. Changes are "
                     "kept, and take effect from the user's next message; a new model is tried first. With "
                     "no arguments, it tells the current ones and the models on offer. How the user wants "
                     "you to answer goes in your memory instead.")
   :parameters {:type "object"
                :properties {:name {:type "string" :description "Your new name."}
                             :language {:type ["string" "null"]
                                        :description "The language to always answer in, like \"English\"; null for the one the user speaks."}
                             :location {:type ["object" "null"]
                                        :description "Where the home is; null to forget it."
                                        :properties {:city {:type "string"}
                                                     :region {:type "string" :description "State or province."}
                                                     :country {:type "string" :description "ISO 3166-1 alpha-2 code, like \"BR\"."}}
                                        :additionalProperties false}
                             :timezone {:type ["string" "null"]
                                        :description "IANA time zone, like \"America/Sao_Paulo\"; null for the machine's."}
                             :model {:type "string"
                                     :description "The AI model to run on, by its id, as listed with no arguments."}
                             :reasoning_effort {:type "string"
                                                :enum ["low" "medium" "high"]
                                                :description "How much you think before answering: low answers fastest."}
                             :reset {:type "array"
                                     :items {:type "string" :enum (mapv name settable)}
                                     :description "Settings to put back as they were configured."}}
                :additionalProperties false}
   :changes? (fn [args] (boolean (or (seq (:reset args)) (some #(contains? args %) settable))))
   :run (fn [ctx args _] (change! ctx args))})
