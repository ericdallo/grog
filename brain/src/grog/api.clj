(ns grog.api
  "grog's HTTP API (README.md, HTTP API), answered by `route`. JSON keys are
  in lowerCamelCase, both ways. The Host must be one of
  `server.allowedHosts`, and POST and DELETE take Content-Type:
  application/json: web pages can't use grog (see `refusal`)."
  (:require [clojure.string :as str]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.events :as events]
            [grog.log :as log]
            [grog.login :as login]
            [grog.provider :as provider]
            [grog.settings :as settings]
            [grog.skin :as skin]
            [grog.sse :as sse]
            [grog.usage :as usage]
            [org.httpkit.server :as http]))

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
  appended inline. When the client goes away, the turn stops."
  [ctx ch gone request events?]
  (let [started? (volatile! false)
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
      (assistant/reply! ctx request on-text on-tool)
      (when events?
        (send! (sse/message "done" {})))
      (http/close ch)
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

(defn ^:private watch
  "Streams what grog is up to (grog.events): the state, then each change."
  [ctx req]
  (http/as-channel req {:on-open (fn [ch]
                                   ;; The headers go with the first event.
                                   (let [started? (volatile! false)]
                                     (events/watch! (:events ctx) ch
                                                    (fn [text]
                                                      (if @started?
                                                        (http/send! ch text false)
                                                        (do (vreset! started? true)
                                                            (http/send! ch {:status 200 :headers event-headers :body text}
                                                                        false)))))))
                        :on-close (fn [ch _] (events/unwatch! (:events ctx) ch))}))

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

(defn ^:private api-call
  "Answers the result of `f` as JSON; errors as 400 (ex-info) or 502."
  [f]
  (try
    (json-response 200 (f))
    (catch clojure.lang.ExceptionInfo e
      (json-response 400 {:error (ex-message e)}))
    (catch Exception e
      (json-response 502 {:error (or (ex-message e) (str e))}))))

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

      (and (#{:post :delete} request-method)
           (not (some-> (get headers "content-type") str/lower-case (str/starts-with? "application/json"))))
      (json-response 415 {:error "Send requests with Content-Type: application/json."}))))

(defn route
  "Answers `req`, with what the context `ctx` holds (grog.assistant)."
  [ctx {:keys [request-method uri] :as req}]
  (let [config @(:config ctx)
        [_ provider login?] (re-matches #"/v1/auth/([a-z]+)(/login)?" uri)]
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
                         :changed (settings/changed ctx)
                         :usage (usage/summary ctx)))

       (= [:delete "/v1/settings"] [request-method uri])
       (api-call #(do (settings/reset-all! ctx)
                      (assistant/publish-status! ctx)
                      {:changed (settings/changed ctx)}))

       (= [:get "/v1/models"] [request-method uri])
       (api-call #(hash-map :models (provider/models config)))

       (and provider login? (= :post request-method))
       (api-call #(login/start! config provider))

       ;; Polled while signing in: whoever watches sees it as it happens.
       (and provider (not login?) (= :get request-method))
       (api-call #(do (assistant/publish-status! ctx)
                      (login/status config provider)))

       (and provider (not login?) (= :delete request-method))
       (api-call #(do (login/logout! config provider)
                      (usage/forget-quota! ctx)
                      (assistant/publish-status! ctx)
                      (login/status config provider)))

       (= [:get "/v1/events"] [request-method uri])
       (watch ctx req)

       (= [:post "/v1/voice"] [request-method uri])
       (voice-report ctx req)

       :else
       (or (when (= :get request-method)
             (skin/response (get-in config [:skin :dir]) uri))
           (json-response 404 {:error "Not found."}))))))
