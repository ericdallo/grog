(ns grog.assistant-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.events :as events]
            [grog.mcp-tools :as mcp-tools]
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

(deftest marks-answers-that-read-what-others-wrote
  (let [ctx (assistant/new-context (assoc config/defaults :state-dir (h/temp-dir)))
        answer (fn [id usage tool]
                 (with-redefs [mcp-tools/tools (constantly [{:name "ha__read_mail" :description "Reads mail."
                                                             :parameters {:type "object"} :open-world true}
                                                            {:name "ha__lights" :description "Lights."
                                                             :parameters {:type "object"} :open-world false}])
                               provider/stream-reply! (fn [_ _ on-text on-tool]
                                                        (when tool
                                                          (on-tool {:name tool}))
                                                        (on-text "Done.")
                                                        {:text "Done." :usage usage})]
                   (with-out-str
                     (assistant/reply! ctx {:id id :message "hi"} (fn [_]) (fn [_]))))
                 (peek (get-in @(:store ctx) [id :messages])))]
    (testing "so that grog learns nothing from them (grog.learning)"
      (is (= {:role "assistant" :content "Done." :read-others? true} (answer "a" {:web-searches 1} nil))
          "it searched the web")
      (is (= {:role "assistant" :content "Done." :read-others? true} (answer "b" nil "ha__read_mail"))
          "it used a tool whose answers may carry someone else's text"))
    (testing "only those"
      (is (= {:role "assistant" :content "Done."} (answer "c" {:web-searches 0} "ha__lights"))))))
