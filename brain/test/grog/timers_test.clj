(ns grog.timers-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [grog.actions :as actions]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.events :as events]
            [grog.test-helpers :as h]
            [grog.timers :as timers]
            [grog.tools :as tools]
            [grog.voice :as voice])
  (:import (java.time ZoneId ZonedDateTime)))

(def ^:private zone "America/Sao_Paulo")

(defn ^:private ms
  "Epoch ms of a time on October 26, 2026 at home."
  [hour minute]
  (.toEpochMilli (.toInstant (ZonedDateTime/of 2026 10 26 hour minute 0 0 (ZoneId/of zone)))))

(def ^:private now (ms 20 0))

(defn ^:private context
  "A context keeping its state in `dir`, showing what's said (captions)."
  ([] (context (h/temp-dir)))
  ([dir] (assistant/new-context (-> config/defaults
                                    (assoc :state-dir dir :timezone zone)
                                    (assoc-in [:skin :captions] true)))))

(defn ^:private grog-voice
  "What `ctx` asked grog-voice to say: [[text sound]]."
  [ctx]
  (let [said (atom [])]
    (voice/watch! (:voice ctx) :voice
                  (fn [text]
                    (when-let [[_ data] (re-find #"event: say\ndata: (.*)\n" text)]
                      (let [{:keys [text sound]} (json/parse-string data true)]
                        (swap! said conj [text sound])))
                    true))
    said))

(defn ^:private shown [ctx]
  (:timers (events/snapshot (:events ctx))))

(deftest when-it-is
  (testing "a time: today, or tomorrow once it's past"
    (is (= (ms 21 30) (timers/when-at "21:30" zone now)))
    (is (= (+ (ms 7 30) (* 24 3600 1000)) (timers/when-at "07:30" zone now)))
    (is (= (+ now (* 24 3600 1000)) (timers/when-at "20:00" zone now)) "now is past"))
  (testing "a date and time"
    (is (= (ms 22 15) (timers/when-at "2026-10-26T22:15" zone now))))
  (testing "nothing else"
    (is (nil? (timers/when-at "tomorrow morning" zone now)))))

(deftest sets-lists-and-cancels
  (let [ctx (context)]
    (with-out-str
      (testing "a timer, after a while"
        (let [r (timers/set-timer! ctx {:in-seconds 600 :label "macarrão" :say "O macarrão está pronto!"} now)]
          (is (= {:set {:kind "timer" :at "2026-10-26T20:10" :in "10 minutes" :label "macarrão"}}
                 (update r :set dissoc :id)))
          (is (= (get-in @(:timers ctx) [0 :id]) (get-in r [:set :id])))))
      (testing "an alarm, at a time"
        (is (= {:kind "alarm" :at "2026-10-27T07:00" :in "11 hours"}
               (select-keys (:set (timers/set-timer! ctx {:kind "alarm" :at "07:00" :say "Bom dia!"} now))
                            [:kind :at :in]))))
      (testing "what's set, soonest first, for the model and whoever watches"
        (is (= ["timer" "alarm"] (map :kind (:timers (timers/listed ctx now)))))
        (is (= [{:kind "timer" :label "macarrão" :at (+ now 600000) :ringing false}
                {:kind "alarm" :at (+ (ms 7 0) (* 24 3600 1000)) :ringing false}]
               (map #(dissoc % :id) (shown ctx))))
        (testing "their labels only with captions"
          (swap! (:config ctx) assoc-in [:skin :captions] false)
          (timers/set-timer! ctx {:in-seconds 60 :label "remédio"} now)
          (is (not-any? :label (shown ctx)))
          (swap! (:config ctx) assoc-in [:skin :captions] true)
          (timers/cancel! ctx {:label "remédio"} now)))
      (testing "when isn't said, or too far"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"say when" (timers/set-timer! ctx {:label "x"} now)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at most a week ahead"
                              (timers/set-timer! ctx {:in-seconds (* 8 24 3600)} now))))
      (testing "cancelled by its label, accents aside, or its id"
        (is (= ["timer"] (map :kind (:cancelled (timers/cancel! ctx {:label "Macarrao"} now)))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"say which"
                              (do (timers/set-timer! ctx {:in-seconds 60} now)
                                  (timers/cancel! ctx {} now))))
        (let [id (:id (first (:timers (timers/listed ctx now))))]
          (is (= [id] (map :id (:cancelled (timers/cancel! ctx {:id id} now))))))
        (is (= ["alarm"] (map :kind (:cancelled (timers/cancel! ctx {} now)))) "the only one")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing is set" (timers/cancel! ctx {} now)))))))

(deftest go-off-and-ring-until-stopped
  (let [ctx (context)
        said (grog-voice ctx)]
    (with-out-str
      (timers/set-timer! ctx {:in-seconds 60 :label "chá" :say "O chá está pronto."} now)
      (testing "not before its time"
        (timers/tick! ctx (+ now 59000))
        (is (= [] @said)))
      (testing "then said, with its sound, and shown going off"
        (timers/tick! ctx (+ now 60000))
        (is (= [["O chá está pronto." "alarm"]] @said))
        (is (true? (:ringing (first (shown ctx))))))
      (testing "again every 20 s, 3 times, then dropped"
        (timers/tick! ctx (+ now 70000))
        (is (= 1 (count @said)))
        (timers/tick! ctx (+ now 80000))
        (timers/tick! ctx (+ now 100000))
        (is (= 3 (count @said)))
        (timers/tick! ctx (+ now 120000))
        (is (= 3 (count @said)))
        (is (= [] (shown ctx)))))
    (testing "stopped by the stop action"
      (with-out-str
        (timers/set-timer! ctx {:in-seconds 1 :label "ovo"} now)
        (timers/tick! ctx (+ now 1000))
        (is (= ["ovo" "alarm"] (last @said)) "its label, when it was given nothing to say")
        (actions/perform! ctx "stop" {})
        (is (= [] (shown ctx)))))
    (testing "and by touching grog's face"
      (with-out-str
        (timers/set-timer! ctx {:in-seconds 1 :label "ovo"} now)
        (timers/tick! ctx (+ now 1000))
        (actions/perform! ctx "listen" {})
        (is (= [] (shown ctx)))))
    (testing "and by someone talking to grog"
      (timers/watch-voice! ctx)
      (try
        (with-out-str
          (timers/set-timer! ctx {:in-seconds 1 :label "pão"} now)
          (timers/tick! ctx (+ now 1000))
          (events/publish! (:events ctx) {:type :voice :state "hearing" :conversation "voice"})
          (is (h/eventually 2000 #(= [] (shown ctx)))))
        (finally (timers/unwatch-voice! ctx))))))

(deftest those-going-off-together-are-said-together
  (let [ctx (context)
        said (grog-voice ctx)]
    (with-out-str
      (timers/set-timer! ctx {:in-seconds 60 :say "O chá está pronto."} now)
      (timers/set-timer! ctx {:in-seconds 60 :label "bolo"} now)
      (timers/set-timer! ctx {:in-seconds 60} now)
      (timers/tick! ctx (+ now 60000))
      (is (= [["O chá está pronto. bolo" "alarm"]] @said) "after its sound, once")
      (is (= [true true true] (map :ringing (shown ctx)))))))

(deftest kept-across-restarts
  (let [dir (h/temp-dir)
        ctx (context dir)]
    (with-out-str
      (timers/set-timer! ctx {:in-seconds 60 :label "bolo" :say "O bolo!"} now)
      (timers/set-timer! ctx {:in-seconds (* 30 60) :label "pizza"} now))
    (let [again (context dir)
          said (grog-voice again)]
      (is (= ["bolo" "pizza"] (map :label @(:timers again))))
      (with-out-str
        (testing "one that went off while grog was away, lately, goes off once it's back; older ones go"
          (timers/tick! again (+ now (* 60 1000) timers/late-ms 1000))
          (is (= [] @said) "the cake's went off over 10 minutes ago")
          (is (= ["pizza"] (map :label @(:timers again))))
          (timers/tick! again (+ now (* 35 60 1000)))
          (is (= [["pizza" "alarm"]] @said)))))))

(deftest the-tool
  (let [ctx (context)
        {:keys [run detail parameters]} timers/tool]
    (with-out-str
      (is (= "timer" (get-in (run ctx {:action "set" :in_seconds 300 :label "café"} {:id "c" :n 1}) [:set :kind])))
      (is (= 1 (count (:timers (run ctx {:action "list"} {:id "c" :n 1})))))
      (is (= 1 (count (:cancelled (run ctx {:action "cancel" :label "café"} {:id "c" :n 1})))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"action: set, list or cancel"
                            (run ctx {:action "snooze"} {:id "c" :n 1}))))
    (is (= "set timer" (detail {:action "set" :kind "timer" :label "the secret"})) "never what it's for")
    (is (= ["action"] (:required parameters)))
    (is (some #{"timers"} (map :name (tools/available @(:config ctx)))))))
