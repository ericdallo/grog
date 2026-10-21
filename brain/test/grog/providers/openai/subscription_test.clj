(ns grog.providers.openai.subscription-test
  (:require [clojure.test :refer [deftest is testing]]
            [grog.config :as config]
            [grog.providers.openai.subscription :as subscription]
            [grog.test-helpers :as h]))

(defn ^:private cfg [url]
  (assoc-in (get-in config/defaults [:providers :openai]) [:subscription :base-url] (str url "/codex")))

(deftest headers
  (is (= {"authorization" "Bearer tok"
          "originator" "codex_cli_rs"
          "user-agent" (str "codex_cli_rs/" subscription/compatibility-version)
          "session-id" "t"
          "thread-id" "t"
          "x-client-request-id" "t"
          "chatgpt-account-id" "acct"}
         (subscription/headers {:access-token "tok" :account-id "acct"} "t" false)))
  (is (= "true" (get (subscription/headers {:access-token "tok"} "t" true)
                     "x-openai-internal-codex-responses-lite"))))

(deftest request-body
  (let [request {:system "sys" :messages [{:role "user" :content "hi"}]}
        settings (get-in config/defaults [:providers :openai])
        search [{:type "web_search" :user_location {:type "approximate" :timezone "America/Sao_Paulo"}}]]
    (testing "regular shape, with the tools"
      (is (= {:model "m"
              :instructions "sys"
              :input [{:role "user" :content "hi"}]
              :tools search
              :tool_choice "auto"
              :reasoning {:summary "auto" :effort "low"}
              :include ["reasoning.encrypted_content"]
              :prompt_cache_key "grog"
              :store false
              :stream true}
             (subscription/request-body settings request {:model "m" :tools search}))))
    (testing "Responses Lite shape: instructions in the input"
      (is (= {:model "m"
              :input [{:type "additional_tools" :role "developer" :tools []}
                      {:type "message" :role "developer" :content [{:type "input_text" :text "sys"}]}
                      {:role "user" :content "hi"}]
              :tool_choice "auto"
              :parallel_tool_calls false
              :reasoning {:summary "auto" :effort "low" :context "all_turns"}
              :include ["reasoning.encrypted_content"]
              :prompt_cache_key "grog"
              :store false
              :stream true}
             (subscription/request-body settings request {:model "m" :tools [] :lite? true}))))))

(deftest lite-detection
  (h/with-fake [fake [(h/json-response {:models [{:slug "gpt-6-luna" :use_responses_lite true}
                                                 {:slug "gpt-6-astra" :use_responses_lite false}]})]]
    (let [cfg (cfg (:url fake))
          creds {:access-token "t"}]
      (subscription/reset-catalog!)
      (is (true? (subscription/lite? cfg creds "gpt-6-luna")))
      (is (false? (subscription/lite? cfg creds "gpt-6-astra")) "the live catalog wins over the fallback list")
      (is (false? (subscription/lite? cfg creds "unknown-model")))
      (is (= 1 (count @(:requests fake))) "the catalog is fetched once")
      (let [[{:keys [method uri query headers]}] @(:requests fake)]
        (is (= :get method))
        (is (= "/codex/models" uri))
        (is (= (str "client_version=" subscription/compatibility-version) query))
        (is (= "Bearer t" (get headers "authorization"))))))
  (testing "without a live catalog, the fallback list"
    (subscription/reset-catalog! {})
    (is (true? (subscription/lite? nil nil "gpt-6-astra")))
    (is (false? (subscription/lite? nil nil "unknown-model"))))
  (subscription/reset-catalog!))

(deftest fetching-the-catalog-again-after-a-failure
  (subscription/reset-catalog!)
  (h/with-fake [fake [{:status 503 :body "busy"}
                      (h/json-response {:models [{:slug "gpt-6-astra" :use_responses_lite false}]})]]
    (let [cfg (cfg (:url fake))
          now (System/currentTimeMillis)]
      (with-redefs [subscription/now-ms (constantly now)]
        (is (true? (subscription/lite? cfg {:access-token "t"} "gpt-6-astra")) "the fallback list meanwhile")
        (is (true? (subscription/lite? cfg {:access-token "t"} "gpt-6-astra")))
        (is (= 1 (count @(:requests fake))) "not again right away"))
      (with-redefs [subscription/now-ms (constantly (+ now subscription/retry-ms))]
        (is (false? (subscription/lite? cfg {:access-token "t"} "gpt-6-astra")) "a while later, the live catalog")
        (is (= 2 (count @(:requests fake)))))))
  (subscription/reset-catalog!))
