(ns grog.signin-test
  "Signing in to the ChatGPT subscription through the connect engine: with a
  code, in a browser, from the CLI, and the sign-in grog keeps up for its
  skin while it has nothing to pay with."
  (:require [babashka.http-client :as http-client]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.client :as client]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.connections :as connections]
            [grog.credentials :as credentials]
            [grog.events :as events]
            [grog.oauth :as oauth]
            [grog.providers.openai.oauth :as openai-oauth]
            [grog.providers.openai.subscription :as subscription]
            [grog.server :as server]
            [grog.signin :as signin]
            [grog.test-helpers :as h]))

(defn ^:private start-server
  ([auth-url state-dir callback-port]
   (start-server auth-url state-dir callback-port identity))
  ([auth-url state-dir callback-port tweak]
   (server/start! (-> config/defaults
                      (assoc :provider "openai" :state-dir state-dir)
                      (assoc-in [:server :port] 0)
                      (assoc :integrations {:host "127.0.0.1" :port 0})
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

(def ^:private browser {:way "browser"})

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

(defn ^:private watched
  "What whoever watches `srv` knows of the last session connecting
  something."
  [srv]
  (:connect (events/snapshot (:events (:ctx srv)))))

(defn ^:private connect-url [base]
  (str base "/v1/integrations/openai/connect"))

(deftest sign-in-with-a-code
  (h/with-fake [fake [(user-code "ABCD-12345") not-yet entered (h/token-response "access-1" "refresh-1")]]
    (let [state-dir (h/temp-dir)
          srv (start-server (:url fake) state-dir (h/free-port))
          base (str "http://127.0.0.1:" (:port srv))]
      (try
        (let [[status started] (api :post (connect-url base))
              now (System/currentTimeMillis)]
          (is (= 200 status))
          (is (= {:integration "openai" :title "ChatGPT" :heading "Sign in with ChatGPT" :way "device" :status "open"
                  :kept false}
                 (select-keys started [:integration :title :heading :way :status :kept])))
          (is (= {:kind "device-code" :code "ABCD-12345" :url (str (:url fake) "/codex/device")
                  :help openai-oauth/device-help}
                 (dissoc (:step started) :expires-at)))
          (is (<= (+ now (* 14 60000)) (get-in started [:step :expires-at]) (+ now (* 15 60000))) "good for 15 minutes")
          (is (re-matches #"http://127\.0\.0\.1:\d+/c/\w{10}" (:link started)) "and a page on the network")
          (testing "the same code, while it waits"
            (is (= (:step started) (:step (second (api :post (connect-url base))))))))

        (testing "whoever watches sees it"
          (is (= {:integration "openai" :status "open" :code "ABCD-12345"}
                 (let [w (watched srv)] {:integration (:integration w) :status (:status w) :code (get-in w [:step :code])}))))

        (testing "once entered, signed in"
          (is (eventually 10000 #(= "done" (:status (second (api :get (str base "/v1/connect")))))))
          (is (= "grog@example.com" (:connected (second (api :get (str base "/v1/connect"))))))
          (is (= "access-1" (:access-token (credentials/saved state-dir "openai"))))
          (let [[usercode pending ok token] @(:requests fake)]
            (is (= "/api/accounts/deviceauth/usercode" (:uri usercode)))
            (is (= {:device_auth_id "dev-ABCD-12345" :user_code "ABCD-12345"} (:body pending) (:body ok)))
            (is (= {:grant_type "authorization_code" :code "the-code" :code_verifier "the-verifier"
                    :redirect_uri (str (:url fake) "/deviceauth/callback")}
                   (select-keys (:body token) [:grant_type :code :code_verifier :redirect_uri])))))

        (testing "whoever watches sees it done, grog paid by the subscription, and the connection"
          (is (= {:status "done" :connected "grog@example.com"} (select-keys (watched srv) [:status :connected])))
          (let [state (events/snapshot (:events (:ctx srv)))]
            (is (= "subscription" (:auth state)))
            (is (= [{:id "openai" :name "grog@example.com" :status "connected"}]
                   (map #(dissoc % :added-at) (:connections (first (:integrations state))))))))
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
          (is (re-find #"Or open http://127\.0\.0\.1:\d+/c/\w{10} there: grog's page has it all\." out))
          (is (str/includes? out "Signed in as grog@example.com: grog now uses your subscription.")))
        (finally
          (server/stop! srv))))))

(deftest a-code-expires
  (with-redefs [signin/device-ttl-ms 1500]
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

(deftest a-sign-in-to-show-while-grog-has-nothing-to-pay-with
  (testing "with a skin to show it: grog opens it itself"
    (h/with-fake [fake [(user-code "SKIN-1")]]
      (let [srv (start-server (:url fake) (h/temp-dir) (h/free-port) #(assoc-in % [:skin :dir] (h/temp-dir)))]
        (try
          (is (eventually 5000 #(= "SKIN-1" (get-in (watched srv) [:step :code]))))
          (is (true? (:kept (watched srv))))
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
          (is (nil? (watched srv)))
          (finally
            (server/stop! srv))))))
  (testing "nor without a skin: `grog login` gets it"
    (h/with-fake [fake []]
      (let [srv (start-server (:url fake) (h/temp-dir) (h/free-port))]
        (try
          (Thread/sleep 500)
          (is (empty? @(:requests fake)))
          (finally
            (server/stop! srv))))))
  (testing "nor once its sign-in stopped working, when an API key pays"
    (h/with-fake [fake []]
      (let [state-dir (h/temp-dir)
            _ (credentials/save! state-dir "openai" {:access-token "a" :email "grog@example.com"})
            _ (credentials/signed-out! state-dir "openai")
            srv (start-server (:url fake) state-dir (h/free-port)
                              #(-> %
                                   (assoc-in [:skin :dir] (h/temp-dir))
                                   (assoc-in [:providers :openai :api-key-env] "HOME")))]
        (try
          (Thread/sleep 500)
          (is (nil? (watched srv)))
          (is (= "signed-out" (:status (connections/by-id state-dir "openai"))) "it says it needs signing in again")
          (finally
            (server/stop! srv)))))))

(deftest when-signing-in-with-a-code-fails
  (testing "no code"
    (let [srv (start-server "http://127.0.0.1:1" (h/temp-dir) (h/free-port))
          base (str "http://127.0.0.1:" (:port srv))]
      (try
        (let [[status body] (api :post (connect-url base))]
          (is (= 400 status))
          (is (str/starts-with? (:error body) "Couldn't reach OpenAI's sign-in server")))
        (is (= "failed" (:status (second (api :get (str base "/v1/connect"))))))
        (finally
          (server/stop! srv)))))
  (testing "asking whether it was entered keeps failing"
    (with-redefs [connect/poll-failures-max 2]
      (h/with-fake [fake [(user-code "X-1") {:status 500 :body "Boom."} {:status 500 :body "Boom."}]]
        (let [srv (start-server (:url fake) (h/temp-dir) (h/free-port))
              base (str "http://127.0.0.1:" (:port srv))]
          (try
            (api :post (connect-url base))
            (is (eventually 10000 #(= "failed" (:status (second (api :get (str base "/v1/connect")))))))
            (is (= "OpenAI's sign-in server returned 500: Boom." (:error (second (api :get (str base "/v1/connect"))))))
            (finally
              (server/stop! srv)))))))
  (testing "neither a code nor a browser"
    (let [srv (start-server "http://127.0.0.1:1" (h/temp-dir) (h/free-port))]
      (try
        (let [[status body] (api :post (connect-url (str "http://127.0.0.1:" (:port srv))) {:way "pigeon"})]
          (is (= 400 status))
          (is (= "ChatGPT connects device or browser, not \"pigeon\"" (:error body))))
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

        (let [[status {:keys [step]}] (api :post (connect-url base) browser)
              params (oauth/form-decode (second (str/split (:url step) #"\?" 2)))]
          (is (= 200 status))
          (testing "keys in lowerCamelCase"
            (is (str/includes? (:body (http-client/post (connect-url base)
                                                        {:headers {"content-type" "application/json"}
                                                         :body "{\"way\":\"browser\"}"}))
                               "\"backTo\":")))
          (is (= (format "http://localhost:%d/auth/callback" port) (:back-to step)))
          (is (= (:back-to step) (:redirect_uri params)))

          (testing "a callback with another state is refused"
            (is (str/includes? (:body (http-client/get (str callback "?code=c&state=forged")))
                               "doesn't belong"))
            (is (= "open" (:status (second (api :get (str base "/v1/connect")))))))

          (testing "the right callback signs in"
            (is (str/includes? (:body (http-client/get (str callback "?code=the-code&state=" (:state params))))
                               "Signed in to grog"))
            (is (= {:status "done" :connected "grog@example.com"}
                   (select-keys (second (api :get (str base "/v1/connect"))) [:status :connected])))
            (is (= "access-1" (:access-token (credentials/saved state-dir "openai"))))
            (let [[{:keys [body]}] @(:requests fake)]
              (is (= "the-code" (:code body)))
              (is (= (:code_challenge params) (oauth/challenge (:code_verifier body)))
                  "the verifier matches the challenge the browser got")))

          (testing "whoever watches never sees where the browser signs in, which carries its state"
            (is (not (str/includes? (pr-str (events/snapshot (:events (:ctx srv)))) (:state params)))))

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

          (testing "what's connected"
            (let [out (with-out-str (client/integrations! base))]
              (is (re-find #"ChatGPT \(grog connect openai\)\n  openai +grog@example\.com, since \d{4}-\d\d-\d\d\n" out))
              (is (str/includes? out "Computers (grog connect computer)\n  nothing connected\n"))))

          (testing "signing out"
            (is (= "Signed out of openai.\n" (with-out-str (client/logout! base nil))))
            (is (nil? (credentials/saved state-dir "openai")))
            (is (= "none" (:auth (events/snapshot (:events (:ctx srv))))))))
        (finally
          (server/stop! srv))))))

(deftest sign-in-again
  (h/with-fake [fake [(h/token-response "access-1" "refresh-1")]]
    (let [state-dir (h/temp-dir)
          port (h/free-port)
          srv (start-server (:url fake) state-dir port)
          base (str "http://127.0.0.1:" (:port srv))
          sign-in! #(:step (second (api :post (connect-url base) browser)))
          state-of #(:state (oauth/form-decode (second (str/split (:url %) #"\?" 2))))
          callback #(:body (http-client/get (format "http://127.0.0.1:%d/auth/callback?%s" port %)))]
      (try
        (let [first-sign-in (sign-in!)]
          (testing "while the browser is away, the same link"
            (is (= first-sign-in (sign-in!))))

          (testing "which still signs in"
            (is (str/includes? (callback (str "code=the-code&state=" (state-of first-sign-in))) "Signed in to grog"))
            (is (= 1 (count @(:requests fake))))
            (is (= "access-1" (:access-token (credentials/saved state-dir "openai")))))

          (testing "once it's over, a new sign-in"
            ;; Where the browser came back to, free again.
            (is (eventually 5000 #(try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] false)
                                       (catch java.net.ConnectException _ true))))
            (let [second-sign-in (sign-in!)]
              (is (not= (state-of first-sign-in) (state-of second-sign-in)))
              (is (str/includes? (callback (str "error=access_denied&error_description=Denied&state="
                                                (state-of second-sign-in)))
                                 "Denied"))
              (is (= {:status "failed" :error "Denied"}
                     (select-keys (second (api :get (str base "/v1/connect"))) [:status :error])))
              (is (= "access-1" (:access-token (credentials/saved state-dir "openai")))
                  "the saved credentials are kept"))))
        (finally
          (server/stop! srv))))))

(deftest unknown-integrations
  (let [srv (start-server "http://127.0.0.1:1" (h/temp-dir) (h/free-port))
        base (str "http://127.0.0.1:" (:port srv))]
    (try
      (is (= [400 {:error "There's no integration called \"nope\" (there's openai, computer)."}]
             (api :post (str base "/v1/integrations/nope/connect"))))
      (is (= [400 {:error "There's no connection called \"nope\" (grog integrations lists them)."}]
             (api :delete (str base "/v1/connections/nope"))))
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
      (is (eventually 5000 #(= "oauth" (get-in (second (api :get (str base "/v1/connect"))) [:step :kind])))
          "the CLI started signing in to the server's provider")
      ;; Asking again gives the link the CLI printed.
      (let [[_ {:keys [step]}] (api :post (connect-url base) browser)
            state (:state (oauth/form-decode (second (str/split (:url step) #"\?" 2))))]
        (http-client/get (format "http://127.0.0.1:%d/auth/callback?error=access_denied&state=%s" port state)))
      (let [[ok? out] (deref result 5000 [:timeout ""])]
        (is (false? ok?))
        (is (str/includes? out "Sign in with your ChatGPT account at:"))
        (is (re-find #"or paste the address it ended at on grog's page, http://127\.0\.0\.1:\d+/c/\w{10}" out))
        (is (str/includes? out "[error] Sign-in failed: access_denied")))
      (finally
        (future-cancel result)
        (server/stop! srv)))))

(deftest stopping-the-server-ends-sign-ins
  (let [port (h/free-port)
        srv (start-server "http://127.0.0.1:1" (h/temp-dir) port)]
    (api :post (connect-url (str "http://127.0.0.1:" (:port srv))) browser)
    (with-open [_ (java.net.Socket. "127.0.0.1" (int port))]
      (is true "the browser's way back is open"))
    (server/stop! srv)
    (is (eventually 5000 #(try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] false)
                               (catch java.net.ConnectException _ true))))))
