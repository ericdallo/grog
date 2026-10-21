(ns grog.usage
  "What grog consumed: per day, its turns, tokens, web searches and, with an
  API key, dollars (kept in usage.json in the state directory, only its user
  can read it); and how much of the subscription's usage limits is used
  (asked to the provider, kept in memory). `grog status` and its skin show
  them."
  (:require [babashka.fs :as fs]
            [grog.config :as config]
            [grog.events :as events]
            [grog.log :as log]
            [grog.provider :as provider]
            [grog.state :as state])
  (:import (java.time LocalDate ZoneId)))

(def ^:private kept-days 400)

;; Days in the chart of the skin.
(def ^:private chart-days 30)

(def ^:private zero
  {:turns 0 :input-tokens 0 :cached-tokens 0 :output-tokens 0 :web-searches 0 :usd 0 :unpriced 0})

(defn file [config]
  (str (fs/path (config/state-dir config) "usage.json")))

(defn today
  "Today, where grog is (the `timezone` setting)."
  ^LocalDate [config]
  (LocalDate/now (ZoneId/of (config/timezone config))))

(defn ^:private day-totals
  "A day as read from the file: its known numbers."
  [day]
  (merge zero (into {} (filter (comp number? val)) (select-keys day (keys zero)))))

(defn load-days
  "The totals kept, per day: {\"2026-10-16\" {:turns ...}}. None when there's
  no file, or it can't be read (logged)."
  [config]
  (let [f (file config)]
    (if-not (fs/exists? f)
      {}
      (try
        (into {} (for [{:keys [date] :as day} (:days (config/parse (slurp f)))
                       :when (string? date)]
                   [date (day-totals day)]))
        (catch Exception e
          (log/info (format "ignoring %s: %s" f (.getSimpleName (class e))))
          {})))))

(defn add
  "`day`'s totals, plus a turn's `usage` (see grog.provider). API key turns
  without a price count as unpriced."
  [day {:keys [auth input-tokens cached-tokens output-tokens web-searches usd]}]
  (merge-with + (or day zero) {:turns 1
                               :input-tokens (or input-tokens 0)
                               :cached-tokens (or cached-tokens 0)
                               :output-tokens (or output-tokens 0)
                               :web-searches (or web-searches 0)
                               :usd (or usd 0)
                               :unpriced (if (and (= "api-key" auth) (nil? usd)) 1 0)}))

(defn ^:private prune
  "The `days` since `kept-days` before `today` (ISO dates sort as text)."
  [days ^LocalDate today]
  (let [oldest (str (.minusDays today kept-days))]
    (into {} (filter #(not (neg? (compare (key %) oldest)))) days)))

(defn record!
  "Adds a turn's `usage` to today's totals, kept for good."
  [{:keys [config usage lock]} turn-usage]
  (when turn-usage
    (locking lock
      (let [cfg @config
            today (today cfg)
            days (swap! usage #(prune (update % (str today) add turn-usage) today))]
        (state/write! (file cfg)
                      (config/generate {:days (vec (for [[date day] (sort-by key days)]
                                                     (assoc day :date date)))}))))))

(defn ^:private total [days dates]
  (reduce (fn [sum date] (merge-with + sum (get days date zero))) zero dates))

(defn summary
  "What grog consumed today, this month, and each of the last 30 days, and how
  it pays now (:auth); with a subscription, how much of its limits is used
  (:quota, once known)."
  [{:keys [config usage quota]}]
  (let [cfg @config
        today (today cfg)
        days @usage
        dates (fn [n] (for [i (range (dec n) -1 -1)] (str (.minusDays today i))))
        auth (:auth (provider/describe-quietly cfg))]
    (cond-> {:auth auth
             :today (get days (str today) zero)
             :month (total days (dates (.getDayOfMonth today)))
             :days (vec (for [date (dates chart-days)]
                          (assoc (select-keys (get days date zero) [:turns :usd]) :date date)))}
      (and (= "subscription" auth) @quota) (assoc :quota @quota))))

(defn ^:private merge-quota
  "`new` over `old`, window by window."
  [old new]
  (let [by-id #(into {} (map (juxt :id identity)) %)]
    (merge old
           (select-keys new [:plan :updated-at])
           {:windows (vec (sort-by (juxt #(or (:window-minutes %) Long/MAX_VALUE) :id)
                                   (vals (merge (by-id (:windows old)) (by-id (:windows new))))))})))

(defn update-quota!
  "Takes in what the provider told of the subscription's limits, if anything."
  [{:keys [quota]} snapshot]
  (when snapshot
    (swap! quota merge-quota snapshot)))

(defn forget-quota!
  "Forgets what grog knows of the subscription's limits: it signed out, and
  another account has others."
  [{:keys [quota]}]
  (reset! quota nil))

(defn refresh-quota!
  "Asks the provider how much of the subscription's limits is used, unless
  it told less than `max-age-s` ago (answers tell it too). Returns whether
  it told now. A failure is logged once, not every time it fails again
  (:quota-failing? of the context)."
  [{:keys [config quota quota-failing?] :as ctx} max-age-s now-s]
  (when (or (nil? @quota) (>= (- now-s (:updated-at @quota 0)) max-age-s))
    (try
      (let [told? (some? (update-quota! ctx (provider/quota @config)))]
        (reset! quota-failing? false)
        told?)
      (catch Exception e
        (when-not (first (reset-vals! quota-failing? true))
          (log/info "couldn't ask how much of the subscription is used:"
                    (or (ex-message e) (.getSimpleName (class e)))))
        false))))

(defn publish!
  "Tells whoever watches (grog.events) what grog consumed."
  [ctx]
  (events/publish! (:events ctx) {:type :usage :usage (summary ctx)}))

(defn ^:private new-day?
  "Whether what watchers were last told of grog's usage was another day's:
  its \"today\" is over."
  [{:keys [config events]}]
  (not= (str (today @config))
        (:date (peek (get-in (events/snapshot events) [:usage :days])))))

(defn refresh!
  "Tells whoever watches what grog consumed again when they should know
  more: the subscription told how much of its limits is used (see
  `refresh-quota!`), or a new day started."
  [ctx max-age-s now-s]
  (when (or (refresh-quota! ctx max-age-s now-s) (new-day? ctx))
    (publish! ctx)))
