(ns grog.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [grog.client :as client]))

(deftest what-status-says-was-consumed
  (testing "with the subscription, how much of its limits is used, and when they reset"
    (is (= "Usage:    5h 12% used (resets in 2h 10m), 7d 41% used (resets in 3d 4h)"
           (client/usage-line {:auth "subscription"
                               :quota {:windows [{:label "5h" :used-percent 12.4 :resets-at (+ 1000 7800)}
                                                 {:label "7d" :used-percent 40.6 :resets-at (+ 1000 273600)}]}}
                              1000)))
    (is (= "Usage:    5h 100% used" (client/usage-line {:auth "subscription"
                                                        :quota {:windows [{:label "5h" :used-percent 100
                                                                           :resets-at 900}]}}
                                                       1000))
        "a reset gone by")
    (is (= "Usage:    not known yet" (client/usage-line {:auth "subscription"} 1000))))
  (testing "with an API key, what it spent"
    (is (= "Spent:    $1.25 this month, $0.0312 today"
           (client/usage-line {:auth "api-key" :today {:usd 0.03124} :month {:usd 1.2499 :unpriced 0}} 1000)))
    (is (= "Spent:    $0.0000 this month, $0.0000 today (and 3 turns of models without a price)"
           (client/usage-line {:auth "api-key" :today {:usd 0} :month {:usd 0 :unpriced 3}} 1000))))
  (testing "nothing without credentials"
    (is (nil? (client/usage-line {:auth "none"} 1000)))))

(deftest durations
  (is (= ["<1m" "45m" "2h" "2h 10m" "3d" "3d 4h"]
         (map client/duration [30 2700 7200 7800 259200 273600]))))
