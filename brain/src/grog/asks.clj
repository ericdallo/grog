(ns grog.asks
  "Asking the user before a tool runs (plans/integrations.md, the guards):
  a call whose policy is \"ask\" doesn't run, and the model is told to ask
  the user in a yes-or-no question. When it calls again with the same
  arguments, in a later turn of the same conversation (the user said yes)
  and within `ttl-ms`, it runs. grog's card also asks, when nothing else is
  open there: Allow runs it, answering for the user in that conversation;
  Deny forgets it.

  What's asked is kept in the context's :asks, {[conversation tool
  arguments] {:n :at :allowed? :generation}}, :n the turn it was asked in,
  :generation the card's session. Never the arguments in the logs, nor on
  the card."
  (:require [cheshire.core :as json]
            [clojure.walk :as walk]
            [grog.connect :as connect]
            [grog.log :as log]))

(def ttl-ms
  "How long an ask stays good. Tests redefine it."
  (* 5 60 1000))

(def card-ms
  "How long grog's card asks. Tests redefine it."
  (* 2 60 1000))

(defn ^:private now []
  (System/currentTimeMillis))

(defn canonical
  "`args` as JSON, keys sorted: the same arguments, however the model wrote
  them."
  [args]
  (json/generate-string (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) %) %)
                                       args)))

(defn ^:private live? [ask at]
  (and ask (< (- at (:at ask)) ttl-ms)))

(defn ^:private usable?
  "Whether `ask` lets the call run in `turn`: allowed at grog, or asked in
  an earlier turn, and not too long ago."
  [ask turn at]
  (and (live? ask at) (or (:allowed? ask) (< (:n ask) (:n turn)))))

(defn ^:private prune [asks at]
  (into {} (filter #(live? (val %) at)) asks))

(defn ^:private consume!
  "Takes the ask `k` for `turn`, if it lets the call run: the ask, or nil."
  [ctx k turn]
  (let [at (now)
        [before after] (swap-vals! (:asks ctx) #(let [p (prune % at)]
                                                  (if (usable? (get p k) turn at) (dissoc p k) p)))]
    (when (and (usable? (get before k) turn at) (not (contains? after k)))
      (get before k))))

(defn ^:private answer
  "What's said for the user once they allowed a call at grog."
  [config]
  (str "Yes, go ahead (allowed on " (:name config) "'s screen)."))

(defn ^:private go-ahead!
  "Answers for the user, in `conversation`, that they allowed it."
  [ctx conversation]
  (future
    (try
      ((requiring-resolve 'grog.assistant/reply!) ctx {:id conversation :message (answer @(:config ctx))}
                                                  (fn [_]) (fn [_]))
      (catch Exception e
        (log/info "asks: answering for the user failed:" (.getSimpleName (class e)))))))

(defn ^:private decided!
  "Someone at grog allowed the ask `k` (`allow?`), or not."
  [ctx k generation allow?]
  (if-not allow?
    (do (swap! (:asks ctx) dissoc k)
        (connect/deny! ctx generation))
    (let [[_ after] (swap-vals! (:asks ctx) #(cond-> % (live? (get % k) (now)) (update k assoc :allowed? true :at (now))))]
      (if (contains? after k)
        (do (connect/step! ctx generation {:kind "wait" :note "Going ahead…"})
            (go-ahead! ctx (first k)))
        (connect/fail! ctx generation "it was asked too long ago: ask again")))))

(defn ^:private show!
  "Asks on grog's card too, unless something else is open there."
  [ctx k {:keys [integration title detail]}]
  (try
    (connect/open! ctx
                   {:id "ask"
                    :title integration
                    :heading "Go ahead?"
                    :ways [{:id "ask"}]
                    :start! (fn [ctx {:keys [generation]} _]
                              (swap! (:asks ctx) #(cond-> % (contains? % k) (assoc-in [k :generation] generation)))
                              (connect/confirm! ctx generation
                                                (cond-> {:question (str integration ": " title "?")
                                                         :decide! #(decided! ctx k generation %)}
                                                  detail (assoc :detail detail))))}
                   nil
                   {:unless-busy? true :window-ms card-ms})
    (catch Exception e
      (log/info "asks: not on the card," (name (or (:type (ex-data e)) (.getSimpleName (class e))))))))

(defn message
  "What the model is told of a call that asks first, `title`."
  [title]
  (str "not done yet: " title " asks the user first. Ask them in a short yes-or-no question whether to do it; "
       "if they say yes, call it again with the same arguments."))

(defn ask!
  "Runs (run) when the call of `tool` with `args` in `turn` ({:id :n}) was
  asked before and the user said yes (see the ns); else keeps it, asks on
  grog's card, and returns what to tell the model (`message`). `what`:
  {:integration :title :detail}, the integration's and the tool's titles,
  and what it would do in a few words, for the card."
  [ctx turn {:keys [tool args title] :as what} run]
  (let [k [(:id turn) tool (canonical args)]]
    (if-let [ask (consume! ctx k turn)]
      (do (when-let [generation (:generation ask)]
            (connect/done! ctx generation nil))
          (run))
      (do (swap! (:asks ctx) #(let [p (prune % (now))]
                                (assoc p k (merge (select-keys (get p k) [:generation]) {:n (:n turn) :at (now)}))))
          (show! ctx k what)
          (message title)))))
