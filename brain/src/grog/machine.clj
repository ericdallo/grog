(ns grog.machine
  "Tools that act on the machine grog runs on, through its hands (grog.hands,
  the `hands.socket` setting): the volume of the speaker grog speaks on, how
  the machine is doing, and a shell, which runs what the model asks right
  away. Restarting is grog.actions'."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.events :as events]
            [grog.hands :as hands]
            [grog.voice :as voice]))

(defn ^:private on-machine!
  "Runs `command` through the hands: {:exit :output :timed-out :truncated}."
  [config command]
  (hands/ask! (get-in config [:hands :socket]) command))

(def ^:private default-sink "@DEFAULT_AUDIO_SINK@")

(defn ^:private find-sink
  "Bash setting `sink` to the id of the speaker named `speaker`, found with
  pw-dump, or to the default one while it isn't there (grog-voice speaks on
  that one then)."
  [speaker]
  (str "sink=$(pw-dump -N " (hands/quoted speaker) " 2>/dev/null | grep -m 1 -o '\"id\": *[0-9]*' | grep -o '[0-9]*$'); "
       "sink=${sink:-" default-sink "}; "))

(defn ^:private whole
  "`args`'s `k`, a number from `lo` to `hi`, rounded; nil when absent."
  [args k lo hi]
  (when (contains? args k)
    (let [v (get args k)]
      (if (and (number? v) (<= lo v hi))
        (Math/round (double v))
        (throw (ex-info (format "%s must be a number from %d to %d" (name k) lo hi) {}))))))

(defn volume-command
  "The wpctl commands doing what the `volume` tool's `args` say, then telling
  the volume, on the speaker named `speaker` (the one picked for
  grog-voice), or the default one."
  ([args]
   (volume-command args nil))
  ([args speaker]
   (let [level (whole args :level 0 100)
         change (whole args :change -100 100)
         mute (:mute args)
         sink (if speaker "\"$sink\"" default-sink)]
     (when-not (or (nil? mute) (boolean? mute))
       (throw (ex-info "mute must be true or false" {})))
     (str (when speaker (find-sink speaker))
          (str/join " && " (concat (when (some? mute)
                                     [(str "wpctl set-mute " sink " " (if mute 1 0))])
                                   (when level
                                     [(str "wpctl set-volume -l 1 " sink " " level "%")])
                                   (when change
                                     [(str "wpctl set-volume -l 1 " sink " " (abs change) "%" (if (neg? change) "-" "+"))])
                                   [(str "wpctl get-volume " sink)]))))))

(defn volume-of
  "The volume `volume-command` told, from what it printed: {:level :muted},
  or nil when it didn't."
  [output]
  (when-let [[_ level muted] (re-find #"Volume: ([\d.]+)( \[MUTED\])?" (str output))]
    {:level (Math/round (* 100 (Double/parseDouble level))) :muted (some? muted)}))

(defn set-volume!
  "Tells the volume of the speaker grog speaks on, or changes it as `args`
  say (see `volume-command`), telling whoever watches grog (grog.events):
  {:level :muted}."
  [{:keys [config events] :as ctx} args]
  (let [speaker (some-> (:voice ctx) voice/speaker :name)
        {:keys [exit output]} (on-machine! @config (volume-command args speaker))
        volume (volume-of output)]
    (if (and (zero? exit) volume)
      (do (events/publish! events (assoc volume :type :volume))
          volume)
      (throw (ex-info (str "the volume couldn't be changed: " (str/trim (str output))) {})))))

(defn volume-detail
  "What a volume is changed with, for whoever watches: a level, a change,
  muting."
  [{:keys [level change mute]}]
  (str/join " " (remove nil? [(when (int? level) (str level "%"))
                              (when (int? change) (format "%+d" change))
                              (case mute true "mute" false "unmute" nil)])))

(def volume-tool
  {:name "volume"
   :detail volume-detail
   :description (str "The volume of the speaker you speak on: tells it, or changes it when the user asks (louder, "
                     "quieter, to a level, mute, unmute).")
   :parameters {:type "object"
                :properties {:level {:type "integer" :minimum 0 :maximum 100
                                     :description "Set it to this percentage."}
                             :change {:type "integer" :minimum -100 :maximum 100
                                      :description "Raise (positive) or lower (negative) it by this many points."}
                             :mute {:type "boolean" :description "Mute (true) or unmute (false)."}}
                :additionalProperties false}
   :changes? (fn [args] (boolean (some #(contains? args %) [:level :change :mute])))
   :run (fn [ctx args _] (set-volume! ctx args))})

(def status-script
  (delay (slurp (io/resource "grog/system-status.sh"))))

(def status-tool
  {:name "system_status"
   :description (str "How the machine you run on is doing: how long it's been up, its load, memory, disk "
                     "and temperature, its network and Wi-Fi, the speaker and mic in use, the Bluetooth "
                     "devices connected, your services, and its NixOS version and when it was last deployed.")
   :parameters {:type "object" :properties {} :additionalProperties false}
   :changes? (constantly false)
   :run (fn [{:keys [config]} _ _]
          (str/trim (str (:output (on-machine! @config @status-script)))))})

(defn ^:private shell! [{:keys [config]} {:keys [command]} _]
  (when (str/blank? command)
    (throw (ex-info "say which command to run" {})))
  (let [{:keys [exit output timed-out truncated]} (on-machine! @config command)]
    (cond-> {:exit exit :output output}
      timed-out (assoc :timed_out true)
      truncated (assoc :truncated true))))

(def shell-tool
  {:name "shell"
   :description (str "Runs a bash command right away on the machine you run on (NixOS), as its user, who can "
                     "use sudo without a password: for what your other tools don't do. Do what the user asks; "
                     "only when a command could lose data, or cut you off, and they didn't clearly ask for that, "
                     "check with them first. Restarting you or the machine, or turning it off, goes through "
                     "power, which waits for your answer to be said. Changes to the system's configuration don't "
                     "last there (it lives elsewhere, in a NixOS configuration), so prefer reading to changing, "
                     "and say so.")
   :parameters {:type "object"
                :properties {:command {:type "string" :description "The bash command."}}
                :required ["command"]
                :additionalProperties false}
   :changes? (constantly true)
   :detail :command
   :run shell!})

(def tools
  [volume-tool status-tool shell-tool])
