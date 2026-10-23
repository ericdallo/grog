(ns grog.actions-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.actions :as actions]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.conversation :as conversation]
            [grog.events :as events]
            [grog.hands :as hands]
            [grog.test-helpers :as h]
            [grog.voice :as voice]))

(def ^:private socket "/run/grog-hands/hands.sock")

(defn ^:private context
  ([] (context true))
  ([hands?]
   (assistant/new-context (cond-> (assoc config/defaults :state-dir (h/temp-dir))
                            hands? (assoc-in [:hands :socket] socket)))))

(defn ^:private state [ctx]
  (events/snapshot (:events ctx)))

(deftest connects-something
  (let [ctx (assistant/new-context (-> config/defaults
                                       (assoc :state-dir (h/temp-dir))
                                       (update :integrations merge {:host "127.0.0.1" :port 0})))]
    (try
      (is (= {:integration "computer" :status "open"}
             (select-keys (actions/perform! ctx "connect" {:integration "computer"}) [:integration :status])))
      (is (= "script" (get-in (state ctx) [:connect :step :kind])) "for the skin")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"say what to connect" (actions/perform! ctx "connect" {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"There's no integration called \"nope\""
                            (actions/perform! ctx "connect" {:integration "nope"})))
      (finally
        (connect/stop! ctx)))))

(defn ^:private with-machine
  "Runs (f ran exited) with grog as a service (or not, `service?`), its
  hands answering commands with (`answer` command) and keeping them in
  `ran`, and grog's exits delivered to `exited` rather than made."
  [answer service? f]
  (let [ran (atom [])
        exited (promise)]
    (with-redefs [actions/service? (constantly service?)
                  actions/exit! #(deliver exited %)
                  hands/ask! (fn [_ command] (swap! ran conj command) (answer command))]
      (f ran exited))))

(def ^:private ok (constantly {:exit 0 :output ""}))
(def ^:private refused (constantly {:exit 1 :output ""}))

(deftest what-it-can-do
  (let [voice ["listen" "stop" "mic" "speakers" "new-conversation" "connect"]]
    (with-redefs [actions/service? (constantly false)]
      (is (= voice (actions/available config/defaults)))
      (is (= (into voice ["volume" "wifi" "reboot" "power-off"])
             (actions/available (assoc-in config/defaults [:hands :socket] socket))))
      (is (nil? (actions/tool config/defaults))))
    (with-redefs [actions/service? (constantly true)]
      (is (= (conj voice "restart") (actions/available config/defaults)))
      (testing "the model is offered those making it go away"
        (is (= ["restart"] (get-in (actions/tool config/defaults) [:parameters :properties :action :enum])))))
    (testing "updating itself, with the update service and hands to start it"
      (with-redefs [actions/service? (constantly false)]
        (is (= (into voice ["volume" "wifi" "update" "reboot" "power-off"])
               (actions/available (-> config/defaults
                                      (assoc-in [:hands :socket] socket)
                                      (assoc-in [:update :status] "/run/grog-update/status.json")))))))))

(deftest updates-itself
  (with-machine #(if (str/includes? % "is-active") {:exit 3 :output ""} {:exit 0 :output ""}) false
    (fn [ran _]
      (let [ctx (assistant/new-context (-> config/defaults
                                           (assoc :state-dir (h/temp-dir))
                                           (assoc-in [:hands :socket] socket)
                                           (assoc-in [:update :status] "/run/grog-update/none.json")))]
        (is (= {:started true} (actions/perform! ctx "update")))
        (is (str/includes? (last @ran) "systemctl start --no-block grog-update.service")))))
  (testing "not without the update service"
    (with-machine ok false
      (fn [_ _]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"can't update itself here"
                              (actions/perform! (context) "update")))))))

(deftest restarts-itself
  (with-machine ok true
    (fn [ran exited]
      (let [ctx (context)]
        (is (= {:ok true} (actions/perform! ctx "restart")))
        (testing "whoever watches is told at once"
          (is (= "restart" (get-in (state ctx) [:restarting :action]))))
        (testing "its voice, then its brain, which systemd starts again"
          (is (= actions/restart-exit-code (deref exited 5000 :not-yet)))
          (is (= ["systemctl --user restart --no-block grog-voice.service"] @ran))))))
  (testing "without hands, only its brain"
    (with-machine ok true
      (fn [ran exited]
        (actions/perform! (context false) "restart")
        (is (= actions/restart-exit-code (deref exited 5000 :not-yet)))
        (is (= [] @ran)))))
  (testing "not when nothing would start it again"
    (with-machine ok false
      (fn [_ exited]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not running as a service"
                              (actions/perform! (context) "restart")))
        (is (= :never (deref exited 700 :never)))))))

(deftest restarts-or-turns-off-the-machine
  (with-machine ok false
    (fn [ran _]
      (let [ctx (context)]
        (actions/perform! ctx "reboot")
        (is (= "reboot" (get-in (state ctx) [:restarting :action])))
        (Thread/sleep 1000)
        (testing "checking it may first: sudo, or else polkit"
          (is (= [(actions/root-command "CanReboot")
                  "sudo -n systemctl reboot 2>/dev/null || systemctl reboot"]
                 @ran))
          (is (str/includes? (first @ran) "sudo -n true"))))))
  (testing "when the machine refuses it after all, it stays"
    (with-machine #(if (str/includes? % "poweroff") (refused) (ok)) false
      (fn [_ _]
        (let [ctx (context)]
          (actions/perform! ctx "power-off")
          (Thread/sleep 1000)
          (is (nil? (:restarting (state ctx))))))))
  (testing "not without root"
    (with-machine refused false
      (fn [ran _]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs sudo without a password"
                              (actions/perform! (context) "power-off")))
        (is (= 1 (count @ran))))))
  (testing "nor without hands"
    (with-machine ok false
      (fn [_ _]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no hands"
                              (actions/perform! (context false) "reboot")))))))

(defn ^:private voice-watcher
  "A grog-voice watching for what `ctx` asks of it: what it got, as [type
  data]."
  [ctx]
  (let [got (atom [])]
    (voice/watch! (:voice ctx) :voice (fn [text]
                                        (let [[_ type data] (re-find #"event: (\S+)\ndata: (.*)\n" text)]
                                          (swap! got conj [type (json/parse-string data true)]))
                                        true))
    got))

(deftest asks-grog-voice-to-listen-or-stop
  (let [ctx (context false)]
    (testing "only when it's there"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"voice isn't there" (actions/perform! ctx "listen"))))
    (let [got (voice-watcher ctx)]
      (actions/perform! ctx "listen")
      (testing "stopping, the turn under way ends stopped"
        (events/publish! (:events ctx) {:type :turn :conversation "voice" :n 1})
        (actions/perform! ctx "stop")
        (is (true? (get-in (state ctx) [:turn :stopping]))))
      (is (= [["snapshot" {:mic "on" :music false :speaker nil}] ["listen" {}] ["stop" {}]] @got)))))

(deftest turns-the-mic-on-or-off
  (let [ctx (context false)
        got (voice-watcher ctx)]
    (is (= {:mic "off"} (actions/perform! ctx "mic" {:on false})))
    (is (= "off" (:mic (state ctx))))
    (testing "asked to listen, it's turned on first"
      (actions/perform! ctx "listen")
      (is (= [["snapshot" {:mic "on" :music false :speaker nil}] ["mic" {:mic "off"}] ["mic" {:mic "on"}] ["listen" {}]] @got)))
    (testing "only on or off"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"whether it's on" (actions/perform! ctx "mic" {:on "yes"}))))))

(def ^:private speakers
  [{:name "bluez_output.1" :label "EDIFIER" :kind "bluetooth" :default true}
   {:name "alsa_output.speaker" :label "Speaker" :kind "speaker" :default false}])

(defn ^:private grog-voice
  "A grog-voice watching for what `ctx` asks of it (what it got, as [type
  data], in the atom returned), saying `speakers` are there when asked, the
  one asked for the music the default."
  [ctx]
  (let [got (atom [])]
    (voice/watch! (:voice ctx) :voice
                  (fn [text]
                    (let [[_ type data] (re-find #"event: (\S+)\ndata: (.*)\n" text)
                          {:keys [id music] :as data} (json/parse-string data true)]
                      (swap! got conj [type data])
                      (when (= "speakers" type)
                        (future (voice/speakers-said! (:voice ctx)
                                                      {:id id
                                                       :speakers (cond->> speakers
                                                                   music (mapv #(assoc % :default (= music (:name %)))))}))))
                    true))
    got))

(deftest picks-the-speakers
  (let [ctx (context false)]
    (testing "only when grog-voice is there"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"voice isn't there" (actions/perform! ctx "speakers" {}))))
    (let [got (grog-voice ctx)
          builtin {:name "alsa_output.speaker" :label "Speaker"}]
      (with-out-str
        (testing "which there are"
          (is (= {:speakers speakers :voice nil} (actions/perform! ctx "speakers" {}))))
        (testing "one the music's: the default"
          (let [{listed :speakers} (actions/perform! ctx "speakers" {:music "alsa_output.speaker"})]
            (is (= ["alsa_output.speaker"] (map :name (filter :default listed)))))
          (is (= ["speakers" {:id 2 :music "alsa_output.speaker"}] (last @got))))
        (testing "one its voice's, for good"
          (is (= builtin (:voice (actions/perform! ctx "speakers" {:voice "alsa_output.speaker"}))))
          (is (= ["speaker" {:speaker "alsa_output.speaker"}] (last @got)))
          (is (= builtin (:speaker (state ctx))))
          (is (= builtin (voice/load-speaker @(:config ctx)))))
        (testing "and back to its own"
          (is (nil? (:voice (actions/perform! ctx "speakers" {:voice nil}))))
          (is (= ["speaker" {:speaker nil}] (last @got))))
        (testing "only speakers there are, by their names"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such speaker"
                                (actions/perform! ctx "speakers" {:voice "gone"})))
          (is (nil? (voice/speaker (:voice ctx))))
          (doseq [args [{:music 5} {:voice ""} {:music (apply str (repeat 201 "x"))}]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"say which speaker"
                                  (actions/perform! ctx "speakers" args)))))))))

(deftest changes-the-volume
  (with-machine (constantly {:exit 0 :output "Volume: 0.40 [MUTED]\n"}) false
    (fn [ran _]
      (let [ctx (context)]
        (is (= {:level 40 :muted true} (actions/perform! ctx "volume" {:change -10 :mute true})))
        (is (str/starts-with? (first @ran) "wpctl set-mute @DEFAULT_AUDIO_SINK@ 1"))
        (testing "whoever watches knows it then"
          (is (= {:level 40 :muted true} (:volume (state ctx)))))
        (testing "of the speaker grog speaks on, once picked"
          (with-out-str (voice/set-speaker! ctx {:name "alsa_output.speaker" :label "Speaker"}))
          (actions/perform! ctx "volume" {})
          (is (str/starts-with? (last @ran) "sink=$(pw-dump -N 'alsa_output.speaker' "))
          (is (str/ends-with? (last @ran) "; wpctl get-volume \"$sink\""))))))
  (testing "with hands only"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no hands" (actions/perform! (context false) "volume" {})))))

(deftest forgets-the-conversations
  (let [ctx (context false)]
    (conversation/save! (:store ctx) "voice" [{:role "user" :content "hi"}] (System/currentTimeMillis)
                        {:ttl-ms 60000 :max-messages 20})
    (events/publish! (:events ctx) {:type :turn :conversation "voice" :n 1})
    (is (= {:ok true} (actions/perform! ctx "new-conversation")))
    (is (= {} @(:store ctx)))
    (is (nil? (:turn (state ctx))))))

(deftest the-model-asks-and-it-waits-for-the-answer
  (with-machine ok true
    (fn [_ exited]
      (let [ctx (context)
            {:keys [run]} (actions/tool @(:config ctx))
            later (atom [])
            voice! #(events/publish! (:events ctx) {:type :voice :state % :conversation "voice"})]
        (testing "the tool only says it'll happen"
          (is (= "it happens once your answer was said" (run ctx {:action "restart"} {:id "voice" :n 1 :later later})))
          (is (= ["restart"] @later))
          (is (= :not-yet (deref exited 700 :not-yet))))
        (testing "once the answer is out, it waits for grog-voice to say it"
          (voice! "speaking")
          (let [done (actions/after-answer! ctx "voice" @later)]
            (is (= :not-yet (deref exited 700 :not-yet)))
            (voice! "listening")
            @done
            (is (= actions/restart-exit-code (deref exited 5000 :not-yet))))))))
  (testing "a turn grog-voice isn't saying (the CLI's): right away"
    (with-machine ok true
      (fn [_ exited]
        (let [ctx (context)]
          (events/publish! (:events ctx) {:type :voice :state "speaking" :conversation "voice"})
          @(actions/after-answer! ctx "default" ["restart"])
          (is (= actions/restart-exit-code (deref exited 5000 :not-yet)))))))
  (testing "what it can't do, the model is told at once"
    (with-machine refused true
      (fn [_ _]
        (let [ctx (context)
              {:keys [run]} (actions/tool @(:config ctx))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs sudo"
                                (run ctx {:action "reboot"} {:id "voice" :n 1 :later (atom [])})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"there's no action called dance"
                                (run ctx {:action "dance"} {:id "voice" :n 1 :later (atom [])})))
          (testing "or when whoever asked won't take it afterwards"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"can't be done from here"
                                  (run ctx {:action "restart"} {:id "voice" :n 1})))))))))
