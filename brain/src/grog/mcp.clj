(ns grog.mcp
  "grog as an MCP client (plans/integrations.md): listing a server's tools
  and calling them, over stdio (a process grog started, or the runner's)
  or Streamable HTTP. It speaks MCP 2026-07-28, stateless, where every
  request says who grog is and what it can do in its `_meta`, and falls
  back to the handshake of the revisions before (2025-11-25, and servers
  answering 2025-06-18 or 2025-03-26) for servers that don't know it.

  Messages are JSON-RPC 2.0, read with keyword keys and written back as
  they came: MCP's names (inputSchema, nextCursor...) stay as they are, and
  tool schemas reach the model untouched. Servers ask for what grog can't
  know itself through elicitation (`:on-elicit`): in 2026 as input a tool
  call needs before it goes on, which grog gets and sends with the call
  again; before, as requests of their own while a call runs.

  Errors are ex-info whose data has a :type: ::unauthorized and ::forbidden
  (with the server's :challenge, see `parse-challenge`), ::unreachable,
  ::timeout, ::closed (the connection ended), ::unsupported-version and
  ::failed (the server answered with an error, :code and :data, or with
  something that isn't MCP, :status). It never logs what tools are given
  or answer."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.log :as log]
            [grog.sse :as sse])
  (:import (java.io BufferedReader IOException InputStream Writer)
           (java.net ConnectException UnknownHostException)
           (java.net.http HttpTimeoutException)
           (java.util Base64)
           (java.util.concurrent ExecutionException)
           (java.util.concurrent.atomic AtomicLong)))

(def protocol-version
  "The MCP revision grog speaks."
  "2026-07-28")

(def legacy-versions
  "The revisions before, which grog speaks to servers that don't know
  `protocol-version`, the one it asks for first."
  ["2025-11-25" "2025-06-18" "2025-03-26"])

(def ^:dynamic *detect-timeout-ms*
  "How long a stdio server has to answer server/discover before grog takes
  it for one of an older revision. Tests shorten it."
  5000)

(def default-timeout-ms 30000)
(def call-timeout-ms 60000)

;; What a tool list may take: pages, tools.
(def ^:private max-pages 20)
(def ^:private max-tools 1000)

;; How many times a tool call may ask for input before grog gives up.
(def ^:private max-input-rounds 8)

;; How long tools listed over HTTP by an older server are kept.
(def ^:private legacy-http-ttl-ms 60000)

;; The errors only servers of `protocol-version` answer.
(def ^:private unsupported-version-code -32022)
(def ^:private header-mismatch-code -32020)
(def ^:private missing-capability-code -32021)
(def ^:private new-style-codes #{unsupported-version-code header-mismatch-code missing-capability-code})

(def ^:private method-not-found {:code -32601 :message "Method not found"})

(defn ^:private now []
  (System/currentTimeMillis))

(defn ^:private failure
  ([type message] (failure type message {}))
  ([type message data]
   (ex-info message (assoc data :type type))))

(defn ^:private error-type [e]
  (:type (ex-data e)))

(defn stdio-transport
  "A server speaking over stdio: `in` is what it writes (its stdout), `out`
  what it reads (its stdin), and `close!`, (fn []), ends what carries them
  (stopping the process, say). Its stderr isn't grog's to read here."
  [{:keys [in out close!]}]
  {:kind :stdio :in in :out out :close! close!})

(defn http-transport
  "A server at `url`, over Streamable HTTP. `headers`, (fn []) called for
  each request, gives the headers it needs beyond MCP's own:
  {\"Authorization\" \"Bearer ...\"}, or {}."
  [{:keys [url headers]}]
  {:kind :http :url url :headers headers})

(defn ^:private capabilities [{:keys [on-elicit]}]
  (if on-elicit {:elicitation {:form {} :url {}}} {}))

(defn ^:private new-style? [client]
  (= protocol-version (:version @(:state client))))

(defn ^:private with-meta-params
  "`params` with what every request says in 2026: grog's revision, what it
  can do, who it is."
  [client params]
  (if (new-style? client)
    (assoc params :_meta (merge (:_meta params)
                                {"io.modelcontextprotocol/protocolVersion" protocol-version
                                 "io.modelcontextprotocol/clientCapabilities" (capabilities (:opts client))
                                 "io.modelcontextprotocol/clientInfo" (:client-info (:opts client))}))
    params))

(defn ^:private await!
  "What `ref` holds once it's there, or ::timeout after `timeout-ms`. The
  wait starts again while someone answers the server (`elicit!`): people
  take longer than servers."
  [client ref timeout-ms]
  (loop [deadline (+ (now) timeout-ms)]
    (let [v (deref ref (max 0 (- deadline (now))) ::timeout)]
      (if (and (= ::timeout v) (pos? @(:eliciting client)))
        (recur (+ (now) timeout-ms))
        v))))

(defn ^:private elicit!
  "Asks the user, through the client's handler, what the server asks for:
  its answer, as MCP takes it."
  [client params]
  (if-let [on-elicit (:on-elicit (:opts client))]
    (do (swap! (:eliciting client) inc)
        (try
          (let [{:keys [action content]} (on-elicit params)]
            (cond-> {:action (name action)}
              (some? content) (assoc :content content)))
          (finally
            (swap! (:eliciting client) dec))))
    (throw (failure ::failed "The MCP server asked for input grog can't give."))))

(defn ^:private invalidate-tools! [client]
  (swap! (:state client) #(-> % (dissoc :tools-cache) (update :tools-generation (fnil inc 0)))))

(defn ^:private notified!
  "What a server's notification changes: only that its tools changed."
  [client {:keys [method]}]
  (when (= "notifications/tools/list_changed" method)
    (invalidate-tools! client)
    (when-let [f (:on-tools-changed (:opts client))]
      (try (f)
           (catch Exception e
             (log/info "mcp: on-tools-changed failed:" (.getName (class e))))))))

(defn ^:private answer
  "The response to a request a server of an older revision sent grog."
  [client {:keys [id method params]}]
  (let [respond (fn [k v] {:jsonrpc "2.0" :id id k v})]
    (case method
      "ping" (respond :result {})
      "elicitation/create"
      (if (:on-elicit (:opts client))
        (try (respond :result (elicit! client params))
             (catch Exception e
               (log/info "mcp: answering elicitation failed:" (.getName (class e)))
               (respond :error {:code -32603 :message "The user couldn't be asked."})))
        (respond :error method-not-found))
      (respond :error method-not-found))))

(defn ^:private request-message? [m]
  (and (:method m) (contains? m :id)))

(defn ^:private response-message? [m]
  (and (not (:method m)) (contains? m :id) (or (contains? m :result) (contains? m :error))))

(defn ^:private send-line!
  "Writes `msg` to a stdio server, a line of JSON."
  [client msg]
  (let [^Writer w (:writer client)]
    (try
      (locking w
        (.write w (str (json/generate-string msg) "\n"))
        (.flush w))
      (catch IOException _
        (throw (failure ::closed "The MCP server's connection is closed."))))))

(defn ^:private die!
  "The connection ended: whatever waits for an answer gets ::closed."
  [client]
  (swap! (:state client) assoc :dead? true)
  (doseq [[_ p] @(:pending client)]
    (deliver p ::closed)))

(defn ^:private read-stdio!
  "Reads what a stdio server writes until it ends: answers go to who waits
  for them, requests get answered, notifications noted. Lines that aren't
  JSON aren't MCP, and are skipped."
  [client]
  (let [^BufferedReader reader (io/reader (:in (:transport client)) :encoding "UTF-8")]
    (try
      (loop []
        (when-let [line (.readLine reader)]
          (when-let [m (try (json/parse-string line true)
                            (catch Exception _ nil))]
            (when (map? m)
              (cond
                (request-message? m) (future
                                       (try (send-line! client (answer client m))
                                            (catch Exception _ nil)))
                (:method m) (notified! client m)
                (contains? m :id) (some-> (get @(:pending client) (:id m)) (deliver m)))))
          (recur)))
      (catch Exception _ nil)
      (finally
        (die! client)))))

(defn ^:private stdio-exchange!
  "Sends request `msg` to a stdio server and waits for its response. On a
  timeout, tells the server to stop (unless `cancel?` is false)."
  [client msg timeout-ms cancel?]
  (let [id (:id msg)
        p (promise)]
    (swap! (:pending client) assoc id p)
    (try
      (when (:dead? @(:state client))
        (throw (failure ::closed "The MCP server's connection is closed.")))
      (send-line! client msg)
      (let [v (await! client p timeout-ms)]
        (case v
          ::timeout (do (when cancel?
                          (try (send-line! client {:jsonrpc "2.0"
                                                   :method "notifications/cancelled"
                                                   :params {:requestId id :reason "timed out"}})
                               (catch Exception _ nil)))
                        (throw (failure ::timeout (format "The MCP server didn't answer %s within %d s."
                                                          (:method msg) (quot timeout-ms 1000)))))
          ::closed (throw (failure ::closed "The MCP server's connection closed."))
          v))
      (finally
        (swap! (:pending client) dissoc id)))))

(defn ^:private key-name [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn ^:private header-value
  "How an argument goes in a header, or nil if it doesn't: integers in
  decimal, booleans, and strings as they are when they're plain visible
  ASCII, else in base64."
  [v]
  (cond
    (integer? v) (str v)
    (boolean? v) (str v)
    (string? v) (if (and (re-matches #"[\x21-\x7E](?:[\x20-\x7E]*[\x21-\x7E])?" v)
                         (not (and (str/starts-with? v "=?") (str/ends-with? v "?="))))
                  v
                  (str "=?base64?" (.encodeToString (Base64/getEncoder) (.getBytes ^String v "UTF-8")) "?="))
    :else nil))

(defn ^:private argument-at [arguments path]
  (reduce (fn [m k]
            (when (map? m)
              (let [v (get m k ::none)]
                (if (= ::none v) (get m (key-name k)) v))))
          arguments
          path))

(defn ^:private call-headers
  "The headers of a tools/call over HTTP in 2026: the tool's name, and the
  arguments its schema puts in headers (x-mcp-header)."
  [client {:keys [name arguments]}]
  (into {"Mcp-Name" name}
        (for [[path header] (get-in @(:state client) [:header-params name])
              :let [v (header-value (argument-at arguments path))]
              :when v]
          [(str "Mcp-Param-" header) v])))

(defn ^:private http-headers [client msg]
  (let [{:keys [version session-id]} @(:state client)
        method (:method msg)
        new? (= protocol-version version)]
    (cond-> (merge {"Accept" "application/json, text/event-stream"
                    "Content-Type" "application/json"}
                   (when-let [f (:headers (:transport client))] (f)))
      (and version (not= "2025-03-26" version)) (assoc "MCP-Protocol-Version" version)
      session-id (assoc "Mcp-Session-Id" session-id)
      (and new? method) (assoc "Mcp-Method" method)
      (and new? (= "tools/call" method)) (merge (call-headers client (:params msg))))))

(defn ^:private param-pattern [k]
  (re-pattern (str "(?i)(?:^|[\\s,])" k "\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s,\"]+))")))

(defn ^:private param-value [s k]
  (when-let [[_ quoted token] (re-find (param-pattern k) s)]
    (if quoted (str/replace quoted #"\\(.)" "$1") token)))

(defn parse-challenge
  "What a WWW-Authenticate header asks for with its Bearer challenge:
  {:scheme \"bearer\" :resource-metadata :scope :error :error-description},
  the parameters it has, or nil without one."
  [header]
  (when-let [[_ params] (and (string? header)
                             (re-find #"(?i)(?:^|,)\s*bearer(?:\s+(.*))?$" header))]
    (let [params (or params "")]
      (into {:scheme "bearer"}
            (for [[k param] {:resource-metadata "resource_metadata"
                             :scope "scope"
                             :error "error"
                             :error-description "error_description"}
                  :let [v (param-value params param)]
                  :when v]
              [k v])))))

(defn ^:private transport-failure
  "What failing to reach a server over HTTP means."
  [^Exception e timeout-ms]
  (let [causes (take-while some? (iterate ex-cause e))]
    (cond
      (some #(instance? HttpTimeoutException %) causes)
      (failure ::timeout (format "The MCP server didn't answer within %d s." (quot timeout-ms 1000)))

      (some #(or (instance? ConnectException %) (instance? UnknownHostException %)) causes)
      (failure ::unreachable (str "Couldn't reach the MCP server (" (.getName (class e)) ")."))

      :else
      (failure ::closed (str "The connection to the MCP server broke (" (.getName (class e)) ").")))))

(declare http-post!)

(defn ^:private reply!
  "Sends the response to a server's request."
  [client response]
  (if (= :stdio (:kind (:transport client)))
    (send-line! client response)
    (try (http-post! client response (atom nil) default-timeout-ms)
         (catch Exception e
           (log/info "mcp: answering the server failed:" (.getName (class e)))))))

(defn ^:private read-sse!
  "The response with `id` in a server's SSE stream, answering the requests
  and noting the notifications that come before it."
  [client ^InputStream body id]
  (or (try
        (some (fn [m]
                (when (map? m)
                  (cond
                    (and (response-message? m) (= id (:id m))) m
                    (request-message? m) (do (reply! client (answer client m)) nil)
                    (:method m) (do (notified! client m) nil)
                    :else nil)))
              (sse/events body))
        (catch IOException _ nil))
      (throw (failure ::closed "The MCP server's stream ended before its answer."))))

(defn ^:private refused
  "The ex-info for a 401 or 403."
  [status headers]
  (let [challenge (parse-challenge (get headers "www-authenticate"))]
    (cond
      (= 401 status)
      (failure ::unauthorized "The MCP server wants grog to sign in." {:challenge challenge})

      (= "insufficient_scope" (:error challenge))
      (failure ::forbidden "The MCP server wants grog to ask for more access." {:challenge challenge})

      :else
      (failure ::failed "The MCP server refused grog (403)." {:status status}))))

;; MCP needs nothing of HTTP/2, and asking a plain http:// server for it
;; (an h2c upgrade) only adds ways for its connections to end mid-way.
(def ^:private http-client
  (delay (http/client (assoc http/default-client-opts :version :http1.1))))

(defn ^:private post!
  "The response to POSTing `msg`, or an ex-info saying how reaching the
  server failed. A connection that broke before any answer (one the server
  had closed, say) is tried again once, but not for a tool call, which the
  server may have run."
  [client msg timeout-ms]
  (let [post #(http/post (:url (:transport client))
                         {:client @http-client
                          :headers (http-headers client msg)
                          :body (json/generate-string msg)
                          :as :stream
                          :throw false
                          :timeout timeout-ms})]
    (try (post)
         (catch Exception e
           (let [failure (transport-failure e timeout-ms)]
             (if (and (= ::closed (error-type failure)) (not= "tools/call" (:method msg)))
               (try (post)
                    (catch Exception e
                      (throw (transport-failure e timeout-ms))))
               (throw failure)))))))

(defn ^:private http-post!
  "POSTs `msg` to an HTTP server: its response when it's a request (JSON or
  an SSE stream), nil once it's taken otherwise. Keeps the response's body
  in `stream`, to close it if it takes too long."
  [client msg stream timeout-ms]
  (let [sent-session (:session-id @(:state client))
        request? (request-message? msg)
        resp (post! client msg timeout-ms)
        {:keys [status headers]} resp
        content-type (str/lower-case (get headers "content-type" ""))]
    (reset! stream (:body resp))
    (with-open [^InputStream body (:body resp)]
      (when (= "initialize" (:method msg))
        (when-let [session (get headers "mcp-session-id")]
          (swap! (:state client) assoc :session-id session)))
      (cond
        (#{401 403} status) (throw (refused status headers))
        (and (= 404 status) sent-session) (throw (failure ::session-expired "The MCP session expired."))
        (and (<= 200 status 299) (not request?)) nil
        (and (<= 200 status 299) (str/starts-with? content-type "text/event-stream")) (read-sse! client body (:id msg))
        (str/starts-with? content-type "application/json")
        (let [m (try (json/parse-stream (io/reader body :encoding "UTF-8") true)
                     (catch Exception _ nil))]
          (if (and (map? m) (or (and (= (:id msg) (:id m)) (response-message? m)) (:error m)))
            m
            (throw (failure ::failed (format "The MCP server answered %d without a JSON-RPC response." status)
                            {:status status}))))
        :else (throw (failure ::failed (format "The MCP server answered %d, not MCP." status) {:status status}))))))

(defn ^:private http-exchange!
  "POSTs `msg` and waits for what comes back, closing the response's
  stream on a timeout."
  [client msg timeout-ms]
  (let [stream (atom nil)
        f (future (http-post! client msg stream timeout-ms))
        v (try (await! client f timeout-ms)
               (catch ExecutionException e
                 (throw (or (ex-cause e) e))))]
    (if (= ::timeout v)
      (do (try (some-> ^InputStream @stream .close)
               (catch Exception _ nil))
          (future-cancel f)
          (throw (failure ::timeout (format "The MCP server didn't answer %s within %d s."
                                            (:method msg) (quot timeout-ms 1000)))))
      v)))

(defn ^:private rpc-failure [method {:keys [code message data]}]
  (failure ::failed (format "The MCP server answered %s with an error: %s (%s)." method message code)
           {:code code :data data}))

(declare initialize!)

(defn ^:private rpc!
  "Sends request `method` with `params`: its result, or an ex-info. Over
  HTTP, a session that expired is started again once."
  ([client method params] (rpc! client method params {}))
  ([client method params {:keys [timeout-ms cancel? retried?] :or {cancel? true}}]
   (when (:closed? @(:state client))
     (throw (failure ::closed "The MCP client is closed.")))
   (let [timeout-ms (or timeout-ms (:timeout-ms (:opts client)))
         msg {:jsonrpc "2.0"
              :id (.incrementAndGet ^AtomicLong (:ids client))
              :method method
              :params (with-meta-params client params)}
         response (if (= :stdio (:kind (:transport client)))
                    (stdio-exchange! client msg timeout-ms cancel?)
                    (try (http-exchange! client msg timeout-ms)
                         (catch clojure.lang.ExceptionInfo e
                           (if (and (= ::session-expired (error-type e)) (not retried?) (not= "initialize" method))
                             (do (swap! (:state client) dissoc :session-id)
                                 (initialize! client)
                                 {::retry true})
                             (throw (if (= ::session-expired (error-type e))
                                      (failure ::closed (ex-message e))
                                      e))))))]
     (cond
       (::retry response) (rpc! client method params {:timeout-ms timeout-ms :cancel? cancel? :retried? true})
       (:error response) (throw (rpc-failure method (:error response)))
       :else (:result response)))))

(defn ^:private notify!
  "Sends notification `method`."
  [client method params]
  (let [msg (cond-> {:jsonrpc "2.0" :method method}
              (some? params) (assoc :params params))]
    (if (= :stdio (:kind (:transport client)))
      (send-line! client msg)
      (http-exchange! client msg (:timeout-ms (:opts client))))))

(defn ^:private initialize!
  "The handshake of the revisions before 2026: grog's capabilities, then
  the server's, then saying it's ready."
  [client]
  (swap! (:state client) assoc :version nil)
  (let [{:keys [opts state]} client
        result (rpc! client "initialize" {:protocolVersion (first legacy-versions)
                                          :capabilities (capabilities opts)
                                          :clientInfo (:client-info opts)})
        version (:protocolVersion result)]
    (when-not (some #{version} legacy-versions)
      (throw (failure ::unsupported-version (str "The MCP server speaks revision " version
                                                 ", which grog doesn't.")
                      {:version version})))
    (swap! state assoc
           :version version
           :server-info (:serverInfo result)
           :instructions (:instructions result)
           :capabilities (:capabilities result))
    (notify! client "notifications/initialized" nil)))

(defn ^:private discovered!
  "What server/discover said, for a server of `protocol-version`."
  [client result]
  (swap! (:state client) assoc
         :version protocol-version
         :server-info (get-in result [:_meta :io.modelcontextprotocol/serverInfo])
         :instructions (:instructions result)
         :capabilities (:capabilities result)))

(defn ^:private unsupported [versions]
  (failure ::unsupported-version (str "The MCP server speaks revisions " (str/join ", " versions)
                                      ", none of which grog does.")
           {:versions versions}))

(defn ^:private speaking!
  "What a server of 2026 that answered `versions` takes: 2026 itself, or
  the handshake before."
  [client versions result]
  (cond
    (or (nil? versions) (some #{protocol-version} versions)) (discovered! client result)
    (some (set legacy-versions) versions) (initialize! client)
    :else (throw (unsupported versions))))

(defn ^:private discover
  "Asks the server what it is: {:result}, {:error} for an error only a
  server of 2026 gives, or {:legacy true} when it's of an older revision."
  [client]
  (swap! (:state client) assoc :version protocol-version)
  (let [stdio? (= :stdio (:kind (:transport client)))]
    (try
      {:result (rpc! client "server/discover" {}
                     (cond-> {:cancel? false}
                       stdio? (assoc :timeout-ms *detect-timeout-ms*)))}
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [type code] :as data} (ex-data e)]
          (cond
            (and (= ::failed type) (new-style-codes code)) {:error data}
            (= ::failed type) {:legacy true}
            (and stdio? (= ::timeout type)) {:legacy true}
            :else (throw e)))))))

(defn ^:private detect!
  "Finds which revision the server speaks, and starts speaking it."
  [client]
  (let [{:keys [result error]} (discover client)]
    (cond
      result (speaking! client (:supportedVersions result) result)

      (= unsupported-version-code (:code error))
      (speaking! client (get-in error [:data :supported]) nil)

      (= missing-capability-code (:code error))
      (throw (failure ::failed "The MCP server wants something of grog it can't do." (select-keys error [:code :data])))

      error (discovered! client nil)

      (= :stdio (:kind (:transport client)))
      (initialize! client)

      :else
      (try (initialize! client)
           (catch clojure.lang.ExceptionInfo e
             (if (#{::failed ::closed} (error-type e))
               (throw (failure ::failed (str "The server doesn't speak MCP: " (ex-message e)) (ex-data e)))
               (throw e)))))))

(defn close!
  "Closes `client`: whatever waits for the server fails, a stdio server's
  transport is closed, an HTTP session ended. Closing again does nothing."
  [client]
  (let [[old] (swap-vals! (:state client) assoc :closed? true)]
    (when-not (:closed? old)
      (if (= :stdio (:kind (:transport client)))
        (do (try (some-> (:close! (:transport client)) (apply []))
                 (catch Exception _ nil))
            (die! client))
        (when-let [session (:session-id old)]
          (try (http/delete (:url (:transport client))
                            {:headers (merge (when-let [f (:headers (:transport client))] (f))
                                             {"Mcp-Session-Id" session}
                                             (when-not (= "2025-03-26" (:version old))
                                               {"MCP-Protocol-Version" (:version old)}))
                             :throw false
                             :timeout 5000})
               (catch Exception _ nil)))))
    nil))

(defn connect!
  "Connects to the server behind `transport`, finding which revision it
  speaks: server/discover first, and the handshake before 2026 if it
  doesn't know it. Returns the client.

    :client-info       who grog is, {:name :version}
    :on-elicit         (fn [params]) → {:action \"accept\"|\"decline\"|\"cancel\"
                       :content {...}}, asking the user what a server asks
                       for (form or URL mode); without it, grog says it
                       can't be asked
    :on-tools-changed  (fn []), when the server says its tools changed
    :timeout-ms        how long requests wait for an answer by default
    :legacy?           skip server/discover: for a stdio server whose
                       connection closed while being asked (::closed),
                       started again

  Throws ex-info ::unauthorized, ::forbidden, ::unreachable, ::timeout,
  ::closed, ::unsupported-version or ::failed."
  [transport {:keys [client-info timeout-ms legacy?] :as opts}]
  (let [stdio? (= :stdio (:kind transport))
        client (cond-> {:transport transport
                        :opts (assoc opts
                                     :client-info (or client-info {:name "grog" :version "dev"})
                                     :timeout-ms (or timeout-ms default-timeout-ms))
                        :ids (AtomicLong.)
                        :pending (atom {})
                        :eliciting (atom 0)
                        :state (atom {})}
                 stdio? (assoc :writer (io/writer (:out transport) :encoding "UTF-8")))]
    (when stdio?
      (doto (Thread. ^Runnable (fn [] (read-stdio! client)) "grog-mcp-reader")
        (.setDaemon true)
        (.start)))
    (try
      (if legacy?
        (initialize! client)
        (detect! client))
      client
      (catch Throwable e
        (close! client)
        (throw e)))))

(defn info
  "What `client`'s server said of itself: {:version :server-info
  :instructions :capabilities}."
  [client]
  (select-keys @(:state client) [:version :server-info :instructions :capabilities]))

(defn ^:private annotations
  "Every x-mcp-header in `schema`: [[path name]...], path being the keys
  leading to the schema it's on."
  [schema]
  (letfn [(walk [x path]
            (cond
              (map? x) (concat (when (contains? x :x-mcp-header) [[path (:x-mcp-header x)]])
                               (mapcat (fn [[k v]] (walk v (conj path k))) x))
              (sequential? x) (mapcat (fn [i v] (walk v (conj path i))) (range) x)
              :else nil))]
    (walk schema [])))

(defn ^:private valid-annotation?
  "Whether an x-mcp-header can be sent: a header name, on a property that
  isn't a number, object or array, reached from the root through
  properties only."
  [schema path header]
  (let [types (set (flatten [(:type (get-in schema path))]))]
    (and (string? header)
         (re-matches #"[!#$%&'*+\-.^_`|~0-9A-Za-z]+" header)
         (seq path)
         (even? (count path))
         (every? #{:properties} (take-nth 2 path))
         (not-any? types ["number" "object" "array"]))))

(defn ^:private header-params
  "Which arguments of `tool` go in headers, [[path header]...], or nil when
  its schema says so wrongly."
  [tool]
  (let [schema (:inputSchema tool)
        found (annotations schema)
        names (map (comp str/lower-case str second) found)]
    (when (and (every? (fn [[path header]] (valid-annotation? schema path header)) found)
               (or (empty? names) (apply distinct? names)))
      (mapv (fn [[path header]] [(take-nth 2 (rest path)) header]) found))))

(defn ^:private list-tools!
  "Every page of the server's tools: [tools ttl-ms], the ttl being the
  smallest of its pages'."
  [client]
  (loop [cursor nil pages 1 acc [] ttl nil]
    (let [result (rpc! client "tools/list" (cond-> {} cursor (assoc :cursor cursor)))
          acc (into acc (:tools result))
          page-ttl (let [t (:ttlMs result)] (if (and (number? t) (pos? t)) (long t) 0))
          ttl (min (or ttl Long/MAX_VALUE) page-ttl)]
      (if (and (:nextCursor result) (< pages max-pages) (< (count acc) max-tools))
        (recur (:nextCursor result) (inc pages) acc ttl)
        [(vec (take max-tools acc)) ttl]))))

(defn ^:private refresh-tools! [client]
  (let [{:keys [state transport]} client
        generation (:tools-generation @state)
        [listed ttl] (list-tools! client)
        http? (= :http (:kind transport))
        params (when http? (into {} (map (juxt :name header-params)) listed))
        kept (if http?
               (filterv (fn [tool]
                          (or (some? (get params (:name tool)))
                              (do (log/info "mcp: dropped tool" (pr-str (:name tool)) "(invalid x-mcp-header)")
                                  false)))
                        listed)
               listed)
        ttl (cond
              (new-style? client) ttl
              http? legacy-http-ttl-ms
              :else Long/MAX_VALUE)]
    (swap! state (fn [s]
                   (cond-> (assoc s :header-params params)
                     (= generation (:tools-generation s))
                     (assoc :tools-cache {:tools kept
                                          :expires-at (if (= Long/MAX_VALUE ttl) Long/MAX_VALUE (+ (now) ttl))}))))
    kept))

(defn tools
  "The server's tools, every page: their maps as the server sent them. Kept
  for as long as the server says (2026), until it says they changed
  (older, over stdio), or for a minute (older, over HTTP). Over HTTP,
  tools whose x-mcp-header annotations are wrong are left out."
  [client]
  (let [{:keys [tools expires-at]} (:tools-cache @(:state client))]
    (if (and tools (< (now) expires-at))
      tools
      (refresh-tools! client))))

(defn ^:private call-once!
  "One tools/call. When the server says its headers don't match the tool
  it knows, lists the tools again and calls once more."
  [client params timeout-ms]
  (try
    (rpc! client "tools/call" params {:timeout-ms timeout-ms})
    (catch clojure.lang.ExceptionInfo e
      (if (= header-mismatch-code (:code (ex-data e)))
        (do (invalidate-tools! client)
            (tools client)
            (rpc! client "tools/call" params {:timeout-ms timeout-ms}))
        (throw e)))))

(defn ^:private input-for
  "What a call that asked for input sends with itself again: the user's
  answers, and the server's state as it came."
  [client result]
  (let [responses (into {}
                        (for [[k {:keys [method params]}] (:inputRequests result)]
                          (if (= "elicitation/create" method)
                            [k (elicit! client params)]
                            (throw (failure ::failed (str "The MCP server asked grog for " method
                                                          ", which it doesn't do."))))))]
    (cond-> {}
      (seq responses) (assoc :inputResponses responses)
      (contains? result :requestState) (assoc :requestState (:requestState result)))))

(defn call-tool!
  "Calls tool `name` with `arguments`: the tools/call result, {:content
  :structuredContent :isError}. When it asks for input, asks the user
  (`:on-elicit`) and calls again with the answers, up to 8 times.
  `:timeout-ms` bounds each request (a minute by default)."
  ([client name arguments] (call-tool! client name arguments {}))
  ([client name arguments {:keys [timeout-ms]}]
   (let [timeout-ms (or timeout-ms call-timeout-ms)
         params {:name name :arguments (or arguments {})}]
     (when (and (= :http (:kind (:transport client)))
                (new-style? client)
                (not (contains? (:header-params @(:state client)) name)))
       (tools client))
     (loop [rounds 0 extra {}]
       (let [result (call-once! client (merge params extra) timeout-ms)]
         (cond
           (not= "input_required" (:resultType result)) result
           (>= rounds max-input-rounds) (throw (failure ::failed "The MCP server kept asking for input."))
           :else (recur (inc rounds) (input-for client result))))))))

(defn alive?
  "Whether `client` can still talk to its server."
  [client]
  (let [{:keys [closed? dead?]} @(:state client)]
    (not (or closed? dead?))))

(defn ^:private block-text [{:keys [type text name title uri resource]}]
  (case type
    "text" text
    "image" "[image]"
    "audio" "[audio]"
    "resource_link" (str (or name title uri) (when uri (str " (" uri ")")))
    "resource" (or (:text resource) (str "[file " (:uri resource) "]"))
    nil))

(defn text-of
  "A tools/call result as text for the model: its text blocks joined by
  blank lines, links as \"name (uri)\", images and audio as [image] and
  [audio], embedded files' text inline (or [file uri]), its structured
  content as JSON when there's no text; \"error: \" first when it's one."
  [result]
  (when result
    (let [texts (keep block-text (:content result))
          text (if (seq texts)
                 (str/join "\n\n" texts)
                 (some-> (:structuredContent result) json/generate-string))]
      (if (:isError result)
        (str "error: " text)
        (or text "")))))
