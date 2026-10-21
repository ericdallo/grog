(ns grog.usage-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.events :as events]
            [grog.provider :as provider]
            [grog.test-helpers :as h]
            [grog.usage :as usage])
  (:import (java.time LocalDate)))

(defn ^:private ctx []
  (assistant/new-context (assoc config/defaults :state-dir (h/temp-dir) :timezone "America/Sao_Paulo")))

(def ^:private priced
  {:auth "api-key" :model "gpt-6-luna" :input-tokens 1000 :cached-tokens 200 :output-tokens 100
   :web-searches 1 :usd 0.5})

(deftest a-day-adds-up
  (is (= {:turns 2 :input-tokens 1003 :cached-tokens 200 :output-tokens 102 :web-searches 1 :usd 0.5 :unpriced 1}
         (-> nil
             (usage/add priced)
             (usage/add {:auth "api-key" :model "gpt-9" :input-tokens 3 :cached-tokens 0 :output-tokens 2
                         :web-searches 0 :usd nil}))))
  (testing "the subscription's turns are counted, without dollars"
    (is (= {:turns 1 :usd 0 :unpriced 0}
           (select-keys (usage/add nil {:auth "subscription" :input-tokens 3 :cached-tokens 0 :output-tokens 2
                                        :web-searches 0})
                        [:turns :usd :unpriced])))))

(defn ^:private on [date f]
  (with-redefs [usage/today (constantly (LocalDate/parse date))]
    (f)))

(deftest kept-for-good
  (let [c (ctx)
        cfg @(:config c)]
    (on "2026-10-15" #(usage/record! c priced))
    (on "2026-10-16" #(do (usage/record! c priced)
                          (usage/record! c priced)))
    (testing "per day, in the state directory, only for grog's user"
      (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (usage/file cfg)))))
      (is (= [{:date "2026-10-15" :turns 1} {:date "2026-10-16" :turns 2}]
             (map #(select-keys % [:date :turns]) (:days (json/parse-string (slurp (usage/file cfg)) true)))))
      (is (= 2000 (get-in (json/parse-string (slurp (usage/file cfg))) ["days" 1 "inputTokens"]))
          "as settings are written")
      (is (= @(:usage c) (usage/load-days cfg))))
    (testing "for a bit more than a year"
      (on "2027-11-30" #(usage/record! c priced))
      (is (= ["2027-11-30"] (keys (usage/load-days cfg)))))))

(deftest unreadable-totals-start-over
  (let [c (ctx)
        cfg @(:config c)]
    (spit (usage/file cfg) "{not json")
    (is (= {} (usage/load-days cfg)))
    (spit (usage/file cfg) (json/generate-string {:days [{:date "2026-10-16" :turns "many" :usd 0.25}]}))
    (is (= {:turns 0 :usd 0.25} (select-keys (get (usage/load-days cfg) "2026-10-16") [:turns :usd])))))

(deftest what-status-and-the-skin-show
  (let [c (ctx)]
    (on "2026-09-30" #(usage/record! c priced))
    (on "2026-10-01" #(usage/record! c priced))
    (on "2026-10-16" #(usage/record! c priced))
    (with-redefs [provider/describe (constantly {:auth "api-key"})]
      (let [{:keys [auth today month days]} (on "2026-10-16" #(usage/summary c))]
        (is (= "api-key" auth))
        (is (= [1 0.5] ((juxt :turns :usd) today)))
        (is (= [2 1.0] ((juxt :turns :usd) month)) "since the 1st")
        (is (= 30 (count days)))
        (is (= {:date "2026-10-16" :turns 1 :usd 0.5} (last days)))
        (is (= {:date "2026-09-17" :turns 0 :usd 0} (first days)))))
    (testing "the subscription's limits, once known"
      (usage/update-quota! c {:updated-at 1 :plan "plus"
                              :windows [{:id "secondary" :label "7d" :used-percent 40.0}
                                        {:id "primary" :label "5h" :used-percent 10.0 :window-minutes 300}]})
      (usage/update-quota! c {:updated-at 2 :windows [{:id "primary" :label "5h" :used-percent 12.0
                                                       :window-minutes 300}]})
      (with-redefs [provider/describe (constantly {:auth "subscription"})]
        (is (= {:updated-at 2 :plan "plus"
                :windows [{:id "primary" :label "5h" :used-percent 12.0 :window-minutes 300}
                          {:id "secondary" :label "7d" :used-percent 40.0}]}
               (:quota (usage/summary c)))))
      (with-redefs [provider/describe (constantly {:auth "api-key"})]
        (is (nil? (:quota (usage/summary c))) "not with an API key")))))

(deftest asks-the-subscription-now-and-then
  (let [c (ctx)
        asked (atom 0)]
    (with-redefs [provider/quota (fn [_] (swap! asked inc) {:updated-at 100 :windows []})]
      (is (true? (usage/refresh-quota! c 300 100)))
      (is (nil? (usage/refresh-quota! c 300 399)) "it knows, from not long ago")
      (is (true? (usage/refresh-quota! c 300 400)))
      (is (= 2 @asked)))
    (testing "failures are said once, and change nothing"
      (with-redefs [provider/quota (fn [_] (throw (ex-info "offline" {})))]
        (is (= "couldn't ask how much of the subscription is used: offline\n"
               (with-out-str (usage/refresh-quota! c 0 1000)
                             (usage/refresh-quota! c 0 1000))))
        (is (= 100 (:updated-at @(:quota c))))
        (testing "by each server"
          (is (seq (with-out-str (usage/refresh-quota! (ctx) 0 1000)))))))
    (testing "forgotten on signing out"
      (usage/forget-quota! c)
      (is (nil? @(:quota c))))))

(defn ^:private published-today
  "The day whoever watches `c` was last told was today."
  [c]
  (:date (peek (get-in (events/snapshot (:events c)) [:usage :days]))))

(deftest whoever-watches-learns-of-a-new-day
  (let [c (ctx)]
    (with-redefs [provider/quota (constantly nil)]
      (on "2026-10-16" #(usage/publish! c))
      (on "2026-10-16" #(usage/refresh! c 300 1000))
      (is (= "2026-10-16" (published-today c)))
      (testing "after midnight, without a turn"
        (on "2026-10-17" #(usage/refresh! c 300 1000))
        (is (= "2026-10-17" (published-today c)))))))
