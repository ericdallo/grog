(ns grog.assistant-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.events :as events]
            [grog.provider :as provider]
            [grog.test-helpers :as h]))

(defn ^:private turn-events
  "The :turn events whoever watches `ctx` gets while it answers a message in
  each of `conversations`."
  [ctx conversations]
  (let [got (atom [])]
    (events/watch! (:events ctx) :w (fn [text]
                                      (when-let [[_ data] (re-matches #"(?s)event: turn\ndata: (.*)\n\n" text)]
                                        (swap! got conj (:event (json/parse-string data true))))
                                      true))
    (with-redefs [provider/stream-reply! (fn [_ _ on-text _]
                                           (on-text "Hi.")
                                           {:text "Hi."})]
      (with-out-str
        (doseq [id conversations]
          (assistant/reply! ctx {:id id :message "hi"} (fn [_]) (fn [_])))))
    @got))

(deftest turn-numbers
  (let [ctx (assistant/new-context (assoc config/defaults :state-dir (h/temp-dir)))]
    (testing "one count for all conversations, telling every turn apart"
      (is (= [["a" 1] ["b" 2] ["a" 3] ["c" 4]]
             (map (juxt :conversation :n) (turn-events ctx ["a" "b" "a" "c"])))))
    (testing "whose number doesn't grow with them"
      (is (= 4 @(:turns ctx))))))
