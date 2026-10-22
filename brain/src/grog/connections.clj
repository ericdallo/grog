(ns grog.connections
  "What's connected to grog (grog.integrations): an account, say. Each
  connection has an id, its integration, a name for the screen (the
  account's), when it was added, and how it is (`statuses`), kept in
  integrations.json in the state directory. Its secrets (tokens) go in a file
  of their own, secrets/<id>.json: only grog's user can read either, and
  secrets never go in the settings, the logs, the events or the model's
  context. The computers grog acts on are connections too, kept by
  grog.computers."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [grog.config :as config]
            [grog.log :as log]
            [grog.state :as state]))

(def id-pattern
  "A connection's id: also the name of its secrets' file."
  #"[a-z0-9][a-z0-9-]{0,63}")

(def statuses
  "How a connection can be: connected, or signed out (signing in to it
  stopped working, and it needs signing in again)."
  #{"connected" "signed-out"})

;; Changes, one at a time.
(def ^:private lock (Object.))

(defn file [state-dir]
  (str (fs/path state-dir "integrations.json")))

(defn ^:private secrets-dir [state-dir]
  (str (fs/path state-dir "secrets")))

(defn secrets-file
  "Where the secrets of connection `id` are."
  [state-dir id]
  (when-not (re-matches id-pattern (str id))
    (throw (ex-info (str "a connection can't be called " (pr-str id)) {:type ::bad-id})))
  (str (fs/path (secrets-dir state-dir) (str id ".json"))))

(defn all
  "The connections, by id: [{:id :integration :name :added-at :status}].
  None without a state directory, or when integrations.json can't be read
  (logged, without what's in it)."
  [state-dir]
  (let [f (some-> state-dir file)]
    (if-not (and f (fs/exists? f))
      []
      (try
        (vec (sort-by :id (:connections (config/parse (slurp f)))))
        (catch Exception e
          (log/info (format "ignoring the connections in %s: %s" f (.getSimpleName (class e))))
          [])))))

(defn by-id
  "Connection `id`, or nil."
  [state-dir id]
  (some #(when (= id (:id %)) %) (all state-dir)))

(defn secrets
  "The secrets of connection `id`, or nil. A file that can't be read is
  like none (logged, without what's in it)."
  [state-dir id]
  (let [f (secrets-file state-dir id)]
    (when (fs/exists? f)
      (try
        (json/parse-string (slurp f) true)
        (catch Exception e
          (log/info (format "ignoring the secrets in %s: %s" f (.getSimpleName (class e))))
          nil)))))

(defn ^:private write-all! [state-dir connections]
  (state/write! (file state-dir) (config/generate {:connections (vec (sort-by :id connections))} {:pretty true})))

(defn ^:private write-secrets! [state-dir id secrets]
  (let [dir (secrets-dir state-dir)]
    (when-not (fs/exists? dir)
      (fs/create-dirs dir)
      (fs/set-posix-file-permissions dir "rwx------"))
    (state/write! (secrets-file state-dir id) (json/generate-string secrets {:pretty true}))))

(defn save!
  "Keeps `connection` ({:id :integration :name}) and its `secrets`, in place
  of any with its id, connected: added when it first was."
  [state-dir {:keys [id] :as connection} secrets]
  (locking lock
    (let [before (by-id state-dir id)]
      (write-secrets! state-dir id secrets)
      (write-all! state-dir (conj (vec (remove #(= id (:id %)) (all state-dir)))
                                  (merge (select-keys connection [:id :integration :name])
                                         {:added-at (or (:added-at before) (System/currentTimeMillis))
                                          :status "connected"}))))))

(defn set-status!
  "Says how connection `id` is (one of `statuses`), if it's there."
  [state-dir id status]
  (locking lock
    (when (by-id state-dir id)
      (write-all! state-dir (map #(cond-> % (= id (:id %)) (assoc :status status)) (all state-dir))))))

(defn remove!
  "Forgets connection `id`, and its secrets."
  [state-dir id]
  (locking lock
    (fs/delete-if-exists (secrets-file state-dir id))
    (when (by-id state-dir id)
      (write-all! state-dir (remove #(= id (:id %)) (all state-dir))))))
