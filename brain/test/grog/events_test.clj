(ns grog.events-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.events :as events]))

(defn ^:private run
  "The state after `events`, each a [ms event]."
  [& events]
  (reduce (fn [state [at event]] (events/step state (assoc event :at at)))
          events/initial-state
          events))

(defn ^:private steps [state]
  (mapv (juxt :step :at) (get-in state [:turn :steps])))

(deftest a-voice-turn
  (let [state (run [1000 {:type :voice :state "listening" :conversation "voice"}]
                   [2000 {:type :voice :state "hearing" :conversation "voice"}]
                   [3500 {:type :voice :state "answering" :conversation "voice"}]
                   [3510 {:type :turn :conversation "voice" :n 1 :question "will it rain?"}]
                   [4000 {:type :text :conversation "voice" :n 1 :text "Let me check."}]
                   [4100 {:type :tool :conversation "voice" :n 1 :name "web_search"}]
                   [4200 {:type :voice :state "speaking" :conversation "voice"}]
                   [6000 {:type :text :conversation "voice" :n 1 :text " Sunny."}]
                   [6100 {:type :text :conversation "voice" :n 1 :text " Warm."}]
                   [6200 {:type :done :conversation "voice" :n 1 :ms 2690 :tools ["web_search"]}]
                   [7000 {:type :voice :state "listening" :conversation "voice"}])]
    (testing "its steps, from what grog-voice heard to it speaking (what follows, said as it comes, too)"
      (is (= [["heard" 2000] ["thinking" 3510] ["answering" 4000] ["tool" 4100] ["speaking" 4200]]
             (steps state)))
      (is (= "web_search" (get-in state [:turn :steps 3 :name]))))
    (testing "what was said, and how it went"
      (is (= {:question "will it rain?" :answer "Let me check. Sunny. Warm." :done-at 6200 :ok true}
             (select-keys (:turn state) [:question :answer :done-at :ok :ms]))))
    (testing "the recent turns"
      (is (= [{:at 6200 :conversation "voice" :ms 2690 :tools ["web_search"] :ok true}] (:recent state))))
    (testing "grog-voice: since when it's in its state, and when it last said"
      (is (= {:state "listening" :since 7000 :seen 7000 :conversation "voice"} (:voice state))))))

(deftest heard-only-for-its-own-conversation-and-just-before
  (let [heard [[2000 {:type :voice :state "hearing" :conversation "voice"}]]]
    (testing "a turn of another conversation (the CLI) wasn't heard"
      (is (= [["thinking" 3000]]
             (steps (apply run (conj heard [3000 {:type :turn :conversation "default" :n 1}]))))))
    (testing "nor one long after"
      (is (= [["thinking" 60000]]
             (steps (apply run (conj heard [60000 {:type :turn :conversation "voice" :n 1}]))))))
    (testing "nor a second turn, after the first one used it"
      (is (= [["thinking" 9000]]
             (steps (apply run (concat heard [[3000 {:type :turn :conversation "voice" :n 1}]
                                              [4000 {:type :done :conversation "voice" :n 1 :ms 1000}]
                                              [9000 {:type :turn :conversation "voice" :n 2}]]))))))))

(deftest answering-again-after-a-tool
  (let [state (run [1000 {:type :turn :conversation "c" :n 1}]
                   [1100 {:type :text :conversation "c" :n 1 :text "Let me check."}]
                   [1200 {:type :text :conversation "c" :n 1 :text " Hm."}]
                   [1300 {:type :tool :conversation "c" :n 1 :name "web_search"}]
                   [5000 {:type :text :conversation "c" :n 1 :text "Sunny."}])]
    (is (= ["thinking" "answering" "tool" "answering"] (map first (steps state))))))

(deftest what-grog-voice-heard-for-grog
  (let [state (run [1000 {:type :voice :state "hearing" :conversation "voice"}]
                   [1800 {:type :voice :state "hearing" :conversation "voice"
                          :heard {:text "Grog, que" :final false}}]
                   [2600 {:type :voice :state "hearing" :conversation "voice"
                          :heard {:text "Grog, que horas são?" :final true}}])]
    (testing "the latest, as it comes"
      (is (= {:text "Grog, que horas são?" :final true :at 2600} (:heard state))))
    (testing "grog-voice is in the same state"
      (is (= {:state "hearing" :since 1000 :seen 2600 :conversation "voice"} (:voice state))))
    (testing "it stays when grog-voice says something else"
      (is (= "Grog, que horas são?"
             (get-in (events/step state {:type :voice :state "answering" :conversation "voice" :at 2700})
                     [:heard :text]))))))

(deftest grog-voice-staying-in-its-state
  (let [state (run [1000 {:type :voice :state "listening" :conversation "voice"}]
                   [31000 {:type :voice :state "listening" :conversation "voice"}])]
    (is (= {:state "listening" :since 1000 :seen 31000 :conversation "voice"} (:voice state)))))

(deftest when-grog-voice-last-failed
  (let [state (run [1000 {:type :voice :state "listening" :conversation "voice"}]
                   [2000 {:type :voice :state "error" :conversation "voice"}]
                   [3000 {:type :voice :state "error" :conversation "voice"}]
                   [4000 {:type :voice :state "loading" :conversation "voice"}]
                   [5000 {:type :voice :state "listening" :conversation "voice"}])]
    (is (= {:state "listening" :since 5000 :seen 5000 :conversation "voice" :error-at 2000} (:voice state))
        "kept after it's back")))

(deftest events-of-other-turns-dont-touch-the-current-one
  (let [state (run [1000 {:type :turn :conversation "a" :n 1}]
                   [1100 {:type :turn :conversation "b" :n 1}]
                   [1200 {:type :tool :conversation "a" :n 1 :name "volume"}]
                   [1300 {:type :done :conversation "a" :n 1 :ms 300 :tools ["volume"]}])]
    (is (= {:conversation "b" :n 1 :steps [{:step "thinking" :at 1100}]} (:turn state)))
    (is (= [{:at 1300 :conversation "a" :ms 300 :tools ["volume"] :ok true}] (:recent state)))))

(deftest failed-turns
  (let [state (run [1000 {:type :turn :conversation "c" :n 1}]
                   [1500 {:type :failed :conversation "c" :n 1 :ms 500 :tools [] :error "No credentials."}])]
    (is (= {:done-at 1500 :ok false :error "No credentials."}
           (select-keys (:turn state) [:done-at :ok :ms :error])))
    (is (false? (:ok (first (:recent state)))))))

(deftest only-the-last-turns
  (let [state (apply run (for [n (range 1 12)]
                           [(* n 1000) {:type :done :conversation "c" :n n :ms n}]))]
    (is (= [11 10 9 8] (map :ms (:recent state))))))

(defn ^:private watcher
  "A watcher keeping what it gets, parsed: [send! messages-atom]."
  []
  (let [got (atom [])]
    [(fn [text]
       (swap! got conj (if (str/starts-with? text ":")
                         :ping
                         (let [[_ type data] (re-matches #"(?s)event: (\S+)\ndata: (.*)\n\n" text)]
                           [type (json/parse-string data true)])))
       true)
     got]))

(deftest watching
  (with-redefs [events/now-ms (constantly 5000)]
    (let [hub (events/new-hub (constantly false))
          [send! got] (watcher)]
      (events/publish! hub {:type :status :name "grog" :provider "openai" :model "m" :auth "api-key"
                            :timezone "America/Sao_Paulo"})
      (events/publish! hub {:type :voice :state "hearing" :conversation "voice"})
      (events/watch! hub :w send!)
      (testing "the state first, with whether there are captions and the time"
        (let [[[type {:keys [state]}]] @got]
          (is (= "snapshot" type))
          (is (= {:name "grog" :auth "api-key" :timezone "America/Sao_Paulo" :captions false :now 5000 :recent []}
                 (select-keys state [:name :auth :timezone :captions :now :recent])))
          (testing "without what's kept for the turns to come"
            (is (= {:state "hearing" :since 5000 :seen 5000 :conversation "voice"} (:voice state)))
            (is (not (contains? state :heardAt)))))))))

(deftest without-captions-nothing-said-goes-out
  (with-redefs [events/now-ms (constantly 5000)]
    (let [hub (events/new-hub (constantly false))
          [send! got] (watcher)]
      (events/watch! hub :w send!)
      (events/publish! hub {:type :voice :state "hearing" :conversation "c"
                            :heard {:text "secret heard" :final true}})
      (events/publish! hub {:type :turn :conversation "c" :n 1 :question "secret question" :said "Grog, secret"})
      (events/publish! hub {:type :text :conversation "c" :n 1 :text "secret answer"})
      (events/publish! hub {:type :text :conversation "c" :n 1 :text "more secrets"})
      (events/publish! hub {:type :done :conversation "c" :n 1 :ms 10 :tools ["shell"]})
      (events/publish! hub {:type :turn :conversation "c" :n 2 :question "secret again"})
      (events/publish! hub {:type :failed :conversation "c" :n 2 :ms 10 :error "quoting a secret"})
      (let [sent (pr-str @got)]
        (is (not (str/includes? sent "secret")) sent))
      (testing "the steps go on, and an answer that adds nothing isn't sent again"
        (is (= ["snapshot" "voice" "turn" "text" "done" "turn" "failed"] (map first @got)))
        (is (= ["heard" "thinking" "answering"] (map :step (get-in (second (nth @got 4)) [:state :turn :steps])))))
      (testing "nor is it kept"
        (is (not (str/includes? (pr-str (events/snapshot hub)) "secret")))))))

(deftest with-captions-it-shows-what-was-said
  (with-redefs [events/now-ms (constantly 5000)]
    (let [hub (events/new-hub (constantly true))
          [send! got] (watcher)]
      (events/watch! hub :w send!)
      (events/publish! hub {:type :voice :state "hearing" :conversation "c"
                            :heard {:text "Grog, will it rain?" :final true}})
      (events/publish! hub {:type :turn :conversation "c" :n 1 :question "will it rain?" :said "Grog, will it rain?"})
      (events/publish! hub {:type :text :conversation "c" :n 1 :text "No."})
      (let [[type {:keys [event state]}] (last @got)]
        (is (= "text" type))
        (is (= {:conversation "c" :n 1 :text "No." :at 5000} event))
        (is (= {:question "will it rain?" :said "Grog, will it rain?" :answer "No."}
               (select-keys (:turn state) [:question :said :answer])))
        (is (= {:text "Grog, will it rain?" :final true :at 5000} (:heard state)))
        (is (true? (:captions state)))))))

(deftest watchers-gone-are-forgotten
  (let [hub (events/new-hub (constantly false))
        calls (atom 0)]
    (events/watch! hub :gone (fn [_] (swap! calls inc) (< @calls 2)))
    (events/publish! hub {:type :voice :state "listening"})
    (events/publish! hub {:type :voice :state "hearing"})
    (is (= 2 @calls) "the snapshot, then the event it failed to get")
    (is (empty? @(:watchers hub)))
    (testing "and unwatching stops them"
      (let [[send! got] (watcher)]
        (events/watch! hub :w send!)
        (events/ping! hub)
        (events/unwatch! hub :w)
        (events/publish! hub {:type :voice :state "listening"})
        (is (= ["snapshot" :ping] (map #(if (vector? %) (first %) %) @got)))))))

