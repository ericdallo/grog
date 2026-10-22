(ns grog.credentials
  "A provider's credentials, from signing in to its subscription
  (grog.signin): the secrets of its connection (grog.connections), whose id
  is the provider's, and whose name is the account's email."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [grog.connections :as connections]
            [grog.log :as log]))

(defn saved
  "The credentials saved for `provider`, or nil: none, or they stopped
  working (`signed-out!`)."
  [state-dir provider]
  (when (and state-dir (= "connected" (:status (connections/by-id state-dir provider))))
    (connections/secrets state-dir provider)))

(defn save!
  "Keeps `credentials` for `provider`, connected."
  [state-dir provider credentials]
  (connections/save! state-dir {:id provider :integration provider :name (:email credentials)} credentials))

(defn remove! [state-dir provider]
  (connections/remove! state-dir provider))

(defn signed-out!
  "Remembers that the credentials of `provider` stopped working (its refresh
  token was refused): it needs signing in again."
  [state-dir provider]
  (connections/set-status! state-dir provider "signed-out"))

(defn migrate!
  "Moves the credentials older grogs kept in auth.json, by provider, to
  their connections."
  [state-dir]
  (let [f (str (fs/path state-dir "auth.json"))]
    (when (fs/exists? f)
      (try
        (doseq [[provider credentials] (json/parse-string (slurp f) true)
                :let [provider (name provider)]
                :when (and (map? credentials)
                           (re-matches connections/id-pattern provider)
                           (nil? (connections/by-id state-dir provider)))]
          (save! state-dir provider credentials))
        (fs/delete f)
        (log/info (str "moved the credentials in " f " to " (connections/file state-dir)))
        (catch Exception e
          (log/info (format "couldn't move the credentials in %s: %s" f (.getSimpleName (class e)))))))))
