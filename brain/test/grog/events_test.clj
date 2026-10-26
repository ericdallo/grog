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
    (testing "its answer, in order: text, the tool it used until it answered again, and more text"
      (is (= [{:text "Let me check."} {:tool "web_search" :at 4100 :done-at 6000} {:text " Sunny. Warm."}]
             (get-in state [:turn :parts]))))
    (testing "what was said, how it went, and when grog-voice was done saying it"
      (is (= {:question "will it rain?" :done-at 6200 :ok true :spoken-at 7000}
             (select-keys (:turn state) [:question :done-at :ok :ms :spoken-at]))))
    (testing "no history before it"
      (is (= [] (:history state))))
    (testing "grog-voice: since when it's in its state, and when it last said"
      (is (= {:state "listening" :since 7000 :seen 7000 :conversation "voice"} (:voice state))))))

(deftest a-tool-ends-with-the-next-one-or-the-turn
  (let [state (run [1000 {:type :turn :conversation "c" :n 1}]
                   [1100 {:type :tool :conversation "c" :n 1 :name "computer"}]
                   [1500 {:type :tool :conversation "c" :n 1 :name "volume"}]
                   [1900 {:type :failed :conversation "c" :n 1 :error "Oops."}])]
    (is (= [{:tool "computer" :at 1100 :done-at 1500} {:tool "volume" :at 1500 :done-at 1900}]
           (get-in state [:turn :parts])))))

(deftest what-a-tool-is-used-with
  (let [state (run [1000 {:type :turn :conversation "c" :n 1}]
                   [1100 {:type :tool :conversation "c" :n 1 :name "shell"}]
                   [1200 {:type :tool-detail :conversation "c" :n 1 :name "shell" :detail "uptime"}]
                   [1300 {:type :tool :conversation "c" :n 1 :name "shell"}]
                   [1400 {:type :tool-detail :conversation "c" :n 1 :name "shell" :detail "df -h"}]
                   [1500 {:type :tool-detail :conversation "c" :n 1 :name "volume" :detail "40%"}])]
    (is (= [["shell" "uptime"] ["shell" "df -h"]] (map (juxt :tool :detail) (get-in state [:turn :parts])))
        "each its own, none for a tool it didn't use"))
  (testing "never without captions"
    (is (= {:type :tool-detail :name "shell"} (events/redact {:type :tool-detail :name "shell" :detail "uptime"})))))

(deftest spoken-only-once-grog-voice-said-it
  (testing "not a typed turn"
    (let [state (run [1000 {:type :voice :state "listening" :conversation "voice"}]
                     [2000 {:type :turn :conversation "default" :n 1}]
                     [3000 {:type :done :conversation "default" :n 1 :ms 1000}]
                     [4000 {:type :voice :state "hearing" :conversation "voice"}]
                     [5000 {:type :voice :state "listening" :conversation "voice"}])]
      (is (not (contains? (:turn state) :spoken-at)))))
  (testing "nor one that started while grog-voice was still saying the last"
    (let [state (run [1000 {:type :turn :conversation "voice" :n 1}]
                     [1500 {:type :voice :state "speaking" :conversation "voice"}]
                     [2000 {:type :done :conversation "voice" :n 1 :ms 1000}]
                     [2500 {:type :turn :conversation "default" :n 2}]
                     [3000 {:type :voice :state "listening" :conversation "voice"}])]
      (is (not (contains? (:turn state) :spoken-at))))))

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
    (is (= ["thinking" "answering" "tool" "answering"] (map first (steps state))))
    (is (= [{:text "Let me check. Hm."} {:tool "web_search" :at 1300 :done-at 5000} {:text "Sunny."}]
           (get-in state [:turn :parts])))))

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

(deftest the-last-turns-go-in-the-history
  (let [state (run [1000 {:type :turn :conversation "a" :n 1 :question "first?"}]
                   [1100 {:type :text :conversation "a" :n 1 :text "One."}]
                   [1200 {:type :done :conversation "a" :n 1}]
                   [2000 {:type :turn :conversation "a" :n 2 :question "second?"}])]
    (is (= [{:conversation "a" :n 1 :question "first?" :parts [{:text "One."}] :done-at 1200 :ok true}]
           (map #(dissoc % :steps) (:history state))))
    (is (= 2 (get-in state [:turn :n]))))
  (testing "up to 20"
    (let [state (apply run (for [n (range 1 26)]
                             [(* n 1000) {:type :turn :conversation "c" :n n}]))]
      (is (= (range 5 25) (map :n (:history state))))
      (is (= 25 (get-in state [:turn :n]))))))

(deftest events-of-turns-gone-to-the-history-go-there
  (let [state (run [1000 {:type :turn :conversation "a" :n 1}]
                   [1100 {:type :turn :conversation "b" :n 1}]
                   [1200 {:type :tool :conversation "a" :n 1 :name "volume"}]
                   [1300 {:type :done :conversation "a" :n 1 :ms 300 :tools ["volume"]}]
                   [1400 {:type :text :conversation "z" :n 9 :text "Lost."}])]
    (is (= {:conversation "b" :n 1 :steps [{:step "thinking" :at 1100}] :parts []} (:turn state)))
    (is (= [{:tool "volume" :at 1200 :done-at 1300}] (get-in state [:history 0 :parts])))
    (is (= {:done-at 1300 :ok true} (select-keys (get-in state [:history 0]) [:done-at :ok])))
    (is (= 1 (count (:history state))) "nor anything of a turn it never saw")))

(deftest failed-turns
  (let [state (run [1000 {:type :turn :conversation "c" :n 1}]
                   [1500 {:type :failed :conversation "c" :n 1 :ms 500 :tools [] :error "No credentials."
                          :reason "login"}])]
    (is (= {:done-at 1500 :ok false :error "No credentials." :reason "login"}
           (select-keys (:turn state) [:done-at :ok :ms :error :reason])))))

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
                            :timezone "America/Sao_Paulo" :screen-off-minutes 10})
      (events/publish! hub {:type :voice :state "hearing" :conversation "voice"})
      (events/watch! hub :w send!)
      (testing "the state first, with whether there are captions, the time and when grog started"
        (let [[[type {:keys [state]}]] @got]
          (is (= "snapshot" type))
          (is (= {:name "grog" :auth "api-key" :timezone "America/Sao_Paulo" :screenOffMinutes 10 :captions false
                  :now 5000 :history [] :startedAt 5000}
                 (select-keys state [:name :auth :timezone :screenOffMinutes :captions :now :history :startedAt])))
          (testing "without what's kept for the turns to come"
            (is (= {:state "hearing" :since 5000 :seen 5000 :conversation "voice"} (:voice state)))
            (is (not (contains? state :heardAt)))))))))

(deftest the-history-goes-out-when-it-changes
  (with-redefs [events/now-ms (constantly 5000)]
    (let [hub (events/new-hub (constantly true))
          [send! got] (watcher)]
      (events/watch! hub :w send!)
      (events/publish! hub {:type :turn :conversation "c" :n 1 :question "one?"})
      (events/publish! hub {:type :text :conversation "c" :n 1 :text "One."})
      (events/publish! hub {:type :done :conversation "c" :n 1})
      (events/publish! hub {:type :turn :conversation "c" :n 2 :question "two?"})
      (events/publish! hub {:type :text :conversation "c" :n 2 :text "Two."})
      (events/publish! hub {:type :forgot})
      (is (= [["snapshot" true] ["turn" false] ["text" false] ["done" false] ["turn" true] ["text" false]
              ["forgot" true]]
             (map (fn [[type {:keys [state]}]] [type (contains? state :history)]) @got)))
      (is (= ["one?"] (map :question (get-in (nth @got 4) [1 :state :history]))))
      (is (= [] (get-in (last @got) [1 :state :history])) "forgotten"))))

(deftest without-captions-nothing-said-goes-out
  (with-redefs [events/now-ms (constantly 5000)]
    (let [hub (events/new-hub (constantly false))
          [send! got] (watcher)]
      (events/watch! hub :w send!)
      (events/publish! hub {:type :voice :state "hearing" :conversation "c"
                            :heard {:text "secret heard" :final true}})
      (events/publish! hub {:type :turn :conversation "c" :n 1 :question "secret question" :said "Grog, secret"})
      (events/publish! hub {:type :text :conversation "c" :n 1 :text "secret answer"})
      (events/publish! hub {:type :tool :conversation "c" :n 1 :name "shell"})
      (events/publish! hub {:type :text :conversation "c" :n 1 :text "more secrets"})
      (events/publish! hub {:type :text :conversation "c" :n 1 :text "still more secrets"})
      (events/publish! hub {:type :done :conversation "c" :n 1 :ms 10 :tools ["shell"]})
      (events/publish! hub {:type :turn :conversation "c" :n 2 :question "secret again"})
      (events/publish! hub {:type :failed :conversation "c" :n 2 :ms 10 :error "quoting a secret" :reason "offline"})
      (let [sent (pr-str @got)]
        (is (not (str/includes? sent "secret")) sent))
      (testing "why a turn failed, in a word, goes on"
        (is (= "offline" (get-in (second (last @got)) [:state :turn :reason]))))
      (testing "the steps and the tools go on, and an answer that adds nothing isn't sent again"
        (is (= ["snapshot" "voice" "turn" "text" "tool" "text" "done" "turn" "failed"] (map first @got)))
        (is (= ["heard" "thinking" "answering" "tool" "answering"]
               (map :step (get-in (second (nth @got 6)) [:state :turn :steps]))))
        (is (= [{:tool "shell" :at 5000 :doneAt 5000}] (get-in (second (nth @got 6)) [:state :turn :parts]))))
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
        (is (= {:question "will it rain?" :said "Grog, will it rain?" :parts [{:text "No."}]}
               (select-keys (:turn state) [:question :said :parts])))
        (is (= {:text "Grog, will it rain?" :final true :at 5000} (:heard state)))
        (is (true? (:captions state)))))))

(deftest a-turn-asked-to-stop-is-stopped-not-failed
  (let [state (run [1000 {:type :turn :conversation "voice" :n 1}]
                   [1500 {:type :stop}]
                   [1600 {:type :failed :conversation "voice" :n 1 :ms 600 :error "The client went away."}])]
    (is (= {:done-at 1600 :ok false :stopped true} (select-keys (:turn state) [:done-at :ok :stopped :error :stopping]))))
  (testing "stopping nothing changes nothing, and what went well went well"
    (let [state (run [1000 {:type :turn :conversation "voice" :n 1}]
                     [1500 {:type :done :conversation "voice" :n 1 :ms 500}]
                     [1600 {:type :stop}])]
      (is (= {:ok true} (select-keys (:turn state) [:ok :stopped :stopping]))))))

(deftest a-turn-cut-off-by-whoever-asked-is-stopped-not-failed
  ;; Someone called grog while it answered: grog-voice hung up on it.
  (let [state (run [1000 {:type :turn :conversation "voice" :n 1}]
                   [1400 {:type :text :conversation "voice" :n 1 :text "Amanhã"}]
                   [1500 {:type :failed :conversation "voice" :n 1 :ms 500 :stopped true
                          :error "The client went away."}])]
    (is (= {:done-at 1500 :ok false :stopped true} (select-keys (:turn state) [:done-at :ok :stopped :error])))))

(deftest the-mic-the-volume-and-forgetting
  (let [state (run [1000 {:type :status :name "grog" :mic "on"}]
                   [1100 {:type :turn :conversation "voice" :n 1}]
                   [1150 {:type :turn :conversation "voice" :n 2}]
                   [1200 {:type :mic :mic "off"}]
                   [1300 {:type :volume :level 40 :muted false}])]
    (is (= ["off" {:level 40 :muted false}] ((juxt :mic :volume) state)))
    (testing "forgetting the conversations, the last turns go too"
      (is (= [nil []] ((juxt :turn :history) (events/step state {:type :forgot :at 1400})))))))

(deftest what-can-be-asked-and-going-away
  (let [state (run [1000 {:type :status :name "grog" :actions ["restart" "reboot"]}]
                   [2000 {:type :restarting :action "reboot"}])]
    (is (= ["restart" "reboot"] (:actions state)))
    (is (= {:action "reboot" :at 2000} (:restarting state)))
    (testing "the machine refused: it stays"
      (is (nil? (:restarting (events/step state {:type :restarting :at 3000})))))))

(deftest its-revision-and-updates
  (let [state (run [1000 {:type :status :name "grog" :revision "fbd2aad"}]
                   [2000 {:type :update :update {:stage "building" :to "1234abc"}}])]
    (is (= "fbd2aad" (:revision state)))
    (is (= {:stage "building" :to "1234abc"} (:update state)))))

(deftest whether-someone-talks-to-grog
  (let [voice (fn [state seen] {:voice {:state state :seen seen}})]
    (is (false? (events/busy? events/initial-state 1000)))
    (testing "grog-voice heard its name lately, and listens, waits for the answer, or says it"
      (doseq [s ["awake" "asked" "answering" "speaking"]]
        (is (true? (events/busy? (voice s 1000) 2000)) s))
      (is (false? (events/busy? (voice "speaking" 1000) 60000)) "not once it's gone"))
    (testing "not when it only hears someone, or listens"
      (is (false? (events/busy? (voice "hearing" 1000) 2000)))
      (is (false? (events/busy? (voice "listening" 1000) 2000))))
    (testing "a turn under way, until it's done, or old"
      (let [state (run [1000 {:type :turn :conversation "default" :n 1}])]
        (is (true? (events/busy? state 2000)))
        (is (false? (events/busy? state (+ 1000 (* 6 60 1000)))))
        (is (false? (events/busy? (events/step state {:type :done :conversation "default" :n 1 :at 3000})
                                  4000)))))))

(deftest whether-grog-voice-is-saying-an-answer
  (let [now (atom 1000)]
    (with-redefs [events/now-ms #(deref now)]
      (let [hub (events/new-hub (constantly false))
            voice! #(events/publish! hub {:type :voice :state % :conversation "voice"})]
        (is (not (events/saying? hub "voice")) "it never said anything")
        (voice! "answering")
        (is (events/saying? hub "voice"))
        (is (not (events/saying? hub "default")) "not another conversation's answer")
        (voice! "speaking")
        (is (events/saying? hub "voice"))
        (testing "not once it's gone quiet for long: it's gone"
          (reset! now 40000)
          (is (not (events/saying? hub "voice"))))
        (voice! "asked")
        (is (not (events/saying? hub "voice")))))))

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
