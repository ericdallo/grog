(ns grog.player
  "The client of grog's player, grog-music (music/ in the repo), which plays
  in the user's session, on grog's speaker, over its unix socket (the
  `music.socket` setting). A connection per request: one line of JSON in,
  {\"command\", and its arguments}, and one out, {\"ok\": true, ...} or
  {\"ok\": false, \"error\"}, keys in lowerCamelCase.

  Its commands: status (state playing, paused or idle, title, artist,
  album, positionS, durationS, volume, ducked, index, count); play {items
  shuffle}, enqueue {items}, pause, resume, stop, next, previous, volume
  {level or change}, duck {level} and unduck, answering its status; and
  YouTube Music's (youtube-music.search, .play, .library), which take the
  account's tokens as `auth` when it's connected (grog.youtube-music)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config])
  (:import (java.net UnixDomainSocketAddress)
           (java.nio.channels Channels SocketChannel)))

(def default-timeout-ms
  "How long grog waits for its player to answer."
  10000)

(def search-timeout-ms
  "How long grog waits for what searches first (YouTube Music's commands)."
  60000)

(defn socket
  "Where grog's player listens, with `config`, or nil."
  [config]
  (get-in config [:music :socket]))

(defn there?
  "Whether grog has a player with `config` (it may not be running)."
  [config]
  (some? (socket config)))

(defn ^:private timeout-of [command]
  (if (str/includes? (str command) ".") search-timeout-ms default-timeout-ms))

(defn ^:private send-line! [^SocketChannel ch data]
  (let [out (Channels/newOutputStream ch)]
    (.write out (.getBytes (str (config/generate data) "\n") "UTF-8"))
    (.flush out)))

(defn ^:private read-line* [^SocketChannel ch]
  (.readLine (io/reader (Channels/newInputStream ch))))

(defn request!
  "Asks grog's player for `command`, with `args`, waiting up to `timeout-ms`
  (longer for what searches first): what it answers, without its ok.
  Throws ex-info when there's no player, it isn't running, doesn't answer,
  or refuses (saying why)."
  ([config command]
   (request! config command nil))
  ([config command args]
   (request! config command args (timeout-of command)))
  ([config command args timeout-ms]
   (let [path (or (socket config)
                  (throw (ex-info "grog has no player here (music.socket)" {:type ::none})))
         reply (future
                 (try
                   (with-open [ch (SocketChannel/open (UnixDomainSocketAddress/of (str path)))]
                     (send-line! ch (assoc args :command command))
                     (some-> (read-line* ch) config/parse))
                   (catch java.io.IOException e e)
                   (catch Exception _ nil)))
         result (deref reply timeout-ms ::late)]
     (cond
       (= ::late result)
       (do (future-cancel reply)
           (throw (ex-info "grog's player took too long to answer" {:type ::late})))

       (instance? Exception result)
       (throw (ex-info "grog's player isn't running" {:type ::unreachable}))

       (not (map? result))
       (throw (ex-info "grog's player gave no answer" {:type ::no-answer}))

       (not (true? (:ok result)))
       (throw (ex-info (str (or (not-empty (str (:error result))) "grog's player refused it")) {:type ::refused}))

       :else
       (dissoc result :ok)))))
