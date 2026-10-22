(ns grog.test-helpers
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.mcp :as mcp]
            [grog.oauth :as oauth]
            [grog.sse :as sse]
            [org.httpkit.server :as http])
  (:import (java.io PipedInputStream PipedOutputStream)
           (java.util Base64)))

(defn sse
  "Serializes events (maps with a :type) as a Server-Sent Events body."
  [& events]
  (apply str (for [event events]
               (sse/message (:type event) event))))

(defn start-handler!
  "Starts an HTTP server on loopback running the ring `handler`.
  Returns {:url :stop!}."
  [handler]
  (let [server (http/run-server handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    {:url (str "http://127.0.0.1:" (http/server-port server))
     :stop! #(http/server-stop! server)}))

(defn start-fake!
  "Starts an HTTP server that answers each request with the next of
  `responses` (ring response maps, SSE content type by default, optionally
  with a :delay-ms before answering) and records the requests.
  Returns {:url :requests :stop!}."
  [responses]
  (let [requests (atom [])
        queue (atom responses)
        server (start-handler!
                (fn [req]
                  (swap! requests conj {:method (:request-method req)
                                        :uri (:uri req)
                                        :query (:query-string req)
                                        :headers (:headers req)
                                        :body (some-> (:body req) slurp (json/parse-string true))})
                  (let [[pending] (swap-vals! queue rest)]
                    (if-let [{:keys [delay-ms] :as resp} (first pending)]
                      (do (some-> delay-ms Thread/sleep)
                          (merge {:status 200 :headers {"Content-Type" "text/event-stream"}}
                                 (dissoc resp :delay-ms)))
                      {:status 500 :body "{\"error\": {\"message\": \"no more fake responses\"}}"}))))]
    (assoc server :requests requests)))

(defmacro with-fake
  "Binds `sym` to a fake server (see `start-fake!`) while running `body`."
  [[sym responses] & body]
  `(let [~sym (start-fake! ~responses)]
     (try ~@body
          (finally ((:stop! ~sym))))))

(defn collector
  "Returns [on-text chunks-atom]."
  []
  (let [chunks (atom [])]
    [#(swap! chunks conj %) chunks]))

(defn json-response [body]
  {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string body)})

(defn jwt
  "An unsigned JWT carrying `claims`."
  [claims]
  (str "e30."
       (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes (json/generate-string claims) "UTF-8"))
       ".sig"))

(defn token-response
  "What OpenAI's token endpoint answers."
  [access-token refresh-token]
  (json-response {:access_token access-token
                  :refresh_token refresh-token
                  :id_token (jwt {"email" "grog@example.com"
                                  "https://api.openai.com/auth" {"chatgpt_account_id" "acct-1"}})
                  :expires_in 3600}))

(defn temp-dir []
  (str (fs/create-temp-dir {:prefix "grog-test"})))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn sh-quote
  "`s` quoted for bash."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn script!
  "An executable bash script `name` in `dir`, running `body`."
  [dir name body]
  (let [f (str (fs/path dir name))]
    (fs/create-dirs dir)
    (spit f (str "#!" (fs/which "bash") "\n" body "\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    f))

(def computer-sh
  "A computer's side of grog, resources/grog/computer.sh: what pairs it,
  then grog's hands there."
  (str (io/file (io/resource "grog/computer.sh"))))

(def quiet-commands
  "What grog's hands run on a computer, faked: nothing happens, and nobody's
  in (no sessions, nothing playing). notify-send answers being asked with
  $ANSWER, and wpctl says the volume is $VOLUME."
  {"notify-send" "if [[ \" $* \" == *\" --wait \"* ]]; then echo 17; printf '%s\\n' \"${ANSWER-}\"; fi"
   "systemd-run" ""
   "xdg-open" ""
   "playerctl" ""
   "wpctl" "if [[ $1 == get-volume ]]; then echo \"Volume: ${VOLUME:-0.40}\"; fi"
   "loginctl" ""
   "busctl" ""
   "sudo" "exit 1"})

(defn computer!
  "A computer of its own, in a temp directory: its user's home, and
  `commands` (name -> bash, over `quiet-commands`) first in its PATH,
  keeping how they were run (`ran`). What its hands run with in :env."
  ([] (computer! {}))
  ([commands]
   (let [dir (temp-dir)
         home (str (fs/path dir "home"))
         bin (str (fs/path dir "bin"))
         log (sh-quote (fs/path dir "ran"))]
     (fs/create-dirs home)
     (fs/create-dirs (fs/path dir "run"))
     (doseq [[name body] (merge quiet-commands commands)]
       (script! bin name (str "printf '%s\\x1f' \"${0##*/}\" \"$@\" >> " log "\nprintf '\\x1e' >> " log "\n" body)))
     {:dir dir
      :home home
      :authorized-keys (str (fs/path home ".ssh" "authorized_keys"))
      :policy-file (str (fs/path home ".config" "grog" "hands.json"))
      :hands-file (str (fs/path home ".local" "share" "grog" "computer.sh"))
      :env {"HOME" home
            "XDG_CONFIG_HOME" (str (fs/path home ".config"))
            "XDG_DATA_HOME" (str (fs/path home ".local" "share"))
            "XDG_RUNTIME_DIR" (str (fs/path dir "run"))
            "PATH" (str bin ":" (System/getenv "PATH"))}})))

(defn ran
  "How `computer`'s faked commands were run, in order: [[name arg...]...]."
  [{:keys [dir]}]
  (let [log (fs/file dir "ran")]
    (if (fs/exists? log)
      (mapv #(vec (butlast (str/split % #"\u001f" -1))) (remove empty? (str/split (slurp log) #"\u001e")))
      [])))

(defn env-words
  "`env` as bash words: NAME='value'..."
  [env]
  (str/join " " (for [[k v] env] (str k "=" (sh-quote v)))))

(defn eventually
  "Whether (pred) becomes true within `ms`."
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(defn mcp-reply
  "What a fake MCP server of 2026 answers `msg` with, nil for a
  notification: `tools` (or (tools)) to list, (call name arguments) the
  result of a call (\"ran <name>\" by default), `server-info` who it is."
  [{:keys [tools call server-info]} msg]
  (when (contains? msg :id)
    (let [ok (fn [r] {:jsonrpc "2.0" :id (:id msg) :result r})]
      (case (:method msg)
        "server/discover" (ok {:supportedVersions ["2026-07-28"]
                               :capabilities {:tools {:listChanged true}}
                               :ttlMs 0
                               :_meta {"io.modelcontextprotocol/serverInfo" (or server-info {:name "fake" :version "1.0"})}})
        "tools/list" (ok {:tools (if (fn? tools) (tools) (or tools [])) :ttlMs 0})
        "tools/call" (ok ((or call (fn [n _] {:content [{:type "text" :text (str "ran " n)}]}))
                          (get-in msg [:params :name])
                          (get-in msg [:params :arguments])))
        {:jsonrpc "2.0" :id (:id msg) :error {:code -32601 :message "Method not found"}}))))

(defn fake-stdio-mcp!
  "A stdio MCP server in this process, answering as `mcp-reply` does with
  `opts`: {:transport (for grog.mcp/connect!) :received (an atom of what it
  got) :close! (it goes away)}."
  [opts]
  (let [server-out (PipedOutputStream.)
        client-in (PipedInputStream. server-out 65536)
        client-out (PipedOutputStream.)
        server-in (PipedInputStream. client-out 65536)
        reader (io/reader server-in :encoding "UTF-8")
        writer (io/writer server-out :encoding "UTF-8")
        received (atom [])
        close! #(try (.close server-out) (catch Exception _ nil))]
    (future
      (try
        (loop []
          (when-let [line (.readLine reader)]
            (let [m (json/parse-string line true)]
              (swap! received conj m)
              (when-let [r (mcp-reply opts m)]
                (locking writer
                  (.write writer (str (json/generate-string r) "\n"))
                  (.flush writer))))
            (recur)))
        (catch Exception _ nil)))
    {:received received
     :close! close!
     :transport (mcp/stdio-transport {:in client-in
                                      :out client-out
                                      :close! #(do (close!) (.close client-out))})}))

(defn ^:private as-metadata [base {:keys [cimd? dcr?]}]
  (cond-> {:issuer base
           :authorization_endpoint (str base "/authorize")
           :token_endpoint (str base "/token")
           :revocation_endpoint (str base "/revoke")
           :code_challenge_methods_supported ["S256"]
           :authorization_response_iss_parameter_supported true}
    cimd? (assoc :client_id_metadata_document_supported true)
    dcr? (assoc :registration_endpoint (str base "/register"))))

(defn start-mcp!
  "Starts a fake MCP server of 2026 over HTTP at <base>/mcp, answering as
  `mcp-reply` does with `opts`. With :oauth ({:cimd? :dcr? :expires-in}),
  it wants a token it gave: a 401 says where its authorization server is
  (itself: metadata, /token taking the code \"good-code\" and refresh
  tokens unless :refuse-refresh (an atom) says no, /register, /revoke).
  Returns {:url :base :requests :stop!}, the requests {:uri :headers
  :body} (the body as text)."
  [{:keys [oauth refuse-refresh] :as opts}]
  (let [base (promise)
        requests (atom [])
        tokens (atom #{})
        issued (atom 0)
        issue! (fn []
                 (let [n (swap! issued inc)]
                   (swap! tokens conj (str "at-" n))
                   (json-response {:access_token (str "at-" n) :refresh_token (str "rt-" n)
                                   :token_type "Bearer" :expires_in (:expires-in oauth 3600)})))
        server (start-handler!
                (fn [req]
                  (let [body (some-> (:body req) slurp)
                        b @base]
                    (swap! requests conj {:uri (:uri req) :headers (:headers req) :body body})
                    (case (:uri req)
                      "/mcp"
                      (cond
                        (and oauth (not (contains? @tokens (some-> (get-in req [:headers "authorization"])
                                                                   (str/replace #"^Bearer " "")))))
                        {:status 401
                         :headers {"WWW-Authenticate" (str "Bearer resource_metadata=\"" b
                                                           "/.well-known/oauth-protected-resource/mcp\", scope=\"tools\"")}}

                        (= :delete (:request-method req)) {:status 200}

                        :else
                        (if-let [r (mcp-reply opts (json/parse-string body true))]
                          (json-response r)
                          {:status 202}))

                      "/.well-known/oauth-protected-resource/mcp"
                      (json-response {:resource (str b "/mcp") :authorization_servers [b] :scopes_supported ["tools"]})

                      "/.well-known/oauth-authorization-server"
                      (if oauth (json-response (as-metadata b oauth)) {:status 404})

                      "/token"
                      (let [{:keys [grant_type code]} (oauth/form-decode body)]
                        (cond
                          (and (= "authorization_code" grant_type) (= "good-code" code)) (issue!)
                          (and (= "refresh_token" grant_type) (not (some-> refuse-refresh deref))) (issue!)
                          :else {:status 400 :headers {"Content-Type" "application/json"}
                                 :body "{\"error\":\"invalid_grant\"}"}))

                      "/register" (assoc (json-response {:client_id "registered-client"}) :status 201)
                      "/revoke" {:status 200}
                      {:status 404}))))]
    (deliver base (:url server))
    (assoc server :base (:url server) :url (str (:url server) "/mcp") :requests requests)))

(def ha-tools
  "Tools as Home Assistant's Assist API names them at /api/mcp/assist."
  [{:name "intent__HassTurnOn" :title "Turn on" :description "Turns on/opens/presses a device or entity."
    :inputSchema {:type "object" :properties {:name {:type "string"} :area {:type "string"}
                                              :domain {:type "array" :items {:type "string"}}}}
    :annotations {:title "Turn on" :readOnlyHint false :destructiveHint false :idempotentHint false
                  :openWorldHint false}}
   {:name "intent__HassTurnOff" :title "Turn off" :description "Turns off/closes a device or entity."
    :inputSchema {:type "object" :properties {:name {:type "string"} :area {:type "string"}
                                              :domain {:type "array" :items {:type "string"}}}}
    :annotations {:title "Turn off" :readOnlyHint false :destructiveHint false :idempotentHint true
                  :openWorldHint false}}
   {:name "light__HassLightSet" :title "Set a light" :description "Sets the brightness or color of a light."
    :inputSchema {:type "object" :properties {:name {:type "string"} :brightness {:type "integer"}}}
    :annotations {:title "Set a light" :readOnlyHint false :destructiveHint false :idempotentHint true
                  :openWorldHint false}}
   {:name "homeassistant__GetLiveContext" :title "Get live context"
    :description "Provides real-time information about the CURRENT state of devices."
    :inputSchema {:type "object" :properties {}}
    :annotations {:title "Get live context" :readOnlyHint true :openWorldHint false}}])

(defn ^:private ha-mcp
  "What Home Assistant's MCP server answers `req`, the Streamable HTTP of
  2025 it speaks: stateless, JSON only, -32601 (and 200) for what it
  doesn't know, 202 for notifications."
  [req tools calls token]
  (let [msg (try (json/parse-string (:body req) true) (catch Exception _ nil))
        ok #(json-response {:jsonrpc "2.0" :id (:id msg) :result %})]
    (cond
      (not (str/includes? (str (get-in req [:headers "accept"])) "application/json")) {:status 400 :body "Accept"}
      (not (contains? msg :id)) {:status 202}
      :else
      (case (:method msg)
        "initialize" (ok {:protocolVersion "2025-06-18"
                          :capabilities {:tools {:listChanged false}}
                          :serverInfo {:name "home-assistant" :version "2026.10.0"}})
        "ping" (ok {})
        "tools/list" (ok {:tools tools})
        "tools/call" (do (swap! calls conj {:uri (:uri req) :token token
                                            :name (get-in msg [:params :name])
                                            :arguments (get-in msg [:params :arguments])})
                         (ok {:content [{:type "text" :text (json/generate-string {:success true :result "done"})}]
                              :isError false}))
        (json-response {:jsonrpc "2.0" :id (:id msg) :error {:code -32601 :message "Method not found"}})))))

(defn start-ha!
  "Starts a fake Home Assistant, as its source has it: its sign-in
  (/auth/authorize answering 302 to the redirect_uri with a code, its
  client id any address whose host and port are the redirect's;
  /auth/token taking that code with its PKCE verifier, and refresh tokens,
  giving no new one; /auth/revoke), /api/config (location_name \"Home\"),
  and its MCP server at /api/mcp/assist (prefixed tools) and /api/mcp (as
  older ones: unprefixed). Every /api/* wants a token it gave or the
  long-lived \"llat-1\". `switches` (an atom of a set) changes it: :no-assist
  (an older one: /api/mcp/assist is 404), :no-mcp (no MCP server set up:
  both 404), :forbidden (/api/mcp only for admins: 403). With :expires-in
  its tokens' life in seconds. Returns {:url :requests :calls :revoked
  :switches :stop!}: requests {:method :uri :query :headers :body} (body as
  text), calls {:uri :token :name :arguments}, revoked tokens."
  [& [{:keys [expires-in] :or {expires-in 1800}}]]
  (let [requests (atom [])
        calls (atom [])
        revoked (atom [])
        switches (atom #{})
        codes (atom {})
        access (atom #{"llat-1"})
        refresh (atom {})
        issued (atom 0)
        coded (atom 0)
        unauthorized (fn [base]
                       {:status 401
                        :headers {"WWW-Authenticate" (str "Bearer resource_metadata=\"" base
                                                          "/.well-known/oauth-protected-resource\"")}
                        :body "401: Unauthorized"})
        bad (fn [error] {:status 400 :headers {"Content-Type" "application/json"}
                         :body (json/generate-string {:error error})})
        base (promise)
        server (start-handler!
                (fn [req]
                  (let [body (some-> (:body req) slurp)
                        req (assoc req :body body)
                        token (some-> (get-in req [:headers "authorization"]) (str/replace #"^Bearer " ""))
                        mcp #(ha-mcp req % calls token)]
                    (swap! requests conj {:method (:request-method req) :uri (:uri req) :query (:query-string req)
                                          :headers (:headers req) :body body})
                    (cond
                      (= "/auth/authorize" (:uri req))
                      (let [{:keys [response_type client_id redirect_uri state code_challenge code_challenge_method]}
                            (oauth/form-decode (:query-string req))
                            authority #(let [u (java.net.URI. (str %))] [(.getScheme u) (.getHost u) (.getPort u)])]
                        (if (and (= "code" response_type) (= "S256" code_challenge_method)
                                 (= (authority client_id) (authority redirect_uri)))
                          (let [code (str "code-" (swap! coded inc))]
                            (swap! codes assoc code {:client-id client_id :challenge code_challenge})
                            {:status 302 :headers {"Location" (str redirect_uri "?" (oauth/form-encode {"code" code "state" state}))}})
                          {:status 400 :body "Invalid redirect URI"}))

                      (= "/auth/token" (:uri req))
                      (let [{:keys [grant_type code client_id code_verifier refresh_token]} (oauth/form-decode body)
                            issue! (fn [refresh?]
                                     (let [n (swap! issued inc)]
                                       (swap! access conj (str "at-" n))
                                       (when refresh? (swap! refresh assoc (str "rt-" n) client_id))
                                       (json-response (cond-> {:access_token (str "at-" n) :token_type "Bearer"
                                                               :expires_in expires-in}
                                                        refresh? (assoc :refresh_token (str "rt-" n))))))]
                        (case grant_type
                          "authorization_code"
                          (let [[before] (swap-vals! codes dissoc code)
                                {:keys [client-id challenge]} (get before code)]
                            (if (and client-id (= client-id client_id) code_verifier (= challenge (oauth/challenge code_verifier)))
                              (issue! true)
                              (bad "invalid_request")))
                          "refresh_token"
                          (if (and refresh_token (= client_id (get @refresh refresh_token)))
                            (issue! false)
                            (bad "invalid_grant"))
                          (bad "unsupported_grant_type")))

                      (= "/auth/revoke" (:uri req))
                      (do (swap! revoked conj (:token (oauth/form-decode body)))
                          (swap! refresh dissoc (:token (oauth/form-decode body)))
                          {:status 200 :body ""})

                      (not (str/starts-with? (:uri req) "/api/")) {:status 404 :body "404: Not Found"}
                      (not (contains? @access token)) (unauthorized @base)

                      (= "/api/config" (:uri req))
                      (json-response {:location_name "Home" :version "2026.10.0" :internal_url nil})

                      (#{"/api/mcp/assist" "/api/mcp"} (:uri req))
                      (cond
                        (contains? @switches :no-mcp)
                        {:status 404 :headers {"Content-Type" "text/plain"} :body "Model Context Protocol server is not configured"}
                        (and (= "/api/mcp/assist" (:uri req)) (contains? @switches :no-assist))
                        {:status 404 :headers {"Content-Type" "text/plain"} :body "404: Not Found"}
                        (and (= "/api/mcp" (:uri req)) (contains? @switches :forbidden))
                        {:status 403 :headers {"Content-Type" "application/json"} :body "{\"message\":\"Unauthorized\"}"}
                        (= "/api/mcp/assist" (:uri req)) (mcp ha-tools)
                        :else (mcp (mapv #(update % :name (fn [n] (last (str/split n #"__")))) ha-tools)))

                      :else {:status 404 :body "404: Not Found"}))))]
    (deliver base (:url server))
    (assoc server :requests requests :calls calls :revoked revoked :switches switches)))
