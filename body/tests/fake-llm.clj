;; Stand-in for OpenAI in the VM tests. It serves the Responses API
;; (/v1/responses), and for the ChatGPT subscription signing in with a code
;; (/api/accounts/deviceauth/...), the token endpoint (/oauth/token), the
;; Codex backend (/codex/...) and how much of its limits is used (/wham/usage,
;; and in the answers' headers). Answers say what was
;; received, so the tests can check the credentials, the system prompt and the
;; conversation history. Asked for the forecast, it searches the web first,
;; like the real model; asked to be called Jarvis, who runs the commands, for
;; the volume, or to restart, it uses grog's tools (see `calls`); told "use
;; tool <name> <json>", any tool (`asked-tool`).
(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[org.httpkit.server :as http])
(import '(java.util Base64))

(defn sse [& events]
  (apply str (for [e events]
               (str "event: " (:type e) "\ndata: " (json/generate-string e) "\n\n"))))

;; What each answer used: what an API key pays for.
(def usage {:input_tokens 2000 :input_tokens_details {:cached_tokens 1000} :output_tokens 100})

;; How much of the subscription's limits is used, as the Codex backend says.
(def limits
  {"x-codex-primary-used-percent" "12"
   "x-codex-primary-window-minutes" "300"
   "x-codex-primary-reset-after-seconds" "7800"
   "x-codex-secondary-used-percent" "40"
   "x-codex-secondary-window-minutes" "10080"
   "x-codex-secondary-reset-after-seconds" "273600"})

(defn summary [messages key system]
  (format "messages=%d key=%s name=%s"
          (count messages)
          key
          (if (str/includes? (str system) "You are Test,") "ok" "missing")))

(defn bearer [req]
  (str/replace-first (str (get-in req [:headers "authorization"])) "Bearer " ""))

(defn offers-search?
  "Whether the request offers the model web search."
  [body]
  (boolean (some #(= "web_search" (:type %)) (:tools body))))

(defn searches?
  "Whether the model searches the web: when it's offered, for the forecast
  (what the voice turn asks)."
  [body]
  (and (offers-search? body)
       (str/includes? (str (:content (last (:input body)))) "previsão")))

(defn responses-stream
  "The answer `text`, streamed. Searching, it says first what it's about to do
  (as the real model still may, unasked), and then cites its source in the
  text, like the real model."
  [text search?]
  (apply sse (concat (when search?
                       [{:type "response.output_item.added" :item {:type "message"}}
                        {:type "response.output_text.delta" :delta "Vou ver a previsão."}
                        {:type "response.output_item.added" :item {:type "web_search_call"}}])
                     [{:type "response.output_item.added" :item {:type "message"}}
                      {:type "response.output_text.delta" :delta text}]
                     (when search?
                       [{:type "response.output_text.delta"
                         :delta " ([tempo.com](https://tempo.com/sp?utm_source=openai))"}])
                     [{:type "response.completed" :response {:status "completed" :usage usage}}])))

(defn tool-result
  "The result of the model's tool call the request carries, as [the tool's
  name, what it returned], or nil."
  [{:keys [input]}]
  (when (= "function_call_output" (:type (last input)))
    [(:name (last (filter #(= "function_call" (:type %)) input))) (:output (last input))]))

(def calls
  "The tool the model calls, and how, when it's offered and the user says
  the phrase."
  [["Jarvis" "settings" "{\"name\":\"Jarvis\"}"]
   ["quem roda" "shell" "{\"command\":\"id -un | tee /tmp/hands-ran\"}"]
   ["volume em 30" "volume" "{\"level\":30}"]
   ["reinicie-se" "power" "{\"action\":\"restart\"}"]
   ["como está o pc" "computer" "{\"computer\":\"pc\",\"action\":\"status\"}"]
   ["rode no pc" "computer" "{\"computer\":\"pc\",\"action\":\"shell\",\"command\":\"id -un\"}"]])

(defn asked-tool
  "[tool arguments] when the user says \"use tool <name> <json arguments>\":
  any tool, an MCP server's say. Told to go ahead, what the user asked
  before."
  [input]
  (let [said (map #(str (:content %)) (filter #(= "user" (:role %)) input))
        said (if (str/includes? (str (last said)) "go ahead") (last (butlast said)) (last said))]
    (when-let [[_ tool arguments] (re-find #"use tool (\S+) (\{.*\})" (str said))]
      [tool arguments])))

(defn tool-call
  "[tool arguments] the model calls for the request, or nil."
  [{:keys [input tools]}]
  (let [said (str (:content (last (filter #(= "user" (:role %)) input))))]
    (or (asked-tool input)
        (some (fn [[phrase tool arguments]]
                (when (and (str/includes? said phrase) (some #(= tool (:name %)) tools))
                  [tool arguments]))
              calls))))

(defn missing-tool
  "What the model says when asked to use a tool it isn't offered, or nil."
  [{:keys [tools] :as body}]
  (when-let [[tool] (tool-call body)]
    (when-not (some #(= tool (:name %)) tools)
      (str "No tool " tool ", only " (str/join " " (keep :name tools))))))

(defn call-stream [[tool arguments]]
  (sse {:type "response.output_item.added"
        :item {:type "function_call" :call_id "call_1" :name tool :arguments ""}}
       {:type "response.output_item.done"
        :item {:type "function_call" :call_id "call_1" :name tool :arguments arguments}}
       {:type "response.completed" :response {:status "completed" :usage usage}}))

(defn after-tool
  "What the model says once its tool ran: done, with what the machine's
  tools returned."
  [[tool output]]
  (case tool
    "settings" "Pronto."
    (str "Feito: " output)))

(defn jwt [claims]
  (str "e30."
       (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes (json/generate-string claims) "UTF-8"))
       ".sig"))

(defn tokens []
  (json/generate-string
   {:access_token "test-access-token"
    :refresh_token "test-refresh-token"
    :id_token (jwt {"email" "grog@example.com"
                    "https://api.openai.com/auth" {"chatgpt_account_id" "acct-test"}})
    :expires_in 3600}))

;; Whether someone entered the code grog got to sign in with: the tests do,
;; like a person on their phone would.
(def code-entered? (atom false))

(defn handler [{:keys [uri] :as req}]
  (let [body (some-> (:body req) slurp (json/parse-string true))
        stream (fn [s] {:status 200 :headers {"Content-Type" "text/event-stream"} :body s})
        json-ok (fn [s] {:status 200 :headers {"Content-Type" "application/json"} :body s})]
    (case uri
      "/v1/responses" (stream (cond
                                (tool-result body) (responses-stream (after-tool (tool-result body)) false)
                                (missing-tool body) (responses-stream (missing-tool body) false)
                                (tool-call body) (call-stream (tool-call body))
                                :else (responses-stream (summary (:input body) (bearer req) (:instructions body))
                                                        (searches? body))))
      "/oauth/token" (json-ok (tokens))
      ;; Signing in with a code, once someone entered it (/fake/enter-code).
      "/api/accounts/deviceauth/usercode" (json-ok (json/generate-string {:device_auth_id "dev-test"
                                                                          :user_code "TEST-CODE"
                                                                          :interval "1"}))
      "/api/accounts/deviceauth/token" (if @code-entered?
                                         (json-ok (json/generate-string {:authorization_code "test-code"
                                                                         :code_challenge "test-challenge"
                                                                         :code_verifier "test-verifier"}))
                                         {:status 403 :headers {"Content-Type" "application/json"}
                                          :body "{\"error\":{\"message\":\"Not entered yet.\"}}"})
      "/fake/enter-code" (do (reset! code-entered? true)
                             (json-ok "{}"))
      ;; Like the real catalog, the model asks for Responses Lite.
      "/codex/models" (json-ok (json/generate-string {:models [{:slug "gpt-6-luna"
                                                                :use_responses_lite true
                                                                :description "Fast and affordable model for easier tasks."}]}))
      "/codex/responses" (update (stream (responses-stream
                                          (str (summary (:input body) (bearer req) (:instructions body))
                                               " account=" (get-in req [:headers "chatgpt-account-id"])
                                               " search=" (if (offers-search? body) "yes" "no"))
                                          (searches? body)))
                                 :headers merge limits)
      "/wham/usage" (json-ok (json/generate-string
                              {:plan_type "plus"
                               :rate_limit {:primary_window {:used_percent 12 :limit_window_seconds 18000
                                                             :reset_after_seconds 7800}
                                            :secondary_window {:used_percent 40 :limit_window_seconds 604800
                                                               :reset_after_seconds 273600}}}))
      {:status 404 :headers {"Content-Type" "text/plain"} :body (str "fake-llm doesn't know " uri)})))

(http/run-server handler {:ip "127.0.0.1" :port 9999})
@(promise)
