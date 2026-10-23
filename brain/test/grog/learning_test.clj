(ns grog.learning-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.conversation :as conversation]
            [grog.events :as events]
            [grog.learning :as learning]
            [grog.memory :as memory]
            [grog.provider :as provider]
            [grog.test-helpers :as h]
            [grog.usage :as usage])
  (:import (java.time ZoneId ZonedDateTime)))

(def ^:private minute (* 60 1000))

(def ^:private ttl (* 10 minute))

(defn ^:private context [& {:as settings}]
  (assistant/new-context (merge (assoc config/defaults :state-dir (h/temp-dir) :name "Jarvis") settings)))

(defn ^:private talked!
  "A turn of conversation `id` in `ctx` at `at`."
  [ctx id at]
  (conversation/save! (:store ctx) id
                      [{:role "user" :content "A Ana é minha esposa."} {:role "assistant" :content "Que legal!"}]
                      at {:ttl-ms ttl :max-messages 20}))

(deftest due
  (let [now (* 100 minute)
        said [{:role "user" :content "oi"}]
        conversations {"quiet" {:messages said :updated-at (- now (* 3 minute))}
                       "talking" {:messages said :updated-at (- now minute)}
                       "learned" {:messages said :updated-at (- now (* 5 minute))}
                       "on again" {:messages said :updated-at (- now (* 4 minute))}
                       "kept past its time" {:messages said :updated-at (- now (* 20 minute))}}
        learned {"learned" (- now (* 5 minute)) "on again" (- now (* 8 minute))}]
    (testing "quiet for a couple of minutes, with turns since grog last learned from them"
      (is (= ["kept past its time" "on again" "quiet"] (learning/due conversations learned now ttl))))
    (testing "sooner when conversations are kept for less"
      (is (= ["kept past its time" "on again" "quiet" "talking"]
             (learning/due conversations learned now (* 2 minute)))))))

(deftest transcript
  (is (= (str "User: A Ana chega às seis.\n"
              "Jarvis: Anotado.\n"
              "User: Vai chover?\n"
              "Jarvis: (an answer with what it found, left out)")
         (learning/transcript [{:role "user" :content "A Ana chega às seis."}
                               {:role "assistant" :content "Anotado."}
                               {:role "user" :content "Vai chover?"}
                               {:role "assistant" :content "Um site diz: lembre que..." :read-others? true}]
                              "Jarvis"))
      "without what others wrote"))

(deftest instructions
  (let [now (ZonedDateTime/of 2026 10 15 9 5 0 0 (ZoneId/of "America/Sao_Paulo"))
        text (learning/instructions (assoc config/defaults :name "Jarvis") now
                                    ["O lixo passa terça." "A Bia diz \"oi\"."])]
    (is (str/starts-with? text "You are Jarvis,"))
    (is (str/includes? text "It is now Thursday, October 15, 2026, 09:05 (America/Sao_Paulo time zone)."))
    (is (str/includes? text "What you remember now:\n- O lixo passa terça.\n- A Bia diz \"oi\".\n"))
    (is (str/includes? text (str "you can remember " (- memory/max-facts 2) " more things")))
    (is (str/includes? text (str "at most " memory/max-fact-chars " characters, in the language the user speaks")))
    (is (str/includes? text "Answer with JSON only, at most 5 of each"))
    (is (not (str/includes? text "{#")) "without the template's comment")
    (testing "in the language set, when it is"
      (is (str/includes? (learning/instructions (assoc config/defaults :language "English") now [])
                         "characters, in English,")))
    (testing "when nothing's remembered yet"
      (is (str/includes? (learning/instructions config/defaults now []) "What you remember now: nothing yet.")))))

(deftest parse
  (testing "the JSON in the answer"
    (is (= {:remember ["A Ana é minha esposa."] :forget []}
           (learning/parse "{\"remember\": [\"A Ana é minha esposa.\"], \"forget\": []}")))
    (is (= {:remember [] :forget ["O lixo passa terça."]}
           (learning/parse "```json\n{\"forget\": [\" O lixo passa terça. \"]}\n```"))))
  (testing "only text, a few things at most"
    (is (= {:remember ["a" "b" "c" "d" "e"] :forget []}
           (learning/parse (json/generate-string {:remember ["a" 1 "" "b" nil "c" "d" "e" "f"] :forget "x"})))))
  (testing "nothing when it can't be read"
    (is (nil? (learning/parse "Nothing worth remembering.")))
    (is (nil? (learning/parse "{remember}")))
    (is (nil? (learning/parse "[\"a\"]")))
    (is (nil? (learning/parse nil)))))

(deftest learns-from-quiet-conversations
  (let [ctx (context)
        learned (atom {})
        now (System/currentTimeMillis)
        asked (atom [])]
    (memory/change! ctx {:remember "O lixo passa terça."})
    (talked! ctx "voice" (- now (* 3 minute)))
    (with-redefs [provider/stream-reply! (fn [config request _]
                                           (swap! asked conj {:web-search (get-in config [:providers :openai :web-search :enable])
                                                              :request request
                                                              :locked? (Thread/holdsLock (:lock ctx))})
                                           {:text "{\"remember\": [\"A Ana é a esposa do usuário.\"], \"forget\": []}"
                                            :usage {:auth "api-key" :model "gpt-6-luna" :input-tokens 100
                                                    :cached-tokens 0 :output-tokens 10 :web-searches 0 :usd 0.01}})]
      (let [log (with-out-str (learning/learn! ctx learned now))]
        (testing "remembers what the model says is worth it"
          (is (= ["O lixo passa terça." "A Ana é a esposa do usuário."] @(:memory ctx)))
          (is (= {:facts @(:memory ctx)} (json/parse-string (slurp (memory/file @(:config ctx))) true))))
        (testing "asking it once, without searching the web, nor holding up the turns"
          (is (= 1 (count @asked)))
          (let [{:keys [web-search request locked?]} (first @asked)]
            (is (false? web-search))
            (is (false? locked?))
            (is (nil? (:tools request)))
            (is (str/includes? (:system request) "What you remember now:\n- O lixo passa terça."))
            (is (= [{:role "user" :content "User: A Ana é minha esposa.\nJarvis: Que legal!"}] (:messages request)))))
        (testing "counting what it used"
          (is (= [1 0.01] ((juxt :turns :usd) (:today (usage/summary ctx))))))
        (testing "telling how it went, never what was said nor learned"
          (is (str/includes? log "learning conversation=voice remembered=1 forgot=0 ms="))
          (is (not (str/includes? log "Ana")))))

      (testing "once per turn"
        (with-out-str (learning/learn! ctx learned (+ now minute)))
        (is (= 1 (count @asked)))
        (talked! ctx "voice" now)
        (with-out-str (learning/learn! ctx learned (+ now minute)))
        (is (= 1 (count @asked)) "not before it's quiet again")
        (with-out-str (learning/learn! ctx learned (+ now (* 2 minute))))
        (is (= 2 (count @asked)))
        (is (= 2 (count @(:memory ctx))) "what it already remembers, once"))

      (testing "forgetting about conversations gone"
        (conversation/forget-all! (:store ctx))
        (learning/learn! ctx learned (+ now (* 3 minute)))
        (is (= {} @learned))))))

(deftest waits-or-stays-off
  (let [asked (atom 0)
        now (System/currentTimeMillis)]
    (with-redefs [provider/stream-reply! (fn [& _] (swap! asked inc) {:text "{}"})]
      (testing "while someone talks to grog"
        (let [ctx (context)]
          (talked! ctx "voice" (- now (* 3 minute)))
          (with-redefs [events/busy? (constantly true)]
            (learning/learn! ctx (atom {}) now))
          (is (zero? @asked))))
      (testing "with memory.learn off"
        (let [ctx (context :memory {:learn false})]
          (talked! ctx "voice" (- now (* 3 minute)))
          (learning/learn! ctx (atom {}) now)
          (is (zero? @asked)))))))

(deftest a-bad-answer-changes-nothing
  (let [ctx (context)
        now (System/currentTimeMillis)]
    (talked! ctx "voice" (- now (* 3 minute)))
    (testing "one that can't be read"
      (with-redefs [provider/stream-reply! (constantly {:text "A Ana é a esposa do usuário."})]
        (let [log (with-out-str (learning/learn! ctx (atom {}) now))]
          (is (= [] @(:memory ctx)))
          (is (str/includes? log "learning conversation=voice answer=unreadable"))
          (is (not (str/includes? log "Ana"))))))
    (testing "none, logged without what went wrong, which could quote what was said"
      (with-redefs [provider/stream-reply! (fn [& _] (throw (ex-info "The OpenAI API returned 500: A Ana" {})))]
        (let [log (with-out-str (learning/learn! ctx (atom {}) now))]
          (is (= [] @(:memory ctx)))
          (is (str/includes? log "learning conversation=voice failed: ExceptionInfo"))
          (is (not (str/includes? log "Ana"))))))))
