(ns grog.prompt
  "The system prompt: a Selmer template, the built-in one
  (resources/grog/system-prompt.md, which documents the variables) or the
  `systemPrompt`/`systemPromptFile` setting, rendered with the settings and the
  current date and time. The `instructions` setting is appended."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.provider :as provider]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util])
  (:import (java.time ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(def ^:private date-time-format
  (DateTimeFormatter/ofPattern "EEEE, MMMM d, yyyy, HH:mm" Locale/ENGLISH))

(defn now
  "The current date and time in the configured (or the system's) time zone."
  [config]
  (ZonedDateTime/now (ZoneId/of (config/timezone config))))

(defn format-date-time
  "e.g. \"Thursday, October 15, 2026, 15:32\"."
  [^ZonedDateTime t]
  (.format date-time-format t))

(defn template
  "The template in use."
  [{:keys [system-prompt system-prompt-file]}]
  (cond
    (not (str/blank? system-prompt)) system-prompt
    system-prompt-file (slurp system-prompt-file)
    :else (slurp (io/resource "grog/system-prompt.md"))))

(defn ^:private location-str [{:keys [city region country]}]
  (->> [city region country]
       (remove str/blank?)
       (str/join ", ")))

(defn variables
  "What templates can use, at instant `now` (a ZonedDateTime), knowing
  `memory` (what grog remembers, grog.memory)."
  [config ^ZonedDateTime now {:keys [memory]}]
  {:name (:name config)
   :language (:language config)
   :now (format-date-time now)
   :timezone (str (.getZone now))
   :location (not-empty (location-str (:location config)))
   :model (provider/model config)
   :web_search (boolean (get-in (config/provider-config config) [:web-search :enable]))
   :memory (not-empty (vec memory))})

(defn system-prompt
  "The system prompt for `config` at instant `now` (a ZonedDateTime), with
  what grog knows besides (see `variables`)."
  ([config now]
   (system-prompt config now {}))
  ([{:keys [instructions] :as config} now known]
   (let [prompt (str/trim (selmer-util/without-escaping
                           (selmer/render (template config) (variables config now known))))]
     (cond-> prompt
       (not (str/blank? instructions)) (str "\n\n" (str/trim instructions))))))

(defn problems
  "What's wrong with the prompt settings: a template that doesn't render."
  [config]
  (try
    (system-prompt config (now config))
    nil
    (catch Exception e
      [(format "%s: the template doesn't render: %s"
               (if (:system-prompt-file config) "systemPromptFile" "systemPrompt")
               (ex-message e))])))
