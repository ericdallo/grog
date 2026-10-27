(ns grog.assist-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assist :as assist]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.connections :as connections]
            [grog.provider :as provider]
            [grog.test-helpers :as h]))

(defn ^:private context
  "A context with `ha` connected, by a long-lived token, unless it's nil;
  `settings` over the defaults."
  [ha & [settings]]
  (let [ctx (assistant/new-context (merge (assoc config/defaults :state-dir (h/temp-dir)) settings))]
    (when ha
      (connections/save! (config/state-dir @(:config ctx))
                         {:id "home-assistant" :integration "home-assistant" :name "Home"}
                         {:way "token" :url (str (:url ha) "/api/mcp/assist") :headers {"Authorization" "Bearer llat-1"}}))
    ctx))

(defn ^:private asked
  "What `ha`'s conversation agent was asked: the bodies, parsed, with the
  token they came with."
  [ha]
  (for [{:keys [uri body headers]} @(:requests ha)
        :when (= "/api/conversation/process" uri)]
    (assoc (json/parse-string body true) :token (get headers "authorization"))))

(deftest what-it-did
  (testing "what it said once it acted: done, either way"
    (is (= "Ligado" (assist/acted (h/ha-answer "action_done" " Ligado "))))
    (is (= "13:13" (assist/acted (h/ha-answer "action_done" "13:13")))))
  (testing "nothing otherwise"
    (is (nil? (assist/acted (h/ha-answer "error" "Desculpe, não consegui entender seu pedido" "no_intent_match"))))
    (is (nil? (assist/acted (h/ha-answer "query_answer" "Nenhuma"))) "a question: the model knows more")
    (is (nil? (assist/acted (h/ha-answer "action_done" " "))))
    (is (nil? (assist/acted nil)))))

(deftest its-address
  (is (= "http://ha.local:8123" (assist/base-url "http://ha.local:8123/api/mcp")))
  (is (= "http://ha.local:8123" (assist/base-url "http://ha.local:8123/api/mcp/assist")))
  (is (= "https://ha.example.com" (assist/base-url "https://ha.example.com/api/mcp/"))))

(deftest home-assistant-first
  (let [ha (h/start-ha! {:converse (fn [{:keys [text]}]
                                     (if (= "liga a luz da cozinha" text)
                                       (h/ha-answer "action_done" "Ligado")
                                       (h/ha-answer "error" "Não existe nenhum dispositivo" "no_valid_targets")))})]
    (try
      (testing "what it did, asked of its own agent with its token"
        (let [ctx (context ha)
              logged (with-out-str
                       (is (= "Ligado" (assist/act! ctx "liga a luz da cozinha")))
                       (is (nil? (assist/act! ctx "qual a capital da França?"))))]
          (is (= [{:text "liga a luz da cozinha" :agent_id "conversation.home_assistant" :token "Bearer llat-1"}
                  {:text "qual a capital da França?" :agent_id "conversation.home_assistant" :token "Bearer llat-1"}]
                 (asked ha)))
          (is (re-find #"assist: action_done in \d+ ms" logged))
          (is (re-find #"assist: error no_valid_targets in \d+ ms" logged))
          (is (not (re-find #"cozinha|França|Ligado|dispositivo" logged)) "never what was said")))
      (testing "not when it's turned off, or nothing's connected"
        (reset! (:requests ha) [])
        (is (nil? (assist/act! (context ha {:assist {:enable false}}) "liga a luz da cozinha")))
        (is (nil? (assist/act! (context nil) "liga a luz da cozinha")))
        (is (empty? (asked ha))))
      (testing "nor once it's signed out"
        (let [ctx (context ha)]
          (connections/set-status! (config/state-dir @(:config ctx)) "home-assistant" "signed-out")
          (is (nil? (assist/act! ctx "liga a luz da cozinha")))
          (is (empty? (asked ha)))))
      (finally
        ((:stop! ha))))))

(deftest the-model-when-it-cant-be-asked
  (testing "too slow"
    (let [ha (h/start-ha! {:converse (fn [_] (assoc (h/ha-answer "action_done" "Ligado") :delay-ms 1000))})]
      (try
        (with-redefs [assist/timeout-ms 100]
          (let [started (System/currentTimeMillis)]
            (is (re-find #"assist: couldn't ask Home Assistant \(\w+\)"
                         (with-out-str (is (nil? (assist/act! (context ha) "liga a luz da cozinha"))))))
            (is (< (- (System/currentTimeMillis) started) 900))))
        (finally
          ((:stop! ha))))))
  (testing "gone"
    (let [ha (h/start-ha!)
          ctx (context ha)]
      ((:stop! ha))
      (with-out-str (is (nil? (assist/act! ctx "liga a luz da cozinha")))))))

(deftest a-turn-home-assistant-did
  (let [ha (h/start-ha! {:converse (fn [{:keys [text]}]
                                     (if (str/starts-with? text "liga")
                                       (h/ha-answer "action_done" "Ligado")
                                       (h/ha-answer "error" "Desculpe" "no_intent_match")))})
        ctx (context ha)
        said (atom [])
        asked-model (atom [])]
    (try
      (with-redefs [provider/stream-reply! (fn [_ {:keys [messages]} on-text _]
                                             (swap! asked-model conj (mapv :content (remove #(= "developer" (:role %)) messages)))
                                             (on-text "Desliguei.")
                                             {:text "Desliguei."})]
        (with-out-str
          (testing "its answer, without the model"
            (is (= "Ligado" (assistant/reply! ctx {:id "voice" :message "liga a luz da cozinha"} #(swap! said conj %) (fn [_]))))
            (is (= ["Ligado"] @said))
            (is (= [] @asked-model)))
          (testing "kept in the conversation, for the model to see next"
            (is (= "Desliguei." (assistant/reply! ctx {:id "voice" :message "e desliga a da sala"} (fn [_]) (fn [_]))))
            (is (= [["liga a luz da cozinha" "Ligado" "e desliga a da sala"]] @asked-model)))))
      (finally
        ((:stop! ha))))))
