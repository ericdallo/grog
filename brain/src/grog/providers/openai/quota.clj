(ns grog.providers.openai.quota
  "How much of a ChatGPT subscription's usage limits is used, read the way ECA
  does (`eca.features.quota`): the Codex backend tells it in the headers of
  its answers (x-codex-primary-used-percent...), and at
  backend-api/wham/usage. A snapshot is {:plan :updated-at :windows}, each
  window {:id :label :used-percent :resets-at :window-minutes}, shortest
  first, times in epoch seconds."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.providers.openai.subscription :as subscription])
  (:import (java.time Instant)))

(defn ^:private parse-number [v]
  (cond
    (number? v) (double v)
    (string? v) (parse-double (str/trim v))))

(defn ^:private round1 [x]
  (/ (Math/round (* 10.0 (double x))) 10.0))

(defn ^:private epoch-seconds
  "From epoch seconds or milliseconds, as a number or a string, or ISO-8601."
  [v]
  (cond
    (number? v) (let [n (long v)] (if (> n 100000000000) (quot n 1000) n))
    (string? v) (or (some-> (parse-long (str/trim v)) epoch-seconds)
                    (try (.getEpochSecond (Instant/parse v)) (catch Exception _ nil)))))

(defn ^:private minutes->label [minutes]
  (when (and minutes (pos? minutes))
    (cond
      (zero? (mod minutes 1440)) (str (quot minutes 1440) "d")
      (zero? (mod minutes 60)) (str (quot minutes 60) "h")
      :else (str minutes "m"))))

(defn ^:private sort-windows [windows]
  (vec (sort-by (juxt #(or (:window-minutes %) Long/MAX_VALUE) :id) windows)))

(defn ^:private window [id label used minutes resets-at]
  (cond-> {:id id :label label :used-percent (round1 used)}
    resets-at (assoc :resets-at resets-at)
    minutes (assoc :window-minutes minutes)))

(defn ^:private lower-case-headers
  "Header name -> its first value."
  [headers]
  (into {} (for [[k v] headers
                 :let [v (if (sequential? v) (first v) v)]
                 :when (string? v)]
             [(str/lower-case (name k)) v])))

(defn from-headers
  "The snapshot in the headers of a Codex answer, or nil. Limits other than
  Codex's have their own `x-<family>-` prefix."
  [headers now-s]
  (let [headers (lower-case-headers headers)
        windows (keep (fn [[k v]]
                        (when-let [[_ family slot] (re-matches #"x-([a-z0-9-]+)-(primary|secondary)-used-percent" k)]
                          (when-let [used (parse-number v)]
                            (let [prefix (str "x-" family "-" slot "-")
                                  minutes (some-> (get headers (str prefix "window-minutes")) parse-number long)
                                  resets-at (or (some-> (get headers (str prefix "reset-at")) parse-number long)
                                                (some->> (get headers (str prefix "reset-after-seconds"))
                                                         parse-number long (+ now-s)))
                                  codex? (= "codex" family)
                                  label (or (minutes->label minutes) slot)]
                              (window (if codex? slot (str family "-" slot))
                                      (if codex? label (str family " " label))
                                      used minutes resets-at)))))
                      headers)]
    (when (seq windows)
      {:updated-at now-s :windows (sort-windows windows)})))

(defn from-usage
  "The snapshot in backend-api/wham/usage's answer, or nil. Its windows get
  the ids of the headers'."
  [body now-s]
  (let [windows (keep (fn [[id k]]
                        (when-let [{:keys [used_percent limit_window_seconds reset_at reset_after_seconds]}
                                   (get-in body [:rate_limit k])]
                          (when-let [used (parse-number used_percent)]
                            (let [minutes (some-> limit_window_seconds parse-number long (quot 60))]
                              (window id (or (minutes->label minutes) id) used minutes
                                      (or (epoch-seconds reset_at)
                                          (some->> reset_after_seconds parse-number long (+ now-s))))))))
                      [["primary" :primary_window] ["secondary" :secondary_window]])]
    (when (seq windows)
      (cond-> {:updated-at now-s :windows (sort-windows windows)}
        (string? (:plan_type body)) (assoc :plan (:plan_type body))))))

(defn usage-url
  "Where the subscription tells its usage: next to the Codex backend."
  [cfg]
  (str/replace (get-in cfg [:subscription :base-url]) #"/codex/?$" "/wham/usage"))

(defn fetch!
  "The subscription's snapshot, from backend-api/wham/usage."
  [cfg credentials now-s]
  (let [resp (http/get (usage-url cfg) {:headers (subscription/headers credentials (str (random-uuid)) false)
                                        :throw false
                                        :timeout 10000})]
    (if (= 200 (:status resp))
      (from-usage (json/parse-string (:body resp) true) now-s)
      (throw (ex-info (format "The ChatGPT usage endpoint returned %d." (:status resp))
                      {:type ::usage-error :status (:status resp)})))))
