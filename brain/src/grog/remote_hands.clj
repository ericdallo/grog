(ns grog.remote-hands
  "grog's hands on another computer, paired with it (grog.pairing): what
  `grog hands --stdio` does there when grog connects over SSH
  (grog.computers), as the computer's user. It reads a request, a line of
  JSON {\"v\", \"from\", \"action\", ...}, and answers with a line: what the
  action did, or {\"error\"}.

  The computer decides what grog may do there, in its policy
  (~/.config/grog/hands.json): each action is allowed, asked (someone at the
  computer allows it on a notification, in time) or denied. Like grog's own
  hands, they never log commands, or what they printed."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.hands :as hands]
            [grog.machine :as machine]))

;; Grows when grog and its hands would stop understanding each other.
(def protocol-version 1)

(def actions
  "What grog can ask of a computer, as its policy names them. Besides,
  `hello` tells what the computer lets grog do, and `unpair` forgets grog."
  ["status" "notify" "open" "media" "volume" "lock" "suspend" "power-off" "shell"])

(def default-policy
  "What a computer lets grog do unless its user says otherwise: what can't
  hurt, without asking; suspending it, turning it off and commands, asking
  whoever is at it."
  {:allow ["status" "notify" "open" "media" "volume" "lock"]
   :ask ["suspend" "power-off" "shell"]
   :deny []})

(def trusting-policy
  "Everything, without asking, like grog's own hands (`grog pair --trust`)."
  {:allow actions :ask [] :deny []})

;; How long whoever is at the computer has to allow what's asked.
(def ask-timeout-ms 30000)

(def default-timeout-ms 30000)
(def max-timeout-ms (* 10 60 1000))

;; What the actions but shell take at most.
(def ^:private action-timeout-ms 15000)

(def media-commands
  "What playerctl does with what plays."
  #{"play-pause" "play" "pause" "next" "previous" "stop"})

(def ^:private max-text-chars 1000)
(def ^:private max-target-chars 2000)
(def ^:private max-command-chars 20000)
;; How much of a command the notification asking for it shows.
(def ^:private shown-command-chars 300)

(def ^:private doing
  "What each action does, for what's said of it."
  {"status" "tell how it's doing"
   "notify" "show a notification"
   "open" "open something"
   "media" "control what plays"
   "volume" "change the volume"
   "lock" "lock the screen"
   "suspend" "suspend the computer"
   "power-off" "turn the computer off"
   "shell" "run a command"})

(defn policy-file
  "Where the computer's user says what grog may do there."
  []
  (str (fs/path (or (not-empty (System/getenv "XDG_CONFIG_HOME"))
                    (str (fs/path (System/getProperty "user.home") ".config")))
                "grog" "hands.json")))

(defn ^:private names? [v]
  (and (sequential? v) (every? string? v)))

(defn read-policy
  "The policy in `file`, the default without one. Throws ex-info when it
  can't be read, or makes no sense: grog may do nothing then."
  [file]
  (if-not (fs/exists? file)
    default-policy
    (let [policy (try (config/parse (slurp file))
                      (catch Exception _ nil))]
      (when-not (and (map? policy) (every? #(names? (get policy % [])) [:allow :ask :deny]))
        (throw (ex-info (format "its policy, %s, should be {\"allow\": [...], \"ask\": [...], \"deny\": [...]}"
                                file)
                        {:type ::bad-policy})))
      (merge {:allow [] :ask [] :deny []} (select-keys policy [:allow :ask :deny])))))

(defn mode
  "How `policy` lets grog take `action`: :allow, :ask or :deny. Denying wins,
  and what it doesn't name is denied."
  [policy action]
  (let [in? #(boolean (some #{action} (get policy %)))]
    (cond
      (in? :deny) :deny
      (in? :allow) :allow
      (in? :ask) :ask
      :else :deny)))

(defn ^:private fail [message]
  (throw (ex-info message {:type ::bad-request})))

(defn ^:private text
  "`request`'s `k`: text of up to `max-chars`, on one line unless `lines?`."
  ([request k max-chars]
   (text request k max-chars false))
  ([request k max-chars lines?]
   (let [v (get request k)]
     (when-not (and (string? v)
                    (not (str/blank? v))
                    (<= (count v) max-chars)
                    (or lines? (not (re-find #"[\r\n]" v))))
       (fail (format "%s: text of up to %d characters%s" (name k) max-chars (if lines? "" ", on one line"))))
     v)))

(defn ^:private target
  "What `open` opens: a web or mail link, or a file's path."
  [request]
  (let [v (text request :target max-target-chars)]
    (when-not (re-matches #"(?i)(https?://|mailto:|/|~/)\S.*" v)
      (fail "target: a link (https://..., mailto:...) or a file's path (/... or ~/...)"))
    v))

(defn check
  "`request`, when it makes sense, from a grog speaking this protocol: its
  :action, with what it needs, and :from, grog's name. Throws ex-info saying
  what's wrong otherwise."
  [request]
  (when-not (map? request)
    (fail "send a JSON object"))
  (when-not (= protocol-version (:v request))
    (fail (format (str "this computer's grog speaks version %d of what grog says to its hands: update the older "
                       "grog")
                  protocol-version)))
  (let [{:keys [action]} request
        from (or (:from request) "grog")]
    (when-not (and (string? from) (re-matches #"[\p{L}\p{N} ._-]{1,40}" from))
      (fail "from: a name of up to 40 letters"))
    (assoc (case action
             ("hello" "status" "lock" "suspend" "power-off") {:action action}
             "notify" {:action action :text (text request :text max-text-chars true)}
             "open" {:action action :target (target request)}
             "media" (if (contains? media-commands (:media request))
                       {:action action :media (:media request)}
                       (fail (str "media: one of " (str/join ", " (sort media-commands)))))
             ;; volume-command says what's wrong with them.
             "volume" (do (machine/volume-command request)
                          (merge {:action action} (select-keys request [:level :change :mute])))
             "shell" (let [timeout (or (:timeout-ms request) default-timeout-ms)]
                       (when-not (and (pos-int? timeout) (<= timeout max-timeout-ms))
                         (fail (format "timeoutMs: a whole number of milliseconds, from 1 to %d" max-timeout-ms)))
                       {:action action :command (text request :command max-command-chars true) :timeout-ms timeout})
             "unpair" {:action action :key (text request :key 1000)}
             (fail (str "action: one of hello, unpair, " (str/join ", " actions))))
           :from from)))

(defn session-env
  "What commands need to reach the session of the computer's user (its
  D-Bus: notifications, players; its audio), set from its runtime directory
  when SSH didn't: `getenv` reads the environment, (`uid`) tells its user's
  id."
  [getenv uid]
  (let [runtime (or (not-empty (getenv "XDG_RUNTIME_DIR")) (str "/run/user/" (uid)))]
    {"XDG_RUNTIME_DIR" runtime
     "DBUS_SESSION_BUS_ADDRESS" (or (not-empty (getenv "DBUS_SESSION_BUS_ADDRESS"))
                                    (str "unix:path=" runtime "/bus"))}))

(defn ask-command
  "The notify-send asking whoever is at the computer to allow what grog
  (`from`) wants to do: it prints the notification's id, then the action
  they took. Its default action (clicking it, with most notification
  daemons; dunst's middle click) and its Allow button (where there are
  buttons) allow it; ignoring or closing it denies it."
  [from title body]
  ["notify-send" "--app-name" from "--urgency" "critical" "--expire-time" (str ask-timeout-ms)
   "--print-id" "--wait" "--action" "default=Allow" "--action" "allow=Allow" title body])

(defn answer-of
  "What asking (`ask-command`) printed, as {:id :allowed}."
  [output]
  (let [lines (map str/trim (str/split-lines (str output)))]
    {:id (some #(when (re-matches #"\d+" %) %) lines)
     :allowed (boolean (some #{"default" "allow"} lines))}))

(defn ^:private close-command
  "Closes the notification `id`, which nobody answered."
  [id]
  ["busctl" "--user" "call" "org.freedesktop.Notifications" "/org/freedesktop/Notifications"
   "org.freedesktop.Notifications" "CloseNotification" "u" id])

(defn ^:private shown
  "As much of `command` as a notification shows."
  [command]
  (if (> (count command) shown-command-chars)
    (str (subs command 0 shown-command-chars) "…")
    command))

(defn ^:private asking
  "What the notification asking for `request` says: [title body]."
  [{:keys [action from] :as request}]
  [(str from " wants to " (doing action))
   (case action
     "shell" (shown (:command request))
     "open" (:target request)
     "notify" (:text request)
     "Allow it?")])

(defn ^:private allowed!
  "Asks whoever is at the computer to allow `request`. Throws ex-info saying
  why it can't be taken unless they do, in time."
  [{:keys [run host]} request]
  (let [[title body] (asking request)
        {:keys [exit output timed-out]} (run (ask-command (:from request) title body) (+ ask-timeout-ms 2000))
        {:keys [id allowed]} (answer-of output)]
    (when (and timed-out id)
      (run (close-command id) 5000))
    (cond
      allowed
      nil

      (and (not timed-out) (not (zero? exit)))
      (throw (ex-info (format "nobody is there to allow it on %s: it can't ask anyone (no session, or no notify-send)"
                              host)
                      {:type ::nobody}))

      :else
      (throw (ex-info (format "nobody allowed it on %s (someone at it has %d s to)" host (quot ask-timeout-ms 1000))
                      {:type ::unanswered})))))

(defn without-key
  "The authorized_keys `text` without the lines letting `key` in."
  [text key]
  (let [blob (second (str/split (str/trim key) #"\s+"))]
    (->> (str/split-lines text)
         (remove #(and blob (some #{blob} (str/split % #"\s+"))))
         (map #(str % "\n"))
         (apply str))))

(defn ^:private unpair!
  "Forgets grog's `key`: it can't come in anymore."
  [{:keys [authorized-keys]} {:keys [key]}]
  (when (fs/exists? authorized-keys)
    (spit authorized-keys (without-key (slurp authorized-keys) key)))
  {:done true})

(defn ^:private ok
  "What an action answers once `result` (a command's) says it worked:
  `what` failed otherwise."
  [{:keys [exit output]} what]
  (if (zero? exit)
    {:done true}
    (throw (ex-info (str what ": " (or (not-empty (str/trim (str output))) (str "exit " exit)))
                    {:type ::failed}))))

(defn ^:private script [name]
  (delay (slurp (io/resource (str "grog/" name)))))

(def ^:private status-script (script "computer-status.sh"))
(def ^:private lock-script (script "computer-lock.sh"))
(def ^:private power-script (script "computer-power.sh"))

(defn ^:private expand-home [home path]
  (if (str/starts-with? path "~/") (str home (subs path 1)) path))

(defn ^:private take!
  "Takes the action of `request` on this computer: what it did."
  [{:keys [run home]} {:keys [action from] :as request}]
  (case action
    "status" {:status (str/trim (str (:output (run @status-script action-timeout-ms))))}
    "notify" (ok (run ["notify-send" "--app-name" from from (:text request)] action-timeout-ms)
                 "the notification failed")
    "open" (ok (run ["systemd-run" "--user" "--collect" "--quiet" "--" "xdg-open"
                     (expand-home home (:target request))]
                    action-timeout-ms)
               "it couldn't be opened")
    "media" (ok (run ["playerctl" (:media request)] action-timeout-ms) "playerctl failed")
    "volume" (let [{:keys [exit output]} (run (machine/volume-command request) action-timeout-ms)
                   volume (machine/volume-of output)]
               (if (and (zero? exit) volume)
                 volume
                 (throw (ex-info (str "the volume couldn't be changed: " (str/trim (str output))) {:type ::failed}))))
    "lock" (ok (run @lock-script action-timeout-ms) "it couldn't be locked")
    "suspend" (ok (run ["bash" "-c" @power-script "computer-power" "suspend"] action-timeout-ms)
                  "it can't be suspended")
    "power-off" (ok (run ["bash" "-c" @power-script "computer-power" "poweroff"] action-timeout-ms)
                    "it can't be turned off")
    "shell" (run (:command request) (:timeout-ms request))))

(defn handle
  "What answers `request` on this computer (`system`, see `system`): what
  its action did, once its policy lets grog take it. Throws ex-info saying
  why not."
  [{:keys [policy host user] :as system} request]
  (let [{:keys [action from] :as request} (check request)]
    (case action
      "hello" {:v protocol-version :host host :user user :policy (policy)}
      "unpair" (unpair! system request)
      (do (case (mode (policy) action)
            :deny (throw (ex-info (format "%s doesn't let %s %s (its policy: %s)"
                                          host from (doing action) (policy-file))
                                  {:type ::denied}))
            :ask (allowed! system request)
            :allow nil)
          (take! system request)))))

(defn hostname
  "This computer's name."
  []
  (or (some-> (try (slurp "/etc/hostname") (catch Exception _ nil)) str/trim not-empty)
      (some-> (System/getenv "HOSTNAME") not-empty)
      "this computer"))

(defn system
  "This computer, for `handle`: running commands as its user, in reach of
  their session, what its policy says, its name, its user, their home and
  authorized_keys."
  []
  (let [home (System/getProperty "user.home")
        env (session-env #(System/getenv %) #(str/trim (str (:output (hands/run-command ["id" "-u"] 5000)))))]
    ;; A program that isn't there fails like it would in a shell.
    {:run (fn [command timeout-ms]
            (try (hands/run-command command timeout-ms env)
                 (catch java.io.IOException _
                   {:exit 127 :output (str (if (string? command) "bash" (first command)) ": not found")
                    :timed-out false :truncated false})))
     :policy #(read-policy (policy-file))
     :host (hostname)
     :user (System/getProperty "user.name")
     :home home
     :authorized-keys (str (fs/path home ".ssh" "authorized_keys"))}))

(defn serve!
  "Answers the request on stdin, on stdout: what `grog hands --stdio` does
  when grog connects over SSH."
  ([]
   (serve! (system)))
  ([system]
   (let [request (try (config/parse (read-line)) (catch Exception _ nil))
         answer (try (handle system request)
                     (catch clojure.lang.ExceptionInfo e {:error (ex-message e)})
                     (catch Exception e {:error (str "it failed (" (.getSimpleName (class e)) ")")}))]
     (println (config/generate answer))
     (flush))))
