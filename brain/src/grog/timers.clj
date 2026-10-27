(ns grog.timers
  "Timers, alarms and reminders (the `timers` tool). Each goes off at its
  time: grog-voice plays its alarm sound and says what it was given to say
  (grog.voice/say!), again every `ring-every-ms`, `rings` times at most,
  until someone stops it: by saying grog's name (grog-voice hearing them,
  as grog.events says), touching its face or the stop action (`silence!`).
  Kept in timers.json in the state directory, across restarts: one that
  went off while grog was away goes off once it's back, if that was
  less than `late-ms` ago; older ones are dropped. Whoever watches grog
  sees them (grog.events' :timers), counting down. Never logged: their
  labels, nor what they say."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.events :as events]
            [grog.log :as log]
            [grog.state :as state]
            [grog.voice :as voice])
  (:import (java.text Normalizer Normalizer$Form)
           (java.time Instant LocalDateTime LocalTime ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)))

(def kinds
  "What can be set: after a while, at a time to wake or alert, at a time to
  remind of something."
  ["timer" "alarm" "reminder"])

;; How often one that went off is said again, and how many times at most.
(def ring-every-ms 20000)
(def rings 3)

;; How late, at most, one that went off while grog was away still goes off.
(def late-ms (* 10 60 1000))

;; How many can be set at once, and how far ahead.
(def ^:private most 20)
(def ^:private longest-ms (* 7 24 60 60 1000))

;; The longest label, and what it says.
(def ^:private label-max 60)
(def ^:private say-max 300)

;; Changes, one at a time.
(def ^:private lock (Object.))

(defn file [config]
  (str (fs/path (config/state-dir config) "timers.json")))

(defn ^:private valid? [t]
  (and (map? t) (string? (:id t)) (some #{(:kind t)} kinds) (number? (:at t))))

(defn load-timers
  "The timers kept, as they were last: none when the file can't be read
  (logged, without what's in it)."
  [config]
  (let [f (file config)]
    (if-not (fs/exists? f)
      []
      (try
        (let [ts (config/parse (slurp f))]
          (if (sequential? ts) (filterv valid? ts) []))
        (catch Exception e
          (log/info "ignoring timers.json:" (.getSimpleName (class e)))
          [])))))

(defn ^:private save! [config timers]
  (state/write! (file config) (config/generate (vec timers) {:pretty true})))

(defn view
  "What whoever watches grog sees of `timers`: [{:id :kind :label :at
  :ringing}], soonest first."
  [timers]
  (->> timers
       (map #(-> (select-keys % [:id :kind :label :at])
                 (assoc :ringing (= "ringing" (:status %)))))
       (sort-by :at)
       vec))

(defn ^:private changed!
  "Keeps `timers` as `ctx`'s, for good, and tells whoever watches."
  [{:keys [config events] :as ctx} timers]
  (reset! (:timers ctx) (vec timers))
  (save! @config timers)
  (events/publish! events {:type :timers :timers (view timers)}))

(defn ^:private fold [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      str/lower-case
      str/trim))

(defn ^:private clip [s n]
  (let [s (some-> s str str/trim)]
    (when-not (str/blank? s)
      (if (> (count s) n) (subs s 0 n) s))))

(defn when-at
  "When `at` (\"07:30\", the next time it's that time, or \"2026-10-27T07:30\")
  is, in epoch ms, in time zone `zone`, `now` being epoch ms. nil when it
  isn't either."
  [at zone now]
  (let [zone (ZoneId/of zone)
        today (ZonedDateTime/ofInstant (Instant/ofEpochMilli now) zone)
        s (str/trim (str at))]
    (or (try (let [t (LocalTime/parse s)
                   z (.with today t)
                   z (if (<= (.toEpochMilli (.toInstant z)) now) (.plusDays z 1) z)]
               (.toEpochMilli (.toInstant z)))
             (catch Exception _ nil))
        (try (.toEpochMilli (.toInstant (.atZone (LocalDateTime/parse s) zone)))
             (catch Exception _ nil)))))

(defn ^:private local [ms zone]
  (.format (.atZone (Instant/ofEpochMilli ms) (ZoneId/of zone)) (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm")))

(defn ^:private left
  "How long until `ms`, from `now`, in words: \"4 minutes\"."
  [ms now]
  (let [s (max 0 (quot (- ms now) 1000))
        [n unit] (cond (< s 60) [s "second"] (< s 3600) [(quot (+ s 30) 60) "minute"]
                       (< s 86400) [(quot (+ s 1800) 3600) "hour"] :else [(quot (+ s 43200) 86400) "day"])]
    (str n " " unit (when (not= 1 n) "s"))))

(defn ^:private described
  "What the model reads of timer `t`."
  [t zone now]
  (cond-> {:id (:id t) :kind (:kind t) :at (local (:at t) zone) :in (left (:at t) now)}
    (:label t) (assoc :label (:label t))
    (= "ringing" (:status t)) (assoc :ringing true)))

(defn set-timer!
  "Sets a timer (after `in-seconds`), or an alarm or a reminder (`at`),
  saying `say` when it goes off, shown as `label`: what the model reads of
  it. ex-info saying what's wrong."
  [ctx {:keys [kind in-seconds at label say]} now]
  (let [config @(:config ctx)
        zone (config/timezone config)
        kind (or (some #{kind} kinds) (if in-seconds "timer" "alarm"))
        due (cond
              (number? in-seconds) (when (pos? in-seconds) (+ now (* 1000 (long in-seconds))))
              (string? at) (when-at at zone now)
              :else nil)]
    (cond
      (nil? due) (throw (ex-info "say when: in_seconds, for a timer, or at, for an alarm or a reminder (\"07:30\")"
                                 {:type ::when}))
      (> (- due now) longest-ms) (throw (ex-info "at most a week ahead" {:type ::when}))
      (>= (count @(:timers ctx)) most) (throw (ex-info (str "at most " most " at once") {:type ::full})))
    (let [t (cond-> {:id (subs (str (random-uuid)) 0 8) :kind kind :at due :created-at now :status "pending"}
              (clip label label-max) (assoc :label (clip label label-max))
              (clip say say-max) (assoc :say (clip say say-max)))]
      (locking lock
        (changed! ctx (conj @(:timers ctx) t)))
      {:set (described t zone now)})))

(defn cancel!
  "Cancels the timer called `id`, or else labeled `label`, or else the only
  one: what the model reads of it. ex-info when it can't tell which."
  [ctx {:keys [id label]} now]
  (let [zone (config/timezone @(:config ctx))]
    (locking lock
      (let [timers @(:timers ctx)
            by-label (when-not (str/blank? label) (filter #(= (fold label) (fold (:label %))) timers))
            found (cond
                    (not (str/blank? id)) (filter #(= id (:id %)) timers)
                    (seq by-label) by-label
                    (= 1 (count timers)) timers
                    :else nil)]
        (when (empty? found)
          (throw (ex-info (if (empty? timers) "nothing is set" "say which (its id, from list)") {:type ::which})))
        (changed! ctx (remove (set found) timers))
        {:cancelled (mapv #(described % zone now) found)}))))

(defn listed
  "What the model reads of the timers set."
  [ctx now]
  (let [zone (config/timezone @(:config ctx))]
    {:timers (mapv #(described % zone now) (sort-by :at @(:timers ctx)))}))

(defn ^:private due
  "Timer `t` at `now`: as it was, going off (once more, `:rang-at` now), or
  nil once it's done: said `rings` times, or gone off while grog was away,
  too long ago."
  [{:keys [status at rang rang-at] :as t} now]
  (cond
    (and (= "pending" status) (<= at now))
    (when (<= (- now at) late-ms)
      (assoc t :status "ringing" :rang 1 :rang-at now))

    (and (= "ringing" status) (>= (- now (or rang-at 0)) ring-every-ms))
    (when (< (or rang 0) rings)
      (assoc t :rang (inc (or rang 0)) :rang-at now))

    :else t))

(defn ^:private ring!
  "Timers `ts` go off: grog-voice says what each says (else its label),
  after its sound, once."
  [ctx ts]
  (voice/say! (:voice ctx) (str/join " " (keep #(or (:say %) (:label %)) ts)) "alarm"))

(defn tick!
  "What's due at `now`: those set for then go off, those going off are said
  again or, said `rings` times, dropped; those that went off while grog was
  away, too long ago, are dropped. Those going off together are said
  together."
  [ctx now]
  (locking lock
    (let [timers @(:timers ctx)
          next (into [] (keep #(due % now)) timers)]
      (when-let [ringing (seq (filter #(= now (:rang-at %)) next))]
        (ring! ctx ringing))
      (when (not= timers next)
        (changed! ctx next)))))

(defn silence!
  "Stops those going off: they're done. Whether any was."
  [ctx]
  (locking lock
    (let [timers @(:timers ctx)
          quiet (remove #(= "ringing" (:status %)) timers)]
      (when (not= (count quiet) (count timers))
        (changed! ctx quiet)
        true))))

(defn watch-voice!
  "Stops what goes off once someone talks to grog (grog-voice hearing
  them)."
  [ctx]
  (add-watch (:state (:events ctx)) ::silencing
             (fn [_ _ before after]
               (when (and (= "hearing" (get-in after [:voice :state]))
                          (not= "hearing" (get-in before [:voice :state]))
                          (some #(= "ringing" (:status %)) @(:timers ctx)))
                 (future (silence! ctx))))))

(defn unwatch-voice! [ctx]
  (remove-watch (:state (:events ctx)) ::silencing))

(def tool
  {:name "timers"
   :description (str "Sets timers, alarms and reminders, which go off at their time with a sound and say what you "
                     "give them to (even while no one asks anything), lists them, or cancels them. Someone saying "
                     "your name, or touching your face, stops one going off.")
   :parameters {:type "object"
                :properties {:action {:type "string" :enum ["set" "list" "cancel"]}
                             :kind {:type "string" :enum kinds
                                    :description (str "timer: after a while (in_seconds); alarm: at a time (at), "
                                                      "to wake or alert; reminder: at a time, to remind of "
                                                      "something.")}
                             :in_seconds {:type "integer" :minimum 1
                                          :description "For a timer: how long, in seconds (10 minutes: 600)."}
                             :at {:type "string"
                                  :description (str "For an alarm or a reminder: when, in the home's time: a time "
                                                    "(\"07:30\": today, or tomorrow once past), or a date and "
                                                    "time (\"2026-10-27T07:30\").")}
                             :label {:type "string"
                                     :description (str "What it's for, in a few words, in the user's language "
                                                       "(\"macarrão\"): shown on the screen.")}
                             :say {:type "string"
                                   :description (str "What to say when it goes off, short, in the user's language "
                                                     "(\"O macarrão está pronto!\"); for a reminder, the reminder.")}
                             :id {:type "string"
                                  :description "For cancel: which one (from list); else the one with that label, or the only one."}}
                :required ["action"]
                :additionalProperties false}
   :detail (fn [{:keys [action kind]}] (str/join " " (remove nil? [action (when (= "set" action) kind)])))
   :run (fn [ctx {:keys [action in_seconds] :as args} _turn]
          (let [now (System/currentTimeMillis)]
            (case action
              "set" (set-timer! ctx (assoc args :in-seconds in_seconds) now)
              "list" (listed ctx now)
              "cancel" (cancel! ctx args now)
              (throw (ex-info "action: set, list or cancel" {:type ::action})))))})
