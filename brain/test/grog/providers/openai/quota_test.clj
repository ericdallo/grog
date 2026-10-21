(ns grog.providers.openai.quota-test
  (:require [clojure.test :refer [deftest is testing]]
            [grog.credentials :as credentials]
            [grog.config :as config]
            [grog.providers.http :as http]
            [grog.providers.openai :as openai]
            [grog.providers.openai.quota :as quota]
            [grog.test-helpers :as h]))

(deftest from-the-headers-of-an-answer
  (testing "Codex's windows, shortest first, resets in epoch seconds"
    (is (= {:updated-at 1000
            :windows [{:id "primary" :label "5h" :used-percent 12.3 :window-minutes 300 :resets-at 4600}
                      {:id "secondary" :label "7d" :used-percent 40.0 :window-minutes 10080 :resets-at 99999}]}
           (quota/from-headers {"X-Codex-Secondary-Used-Percent" "40"
                                "x-codex-secondary-window-minutes" "10080"
                                "x-codex-secondary-reset-at" "99999"
                                "x-codex-primary-used-percent" ["12.34"]
                                "x-codex-primary-window-minutes" "300"
                                "x-codex-primary-reset-after-seconds" "3600"
                                "content-type" "text/event-stream"}
                               1000))))
  (testing "other limits, by their own prefix"
    (is (= [{:id "other-primary" :label "other 1h" :used-percent 5.0 :window-minutes 60}]
           (:windows (quota/from-headers {"x-other-primary-used-percent" "5"
                                          "x-other-primary-window-minutes" "60"}
                                         0)))))
  (testing "none"
    (is (nil? (quota/from-headers {"content-type" "text/event-stream"} 0)))
    (is (nil? (quota/from-headers {"x-codex-primary-used-percent" "lots"} 0)))))

(def ^:private usage-body
  {:plan_type "plus"
   :rate_limit {:primary_window {:used_percent 23.4 :limit_window_seconds 18000 :reset_after_seconds 600}
                :secondary_window {:used_percent 41 :limit_window_seconds 604800
                                   :reset_at "2026-10-20T00:00:00Z"}}})

(deftest from-the-usage-endpoint
  (is (= {:updated-at 1000
          :plan "plus"
          :windows [{:id "primary" :label "5h" :used-percent 23.4 :window-minutes 300 :resets-at 1600}
                    {:id "secondary" :label "7d" :used-percent 41.0 :window-minutes 10080 :resets-at 1792454400}]}
         (quota/from-usage usage-body 1000)))
  (testing "resets in milliseconds"
    (is (= 1792454400 (-> (quota/from-usage {:rate_limit {:primary_window {:used_percent 1
                                                                           :reset_at 1792454400000}}}
                                            0)
                          :windows first :resets-at))))
  (is (nil? (quota/from-usage {:plan_type "free"} 0))))

(deftest next-to-the-codex-backend
  (is (= "https://chatgpt.com/backend-api/wham/usage"
         (quota/usage-url {:subscription {:base-url "https://chatgpt.com/backend-api/codex"}})))
  (is (= "http://127.0.0.1:9999/wham/usage"
         (quota/usage-url {:subscription {:base-url "http://127.0.0.1:9999/codex/"}}))))

(defn ^:private subscription-cfg [url]
  (-> (get-in config/defaults [:providers :openai])
      (assoc-in [:subscription :base-url] (str url "/codex"))
      (assoc :state-dir (h/temp-dir))))

(deftest asking-the-subscription
  (h/with-fake [fake [(h/json-response usage-body)]]
    (let [cfg (subscription-cfg (:url fake))]
      (testing "not signed in: nothing to ask"
        (with-redefs [http/api-key (constantly "k")]
          (is (nil? (openai/quota cfg))))
        (is (nil? (openai/quota cfg)) "nor without credentials")
        (is (empty? @(:requests fake))))
      (credentials/save! (:state-dir cfg) "openai" {:type "chatgpt" :access-token "sub-token" :refresh-token "r"
                                                    :account-id "acct-1"
                                                    :expires-at (+ (quot (System/currentTimeMillis) 1000) 3600)})
      (testing "signed in: it asks, as Codex"
        (is (= ["plus" [23.4 41.0]] ((juxt :plan #(map :used-percent (:windows %))) (openai/quota cfg))))
        (let [[{:keys [uri headers]}] @(:requests fake)]
          (is (= "/wham/usage" uri))
          (is (= ["Bearer sub-token" "acct-1"] [(get headers "authorization") (get headers "chatgpt-account-id")]))))
      (testing "errors say so"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"usage endpoint returned 500"
                              (openai/quota cfg)))))))
