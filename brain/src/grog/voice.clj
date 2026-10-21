(ns grog.voice
  "What grog asks of grog-voice, which watches for it (`GET
  /v1/voice/commands`, as server-sent events, keys in lowerCamelCase):
  whether its mic is on (a `snapshot` first, then `mic` when it changes),
  and as they come, to `listen` now, without its name, or to `stop`
  answering. Whether the mic is on stays in voice.json, in the state
  directory: off, grog-voice hears nothing until it's turned on again, even
  after restarts."
  (:require [babashka.fs :as fs]
            [grog.config :as config]
            [grog.events :as events]
            [grog.log :as log]
            [grog.sse :as sse]
            [grog.state :as state]))

(defn file [config]
  (str (fs/path (config/state-dir config) "voice.json")))

(defn ^:private mic-name [on?]
  (if on? "on" "off"))

(defn load-mic
  "Whether the mic is on, as it was last set: on when it never was, or when
  the file can't be read (logged, without what's in it)."
  [config]
  (let [f (file config)]
    (if-not (fs/exists? f)
      true
      (try
        (case (:mic (config/parse (slurp f)))
          "on" true
          "off" false
          (throw (ex-info "neither on nor off" {})))
        (catch Exception e
          (log/info (format "ignoring whether the mic was on in %s: %s" f (.getSimpleName (class e))))
          true)))))

(defn new-channel
  "Where what grog asks of grog-voice goes: whether its mic is on (as last
  set, with `config`), and whoever watches for it."
  [config]
  {:mic (atom (load-mic config))
   :watchers (atom {})
   ;; One message at a time, sent to everyone in the same order.
   :lock (Object.)})

(defn mic
  "\"on\" or \"off\": whether grog-voice's mic is on, as asked."
  [{:keys [mic]}]
  (mic-name @mic))

(defn ^:private send-all!
  "Sends `text` to every watcher, forgetting those gone (their send!
  returned false)."
  [{:keys [watchers]} text]
  (doseq [[k send!] @watchers]
    (when-not (send! text)
      (swap! watchers dissoc k))))

(defn watch!
  "Sends whether the mic is on to `send!` (a function of an SSE text
  returning false once the watcher is gone), then what's asked, until it's
  gone or `unwatch!`ed by `k`."
  [{:keys [watchers lock] :as channel} k send!]
  (locking lock
    (when (send! (sse/message "snapshot" {:mic (mic channel)}))
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
        (state/write! (file @config) (config/generate {:mic mic})))
      (reset! (:mic voice) on?)
      (send-all! voice (sse/message "mic" {:mic mic}))
      (events/publish! events {:type :mic :mic mic}))
    (log/info "mic:" mic)
    {:mic mic}))

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
