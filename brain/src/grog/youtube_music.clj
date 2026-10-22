(ns grog.youtube-music
  "YouTube Music as an integration (grog.integrations): grog plays it
  without an account, through its player (grog.player, grog.music), and
  with the user's Google account connected, their playlists and liked songs
  too. Connecting signs in with Google by a code (a device-code step, RFC
  8628), entered at Google's page on any phone or computer, two ways:

    google      with grog's own client, shipped by the project in
                resources/grog/google-client.json ({\"clientId\",
                \"clientSecret\"}: an installed-app client of type \"TVs and
                Limited Input devices\", whose secret Google doesn't treat
                as confidential); only offered when it's there
    own-client  with a client the user made, entered in a form first

  The connection, `id`, is named by the account's email. Its secrets: the
  tokens (:access-token :refresh-token :expires-at, in epoch ms, :scope
  :token-type), the client they're of (:client-id :client-secret), the
  :way and the :email. `auth!` hands them to the player, refreshed first
  when they expire within a minute; a refused refresh signs it out.
  Disconnecting revokes them, if Google can be reached, and forgets them."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.connections :as connections]
            [grog.log :as log]
            [grog.oauth :as oauth]
            [grog.player :as player]))

(def id
  "The integration's id, and its connection's."
  "youtube-music")

(def endpoints
  "Google's, for signing in with a code and keeping it up. Tests redefine
  it."
  {:device-code "https://oauth2.googleapis.com/device/code"
   :token "https://oauth2.googleapis.com/token"
   :revoke "https://oauth2.googleapis.com/revoke"})

(def scope
  "What grog asks Google for: YouTube, and who the account is."
  "https://www.googleapis.com/auth/youtube openid email")

(def device-ttl-ms
  "How long Google's codes are good for (30 minutes). Tests redefine it."
  (* 30 60 1000))

(def slow-down-ms
  "How much longer grog waits between polls each time Google says to slow
  down. Tests redefine it."
  5000)

;; Refreshing, one at a time.
(def ^:private refresh-lock (Object.))

(defn ^:private now []
  (System/currentTimeMillis))

(defn shipped-client
  "grog's own Google client, {:client-id :client-secret}, or nil when the
  project doesn't ship one (or it can't be read, logged without what's in
  it). Tests redefine it."
  []
  (when-let [url (io/resource "grog/google-client.json")]
    (try
      (let [{:keys [client-id client-secret]} (config/parse (slurp url))]
        (when (and (config/non-blank-string? client-id) (config/non-blank-string? client-secret))
          {:client-id client-id :client-secret client-secret}))
      (catch Exception e
        (log/info "youtube-music: ignoring google-client.json:" (.getSimpleName (class e)))
        nil))))

(defn ^:private post!
  "A form POST to Google: [status body], the body as Google names it."
  [url params]
  (try
    (let [resp (http/post url {:headers {"content-type" "application/x-www-form-urlencoded"}
                               :body (oauth/form-encode params)
                               :throw false
                               :timeout 30000})]
      [(:status resp) (try (json/parse-string (:body resp) true) (catch Exception _ nil))])
    (catch java.io.IOException e
      (throw (ex-info (str "Couldn't reach Google: " (or (ex-message e) (.getSimpleName (class e))))
                      {:type ::unreachable})))))

(defn ^:private error-of [status body]
  (let [{:keys [error error_description]} (when (map? body) body)]
    (or (cond (and error error_description) (str error ": " error_description)
              error (str error))
        (str "status " status))))

(defn ^:private code!
  "A code to sign in with `client`: {:user-code :url :interval-s :device-code
  :slow}, :slow how much longer to wait between polls."
  [client]
  (let [[status body] (post! (:device-code endpoints) {:client_id (:client-id client) :scope scope})
        interval (:interval body)]
    (if (and (= 200 status) (:device_code body) (:user_code body))
      {:device-code (:device_code body)
       :user-code (:user_code body)
       :url (or (:verification_url body) (:verification_uri body) "https://www.google.com/device")
       :interval-s (max 1 (if (number? interval) (long interval) 5))
       :slow (atom 0)}
      (throw (ex-info (str "Google gave no code (" (error-of status body) ")") {:type ::refused :status status})))))

(defn ^:private poll!
  "Whether the code was entered, for the session `generation`: nil until
  then, then Google's tokens. Denied there, the session is; expired, a new
  code comes."
  [ctx generation client {:keys [device-code slow]}]
  (Thread/sleep (long @slow))
  (let [[status body] (post! (:token endpoints) {:client_id (:client-id client)
                                                 :client_secret (:client-secret client)
                                                 :device_code device-code
                                                 :grant_type "urn:ietf:params:oauth:grant-type:device_code"})]
    (if (and (= 200 status) (:access_token body))
      body
      (case (:error body)
        "authorization_pending" nil
        "slow_down" (do (swap! slow + slow-down-ms) nil)
        "access_denied" (do (connect/deny! ctx generation) nil)
        "expired_token" (do (connect/change! ctx generation #(assoc-in % [:step :expires-at] 0)) nil)
        (throw (ex-info (str "Google refused it (" (error-of status body) ")") {:type ::refused :status status}))))))

(defn ^:private tokens
  "What Google's token `body` gives, over the `previous` tokens: Google
  only sends a refresh token the first time."
  [body previous]
  {:access-token (:access_token body)
   :refresh-token (or (:refresh_token body) (:refresh-token previous))
   :expires-at (+ (now) (* 1000 (long (or (:expires_in body) 3600))))
   :scope (or (:scope body) (:scope previous))
   :token-type (or (:token_type body) (:token-type previous))})

(defn ^:private finish!
  "Keeps what Google gave for `client` as the connection: the account's
  email, what got connected."
  [config client way body]
  (let [email (get (oauth/jwt-claims (:id_token body)) "email")]
    (connections/save! (config/state-dir config)
                       {:id id :integration id :name (or email "YouTube Music")}
                       (merge (tokens body nil)
                              (select-keys client [:client-id :client-secret])
                              {:way way :email email}))
    (log/info "youtube-music: connected")
    (or email "your Google account")))

(defn ^:private sign-in!
  "Takes the session `generation` through signing in with a code, with
  `client`."
  [ctx generation client way]
  (connect/device-code! ctx generation {:code! #(code! client)
                                        :poll! #(poll! ctx generation client %)
                                        :finish! #(finish! @(:config ctx) client way %)
                                        :ttl-ms device-ttl-ms
                                        :note "Signing in…"}))

(def ^:private own-client-fields
  [{:id "client-id"
    :label "Client ID"
    :example "1234-abc.apps.googleusercontent.com"
    :description (str "Make one in Google Cloud's console: enable the YouTube Data API v3, set up the OAuth "
                      "consent screen (published, or with you as a test user), then in APIs & Services, "
                      "Credentials, create an OAuth client ID of type TVs and Limited Input devices.")}
   {:id "client-secret" :label "Client secret" :secret true}])

(defn ^:private start!
  "How connecting YouTube Music starts, each way."
  [ctx {:keys [generation]} way]
  (case way
    "google" (sign-in! ctx generation (or (shipped-client)
                                          (throw (ex-info "grog has no Google client of its own here: use your own"
                                                          {:type ::no-client})))
                       way)
    "own-client" (connect/form! ctx generation
                                {:fields own-client-fields
                                 :submit! (fn [values]
                                            (sign-in! ctx generation {:client-id (get values "client-id")
                                                                      :client-secret (get values "client-secret")}
                                                      way))})))

(defn ^:private connections-of
  "The account connected, if there's one."
  [config]
  (if-let [connection (connections/by-id (config/state-dir config) id)]
    [(select-keys connection [:id :name :status :added-at])]
    []))

(defn connected?
  "Whether the user's account is connected, and working, with `config`."
  [config]
  (= "connected" (:status (connections/by-id (config/state-dir config) id))))

(defn ^:private disconnect!
  "Revokes the account's tokens, if Google can be reached, and forgets
  them."
  [ctx _]
  (let [dir (config/state-dir @(:config ctx))
        {:keys [refresh-token access-token]} (connections/secrets dir id)]
    (when-let [token (or refresh-token access-token)]
      (try
        (let [[status] (post! (:revoke endpoints) {:token token})]
          (when-not (= 200 status)
            (log/info "youtube-music: Google didn't revoke the tokens: status" status)))
        (catch Exception e
          (log/info "youtube-music: couldn't revoke the tokens:" (.getSimpleName (class e))))))
    (connections/remove! dir id)
    (log/info "youtube-music: disconnected")
    {:id id :disconnected true}))

(defn offered?
  "Whether grog offers connecting it with `config`: with a player to play it
  (grog.player), or while it's connected, to disconnect it."
  [config]
  (or (player/there? config)
      (some? (connections/by-id (config/state-dir config) id))))

(defn integration
  "The YouTube Music integration (grog.integrations)."
  []
  {:id id
   :title "YouTube Music"
   :heading "Connect YouTube Music"
   :about "your playlists and liked songs; it plays without it too"
   :icon "music"
   :ways (cond-> []
           (shipped-client) (conj {:id "google" :label "Sign in with a code instead"})
           true (conj {:id "own-client" :label "Use a Google client of your own instead"}))
   :start! start!
   :connections connections-of
   :disconnect! disconnect!})

(defn ^:private refresh!
  "New tokens for `secrets`, kept. Throws ex-info when Google refuses
  (the connection is signed out then) or can't be reached."
  [dir {:keys [refresh-token client-id client-secret email] :as secrets}]
  (let [[status body] (if refresh-token
                        (post! (:token endpoints) {:grant_type "refresh_token"
                                                   :client_id client-id
                                                   :client_secret client-secret
                                                   :refresh_token refresh-token})
                        [400 {:error "invalid_grant"}])]
    (cond
      (and (= 200 status) (:access_token body))
      (let [fresh (merge secrets (tokens body secrets))]
        (connections/save! dir {:id id :integration id :name (or email "YouTube Music")} fresh)
        fresh)

      (or (= "invalid_grant" (:error body)) (= 401 status))
      (do (connections/set-status! dir id "signed-out")
          (log/info "youtube-music: signed out, its refresh was refused")
          (throw (ex-info "the YouTube Music account was signed out: connect it again (grog connect youtube-music)"
                          {:type ::signed-out})))

      :else
      (throw (ex-info (str "Google didn't refresh the YouTube Music sign-in (" (error-of status body) ")")
                      {:type ::refresh-failed})))))

(defn auth!
  "The account's tokens for grog's player, with `config`: {:access-token
  :refresh-token :expires-at :client-id :client-secret :scope :token-type},
  refreshed first when they expire within a minute; nil when it isn't
  connected. Throws ex-info when Google refuses to refresh them (signing it
  out) or can't be reached."
  [config]
  (locking refresh-lock
    (let [dir (config/state-dir config)
          secrets (when (connected? config) (connections/secrets dir id))]
      (when (:access-token secrets)
        (select-keys (if (<= (or (:expires-at secrets) 0) (+ (now) 60000))
                       (refresh! dir secrets)
                       secrets)
                     [:access-token :refresh-token :expires-at :client-id :client-secret :scope :token-type])))))
