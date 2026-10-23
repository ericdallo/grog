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

;; With something new, what the model said before using the tool, that it
;; updates, is all it says.
(def ^:private updating
  (str "you restart on your own once it's ready. If you already said you'd update, that's your whole "
       "answer; otherwise, say just that you're updating."))

(defn describe
  "What `status` (of an update) tells the model, and how little to say of
  it."
  [{:keys [stage error]}]
  (case stage
    "fetching" (str "It's looking for an update: if there's one, " updating)
    "current" "There's nothing new: you're up to date. Say just that."
    "done" "You were just updated. Say just that."
    "failed" (str "The update failed: say so in a few words. What went wrong: " error)
    (str "There's an update, under way: " updating)))

(defn ^:private update-now!
  "What the `update` tool does: starts an update, and waits to tell whether
  there's a new grog."
  [ctx]
  (let [config @(:config ctx)
        since (events/now-ms)
        {:keys [started] running :update} (start! config)]
    (if-not started
      (str "An update was already under way. " (describe running))
      (describe (or (wait-known! config since) {:stage "fetching"})))))

(defn tool
  "The `update` tool, when grog can update itself with `config`, or nil."
  [config]
  (when (available? config)
    {:name "update"
     :description (str "Updates you to the latest version that was pushed, when the user asks. Before using "
                       "it, say only that you'll update, in a couple of words (like \"Updating.\"), and nothing "
                       "about how long it takes or what comes next. It tells in a few seconds whether there was "
                       "something new: then you restart on your own once it's ready, and should that not work, "
                       "you stay as you are.")
     :parameters {:type "object" :properties {} :additionalProperties false}
     :run (fn [ctx _ _] (update-now! ctx))}))

(defn watch!
  "Tells whoever watches how the last update goes, when it changed since
  what `seen` (an atom) holds."
  [ctx seen]
  (let [s (status @(:config ctx))]
    (when (not= s @seen)
      (reset! seen s)
      (events/publish! (:events ctx) {:type :update :update s}))))
