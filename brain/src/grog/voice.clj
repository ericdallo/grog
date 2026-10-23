(ns grog.voice
  "What grog asks of grog-voice, which watches for it (`GET
  /v1/voice/commands`, as server-sent events, keys in lowerCamelCase):
  whether its mic is on, whether grog's music plays, and the speaker it
  speaks on (a `snapshot` first, then `mic`, `music` or `speaker` when one
  changes), and as they come, to `listen` now, without its name, to `stop`
  answering, or which `speakers` its session has (`speakers!`: it answers
  through `POST /v1/voice/speakers`). Whether the mic is on, and the speaker
  picked for it, stay in voice.json, in the state directory: off, grog-voice
  hears nothing until it's turned on again, even after restarts."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.events :as events]
            [grog.log :as log]
            [grog.sse :as sse]
            [grog.state :as state]))

;; How long grog-voice has to say which speakers there are: making one the
;; default, it waits up to 2 s for WirePlumber.
(def ^:private speakers-timeout-ms 5000)

;; The longest name a speaker can have, or a label.
(def ^:private max-name-chars 200)

(defn file [config]
  (str (fs/path (config/state-dir config) "voice.json")))

(defn ^:private mic-name [on?]
  (if on? "on" "off"))

(defn ^:private saved
  "`read` of what voice.json holds: `fallback` without it, or when it can't
  be read or `read` refuses it, throwing (logged as `what`, without what's
  in it)."
  [config what read fallback]
  (let [f (file config)]
    (if-not (fs/exists? f)
      fallback
      (try
        (read (config/parse (slurp f)))
        (catch Exception e
          (log/info (format "ignoring %s in %s: %s" what f (.getSimpleName (class e))))
          fallback)))))

(defn load-mic
  "Whether the mic is on, as it was last set: on when it never was, or when
  the file can't be read (logged, without what's in it)."
  [config]
  (saved config "whether the mic was on"
         #(case (:mic %)
            "on" true
            "off" false
            (throw (ex-info "neither on nor off" {})))
         true))

(defn name?
  "Whether `v` can be a speaker's name (a PipeWire node's)."
  [v]
  (and (string? v) (not (str/blank? v)) (<= (count v) max-name-chars)))

(defn load-speaker
  "The speaker picked for grog-voice, as it was last: {:name :label}, or nil
  for its own, also when the file can't be read (logged)."
  [config]
  (saved config "the speaker picked"
         (fn [{:keys [speaker]}]
           (cond
             (nil? speaker) nil
             (and (map? speaker) (name? (:name speaker)) (name? (:label speaker))) (select-keys speaker [:name :label])
             :else (throw (ex-info "not a speaker" {}))))
         nil))

(defn new-channel
  "Where what grog asks of grog-voice goes: whether its mic is on and the
  speaker picked for it (as last set, with `config`), whether grog's music
  plays (not yet), whoever watches for it, and what it was asked and has
  yet to answer."
  [config]
  {:mic (atom (load-mic config))
   :speaker (atom (load-speaker config))
   :music (atom false)
   :watchers (atom {})
   :asked (atom {})
   :asks (atom 0)
   ;; One message at a time, sent to everyone in the same order.
   :lock (Object.)})

(defn mic
  "\"on\" or \"off\": whether grog-voice's mic is on, as asked."
  [{:keys [mic]}]
  (mic-name @mic))

(defn speaker
  "The speaker grog-voice speaks on, as picked: {:name :label}, or nil for
  its own (its speaker setting, or the default)."
  [{:keys [speaker]}]
  @speaker)

(defn music
  "Whether grog-voice was told grog's music plays."
  [{:keys [music]}]
  @music)

(defn ^:private save!
  "Keeps whether the mic is on (`mic`, \"on\" or \"off\") and the speaker
  picked for grog-voice, for good."
  [config mic speaker]
  (state/write! (file config) (config/generate (cond-> {:mic mic} speaker (assoc :speaker speaker)))))

(defn ^:private send-all!
  "Sends `text` to every watcher, forgetting those gone (their send!
  returned false)."
  [{:keys [watchers]} text]
  (doseq [[k send!] @watchers]
    (when-not (send! text)
      (swap! watchers dissoc k))))

(defn watch!
  "Sends whether the mic is on, whether music plays and the speaker picked
  (its name, or nil) to `send!` (a function of an SSE text returning false
  once the watcher is gone), then what's asked, until it's gone or
  `unwatch!`ed by `k`."
  [{:keys [watchers lock] :as channel} k send!]
  (locking lock
    (when (send! (sse/message "snapshot" {:mic (mic channel)
                                          :music (music channel)
                                          :speaker (:name (speaker channel))}))
      (swap! watchers assoc k send!))))

(defn unwatch! [{:keys [watchers lock]} k]
  (locking lock
    (swap! watchers dissoc k)))

(defn there?
  "Whether grog-voice watches for what grog asks."
  [{:keys [watchers]}]
  (boolean (seq @watchers)))

(defn ask!
  "Asks grog-voice to `command`: :listen or :stop."
  [{:keys [lock] :as channel} command]
  (locking lock
    (send-all! channel (sse/message (name command) {}))))

(defn set-music!
  "Tells grog-voice whether grog's music plays (`playing?`), only when that
  changed: it listens for its name differently over music. Whether it
  changed."
  [{:keys [lock] :as channel} playing?]
  (let [playing? (boolean playing?)
        changed? (locking lock
                   (when (not= playing? @(:music channel))
                     (reset! (:music channel) playing?)
                     (send-all! channel (sse/message "music" {:music playing?}))
                     true))]
    (when changed?
      (log/info "music:" (if playing? "on" "off")))
    (boolean changed?)))

(defn ping!
  "A comment to every watcher: keeps the connections alive, and finds those
  gone."
  [{:keys [lock] :as channel}]
  (locking lock
    (send-all! channel ": ping\n\n")))

(defn set-mic!
  "Turns grog-voice's mic on or off, for good, telling it and whoever
  watches grog (grog.events): {:mic \"on\" or \"off\"}."
  [{:keys [config voice events lock]} on?]
  (let [mic (mic-name on?)
        {voice-lock :lock} voice]
    ;; Whoever watches, in the order it changed.
    (locking voice-lock
      (locking lock
        (save! @config mic (speaker voice)))
      (reset! (:mic voice) on?)
      (send-all! voice (sse/message "mic" {:mic mic}))
      (events/publish! events {:type :mic :mic mic}))
    (log/info "mic:" mic)
    {:mic mic}))

(defn set-speaker!
  "Makes grog-voice speak on `speaker` ({:name :label}, or nil: its own),
  for good, telling it and whoever watches grog (grog.events). Returns it."
  [{:keys [config voice events lock]} speaker]
  (let [{voice-lock :lock} voice]
    (locking voice-lock
      (locking lock
        (save! @config (mic voice) speaker))
      (reset! (:speaker voice) speaker)
      (send-all! voice (sse/message "speaker" {:speaker (:name speaker)}))
      (events/publish! events {:type :speaker :speaker speaker}))
    (log/info "speaker:" (or (:name speaker) "its own"))
    speaker))

(defn speakers!
  "Asks grog-voice which speakers its session has, making the one named
  `music` the default first, when given: what it said within `timeout-ms`,
  [{:name :label :kind :default}]. Throws ex-info saying why when it can't,
  or doesn't say in time."
  ([channel music]
   (speakers! channel music speakers-timeout-ms))
  ([{:keys [lock asked asks] :as channel} music timeout-ms]
   (let [id (swap! asks inc)
         answer (promise)]
     (swap! asked assoc id answer)
     (try
       (locking lock
         (send-all! channel (sse/message "speakers" (cond-> {:id id} music (assoc :music music)))))
       (let [{:keys [speakers error] :as said} (deref answer timeout-ms nil)]
         (cond
           (nil? said) (throw (ex-info "its voice didn't say which speakers there are" {:type ::no-answer}))
           error (throw (ex-info error {:type ::refused}))
           :else speakers))
       (finally
         (swap! asked dissoc id))))))

(defn speakers-said!
  "What grog-voice said as asked by `speakers!` ({:id :speakers}, or {:id
  :error}): whether that was still asked."
  [{:keys [asked]} {:keys [id] :as said}]
  (if-let [answer (get @asked id)]
    (do (deliver answer said) true)
    false))

(def tool
  {:name "microphone"
   :description (str "Turns your microphone off when the user asks you to stop listening (you hear nothing "
                     "then, until someone turns it back on, at your screen), or on again.")
   :parameters {:type "object"
                :properties {:on {:type "boolean" :description "On (true) or off (false)."}}
                :required ["on"]
                :additionalProperties false}
   :changes? (constantly true)
   :detail (fn [{:keys [on]}] (case on true "on" false "off" nil))
   :run (fn [ctx {:keys [on]} _]
          (when-not (boolean? on)
            (throw (ex-info "on must be true or false" {})))
          (set-mic! ctx on))})
