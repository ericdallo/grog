(ns grog.conversation
  "In-memory conversation histories, forgotten after a period of inactivity.")

(defn new-store []
  (atom {}))

(defn ^:private expired? [{:keys [updated-at]} now ttl-ms]
  (> (- now updated-at) ttl-ms))

(defn history
  "Messages of conversation `id`, or [] when unknown or expired."
  [store id now ttl-ms]
  (let [conversation (get @store id)]
    (if (and conversation (not (expired? conversation now ttl-ms)))
      (:messages conversation)
      [])))

(defn forget!
  "Drops the history of conversation `id`."
  [store id]
  (swap! store dissoc id))

(defn trim
  "The `max-messages` most recent messages, starting at a user message (the
  APIs expect the conversation to start with the user)."
  [messages max-messages]
  (->> (take-last max-messages messages)
       (drop-while #(not= "user" (:role %)))
       vec))

(defn save!
  "Stores `messages` as the history of `id`, dropping expired conversations."
  [store id messages now {:keys [ttl-ms max-messages]}]
  (swap! store
         (fn [conversations]
           (-> (into {} (remove (fn [[_ c]] (expired? c now ttl-ms))) conversations)
               (assoc id {:messages (trim messages max-messages)
                          :updated-at now})))))
