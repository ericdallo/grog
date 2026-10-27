(ns grog.assistant
  "A turn of conversation: the history, the system prompt, the tools and the
  provider's answer, or a connected Home Assistant's, when it did what was
  asked by itself (grog.assist). The HTTP API (grog.api) goes through
  `reply!`."
  (:require [clojure.string :as str]
            [grog.actions :as actions]
            [grog.assist :as assist]
            [grog.config :as config]
            [grog.conversation :as conversation]
            [grog.events :as events]
            [grog.integrations :as integrations]
            [grog.log :as log]
            [grog.memory :as memory]
            [grog.prompt :as prompt]
            [grog.provider :as provider]
            [grog.settings :as settings]
            [grog.timers :as timers]
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
  (grog.servers), the calls asked of the user first (grog.asks) and the
  timers set (grog.timers)."
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
     :asks (atom {})
     :timers (atom (timers/load-timers config))}))

(defn publish-status!
  "Tells whoever watches (grog.events) who grog is (its revision too), how
  it's paid, its time zone, how long its screen stays on once nothing
  happens, what can be asked of it, whether its mic is on and the speaker
  it speaks on, what's connected to it, and what it consumed, now."
  [ctx]
  (let [config @(:config ctx)]
    (events/publish! (:events ctx) (merge {:type :status
                                           :name (:name config)
                                           :revision (update/revision)
                                           :timezone (config/timezone config)
                                           :screen-off-minutes (get-in config [:skin :screen-off-minutes])
                                           :screen-off (get-in config [:skin :screen-off])
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

;; What marks an answer cut off.
(def ^:private cut-mark " …")

(defn ^:private squeezed
  "`s` with its whitespace as single spaces, trimmed."
  [s]
  (str/trim (str/replace (or s "") #"\s+" " ")))

(defn cut
  "`messages`, ending with grog's last answer, cut off after `said`, what
  of it was said aloud (whitespace aside), as the user heard it: marked as
  cut off, or dropped when none of it was said. As they are when all of it
  was, or when they don't end with an answer starting with that."
  [messages said]
  (let [{:keys [role content] :as answer} (peek messages)
        whole (squeezed content)
        said (squeezed said)]
    (cond
      (or (not= "assistant" role) (= said whole) (not (str/starts-with? whole said))) messages
      (str/blank? said) (pop messages)
      :else (conj (pop messages) (assoc answer :content (str said cut-mark))))))

(defn failure-reason
  "Why a turn failed, in a word whoever watches grog may see without
  captions, unlike its message, which could quote what was said: offline
  (no network), timeout (the provider took too long), busy (its rate
  limits), login (signed out, or no key), provider (its own failure), or
  failed."
  [^Throwable e]
  (let [{:keys [type status]} (ex-data e)
        kind (when (keyword? type) (name type))]
    (cond
      (= "network-error" kind) "offline"
      (#{"timeout" "stalled"} kind) "timeout"
      (= 429 status) "busy"
      (or (#{"no-credentials" "not-signed-in" "missing-api-key"} kind) (#{401 403} status)) "login"
      (or (and (number? status) (>= status 500)) (#{"stream-error" "truncated"} kind)) "provider"
      :else "failed")))

(defn ^:private cut-short
  "`messages` with what was answered so far (`answered`), marked as cut
  off, when it's anything."
  [messages answered read-others?]
  (cond-> messages
    (not (str/blank? answered)) (conj (cond-> {:role "assistant" :content (str (str/trimr answered) cut-mark)}
                                        read-others? (assoc :read-others? true)))))

(defn reply!
  "Answers `message` within conversation `id`, streaming the text through
  `on-text`, and telling `on-tool` ({:name}) when the model uses a tool.
  `new?` forgets the conversation first, even if answering fails. `said`:
  what was said aloud for it, when it was, as grog-voice heard it.
  `cut-off`: how much of the conversation's last answer was said aloud
  before it was cut off, when it was (see `cut`). `later`: an atom getting
  the actions the model asks for, for the caller to take once the answer
  is out (grog.actions/after-answer!); without it, the model can't.
  `cancel`: a promise delivered once whoever asked went away: the turn
  stops then, what was asked and what was answered so far kept, marked as
  cut off. Returns the full answer; throws when there's none. Whoever
  watches sees the turn go (grog.events), stopped when cancelled."
  [{:keys [store memory turns] :as ctx} {:keys [id message new? said later cut-off cancel]} on-text on-tool]
  (let [lock (turn-lock id)]
    (locking lock
      (when new?
        (conversation/forget! store id))
      (let [n (swap! turns inc)
            publish! #(events/publish! (:events ctx) (assoc % :conversation id :n n))
            used (volatile! [])
            answered (StringBuilder.)
            ;; What the turn keeps once cut off: [messages now-ms opts offered].
            asked (volatile! nil)
            started (System/nanoTime)
            elapsed-ms #(quot (- (System/nanoTime) started) 1000000)]
        (publish! (cond-> {:type :turn :question message} said (assoc :said said)))
        (try
          (let [config @(:config ctx)
                clock (prompt/now config)
                now-ms (System/currentTimeMillis)
                opts {:ttl-ms (* 60 1000 (:conversation-ttl-minutes config))
                      :max-messages (:max-history-messages config)}
                history (conversation/history store id now-ms (:ttl-ms opts))
                heard (cond-> history (some? cut-off) (cut cut-off))
                messages (conj heard {:role "user" :content message})
                offered (tools/available config)
                on-tool (fn [{:keys [name detail] :as tool}]
                          (if (contains? tool :detail)
                            (when-let [detail (tools/short-detail detail)]
                              (publish! {:type :tool-detail :name name :detail detail}))
                            (do (vswap! used conj name)
                                (publish! {:type :tool :name name})
                                (on-tool tool))))
                on-text (fn [text]
                          (.append answered ^String text)
                          (publish! {:type :text :text text})
                          (on-text text))
                turn {:id id :n n :later later :detail! #(on-tool {:name %1 :detail %2})}]
            (when (not= history heard)
              (conversation/save! store id heard now-ms opts))
            (vreset! asked [messages now-ms opts offered])
            (if-let [done (assist/act! ctx message)]
              ;; Home Assistant did it by itself (grog.assist): its answer.
              (do (on-text done)
                  (log/info (format "chat conversation=%s assist ms=%d" id (elapsed-ms)))
                  (conversation/save! store id (conj messages {:role "assistant" :content done}) now-ms opts)
                  (publish! {:type :done :ms (elapsed-ms) :tools []})
                  done)
              (let [{:keys [text stop-reason usage quota]} (provider/stream-reply!
                                                            config
                                                            {:system (prompt/system-prompt
                                                                      config clock {:memory @memory})
                                                           ;; When it is, said just before the question
                                                           ;; and not kept (grog.prompt/time-note).
                                                             :messages (into heard [(prompt/time-note clock) (peek messages)])
                                                             :location (:location config)
                                                             :timezone (config/timezone config)
                                                             :tools (tools/definitions offered)
                                                             :call-tool (tools/runner ctx offered turn)
                                                             :cancel cancel}
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
                text)))
          (catch Throwable e
            (let [cancelled? (boolean (and cancel (realized? cancel)))]
              (when-let [[messages now-ms opts offered] (when cancelled? @asked)]
                (let [searches (count (filter #{"web_search"} @used))]
                  (conversation/save! store id
                                      (cut-short messages (str answered)
                                                 (read-others? {:web-searches searches} offered @used))
                                      now-ms opts)))
              (publish! (cond-> {:type :failed :ms (elapsed-ms) :tools @used :error (or (ex-message e) (str e))
                                 :reason (failure-reason e)}
                          cancelled? (assoc :stopped true)))
              (throw e))))))))
