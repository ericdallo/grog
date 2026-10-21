(ns grog.providers.openai.oauth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.credentials :as credentials]
            [grog.config :as config]
            [grog.oauth :as oauth]
            [grog.providers.openai.oauth :as openai-oauth]
            [grog.test-helpers :as h]))

(defn ^:private cfg [url]
  (-> (get-in config/defaults [:providers :openai])
      (assoc-in [:subscription :auth-url] url)
      (assoc :state-dir (h/temp-dir))))

(defn ^:private now [] (quot (System/currentTimeMillis) 1000))

(deftest authorize-url
  (let [cfg (cfg "https://auth.example")
        url (openai-oauth/authorize-url cfg {:challenge "ch"
                                             :state "st"
                                             :redirect-uri (openai-oauth/redirect-uri cfg)})
        [base query] (str/split url #"\?" 2)]
    (is (= "https://auth.example/oauth/authorize" base))
    (is (= {:client_id openai-oauth/client-id
            :response_type "code"
            :redirect_uri "http://localhost:1455/auth/callback"
            :scope "openid profile email offline_access"
            :id_token_add_organizations "true"
            :prompt "login"
            :codex_cli_simplified_flow "true"
            :code_challenge "ch"
            :code_challenge_method "S256"
            :state "st"}
           (oauth/form-decode query)))))

(deftest exchange-and-refresh
  (h/with-fake [fake [(h/token-response "access-1" "refresh-1")
                      (h/token-response "access-2" nil)]]
    (let [cfg (cfg (:url fake))
          creds (openai-oauth/exchange-code! cfg {:code "the-code"
                                                  :verifier "the-verifier"
                                                  :redirect-uri "http://localhost:1455/auth/callback"})
          refreshed (openai-oauth/refresh! cfg creds)
          [exchange refresh] @(:requests fake)]
      (testing "the code exchange"
        (is (= "/oauth/token" (:uri exchange)))
        (is (= {:grant_type "authorization_code"
                :client_id openai-oauth/client-id
                :code "the-code"
                :code_verifier "the-verifier"
                :redirect_uri "http://localhost:1455/auth/callback"}
               (:body exchange)))
        (is (= {:type "chatgpt" :access-token "access-1" :refresh-token "refresh-1"
                :account-id "acct-1" :email "grog@example.com"}
               (dissoc creds :expires-at)))
        (is (<= 3595 (- (:expires-at creds) (now)) 3600)))
      (testing "the refresh keeps the refresh token when no new one comes"
        (is (= {:grant_type "refresh_token" :client_id openai-oauth/client-id :refresh_token "refresh-1"}
               (:body refresh)))
        (is (= {:type "chatgpt" :access-token "access-2" :refresh-token "refresh-1"
                :account-id "acct-1" :email "grog@example.com"}
               (dissoc refreshed :expires-at)))))))

(deftest token-errors
  (h/with-fake [fake [{:status 400
                       :headers {"Content-Type" "application/json"}
                       :body "{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token expired\"}"}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"returned 400: Refresh token expired"
                          (openai-oauth/refresh! (cfg (:url fake)) {:refresh-token "old"})))))

(deftest fresh-credentials
  (h/with-fake [fake [(h/token-response "access-new" "refresh-new")]]
    (let [cfg (cfg (:url fake))]
      (credentials/save! (:state-dir cfg) "openai" {:access-token "valid" :refresh-token "r" :expires-at (+ (now) 3600)})
      (testing "valid credentials are used as they are"
        (is (= "valid" (:access-token (openai-oauth/fresh-credentials! cfg false))))
        (is (empty? @(:requests fake))))
      (testing "expiring ones are refreshed and saved"
        (credentials/save! (:state-dir cfg) "openai" {:access-token "old" :refresh-token "r" :expires-at (+ (now) 30)})
        (is (= "access-new" (:access-token (openai-oauth/fresh-credentials! cfg false))))
        (is (= "refresh-new" (:refresh-token (credentials/saved (:state-dir cfg) "openai"))))))))
