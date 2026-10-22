(ns grog.api
  "grog's HTTP API (brain/README.md, HTTP API), answered by `route`. JSON keys are
  in lowerCamelCase, both ways. The Host must be one of
  `server.allowedHosts`, and POST, PUT and DELETE take Content-Type:
  application/json: web pages can't use grog (see `refusal`)."
  (:require [clojure.string :as str]
            [grog.actions :as actions]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.events :as events]
            [grog.integrations :as integrations]
            [grog.log :as log]
            [grog.mcp-connect :as mcp-connect]
            [grog.mcp-tools :as mcp-tools]
            [grog.music :as music]
            [grog.oauth :as oauth]
            [grog.provider :as provider]
            [grog.qr :as qr]
            [grog.settings :as settings]
            [grog.skin :as skin]
            [grog.sse :as sse]
            [grog.update :as update]
            [grog.usage :as usage]
            [grog.voice :as voice]
            [org.httpkit.server :as http])
  (:import (java.net URLDecoder)))

(defn ^:private json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (config/generate body)})

(defn ^:private read-json
  "The request's JSON body, keys as kebab-case keywords, or nil."
  [req]
  (try (some-> (:body req) slurp config/parse)
       (catch Exception _ nil)))

(def ^:private text-headers
  {"Content-Type" "text/plain; charset=utf-8"
   "Cache-Control" "no-cache"})

(def ^:private event-headers
  {"Content-Type" "text/event-stream; charset=utf-8"
   "Cache-Control" "no-cache"})

(defn ^:private error-kind
  "What went wrong in a turn, without its message, which could quote what
  was said: what's safe to log."
  [^Throwable e]
  (let [{:keys [type status]} (ex-data e)]
    (cond-> (str (or type (.getName (class e))))
      status (str " " status))))

(defn ^:private stream-reply
  "Runs a turn (grog.assistant/reply!) streaming into channel `ch`: plain text,
  or server-sent events when `events?` (text, tool, then done or error). The
  200 status is only sent with the first chunk, so failures before any answer
  (bad key, network, API errors) become a 502 with a JSON error; later ones are
  appended inline. When the client goes away, the turn stops. Once the
  answer is out, the actions the model asked for are taken."
  [ctx ch gone request events?]
  (let [later (atom [])
        started? (volatile! false)
        headers (if events? event-headers text-headers)
        send! (fn [data]
                (when (or (realized? gone) (not (http/send! ch data false)))
                  (throw (ex-info "The client went away." {:type ::client-gone}))))
        start! (fn []
                 (when-not @started?
                   (vreset! started? true)
                   (send! {:status 200 :headers headers})))
        on-text (fn [text]
                  (start!)
                  (send! (if events? (sse/message "text" {:text text}) text)))
        ;; Plain text has no room for them.
        on-tool (fn [{:keys [name]}]
                  (when events?
                    (start!)
                    (send! (sse/message "tool" {:name name}))))]
    (try
      ;; An answer always started: there's no answer without text.
      (assistant/reply! ctx (assoc request :later later) on-text on-tool)
      (when events?
        (send! (sse/message "done" {})))
      (http/close ch)
      (actions/after-answer! ctx (:id request) @later)
      (catch Throwable e
        (let [msg (or (ex-message e) (str e))]
          (cond
            (= ::client-gone (:type (ex-data e)))
            (log/info "chat cancelled: the client went away")

            @started?
            (do (log/info "chat error:" (error-kind e))
                (http/send! ch (if events? (sse/message "error" {:error msg}) (str "\n[error] " msg)) false)
                (http/close ch))

            :else
            (do (log/info "chat error:" (error-kind e))
                (http/send! ch (json-response 502 {:error msg}) true))))))))

(defn ^:private conversation-id?
  "Whether `id` can name a conversation."
  [id]
  (and (string? id) (not (str/blank? id)) (<= (count id) 200)))

(def ^:private max-said-chars 1000)

(defn ^:private chat-request
  "The turn a /v1/chat `body` asks for ({:id :message :new? :said}), or nil
  when it doesn't make sense."
  [body]
  (when (map? body)
    (let [{:keys [conversation-id said]} body
          message (some-> (:message body) str str/trim)]
      (when (and (not (str/blank? message))
                 (or (nil? conversation-id) (conversation-id? conversation-id))
                 (or (nil? said) (and (string? said) (<= (count said) max-said-chars))))
        (cond-> {:id (or conversation-id "default") :message message :new? (true? (:new body))}
          (not (str/blank? said)) (assoc :said said))))))

(defn ^:private chat [ctx req]
  (let [request (chat-request (read-json req))
        events? (some-> (get-in req [:headers "accept"]) (str/includes? "text/event-stream"))]
    (if-not request
      (json-response 400 {:error (str "Send a JSON body with a \"message\", and maybe a \"conversationId\" (up to 200"
                                      " characters), \"new\": true, and what was \"said\" (up to "
                                      max-said-chars " characters).")})
      (let [gone (promise)]
        (http/as-channel req {:on-open (fn [ch]
                                         (future
                                           (try
                                             (stream-reply ctx ch gone request (boolean events?))
                                             ;; The turn may have changed its name.
                                             (finally (assistant/publish-status! ctx)))))
                              ;; Without it, http-kit doesn't notice the client
                              ;; leaving, and send! keeps returning true.
                              :on-close (fn [_ _] (deliver gone true))})))))

(defn ^:private stream
  "Streams server-sent events to whoever asked: `watch!` gets a key and a
  function sending an event's text (false once they're gone), `unwatch!`
  the key once they go."
  [req watch! unwatch!]
  (http/as-channel req {:on-open (fn [ch]
                                   ;; The headers go with the first event.
                                   (let [started? (volatile! false)]
                                     (watch! ch (fn [text]
                                                  (if @started?
                                                    (http/send! ch text false)
                                                    (do (vreset! started? true)
                                                        (http/send! ch {:status 200 :headers event-headers :body text}
                                                                    false)))))))
                        :on-close (fn [ch _] (unwatch! ch))}))

(defn ^:private watch
  "Streams what grog is up to (grog.events): the state, then each change."
  [{:keys [events]} req]
  (stream req #(events/watch! events %1 %2) #(events/unwatch! events %)))

(defn ^:private voice-commands
  "Streams what grog asks of grog-voice (grog.voice): whether its mic is on,
  then what it's asked to do."
  [{:keys [voice]} req]
  (stream req #(voice/watch! voice %1 %2) #(voice/unwatch! voice %)))

(defn ^:private heard?
  "Whether `heard` is what grog-voice heard for grog: {:text :final}."
  [heard]
  (and (map? heard)
       (string? (:text heard))
       (<= (count (:text heard)) max-said-chars)
       (boolean? (:final heard))))

(defn ^:private voice-report
  "grog-voice saying what it's doing, in which conversation, and maybe what
  it heard for grog (kept only with captions, see grog.events). The answer
  says whether there are captions (grog-voice only sends what it hears for
  grog, and transcribes it to its end while someone talks, to show it), and
  grog's name, which it answers to."
  [ctx req]
  (let [body (read-json req)
        {:keys [state conversation-id heard]} (when (map? body) body)]
    (if (and (contains? events/voice-states state)
             (or (nil? conversation-id) (conversation-id? conversation-id))
             (or (nil? heard) (heard? heard)))
      (do (events/publish! (:events ctx) (cond-> {:type :voice :state state :conversation conversation-id}
                                           heard (assoc :heard (select-keys heard [:text :final]))))
          (json-response 200 {:captions (events/captions? (:events ctx))
                              :name (:name @(:config ctx))}))
      (json-response 400 {:error (format (str "Send {\"state\": one of %s, \"conversationId\": its conversation,"
                                              " and maybe \"heard\": {\"text\", \"final\": true or false}}.")
                                         (str/join ", " (sort events/voice-states)))}))))

(defn ^:private way
  "How a connect request's `body` asks to connect: its way, or nil for the
  usual one."
  [body]
  (let [way (when (map? body) (:way body))]
    (when-not (or (nil? way) (string? way))
      (throw (ex-info "Send {\"way\": how to connect}, or {} for the usual way." {:type ::bad-way})))
    way))

(defn ^:private api-call
  "Answers the result of `f` as JSON; errors as 400 (ex-info) or 502."
  [f]
  (try
    (json-response 200 (f))
    (catch clojure.lang.ExceptionInfo e
      (json-response 400 {:error (ex-message e)}))
    (catch Exception e
      (json-response 502 {:error (or (ex-message e) (str e))}))))

(defn ^:private action
  "Takes the action `name` (grog.actions) a request asks for, with what its
  JSON body says, if anything."
  [ctx name req]
  (let [args (read-json req)]
    (cond
      (not (contains? actions/known name))
      (json-response 404 {:error (format "There's no action called %s." name)})

      (not (or (nil? args) (map? args)))
      (json-response 400 {:error "Send a JSON object, or nothing."})

      :else
      (api-call #(actions/perform! ctx name (or args {}))))))

(defn ^:private music
  "Does what the body asks of grog's music, {\"action\": play, pause,
  resume, stop, next, previous, volume or status, and \"query\", \"kind\",
  \"shuffle\", \"level\", \"change\" as the `music` tool takes them}
  (grog.music): {\"provider\", \"text\"}, what it says it did."
  [ctx req]
  (let [body (read-json req)]
    (if-not (and (map? body) (some #{(:action body)} music/actions))
      (json-response 400 {:error (str "Send {\"action\": one of " (str/join ", " music/actions)
                                      ", and maybe \"query\", \"kind\", \"shuffle\", \"level\" or \"change\"}.")})
      (api-call #(let [text (music/perform! ctx body)]
                   {:provider (:id (music/chosen @(:config ctx))) :text text})))))

(def ^:private registry-trouble
  "What the MCP Registry not answering, or answering nonsense, is called."
  #{:grog.registry/unreachable :grog.registry/bad-answer})

(defn ^:private registry-call
  "`api-call`, but the MCP Registry not answering is a 502."
  [f]
  (try
    (json-response 200 (f))
    (catch clojure.lang.ExceptionInfo e
      (json-response (if (registry-trouble (:type (ex-data e))) 502 400) {:error (ex-message e)}))
    (catch Exception e
      (json-response 502 {:error (or (ex-message e) (str e))}))))

(defn ^:private add-integration
  "Adds the MCP server the body names, {\"name\"}, {\"package\"} or
  {\"url\"} (grog.mcp-connect/add!): its id, and how it shows."
  [ctx req]
  (let [body (read-json req)
        what (when (map? body) (select-keys body [:name :package :url]))]
    (if-not (and (= 1 (count what)) (every? string? (vals what)))
      (json-response 400 {:error (str "Send one of {\"name\": its name in the MCP Registry}, "
                                      "{\"package\": \"npm:name@1.2.3\" or \"pypi:name==1.2.3\"} "
                                      "or {\"url\": \"https://...\"}.")})
      (registry-call #(let [id (mcp-connect/add! @(:config ctx) what)]
                        (assistant/publish-status! ctx)
                        {:id id
                         :integration (some (fn [i] (when (= id (:id i)) i))
                                            (integrations/summary @(:config ctx)))})))))

(defn ^:private remove-integration
  "Removes the MCP server the user added as `id`, disconnecting it first."
  [ctx id]
  (api-call #(let [result (mcp-connect/remove! ctx id)]
               (assistant/publish-status! ctx)
               result)))

(defn ^:private query-param
  "What the query of `req` says `k` is, or nil."
  [{:keys [query-string]} k]
  (try (get (oauth/form-decode query-string) k)
       (catch IllegalArgumentException _ nil)))

(defn ^:private search-registry
  "Servers of the MCP Registry whose name holds the query's `q`."
  [req]
  (let [q (some-> (query-param req :q) str/trim)]
    (if (str/blank? q)
      (json-response 400 {:error "Say what to look for: /v1/integrations/search?q=..."})
      (registry-call #(hash-map :servers (mcp-connect/search! q))))))

(defn ^:private tools-call
  "`api-call` for a connection's tools: unknown connections are a 404."
  [f]
  (try
    (json-response 200 (f))
    (catch clojure.lang.ExceptionInfo e
      (json-response (if (= :grog.mcp-tools/unknown (:type (ex-data e))) 404 400) {:error (ex-message e)}))))

(defn ^:private connection-tools
  "The tools of connection `id`, an MCP server's, and what the user chose
  for each."
  [ctx id]
  (tools-call #(hash-map :tools (mcp-tools/tool-settings @(:config ctx) id))))

(defn ^:private tool-name
  "The tool name in a path, percent-decoded, or nil when it isn't an MCP
  tool's name."
  [raw]
  (let [decoded (try (URLDecoder/decode (str/replace raw "+" "%2B") "UTF-8")
                     (catch IllegalArgumentException _ nil))]
    (when (and decoded (re-matches #"[A-Za-z0-9_.-]{1,128}" decoded))
      decoded)))

(defn ^:private set-tool
  "Sets what the user chose for a tool of connection `id`: the body's
  \"mode\" and \"show\", null for the default."
  [ctx id raw req]
  (let [tool (tool-name raw)
        body (read-json req)
        chosen (when (map? body) (select-keys body [:mode :show]))]
    (cond
      (not tool) (json-response 400 {:error "That's no tool's name."})
      (empty? chosen) (json-response 400 {:error (str "Send {\"mode\": \"allow\", \"ask\", \"deny\" or null, "
                                                      "\"show\": true, false or null}, either or both.")})
      :else (tools-call #(mcp-tools/set-tool! @(:config ctx) id tool chosen)))))

(defn ^:private qr-image
  "The QR code of what the query's `text` says, as SVG (grog.qr): the skin
  shows links it can't know in advance, like a session's (grog.connect)."
  [{:keys [query-string]}]
  (let [text (try (:text (oauth/form-decode query-string))
                  (catch IllegalArgumentException _ nil))]
    (if (str/blank? text)
      (json-response 400 {:error "Say what the code holds: /v1/qr?text=..."})
      (try
        {:status 200
         :headers {"Content-Type" "image/svg+xml"
                   "Cache-Control" "no-store"
                   "X-Content-Type-Options" "nosniff"}
         :body (qr/svg text)}
        (catch clojure.lang.ExceptionInfo e
          (json-response 400 {:error (str "The text is " (ex-message e) ".")}))))))

(defn ^:private host-name
  "A Host header without the port: \"[::1]:8484\" -> \"[::1]\"."
  [host]
  (some-> host (str/replace #":\d+$" "")))

(defn ^:private refusal
  "A response refusing `req`, or nil. Web pages can't use grog: through DNS
  rebinding their requests carry another Host, and they can't send JSON to
  another site without a CORS preflight, which grog never allows."
  [config {:keys [request-method headers]}]
  (let [host (get headers "host")]
    (cond
      (not (contains? (set (get-in config [:server :allowed-hosts])) (host-name host)))
      (json-response 403 {:error (format "Host %s isn't allowed (see server.allowedHosts)." (pr-str host))})

      (and (#{:post :put :delete} request-method)
           (not (some-> (get headers "content-type") str/lower-case (str/starts-with? "application/json"))))
      (json-response 415 {:error "Send requests with Content-Type: application/json."}))))

(defn route
  "Answers `req`, with what the context `ctx` holds (grog.assistant)."
  [ctx {:keys [request-method uri] :as req}]
  (let [config @(:config ctx)
        [_ action-name] (re-matches #"/v1/actions/([a-z-]+)" uri)
        [_ integration] (re-matches #"/v1/integrations/([a-z0-9-]+)/connect" uri)
        [_ added] (re-matches #"/v1/integrations/([a-z0-9-]+)" uri)
        [_ connection] (re-matches #"/v1/connections/([a-z0-9:-]+)" uri)
        [_ tools-of] (re-matches #"/v1/connections/([a-z0-9:-]+)/tools" uri)
        [_ tool-of raw-tool] (re-matches #"/v1/connections/([a-z0-9:-]+)/tools/([^/]+)" uri)]
    (or
     (refusal config req)
     (cond
       (= [:get "/health"] [request-method uri])
       {:status 200 :headers {"Content-Type" "text/plain"} :body "ok"}

       (= [:post "/v1/chat"] [request-method uri])
       (chat ctx req)

       (= [:get "/v1/status"] [request-method uri])
       (api-call #(assoc (provider/describe config)
                         :name (:name config)
                         :revision (update/revision)
                         :changed (settings/changed ctx)
                         :usage (usage/summary ctx)
                         ;; For updates: whether someone is talking to it, and how the last one goes.
                         :busy (events/busy? (events/snapshot (:events ctx)) (events/now-ms))
                         :update (update/status config)))

       (= [:delete "/v1/settings"] [request-method uri])
       (api-call #(do (settings/reset-all! ctx)
                      (assistant/publish-status! ctx)
                      {:changed (settings/changed ctx)}))

       (= [:get "/v1/models"] [request-method uri])
       (api-call #(hash-map :models (provider/models config)))

       (= [:get "/v1/qr"] [request-method uri])
       (qr-image req)

       (= [:get "/v1/integrations"] [request-method uri])
       (api-call #(hash-map :integrations (integrations/summary config) :connect (connect/view @(:connect ctx))))

       (= [:post "/v1/integrations"] [request-method uri])
       (add-integration ctx req)

       (= [:get "/v1/integrations/search"] [request-method uri])
       (search-registry req)

       (and added (= :delete request-method))
       (remove-integration ctx added)

       (and integration (= :post request-method))
       (api-call #(integrations/open! ctx integration (way (read-json req))))

       ;; Polled while connecting: whoever watches sees it as it happens.
       (= [:get "/v1/connect"] [request-method uri])
       (api-call #(or (connect/view @(:connect ctx)) {}))

       (= [:delete "/v1/connect"] [request-method uri])
       (api-call #(connect/cancel! ctx))

       (= [:post "/v1/connect/allow"] [request-method uri])
       (api-call #(connect/decide! ctx true))

       (= [:post "/v1/connect/deny"] [request-method uri])
       (api-call #(connect/decide! ctx false))

       (and connection (= :delete request-method))
       (api-call #(let [result (integrations/disconnect! ctx connection)]
                    (assistant/publish-status! ctx)
                    result))

       (and tools-of (= :get request-method))
       (connection-tools ctx tools-of)

       (and tool-of (= :put request-method))
       (set-tool ctx tool-of raw-tool req)

       (= [:get "/v1/events"] [request-method uri])
       (watch ctx req)

       (= [:post "/v1/voice"] [request-method uri])
       (voice-report ctx req)

       (= [:get "/v1/voice/commands"] [request-method uri])
       (voice-commands ctx req)

       (and action-name (= :post request-method))
       (action ctx action-name req)

       (= [:post "/v1/music"] [request-method uri])
       (music ctx req)

       :else
       (or (when (= :get request-method)
             (skin/response (get-in config [:skin :dir]) uri))
           (json-response 404 {:error "Not found."}))))))
