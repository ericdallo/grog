(ns grog.login-test
  (:require [babashka.http-client :as http-client]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.client :as client]
            [grog.config :as config]
            [grog.credentials :as credentials]
            [grog.events :as events]
            [grog.login :as login]
            [grog.oauth :as oauth]
            [grog.providers.openai.oauth :as openai-oauth]
            [grog.providers.openai.subscription :as subscription]
            [grog.server :as server]
            [grog.test-helpers :as h]))

(defn ^:private start-server
  ([auth-url state-dir callback-port]
   (start-server auth-url state-dir callback-port identity))
  ([auth-url state-dir callback-port tweak]
   (server/start! (-> config/defaults
                      (assoc :provider "openai" :state-dir state-dir)
                      (assoc-in [:server :port] 0)
                      (assoc-in [:providers :openai :api-key-env] "GROG_TEST_UNSET_VAR")
                      (assoc-in [:providers :openai :subscription :auth-url] auth-url)
                      (assoc-in [:providers :openai :subscription :base-url] (str auth-url "/codex"))
                      (assoc-in [:providers :openai :subscription :callback-port] callback-port)
                      tweak))))

(defn ^:private api
  ([method url]
   (api method url {}))
  ([method url params]
   (let [resp (http-client/request (cond-> {:method method :uri url :throw false}
                                     (#{:post :delete} method)
                                     (assoc :headers {"content-type" "application/json"}
                                            :body (config/generate params))))]
     [(:status resp) (config/parse (:body resp))])))

(def ^:private browser {:method "browser"})

(defn ^:private eventually
  "Whether `f` returns something truthy within `ms`, trying every 100 ms."
  [ms f]
  (loop [left ms]
    (or (f)
        (when (pos? left)
          (Thread/sleep 100)
          (recur (- left 100))))))

(defn ^:private user-code
  "What OpenAI answers when asked for a code to sign in with."
  [code]
  (h/json-response {:device_auth_id (str "dev-" code) :user_code code :interval "1"}))

;; Asked whether the code was entered: not yet.
(def ^:private not-yet
  {:status 403 :headers {"Content-Type" "application/json"} :body "{\"error\":{\"message\":\"Pending.\"}}"})

;; It was, and allowed.
(def ^:private entered
  (h/json-response {:authorization_code "the-code" :code_challenge "challenge" :code_verifier "the-verifier"}))

(defn ^:private watched-login
  "What whoever watches `srv` knows of the last sign-in."
  [srv]
  (:login (events/snapshot (:events (:ctx srv)))))

(deftest sign-in-with-a-code
  (h/with-fake [fake [(user-code "ABCD-12345") not-yet entered (h/token-response "access-1" "refresh-1")]]
    (let [state-dir (h/temp-dir)
          srv (start-server (:url fake) state-dir (h/free-port))
          base (str "http://127.0.0.1:" (:port srv))]
      (try
        (let [[status started] (api :post (str base "/v1/auth/openai/login"))
              now (System/currentTimeMillis)]
          (is (= 200 status))
          (is (= {:status "pending" :method "device" :label "ChatGPT" :code "ABCD-12345"
                  :url (str (:url fake) "/codex/device") :help openai-oauth/device-help}
                 (dissoc started :expires-at)))
          (is (<= (+ now (* 14 60000)) (:expires-at started) (+ now (* 15 60000))) "good for 15 minutes")
          (testing "the same code, while it waits"
            (is (= started (second (api :post (str base "/v1/auth/openai/login")))))))

        (testing "whoever watches sees it"
          (is (= {:provider "openai" :status "pending" :method "device" :code "ABCD-12345"}
                 (select-keys (watched-login srv) [:provider :status :method :code]))))
        (testing "and so does the status"
          (is (= {:signed-in false :login "pending" :method "device" :code "ABCD-12345"}
                 (select-keys (second (api :get (str base "/v1/auth/openai"))) [:signed-in :login :method :code]))))

        (testing "once entered, signed in"
          (is (eventually 10000 #(= "done" (:login (second (api :get (str base "/v1/auth/openai")))))))
          (is (= "access-1" (:access-token (credentials/saved state-dir "openai"))))
          (let [[usercode pending ok token] @(:requests fake)]
            (is (= "/api/accounts/deviceauth/usercode" (:uri usercode)))
            (is (= {:device_auth_id "dev-ABCD-12345" :user_code "ABCD-12345"} (:body pending) (:body ok)))
            (is (= {:grant_type "authorization_code" :code "the-code" :code_verifier "the-verifier"
                    :redirect_uri (str (:url fake) "/deviceauth/callback")}
                   (select-keys (:body token) [:grant_type :code :code_verifier :redirect_uri])))))

        (testing "whoever watches sees it done, and grog paid by the subscription"
          (is (= {:provider "openai" :status "done" :method "device" :label "ChatGPT"} (watched-login srv)))
          (is (= "subscription" (:auth (events/snapshot (:events (:ctx srv)))))))
        (finally
          (server/stop! srv))))))

(deftest grog-login-with-a-code
  (h/with-fake [fake [(user-code "CLI-1") not-yet entered (h/token-response "access-1" "refresh-1")]]
    (let [srv (start-server (:url fake) (h/temp-dir) (h/free-port))
          base (str "http://127.0.0.1:" (:port srv))
          out (java.io.StringWriter.)]
      (try
        (is (true? (binding [*out* out] (client/login! base nil))))
        (let [out (str out)]
          (is (str/includes? out (str "open\n\n  " (:url fake) "/codex/device\n")))
          (is (str/includes? out "enter the code (good for 15 minutes):\n\n  CLI-1\n"))
          (is (str/includes? out "PowerPoint, and Word”.\n  https://chatgpt.com/#settings/Security\n"))
          (is (str/includes? out "Signed in as grog@example.com: grog now uses your subscription.")))
        (finally
          (server/stop! srv))))))

(deftest a-code-expires
  (with-redefs [login/device-ttl-ms 1500]
    (h/with-fake [fake [(user-code "OLD-1") not-yet (user-code "NEW-2") entered (h/token-response "access-1" "refresh-1")]]
      (let [srv (start-server (:url fake) (h/temp-dir) (h/free-port))
            base (str "http://127.0.0.1:" (:port srv))
            out (java.io.StringWriter.)]
        (try
          (is (true? (binding [*out* out] (client/login! base nil))))
          (let [out (str out)]
            (is (str/includes? out "  OLD-1\n"))
            (is (str/includes? out "That code expired: enter this one instead, NEW-2\n"))
            (is (str/includes? out "Signed in as grog@example.com")))
          (finally
            (server/stop! srv)))))))

(deftest a-code-to-show-while-grog-has-nothing-to-pay-with
  (testing "with a skin to show it"
    (h/with-fake [fake [(user-code "SKIN-1")]]
      (let [srv (start-server (:url fake) (h/temp-dir) (h/free-port) #(assoc-in % [:skin :dir] (h/temp-dir)))]
        (try
          (is (eventually 5000 #(= "SKIN-1" (:code (watched-login srv)))))
          (finally
            (server/stop! srv))))))
  (testing "not with an API key"
    (h/with-fake [fake []]
      (let [srv (start-server (:url fake) (h/temp-dir) (h/free-port)
                              #(-> %
                                   (assoc-in [:skin :dir] (h/temp-dir))
                                   (assoc-in [:providers :openai :api-key-env] "HOME")))]
        (try
          (Thread/sleep 500)
          (is (empty? @(:requests fake)))
          (is (nil? (watched-login srv)))
          (finally
            (server/stop! srv))))))
  (testing "nor without a skin: `grog login` gets it"
    (h/with-fake [fake []]
      (let [srv (start-server (:url fake) (h/temp-dir) (h/free-port))]
        (try
          (Thread/sleep 500)
          (is (empty? @(:requests fake)))
          (finally
            (server/stop! srv)))))))

(deftest when-signing-in-with-a-code-fails
  (testing "no code"
    (let [srv (start-server "http://127.0.0.1:1" (h/temp-dir) (h/free-port))
          base (str "http://127.0.0.1:" (:port srv))]
      (try
        (let [[status body] (api :post (str base "/v1/auth/openai/login"))]
          (is (= 400 status))
          (is (str/starts-with? (:error body) "Couldn't reach OpenAI's sign-in server")))
        (is (= {:signed-in false :login "error" :method "device"}
               (dissoc (second (api :get (str base "/v1/auth/openai"))) :error)))
        (finally
          (server/stop! srv)))))
  (testing "asking whether it was entered keeps failing"
    (with-redefs [login/poll-failures-max 2]
      (h/with-fake [fake [(user-code "X-1") {:status 500 :body "Boom."} {:status 500 :body "Boom."}]]
        (let [srv (start-server (:url fake) (h/temp-dir) (h/free-port))
              base (str "http://127.0.0.1:" (:port srv))]
          (try
            (api :post (str base "/v1/auth/openai/login"))
            (is (eventually 10000 #(= "error" (:login (second (api :get (str base "/v1/auth/openai")))))))
            (is (= "OpenAI's sign-in server returned 500: Boom." (:error (second (api :get (str base "/v1/auth/openai"))))))
            (finally
              (server/stop! srv)))))))
  (testing "neither a code nor a browser"
    (let [srv (start-server "http://127.0.0.1:1" (h/temp-dir) (h/free-port))]
      (try
        (let [[status body] (api :post (str "http://127.0.0.1:" (:port srv) "/v1/auth/openai/login")
                                 {:method "pigeon"})]
          (is (= 400 status))
          (is (str/includes? (:error body) "\"device\" or \"browser\"")))
        (finally
          (server/stop! srv))))))

(deftest sign-in-flow
  (h/with-fake [fake [(h/token-response "access-1" "refresh-1")
                      (h/json-response {:models [{:slug "gpt-6-luna" :description "Fast."}
                                                 {:slug "gpt-6-astra"}]})]]
    (let [state-dir (h/temp-dir)
          port (h/free-port)
          srv (start-server (:url fake) state-dir port)
          base (str "http://127.0.0.1:" (:port srv))
          callback (format "http://127.0.0.1:%d/auth/callback" port)]
      (try
        (testing "not signed in"
          (is (= "none" (:auth (second (api :get (str base "/v1/status"))))))
          (is (str/includes? (with-out-str (client/status! base)) "Auth:     none: No OpenAI credentials")))

        (let [[status {:keys [url redirect-uri]}] (api :post (str base "/v1/auth/openai/login") browser)
              params (oauth/form-decode (second (str/split url #"\?" 2)))]
          (is (= 200 status))
          (testing "keys in lowerCamelCase"
            (is (str/includes? (:body (http-client/post (str base "/v1/auth/openai/login")
                                                        {:headers {"content-type" "application/json"}
                                                         :body "{\"method\":\"browser\"}"}))
                               "\"redirectUri\":"))
            (is (str/includes? (:body (http-client/get (str base "/v1/auth/openai"))) "\"signedIn\":false")))
          (is (= (format "http://localhost:%d/auth/callback" port) redirect-uri))
          (is (= redirect-uri (:redirect_uri params)))

          (testing "a callback with another state is refused"
            (is (str/includes? (:body (http-client/get (str callback "?code=c&state=forged")))
                               "doesn't belong"))
            (is (= {:signed-in false :login "pending" :method "browser"} (second (api :get (str base "/v1/auth/openai"))))))

          (testing "the right callback signs in"
            (is (str/includes? (:body (http-client/get (str callback "?code=the-code&state=" (:state params))))
                               "Signed in to grog"))
            (is (= {:signed-in true :login "done" :method "browser" :email "grog@example.com" :account-id "acct-1"}
                   (dissoc (second (api :get (str base "/v1/auth/openai"))) :expires-at)))
            (is (= "access-1" (:access-token (credentials/saved state-dir "openai"))))
            (let [[{:keys [body]}] @(:requests fake)]
              (is (= "the-code" (:code body)))
              (is (= (:code_challenge params) (oauth/challenge (:code_verifier body)))
                  "the verifier matches the challenge the browser got")))

          (testing "whoever watches never sees the link, which carries its state"
            (is (= {:provider "openai" :status "done" :method "browser" :label "ChatGPT"} (watched-login srv))))

          (testing "status shows the subscription"
            (is (= {:name "grog" :provider "openai" :auth "subscription" :model "gpt-6-luna"
                    :email "grog@example.com" :account-id "acct-1" :changed []}
                   (dissoc (second (api :get (str base "/v1/status"))) :expires-at :usage :revision :busy :update)))
            (let [out (with-out-str (client/status! base))]
              (is (str/includes? out "Name:     grog\n"))
              (is (str/includes? out "Auth:     subscription, signed in as grog@example.com"))
              (is (str/includes? out "Usage:    not known yet\n") "until it asks, or answers")))

          (testing "models, marking the one in use"
            (is (= "* gpt-6-luna   Fast.\n  gpt-6-astra\n" (with-out-str (client/models! base))))
            (subscription/reset-catalog!))

          (testing "signing out"
            (is (= {:signed-in false :login "done" :method "browser"} (second (api :delete (str base "/v1/auth/openai")))))
            (is (nil? (credentials/saved state-dir "openai")))))
        (finally
          (server/stop! srv))))))

(deftest login-again
  (h/with-fake [fake [(h/token-response "access-1" "refresh-1")]]
    (let [state-dir (h/temp-dir)
          port (h/free-port)
          srv (start-server (:url fake) state-dir port)
          base (str "http://127.0.0.1:" (:port srv))
          login! #(second (api :post (str base "/v1/auth/openai/login") browser))
          state-of #(:state (oauth/form-decode (second (str/split (:url %) #"\?" 2))))
          callback #(:body (http-client/get (format "http://127.0.0.1:%d/auth/callback?%s" port %)))]
      (try
        (let [first-login (login!)]
          (testing "while the browser is away, the same link"
            (is (= first-login (login!))))

          (testing "which still signs in"
            (is (str/includes? (callback (str "code=the-code&state=" (state-of first-login)))
                               "Signed in to grog"))
            (is (= 1 (count @(:requests fake))))
            (is (= "access-1" (:access-token (credentials/saved state-dir "openai")))))

          (testing "once it ended, a new sign-in"
            (let [second-login (login!)]
              (is (not= (state-of first-login) (state-of second-login)))
              (is (str/includes? (callback (str "error=access_denied&error_description=Denied&state="
                                                (state-of second-login)))
                                 "Denied"))
              (is (= {:signed-in true :login "error" :error "Denied"}
                     (select-keys (second (api :get (str base "/v1/auth/openai")))
                                  [:signed-in :login :error]))
                  "the saved credentials are kept"))))
        (finally
          (server/stop! srv))))))

(deftest unknown-provider
  (let [srv (start-server "http://127.0.0.1:1" (h/temp-dir) (h/free-port))
        base (str "http://127.0.0.1:" (:port srv))]
    (try
      (doseq [[method path] [[:post "/v1/auth/nope/login"] [:get "/v1/auth/nope"] [:delete "/v1/auth/nope"]]]
        (let [[status body] (api method (str base path))]
          (is (= 400 status))
          (is (= "Unknown provider \"nope\"; available: openai." (:error body)))))
      (finally
        (server/stop! srv)))))

(deftest grog-login-in-a-browser
  (let [port (h/free-port)
        srv (start-server "http://127.0.0.1:1" (h/temp-dir) port)
        base (str "http://127.0.0.1:" (:port srv))
        result (future (let [out (java.io.StringWriter.)]
                         (binding [*out* out]
                           [(client/login! base nil true) (str out)])))]
    (try
      (is (eventually 5000 #(= "pending" (:login (second (api :get (str base "/v1/auth/openai"))))))
          "the CLI started signing in to the server's provider")
      ;; Asking again gives the link the CLI printed.
      (let [[_ {:keys [url]}] (api :post (str base "/v1/auth/openai/login") browser)
            state (:state (oauth/form-decode (second (str/split url #"\?" 2))))]
        (http-client/get (format "http://127.0.0.1:%d/auth/callback?error=access_denied&state=%s" port state)))
      (let [[ok? out] (deref result 5000 [:timeout ""])]
        (is (false? ok?))
        (is (str/includes? out "Sign in with your ChatGPT account at:"))
        (is (str/includes? out "[error] Sign-in failed: access_denied")))
      (finally
        (future-cancel result)
        (server/stop! srv)))))

(deftest stopping-the-server-ends-sign-ins
  (let [port (h/free-port)
        srv (start-server "http://127.0.0.1:1" (h/temp-dir) port)]
    (api :post (str "http://127.0.0.1:" (:port srv) "/v1/auth/openai/login") browser)
    (with-open [_ (java.net.Socket. "127.0.0.1" (int port))]
      (is true "the browser's way back is open"))
    (server/stop! srv)
    (is (thrown? java.net.ConnectException (java.net.Socket. "127.0.0.1" (int port))))))
