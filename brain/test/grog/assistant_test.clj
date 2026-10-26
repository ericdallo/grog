(ns grog.assistant-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.conversation :as conversation]
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

(deftest an-answer-cut-off-is-what-was-heard-of-it
  (let [q {:role "user" :content "como vai ser amanhã?"}
        a {:role "assistant" :content "Amanhã faz sol.\nLeve  um casaco."}]
    (testing "cut off after what was said aloud, whitespace aside, and marked so"
      (is (= [q {:role "assistant" :content "Amanhã faz sol. …"}] (assistant/cut [q a] "Amanhã faz  sol."))))
    (testing "dropped when none of it was said"
      (is (= [q] (assistant/cut [q a] ""))))
    (testing "as it is when all of it was"
      (is (= [q a] (assistant/cut [q a] "Amanhã faz sol. Leve um casaco."))))
    (testing "or when there's no answer starting with that"
      (is (= [q a] (assistant/cut [q a] "Amanhã chove.")))
      (is (= [q] (assistant/cut [q] "")))
      (is (= [] (assistant/cut [] ""))))
    (testing "what grog learns nothing from stays so"
      (is (= [q {:role "assistant" :content "Amanhã faz sol. …" :read-others? true}]
             (assistant/cut [q (assoc a :read-others? true)] "Amanhã faz sol."))))))

(deftest the-model-sees-what-was-heard-of-its-last-answer
  (let [ctx (assistant/new-context (assoc config/defaults :state-dir (h/temp-dir)))
        sent (atom [])]
    (with-redefs [provider/stream-reply! (fn [_ {:keys [messages]} on-text _]
                                           (swap! sent conj messages)
                                           (on-text "Amanhã faz sol. Leve um casaco.")
                                           {:text "Amanhã faz sol. Leve um casaco."})]
      (with-out-str
        (assistant/reply! ctx {:id "voice" :message "como vai ser amanhã?"} (fn [_]) (fn [_]))
        (assistant/reply! ctx {:id "voice" :message "e que horas são?" :cut-off "Amanhã faz sol."}
                          (fn [_]) (fn [_]))))
    (is (= [{:role "user" :content "como vai ser amanhã?"}
            {:role "assistant" :content "Amanhã faz sol. …"}
            {:role "user" :content "e que horas são?"}]
           (remove #(= "developer" (:role %)) (second @sent))))))

(deftest a-turn-cut-off-keeps-what-was-asked-and-answered-so-far
  (let [ctx (assistant/new-context (assoc config/defaults :state-dir (h/temp-dir)))
        reply! (fn [cancel answer]
                 (with-redefs [provider/stream-reply! (fn [_ request on-text on-tool]
                                                        (on-tool {:name "web_search"})
                                                        (some-> answer on-text)
                                                        ;; Whoever asked went away.
                                                        (deliver (:cancel request) true)
                                                        (throw (ex-info "The turn was cut off." {})))]
                   (with-out-str
                     (try
                       (assistant/reply! ctx {:id "voice" :message "como vai ser amanhã?" :cancel cancel}
                                         (fn [_]) (fn [_]))
                       (catch clojure.lang.ExceptionInfo _ nil))))
                 (get-in @(:store ctx) ["voice" :messages]))]
    (testing "marked, and so that grog learns nothing from what it searched (grog.learning)"
      (is (= [{:role "user" :content "como vai ser amanhã?"}
              {:role "assistant" :content "Amanhã faz sol, com …" :read-others? true}]
             (reply! (promise) "Amanhã faz sol, com"))))
    (testing "stopped rather than failed, for whoever watches"
      (is (= {:ok false :stopped true} (select-keys (:turn (events/snapshot (:events ctx))) [:ok :stopped :error]))))
    (testing "the question alone, when nothing was answered"
      (conversation/forget-all! (:store ctx))
      (is (= [{:role "user" :content "como vai ser amanhã?"}] (reply! (promise) nil))))))
