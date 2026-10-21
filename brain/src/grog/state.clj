(ns grog.state
  "Files grog keeps in its state directory (grog.config/state-dir): what it
  was told to change or remember, which only its user can read."
  (:require [babashka.fs :as fs]))

(defn write!
  "Writes `text` to `file` at once (readers never see half of it), readable
  only by grog's user."
  [file text]
  (fs/create-dirs (fs/parent file))
  (let [tmp (str file ".tmp")]
    (spit tmp "")
    (fs/set-posix-file-permissions tmp "rw-------")
    (spit tmp text)
    (fs/move tmp file {:replace-existing true :atomic-move true})))
