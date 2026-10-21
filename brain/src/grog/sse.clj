(ns grog.sse
  "Server-sent events: reading the LLM APIs' streams, and writing grog's."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config]))

(defn ^:private payload [data-lines]
  (let [data (str/join "\n" data-lines)]
    (when-not (or (str/blank? data) (= "[DONE]" data))
      (json/parse-string data true))))

(defn ^:private data-value
  "The value of a `data:` line, without the one space after the colon (per
  the spec)."
  [line]
  (let [value (subs line 5)]
    (cond-> value (str/starts-with? value " ") (subs 1))))

(defn events
  "Lazy seq of the JSON payloads (keyword keys) of the events read from `reader`.
  Multi-line data is joined; `event:`/`id:` lines, comments and `[DONE]`
  sentinels are ignored (the APIs repeat the event name in the payload's
  `type`). Each event is handed over as soon as its blank line arrives. Must be
  consumed while the reader is open."
  [reader]
  (letfn [(step [lines data]
            (lazy-seq
             ;; first/rest: destructuring with & would read the next line
             ;; before handing over the event.
             (if-let [s (seq lines)]
               (let [line (first s)
                     more (rest s)]
                 (cond
                   (str/blank? line)
                   (if-let [event (payload data)]
                     (cons event (step more []))
                     (step more []))

                   (str/starts-with? line "data:")
                   (step more (conj data (data-value line)))

                   :else
                   (step more data)))
               (when-let [event (payload data)]
                 (list event)))))]
    (step (lazy-seq (line-seq (io/reader reader))) [])))

(defn message
  "A server-sent event: `data` as JSON, keys in lowerCamelCase."
  [type data]
  (str "event: " type "\n"
       "data: " (config/generate data) "\n\n"))
