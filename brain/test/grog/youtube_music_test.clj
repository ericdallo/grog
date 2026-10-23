(ns grog.youtube-music-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.connections :as connections]
            [grog.integrations :as integrations]
            [grog.oauth :as oauth]
            [grog.test-helpers :as h]
            [grog.youtube-music :as youtube-music]))

(def ^:private client {:client-id "grog-client" :client-secret "grog-secret"})

(defn ^:private context
  ([] (context "/run/music.sock"))
  ([socket]
   (assistant/new-context (-> config/defaults
                              (assoc :state-dir (h/temp-dir) :music {:socket socket :provider nil})
                              (update :integrations merge {:host "127.0.0.1" :port 0})))))

(defn ^:private state-dir [ctx]
  (config/state-dir @(:config ctx)))

(defn ^:private error [status error & [description]]
  {:status status :headers {"Content-Type" "application/json"}
   :body (json/generate-string (cond-> {:error error} description (assoc :error_description description)))})

(defn ^:private start-google!
  "A fake Google: its device codes (refusing the client \"bad\"), its token
  endpoint answering the device code's polls with the next of `polls`
  (:pending, :slow-down, :denied, :expired, or :tokens), refreshing
  unless `refuse` (an atom) says no, and revoking. Returns {:url :requests
  :stop! :endpoints}, the requests {:uri :body} (the form decoded)."
  [polls refuse]
  (let [requests (atom [])
        queue (atom polls)
        server (h/start-handler!
                (fn [req]
                  (let [body (oauth/form-decode (slurp (:body req)))]
                    (swap! requests conj {:uri (:uri req) :body body})
                    (case (:uri req)
                      "/device/code"
                      (if (= "bad" (:client_id body))
                        (error 401 "invalid_client" "The OAuth client was not found.")
                        (h/json-response {:device_code "dev-1" :user_code "ABCD-EFGH"
                                          :verification_url "https://www.google.com/device"
                                          :expires_in 1800 :interval 1}))

                      "/token"
                      (if (= "refresh_token" (:grant_type body))
                        (if @refuse
                          (error 400 "invalid_grant" "Token has been expired or revoked.")
                          (h/json-response {:access_token "at-2" :expires_in 3599 :token_type "Bearer"
                                            :scope youtube-music/scope}))
                        (let [[pending] (swap-vals! queue rest)]
                          (case (first pending)
                            :pending (error 428 "authorization_pending")
                            :slow-down (error 403 "slow_down")
                            :denied (error 403 "access_denied")
                            :expired (error 400 "expired_token")
                            :tokens (h/json-response {:access_token "at-1" :refresh_token "rt-1" :expires_in 3599
                                                      :token_type "Bearer" :scope youtube-music/scope
                                                      :id_token (h/jwt {"email" "greg@example.com"})})
                            (error 428 "authorization_pending"))))

                      "/revoke" {:status 200 :body ""}
                      {:status 404}))))]
    (assoc server
           :requests requests
           :endpoints {:device-code (str (:url server) "/device/code")
                       :token (str (:url server) "/token")
                       :revoke (str (:url server) "/revoke")})))

(defn ^:private session [ctx]
  (connect/view @(:connect ctx)))

(defn ^:private polls-of [google]
  (filterv #(= "urn:ietf:params:oauth:grant-type:device_code" (get-in % [:body :grant_type])) @(:requests google)))

(deftest what-it-is
  (testing "with grog's own client: a code, the usual way, or the user's own"
    (with-redefs [youtube-music/shipped-client (constantly client)]
      (let [{:keys [title heading about icon ways]} (youtube-music/integration)]
        (is (= ["YouTube Music" "Connect YouTube Music" "music"] [title heading icon]))
        (is (= "your playlists and liked songs; it plays without it too" about))
        (is (= ["google" "own-client"] (mapv :id ways))))))
  (testing "without it, only the user's own"
    (with-redefs [youtube-music/shipped-client (constantly nil)]
      (is (= ["own-client"] (mapv :id (:ways (youtube-music/integration)))))))
  (testing "it comes from grog's environment, never from the repository"
    (is (= (when (and (System/getenv "GROG_GOOGLE_CLIENT_ID") (System/getenv "GROG_GOOGLE_CLIENT_SECRET"))
             {:client-id (System/getenv "GROG_GOOGLE_CLIENT_ID")
              :client-secret (System/getenv "GROG_GOOGLE_CLIENT_SECRET")})
           (youtube-music/shipped-client))))
  (testing "offered with a player to play it"
    (is (some #{"youtube-music"} (map :id (integrations/all @(:config (context))))))
    (let [ctx (context nil)]
      (is (not-any? #{"youtube-music"} (map :id (integrations/all @(:config ctx)))))
      (testing "or while it's connected, to disconnect it"
        (connections/save! (state-dir ctx) {:id "youtube-music" :integration "youtube-music" :name "a"} {})
        (is (some #{"youtube-music"} (map :id (integrations/all @(:config ctx)))))))))

(deftest connecting-with-grogs-client
  (let [google (start-google! [:pending :slow-down :tokens] (atom false))
        ctx (context)]
    (try
      (with-redefs [youtube-music/endpoints (:endpoints google)
                    youtube-music/shipped-client (constantly client)
                    youtube-music/slow-down-ms 10]
        (let [opened (integrations/open! ctx "youtube-music" nil)]
          (testing "a code on the card, entered at Google's page"
            (is (= "google" (:way opened)))
            (is (= {:kind "device-code" :code "ABCD-EFGH" :url "https://www.google.com/device"}
                   (select-keys (:step opened) [:kind :code :url])))
            (is (= {:client_id "grog-client" :scope "https://www.googleapis.com/auth/youtube openid email"}
                   (:body (first @(:requests google))))))
          (testing "asked until it's entered, slowing down when told"
            (is (h/eventually 15000 #(= "done" (:status (session ctx)))))
            (is (= "greg@example.com" (:connected (session ctx))))
            (is (= 3 (count (polls-of google))))
            (is (= {:client_id "grog-client" :client_secret "grog-secret" :device_code "dev-1"
                    :grant_type "urn:ietf:params:oauth:grant-type:device_code"}
                   (:body (first (polls-of google))))))
          (testing "the connection, named by the account, its secrets apart"
            (is (= {:id "youtube-music" :integration "youtube-music" :name "greg@example.com" :status "connected"}
                   (dissoc (connections/by-id (state-dir ctx) "youtube-music") :added-at)))
            (is (not (str/includes? (slurp (connections/file (state-dir ctx))) "at-1")))
            (let [secrets (connections/secrets (state-dir ctx) "youtube-music")]
              (is (= {:access-token "at-1" :refresh-token "rt-1" :scope youtube-music/scope :token-type "Bearer"
                      :client-id "grog-client" :client-secret "grog-secret" :way "google" :email "greg@example.com"}
                     (dissoc secrets :expires-at)))
              (is (> (:expires-at secrets) (+ (System/currentTimeMillis) 3500000))))
            (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions
                                               (connections/secrets-file (state-dir ctx) "youtube-music"))))))
          (testing "listed as connected"
            (is (= [{:id "youtube-music" :name "greg@example.com" :status "connected"}]
                   (map #(dissoc % :added-at)
                        (:connections (some #(when (= "youtube-music" (:id %)) %)
                                            (integrations/summary @(:config ctx))))))))))
      (finally
        (connect/stop! ctx)
        ((:stop! google))))))

(deftest connecting-with-the-users-client
  (let [google (start-google! [:tokens] (atom false))
        ctx (context)]
    (try
      (with-redefs [youtube-music/endpoints (:endpoints google)
                    youtube-music/shipped-client (constantly nil)]
        (let [opened (integrations/open! ctx "youtube-music" nil)
              submit #((get-in @(:connect ctx) [:handlers :submit "form"]) %)]
          (testing "a form for it first"
            (is (= "own-client" (:way opened)))
            (is (= [["client-id" nil] ["client-secret" true]]
                   (mapv (juxt :id :secret) (get-in opened [:step :fields])))))
          (testing "one Google doesn't know, said on the page"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Google gave no code \(invalid_client: The OAuth client was not found\.\)"
                                  (submit {:client-id "bad" :client-secret "x"})))
            (is (= "form" (get-in (session ctx) [:step :kind]))))
          (testing "then the same code"
            (submit {:client-id "mine" :client-secret "shh"})
            (is (= "device-code" (get-in (session ctx) [:step :kind])))
            (is (h/eventually 10000 #(= "done" (:status (session ctx)))))
            (is (= {:client_id "mine" :client_secret "shh" :device_code "dev-1"
                    :grant_type "urn:ietf:params:oauth:grant-type:device_code"}
                   (:body (first (polls-of google)))))
            (is (= {:client-id "mine" :client-secret "shh" :way "own-client"}
                   (select-keys (connections/secrets (state-dir ctx) "youtube-music") [:client-id :client-secret :way]))))))
      (finally
        (connect/stop! ctx)
        ((:stop! google))))))

(deftest refused-at-google
  (let [google (start-google! [:denied] (atom false))
        ctx (context)]
    (try
      (with-redefs [youtube-music/endpoints (:endpoints google)
                    youtube-music/shipped-client (constantly client)]
        (integrations/open! ctx "youtube-music" "google")
        (is (h/eventually 10000 #(= "denied" (:status (session ctx)))))
        (is (nil? (connections/by-id (state-dir ctx) "youtube-music"))))
      (finally
        (connect/stop! ctx)
        ((:stop! google))))))

(deftest a-code-that-expired-is-replaced
  (let [google (start-google! [:expired :tokens] (atom false))
        ctx (context)]
    (try
      (with-redefs [youtube-music/endpoints (:endpoints google)
                    youtube-music/shipped-client (constantly client)]
        (integrations/open! ctx "youtube-music" "google")
        (is (h/eventually 15000 #(= "done" (:status (session ctx)))))
        (is (= 2 (count (filter #(= "/device/code" (:uri %)) @(:requests google)))) "a new code"))
      (finally
        (connect/stop! ctx)
        ((:stop! google))))))

(defn ^:private connect-account! [ctx expires-at]
  (connections/save! (state-dir ctx) {:id "youtube-music" :integration "youtube-music" :name "greg@example.com"}
                     {:access-token "at-1" :refresh-token "rt-1" :expires-at expires-at :scope youtube-music/scope
                      :token-type "Bearer" :client-id "cid" :client-secret "cs" :way "google" :email "greg@example.com"}))

(deftest keeping-it-up
  (let [refuse (atom false)
        google (start-google! [] refuse)
        ctx (context)
        config @(:config ctx)]
    (try
      (with-redefs [youtube-music/endpoints (:endpoints google)]
        (testing "nothing without the account"
          (is (nil? (youtube-music/auth! config))))
        (testing "as they are, while good for more than a minute"
          (connect-account! ctx (+ (System/currentTimeMillis) 120000))
          (is (= "at-1" (:access-token (youtube-music/auth! config))))
          (is (= [] @(:requests google))))
        (testing "else refreshed, and kept, the refresh token too"
          (connect-account! ctx (+ (System/currentTimeMillis) 50000))
          (is (= {:access-token "at-2" :refresh-token "rt-1" :client-id "cid" :client-secret "cs"
                  :scope youtube-music/scope :token-type "Bearer"}
                 (dissoc (youtube-music/auth! config) :expires-at)))
          (is (= {:grant_type "refresh_token" :client_id "cid" :client_secret "cs" :refresh_token "rt-1"}
                 (:body (last @(:requests google)))))
          (is (= "at-2" (:access-token (connections/secrets (state-dir ctx) "youtube-music"))))
          (is (= "greg@example.com" (:name (connections/by-id (state-dir ctx) "youtube-music")))))
        (testing "refused: signed out"
          (reset! refuse true)
          (connect-account! ctx 0)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"signed out: connect it again"
                                (youtube-music/auth! config)))
          (is (= "signed-out" (:status (connections/by-id (state-dir ctx) "youtube-music"))))
          (is (not (youtube-music/connected? config)))
          (is (nil? (youtube-music/auth! config)))))
      (finally ((:stop! google))))))

(deftest disconnecting
  (let [google (start-google! [] (atom false))
        ctx (context)]
    (try
      (with-redefs [youtube-music/endpoints (:endpoints google)]
        (testing "revokes the tokens, and forgets them"
          (connect-account! ctx 0)
          (is (= {:id "youtube-music" :disconnected true} (integrations/disconnect! ctx "youtube-music")))
          (is (= {:uri "/revoke" :body {:token "rt-1"}} (last @(:requests google))))
          (is (nil? (connections/by-id (state-dir ctx) "youtube-music")))
          (is (not (fs/exists? (connections/secrets-file (state-dir ctx) "youtube-music"))))))
      (with-redefs [youtube-music/endpoints {:revoke "http://127.0.0.1:1/revoke"}]
        (testing "even when Google can't be reached"
          (connect-account! ctx 0)
          (is (= {:id "youtube-music" :disconnected true} (integrations/disconnect! ctx "youtube-music")))
          (is (nil? (connections/by-id (state-dir ctx) "youtube-music")))))
      (finally ((:stop! google))))))
