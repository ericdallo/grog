(ns grog.weather-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.events :as events]
            [grog.test-helpers :as h]
            [grog.tools :as tools]
            [grog.weather :as weather]))

(use-fixtures :each (fn [f] (weather/forget!) (f) (weather/forget!)))

(def ^:private sao-paulo
  {:name "São Paulo" :latitude -23.547 :longitude -46.636 :country_code "BR" :country "Brazil" :admin1 "São Paulo"})

;; As Open-Meteo answers "Pinheiros": the home's neighborhood fifth.
(def ^:private pinheiros
  [{:name "Pinheiro" :latitude -2.521 :longitude -45.083 :country_code "BR" :country "Brazil" :admin1 "Maranhão"}
   {:name "Pinheiros" :latitude -18.37 :longitude -40.213 :country_code "BR" :country "Brazil"
    :admin1 "Espírito Santo" :admin2 "Pinheiros"}
   {:name "Pinheiros" :latitude 39.662 :longitude -8.859 :country_code "PT" :country "Portugal"
    :admin1 "Leiria District"}
   {:name "Pinheiros" :latitude -23.567 :longitude -46.692 :country_code "BR" :country "Brazil"
    :admin1 "São Paulo" :admin2 "São Paulo" :admin3 "Pinheiros"}])

(def ^:private places
  {"São Paulo" [sao-paulo]
   "Pinheiros" pinheiros
   "Parque Ibirapuera" [{:name "Ibirapuera Park" :latitude -23.587 :longitude -46.659 :country_code "BR"
                         :country "Brazil" :admin1 "São Paulo" :admin2 "São Paulo"}]
   "Ibiúna" [{:name "Ibiúna" :latitude -23.656 :longitude -47.222 :country_code "BR" :country "Brazil"
              :admin1 "São Paulo" :admin2 "Ibiúna"}]
   "Paris" [{:name "Paris" :latitude 48.853 :longitude 2.349 :country_code "FR" :country "France"
             :admin1 "Île-de-France"}]})

(def ^:private forecast
  {:current {:time "2026-10-26T20:30" :temperature_2m 13.9 :apparent_temperature 12.9 :relative_humidity_2m 90
             :precipitation 0.0 :weather_code 1 :wind_speed_10m 11.7 :is_day 0}
   :hourly {:time (mapv #(format "2026-10-26T%02d:00" %) (range 21 24))
            :temperature_2m [13.5 13.2 13.0] :precipitation_probability [10 20 30] :weather_code [2 3 61]}
   :daily {:time ["2026-10-26" "2026-10-27"] :weather_code [51 95]
           :temperature_2m_max [17.5 20.3] :temperature_2m_min [13.4 12.7]
           :precipitation_probability_max [85 2] :precipitation_sum [2.8 0.1]}})

(defn ^:private open-meteo!
  "A fake Open-Meteo: places by name, and the forecast. What it was asked is
  in :asked ([uri params])."
  []
  (let [asked (atom [])
        server (h/start-handler!
                (fn [{:keys [uri query-string]}]
                  (let [params (into {} (for [kv (str/split (or query-string "") #"&")
                                              :let [[k v] (str/split kv #"=" 2)]]
                                          [k (java.net.URLDecoder/decode (or v "") "UTF-8")]))]
                    (swap! asked conj [uri params])
                    (case uri
                      "/v1/search" (h/json-response (if-let [found (places (params "name"))] {:results found} {}))
                      "/v1/forecast" (h/json-response forecast)
                      {:status 404}))))]
    (assoc server :asked asked)))

(defn ^:private settings [url]
  (-> config/defaults
      (assoc :state-dir (h/temp-dir) :location {:city "São Paulo" :region "SP" :country "BR"})
      (assoc :weather {:enable true :geocoding-url (str url "/v1/search") :forecast-url (str url "/v1/forecast")})))

(deftest finds-the-place-meant
  (testing "the nearest home, of those by that name"
    (is (= -23.567 (:latitude (weather/pick pinheiros [] sao-paulo)))))
  (testing "or the one the rest of what was said names"
    (is (= -18.37 (:latitude (weather/pick pinheiros ["espirito santo"] sao-paulo))))
    (is (= 39.662 (:latitude (weather/pick pinheiros ["portugal"] sao-paulo)))))
  (testing "or the first, when none is near home"
    (is (= -2.521 (:latitude (weather/pick pinheiros [] {:latitude 48.85 :longitude 2.35})))))
  (testing "nothing found"
    (is (nil? (weather/pick [] [] sao-paulo))))
  (testing "named with its city, and its country when abroad"
    (is (= "Pinheiros, São Paulo" (weather/label (last pinheiros) "BR")))
    (is (= "São Paulo" (weather/label sao-paulo "BR")))
    (is (= "Ibiúna, São Paulo" (weather/label (first (places "Ibiúna")) "BR")))
    (is (= "Paris, Île-de-France, France" (weather/label (first (places "Paris")) "BR"))))
  (testing "how far"
    (is (< 6 (weather/distance-km sao-paulo (last pinheiros)) 7))))

(deftest the-weather
  (let [fake (open-meteo!)
        config (settings (:url fake))]
    (try
      (testing "at home, when nothing's said"
        (let [{:keys [place now next-hours days units]} (weather/forecast! config nil nil)]
          (is (= "São Paulo" place))
          (is (= {:temperature 13.9 :feels-like 12.9 :humidity 90 :precipitation 0.0 :wind 11.7
                  :condition "mainly clear" :icon "moon" :day false}
                 now))
          (is (= [{:time "21:00" :temperature 13.5 :rain-chance 10 :condition "partly cloudy" :icon "cloud-sun"}]
                 next-hours)
              "every 3 hours")
          (is (= [{:date "2026-10-26" :weekday "Monday" :min 13.4 :max 17.5 :rain-chance 85 :rain 2.8
                   :condition "light drizzle" :icon "rain"}
                  {:date "2026-10-27" :weekday "Tuesday" :min 12.7 :max 20.3 :rain-chance 2 :rain 0.1
                   :condition "thunderstorm" :icon "storm"}]
                 days))
          (is (= "°C" (:temperature units)))))
      (testing "the home's neighborhood, park and town nearby"
        (is (= "Pinheiros, São Paulo" (:place (weather/forecast! config "Pinheiros" 2))))
        (is (= "Ibirapuera Park, São Paulo" (:place (weather/forecast! config "Parque Ibirapuera" 2))))
        (is (= "Ibiúna, São Paulo" (:place (weather/forecast! config "Ibiúna" 2))))
        (is (= "Pinheiros, Espírito Santo" (:place (weather/forecast! config "Pinheiros, Espírito Santo" 2)))))
      (testing "far away"
        (is (= "Paris, Île-de-France, France" (:place (weather/forecast! config "Paris" 1)))))
      (testing "what Open-Meteo was asked: the place, then its forecast, kept a while"
        (let [n (count @(:asked fake))]
          (weather/forecast! config "Pinheiros" 2)
          (is (= [["/v1/search" "Pinheiros"]] (map (fn [[uri params]] [uri (params "name")]) (drop n @(:asked fake)))))
          (let [[_ params] (last (filter #(= "/v1/forecast" (first %)) @(:asked fake)))]
            (is (= {"latitude" "48.853" "longitude" "2.349" "forecast_days" "1" "timezone" "auto"}
                   (select-keys params ["latitude" "longitude" "forecast_days" "timezone"]))))))
      (testing "a place it can't find, saying so"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no place called Nowhereville was found"
                              (weather/forecast! config "Nowhereville" 1))))
      (testing "the home, unknown"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the home's city isn't known"
                              (weather/forecast! (dissoc config :location) nil 1))))
      (finally ((:stop! fake))))))

(deftest the-weather-unreachable
  (let [config (settings "http://127.0.0.1:1")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"didn't answer: is the internet up\?"
                          (weather/forecast! config "Paris" 1)))))

(deftest the-tool
  (let [fake (open-meteo!)
        ctx (assistant/new-context (settings (:url fake)))]
    (try
      (with-out-str
        (let [{:keys [run parameters] :as tool} (weather/tool @(:config ctx))
              result (run ctx {:place "Pinheiros"} {:id "c" :n 1})]
          (is (= ["place" "days"] (map name (keys (:properties parameters)))))
          (testing "what the model reads: no icons"
            (is (= "Pinheiros, São Paulo" (:place result)))
            (is (not (str/includes? (pr-str result) ":icon"))))
          (testing "and whoever watches sees a card"
            (let [{:keys [place now days at]} (:weather (events/snapshot (:events ctx)))]
              (is (= "Pinheiros, São Paulo" place))
              (is (= {:temperature 13.9 :condition "mainly clear" :icon "moon"} now))
              (is (= {:date "2026-10-26" :weekday "Monday" :min 13.4 :max 17.5 :rain-chance 85 :icon "rain"}
                     (first days)))
              (is (number? at))))
          (testing "among grog's tools, unless off"
            (is (some #{"weather"} (map :name (tools/available @(:config ctx)))))
            (is (not-any? #{"weather"} (map :name (tools/available (assoc-in @(:config ctx) [:weather :enable] false)))))
            (is (= "weather" (:name tool))))))
      (finally ((:stop! fake))))))
