(ns grog.providers.openai
  "The OpenAI provider (see grog.provider): the Responses API, streamed,
  optionally with the built-in web search tool (without the sources it cites
  in the text, see grog.providers.openai.citations), and grog's own tools
  (function calling). Paid with an API key, or with a ChatGPT subscription
  after `grog login openai` (grog.providers.openai.oauth and .subscription)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.credentials :as credentials]
            [grog.providers.http :as http]
            [grog.providers.openai.citations :as citations]
            [grog.providers.openai.oauth :as oauth]
            [grog.providers.openai.quota :as quota]
            [grog.providers.openai.subscription :as subscription]))

(defn ^:private now-s []
  (quot (System/currentTimeMillis) 1000))

(defn ^:private user-location [{:keys [city region country]} timezone]
  (cond-> {:type "approximate"}
    city (assoc :city city)
    region (assoc :region region)
    country (assoc :country country)
    timezone (assoc :timezone timezone)))

(defn ^:private web-search-tools
  "The built-in web search tool, when enabled."
  [{:keys [web-search]} {:keys [location timezone]}]
  (if (:enable web-search)
    [(cond-> {:type "web_search"}
       (or location timezone) (assoc :user_location (user-location location timezone)))]
    []))

(defn ^:private function-tools
  "grog's own tools offered with `request` (see grog.tools)."
  [{:keys [tools]}]
  (mapv (fn [{:keys [name description parameters]}]
          {:type "function" :name name :description description :parameters parameters :strict false})
        tools))

(defn request-body
  "The Responses API request. The input is the conversation, then what the
  model did in earlier requests of the turn (`items`: its tool calls and their
  results)."
  [{:keys [model max-tokens reasoning-effort] :as cfg} {:keys [system messages items tool-choice] :as request}]
  (let [functions (function-tools request)
        tools (into (web-search-tools cfg request) functions)]
    (cond-> {:model model
             :instructions system
             :input (into (mapv #(select-keys % [:role :content]) messages) items)
             :max_output_tokens max-tokens
             :store false
             :stream true}
      reasoning-effort (assoc :reasoning {:effort reasoning-effort})
      ;; Nothing is stored between requests: the reasoning behind tool calls
      ;; goes back with their results.
      (and reasoning-effort (seq functions)) (assoc :include ["reasoning.encrypted_content"])
      (seq tools) (assoc :tools tools)
      tool-choice (assoc :tool_choice tool-choice))))

(defn auth-mode
  "How grog pays, per the `auth` setting: :subscription or :api-key. \"auto\"
  takes the subscription when `signed-in?` (`grog login openai`)."
  [auth signed-in?]
  (case auth
    "subscription" :subscription
    "api-key" :api-key
    (if signed-in? :subscription :api-key)))

(defn ^:private signed-in? [{:keys [state-dir]}]
  (some? (credentials/saved state-dir "openai")))

(defn ^:private mode [cfg]
  (auth-mode (:auth cfg) (signed-in? cfg)))

(defn credentials
  "How to pay (see `auth-mode`): {:type :subscription}, or {:type :api-key
  :key ...}. Throws saying what's missing."
  [{:keys [auth api-key-env] :as cfg}]
  (if (= :subscription (mode cfg))
    (if (signed-in? cfg)
      {:type :subscription}
      (throw (ex-info "Not signed in to a ChatGPT subscription: run `grog login openai`."
                      {:type ::not-signed-in})))
    (try
      {:type :api-key :key (http/api-key "OpenAI" api-key-env)}
      (catch clojure.lang.ExceptionInfo e
        (throw (if (= "api-key" auth)
                 e
                 (ex-info (format "No OpenAI credentials: run `grog login openai` to use your ChatGPT subscription, or set the %s environment variable to an API key."
                                  api-key-env)
                          {:type ::no-credentials})))))))

(defn model-path
  "Where the model calls use now is in the provider's settings: the
  subscription's when paying with it (see `auth-mode`), else the API's."
  [cfg]
  (case (mode cfg)
    :subscription [:subscription :model]
    :api-key [:model]))

(defn ^:private api-request [cfg {:keys [key]} request]
  {:url (str (:base-url cfg) "/v1/responses")
   :headers {"authorization" (str "Bearer " key)}
   :body (request-body cfg request)})

(defn ^:private subscription-request [cfg request turn-id force-refresh?]
  (let [credentials (oauth/fresh-credentials! cfg force-refresh?)
        model (get-in cfg [:subscription :model])
        hosted (web-search-tools cfg request)
        ;; The catalog asks for Responses Lite, which rejects hosted tools
        ;; like web search; the regular shape works for those models too.
        lite? (and (empty? hosted) (subscription/lite? cfg credentials model))]
    {:url (str (get-in cfg [:subscription :base-url]) "/responses")
     :headers (subscription/headers credentials turn-id lite?)
     :body (subscription/request-body cfg request {:model model
                                                   :tools (into hosted (function-tools request))
                                                   :lite? lite?})}))

(defn ^:private end-text
  "Emits what the citation filter held back of the text so far, now over."
  [acc emit!]
  (let [text (citations/finish (:pending acc))]
    (when (seq text)
      (emit! text))
    (assoc acc :pending "")))

(defn ^:private consume
  "Folds the streamed events of a request, emitting text as it arrives (but
  citations) and calling `on-tool` when the model starts using a tool, up to
  the final event. `emitted?`: whether earlier requests of the turn emitted
  text. Returns {:status :usage :items :searches :emitted?}, :items being what
  the model output, :searches how many times it searched the web; throws when
  the stream ends before."
  [events emit! on-tool emitted?]
  (let [{:keys [status] :as result}
        (reduce (fn [acc {:keys [type] :as event}]
                  (case type
                    "response.output_item.added"
                    (let [acc (end-text acc emit!)
                          {item-type :type item-name :name} (:item event)]
                      (cond
                        ;; A new message after text was already emitted (e.g.
                        ;; after a web search) starts a new sentence.
                        (= "message" item-type)
                        (cond-> acc (:emitted? acc) (assoc :separate? true))

                        ;; web_search_call, function_call...
                        (some-> item-type (str/ends-with? "_call"))
                        (do (on-tool {:name (or item-name (str/replace item-type #"_call$" ""))})
                            (cond-> acc (= "web_search_call" item-type) (update :searches inc)))

                        :else acc))

                    "response.output_item.done"
                    (update acc :items conj (:item event))

                    "response.output_text.delta"
                    (let [text (:delta event)]
                      (if (seq text)
                        (let [[out pending] (citations/strip (:pending acc)
                                                             (str (when (:separate? acc) " ") text))]
                          (when (seq out)
                            (emit! out))
                          (assoc acc :pending pending :emitted? true :separate? false))
                        acc))

                    ("response.completed" "response.incomplete")
                    (let [acc (end-text acc emit!)]
                      (reduced (cond-> (assoc acc
                                              :status (get-in event [:response :status])
                                              :usage (get-in event [:response :usage]))
                                 ;; Some streams only list the items at the end.
                                 (empty? (:items acc))
                                 (assoc :items (vec (get-in event [:response :output]))))))

                    "response.failed"
                    (throw (ex-info (str "OpenAI API error: " (get-in event [:response :error :message]))
                                    {:type ::stream-error :response (:response event)}))

                    "error"
                    (throw (ex-info (str "OpenAI API error: " (:message event))
                                    {:type ::stream-error :event event}))

                    acc))
                {:emitted? emitted? :pending "" :items [] :searches 0}
                events)]
    (when-not status
      (throw (ex-info "The answer was cut off: OpenAI closed the stream early."
                      {:type ::truncated})))
    result))

(defn ^:private stream!
  "What `consume` returns for the request, with the response's :headers."
  [cfg {:keys [url headers body]} emit! on-tool emitted?]
  (let [resp (http/post-sse! "OpenAI" url headers body (:timeout-ms cfg))]
    (assoc (http/read-events! "OpenAI" (:body resp) (:timeout-ms cfg) #(consume % emit! on-tool emitted?))
           :headers (:headers resp))))

(defn cost
  "What `usage` cost in USD at `prices` (per million tokens of each model, and
  per web search), or nil when its model has no price. Cached tokens are part
  of the input tokens, at their own price."
  [{:keys [models web-search]} {:keys [model input-tokens cached-tokens output-tokens web-searches]}]
  (when-let [{:keys [input cached-input output]} (get models (keyword model))]
    (+ (/ (+ (* (- input-tokens cached-tokens) input)
             (* cached-tokens (or cached-input input))
             (* output-tokens output))
          1e6)
       (* web-searches (or web-search 0)))))

(defn ^:private add-usage
  "The turn's `usage` so far, plus a request's (`used`, as the API tells it)
  and its web `searches`."
  [usage used searches]
  (-> usage
      (update :input-tokens + (:input_tokens used 0))
      (update :cached-tokens + (get-in used [:input_tokens_details :cached_tokens] 0))
      (update :output-tokens + (:output_tokens used 0))
      (update :web-searches + searches)))

(defn ^:private message-text
  "The text of an output message, without citations."
  [{:keys [content]}]
  (let [[out pending] (citations/strip "" (apply str (keep #(when (= "output_text" (:type %)) (:text %))
                                                           content)))]
    (str/trim (str out (citations/finish pending)))))

(defn ^:private replayed
  "How an output item goes back to the model along with the results of its
  tool calls (nothing is stored between requests), or nil."
  [{:keys [type id summary encrypted_content] :as item}]
  (case type
    "reasoning" (when encrypted_content
                  {:type "reasoning" :id id :summary (or summary []) :encrypted_content encrypted_content})
    "function_call" (select-keys item [:type :call_id :name :arguments])
    "message" (when-let [text (not-empty (message-text item))]
                {:role "assistant" :content text})
    nil))

(defn ^:private tool-result
  "Runs the model's function call through `call-tool`: what to tell it."
  [call-tool {:keys [name arguments]}]
  (let [args (try (json/parse-string (if (str/blank? arguments) "{}" arguments) true)
                  (catch Exception _ nil))]
    (if (map? args)
      (call-tool name args)
      "error: the arguments weren't a JSON object")))

;; Requests per turn: the model can call tools in all but the last one.
(def ^:private max-requests 6)

(defn stream-reply!
  "Streams the model's answer to `request` ({:system :messages :location
  :timezone}, and :tools with :call-tool, see grog.provider), calling
  `on-text` with each chunk of text, and `on-tool` with {:name} when the model
  starts using a tool (e.g. \"web_search\"). When it calls one of `tools`,
  runs it and asks again with the result. Returns {:text :stop-reason :usage
  :quota}: what the turn used ({:auth :model :input-tokens :cached-tokens
  :output-tokens :web-searches}, and with an API key its :usd, nil without a
  price for the model), and with the subscription how much of its limits is
  used (grog.providers.openai.quota), when the backend said."
  [cfg {:keys [call-tool] :as request} on-text on-tool]
  (let [out (StringBuilder.)
        emit! (fn [^String s] (.append out s) (on-text s))
        creds (credentials cfg)
        subscription? (= :subscription (:type creds))
        turn-id (str (random-uuid))
        ask! (fn [request emitted?]
               (if subscription?
                 (try
                   (stream! cfg (subscription-request cfg request turn-id false) emit! on-tool emitted?)
                   (catch clojure.lang.ExceptionInfo e
                     ;; Rejected before any text: refresh the token and retry once.
                     (if (= 401 (:status (ex-data e)))
                       (stream! cfg (subscription-request cfg request turn-id true) emit! on-tool emitted?)
                       (throw e))))
                 (stream! cfg (api-request cfg creds request) emit! on-tool emitted?)))]
    (loop [request request
           n 1
           emitted? false
           usage {:input-tokens 0 :cached-tokens 0 :output-tokens 0 :web-searches 0}
           limits nil]
      (let [{:keys [status items headers searches] used :usage :as answer} (ask! request emitted?)
            usage (add-usage usage used searches)
            limits (or (when subscription? (quota/from-headers headers (now-s))) limits)
            calls (filter #(= "function_call" (:type %)) items)]
        (if (and (seq calls) call-tool (< n max-requests))
          (recur (cond-> (update request :items (fnil into [])
                                 (concat (keep replayed items)
                                         (for [call calls]
                                           {:type "function_call_output"
                                            :call_id (:call_id call)
                                            :output (tool-result call-tool call)})))
                   ;; The last request gets an answer, not more calls.
                   (= (inc n) max-requests) (assoc :tool-choice "none"))
                 (inc n)
                 (:emitted? answer)
                 usage
                 limits)
          (let [usage (assoc usage
                             :auth (if subscription? "subscription" "api-key")
                             :model (if subscription? (get-in cfg [:subscription :model]) (:model cfg)))]
            {:text (str out)
             :stop-reason status
             :usage (cond-> usage
                      (not subscription?) (assoc :usd (cost (:prices cfg) usage)))
             :quota limits}))))))

(defn describe
  "How grog would call OpenAI now, for `grog status`: the model of the
  credentials the `auth` setting picks, even when they're missing."
  [cfg]
  (let [model (get-in cfg (model-path cfg))]
    (try
      (if (= :subscription (:type (credentials cfg)))
        (let [{:keys [email account-id expires-at]} (credentials/saved (:state-dir cfg) "openai")]
          {:auth "subscription"
           :model model
           :email email
           :account-id account-id
           :expires-at expires-at})
        {:auth "api-key" :model model})
      (catch clojure.lang.ExceptionInfo e
        {:auth "none" :model model :error (ex-message e)}))))

(defn login-needed?
  "Whether grog has nothing to pay with, and signing in to the subscription
  would fix it: neither signed in nor given an API key, and the `auth`
  setting doesn't insist on one."
  [cfg]
  (and (not= "api-key" (:auth cfg))
       (= "none" (:auth (describe cfg)))))

(defn quota
  "How much of the ChatGPT subscription's usage limits is used, when grog
  uses it (grog.providers.openai.quota); nil otherwise."
  [cfg]
  (when (and (= :subscription (mode cfg)) (signed-in? cfg))
    (quota/fetch! cfg (oauth/fresh-credentials! cfg false) (now-s))))

(defn models
  "The ChatGPT subscription's live model catalog, for `grog models`."
  [cfg]
  (when-not (signed-in? cfg)
    (throw (ex-info "Listing models needs a ChatGPT subscription: run `grog login openai`."
                    {:type ::not-signed-in})))
  (let [current (get-in cfg [:subscription :model])]
    (mapv (fn [{:keys [slug description default_reasoning_level]}]
            {:id slug
             :description description
             :default-reasoning default_reasoning_level
             :current (= current slug)})
          (subscription/models! cfg (oauth/fresh-credentials! cfg false)))))

(defn ^:private price? [v]
  (and (number? v) (not (neg? v))))

(defn ^:private model-prices? [v]
  (and (map? v)
       (price? (:input v))
       (price? (:output v))
       (or (nil? (:cached-input v)) (price? (:cached-input v)))
       (empty? (dissoc v :input :cached-input :output))))

(defn problems
  "What's wrong with the provider settings."
  [cfg]
  (let [check #(config/check cfg %1 %2 %3 [:providers :openai])]
    (->> [(check [:model] config/non-blank-string? "a model, like \"gpt-6-luna\"")
          (check [:prices :models] #(and (map? %) (every? model-prices? (vals %)))
                 "prices per million tokens of each model, like {\"gpt-6-luna\": {input, cachedInput, output}}")
          (check [:prices :web-search] price? "a price in USD per search, like 0.01")
          (check [:base-url] config/url? "a URL")
          (check [:api-key-env] config/non-blank-string? "an environment variable name")
          (check [:max-tokens] pos-int? "a positive integer")
          (check [:reasoning-effort] (some-fn nil? config/non-blank-string?) "an effort, like \"low\", or null")
          (check [:timeout-ms] pos-int? "a positive integer")
          (check [:web-search :enable] boolean? "true or false")
          (check [:auth] #{"auto" "subscription" "api-key"} "\"auto\", \"subscription\" or \"api-key\"")
          (check [:subscription :model] config/non-blank-string? "a model (see `grog models`)")
          (check [:subscription :base-url] config/url? "a URL")
          (check [:subscription :auth-url] config/url? "a URL")
          (check [:subscription :callback-port] config/port? "a port number")]
         (remove nil?))))

;; See grog.provider. Vars, so nREPL redefinitions take effect right away.
(def provider
  {:name "OpenAI"
   :validate #'problems
   :stream-reply! #'stream-reply!
   :describe #'describe
   :quota #'quota
   :models #'models
   :model-path #'model-path
   :model-paths [[:model] [:subscription :model]]
   :login {:label "ChatGPT"
           :needed? #'login-needed?
           :exchange-code! #'oauth/exchange-code!
           :device-code! #'oauth/device-code!
           :poll-device! #'oauth/poll-device!
           :device-help oauth/device-help
           :redirect-uri #'oauth/redirect-uri
           :authorize-url #'oauth/authorize-url}})