(ns grog.providers.openai.oauth
  "Signing in to a ChatGPT subscription with the Codex CLI's OAuth client:
  with a code entered on any other device (`codex login --device-auth`), or
  the way ECA does it (PKCE, with the browser coming back to a localhost
  callback); and keeping the tokens fresh."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.credentials :as credentials]
            [grog.oauth :as oauth]))

;; The Codex CLI's OAuth client.
(def client-id "app_EMoamEEZ73f0CkXaXp7hrann")

(defn ^:private now-seconds []
  (quot (System/currentTimeMillis) 1000))

(defn redirect-uri
  "Where the browser comes back after signing in. It's registered with OpenAI
  for this client: the port is configurable for tests only."
  [cfg]
  (format "http://localhost:%d/auth/callback" (get-in cfg [:subscription :callback-port])))

(defn authorize-url
  "Where the browser signs in."
  [cfg {:keys [challenge state redirect-uri]}]
  (str (get-in cfg [:subscription :auth-url]) "/oauth/authorize?"
       (oauth/form-encode [[:client_id client-id]
                           [:response_type "code"]
                           [:redirect_uri redirect-uri]
                           [:scope "openid profile email offline_access"]
                           [:id_token_add_organizations "true"]
                           [:prompt "login"]
                           [:codex_cli_simplified_flow "true"]
                           [:code_challenge challenge]
                           [:code_challenge_method "S256"]
                           [:state state]])))

(defn ^:private error-message [body raw]
  (let [error (:error body)]
    (or (when (map? error) (:message error))
        (:error_description body)
        error
        raw)))

(defn ^:private token-request! [cfg params]
  (let [resp (try
               (http/post (str (get-in cfg [:subscription :auth-url]) "/oauth/token")
                          {:headers {"content-type" "application/json"}
                           :body (json/generate-string params)
                           :throw false
                           :timeout 30000})
               (catch java.io.IOException e
                 (throw (ex-info (str "Couldn't reach OpenAI's sign-in server: "
                                      (or (ex-message e) (.getName (class e))))
                                 {:type ::network-error}))))
        body (try (json/parse-string (:body resp) true) (catch Exception _ nil))]
    (if (and (= 200 (:status resp)) (:access_token body))
      body
      (throw (ex-info (format "OpenAI's sign-in server returned %d: %s"
                              (:status resp) (error-message body (:body resp)))
                      {:type ::token-error :status (:status resp)})))))

(defn ^:private account-id [claims]
  (or (get claims "chatgpt_account_id")
      (get-in claims ["https://api.openai.com/auth" "chatgpt_account_id"])
      (get-in claims ["organizations" 0 "id"])))

(defn ^:private ->credentials [body previous]
  (let [id-claims (oauth/jwt-claims (:id_token body))
        access-claims (oauth/jwt-claims (:access_token body))]
    {:type "chatgpt"
     :access-token (:access_token body)
     ;; Refresh tokens are single use: keep the new one.
     :refresh-token (or (:refresh_token body) (:refresh-token previous))
     :account-id (or (account-id id-claims) (account-id access-claims) (:account-id previous))
     :email (or (get id-claims "email") (:email previous))
     :expires-at (+ (now-seconds) (or (:expires_in body) 3600))}))

(defn exchange-code!
  "Trades the code the browser brought back for credentials."
  [cfg {:keys [code verifier redirect-uri]}]
  (->credentials (token-request! cfg {:grant_type "authorization_code"
                                      :client_id client-id
                                      :code code
                                      :code_verifier verifier
                                      :redirect_uri redirect-uri})
                 nil))

(defn device-url
  "Where whoever signs in with a code (`device-code!`) enters it."
  [cfg]
  (str (get-in cfg [:subscription :auth-url]) "/codex/device"))

;; Each ChatGPT account allows signing in with a code once, in the settings of
;; ChatGPT's website: its apps don't have it, and phones open its links in
;; them.
(def device-help
  {:text "ChatGPT has to allow it once, on its website on a computer (its app doesn't have it): in Settings, Security and login, turn on “Enable device code sign-in for Codex, Excel, PowerPoint, and Word”."
   :url "https://chatgpt.com/#settings/Security"})

(defn ^:private device-request!
  "A POST to OpenAI's API for signing in with a code: [status body raw-body]."
  [cfg path params]
  (try
    (let [resp (http/post (str (get-in cfg [:subscription :auth-url]) "/api/accounts/deviceauth/" path)
                          {:headers {"content-type" "application/json"}
                           :body (json/generate-string params)
                           :throw false
                           :timeout 30000})]
      [(:status resp) (try (json/parse-string (:body resp) true) (catch Exception _ nil)) (:body resp)])
    (catch java.io.IOException e
      (throw (ex-info (str "Couldn't reach OpenAI's sign-in server: "
                           (or (ex-message e) (.getName (class e))))
                      {:type ::network-error})))))

(defn ^:private seconds
  "Seconds OpenAI sent, as a number or a string; `default` without them."
  [x default]
  (or (cond (number? x) (long x)
            (string? x) (parse-long (str/trim x)))
      default))

(defn device-code!
  "Starts signing in with a code entered on another device, like `codex
  login --device-auth`: {:device-auth-id :user-code :interval-s :url}, the
  code being good for 15 minutes."
  [cfg]
  (let [[status body raw] (device-request! cfg "usercode" {:client_id client-id})
        code (or (:user_code body) (:usercode body))]
    (cond
      (and (= 200 status) (:device_auth_id body) code)
      {:device-auth-id (:device_auth_id body)
       :user-code code
       :interval-s (max 1 (seconds (:interval body) 5))
       :url (device-url cfg)}

      (= 404 status)
      (throw (ex-info "OpenAI doesn't offer signing in with a code here: use `grog login --browser`."
                      {:type ::device-unsupported :status status}))

      :else
      (throw (ex-info (format "OpenAI's sign-in server returned %d: %s" status (error-message body raw))
                      {:type ::token-error :status status})))))

(defn poll-device!
  "Whether the code of `device` (`device-code!`) was entered and allowed: nil
  until it is, then what `exchange-code!` trades for credentials, {:code
  :verifier :redirect-uri}."
  [cfg {:keys [device-auth-id user-code]}]
  (let [[status body raw] (device-request! cfg "token" {:device_auth_id device-auth-id :user_code user-code})]
    (cond
      (and (= 200 status) (:authorization_code body))
      {:code (:authorization_code body)
       :verifier (:code_verifier body)
       :redirect-uri (str (get-in cfg [:subscription :auth-url]) "/deviceauth/callback")}

      ;; Not entered, or not allowed, yet.
      (#{403 404} status)
      nil

      :else
      (throw (ex-info (format "OpenAI's sign-in server returned %d: %s" status (error-message body raw))
                      {:type ::token-error :status status})))))

(defn refresh! [cfg credentials]
  (->credentials (token-request! cfg {:grant_type "refresh_token"
                                      :client_id client-id
                                      :refresh_token (:refresh-token credentials)})
                 credentials))

(def ^:private refresh-lock (Object.))

(defn fresh-credentials!
  "The saved credentials, refreshed and saved again when they expire within a
  minute, or when `force?` (the backend rejected them)."
  [{:keys [state-dir] :as cfg} force?]
  (locking refresh-lock
    (let [credentials (credentials/saved state-dir "openai")]
      (if (or force? (<= (or (:expires-at credentials) 0) (+ (now-seconds) 60)))
        (let [fresh (try
                      (refresh! cfg credentials)
                      (catch clojure.lang.ExceptionInfo e
                        ;; The refresh token expired or was revoked (e.g. by
                        ;; signing out of ChatGPT everywhere).
                        (throw (if (#{400 401} (:status (ex-data e)))
                                 (ex-info (str "The ChatGPT sign-in is no longer valid (" (ex-message e)
                                               "). Sign in again: grog login openai")
                                          {:type ::signed-out}
                                          e)
                                 e))))]
          (credentials/save! state-dir "openai" fresh)
          fresh)
        credentials))))
