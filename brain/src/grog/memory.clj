(ns grog.memory
  "What the user asked grog to remember, through the model's `memory` tool:
  facts, and how they want it to answer, kept in memory.json in the state
  directory, which go in the system prompt."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.log :as log]
            [grog.state :as state]))

;; They all go in every system prompt.
(def max-facts 50)
(def max-fact-chars 300)

(defn file [config]
  (str (fs/path (config/state-dir config) "memory.json")))

(defn load-facts
  "The facts remembered: [] when there are none, or when the file can't be
  read (logged, without what's in it)."
  [config]
  (let [f (file config)]
    (if-not (fs/exists? f)
      []
      (try
        (let [facts (:facts (json/parse-string (slurp f) true))]
          (if (and (sequential? facts) (every? string? facts))
            (vec facts)
            (throw (ex-info "not a list of facts" {}))))
        (catch Exception e
          (log/info (format "ignoring what was remembered in %s: %s" f (.getSimpleName (class e))))
          [])))))

(defn ^:private normalized [s]
  (str/lower-case (str/trim s)))

(defn ^:private remember [facts fact]
  (let [fact (str/trim fact)]
    (cond
      (> (count fact) max-fact-chars)
      (throw (ex-info (format "that's too long to remember: keep it under %d characters" max-fact-chars) {}))

      (some #(= (normalized %) (normalized fact)) facts)
      facts

      (>= (count facts) max-facts)
      (throw (ex-info (format "already remembering %d things: forget some first" max-facts) {}))

      :else
      (conj facts fact))))

(defn ^:private forget
  "`facts` without the one `what` says: as listed, or a part only it has."
  [facts what]
  (let [exact (filter #(= (normalized %) (normalized what)) facts)
        partial (filter #(str/includes? (normalized %) (normalized what)) facts)
        gone (cond
               (seq exact) (set exact)
               (= 1 (count partial)) (set partial)
               (empty? partial) (throw (ex-info "nothing like that is remembered" {}))
               :else (throw (ex-info "more than one remembered thing matches: say which, as listed" {})))]
    (vec (remove gone facts))))

(defn change!
  "Applies the `memory` tool's `args`: forgets, then remembers. Throws
  ex-info saying what's wrong."
  [{:keys [config memory lock]} {what :forget fact :remember}]
  (when (and (str/blank? what) (str/blank? fact))
    (throw (ex-info "say what to remember or what to forget" {})))
  (locking lock
    (let [facts (cond-> @memory
                  (not (str/blank? what)) (forget what)
                  (not (str/blank? fact)) (remember fact))]
      (state/write! (file @config) (json/generate-string {:facts facts} {:pretty true}))
      (reset! memory facts)
      {:remembering (count facts)})))

(def tool
  {:name "memory"
   :description (str "Remembers something for good when the user asks you to: facts (like who lives in the "
                     "home, or that the trash goes out on Tuesdays) and how they want you to answer (like "
                     "more briefly, or calling them by a nickname). Or forgets it. What you remember is "
                     "listed in your instructions.")
   :parameters {:type "object"
                :properties {:remember {:type "string"
                                        :description "What to remember, in a short sentence that makes sense on its own."}
                             :forget {:type "string"
                                      :description "What to forget, as listed."}}
                :additionalProperties false}
   :changes? (constantly true)
   :detail (fn [{:keys [remember forget]}] (or remember (some->> forget (str "forget: "))))
   :run (fn [ctx args _] (change! ctx args))})
