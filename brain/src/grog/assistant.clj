(ns grog.assistant
  "A turn of conversation: the history, the system prompt, the tools and the
  provider's answer. The HTTP API (grog.api) goes through `reply!`."
  (:require [clojure.string :as str]
            [grog.actions :as actions]
            [grog.config :as config]
            [grog.conversation :as conversation]
            [grog.events :as events]
            [grog.integrations :as integrations]
            [grog.log :as log]
            [grog.memory :as memory]
            [grog.prompt :as prompt]
            [grog.provider :as provider]
            [grog.settings :as settings]
            [grog.tools :as tools]
            [grog.update :as update]
            [grog.usage :as usage]
            [grog.voice :as voice]))

(defn new-context
  "What turns need: the settings (an atom, so they can change live) with
  those changed through the `settings` tool over `config`, what grog
  remembers, the conversation histories, how many turns there were (each
  turn's number), what grog is up to, for whoever watches (grog.events),
  what it asks of grog-voice (grog.voice), what it consumed (grog.usage),
  how connecting something goes (grog.connect), the MCP servers running
  (grog.servers) and the calls asked of the user first (grog.asks)."
  [config]
  (let [overrides (settings/load-overrides config)
        live-config (atom (settings/apply-overrides config overrides))]
    {:base-config config
     :overrides (atom overrides)
     :config live-config
     :memory (atom (memory/load-facts config))
     ;; Changes to the settings, the memory, the mic and the usage, one at a
     ;; time.
     :lock (Object.)
     :store (conversation/new-store)
     :turns (atom 0)
     :events (events/new-hub #(get-in @live-config [:skin :captions]))
     :voice (voice/new-channel config)
     :usage (atom (usage/load-days config))
     :quota (atom nil)
     :quota-failing? (atom false)
     :connect (atom nil)
     :servers (atom {})
     :asks (atom {})}))

(defn publish-status!
  "Tells whoever watches (grog.events) who grog is (its revision too), how
  it's paid, its time zone, what can be asked of it, whether its mic is on
  and the speaker it speaks on, what's connected to it, and what it
  consumed, now."
  [ctx]
  (let [config @(:config ctx)]
    (events/publish! (:events ctx) (merge {:type :status
                                           :name (:name config)
                                           :revision (update/revision)
                                           :timezone (config/timezone config)
                                           :actions (actions/available config)
                                           :mic (voice/mic (:voice ctx))
                                           :speaker (voice/speaker (:voice ctx))
                                           :integrations (integrations/summary config)}
                                          (select-keys (provider/describe-quietly config) [:provider :model :auth])))
    (usage/publish! ctx)))

;; Turns of the same conversation take turns, so that each one sees the
;; previous one. Striped: a fixed set of locks, whatever the conversations.
(def ^:private turn-locks
  (vec (repeatedly 64 #(Object.))))

(defn ^:private turn-lock [id]
  (turn-locks (mod (hash id) (count turn-locks))))

(defn ^:private read-others?
  "Whether a turn read what others wrote, as its `usage` and the names of
  the tools it `used`, of those `offered`, say: it searched the web, or used
  a tool whose answers may carry someone else's text (:open-world, see
  grog.tools)."
  [usage offered used]
  (let [used (set used)]
    (boolean (or (pos? (or (:web-searches usage) 0))
                 (some #(and (:open-world %) (used (:name %))) offered)))))

(defn reply!
  "Answers `message` within conversation `id`, streaming the text through
  `on-text`, and telling `on-tool` ({:name}) when the model uses a tool.
  `new?` forgets the conversation first, even if answering fails. `said`:
  what was said aloud for it, when it was, as grog-voice heard it. `later`:
  an atom getting the actions the model asks for, for the caller to take
  once the answer is out (grog.actions/after-answer!); without it, the
  model can't. Returns the full answer; throws when there's none. Whoever
  watches sees the turn go (grog.events)."
  [{:keys [store memory turns] :as ctx} {:keys [id message new? said later]} on-text on-tool]
  (let [lock (turn-lock id)]
    (locking lock
      (when new?
        (conversation/forget! store id))
      (let [n (swap! turns inc)
            publish! #(events/publish! (:events ctx) (assoc % :conversation id :n n))
            used (volatile! [])
            started (System/nanoTime)
            elapsed-ms #(quot (- (System/nanoTime) started) 1000000)]
        (publish! (cond-> {:type :turn :question message} said (assoc :said said)))
        (try
          (let [config @(:config ctx)
                now-ms (System/currentTimeMillis)
                opts {:ttl-ms (* 60 1000 (:conversation-ttl-minutes config))
                      :max-messages (:max-history-messages config)}
                messages (conj (conversation/history store id now-ms (:ttl-ms opts))
                               {:role "user" :content message})
                offered (tools/available config)
                on-tool (fn [{:keys [name detail] :as tool}]
                          (if (contains? tool :detail)
                            (when-let [detail (tools/short-detail detail)]
                              (publish! {:type :tool-detail :name name :detail detail}))
                            (do (vswap! used conj name)
                                (publish! {:type :tool :name name})
                                (on-tool tool))))
                on-text (fn [text]
                          (publish! {:type :text :text text})
                          (on-text text))
                turn {:id id :n n :later later :detail! #(on-tool {:name %1 :detail %2})}
                {:keys [text stop-reason usage quota]} (provider/stream-reply!
                                                        config
                                                        {:system (prompt/system-prompt
                                                                  config (prompt/now config) {:memory @memory})
                                                         :messages messages
                                                         :location (:location config)
                                                         :timezone (config/timezone config)
                                                         :tools (tools/definitions offered)
                                                         :call-tool (tools/runner ctx offered turn)}
                                                        on-text
                                                        on-tool)]
            (log/info (format "chat conversation=%s provider=%s ms=%d usage=%s%s"
                              id (:provider config) (elapsed-ms) (pr-str usage)
                              (if (seq @used) (str " tools=" (str/join "," @used)) "")))
            (usage/record! ctx usage)
            (usage/update-quota! ctx quota)
            (usage/publish! ctx)
            (when (str/blank? text)
              (throw (ex-info (format "The model gave no answer (%s)." (or stop-reason "no reason given"))
                              {:type ::no-answer})))
            ;; Marked, so that grog learns nothing from it (grog.learning).
            (conversation/save! store id
                                (conj messages (cond-> {:role "assistant" :content text}
                                                 (read-others? usage offered @used) (assoc :read-others? true)))
                                now-ms opts)
            (publish! {:type :done :ms (elapsed-ms) :tools @used})
            text)
          (catch Throwable e
            (publish! {:type :failed :ms (elapsed-ms) :tools @used :error (or (ex-message e) (str e))})
            (throw e)))))))
