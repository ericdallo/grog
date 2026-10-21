(ns grog.computers
  "The user's computers grog acts on, paired with it (grog.pairing), and the
  `computer` tool. grog reaches them over SSH, with a key of its own that
  each one only lets run grog's hands there (grog.remote-hands), which
  decide what grog may do. Their host keys are pinned, by name: one whose
  key changed has to be paired again. They're kept in computers.json, the
  key and their host keys in computers/, in the state directory, which only
  grog can read."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.log :as log]
            [grog.machine :as machine]
            [grog.remote-hands :as remote-hands]
            [grog.state :as state])
  (:import (java.net DatagramPacket DatagramSocket InetAddress)))

;; How long ssh tries to connect.
(def connect-timeout-s 5)

(def reach-ms
  "How long reaching a computer takes at most, on top of what it's asked."
  (* 1000 (+ connect-timeout-s 5)))

;; What an action takes at most there, besides asking whoever is at it.
(def ^:private action-ms 15000)

(def name-pattern
  "A computer's name, as the model, the skin and the CLI call it."
  #"[a-z0-9][a-z0-9-]{0,31}")

(def user-pattern
  "A user's name, on a computer."
  #"[a-z_][a-z0-9_.-]{0,31}")

(def host-pattern
  "Where a computer is: an address, or a name."
  #"[A-Za-z0-9][A-Za-z0-9.:-]{0,252}")

(def mac-pattern
  "A network card's address."
  #"(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")

;; Changes to the computers, and making grog's key, one at a time.
(def ^:private lock (Object.))

(defn file [config]
  (str (fs/path (config/state-dir config) "computers.json")))

(defn dir [config]
  (str (fs/path (config/state-dir config) "computers")))

(defn key-file [config]
  (str (fs/path (dir config) "id_ed25519")))

(defn known-hosts-file [config]
  (str (fs/path (dir config) "known_hosts")))

(defn all
  "The computers paired with grog, by name: [{:name :host :port :user
  :host-key :mac :added-at}]. None when computers.json can't be read
  (logged, without what's in it)."
  [config]
  (let [f (file config)]
    (if-not (fs/exists? f)
      []
      (try
        (vec (sort-by :name (:computers (config/parse (slurp f)))))
        (catch Exception e
          (log/info (format "ignoring the computers in %s: %s" f (.getSimpleName (class e))))
          [])))))

(defn by-name
  "The computer called `name`, or nil."
  [config name]
  (some #(when (= name (:name %)) %) (all config)))

(defn summary
  "What grog tells of its computers: [{:name :user :host :added-at}]."
  [config]
  (mapv #(select-keys % [:name :user :host :added-at]) (all config)))

(defn known-hosts
  "The known_hosts of `computers`: their host keys, by name (ssh's
  HostKeyAlias), wherever they are."
  [computers]
  (apply str (for [{:keys [name host-key]} computers]
               (str name " " host-key "\n"))))

(defn ^:private save! [config computers]
  (let [computers (vec (sort-by :name computers))]
    (state/write! (file config) (config/generate {:computers computers} {:pretty true}))
    (state/write! (known-hosts-file config) (known-hosts computers))))

(defn add!
  "Keeps `computer`, in place of any of the same name."
  [config computer]
  (locking lock
    (save! config (conj (vec (remove #(= (:name computer) (:name %)) (all config))) computer)))
  computer)

(defn remove!
  "Forgets computer `name`."
  [config name]
  (locking lock
    (save! config (remove #(= name (:name %)) (all config)))))

(defn ^:private keygen
  "The ssh-keygen next to the ssh grog runs."
  [config]
  (let [ssh (get-in config [:computers :ssh])]
    (if-let [parent (fs/parent ssh)]
      (str (fs/path parent "ssh-keygen"))
      "ssh-keygen")))

(defn public-key!
  "grog's public key, which its computers let in: made the first time, with
  ssh-keygen."
  [config]
  (let [k (key-file config)
        pub (str k ".pub")]
    (locking lock
      (when-not (fs/exists? pub)
        (fs/create-dirs (dir config))
        (fs/set-posix-file-permissions (dir config) "rwx------")
        (let [{:keys [exit err]} (try @(p/process {:cmd [(keygen config) "-q" "-t" "ed25519" "-N" "" "-C" (:name config)
                                                         "-f" k]
                                                   :out :string :err :string})
                                      (catch java.io.IOException e
                                        {:exit -1 :err (str (keygen config) ": " (ex-message e))}))]
          (when-not (zero? exit)
            (throw (ex-info (str "grog couldn't make its SSH key: " (str/trim (str err))) {:type ::no-key}))))))
    (str/trim (slurp pub))))

(defn ssh-command
  "How grog runs ssh to reach `computer`: with its own key only, checking
  the computer's pinned host key (by name, wherever it is), never prompting."
  [config {:keys [name host port user]}]
  (when-not (and (re-matches name-pattern (str name))
                 (re-matches host-pattern (str host))
                 (re-matches user-pattern (str user))
                 (pos-int? port))
    (throw (ex-info (str "grog's record of " name " makes no sense: pair it again") {:type ::bad-record})))
  [(get-in config [:computers :ssh])
   "-F" "/dev/null" "-T" "-x" "-a"
   "-i" (key-file config)
   "-o" "IdentitiesOnly=yes"
   "-o" "IdentityAgent=none"
   "-o" "BatchMode=yes"
   "-o" "StrictHostKeyChecking=yes"
   "-o" (str "UserKnownHostsFile=" (known-hosts-file config))
   "-o" "GlobalKnownHostsFile=/dev/null"
   "-o" (str "HostKeyAlias=" name)
   "-o" "CheckHostIP=no"
   "-o" "UpdateHostKeys=no"
   "-o" (str "ConnectTimeout=" connect-timeout-s)
   "-o" "ServerAliveInterval=10"
   "-o" "ServerAliveCountMax=2"
   "-o" "LogLevel=ERROR"
   "-p" (str port)
   "-l" user
   host
   ;; Its hands run whatever this says (the key's command).
   "grog"])

(defn answer-in
  "The answer in what grog's hands printed: their last line of JSON, or nil."
  [out]
  (some->> (str/split-lines (str out))
           (map str/trim)
           (filter #(str/starts-with? % "{"))
           last
           (#(try (config/parse %) (catch Exception _ nil)))))

(defn unreachable
  "Why computer `name` can't be reached, from what ssh said (`err`)."
  [name err]
  (let [said (str err)]
    (cond
      (re-find #"(?i)host key verification failed|remote host identification has changed" said)
      (format "%s's SSH host key changed: if it was set up again, pair it again" name)

      (re-find #"(?i)permission denied" said)
      (format "%s doesn't let grog in anymore: pair it again" name)

      (re-find #"(?i)connection refused" said)
      (format "%s doesn't take SSH connections: is its SSH server on?" name)

      (re-find #"(?i)no route to host|timed out|network is unreachable|could not resolve" said)
      (format "%s can't be reached: it may be off or asleep, or somewhere else now" name)

      :else
      (format "%s can't be reached (%s)" name (or (first (remove str/blank? (str/split-lines said)))
                                                  "ssh failed")))))

(defn ^:private sender
  "grog's name, as its hands take it (grog.remote-hands/check)."
  [config]
  (let [n (str/replace (str (:name config)) #"[^\p{L}\p{N} ._-]" "")]
    (if (str/blank? n) "grog" (subs n 0 (min 40 (count n))))))

(defn ask!
  "Asks grog's hands on computer `name` for `request` (grog.remote-hands),
  giving them `timeout-ms` (and the time to reach them): what they answer.
  Throws ex-info saying why, when they can't be reached, or refuse."
  [config name request timeout-ms]
  (let [computer (or (by-name config name)
                     (throw (ex-info (str "there's no computer called " name) {:type ::unknown})))
        line (config/generate (assoc request :v remote-hands/protocol-version :from (sender config)))
        proc (p/process {:cmd (ssh-command config computer) :in (str line "\n") :out :string :err :string})
        result (deref proc (+ timeout-ms reach-ms) nil)]
    (when-not result
      (p/destroy-tree proc)
      (throw (ex-info (str name " took too long to answer") {:type ::late})))
    (let [{:keys [exit out err]} result
          answer (answer-in out)]
      (cond
        (and (map? answer) (:error answer)) (throw (ex-info (str (:error answer)) {:type ::refused}))
        (map? answer) answer
        (= 255 exit) (throw (ex-info (unreachable name err) {:type ::unreachable}))
        :else (throw (ex-info (format "%s gave no answer: is grog still installed there?" name)
                              {:type ::no-answer}))))))

(defn magic-packet
  "The Wake-on-LAN packet waking the network card with address `mac`: six
  0xff, then the address sixteen times."
  [mac]
  (let [address (map #(unchecked-byte (Integer/parseInt % 16)) (str/split mac #":"))]
    (byte-array (concat (repeat 6 (unchecked-byte 0xff)) (apply concat (repeat 16 address))))))

(defn broadcasts
  "Where the Wake-on-LAN packet of a computer last at `host` goes: to
  everyone on this network, and on its /24 when it's an IPv4 address."
  [host]
  (distinct (cons "255.255.255.255"
                  (when-let [[_ prefix] (re-matches #"(\d+\.\d+\.\d+)\.\d+" (str host))]
                    [(str prefix ".255")]))))

(defn ^:private wake!
  "Sends `computer` a Wake-on-LAN packet: what to tell the model."
  [{:keys [name mac host]}]
  (when-not (and (string? mac) (re-matches mac-pattern mac))
    (throw (ex-info (format "grog doesn't know %s's network card: pair it again to wake it" name)
                    {:type ::no-mac})))
  (with-open [socket (doto (DatagramSocket.) (.setBroadcast true))]
    (let [packet (magic-packet mac)]
      (doseq [address (broadcasts host)]
        (.send socket (DatagramPacket. packet (alength packet) (InetAddress/getByName address) 9)))))
  (log/info (format "computer %s: wake" name))
  (format (str "Sent %s a Wake-on-LAN packet: if it can wake that way (wired, and set up for it), it's up in a "
               "minute or so.")
          name))

(def ^:private sent
  "What each action sends of the model's arguments."
  {"notify" [:text] "open" [:target] "media" [:media] "volume" [:level :change :mute] "shell" [:command]})

(defn act!
  "Takes the action the model's `args` ask for, on the computer they name:
  what to tell the model."
  [config {:keys [computer action] :as args}]
  (let [found (or (by-name config computer)
                  (throw (ex-info (format "there's no computer called %s (there's %s)"
                                          computer (str/join ", " (map :name (all config))))
                                  {:type ::unknown})))]
    (if (= "wake" action)
      (wake! found)
      (let [started (System/nanoTime)
            timeout (+ remote-hands/ask-timeout-ms (if (= "shell" action) remote-hands/default-timeout-ms action-ms))]
        (try
          (let [answer (ask! config computer (merge {:action action} (select-keys args (get sent action))) timeout)]
            (log/info (format "computer %s: %s, in %d ms" computer action (quot (- (System/nanoTime) started) 1000000)))
            (case action
              "status" (:status answer)
              "shell" (cond-> (select-keys answer [:exit :output])
                        (:timed-out answer) (assoc :timed_out true)
                        (:truncated answer) (assoc :truncated true))
              answer))
          (catch clojure.lang.ExceptionInfo e
            ;; Not its message: a computer's could say what a command printed.
            (log/info (format "computer %s: %s, %s" computer action (name (or (:type (ex-data e)) :failed))))
            (throw e)))))))

(defn forget!
  "Forgets computer `name`, asking its hands there to forget grog's key
  first: {:name :unpaired}, whether they did (it may be off)."
  [config name]
  (when-not (by-name config name)
    (throw (ex-info (str "there's no computer called " name) {:type ::unknown})))
  (let [unpaired (try (ask! config name {:action "unpair" :key (public-key! config)} action-ms)
                      true
                      (catch clojure.lang.ExceptionInfo _ false))]
    (remove! config name)
    (log/info (format "computer %s: forgotten" name))
    {:name name :unpaired unpaired}))

(def ^:private tool-actions
  ["status" "notify" "open" "media" "volume" "lock" "suspend" "power-off" "wake" "shell"])

(defn tool
  "The `computer` tool, for the computers paired with grog, or nil without
  any."
  [config]
  (when-let [names (seq (map :name (all config)))]
    {:name "computer"
     :description (str "Acts on the user's computers paired with you, not the machine you run on: tells how one "
                       "is doing (its battery, its load, its screen, what plays), shows a notification on its "
                       "screen, opens a link or a file there, controls what plays or its volume, locks its "
                       "screen, suspends it, turns it off, wakes it up (Wake-on-LAN, when it's wired and set up "
                       "for it), or runs a bash command as its user. Each computer decides what you may do: some "
                       "actions wait for someone at it to allow them, and fail when nobody does; say so then. "
                       "One that can't be reached may be off or asleep.")
     :parameters {:type "object"
                  :properties {:computer {:type "string" :enum (vec names)}
                               :action {:type "string" :enum tool-actions}
                               :text {:type "string" :description "notify: what it says."}
                               :target {:type "string"
                                        :description "open: a link (https://...), or the path of a file there."}
                               :media {:type "string"
                                       :enum (vec (sort remote-hands/media-commands))
                                       :description "media: what to do with what plays."}
                               :level {:type "integer" :minimum 0 :maximum 100
                                       :description "volume: set it to this percentage."}
                               :change {:type "integer" :minimum -100 :maximum 100
                                        :description "volume: raise (positive) or lower (negative) it by this much."}
                               :mute {:type "boolean" :description "volume: mute (true) or unmute (false)."}
                               :command {:type "string" :description "shell: the bash command."}}
                  :required ["computer" "action"]
                  :additionalProperties false}
     :changes? (fn [args] (not= "status" (:action args)))
     :detail (fn [{:keys [computer action text target media command] :as args}]
               (str computer ": " (str/join " " (remove str/blank? [action
                                                                    (case action
                                                                      "notify" text
                                                                      "open" target
                                                                      "media" media
                                                                      "volume" (machine/volume-detail args)
                                                                      "shell" command
                                                                      nil)]))))
     :run (fn [ctx args _] (act! @(:config ctx) args))}))
