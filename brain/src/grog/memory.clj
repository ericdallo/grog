(ns grog.memory
  "What grog remembers: facts, and how the user wants it to answer, kept in
  memory.json in the state directory, which go in the system prompt. What
  the user asks it to remember or forget, through the model's `memory`
  tool, and what it learns by itself from conversations (grog.learning)."
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

(defn ^:private save!
  "Keeps `facts` as what's remembered."
  [{:keys [config memory]} facts]
  (state/write! (file @config) (json/generate-string {:facts facts} {:pretty true}))
  (reset! memory facts))

(defn change!
  "Applies the `memory` tool's `args`: forgets, then remembers. Throws
  ex-info saying what's wrong."
  [{:keys [memory lock] :as ctx} {what :forget fact :remember}]
  (when (and (str/blank? what) (str/blank? fact))
    (throw (ex-info "say what to remember or what to forget" {})))
  (locking lock
    (let [facts (cond-> @memory
                  (not (str/blank? what)) (forget what)
                  (not (str/blank? fact)) (remember fact))]
      (save! ctx facts)
      {:remembering (count facts)})))

(defn ^:private each
  "`facts` after `f` (`remember` or `forget`) with each of `items` it takes,
  skipping those it can't: blank, or that it throws for."
  [f facts items]
  (reduce (fn [facts item]
            (if (and (string? item) (not (str/blank? item)))
              (try
                (f facts item)
                (catch clojure.lang.ExceptionInfo _ facts))
              facts))
          facts
          items))

(defn learn!
  "Applies what grog learned by itself (grog.learning), lists of what to
  forget and what to remember: forgets, then remembers, each one that it
  can, skipping the rest (too long, no room, nothing or more than one thing
  matching). Returns how many it remembered and forgot."
  [{:keys [memory lock] :as ctx} {to-forget :forget to-remember :remember}]
  (locking lock
    (let [before @memory
          kept (each forget before to-forget)
          facts (each remember kept to-remember)]
      (when (not= before facts)
        (save! ctx facts))
      {:remembered (- (count facts) (count kept))
       :forgot (- (count before) (count kept))})))

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
   :detail (fn [{:keys [remember forget]}] (or remember (some->> forget (str "forget: "))))
   :run (fn [ctx args _] (change! ctx args))})
