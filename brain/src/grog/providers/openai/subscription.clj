(ns grog.providers.openai.subscription
  "OpenAI through a ChatGPT subscription the way ECA does it: the Codex backend
  of the Responses API, which expects the Codex CLI's identity and has its own
  model catalog. Signing in is in grog.providers.openai.oauth."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; The Codex CLI version we present; the backend refuses models whose minimum
;; client version is newer.
(def compatibility-version "0.160.0")

;; Models taking the "Responses Lite" request shape, for when the live catalog
;; doesn't say (ECA's fallback list).
(def ^:private lite-models
  #{"gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna" "gpt-6-astra"})

(defn headers
  "The Codex CLI's request identity, which the backend expects."
  [{:keys [access-token account-id]} turn-id lite?]
  (cond-> {"authorization" (str "Bearer " access-token)
           ;; A mismatched originator/user-agent pair is refused.
           "originator" "codex_cli_rs"
           "user-agent" (str "codex_cli_rs/" compatibility-version)
           "session-id" turn-id
           "thread-id" turn-id
           "x-client-request-id" turn-id}
    account-id (assoc "chatgpt-account-id" account-id)
    lite? (assoc "x-openai-internal-codex-responses-lite" "true")))

(defn ^:private lite-body
  "The Responses Lite shape: instructions and function tools move into the
  input. It rejects hosted tools like web search."
  [{:keys [instructions input tools] :as body}]
  (-> body
      (dissoc :instructions :tools)
      (assoc :input (into (cond-> [{:type "additional_tools"
                                    :role "developer"
                                    :tools (filterv #(= "function" (:type %)) tools)}]
                            (not (str/blank? instructions))
                            (conj {:type "message"
                                   :role "developer"
                                   :content [{:type "input_text" :text instructions}]}))
                          input)
             :parallel_tool_calls false)
      (assoc-in [:reasoning :context] "all_turns")))

(defn request-body
  "Like the Responses API request, minus what the backend rejects (no
  max_output_tokens), plus what Codex sends. `lite?` (the Responses Lite
  shape) only for requests without hosted `tools`. The input is the
  conversation, then what the model did in earlier requests of the turn
  (`items`: its tool calls and their results)."
  [{:keys [reasoning-effort]} {:keys [system messages items tool-choice]} {:keys [model tools lite?]}]
  (cond-> {:model model
           :instructions system
           :input (into (mapv #(select-keys % [:role :content]) messages) items)
           :tools (vec tools)
           :tool_choice (or tool-choice "auto")
           :reasoning (cond-> {:summary "auto"}
                        reasoning-effort (assoc :effort reasoning-effort))
           :include ["reasoning.encrypted_content"]
           :prompt_cache_key "grog"
           :store false
           :stream true}
    lite? lite-body))

;; The live catalog: {:models {slug entry}} once fetched, {:failed-at ms}
;; when that failed, nil before.
(defonce ^:private catalog (atom nil))

;; How long after failing to fetch the catalog it's asked again.
(def retry-ms (* 5 60 1000))

(defn now-ms
  "The clock (tests redefine it)."
  []
  (System/currentTimeMillis))

(defn reset-catalog!
  "Forgets the live catalog, or takes `models` ({slug entry}) as it."
  ([]
   (reset! catalog nil))
  ([models]
   (reset! catalog {:models models})))

(defn models!
  "The live model catalog of the subscription."
  [cfg credentials]
  (let [resp (http/get (str (get-in cfg [:subscription :base-url])
                            "/models?client_version=" compatibility-version)
                       {:headers (headers credentials (str (random-uuid)) false)
                        :throw false
                        :timeout 10000})]
    (if (= 200 (:status resp))
      (let [models (:models (json/parse-string (:body resp) true))]
        (reset-catalog! (into {} (map (juxt :slug identity)) models))
        models)
      (throw (ex-info (format "The ChatGPT model catalog returned %d: %s" (:status resp) (:body resp))
                      {:type ::catalog-error :status (:status resp)})))))

(defn ^:private fetch?
  "Whether to fetch the catalog: never fetched, or failing a while ago."
  [{:keys [models failed-at] :as fetched} now-ms]
  (or (nil? fetched)
      (and (nil? models) (>= (- now-ms failed-at) retry-ms))))

(defn lite?
  "Whether `model` takes the Responses Lite shape, per the live catalog
  (fetched once, again a while after it failed) or the fallback list."
  [cfg credentials model]
  (when (fetch? @catalog (now-ms))
    (try (models! cfg credentials)
         (catch Exception _ (reset! catalog {:failed-at (now-ms)}))))
  (if-let [entry (get-in @catalog [:models model])]
    (true? (:use_responses_lite entry))
    (contains? lite-models model)))
