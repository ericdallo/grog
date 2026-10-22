(ns grog.tools
  "The tools grog gives the model (function calling, see grog.provider). A
  tool is a map:

    :name :description :parameters  what the model sees (parameters: a JSON
                                    schema)
    :changes?  (fn [args]): whether running it changes something. Those don't
               run in a turn that read what others wrote (searched the web,
               or ran an :open-world? tool), as it could have told the model
               to.
    :open-world?  optional: whether what it answers may carry someone else's
               text (an MCP server's mail, a page), so nothing changes after
               it in that turn.
    :run       (fn [ctx args turn]): the result for the model, text or data
               (sent as JSON). Throws ex-info to tell it what went wrong.
               `turn`: {:id the conversation, :n its turn number, :searched?
               (fn []), :later an atom of the actions to take once its
               answer is out, when its caller takes them (grog.actions),
               :detail! (fn [name detail]), telling whoever watches what a
               tool is used with}.
    :detail    (fn [args]), optional: what it's used with, in a few words
               (a command, a level), for whoever watches; nil for nothing."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog.actions :as actions]
            [grog.capabilities :as capabilities]
            [grog.computers :as computers]
            [grog.integrations :as integrations]
            [grog.log :as log]
            [grog.machine :as machine]
            [grog.mcp-tools :as mcp-tools]
            [grog.memory :as memory]
            [grog.settings :as settings]
            [grog.update :as update]
            [grog.voice :as voice]))

(defn available
  "The tools for a turn: grog's own (its settings, memory and microphone),
  the machine's when it has hands, `power` when it can restart itself or
  the machine, `update` when it can update itself, `computer` with
  computers paired with it, its capability tools for what's connected
  (grog.capabilities: `lights`), those of the MCP servers connected to it
  (grog.mcp-tools), and `connect`, to connect something."
  [config]
  (let [power (actions/tool config)
        updating (update/tool config)
        computing (computers/tool config)]
    (cond-> [settings/tool memory/tool voice/tool]
      (get-in config [:hands :socket]) (into machine/tools)
      power (conj power)
      updating (conj updating)
      computing (conj computing)
      true (into (capabilities/tools config))
      true (into (mcp-tools/tools config))
      true (conj (integrations/tool config)))))

(defn definitions
  "What the provider tells the model about `tools`."
  [tools]
  (mapv #(select-keys % [:name :description :parameters]) tools))

;; How long what a tool is used with gets, for whoever watches.
(def ^:private detail-max-chars 120)

(defn short-detail
  "What a tool is used with, `s`, for whoever watches: on one line, at most
  `detail-max-chars` long; nil when blank."
  [s]
  (let [s (some-> s str (str/replace #"\s+" " ") str/trim)]
    (cond
      (str/blank? s) nil
      (> (count s) detail-max-chars) (str (subs s 0 (dec detail-max-chars)) "…")
      :else s)))

(defn ^:private tell-detail!
  "Tells whoever watches what `tool` is used with, if it says."
  [{:keys [name detail]} args {:keys [detail!]}]
  (when (and detail detail!)
    (when-let [d (try (detail args) (catch Exception _ nil))]
      (detail! name d))))

(defn runner
  "Runs the model's calls of `tools` during `turn`: (fn [name args]) -> what
  to tell the model, never throwing. Once it ran an :open-world? tool, as
  after a web search, nothing that changes runs in that turn."
  [ctx tools {:keys [searched?] :as turn}]
  (let [by-name (into {} (map (juxt :name identity)) tools)
        read-others? (atom false)]
    (fn [name args]
      (let [{:keys [run changes? open-world?] :as tool} (get by-name name)]
        (cond
          (nil? tool)
          (str "error: there's no tool called " name)

          (and (or (searched?) @read-others?) (changes? args))
          (str "error: nothing is changed in a turn that read what others wrote, like a web search: "
               "ask the user to say it again")

          :else
          (try
            (when open-world?
              (reset! read-others? true))
            (tell-detail! tool args turn)
            (let [result (run ctx args turn)]
              (if (string? result) result (json/generate-string result)))
            (catch clojure.lang.ExceptionInfo e
              (str "error: " (ex-message e)))
            (catch Exception e
              ;; Its message could quote what was said.
              (log/info (format "the %s tool failed: %s" name (.getName (class e))))
              (str "error: " (.getSimpleName (class e))))))))))
