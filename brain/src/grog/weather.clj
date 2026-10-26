(ns grog.weather
  "The weather, from Open-Meteo (open-meteo.com: no key, its data CC BY
  4.0): the `weather` tool, quicker than searching the web, and what
  whoever watches grog sees of it (grog.events' :weather, a card on its
  skin). Where: the home (the `location` setting) when nothing's said;
  else the place by that name the rest of what was said names (\"Pinheiros,
  Espírito Santo\"), nearest home when one is within `near-km` (a
  neighborhood, a park), or the first Open-Meteo finds (Paris). Forecasts
  are kept a while (`kept-ms`). Never logged: what was asked."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.events :as events])
  (:import (java.text Normalizer Normalizer$Form)
           (java.time LocalDate)
           (java.time.format TextStyle)
           (java.util Locale)))

(def ^:private conditions
  "WMO weather codes, as Open-Meteo gives them: [in words, its icon]."
  {0 ["clear" "sun"] 1 ["mainly clear" "sun"] 2 ["partly cloudy" "cloud-sun"] 3 ["overcast" "cloud"]
   45 ["fog" "fog"] 48 ["freezing fog" "fog"]
   51 ["light drizzle" "rain"] 53 ["drizzle" "rain"] 55 ["heavy drizzle" "rain"]
   56 ["light freezing drizzle" "rain"] 57 ["freezing drizzle" "rain"]
   61 ["light rain" "rain"] 63 ["rain" "rain"] 65 ["heavy rain" "rain"]
   66 ["light freezing rain" "rain"] 67 ["freezing rain" "rain"]
   71 ["light snow" "snow"] 73 ["snow" "snow"] 75 ["heavy snow" "snow"] 77 ["snow grains" "snow"]
   80 ["light showers" "rain"] 81 ["showers" "rain"] 82 ["violent showers" "rain"]
   85 ["light snow showers" "snow"] 86 ["snow showers" "snow"]
   95 ["thunderstorm" "storm"] 96 ["thunderstorm with hail" "storm"] 99 ["thunderstorm with heavy hail" "storm"]})

;; How far from home a place by the name asked can be to be the one meant,
;; rather than the first Open-Meteo finds: the home's neighborhoods and
;; what's around.
(def near-km 150)

;; How long a place's forecast is kept, to answer again without asking.
(def ^:private kept-ms (* 10 60 1000))

;; How long Open-Meteo has to answer.
(def ^:private timeout-ms 8000)

;; The home's place, found once for each `location` setting: {location place}.
(defonce ^:private homes (atom {}))

;; Forecasts kept: {[latitude longitude days] {:at ms :forecast forecast}}.
(defonce ^:private forecasts (atom {}))

(defn forget!
  "Forgets the places and forecasts kept (tests)."
  []
  (reset! homes {})
  (reset! forecasts {}))

(defn ^:private fold
  "`s` in lower case, without accents or spaces around: to compare names."
  [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      str/lower-case
      str/trim))

(defn distance-km
  "How far apart two places ({:latitude :longitude}) are, in km."
  [a b]
  (let [rad #(Math/toRadians (double %))
        dlat (rad (- (:latitude b) (:latitude a)))
        dlon (rad (- (:longitude b) (:longitude a)))
        h (+ (Math/pow (Math/sin (/ dlat 2)) 2)
             (* (Math/cos (rad (:latitude a))) (Math/cos (rad (:latitude b)))
                (Math/pow (Math/sin (/ dlon 2)) 2)))]
    (* 2 6371.0 (Math/asin (Math/sqrt h)))))

(defn ^:private names?
  "Whether Open-Meteo's `result` is in what `hint` names: its region, its
  city, its country."
  [hint result]
  (some #(and (string? %) (str/includes? (fold %) hint))
        ((juxt :admin1 :admin2 :admin3 :admin4 :country :country_code) result)))

(defn pick
  "Which of Open-Meteo's `results` is the place: of those every one of
  `hints` (folded) names, if any are, else of all, the nearest to `home`
  within near-km, if any is, else the first; nil without any."
  [results hints home]
  (let [named (filter (fn [r] (every? #(names? % r) hints)) results)
        pool (if (seq named) named results)
        near (when home
               (->> pool
                    (map (juxt #(distance-km home %) identity))
                    (filter #(<= (first %) near-km))
                    (sort-by first)
                    first
                    second))]
    (or near (first pool))))

(defn label
  "What to call a place Open-Meteo found: its name, with its city or
  region when it isn't one, and its country when it isn't the home's
  (`home-country`, a code)."
  [{:keys [name admin1 admin2 country country_code]} home-country]
  (let [within (first (remove #(or (str/blank? %) (= (fold %) (fold name))) [admin2 admin1]))
        abroad (when (and (not (str/blank? country))
                          (not= (fold country_code) (fold home-country)))
                 country)]
    (str/join ", " (remove str/blank? [name within abroad]))))

(defn ^:private get-json!
  "Open-Meteo's answer at `url` with `params`, parsed. ex-info saying so
  when it can't."
  [url params]
  (let [resp (try (http/get url {:query-params params :throw false :timeout timeout-ms})
                  (catch Exception _
                    (throw (ex-info "the weather service didn't answer: is the internet up?"
                                    {:type ::unreachable}))))]
    (if (= 200 (:status resp))
      (json/parse-string (:body resp) true)
      (throw (ex-info (str "the weather service answered " (:status resp)) {:type ::failed})))))

(defn ^:private find!
  "The places Open-Meteo finds by `name`, in `country` (a code) when given."
  [config name country]
  (:results (get-json! (get-in config [:weather :geocoding-url])
                       (cond-> {"name" name "count" "10" "language" "en" "format" "json"}
                         country (assoc "countryCode" country)))))

(defn ^:private country-code [location]
  (let [c (some-> (:country location) str/trim str/upper-case)]
    (when (and c (re-matches #"[A-Z]{2}" c)) c)))

(defn home!
  "The home's place, as the `location` setting says ({:city :region
  :country}), found once: nil without a city, or when it isn't found."
  [config]
  (let [{:keys [city region] :as location} (:location config)]
    (when-not (str/blank? city)
      (or (get @homes location)
          (when-let [found (pick (find! config (str/trim city) (country-code location))
                                 (remove str/blank? [(fold region)])
                                 nil)]
            (swap! homes assoc location found)
            found)))))

(defn place!
  "Where `asked` (as the user said it, or blank for the home) is:
  {:label :latitude :longitude}. ex-info saying why when it can't tell."
  [config asked]
  (let [home (try (home! config) (catch clojure.lang.ExceptionInfo e (when (str/blank? asked) (throw e))))
        home-country (or (:country_code home) (country-code (:location config)))]
    (if (str/blank? asked)
      (if home
        (assoc (select-keys home [:latitude :longitude]) :label (label home home-country))
        (throw (ex-info "say where: the home's city isn't known (save it with the settings tool)"
                        {:type ::where})))
      (let [[name & hints] (map str/trim (str/split asked #","))
            found (when-not (str/blank? name)
                    (pick (find! config name nil) (remove str/blank? (map fold hints)) home))]
        (if found
          (assoc (select-keys found [:latitude :longitude]) :label (label found home-country))
          (throw (ex-info (str "no place called " asked " was found: say its city, or its country")
                          {:type ::not-found})))))))

(defn ^:private condition [code]
  (get conditions (when (number? code) (long code)) ["unknown" "cloud"]))

(defn ^:private hour [time]
  (if (and (string? time) (>= (count time) 16)) (subs time 11 16) time))

(defn ^:private weekday [date]
  (try (.getDisplayName (.getDayOfWeek (LocalDate/parse date)) TextStyle/FULL Locale/ENGLISH)
       (catch Exception _ nil)))

(defn summary
  "What there is to say of Open-Meteo's `forecast` at the place called
  `place`: {:place :now :next-hours :days :units}, each with its condition
  in words and the icon whoever watches shows for it (:icon)."
  [place {:keys [current hourly daily]}]
  (let [[now-words now-icon] (condition (:weather_code current))
        day? (not= 0 (:is_day current))]
    {:place place
     :now {:temperature (:temperature_2m current)
           :feels-like (:apparent_temperature current)
           :humidity (:relative_humidity_2m current)
           :precipitation (:precipitation current)
           :wind (:wind_speed_10m current)
           :condition now-words
           :icon (if (and (not day?) (#{"sun" "cloud-sun"} now-icon)) "moon" now-icon)
           :day day?}
     :next-hours (vec (for [i (range 0 (count (:time hourly)) 3)
                            :let [[words icon] (condition (get-in hourly [:weather_code i]))]]
                        {:time (hour (get-in hourly [:time i]))
                         :temperature (get-in hourly [:temperature_2m i])
                         :rain-chance (get-in hourly [:precipitation_probability i])
                         :condition words
                         :icon icon}))
     :days (vec (for [i (range (count (:time daily)))
                      :let [date (get-in daily [:time i])
                            [words icon] (condition (get-in daily [:weather_code i]))]]
                  {:date date
                   :weekday (weekday date)
                   :min (get-in daily [:temperature_2m_min i])
                   :max (get-in daily [:temperature_2m_max i])
                   :rain-chance (get-in daily [:precipitation_probability_max i])
                   :rain (get-in daily [:precipitation_sum i])
                   :condition words
                   :icon icon}))
     :units {:temperature "°C" :wind "km/h" :precipitation "mm"}}))

(defn forecast!
  "The weather at `asked` (see place!), now and for `days` days (1 to 7,
  today first): its summary. A place's forecast is kept kept-ms."
  [config asked days]
  (let [days (max 1 (min 7 (long (or days 3))))
        {:keys [label latitude longitude]} (place! config asked)
        k [latitude longitude days]
        now (System/currentTimeMillis)
        kept (get @forecasts k)
        forecast (if (and kept (< (- now (:at kept)) kept-ms))
                   (:forecast kept)
                   (let [f (get-json! (get-in config [:weather :forecast-url])
                                      {"latitude" (str latitude)
                                       "longitude" (str longitude)
                                       "current" (str "temperature_2m,apparent_temperature,relative_humidity_2m,"
                                                      "precipitation,weather_code,wind_speed_10m,is_day")
                                       "hourly" "temperature_2m,precipitation_probability,weather_code"
                                       "daily" (str "weather_code,temperature_2m_max,temperature_2m_min,"
                                                    "precipitation_probability_max,precipitation_sum")
                                       "timezone" "auto"
                                       "forecast_days" (str days)
                                       "forecast_hours" "12"})]
                     (swap! forecasts assoc k {:at now :forecast f})
                     f))]
    (summary label forecast)))

(defn ^:private for-the-model
  "What the model reads of a summary: its icons are the screen's."
  [summary]
  (-> summary
      (update :now dissoc :icon)
      (update :next-hours #(mapv (fn [h] (dissoc h :icon)) %))
      (update :days #(mapv (fn [d] (dissoc d :icon)) %))))

(defn card
  "What whoever watches grog sees of a summary: where, now, and the days."
  [summary at]
  (-> (select-keys summary [:place :now :days])
      (update :now select-keys [:temperature :condition :icon])
      (update :days #(mapv (fn [d] (select-keys d [:date :weekday :min :max :rain-chance :icon])) %))
      (assoc :at at)))

(defn tool
  "The `weather` tool, unless the `weather.enable` setting is false."
  [config]
  (when (get-in config [:weather :enable])
    {:name "weather"
     :description (str "The weather now, the next hours and the forecast for the next days, at the home or "
                       "at a place the user names (a city, a neighborhood, a park): temperature, rain, wind. "
                       "Quicker than searching the web for it.")
     :parameters {:type "object"
                  :properties {:place {:type "string"
                                       :description (str "Where, as the user said it, with its city or country "
                                                         "after a comma when it helps (\"Pinheiros, São Paulo\"). "
                                                         "Leave it out for the home.")}
                               :days {:type "integer" :minimum 1 :maximum 7
                                      :description "How many days of forecast, today first (3 when left out)."}}
                  :additionalProperties false}
     :run (fn [ctx {:keys [place days]} _turn]
            (let [summary (forecast! @(:config ctx) (when (string? place) place) (when (number? days) days))]
              (events/publish! (:events ctx) {:type :weather :weather (card summary (events/now-ms))})
              (for-the-model summary)))}))
