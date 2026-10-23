(ns grog.actions
  "What can be asked of grog, beyond answering: to listen now, without its
  name, or to stop answering, and its music (grog-voice, see grog.voice;
  grog.music); to turn its mic
  on or off; to change the speaker's volume; to forget its conversations;
  to connect something to it (grog.integrations); to update itself
  (grog.update); and to go away: restart itself (its voice, then its
  brain), restart the machine, or turn it off. Someone at its skin asks
  through `POST /v1/actions/<name>` (grog.api), and they happen right away.
  The model asks to go away through the `power` tool, and that waits for
  its answer to be said (`after-answer!`)."
  (:require [babashka.fs :as fs]
            [grog.conversation :as conversation]
            [grog.events :as events]
            [grog.hands :as hands]
            [grog.integrations :as integrations]
            [grog.log :as log]
            [grog.machine :as machine]
            [grog.music :as music]
            [grog.player :as player]
            [grog.update :as update]
            [grog.voice :as voice]))

;; grog restarts itself by exiting with it: its systemd service starts it
;; again then (the NixOS module's RestartForceExitStatus).
(def restart-exit-code 75)

;; How long a turn's actions wait at most for its answer to be said.
(def ^:private said-timeout-ms (* 2 60 1000))
(def ^:private poll-ms 100)

;; Time for answers on their way (to whoever asked, to whoever watches) to
;; go, before grog goes.
(def ^:private going-ms 500)

;; Restarting the machine, or turning it off: systemctl's verb, and logind's
;; method telling whether polkit allows it.
(def ^:private power-verbs
  {"reboot" {:verb "reboot" :can "CanReboot"}
   "power-off" {:verb "poweroff" :can "CanPowerOff"}})

;; What can be asked of grog, on some machines only (see `available`).
(def known #{"listen" "stop" "mic" "volume" "new-conversation" "connect" "update" "restart" "reboot" "power-off"})

(defn exit!
  "Ends grog with `code` (tests redefine it)."
  [code]
  (System/exit code))

(defn service?
  "Whether grog is what a systemd service runs, which starts it again when it
  exits (tests redefine it). Its children are told too, but they aren't it."
  []
  (when-let [pid (System/getenv "SYSTEMD_EXEC_PID")]
    (= pid (try (str (fs/read-link "/proc/self")) (catch Exception _ nil)))))

(defn ^:private hands? [config]
  (some? (get-in config [:hands :socket])))

(defn ^:private going-away
  "The actions making grog go away it can take with `config`: restarting
  itself when it runs as a service, and with hands, restarting the machine
  or turning it off."
  [config]
  (cond-> []
    (service?) (conj "restart")
    (hands? config) (into ["reboot" "power-off"])))

(defn available
  "What can be asked of grog with `config`: to listen or stop (when
  grog-voice is there to ask), its mic, forgetting its conversations,
  connecting something, the volume with hands, updating itself with the
  update service and hands, and going away (`going-away`)."
  [config]
  (into (cond-> ["listen" "stop" "mic" "new-conversation" "connect"]
          (hands? config) (conj "volume")
          (update/available? config) (conj "update"))
        (going-away config)))

(defn ^:private on-machine!
  "Runs `command` through the hands: {:exit :output :timed-out :truncated}."
  [config command]
  (hands/ask! (get-in config [:hands :socket]) command))

(defn root-command
  "A command telling (by its exit) whether the hands' user may do
  `can` (logind's CanReboot, CanPowerOff) as root: with sudo, without a
  password, or else as polkit allows."
  [can]
  (str "sudo -n true 2>/dev/null || busctl call org.freedesktop.login1 /org/freedesktop/login1"
       " org.freedesktop.login1.Manager " can " 2>/dev/null | grep -qx 's \"yes\"'"))

(defn check!
  "Throws ex-info saying why, when action `name` can't be taken now."
  [ctx name]
  (let [config @(:config ctx)]
    (when-not (some #{name} (available config))
      (throw (ex-info (case name
                        "restart" "not running as a service here: nothing would start it again"
                        ("reboot" "power-off" "volume") "there are no hands to do that with (hands.socket)"
                        "update" "it can't update itself here: no update service, or no hands to start it"
                        (str "there's no action called " name))
                      {:type ::unavailable})))
    (when (and (or (= "listen" name)
                   (and (= "stop" name) (not (player/there? config))))
               (not (voice/there? (:voice ctx))))
      (throw (ex-info "its voice isn't there to ask" {:type ::unavailable})))
    (when-let [{:keys [can]} (power-verbs name)]
      (when-not (zero? (:exit (on-machine! config (root-command can))))
        (throw (ex-info "the machine's user needs sudo without a password for that" {:type ::not-allowed}))))))

(defn ^:private act!
  "What going away (`name`) does: restart grog-voice, if there are hands to,
  then exit for systemd to start grog again; or restart the machine, or
  turn it off, as root."
  [ctx name]
  (let [config @(:config ctx)]
    (if (= "restart" name)
      (do (when (hands? config)
            (try (on-machine! config "systemctl --user restart --no-block grog-voice.service")
                 (catch clojure.lang.ExceptionInfo e
                   (log/info "restarting without grog-voice:" (ex-message e)))))
          (exit! restart-exit-code))
      ;; The machine may go before the hands answer.
      (let [verb (get-in power-verbs [name :verb])
            {:keys [exit]} (on-machine! config (format "sudo -n systemctl %s 2>/dev/null || systemctl %s"
                                                       verb verb))]
        (when-not (= 0 exit)
          (log/info (format "action %s: the machine refused it (exit %s)" name exit))
          ;; It stays.
          (events/publish! (:events ctx) {:type :restarting}))))))

(defn ^:private go!
  "Goes away (`name`): whoever watches is told at once, and it goes a moment
  later, for the answer to whoever asked to go out."
  [ctx name]
  (events/publish! (:events ctx) {:type :restarting :action name})
  (future
    (Thread/sleep going-ms)
    (try (act! ctx name)
         (catch Exception e
           (log/info (format "action %s: %s" name (or (ex-message e) (.getName (class e))))))))
  {:ok true})

(defn ^:private listen!
  "Asks grog-voice to listen now, without its name, turning its mic on first
  when it's off."
  [{:keys [voice] :as ctx}]
  (when (= "off" (voice/mic voice))
    (voice/set-mic! ctx true))
  (voice/ask! voice :listen)
  {:ok true})

(defn ^:private stop!
  "Asks grog-voice to stop answering, the turn under way, if any, ending
  then, stopped (grog.events), and pauses grog's music."
  [{:keys [voice events] :as ctx}]
  (music/pause! ctx)
  (events/publish! events {:type :stop})
  (voice/ask! voice :stop)
  {:ok true})

(defn ^:private mic! [ctx {:keys [on]}]
  (when-not (boolean? on)
    (throw (ex-info "say whether it's on: {\"on\": true or false}" {:type ::bad-args})))
  (voice/set-mic! ctx on))

(defn ^:private forget!
  "Forgets every conversation: what's said next starts a new one."
  [{:keys [store events]}]
  (conversation/forget-all! store)
  (events/publish! events {:type :forgot})
  {:ok true})

(defn ^:private connect!
  "Opens a session connecting the `integration` `args` say, `way` (its
  usual way without): the session (grog.connect)."
  [ctx {:keys [integration way]}]
  (when-not (and (string? integration) (or (nil? way) (string? way)))
    (throw (ex-info "say what to connect: {\"integration\": its id, and maybe \"way\"}" {:type ::bad-args})))
  (integrations/open! ctx integration way))

(defn perform!
  "Takes action `name`, with `args` (the volume's, see grog.machine; the
  mic's, {:on}; connect's, {:integration :way}), once `check!` says it can:
  what to answer."
  ([ctx name]
   (perform! ctx name {}))
  ([ctx name args]
   (check! ctx name)
   (log/info "action:" name)
   (case name
     "listen" (listen! ctx)
     "stop" (stop! ctx)
     "mic" (mic! ctx args)
     "volume" (machine/set-volume! ctx args)
     "new-conversation" (forget! ctx)
     "connect" (connect! ctx args)
     "update" (update/start! @(:config ctx))
     (go! ctx name))))

(defn ^:private wait-until
  "Waits until `(pred)`, looking every `poll-ms`, for at most `timeout-ms`:
  whether it came true."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep poll-ms) (recur))))))

(defn after-answer!
  "Takes the actions `names` asked for during a turn of conversation `id`,
  once its answer is out: as soon as grog-voice isn't saying it (it may not
  be), or after a while. Returns the future, or nil without any."
  [ctx id names]
  (when (seq names)
    (future
      (wait-until #(not (events/saying? (:events ctx) id)) said-timeout-ms)
      (doseq [name names]
        (try (perform! ctx name)
             (catch Exception e
               (log/info (format "action %s: %s" name (or (ex-message e) (.getName (class e)))))))))))

(defn ^:private later!
  "Goes away (`name`) once the answer of `turn` is out, if it can: its
  caller takes the actions in its :later atom then (`after-answer!`)."
  [ctx name turn]
  (check! ctx name)
  (when-not (some #{name} (going-away @(:config ctx)))
    (throw (ex-info (str "power doesn't " name) {:type ::unavailable})))
  (if-let [later (:later turn)]
    (do (swap! later conj name)
        "it happens once your answer was said")
    (throw (ex-info "it can't be done from here" {:type ::unavailable}))))

(defn tool
  "The `power` tool, for the ways grog can go away with `config`, or nil
  without any."
  [config]
  (when-let [names (seq (going-away config))]
    {:name "power"
     :description (str "Restarts you (your voice and your brain: you're back in under a minute), restarts the "
                       "whole machine, or turns it off (nobody can talk to you then, until someone turns it on). "
                       "When the user asks. It happens once your answer was said: say what you'll do, in a few "
                       "words.")
     :parameters {:type "object"
                  :properties {:action {:type "string"
                                        :enum (vec names)
                                        :description (str "restart: you; reboot: the machine; power-off: the "
                                                          "machine, off.")}}
                  :required ["action"]
                  :additionalProperties false}
     :changes? (constantly true)
     :detail :action
     :run (fn [ctx {:keys [action]} turn]
            (later! ctx (str action) turn))}))
