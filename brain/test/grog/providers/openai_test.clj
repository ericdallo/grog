(ns grog.providers.openai-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.connections :as connections]
            [grog.credentials :as credentials]
            [grog.config :as config]
            [grog.providers.openai.oauth :as oauth]
            [grog.providers.openai.subscription :as subscription]
            [grog.providers.http :as http]
            [grog.providers.openai :as openai]
            [org.httpkit.server :as http-server]
            [grog.test-helpers :as h]))

(defn ^:private cfg [url]
  (assoc (get-in config/defaults [:providers :openai]) :base-url url))

(def ^:private request
  {:system "sys"
   :messages [{:role "user" :content "hi"} {:role "assistant" :content "hello"} {:role "user" :content "what's up?"}]
   :location {:city "São Paulo" :country "BR"}
   :timezone "America/Sao_Paulo"})

(deftest streams-text-and-builds-the-request
  (h/with-fake [fake [{:body (h/sse {:type "response.created" :response {:status "in_progress"}}
                                    {:type "response.output_item.added" :item {:type "web_search_call"}}
                                    {:type "response.output_item.added" :item {:type "message"}}
                                    {:type "response.output_text.delta" :delta "All"}
                                    {:type "response.output_text.delta" :delta " good."}
                                    {:type "response.completed"
                                     :response {:status "completed"
                                                :usage {:input_tokens 12
                                                        :input_tokens_details {:cached_tokens 2}
                                                        :output_tokens 4}}})}]]
    (with-redefs [http/api-key (constantly "test-key")]
      (let [[on-text chunks] (h/collector)
            result (openai/stream-reply! (cfg (:url fake)) request on-text identity)
            [{:keys [uri headers body]}] @(:requests fake)]
        (is (= ["All" " good."] @chunks))
        (is (= {:text "All good." :stop-reason "completed" :quota nil
                :usage {:auth "api-key" :model "gpt-6-luna"
                        :input-tokens 12 :cached-tokens 2 :output-tokens 4 :web-searches 1}}
               (update result :usage dissoc :usd)))
        (testing "what it cost: the tokens, the cached ones cheaper, and the search"
          (is (< (abs (- (+ (/ (+ (* 10 0.10) (* 2 0.01) (* 4 0.50)) 1e6) 0.01)
                         (get-in result [:usage :usd])))
                 1e-12)))
        (is (= "/v1/responses" uri))
        (is (= "Bearer test-key" (get headers "authorization")))
        (is (= {:model "gpt-6-luna"
                :instructions "sys"
                :input (:messages request)
                :max_output_tokens 2048
                :store false
                :stream true
                :reasoning {:effort "low"}
                :tools [{:type "web_search"
                         :user_location {:type "approximate" :city "São Paulo" :country "BR"
                                         :timezone "America/Sao_Paulo"}}]}
               body))))))

(deftest tells-when-the-model-uses-a-tool
  (h/with-fake [fake [{:body (h/sse {:type "response.output_item.added" :item {:type "reasoning"}}
                                    {:type "response.output_item.added" :item {:type "web_search_call"}}
                                    {:type "response.output_item.done"
                                     :item {:type "web_search_call" :action {:type "search" :query "weather SP"}}}
                                    {:type "response.output_item.added" :item {:type "function_call" :name "get_time"}}
                                    {:type "response.output_item.added" :item {:type "message"}}
                                    {:type "response.output_text.delta" :delta "Noon."}
                                    {:type "response.completed" :response {:status "completed"}})}]]
    (with-redefs [http/api-key (constantly "k")]
      (let [[on-tool tools] (h/collector)]
        (is (= "Noon." (:text (openai/stream-reply! (cfg (:url fake)) request identity on-tool))))
        (is (= [{:name "web_search"} {:name "web_search" :detail "weather SP"} {:name "get_time"}] @tools)
            "and what it searched for, once it knows")))))

(deftest separates-messages
  (h/with-fake [fake [{:body (h/sse {:type "response.output_item.added" :item {:type "message"}}
                                    {:type "response.output_text.delta" :delta "Let me see."}
                                    {:type "response.output_item.added" :item {:type "web_search_call"}}
                                    {:type "response.output_item.added" :item {:type "message"}}
                                    {:type "response.output_text.delta" :delta "Sunny."}
                                    {:type "response.completed" :response {:status "completed"}})}]]
    (with-redefs [http/api-key (constantly "k")]
      (let [[on-text chunks] (h/collector)]
        (is (= "Let me see. Sunny." (:text (openai/stream-reply! (cfg (:url fake)) request on-text identity))))
        (is (= "Let me see. Sunny." (str/join @chunks)))))))

(deftest drops-the-sources-web-search-cites
  (h/with-fake [fake [{:body (h/sse {:type "response.output_item.added" :item {:type "message"}}
                                    {:type "response.output_text.delta" :delta "Vou ver. "}
                                    {:type "response.output_item.added" :item {:type "web_search_call"}}
                                    {:type "response.output_item.added" :item {:type "message"}}
                                    {:type "response.output_text.delta" :delta "Sol. ([tempo.com](https://tem"}
                                    {:type "response.output_text.delta" :delta "po.com/sp?utm_source=openai))"}
                                    {:type "response.completed" :response {:status "completed"}})}]]
    (with-redefs [http/api-key (constantly "k")]
      (let [[on-event events] (h/collector)
            result (openai/stream-reply! (cfg (:url fake)) request
                                         #(on-event [:text %]) #(on-event [:tool (:name %)]))]
        (is (= "Vou ver. Sol." (:text result)))
        (testing "what was said before the tool comes before it"
          (is (= [[:text "Vou ver."] [:tool "web_search"] [:text " Sol."]] @events)))))))

(def ^:private memory-tool
  {:name "memory"
   :description "Remembers things."
   :parameters {:type "object" :properties {:remember {:type "string"}}}})

(defn ^:private function-call
  "The events of the model calling `name` with `arguments` (JSON text)."
  [call-id name arguments]
  [{:type "response.output_item.added" :item {:type "function_call" :call_id call-id :name name :arguments ""}}
   {:type "response.function_call_arguments.delta" :delta arguments}
   {:type "response.output_item.done"
    :item {:type "function_call" :id (str "fc_" call-id) :call_id call-id :name name :arguments arguments}}])

(defn ^:private completed [input-tokens output-tokens]
  {:type "response.completed"
   :response {:status "completed" :usage {:input_tokens input-tokens :output_tokens output-tokens}}})

(defn ^:private text-answer [text]
  {:body (h/sse {:type "response.output_item.added" :item {:type "message"}}
                {:type "response.output_text.delta" :delta text}
                (completed 20 2))})

(deftest runs-the-tools-the-model-calls
  (h/with-fake [fake [{:body (apply h/sse (concat [{:type "response.output_item.added" :item {:type "reasoning"}}
                                                   {:type "response.output_item.done"
                                                    :item {:type "reasoning" :id "rs_1" :summary [] :encrypted_content "secret"}}
                                                   {:type "response.output_item.added" :item {:type "message"}}
                                                   {:type "response.output_text.delta" :delta "Vou anotar."}
                                                   {:type "response.output_item.done"
                                                    :item {:type "message" :role "assistant"
                                                           :content [{:type "output_text" :text "Vou anotar."}]}}]
                                                  (function-call "call_1" "memory" "{\"remember\":\"O lixo passa terça.\"}")
                                                  [(completed 10 5)]))}
                      (text-answer "Anotado.")]]
    (with-redefs [http/api-key (constantly "k")]
      (let [calls (atom [])
            [on-event events] (h/collector)
            result (openai/stream-reply! (cfg (:url fake))
                                         (assoc request
                                                :tools [memory-tool]
                                                :call-tool (fn [name args] (swap! calls conj [name args]) "ok"))
                                         #(on-event [:text %]) #(on-event [:tool (:name %)]))
            [first-request second-request] (map :body @(:requests fake))]
        (is (= [["memory" {:remember "O lixo passa terça."}]] @calls))
        (is (= "Vou anotar. Anotado." (:text result)))
        (is (= [[:text "Vou anotar."] [:tool "memory"] [:text " Anotado."]] @events))
        (is (= {:auth "api-key" :model "gpt-6-luna" :input-tokens 30 :cached-tokens 0 :output-tokens 7 :web-searches 0}
               (dissoc (:usage result) :usd))
            "both requests")
        (testing "the tools are offered next to web search, and the reasoning is sent back"
          (is (= ["web_search" "function"] (map :type (:tools first-request))))
          (is (= {:type "function" :name "memory" :description "Remembers things."
                  :parameters (:parameters memory-tool) :strict false}
                 (second (:tools first-request))))
          (is (= ["reasoning.encrypted_content"] (:include first-request))))
        (testing "the next request carries what the model did, and the result"
          (is (= (into (:messages request)
                       [{:type "reasoning" :id "rs_1" :summary [] :encrypted_content "secret"}
                        {:role "assistant" :content "Vou anotar."}
                        {:type "function_call" :call_id "call_1" :name "memory"
                         :arguments "{\"remember\":\"O lixo passa terça.\"}"}
                        {:type "function_call_output" :call_id "call_1" :output "ok"}])
                 (:input second-request)))
          (is (nil? (:tool_choice second-request))))))))

(deftest asks-for-an-answer-after-too-many-tool-calls
  (let [n (dec openai/max-requests)]
    (is (<= 20 n) "plenty for a turn")
    (h/with-fake [fake (conj (vec (for [i (range n)]
                                    {:body (apply h/sse (concat (function-call (str "call_" i) "memory" "{}")
                                                                [(completed 1 1)]))}))
                             (text-answer "Chega."))]
      (with-redefs [http/api-key (constantly "k")]
        (let [calls (atom 0)
              result (openai/stream-reply! (cfg (:url fake))
                                           (assoc request :tools [memory-tool] :call-tool (fn [_ _] (swap! calls inc) "ok"))
                                           identity identity)
              bodies (map :body @(:requests fake))]
          (is (= "Chega." (:text result)))
          (is (= n @calls))
          (is (= (concat (repeat n nil) ["none"]) (map :tool_choice bodies))))))))

(deftest tells-the-model-its-arguments-are-bad
  ;; The call only comes in the final event, as some streams have it.
  (h/with-fake [fake [{:body (h/sse {:type "response.output_item.added" :item {:type "function_call" :name "memory"}}
                                    {:type "response.completed"
                                     :response {:status "completed"
                                                :output [{:type "function_call" :call_id "call_1"
                                                          :name "memory" :arguments "{oops"}]}})}
                      (text-answer "Não deu.")]]
    (with-redefs [http/api-key (constantly "k")]
      (let [calls (atom 0)
            result (openai/stream-reply! (cfg (:url fake))
                                         (assoc request :tools [memory-tool] :call-tool (fn [_ _] (swap! calls inc) "ok"))
                                         identity identity)]
        (is (= "Não deu." (:text result)))
        (is (zero? @calls))
        (is (= {:type "function_call_output" :call_id "call_1" :output "error: the arguments weren't a JSON object"}
               (last (get-in @(:requests fake) [1 :body :input]))))))))

(deftest api-errors
  (testing "HTTP error"
    (h/with-fake [fake [{:status 429 :headers {"Content-Type" "application/json"}
                         :body "{\"error\":{\"message\":\"Rate limit\",\"type\":\"requests\"}}"}]]
      (with-redefs [http/api-key (constantly "k")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"The OpenAI API returned 429: Rate limit"
                              (openai/stream-reply! (cfg (:url fake)) request identity identity))))))

  (testing "failed response"
    (h/with-fake [fake [{:body (h/sse {:type "response.failed"
                                       :response {:status "failed" :error {:message "boom"}}})}]]
      (with-redefs [http/api-key (constantly "k")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"OpenAI API error: boom"
                              (openai/stream-reply! (cfg (:url fake)) request identity identity)))))))

(deftest slow-apis
  (testing "no answer at all within timeoutMs"
    (with-open [server (java.net.ServerSocket. 0)]
      (future (.accept server))
      (with-redefs [http/api-key (constantly "k")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"The OpenAI API didn't answer within 1 s"
                              (openai/stream-reply! (assoc (cfg (str "http://127.0.0.1:" (.getLocalPort server)))
                                                           :timeout-ms 1000)
                                                    request identity identity))))))

  (testing "an answer that stalls for timeoutMs"
    (let [upstream (h/start-handler!
                    (fn [req]
                      (http-server/as-channel
                       req
                       {:on-open (fn [ch]
                                   (http-server/send! ch {:status 200 :headers {"Content-Type" "text/event-stream"}} false)
                                   (http-server/send! ch (h/sse {:type "response.output_text.delta" :delta "Well"}) false))})))
          [on-text chunks] (h/collector)]
      (try
        (with-redefs [http/api-key (constantly "k")]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"The OpenAI API stopped sending the answer for 1 s"
                                (openai/stream-reply! (assoc (cfg (:url upstream)) :timeout-ms 1000) request on-text identity)))
          (is (= ["Well"] @chunks)))
        (finally
          ((:stop! upstream)))))))

(defn ^:private subscription-cfg [url]
  (-> (cfg url)
      (assoc-in [:subscription :auth-url] url)
      (assoc-in [:subscription :base-url] (str url "/codex"))
      (assoc :state-dir (h/temp-dir))))

(defn ^:private sign-in! [cfg expires-in]
  (credentials/save! (:state-dir cfg) "openai"
                     {:type "chatgpt"
                      :access-token "sub-token"
                      :refresh-token "sub-refresh"
                      :account-id "acct-1"
                      :email "grog@example.com"
                      :expires-at (+ (quot (System/currentTimeMillis) 1000) expires-in)}))

(def ^:private answer
  {:headers {"Content-Type" "text/event-stream"
             ;; How much of the subscription's limits is used.
             "x-codex-primary-used-percent" "12.5"
             "x-codex-primary-window-minutes" "300"
             "x-codex-primary-reset-after-seconds" "3600"
             "x-codex-secondary-used-percent" "40"
             "x-codex-secondary-window-minutes" "10080"
             "x-codex-secondary-reset-at" "1760000000"}
   :body (h/sse {:type "response.output_item.added" :item {:type "message"}}
                {:type "response.output_text.delta" :delta "From the subscription."}
                {:type "response.completed"
                 :response {:status "completed" :usage {:input_tokens 3 :output_tokens 2}}})})

(deftest subscription
  ;; Like the live catalog: every model asks for Responses Lite.
  (subscription/reset-catalog! {"gpt-6-luna" {:slug "gpt-6-luna" :use_responses_lite true}})
  (h/with-fake [fake [answer]]
    (let [cfg (subscription-cfg (:url fake))]
      (sign-in! cfg 3600)
      ;; An API key is around too: the subscription wins.
      (with-redefs [http/api-key (constantly "api-key")]
        (let [[on-text chunks] (h/collector)
              result (openai/stream-reply! cfg request on-text identity)
              [{:keys [uri headers body]}] @(:requests fake)]
          (is (= ["From the subscription."] @chunks))
          (is (= {:auth "subscription" :model "gpt-6-luna" :input-tokens 3 :cached-tokens 0 :output-tokens 2
                  :web-searches 0}
                 (:usage result))
              "no dollars: the subscription pays")
          (testing "how much of its limits is used, from the answer's headers"
            (let [now-s (quot (System/currentTimeMillis) 1000)
                  [primary secondary] (get-in result [:quota :windows])]
              (is (= {:id "primary" :label "5h" :used-percent 12.5 :window-minutes 300}
                     (dissoc primary :resets-at)))
              (is (<= (+ now-s 3590) (:resets-at primary) (+ now-s 3600)))
              (is (= {:id "secondary" :label "7d" :used-percent 40.0 :window-minutes 10080 :resets-at 1760000000}
                     secondary))))
          (is (= "/codex/responses" uri))
          (is (= "Bearer sub-token" (get headers "authorization")))
          (is (= "acct-1" (get headers "chatgpt-account-id")))
          (is (= "codex_cli_rs" (get headers "originator")))
          (is (= "gpt-6-luna" (:model body)))
          (is (not (contains? body :max_output_tokens)))
          (testing "web search takes the regular shape, which Lite models accept too"
            (is (nil? (get headers "x-openai-internal-codex-responses-lite")))
            (is (= "sys" (:instructions body)))
            (is (= (:messages request) (:input body)))
            (is (= [{:type "web_search"
                     :user_location {:type "approximate" :city "São Paulo" :country "BR"
                                     :timezone "America/Sao_Paulo"}}]
                   (:tools body))))))))
  (testing "without web search, the Lite shape the catalog asks for"
    (h/with-fake [fake [answer]]
      (let [cfg (assoc-in (subscription-cfg (:url fake)) [:web-search :enable] false)]
        (sign-in! cfg 3600)
        (is (= "From the subscription." (:text (openai/stream-reply! cfg request identity identity))))
        (let [[{:keys [headers body]}] @(:requests fake)]
          (is (= "true" (get headers "x-openai-internal-codex-responses-lite")))
          (is (not (contains? body :instructions)))
          (is (= "additional_tools" (get-in body [:input 0 :type])))))))
  (subscription/reset-catalog!))

(deftest subscription-refreshes-tokens
  (subscription/reset-catalog! {})
  (testing "about to expire: refreshed before the request"
    (h/with-fake [fake [(h/token-response "fresh-token" "fresh-refresh") answer]]
      (let [cfg (subscription-cfg (:url fake))]
        (sign-in! cfg 10)
        (openai/stream-reply! cfg request identity identity)
        (let [[refresh call] @(:requests fake)]
          (is (= "/oauth/token" (:uri refresh)))
          (is (= "sub-refresh" (get-in refresh [:body :refresh_token])))
          (is (= "Bearer fresh-token" (get-in call [:headers "authorization"])))
          (is (= "fresh-refresh" (:refresh-token (credentials/saved (:state-dir cfg) "openai"))))))))
  (testing "rejected (401): refreshed and retried once"
    (h/with-fake [fake [{:status 401
                         :headers {"Content-Type" "application/json"}
                         :body "{\"error\":{\"message\":\"token expired\"}}"}
                        (h/token-response "fresh-token" "fresh-refresh")
                        answer]]
      (let [cfg (subscription-cfg (:url fake))]
        (sign-in! cfg 3600)
        (is (= "From the subscription." (:text (openai/stream-reply! cfg request identity identity))))
        (is (= ["/codex/responses" "/oauth/token" "/codex/responses"] (map :uri @(:requests fake)))))))
  (testing "revoked: says to sign in again"
    (h/with-fake [fake [{:status 400
                         :headers {"Content-Type" "application/json"}
                         :body "{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token revoked\"}"}]]
      (let [cfg (subscription-cfg (:url fake))]
        (sign-in! cfg 10)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"The ChatGPT sign-in is no longer valid \(.*Refresh token revoked\)\. Sign in again: grog login openai"
                              (openai/stream-reply! cfg request identity identity)))
        (testing "and its connection needs it: grog pays some other way meanwhile"
          (is (= "signed-out" (:status (connections/by-id (:state-dir cfg) "openai"))))
          (is (= {:auth "none" :model "gpt-6-luna"}
                 (select-keys (openai/describe (assoc cfg :api-key-env "GROG_TEST_UNSET_VAR")) [:auth :model])))
          (with-redefs [http/api-key (constantly "k")]
            (is (= {:type :api-key :key "k"} (openai/credentials cfg)))))))))

(deftest credentials
  (let [cfg (subscription-cfg "http://127.0.0.1:1")]
    (testing "auto, not signed in: the API key"
      (with-redefs [http/api-key (constantly "k")]
        (is (= {:type :api-key :key "k"} (openai/credentials cfg)))))
    (testing "auto, with neither"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"run `grog login openai`.*GROG_TEST_UNSET_VAR"
                            (openai/credentials (assoc cfg :api-key-env "GROG_TEST_UNSET_VAR")))))
    (testing "subscription forced without signing in"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not signed in"
                            (openai/credentials (assoc cfg :auth "subscription")))))
    (sign-in! cfg 3600)
    (testing "auto, signed in: the subscription"
      (is (= {:type :subscription} (openai/credentials cfg))))
    (testing "API key forced"
      (with-redefs [http/api-key (constantly "k")]
        (is (= {:type :api-key :key "k"} (openai/credentials (assoc cfg :auth "api-key"))))))))

(deftest how-grog-pays
  (is (= [:subscription :api-key :subscription :subscription :api-key :api-key]
         [(openai/auth-mode "auto" true) (openai/auth-mode "auto" false)
          (openai/auth-mode "subscription" true) (openai/auth-mode "subscription" false)
          (openai/auth-mode "api-key" true) (openai/auth-mode "api-key" false)])))

(deftest where-the-model-in-use-is
  (let [cfg (subscription-cfg "http://127.0.0.1:1")]
    (testing "auto, not signed in: the API's"
      (is (= [:model] (openai/model-path cfg))))
    (testing "the subscription forced"
      (is (= [:subscription :model] (openai/model-path (assoc cfg :auth "subscription")))))
    (sign-in! cfg 3600)
    (testing "auto, signed in: the subscription's"
      (is (= [:subscription :model] (openai/model-path cfg))))
    (testing "the API key forced"
      (is (= [:model] (openai/model-path (assoc cfg :auth "api-key")))))))

(deftest describe-and-models
  (subscription/reset-catalog!)
  (h/with-fake [fake [(h/json-response {:models [{:slug "gpt-6.1-sol" :use_responses_lite true
                                                  :description "Workhorse." :default_reasoning_level "low"}
                                                 {:slug "gpt-6-luna" :use_responses_lite true}]})]]
    (let [cfg (assoc (subscription-cfg (:url fake)) :api-key-env "GROG_TEST_UNSET_VAR")]
      (is (= "none" (:auth (openai/describe cfg))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"grog login openai" (openai/models cfg)))
      (sign-in! cfg 3600)
      (is (= {:auth "subscription" :model "gpt-6-luna" :email "grog@example.com" :account-id "acct-1"}
             (dissoc (openai/describe cfg) :expires-at)))
      (is (= [{:id "gpt-6.1-sol" :description "Workhorse." :default-reasoning "low" :current false}
              {:id "gpt-6-luna" :description nil :default-reasoning nil :current true}]
             (openai/models cfg)))))
  (subscription/reset-catalog!))

(deftest signing-in-with-a-code
  (h/with-fake [fake [(h/json-response {:device_auth_id "dev-1" :user_code "ABCD-12345" :interval "3"})
                      {:status 403 :headers {"Content-Type" "application/json"} :body "{\"error\":\"pending\"}"}
                      (h/json-response {:authorization_code "the-code" :code_challenge "ch" :code_verifier "the-verifier"})]]
    (let [cfg (subscription-cfg (:url fake))
          device (oauth/device-code! cfg)]
      (is (= {:device-auth-id "dev-1" :user-code "ABCD-12345" :interval-s 3 :url (str (:url fake) "/codex/device")}
             device))
      (is (nil? (oauth/poll-device! cfg device)) "not entered yet")
      (is (= {:code "the-code" :verifier "the-verifier" :redirect-uri (str (:url fake) "/deviceauth/callback")}
             (oauth/poll-device! cfg device)))
      (let [[usercode pending entered] @(:requests fake)]
        (is (= ["/api/accounts/deviceauth/usercode" {:client_id oauth/client-id}]
               [(:uri usercode) (:body usercode)]))
        (is (= ["/api/accounts/deviceauth/token" {:device_auth_id "dev-1" :user_code "ABCD-12345"}]
               [(:uri pending) (:body pending)]
               [(:uri entered) (:body entered)]))))))

(deftest codes-as-openai-sends-them
  (testing "the older field name, an interval as a number, or none"
    (h/with-fake [fake [(h/json-response {:device_auth_id "d" :usercode "C-1" :interval 2})
                        (h/json-response {:device_auth_id "d" :user_code "C-2"})]]
      (let [cfg (subscription-cfg (:url fake))]
        (is (= ["C-1" 2] ((juxt :user-code :interval-s) (oauth/device-code! cfg))))
        (is (= ["C-2" 5] ((juxt :user-code :interval-s) (oauth/device-code! cfg)))))))
  (testing "where it isn't offered"
    (h/with-fake [fake [{:status 404 :body "Not found"}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"grog login --browser"
                            (oauth/device-code! (subscription-cfg (:url fake)))))))
  (testing "failing"
    (h/with-fake [fake [{:status 500 :headers {"Content-Type" "application/json"}
                         :body "{\"error\":{\"message\":\"Down.\"}}"}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"returned 500: Down\."
                            (oauth/device-code! (subscription-cfg (:url fake)))))))
  (testing "out of reach"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Couldn't reach OpenAI's sign-in server"
                          (oauth/device-code! (subscription-cfg "http://127.0.0.1:1"))))))

(deftest when-signing-in-helps
  (let [cfg (assoc (subscription-cfg "http://127.0.0.1:1") :api-key-env "GROG_TEST_UNSET_VAR")]
    (is (openai/login-needed? cfg) "nothing to pay with")
    (is (not (openai/login-needed? (assoc cfg :api-key-env "HOME"))) "an API key")
    (is (not (openai/login-needed? (assoc cfg :auth "api-key"))) "insisting on an API key, even a missing one")
    (sign-in! cfg 3600)
    (is (not (openai/login-needed? cfg)) "signed in")))

(deftest describe-without-the-credentials-asked-for
  (let [cfg (-> (subscription-cfg "http://127.0.0.1:1")
                (assoc :auth "subscription" :model "gpt-api")
                (assoc-in [:subscription :model] "gpt-sub"))]
    (is (= {:auth "none" :model "gpt-sub"} (dissoc (openai/describe cfg) :error))
        "the subscription's model, which it would call")))

(deftest what-an-api-key-pays
  (let [prices (get-in config/defaults [:providers :openai :prices])]
    (is (< (abs (- (/ (+ (* 800 0.10) (* 200 0.01) (* 100 0.50)) 1e6)
                   (openai/cost prices {:model "gpt-6-luna" :input-tokens 1000 :cached-tokens 200
                                        :output-tokens 100 :web-searches 0})))
           1e-15))
    (is (< (abs (- 0.02 (openai/cost prices {:model "gpt-6-luna" :input-tokens 0 :cached-tokens 0
                                             :output-tokens 0 :web-searches 2})))
           1e-15)
        "a cent a search")
    (testing "a model without a price"
      (is (nil? (openai/cost prices {:model "gpt-9" :input-tokens 1 :cached-tokens 0 :output-tokens 1
                                     :web-searches 0}))))
    (testing "without a cached price, cached tokens cost like the others"
      (is (= (openai/cost {:models {:m {:input 1 :output 1}}} {:model "m" :input-tokens 10 :cached-tokens 4
                                                               :output-tokens 0 :web-searches 0})
             (openai/cost {:models {:m {:input 1 :output 1}}} {:model "m" :input-tokens 10 :cached-tokens 0
                                                               :output-tokens 0 :web-searches 0}))))))

(deftest prices-are-checked
  (let [problems #(openai/problems (merge (get-in config/defaults [:providers :openai]) %))]
    (is (empty? (problems {})))
    (is (seq (problems {:prices {:models {:gpt-6-luna {:input -1 :output 1}} :web-search 0.01}})))
    (is (seq (problems {:prices {:models {:gpt-6-luna {:input 1}} :web-search 0.01}})))
    (is (seq (problems {:prices {:models {:gpt-6-luna {:input 1 :output 1 :typo 2}} :web-search 0.01}})))
    (is (seq (problems {:prices {:models {} :web-search "free"}})))))

(deftest subscription-lite-takes-grogs-tools-in-the-input
  (subscription/reset-catalog! {"gpt-6-luna" {:slug "gpt-6-luna" :use_responses_lite true}})
  (h/with-fake [fake [answer]]
    (let [cfg (assoc-in (subscription-cfg (:url fake)) [:web-search :enable] false)]
      (sign-in! cfg 3600)
      (openai/stream-reply! cfg (assoc request :tools [memory-tool] :call-tool (fn [_ _] "ok")) identity identity)
      (let [[{:keys [headers body]}] @(:requests fake)]
        (is (= "true" (get headers "x-openai-internal-codex-responses-lite")))
        (is (= [{:type "function" :name "memory" :description "Remembers things."
                 :parameters (:parameters memory-tool) :strict false}]
               (get-in body [:input 0 :tools])))
        (is (not (contains? body :tools))))))
  (subscription/reset-catalog!))
