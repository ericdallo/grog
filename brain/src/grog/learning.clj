(ns grog.learning
  "What grog learns by itself (the `memory.learn` setting): once a
  conversation goes quiet, the model reads it with what grog remembers
  (resources/grog/learning-prompt.md), and says what in it is worth
  remembering for good, and what turned out wrong, which grog remembers or
  forgets (grog.memory). From what the user said only: the answers that
  carried what others wrote (web searches, tools whose answers may, marked
  by grog.assistant) are left out. It never logs what was said, nor what it
  learned."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.events :as events]
            [grog.log :as log]
            [grog.memory :as memory]
            [grog.prompt :as prompt]
            [grog.provider :as provider]
            [grog.usage :as usage]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

;; How long a conversation goes without turns before grog learns from it:
;; half the time conversations are kept (conversationTtlMinutes) when that's
;; shorter, so that it's still there.
(def ^:private quiet-ms (* 2 60 1000))

;; How many things grog can remember, and forget, from a conversation.
(def ^:private max-changes 5)

(defn due
  "The ids of the `conversations` (the store's, grog.conversation) to learn
  from at `now`, sorted: with turns since grog last learned from them
  (`learned`: {id their :updated-at then}), and quiet long enough, as
  conversations are kept for `ttl-ms`."
  [conversations learned now ttl-ms]
  (let [quiet (min quiet-ms (quot ttl-ms 2))]
    (sort (for [[id {:keys [messages updated-at]}] conversations
                :when (and (seq messages)
                           (not= updated-at (get learned id))
                           (>= (- now updated-at) quiet))]
            id))))

(defn transcript
  "The conversation in `messages` as the model reads it, grog being `name`:
  a line per message, without the answers that carried what others wrote."
  [messages name]
  (str/join "\n" (for [{:keys [role content read-others?]} messages]
                   (if (= "user" role)
                     (str "User: " content)
                     (str name ": " (if read-others? "(an answer with what it found, left out)" content))))))

(defn instructions
  "What the model is told, at instant `now` (a ZonedDateTime), knowing what
  grog remembers (`facts`)."
  [config now facts]
  (str/trim (selmer-util/without-escaping
             (selmer/render (slurp (io/resource "grog/learning-prompt.md"))
                            (assoc (prompt/variables config now {:memory facts})
                                   :room (max 0 (- memory/max-facts (count facts)))
                                   :max_fact_chars memory/max-fact-chars
                                   :max_changes max-changes)))))

(defn ^:private changes-in [items]
  (->> (if (sequential? items) items [])
       (filter string?)
       (map str/trim)
       (remove str/blank?)
       (take max-changes)
       vec))

(defn parse
  "What the model's answer `text` says to remember and to forget, at most
  `max-changes` of each: {:remember :forget}, or nil when it can't be read."
  [text]
  (let [text (or text "")
        start (str/index-of text "{")
        end (str/last-index-of text "}")]
    (when (and start end (< start end))
      (let [answer (try (json/parse-string (subs text start (inc end)) true)
                        (catch Exception _ nil))]
        (when (map? answer)
          {:remember (changes-in (:remember answer))
           :forget (changes-in (:forget answer))})))))

(defn ^:private without-web-search [config]
  (assoc-in config [:providers (keyword (:provider config)) :web-search :enable] false))

(defn ^:private learn-from!
  "Asks the model what in conversation `id` (its `messages`) is worth
  remembering, and remembers it, counting what that used as turns do:
  without searching the web or tools, a single request, holding up nothing."
  [ctx config id messages]
  (let [started (System/nanoTime)
        {:keys [text usage quota]} (provider/stream-reply!
                                    (without-web-search config)
                                    {:system (instructions config (prompt/now config) @(:memory ctx))
                                     :messages [{:role "user" :content (transcript messages (:name config))}]}
                                    (fn [_]))
        changes (parse text)]
    (usage/record! ctx usage)
    (usage/update-quota! ctx quota)
    (usage/publish! ctx)
    (let [{:keys [remembered forgot]} (when changes (memory/learn! ctx changes))]
      (log/info (format "learning conversation=%s %s ms=%d usage=%s"
                        id
                        (if changes (format "remembered=%d forgot=%d" remembered forgot) "answer=unreadable")
                        (quot (- (System/nanoTime) started) 1000000)
                        (pr-str usage))))))

(defn learn!
  "Learns from the conversations quiet since their last turns, at `now` (see
  `due`), `learned` being an atom of when it last learned from each: grog's
  server runs it every so often. Nothing while the `memory.learn` setting is
  off, or someone is talking to grog."
  [ctx learned now]
  (let [config @(:config ctx)
        conversations @(:store ctx)]
    (swap! learned select-keys (keys conversations))
    (when (and (get-in config [:memory :learn])
               (not (events/busy? (events/snapshot (:events ctx)) now)))
      (doseq [id (due conversations @learned now (* 60 1000 (:conversation-ttl-minutes config)))
              :let [{:keys [messages updated-at]} (get conversations id)]]
        ;; Once per turn, even when it fails: rather than asking again and
        ;; again.
        (swap! learned assoc id updated-at)
        (try
          (learn-from! ctx config id messages)
          (catch InterruptedException e
            (throw e))
          (catch Exception e
            (log/info (format "learning conversation=%s failed: %s" id (.getSimpleName (class e))))))))))
