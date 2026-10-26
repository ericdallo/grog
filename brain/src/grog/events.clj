(ns grog.events
  "What grog is up to, for screens showing it, like its skin: a state, and the
  events changing it, sent as they happen to whoever watches (`GET
  /v1/events`, as server-sent events, keys in lowerCamelCase). Turns
  (grog.assistant), grog-voice (`POST /v1/voice`) and the server (who grog
  is, what's connected to it and how connecting goes, what it consumed)
  publish them.

  The state:
    :name :provider :model :auth  who grog is, and how it's paid
    :revision  the revision of grog running, when known (grog.update)
    :timezone  grog's time zone (IANA), for clocks to show its time
    :screen-off-minutes  how long a screen showing it stays on once nothing
               happens (the `skin.screenOffMinutes` setting); nil: for good
    :screen-off  how it goes off then: \"auto\", \"dark\" or \"off\" (the
               `skin.screenOff` setting)
    :actions   what can be asked of grog (grog.actions), like \"restart\"
    :restarting  once grog is going away, asked to: {:action :at}, the
               action being restart, reboot or power-off; nil otherwise
    :update    how the last update of grog goes, or went (grog.updater):
               {:stage :from :to :to-date :started-at :at :error
               :rolled-back}; nil without any
    :mic       \"on\" or \"off\": whether grog-voice's mic is on, as asked
               (grog.voice)
    :speaker   the speaker grog-voice speaks on, as picked: {:name :label
               :kind}, nil for its own (grog.voice)
    :volume    the volume of the speaker it speaks on, once known: {:level
               :muted}
    :started-at  when grog started: until grog-voice says anything, it may
               be on its way
    :voice     grog-voice's state: {:state :since :seen :conversation
               :error-at}, nil until it says; :error-at: when it last went
               into \"error\", if it did
    :heard     what grog-voice last heard for grog (its name first, or a
               reply to it), as it comes: {:text :final :at}, a guess at
               what was said so far until it's final
    :turn      the current turn, or the last one: {:conversation :n :steps
               :parts :question :said :done-at :ok :stopped :error
               :spoken-at}, :n telling it from the others, :said being what
               was said aloud for it as grog-voice heard it (the wake word
               included), its steps {:step :at} (heard, thinking, tool with
               :name, answering, speaking), in order, its parts what its
               answer is made of, in order: text ({:text}) and the tools it
               used ({:tool :detail :at :done-at}, :detail saying what with,
               like what it searched, once known), :stopping once it's asked
               to stop, :stopped when it then ended, or whoever asked cut
               it off (not well, without failing), :error and :reason once
               it failed (its message, and a word whoever watches may see
               without captions: grog.assistant/failure-reason),
               :spoken-at when grog-voice was done saying its answer, if it
               said it; nil once the conversations were forgotten
    :history   the turns before it, up to 20, oldest first, as :turn; only
               sent with the events changing it (and the snapshot)
    :integrations  what grog can connect to, and what's connected, as
               grog.integrations/summary tells: [{:id :title :heading
               :about :icon :ways :multiple :connections}], MCP servers'
               with :mcp :added :cloud, no secrets
    :connect   the last session connecting something, as grog.connect/view
               tells: {:integration :title :heading :way :ways :status
               :kept :until :at :step :link :error :connected}, its :step
               saying what to do now ({:kind ...}: a device-code's :code and
               :url, a script's :command, a code-compare's :code...), its
               :link the page on the network, with its token; nil before any
    :weather   the weather the model last looked up (grog.weather/card):
               {:place :now :days :at}; nil before
    :music     what grog's player plays, as grog.music last saw it:
               {:state :title :artist :album :artwork :position-s
               :duration-s :volume :ducked :index :count :error :error-at
               :provider :at}, :error saying why the last song couldn't
               play (grog-music's), :state
               playing, paused or idle (without a song then), :position-s
               where the song was :at (sent when anything else changes,
               and while it plays, every 5 s or so: screens move its clock
               themselves); nil without a player, or while it doesn't
               answer. Not what was said: it goes in without captions
    :usage     what it consumed (grog.usage)
    :captions  whether what's said is in it
    :now       when it was sent

  What's said (what grog-voice heard, questions, what was said for them,
  answers, what tools were used with, error messages) only goes in with
  captions (the `skin.captions` setting): anyone who can reach grog can
  watch."
  (:require [grog.sse :as sse]))

;; How long after grog-voice heard someone a turn of its conversation counts
;; it as heard: more than an utterance and its transcription take.
(def ^:private heard-window-ms 30000)

;; grog-voice says what it's doing at least every 10 s: past this, it's gone.
(def ^:private voice-gone-ms 35000)

(def ^:private history-max 20)

(def voice-states
  "What grog-voice says it's doing (voice/grog_voice/reporter.py)."
  #{"loading" "listening" "hearing" "awake" "asked" "answering" "speaking" "error" "no-mic" "muted"})

(defn now-ms
  "The clock (tests redefine it)."
  []
  (System/currentTimeMillis))

(def initial-state
  {:voice nil :turn nil :history [] :connect nil :usage nil :music nil})

(defn ^:private turn-path
  "Where in `state` the turn `event` is about is: [:turn], [:history i], or
  nil when it's gone."
  [{:keys [turn history]} {:keys [conversation n]}]
  (let [this? #(and (= conversation (:conversation %)) (= n (:n %)))]
    (if (and turn (this? turn))
      [:turn]
      (some #(when (this? (history %)) [:history %]) (range (count history))))))

(defn ^:private add-step [state path step]
  (update-in state (conj path :steps) conj step))

(defn ^:private last-step [state path]
  (:step (peek (get-in state (conj path :steps)))))

(defn ^:private close-tool
  "`parts`, the tool under way at their end, if any, done `at`."
  [parts at]
  (let [parts (or parts [])
        part (peek parts)]
    (if (and (:tool part) (not (:done-at part)))
      (conj (pop parts) (assoc part :done-at at))
      parts)))

(defn ^:private add-text
  "`parts` with `text` at their end."
  [parts text]
  (let [part (peek parts)]
    (if (contains? part :text)
      (conj (pop parts) (update part :text str text))
      (conj parts {:text text}))))

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
      (add-step state [:turn] {:step "speaking" :at at})
      state)))

(defn ^:private archive
  "`state` with its turn, if any, last in its history."
  [{:keys [turn] :as state}]
  (cond-> state
    turn (update :history #(vec (take-last history-max (conj (or % []) turn))))))

(defn ^:private on-turn
  "A turn starts, the last one going in the history: heard, when
  grog-voice just heard it, then thinking."
  [state {:keys [conversation n question said at]}]
  (let [{:keys [voice heard-at] :as state} (archive state)
        heard? (and heard-at
                    (= conversation (:conversation voice))
                    (< (- at heard-at) heard-window-ms))]
    (cond-> (assoc state :turn (cond-> {:conversation conversation
                                        :n n
                                        :steps (cond-> []
                                                 heard? (conj {:step "heard" :at heard-at})
                                                 true (conj {:step "thinking" :at at}))
                                        :parts []}
                                 question (assoc :question question)
                                 said (assoc :said said)))
      heard? (dissoc :heard-at))))

(defn ^:private on-tool
  "The turn uses a tool: a step, and a part of its answer, the tool before
  done."
  [state {:keys [name at] :as event}]
  (if-let [path (turn-path state event)]
    (-> state
        (add-step path {:step "tool" :name name :at at})
        (update-in (conj path :parts) #(conj (close-tool % at) {:tool name :at at})))
    state))

(defn ^:private on-text
  "Text of the answer: answering, again after a tool (while grog-voice
  speaks it, it's the same answer), which is then done."
  [state {:keys [text at] :as event}]
  (if-let [path (turn-path state event)]
    (cond-> (update-in state (conj path :parts) close-tool at)
      (not (#{"answering" "speaking"} (last-step state path))) (add-step path {:step "answering" :at at})
      text (update-in (conj path :parts) add-text text))
    state))

(defn ^:private on-end
  "The turn is over, well (:done) or not (:failed): stopped, when it was
  asked to (see `on-stop`) or cut off by whoever asked (:stopped, someone
  calling grog while it answered, say), rather than failed."
  [state {:keys [type error reason stopped at] :as event}]
  (if-let [path (turn-path state event)]
    (let [ok? (= :done type)
          stopped? (boolean (and (not ok?) (or stopped (get-in state (conj path :stopping)))))]
      (update-in state path #(cond-> (-> (assoc % :done-at at :ok ok?)
                                         (dissoc :stopping)
                                         (update :parts close-tool at))
                               stopped? (assoc :stopped true)
                               (and error (not stopped?)) (assoc :error error)
                               (and reason (not stopped?)) (assoc :reason reason))))
    state))

(defn ^:private on-stop
  "The turn under way was asked to stop: when it ends, it's stopped."
  [{:keys [turn] :as state}]
  (if (and turn (not (:done-at turn)))
    (assoc-in state [:turn :stopping] true)
    state))

(defn ^:private on-tool-detail
  "What a tool of the turn is used with: the last one by that name not told
  yet."
  [state {:keys [name detail] :as event}]
  (let [path (turn-path state event)
        parts (get-in state (conj path :parts))
        i (when (and path detail)
            (last (keep-indexed #(when (and (= name (:tool %2)) (not (:detail %2))) %1) parts)))]
    (if i
      (assoc-in state (conj path :parts i :detail) detail)
      state)))

(defn step
  "The state after `event` (with its :type and :at)."
  [state {:keys [type] :as event}]
  (case type
    :voice (on-voice state event)
    :turn (on-turn state event)
    :tool (on-tool state event)
    :tool-detail (on-tool-detail state event)
    :text (on-text state event)
    (:done :failed) (on-end state event)
    :stop (on-stop state)
    :forgot (assoc state :turn nil :heard nil :history [])
    :status (merge state (select-keys event [:name :provider :model :auth :revision :timezone :screen-off-minutes
                                             :screen-off :actions :mic :speaker :integrations]))
    :mic (assoc state :mic (:mic event))
    :speaker (assoc state :speaker (:speaker event))
    :volume (assoc state :volume (select-keys event [:level :muted]))
    ;; Without an action, it stays after all.
    :restarting (assoc state :restarting (when (:action event) (select-keys event [:action :at])))
    :update (assoc state :update (:update event))
    :usage (assoc state :usage (:usage event))
    :connect (assoc state :connect (:connect event))
    :music (assoc state :music (:music event))
    :weather (assoc state :weather (:weather event))
    state))

(defn redact
  "`event` without what was said in it."
  [event]
  (dissoc event :question :said :text :error :heard :detail))

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

;; grog-voice's states while someone talks to grog: once it heard its name.
(def ^:private talking-states #{"awake" "asked" "answering" "speaking"})

;; A turn this old is over, whatever it said.
(def ^:private turn-max-ms (* 5 60 1000))

(defn busy?
  "Whether someone is talking to grog, as `state` says at `now`: grog-voice
  heard its name lately, and is listening, waiting for the answer or saying
  it, or a turn is under way."
  [{:keys [voice turn]} now]
  (boolean
   (or (and (contains? talking-states (:state voice))
            (< (- now (or (:seen voice) 0)) voice-gone-ms))
       (and turn
            (not (:done-at turn))
            (< (- now (or (:at (first (:steps turn))) 0)) turn-max-ms)))))

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
  unless captions are on, and sends both to the watchers, the history only
  when it changed: the text of an answer comes a few words at a time. A nil
  hub ignores it."
  [{:keys [state lock] :as hub} event]
  (when hub
    (locking lock
      (let [event (cond-> (assoc event :at (now-ms))
                    (not (captions? hub)) redact)
            [before after] (swap-vals! state step event)]
        (when (not= before after)
          (send-all! hub (sse/message (name (:type event))
                                      {:event (dissoc event :type)
                                       :state (cond-> (snapshot hub)
                                                (identical? (:history before) (:history after))
                                                (dissoc :history))})))))))

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
