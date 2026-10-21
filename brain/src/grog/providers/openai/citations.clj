(ns grog.providers.openai.citations
  "OpenAI's web search writes its sources into the answer, like
  \" ([example.com](https://example.com/page?utm_source=openai))\", which
  would be read aloud. `strip` drops them as the text streams in, holding back
  only what could still turn out to be one (and the whitespace before it)."
  (:require [clojure.string :as str]))

(def ^:private citation
  #"^\s*\(\[[^\]\n]{0,200}\]\([^)\s]{0,2000}\)\)")

;; What could still grow into a citation.
(def ^:private citation-start
  #"\s*\((?:\[[^\]\n]{0,200}(?:\](?:\([^)\s]{0,2000}\)?)?)?)?")

(defn ^:private split-trailing-whitespace
  "[s without its trailing whitespace, that whitespace]."
  [s]
  (let [end (count (str/trimr s))]
    [(subs s 0 end) (subs s end)]))

(defn strip
  "Feeds `text` to the filter, which was holding back `pending` (\"\" at
  first). Returns [the text to emit now, what it holds back now]."
  [pending text]
  (loop [s (str pending text)
         out ""]
    (if-let [i (str/index-of s "(")]
      (let [[before ws] (split-trailing-whitespace (subs s 0 i))
            candidate (str ws (subs s i))
            out (str out before)]
        (if-let [found (re-find citation candidate)]
          (recur (subs candidate (count found)) out)
          (if (re-matches citation-start candidate)
            [out candidate]
            (recur (subs s (inc i)) (str out ws "(")))))
      (let [[before ws] (split-trailing-whitespace s)]
        [(str out before) ws]))))

(defn finish
  "What to emit of `pending` once the text is over: an unfinished citation,
  as is (whitespace alone isn't worth it)."
  [pending]
  (if (str/blank? pending) "" pending))
