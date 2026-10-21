(ns grog.events
  "What grog is up to, for screens showing it, like its skin: a state, and the
  events changing it, sent as they happen to whoever watches (`GET
  /v1/events`, as server-sent events, keys in lowerCamelCase). Turns
  (grog.assistant), grog-voice (`POST /v1/voice`) and the server (who grog
  is, how signing in goes, what it consumed) publish them.

  The state:
    :name :provider :model :auth  who grog is, and how it's paid
    :timezone  grog's time zone (IANA), for clocks to show its time
    :actions   what can be asked of grog (grog.actions), like \"restart\"
    :restarting  once grog is going away, asked to: {:action :at}, the
               action being restart, reboot or power-off; nil otherwise
    :mic       \"on\" or \"off\": whether grog-voice's mic is on, as asked
               (grog.voice)
    :volume    the speaker's volume, once known: {:level :muted}
    :started-at  when grog started: until grog-voice says anything, it may
               be on its way
    :voice     grog-voice's state: {:state :since :seen :conversation
               :error-at}, nil until it says; :error-at: when it last went
               into \"error\", if it did
    :heard     what grog-voice last heard for grog (its name first, or a
               reply to it), as it comes: {:text :final :at}, a guess at
               what was said so far until it's final
    :turn      the current turn, or the last one: {:conversation :n :steps
               :question :said :answer :done-at :ok :stopped :error
               :spoken-at}, :n telling it from the others, :said being what
               was said aloud for it as grog-voice heard it (the wake word
               included), its steps {:step :at} (heard, thinking, tool with
               :name, answering, speaking), in order, :stopping once it's
               asked to stop, :stopped when it then ended (not well, without
               failing), :spoken-at when grog-voice was done saying its
               answer, if it said it; nil once the conversations were
               forgotten
    :recent    the last 4 turns, newest first: {:at :conversation :ms :tools
               :ok :stopped}
    :login     the last sign-in to the provider's subscription, as
               grog.login/view tells: {:provider :status :method :label},
               and while it waits for a code to be entered, the :code, the
               :url to enter it at, its :expires-at and :help {:text :url};
               nil before any
    :usage     what it consumed (grog.usage)
    :captions  whether what's said is in it
    :now       when it was sent

  What's said (what grog-voice heard, questions, what was said for them,
  answers, error messages) only goes in with captions (the `skin.captions`
  setting): anyone who can reach grog can watch."
  (:require [grog.sse :as sse]))

;; How long after grog-voice heard someone a turn of its conversation counts
;; it as heard: more than an utterance and its transcription take.
(def ^:private heard-window-ms 30000)

;; grog-voice says what it's doing at least every 10 s: past this, it's gone.
(def ^:private voice-gone-ms 35000)

(def ^:private recent-max 4)

(def voice-states
  "What grog-voice says it's doing (voice/grog_voice/reporter.py)."
  #{"loading" "listening" "hearing" "awake" "asked" "answering" "speaking" "error" "no-mic" "muted"})

(defn now-ms
  "The clock (tests redefine it)."
  []
  (System/currentTimeMillis))

(def initial-state
  {:voice nil :turn nil :recent [] :login nil :usage nil})

(defn ^:private turn-of?
  "Whether `event` is about the current turn."
  [{:keys [turn]} {:keys [conversation n]}]
  (and (some? turn) (= conversation (:conversation turn)) (= n (:n turn))))

(defn ^:private add-step [state step]
  (update-in state [:turn :steps] conj step))

(defn ^:private last-step [state]
  (:step (peek (get-in state [:turn :steps]))))

(defn ^:private spoken?
  "Whether grog-voice, which was saying the current turn's answer, just
  stopped."
  [{:keys [voice turn]} voice-state conversation]
  (and (= "speaking" (:state voice))
       (not= "speaking" voice-state)
       (= conversation (:conversation turn))
       (boolean (some #(= "speaking" (:step %)) (:steps turn)))))

(defn ^:private on-voice
  "grog-voice's new state, and what it heard for grog, if it says. Its first
  words in a turn are a step of it: when it started hearing them is kept
  for the turn (:heard-at, not sent). Once it said the turn's answer, the
  turn says when (:spoken-at)."
  [{:keys [voice turn] :as state} {voice-state :state :keys [at conversation heard]}]
  (let [changed? (not= voice-state (:state voice))
        error-at (if (and changed? (= "error" voice-state)) at (:error-at voice))
        said? (spoken? state voice-state conversation)
        state (cond-> (assoc state :voice (cond-> {:state voice-state
                                                   :since (if changed? at (:since voice))
                                                   :seen at
                                                   :conversation conversation}
                                            error-at (assoc :error-at error-at)))
                (and changed? (= "hearing" voice-state)) (assoc :heard-at at)
                heard (assoc :heard (assoc heard :at at))
                said? (assoc-in [:turn :spoken-at] at))]
    (if (and changed?
             (= "speaking" voice-state)
             (= conversation (:conversation turn))
             (not-any? #(= "speaking" (:step %)) (:steps turn)))
      (add-step state {:step "speaking" :at at})
      state)))

(defn ^:private on-turn
  "A turn starts: heard, when grog-voice just heard it, then thinking."
  [{:keys [voice heard-at] :as state} {:keys [conversation n question said at]}]
  (let [heard? (and heard-at
                    (= conversation (:conversation voice))
                    (< (- at heard-at) heard-window-ms))]
    (cond-> (assoc state :turn (cond-> {:conversation conversation
                                        :n n
                                        :steps (cond-> []
                                                 heard? (conj {:step "heard" :at heard-at})
                                                 true (conj {:step "thinking" :at at}))}
                                 question (assoc :question question)
                                 said (assoc :said said)))
      heard? (dissoc :heard-at))))

(defn ^:private on-text
  "Text of the answer: answering, again after a tool (while grog-voice
  speaks it, it's the same answer)."
  [state {:keys [text at] :as event}]
  (if-not (turn-of? state event)
    state
    (cond-> state
      (not (#{"answering" "speaking"} (last-step state))) (add-step {:step "answering" :at at})
      text (update-in [:turn :answer] str text))))

(defn ^:private on-end
  "The turn is over, well (:done) or not (:failed): stopped, when it was
  asked to (see `on-stop`), rather than failed."
  [state {:keys [type conversation ms tools error at] :as event}]
  (let [ok? (= :done type)
        current? (turn-of? state event)
        stopped? (boolean (and current? (not ok?) (get-in state [:turn :stopping])))]
    (cond-> (update state :recent #(vec (take recent-max (cons (cond-> {:at at
                                                                        :conversation conversation
                                                                        :ms ms
                                                                        :tools (vec tools)
                                                                        :ok ok?}
                                                                 stopped? (assoc :stopped true))
                                                               %))))
      current? (update :turn #(cond-> (dissoc (assoc % :done-at at :ok ok?) :stopping)
                                stopped? (assoc :stopped true)
                                (and error (not stopped?)) (assoc :error error))))))

(defn ^:private on-stop
  "The turn under way was asked to stop: when it ends, it's stopped."
  [{:keys [turn] :as state}]
  (if (and turn (not (:done-at turn)))
    (assoc-in state [:turn :stopping] true)
    state))

(defn step
  "The state after `event` (with its :type and :at)."
  [state {:keys [type] :as event}]
  (case type
    :voice (on-voice state event)
    :turn (on-turn state event)
    :tool (if (turn-of? state event)
            (add-step state {:step "tool" :name (:name event) :at (:at event)})
            state)
    :text (on-text state event)
    (:done :failed) (on-end state event)
    :stop (on-stop state)
    :forgot (assoc state :turn nil :heard nil)
    :status (merge state (select-keys event [:name :provider :model :auth :timezone :actions :mic]))
    :mic (assoc state :mic (:mic event))
    :volume (assoc state :volume (select-keys event [:level :muted]))
    ;; Without an action, it stays after all.
    :restarting (assoc state :restarting (when (:action event) (select-keys event [:action :at])))
    :login (assoc state :login (:login event))
    :usage (assoc state :usage (:usage event))
    state))

(defn redact
  "`event` without what was said in it."
  [event]
  (dissoc event :question :said :text :error :heard))

(defn new-hub
  "Where the state lives and who watches it. `captions?` tells whether what's
  said goes in."
  [captions?]
  {:state (atom (assoc initial-state :started-at (now-ms)))
   :watchers (atom {})
   :captions? captions?
   ;; One event at a time, sent to everyone in the same order.
   :lock (Object.)})

(defn captions?
  "Whether what's said goes to the watchers (`skin.captions`)."
  [{:keys [captions?]}]
  (boolean (and captions? (captions?))))

(defn saying?
  "Whether grog-voice, as it last said, is answering in `conversation`, or
  saying the answer."
  [{:keys [state]} conversation]
  (let [{voice-state :state :keys [seen] :as voice} (:voice @state)]
    (and (= conversation (:conversation voice))
         (contains? #{"answering" "speaking"} voice-state)
         (< (- (now-ms) seen) voice-gone-ms))))

(defn snapshot
  "The state, as watchers get it."
  [{:keys [state] :as hub}]
  (assoc (dissoc @state :heard-at) :captions (captions? hub) :now (now-ms)))

(defn ^:private send-all!
  "Sends `text` to every watcher, forgetting those gone (their send! returned
  false)."
  [{:keys [watchers]} text]
  (doseq [[k send!] @watchers]
    (when-not (send! text)
      (swap! watchers dissoc k))))

(defn publish!
  "Applies `event` ({:type ...}) to the state, without what was said in it
  unless captions are on, and sends both to the watchers. A nil hub ignores
  it."
  [{:keys [state lock] :as hub} event]
  (when hub
    (locking lock
      (let [event (cond-> (assoc event :at (now-ms))
                    (not (captions? hub)) redact)
            [before after] (swap-vals! state step event)]
        (when (not= before after)
          (send-all! hub (sse/message (name (:type event))
                                      {:event (dissoc event :type)
                                       :state (snapshot hub)})))))))

(defn watch!
  "Sends the state to `send!` (a function of an SSE text returning false
  once the watcher is gone), then every change, until it's gone or
  `unwatch!`ed by `k`."
  [{:keys [watchers lock] :as hub} k send!]
  (locking lock
    (when (send! (sse/message "snapshot" {:state (snapshot hub)}))
      (swap! watchers assoc k send!))))

(defn unwatch! [{:keys [watchers lock]} k]
  (locking lock
    (swap! watchers dissoc k)))

(defn ping!
  "A comment to every watcher: keeps the connections alive, and finds those
  gone."
  [{:keys [lock] :as hub}]
  (locking lock
    (send-all! hub ": ping\n\n")))
