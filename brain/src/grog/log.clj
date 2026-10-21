(ns grog.log
  "Log lines on stdout: the journal, under systemd. Never log what users say
  or credentials."
  (:require [clojure.string :as str]))

(defn info [& parts]
  (println (str/join " " parts)))
