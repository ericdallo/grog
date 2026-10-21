(ns grog.credentials
  "Credentials saved by `grog login`, per provider, in auth.json (mode 0600)
  under the state directory."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [grog.log :as log]
            [grog.state :as state]))

(def ^:private lock (Object.))

(defn ^:private file [state-dir]
  (str (fs/path state-dir "auth.json")))

(defn ^:private read-all [state-dir]
  (let [f (some-> state-dir file)]
    (if (and f (fs/exists? f))
      (try
        (json/parse-string (slurp f) true)
        (catch Exception e
          ;; Like signed out: signing in again rewrites it.
          (log/info (format "ignoring %s, which isn't valid JSON: %s" f (ex-message e)))
          {}))
      {})))

(defn ^:private write-all! [state-dir data]
  (state/write! (file state-dir) (json/generate-string data {:pretty true})))

(defn saved
  "The credentials saved for `provider`, or nil."
  [state-dir provider]
  (get (read-all state-dir) (keyword provider)))

(defn save! [state-dir provider credentials]
  (locking lock
    (write-all! state-dir (assoc (read-all state-dir) (keyword provider) credentials))))

(defn remove! [state-dir provider]
  (locking lock
    (write-all! state-dir (dissoc (read-all state-dir) (keyword provider)))))
