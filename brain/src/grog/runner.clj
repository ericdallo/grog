(ns grog.runner
  "grog's runner: a small server that runs MCP servers installed at run time,
  from npm (with npx) and PyPI (with uvx), apart from grog (`grog mcp
  --socket`, the `integrations.runner` setting). grog's sandbox forbids the
  memory Node's JIT needs, and code fetched at run time shouldn't share a
  process, a user or files with grog's tokens.

  A connection sends one line of JSON, {\"registryType\", \"identifier\",
  \"version\", \"packageArguments\", \"env\"}, and gets one back: {\"ok\":
  true}, or {\"error\"}. Once started, the connection carries the server's
  stdin and stdout, both ways, until either end closes. The runner logs
  which servers start and how they exit, and what they say on stderr until
  they first answer (npm's and uv's errors), never what goes through."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.log :as log])
  (:import (java.io ByteArrayOutputStream InputStream IOException OutputStream)
           (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels ServerSocketChannel SocketChannel)))

(def default-opts
  {:npx "npx"
   :uvx "uvx"
   ;; How long a server has to stop, once asked, before it's made to.
   :grace-ms 5000})

;; How long grog waits for the runner to say whether a server started:
;; installing one can take a while.
(def reply-timeout-ms 30000)

(def ^:private max-request-bytes (* 4 1024 1024))
(def ^:private max-arguments 64)
(def ^:private max-argument-chars 4096)
(def ^:private max-env 64)
(def ^:private max-env-value-chars 16384)
;; What a server says on stderr before it answers, in the log.
(def ^:private max-stderr-lines 20)
(def ^:private max-stderr-line-chars 300)

(def ^:private passed-through
  "The runner's own environment that servers get, when it's set."
  ["PATH" "HOME" "LANG" "TZ" "SSL_CERT_FILE" "NIX_SSL_CERT_FILE" "NODE_EXTRA_CA_CERTS"
   "NIX_LD" "NIX_LD_LIBRARY_PATH" "XDG_CACHE_HOME" "XDG_DATA_HOME" "XDG_STATE_HOME"
   "XDG_CONFIG_HOME" "npm_config_cache" "UV_CACHE_DIR" "UV_TOOL_DIR"
   "UV_PYTHON_INSTALL_DIR" "UV_PYTHON" "UV_PYTHON_DOWNLOADS"])

(def ^:private fixed-env
  {"npm_config_update_notifier" "false"
   "NO_UPDATE_NOTIFIER" "1"})

;; npm reads its settings from the environment whatever their case.
(def ^:private reserved-prefixes ["NPM_CONFIG_" "UV_" "NODE_" "XDG_" "LD_" "NIX_"])

(defn ^:private reserved?
  "Whether the runner sets the variable `name` itself: a request can't."
  [name]
  (let [upper (str/upper-case name)]
    (or (some #(= upper (str/upper-case %)) (concat passed-through (keys fixed-env)))
        (some #(str/starts-with? upper %) reserved-prefixes))))

(def ^:private identifiers
  {"npm" {:pattern #"(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*" :max 214}
   "pypi" {:pattern #"[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?" :max 100}})

;; Versions are pinned: no ranges (1.x), no tags (latest).
(def ^:private version-pattern #"(?!.*(^|\.)[xX](\.|$))v?[0-9][0-9A-Za-z.+_-]{0,62}")

(defn ^:private text? [s max-chars]
  (and (string? s) (<= (count s) max-chars) (not (str/includes? s "\u0000"))))

(defn ^:private arguments-problem [args]
  (when-not (or (nil? args)
                (and (sequential? args)
                     (<= (count args) max-arguments)
                     (every? #(text? % max-argument-chars) args)))
    (format "packageArguments must be at most %d strings of at most %d characters"
            max-arguments max-argument-chars)))

(defn ^:private env-problem [env]
  (cond
    (nil? env) nil

    (not (and (map? env) (<= (count env) max-env)))
    (format "env must be an object of at most %d variables" max-env)

    :else
    (some (fn [[k v]]
            (cond
              (not (and (string? k) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" k)))
              "env names must be letters, digits and underscores"

              (reserved? k)
              (str "env can't set " k ": the runner sets it")

              (not (text? v max-env-value-chars))
              (format "env values must be strings of at most %d characters" max-env-value-chars)))
          env)))

(defn problem
  "What's wrong with `request` (parsed with string keys), or nil."
  [request]
  (if-not (map? request)
    "send a JSON object"
    (let [{:strs [registryType identifier version packageArguments env]} request
          {:keys [pattern max]} (identifiers registryType)]
      (cond
        (nil? pattern)
        "registryType must be npm or pypi"

        (not (and (string? identifier) (<= (count identifier) max) (re-matches pattern identifier)))
        (str "identifier isn't a " registryType " package name")

        (not (and (string? version) (re-matches version-pattern version)))
        "version must be an exact version"

        :else
        (or (arguments-problem packageArguments)
            (env-problem env))))))

(defn command
  "What runs the server `request` asks for, with `programs` ({:npx :uvx})."
  [programs {:strs [registryType identifier version packageArguments]}]
  (into (case registryType
          "npm" [(:npx programs) "--yes" (str identifier "@" version)]
          "pypi" [(:uvx programs) (str identifier "==" version)])
        packageArguments))

(defn child-env
  "A server's whole environment: what it gets of the runner's own
  (`parent`), and what it asks for (`env`)."
  [parent env]
  (merge (select-keys parent passed-through) fixed-env env))

(defn ^:private read-line!
  "Reads a line from `in`, a byte at a time, never past its newline: [its
  first `max-bytes` bytes as text, whether it had more], or nil at the end."
  [^InputStream in max-bytes]
  (let [out (ByteArrayOutputStream.)]
    (loop [more? false]
      (let [b (.read in)]
        (cond
          (and (neg? b) (zero? (.size out)) (not more?)) nil
          (or (neg? b) (= b 10)) [(.toString out "UTF-8") more?]
          (< (.size out) max-bytes) (do (.write out b) (recur more?))
          :else (recur true))))))

(defn ^:private send-line! [^OutputStream out data]
  (.write out (.getBytes (str (json/generate-string data) "\n") "UTF-8"))
  (.flush out))

(defn ^:private alive? [h]
  (.isAlive h))

(defn ^:private wait-for!
  "Waits up to `ms` for all of `handles` to exit: whether they did."
  [handles ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond
        (not-any? alive? handles) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 20) (recur))))))

(defn ^:private stop!
  "Stops a server once grog is done with it: closes its stdin, and after
  `grace-ms` asks it and what it started to stop, then makes them."
  [proc grace-ms]
  (let [^Process process (:proc proc)
        handle (.toHandle process)
        started (vec (iterator-seq (.iterator (.descendants handle))))]
    (try (.close ^OutputStream (:in proc)) (catch IOException _))
    (wait-for! [handle] grace-ms)
    (let [handles (into (conj started handle)
                        (when (alive? handle) (iterator-seq (.iterator (.descendants handle)))))]
      (when (some alive? handles)
        (run! #(.destroy %) (filter alive? handles))
        (when-not (wait-for! handles grace-ms)
          (run! #(.destroyForcibly %) (filter alive? handles)))))))

(defn ^:private pump-stdin!
  "Passes what grog sends on to the server, until grog closes its end: then
  stops the server."
  [^InputStream from proc grace-ms]
  (let [^OutputStream to (:in proc)
        buf (byte-array 8192)]
    (try
      (loop []
        (let [n (.read from buf)]
          (when (pos? n)
            (.write to buf 0 n)
            (.flush to)
            (recur))))
      (catch IOException _))
    (stop! proc grace-ms)))

(defn ^:private pump-stdout!
  "Passes what the server answers on to grog, noting it answered. Once the
  server is done, says how it exited and closes the connection."
  [proc ^SocketChannel ch ^OutputStream to answered label]
  (let [^InputStream from (:out proc)
        buf (byte-array 8192)]
    (try
      (loop []
        (let [n (.read from buf)]
          (when (pos? n)
            (deliver answered true)
            (.write to buf 0 n)
            (.flush to)
            (recur))))
      (catch IOException _))
    (log/info (str label ": exited with " (.waitFor ^Process (:proc proc))))
    (.close ch)))

(defn ^:private log-stderr!
  "Logs the first lines of what the server says on stderr before it answers
  (npm's and uv's errors), and discards the rest: later on, it could tell
  what someone said."
  [proc answered label]
  (let [err (java.io.BufferedInputStream. (:err proc))]
    (try
      (loop [logged 0]
        (when-let [[line] (read-line! err (* 4 max-stderr-line-chars))]
          (if (and (< logged max-stderr-lines) (not (realized? answered)))
            (let [line (str/trimr line)]
              (log/info (str label ": " (subs line 0 (min (count line) max-stderr-line-chars))))
              (recur (inc logged)))
            (recur logged))))
      (catch IOException _))))

(defn ^:private launch!
  "Starts the server `request` asks for, relaying it over `ch` once it says
  so."
  [^SocketChannel ch opts request]
  (let [{:strs [identifier version env]} request
        label (str "mcp " identifier)
        argv (command opts request)
        out (Channels/newOutputStream ch)
        proc (try (p/process {:cmd argv
                              :env (child-env (into {} (System/getenv)) env)
                              :in :pipe :out :pipe :err :pipe})
                  (catch Exception e
                    (log/info (str label ": couldn't start (" (.getName (class e)) ")"))
                    nil))]
    (if-not proc
      (do (send-line! out {:error (str "couldn't start " (first argv))})
          (.close ch))
      (let [answered (promise)]
        (log/info (str label ": started, version " version))
        (send-line! out {:ok true})
        (future (log-stderr! proc answered label))
        (future (pump-stdout! proc ch out answered label))
        (future (pump-stdin! (Channels/newInputStream ch) proc (:grace-ms opts)))))))

(defn ^:private handle!
  "Starts the server a connection asks for, or says why it won't."
  [^SocketChannel ch opts]
  (let [[line more?] (read-line! (Channels/newInputStream ch) max-request-bytes)
        request (when (and line (not more?))
                  (try (json/parse-string line) (catch Exception _ nil)))
        why (cond
              (nil? line) nil
              more? "the request is too long"
              :else (problem request))]
    (cond
      (nil? line) (.close ch)
      why (with-open [ch ch] (send-line! (Channels/newOutputStream ch) {:error why}))
      :else (launch! ch opts request))))

(defn start-server!
  "Listens on the unix socket at `path`, readable and writable by its user
  and group only, running the servers connections ask for, in the
  background. `opts` over `default-opts`. Returns a function that stops
  listening."
  [path opts]
  (let [opts (merge default-opts opts)]
    (fs/delete-if-exists path)
    (let [server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                   (.bind (UnixDomainSocketAddress/of (str path))))]
      (fs/set-posix-file-permissions path "rw-rw----")
      (log/info "mcp: listening on" (str path))
      (future
        (loop []
          (let [ch (try (.accept server)
                        (catch Exception e
                          (when (.isOpen server)
                            (log/info "mcp: accepting failed:" (.getName (class e))))
                          nil))]
            (when ch
              (future
                (try (handle! ch opts)
                     (catch Exception e
                       (.close ch)
                       (log/info "mcp: a request failed:" (.getName (class e)))))))
            (when (.isOpen server)
              (recur)))))
      (fn stop []
        (.close server)
        (fs/delete-if-exists path)))))

(defn serve!
  "`start-server!` with the default programs, and blocks."
  [path]
  (start-server! path {})
  @(promise))

(defn start!
  "Asks the runner at `socket` to start the server `request` asks for (a map
  with the request's camelCase keys). Returns {:in :out :close!}: its stdout
  to read, its stdin to write, and what stops it. Throws ex-info when the
  runner can't be reached, doesn't answer or refuses."
  [socket request]
  (let [^SocketChannel ch (try (SocketChannel/open (UnixDomainSocketAddress/of (str socket)))
                               (catch IOException e
                                 (throw (ex-info (str "the runner can't be reached ("
                                                      (.getSimpleName (class e)) ")")
                                                 {:type ::unreachable}))))
        in (Channels/newInputStream ch)
        out (Channels/newOutputStream ch)
        reply (future
                (try (send-line! out request)
                     (some-> (read-line! in max-request-bytes) first json/parse-string)
                     (catch Exception _ nil)))
        result (deref reply reply-timeout-ms nil)]
    (cond
      (not (map? result))
      (do (.close ch)
          (future-cancel reply)
          (throw (ex-info "the runner gave no answer" {:type ::no-answer})))

      (get result "error")
      (do (.close ch)
          (throw (ex-info (str (get result "error")) {:type ::refused})))

      :else
      {:in in
       :out out
       :close! (fn [] (.close ch))})))
