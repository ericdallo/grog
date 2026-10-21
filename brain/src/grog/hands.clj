(ns grog.hands
  "grog's hands: a small server that runs commands on the machine for grog,
  as the user whose session has the mic and the speaker (who can use sudo),
  over a unix socket only grog and that user reach (`grog hands --socket`,
  the `hands.socket` setting). grog runs sandboxed, unable to change anything
  on the machine: its tools that do (grog.machine) go through here. The
  hands log how commands went, never the commands or what they printed.

  A connection sends one line of JSON, {\"command\", \"timeoutMs\"}
  (optional), and gets one back: {\"exit\", \"output\", \"timedOut\",
  \"truncated\"}, or {\"error\"}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [grog.config :as config]
            [grog.log :as log])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels ServerSocketChannel SocketChannel)))

;; What a command can print for the model (stdout and stderr together).
(def max-output-chars 8000)
(def default-timeout-ms 30000)
;; grog waits for commands: it doesn't ask for longer ones.
(def max-timeout-ms (* 10 60 1000))

;; How often the output of a running command is looked at.
(def ^:private poll-ms 10)

(defn ^:private read-output
  "What `proc` prints until it exits: [its first `max-output-chars`
  characters, whether there was more]. It never waits on the stream, which
  what the command left running in the background can keep open: once the
  command exited, all it printed is there to read."
  [proc]
  (let [^Process process (:proc proc)
        ;; A character takes at most 4 bytes.
        limit (* 4 max-output-chars)
        out (ByteArrayOutputStream.)
        buf (byte-array 4096)]
    (with-open [^InputStream in (:out proc)]
      (loop [dropped? false]
        (let [alive? (.isAlive process)
              available (min (.available in) (alength buf))]
          (cond
            (pos? available)
            (let [n (.read in buf 0 available)
                  kept (max 0 (min n (- limit (.size out))))]
              (.write out buf 0 kept)
              (recur (or dropped? (< kept n))))

            alive?
            (do (Thread/sleep poll-ms)
                (recur dropped?))

            :else
            (let [text (.toString out "UTF-8")]
              [(subs text 0 (min (count text) max-output-chars))
               (or dropped? (> (count text) max-output-chars))])))))))

(defn run-command
  "Runs `command` with bash, without input, as this process's user, until
  it exits: what it leaves running in the background goes on without the
  hands. Past `timeout-ms`, kills it and whatever it started. Returns {:exit
  :output :timed-out :truncated}."
  [command timeout-ms]
  (let [proc (p/process {:cmd ["bash" "-c" command] :err :out :in ""})
        output (future (read-output proc))
        done (deref proc timeout-ms nil)]
    (when-not done
      (p/destroy-tree proc))
    (let [[text truncated?] @output]
      {:exit (:exit @proc)
       :output text
       :timed-out (nil? done)
       :truncated truncated?})))

(defn ^:private send-line! [^SocketChannel ch data]
  (let [out (Channels/newOutputStream ch)]
    (.write out (.getBytes (str (config/generate data) "\n") "UTF-8"))
    (.flush out)))

(defn ^:private read-line* [^SocketChannel ch]
  (.readLine (io/reader (Channels/newInputStream ch))))

(defn ^:private timeout
  "The `timeoutMs` a request asks for: the default when it doesn't say, nil
  when it makes no sense."
  [v]
  (cond
    (nil? v) default-timeout-ms
    (and (pos-int? v) (<= v max-timeout-ms)) v))

(defn ^:private handle!
  "Runs the command a connection asks for, answering how it went."
  [^SocketChannel ch]
  (with-open [ch ch]
    (let [started (System/nanoTime)
          request (try (config/parse (read-line* ch))
                       (catch Exception _ nil))
          {:keys [command]} (when (map? request) request)
          timeout-ms (timeout (:timeout-ms request))]
      (cond
        (not (string? command))
        (send-line! ch {:error "send {\"command\": \"...\"}"})

        (nil? timeout-ms)
        (send-line! ch {:error (format "timeoutMs must be a whole number of milliseconds, from 1 to %d"
                                       max-timeout-ms)})

        :else
        (let [{:keys [exit timed-out] :as result} (run-command command timeout-ms)]
          (log/info (format "hands: ran a command: %s in %d ms"
                            (if timed-out "timed out" (str "exit " exit))
                            (quot (- (System/nanoTime) started) 1000000)))
          (send-line! ch result))))))

(defn start!
  "Listens on the unix socket at `path`, readable and writable by its user
  and group only, running each connection's command, in the background.
  Returns a function that stops it."
  [path]
  (fs/delete-if-exists path)
  (let [server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of (str path))))]
    (fs/set-posix-file-permissions path "rw-rw----")
    (log/info "hands: listening on" (str path))
    (future
      (loop []
        (let [ch (try (.accept server)
                      (catch Exception e
                        (when (.isOpen server)
                          (log/info "hands: accepting failed:" (.getName (class e))))
                        nil))]
          (when ch
            (future
              (try (handle! ch)
                   (catch Exception e
                     (log/info "hands: a request failed:" (.getName (class e)))))))
          (when (.isOpen server)
            (recur)))))
    (fn stop []
      (.close server)
      (fs/delete-if-exists path))))

(defn serve!
  "`start!`, and blocks."
  [path]
  (start! path)
  @(promise))

(defn ask!
  "Asks the hands at `socket` to run `command`, waiting up to `timeout-ms` for
  it (and a bit more for them): {:exit :output :timed-out :truncated}. Throws
  ex-info when they can't be reached, or don't answer."
  ([socket command]
   (ask! socket command default-timeout-ms))
  ([socket command timeout-ms]
   (let [reply (future
                 (try
                   (with-open [ch (SocketChannel/open (UnixDomainSocketAddress/of (str socket)))]
                     (send-line! ch {:command command :timeout-ms timeout-ms})
                     (some-> (read-line* ch) config/parse))
                   (catch java.io.IOException e e)))
         result (deref reply (+ timeout-ms 5000) ::late)]
     (cond
       (= ::late result)
       (do (future-cancel reply)
           (throw (ex-info "the machine took too long to answer" {:type ::late})))

       (instance? Exception result)
       (throw (ex-info (str "the machine can't be reached right now ("
                            (.getSimpleName (class result)) ")")
                       {:type ::unreachable}))

       (not (map? result))
       (throw (ex-info "the machine gave no answer" {:type ::no-answer}))

       (:error result)
       (throw (ex-info (str "the machine refused it: " (:error result)) {:type ::refused}))

       :else
       result))))
