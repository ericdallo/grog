(ns grog.tools
  "The tools grog gives the model (function calling, see grog.provider). A
  tool is a map:

    :name :description :parameters  what the model sees (parameters: a JSON
                                    schema)
    :changes?  (fn [args]): whether running it changes something. Those don't
               run in a turn that searched the web, as a page could have told
               the model to.
    :run       (fn [ctx args turn]): the result for the model, text or data
               (sent as JSON). Throws ex-info to tell it what went wrong.
               `turn`: {:id the conversation, :n its turn number, :searched?
               (fn []), :later an atom of the actions to take once its
               answer is out, when its caller takes them (grog.actions)}."
  (:require [cheshire.core :as json]
            [grog.actions :as actions]
            [grog.log :as log]
            [grog.machine :as machine]
            [grog.memory :as memory]
            [grog.settings :as settings]
            [grog.update :as update]
            [grog.voice :as voice]))

(defn available
  "The tools for a turn: grog's own (its settings, memory and microphone),
  the machine's when it has hands, `power` when it can restart itself or
  the machine, and `update` when it can update itself."
  [config]
  (let [power (actions/tool config)
        updating (update/tool config)]
    (cond-> [settings/tool memory/tool voice/tool]
      (get-in config [:hands :socket]) (into machine/tools)
      power (conj power)
      updating (conj updating))))

(defn definitions
  "What the provider tells the model about `tools`."
  [tools]
  (mapv #(select-keys % [:name :description :parameters]) tools))

(defn runner
  "Runs the model's calls of `tools` during `turn`: (fn [name args]) -> what
  to tell the model, never throwing."
  [ctx tools {:keys [searched?] :as turn}]
  (let [by-name (into {} (map (juxt :name identity)) tools)]
    (fn [name args]
      (let [{:keys [run changes?] :as tool} (get by-name name)]
        (cond
          (nil? tool)
          (str "error: there's no tool called " name)

          (and (searched?) (changes? args))
          "error: nothing is changed in a turn that searched the web; ask the user to say it again"

          :else
          (try
            (let [result (run ctx args turn)]
              (if (string? result) result (json/generate-string result)))
            (catch clojure.lang.ExceptionInfo e
              (str "error: " (ex-message e)))
            (catch Exception e
              ;; Its message could quote what was said.
              (log/info (format "the %s tool failed: %s" name (.getName (class e))))
              (str "error: " (.getSimpleName (class e))))))))))
