(ns grog.servers
  "The MCP servers of what's connected (grog.catalog's entries), live: a
  client each (grog.mcp), started on first use (a package through grog's
  runner, a remote over HTTP with its connection's token, refreshed as it
  goes), kept in the context's :servers, and stopped once unused for
  `idle-ms`. A client whose server went away is dropped, and started again
  on the next call; a call that may have reached its server is never made
  again.

  What a server's tools were, last time grog asked, is kept in
  <state-dir>/tools/<id>.json, {\"tools\" [...] \"at\" ms \"server\" {name
  version}}, the tools as the server sent them: what the model sees
  (grog.mcp-tools) without starting anything. It's refreshed on
  connecting, when the server says its tools changed, and after a call once
  it's an hour old."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [grog.catalog :as catalog]
            [grog.config :as config]
            [grog.connections :as connections]
            [grog.elicit :as elicit]
            [grog.log :as log]
            [grog.mcp :as mcp]
            [grog.mcp-auth :as mcp-auth]
            [grog.runner :as runner]
            [grog.state :as state]))

(def idle-ms
  "How long a server runs without calls before it's stopped. Tests
  redefine it."
  (* 10 60 1000))

;; How old the tools kept may get before a call refreshes them.
(def ^:private tools-max-age-ms (* 60 60 1000))

;; Refreshing tokens, one at a time: a refresh token may only work once.
(def ^:private auth-lock (Object.))

(defn ^:private now []
  (System/currentTimeMillis))

(defn ^:private tools-file [config id]
  (when-not (re-matches connections/id-pattern (str id))
    (throw (ex-info (str "a connection can't be called " (pr-str id)) {:type ::bad-id})))
  (str (fs/path (config/state-dir config) "tools" (str id ".json"))))

(defn cached
  "What the server of connection `id` said its tools were, last time: {:tools
  :at :server}, or nil."
  [config id]
  (let [f (tools-file config id)]
    (when (fs/exists? f)
      (try
        (let [kept (json/parse-string (slurp f) true)]
          (when (and (map? kept) (sequential? (:tools kept))) kept))
        (catch Exception e
          (log/info (format "servers: ignoring the tools of %s: %s" id (.getSimpleName (class e))))
          nil)))))

(defn cached-tools
  "The tools the server of connection `id` had, last time grog asked, as it
  sent them, or nil."
  [config id]
  (:tools (cached config id)))

(defn save-tools!
  "Keeps `tools`, the tools of `client`, the server of connection `id`."
  [config id client tools]
  (state/write! (tools-file config id)
                (json/generate-string {:tools (vec tools)
                                       :at (now)
                                       :server (select-keys (:server-info (mcp/info client)) [:name :version])})))

(defn forget-tools!
  "Forgets the tools of connection `id`."
  [config id]
  (fs/delete-if-exists (tools-file config id)))

(defn transport!
  "What carries `launch` (grog.catalog/launch) to its server: a package's
  stdio, through grog's runner, or a remote over HTTP, sending (headers)
  with each request too. Tests redefine it."
  [config launch headers]
  (case (:kind launch)
    :package (let [socket (get-in config [:integrations :runner])]
               (when-not socket
                 (throw (ex-info "grog can't run servers here: its runner isn't set (integrations.runner)"
                                 {:type ::no-runner})))
               (mcp/stdio-transport (runner/start! socket (:request launch))))
    :remote (mcp/http-transport {:url (:url launch)
                                 :headers #(merge (:headers launch) (headers))})))

(defn connect!
  "Connects to the server `launch` says (grog.catalog/launch): the client.
  `opts`: :headers (fn []) giving more headers for each request (a token),
  :on-elicit and :on-tools-changed (grog.mcp/connect!). A package whose
  connection closes while grog finds which revision it speaks is started
  again once, the old way. Throws what grog.mcp/connect! and the runner
  throw."
  [config launch {:keys [headers on-elicit on-tools-changed]}]
  (let [start #(mcp/connect! (transport! config launch (or headers (constantly {})))
                             {:on-elicit on-elicit :on-tools-changed on-tools-changed :legacy? %})]
    (try
      (start false)
      (catch clojure.lang.ExceptionInfo e
        (if (and (= :package (:kind launch)) (= ::mcp/closed (:type (ex-data e))))
          (start true)
          (throw e))))))

(defn ^:private title-of [config id]
  (or (some-> (catalog/entry config id) catalog/title) id))

(defn ^:private signed-out
  "Notes that connection `id` needs signing in again: the ex-info saying
  so."
  [config id]
  (connections/set-status! (config/state-dir config) id "signed-out")
  (log/info "servers:" id "signed out")
  (ex-info (str "sign in to " (title-of config id) " again (grog integrations)") {:type ::signed-out}))

(defn ^:private bearer
  "The headers fn of connection `id`'s server: its token, refreshed when it
  expires, and kept."
  [ctx id]
  (fn []
    (let [config @(:config ctx)
          dir (config/state-dir config)
          auth (:auth (connections/secrets dir id))]
      (if-not auth
        {}
        (let [fresh (locking auth-lock
                      (let [{:keys [auth] :as secrets} (connections/secrets dir id)
                            fresh (try (mcp-auth/fresh! auth (now))
                                       (catch clojure.lang.ExceptionInfo e
                                         (throw (if (= ::mcp-auth/refused (:type (ex-data e)))
                                                  (signed-out config id)
                                                  e))))]
                        (when (not= fresh auth)
                          (connections/save! dir (connections/by-id dir id) (assoc secrets :auth fresh))
                          (log/info "servers:" id "token refreshed"))
                        fresh))]
          {"Authorization" (str "Bearer " (:access-token fresh))})))))

(defn ^:private lock-of [ctx id]
  (get-in (swap! (:servers ctx) update-in [:locks id] #(or % (Object.))) [:locks id]))

(defn ^:private drop!
  "Forgets `client`, connection `id`'s, if it's still the one, and closes
  it."
  [ctx id client]
  (swap! (:servers ctx) update :clients #(if (= client (get-in % [id :client])) (dissoc % id) %))
  (mcp/close! client))

(defn ^:private touch! [ctx id client]
  (swap! (:servers ctx) update :clients #(cond-> % (= client (get-in % [id :client])) (assoc-in [id :used] (now)))))

(defn ^:private launch-of
  "What starts the server of `entry` for a connection with `secrets`: the
  remote it reached when it connected (`:url`, with its `:headers`),
  else as its values fill it in (grog.catalog/launch)."
  [config entry secrets]
  (if-let [url (:url secrets)]
    {:kind :remote
     :url url
     :headers (into {} (for [[k v] (:headers secrets)] [(name k) v]))}
    (catalog/launch config entry (:values secrets))))

(declare refresh-tools!)

(defn ^:private start!
  "Starts the server of connection `id`: its client. One connected through
  its entry's ways goes to the address that answered then."
  [ctx id]
  (let [config @(:config ctx)
        dir (config/state-dir config)
        connection (connections/by-id dir id)
        entry (catalog/entry config id)
        title (title-of config id)]
    (cond
      (or (nil? connection) (nil? entry)) (throw (ex-info (str title " isn't connected") {:type ::not-connected}))
      (= "signed-out" (:status connection)) (throw (ex-info (str "sign in to " title " again (grog integrations)")
                                                            {:type ::signed-out})))
    ;; Refreshing the token first: grog.mcp tells failing headers apart from
    ;; nothing else.
    ((bearer ctx id))
    (let [launch (launch-of config entry (connections/secrets dir id))
          client (try
                   (connect! config launch {:headers (bearer ctx id)
                                            :on-elicit #(elicit/ask! ctx title %)
                                            :on-tools-changed #(future (refresh-tools! ctx id))})
                   (catch clojure.lang.ExceptionInfo e
                     (throw (if (= ::mcp/unauthorized (:type (ex-data e))) (signed-out config id) e))))]
      (swap! (:servers ctx) assoc-in [:clients id] {:client client :used (now)})
      (log/info "servers: started" id)
      client)))

(defn client!
  "The client of connection `id`'s server, started if it isn't (or went
  away). Throws ex-info saying why it can't be."
  [ctx id]
  (let [lock (lock-of ctx id)]
    (locking lock
      (let [{:keys [client]} (get-in @(:servers ctx) [:clients id])]
        (if (and client (mcp/alive? client))
          client
          (do (some->> client (drop! ctx id))
              (start! ctx id)))))))

(defn ^:private refresh-tools!
  "Asks connection `id`'s server for its tools again, if it runs, and keeps
  them."
  [ctx id]
  (when-let [client (get-in @(:servers ctx) [:clients id :client])]
    (try
      (save-tools! @(:config ctx) id client (mcp/tools client))
      (catch Exception e
        (log/info "servers: listing the tools of" id "failed:" (.getSimpleName (class e)))))))

(defn call!
  "Calls `tool` of connection `id`'s server with `args`: the tools/call
  result (grog.mcp/call-tool!). Throws ex-info saying why not."
  [ctx id tool args]
  (let [client (client! ctx id)]
    (touch! ctx id client)
    ((bearer ctx id))
    (try
      (let [result (mcp/call-tool! client tool args)
            config @(:config ctx)]
        (touch! ctx id client)
        (when (> (- (now) (or (:at (cached config id)) 0)) tools-max-age-ms)
          (future (refresh-tools! ctx id)))
        result)
      (catch clojure.lang.ExceptionInfo e
        (let [type (:type (ex-data e))]
          (when (or (= ::mcp/closed type) (= ::mcp/unauthorized type) (not (mcp/alive? client)))
            (drop! ctx id client))
          (throw (if (= ::mcp/unauthorized type) (signed-out @(:config ctx) id) e)))))))

(defn stop!
  "Stops connection `id`'s server, if it runs."
  [ctx id]
  (when-let [client (get-in @(:servers ctx) [:clients id :client])]
    (drop! ctx id client)
    (log/info "servers: stopped" id)))

(defn stop-idle!
  "Stops the servers unused for `idle-ms`: their ids."
  [ctx]
  (let [at (now)
        idle (for [[id {:keys [used]}] (:clients @(:servers ctx))
                   :when (>= (- at used) idle-ms)]
               id)]
    (run! #(stop! ctx %) idle)
    (vec idle)))

(defn stop-all!
  "Stops every server: grog is going away."
  [ctx]
  (run! #(stop! ctx %) (keys (:clients @(:servers ctx)))))

(defn running
  "The ids of the connections whose servers run."
  [ctx]
  (set (keys (:clients @(:servers ctx)))))
