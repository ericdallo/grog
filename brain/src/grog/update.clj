(ns grog.update
  "Updating grog, as far as grog goes: the revision it is, how the last
  update goes (the status file `grog updater` writes, see grog.updater), and
  starting one through its hands: the NixOS module's grog-update service,
  which polkit lets them start (services.grog.update). Someone at its skin,
  or `grog update`, asks through `POST /v1/actions/update` (grog.actions),
  then follows how it goes; the model asks through the `update` tool, which
  waits to tell whether there's a new grog."
  (:require [clojure.string :as str]
            [grog.config :as config]
            [grog.events :as events]
            [grog.hands :as hands]
            [grog.log :as log])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)))

(def ^:private unit "grog-update.service")

;; Where the NixOS module has `grog updater` say how it goes: for `grog
;; update`, when grog doesn't answer.
(def default-status-file "/run/grog-update/status.json")

;; How long the `update` tool waits to know whether there's a new grog.
(def ^:private known-timeout-ms 45000)
(def ^:private poll-ms 500)

(def under-way
  "The stages of an update under way (grog.updater)."
  #{"fetching" "building" "waiting" "switching" "verifying"})

(defn revision
  "The revision of grog running, when known (the NixOS module says)."
  []
  (not-empty (System/getenv "GROG_REVISION")))

(defn short-revision
  "`rev`, as short as git shows it, still saying when it's dirty."
  [rev]
  (when rev
    (str (subs rev 0 (min 7 (count rev)))
         (when (and (> (count rev) 7) (str/ends-with? rev "-dirty")) "-dirty"))))

(defn read-status
  "How the last update goes, as `file` says (grog.updater), or nil."
  [file]
  (when file
    (try (config/parse (slurp file))
         (catch Exception _ nil))))

(defn status
  "How the last update goes, with `config`, or nil."
  [config]
  (read-status (get-in config [:update :status])))

(defn available?
  "Whether grog can update itself with `config`: there's the update service
  (its status file), and hands to start it."
  [config]
  (boolean (and (get-in config [:update :status]) (get-in config [:hands :socket]))))

(defn ^:private on-machine! [config command]
  (hands/ask! (get-in config [:hands :socket]) command))

(defn start!
  "Starts an update, unless one is under way: {:started true}, or
  {:started false :update} with how the one under way goes."
  [config]
  (if (zero? (:exit (on-machine! config (str "systemctl is-active --quiet " unit))))
    {:started false :update (status config)}
    (let [{:keys [exit output]} (on-machine! config (format (str "systemctl start --no-block %s 2>&1"
                                                                 " || sudo -n systemctl start --no-block %s 2>&1")
                                                            unit unit))]
      (when-not (zero? exit)
        (throw (ex-info (str "the machine wouldn't start it: " (str/trim (str output))) {:type ::refused})))
      (log/info "update: asked")
      {:started true})))

(defn wait-known!
  "Waits for the update started since `since` (ms) to know whether there's a
  new grog, for a while at most: how it goes then, or nil."
  [config since]
  (let [deadline (+ (events/now-ms) known-timeout-ms)]
    (loop []
      (let [{:keys [stage started-at] :as s} (status config)]
        (cond
          (and stage (>= (or started-at 0) since) (not= "fetching" stage)) s
          (> (events/now-ms) deadline) nil
          :else (do (Thread/sleep poll-ms) (recur)))))))

(defn when-pushed
  "When grog's revision of `ms` was pushed, in grog's time zone."
  [config ms]
  (when ms
    (.format (.atZone (Instant/ofEpochMilli ms) (ZoneId/of (config/timezone config)))
             (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))))

(defn describe
  "What `status` (of an update) tells, for the model."
  [config {:keys [stage from to to-date error]}]
  (let [latest (str (short-revision to) (some->> (when-pushed config to-date) (str ", from ")))]
    (case stage
      "fetching" "It's looking for an update."
      "current" (format "You're already up to date (%s)." latest)
      "done" (format "You were updated, to %s." latest)
      "failed" (str "The update failed: " error)
      (format (str "There's an update (%s; you're %s): it's being built, which takes several minutes, "
                   "then you restart on your own as soon as nobody's talking to you, and play a sound once "
                   "you're back. Should it not work, you stay as you are.")
              latest (or (short-revision from) "another version")))))

(defn ^:private update-now!
  "What the `update` tool does: starts an update, and waits to tell whether
  there's a new grog."
  [ctx]
  (let [config @(:config ctx)
        since (events/now-ms)
        {:keys [started] running :update} (start! config)]
    (if-not started
      (str "An update is already under way. " (describe config running))
      (or (some->> (wait-known! config since) (describe config))
          (str "Looking for an update takes a while: if there's one, it's built, which takes several "
               "minutes, then you restart on your own and play a sound once you're back.")))))

(defn tool
  "The `update` tool, when grog can update itself with `config`, or nil."
  [config]
  (when (available? config)
    {:name "update"
     :description (str "Updates you to the latest version that was pushed, when the user asks: it tells "
                       "whether there's one (in a few seconds). It's built (several minutes), then you restart "
                       "on your own as soon as nobody's talking to you, and play a sound once you're back; "
                       "should it not work, you stay as you are. Say what it found in a few plain words: with "
                       "an update, that it takes a few minutes and they'll hear a sound when you're back. "
                       "Don't talk of idle conversations or restarting later.")
     :parameters {:type "object" :properties {} :additionalProperties false}
     :changes? (constantly true)
     :run (fn [ctx _ _] (update-now! ctx))}))

(defn watch!
  "Tells whoever watches how the last update goes, when it changed since
  what `seen` (an atom) holds."
  [ctx seen]
  (let [s (status @(:config ctx))]
    (when (not= s @seen)
      (reset! seen s)
      (events/publish! (:events ctx) {:type :update :update s}))))
