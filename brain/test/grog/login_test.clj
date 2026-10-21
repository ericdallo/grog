(ns grog.login-test
  (:require [babashka.http-client :as http-client]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.client :as client]
            [grog.config :as config]
            [grog.credentials :as credentials]
            [grog.oauth :as oauth]
            [grog.providers.openai.subscription :as subscription]
            [grog.server :as server]
            [grog.test-helpers :as h]))

(defn ^:private start-server [auth-url state-dir callback-port]
  (server/start! (-> config/defaults
                     (assoc :provider "openai" :state-dir state-dir)
                     (assoc-in [:server :port] 0)
                     (assoc-in [:providers :openai :api-key-env] "GROG_TEST_UNSET_VAR")
                     (assoc-in [:providers :openai :subscription :auth-url] auth-url)
                     (assoc-in [:providers :openai :subscription :base-url] (str auth-url "/codex"))
                     (assoc-in [:providers :openai :subscription :callback-port] callback-port))))

(defn ^:private api [method url]
  (let [resp (http-client/request (cond-> {:method method :uri url :throw false}
                                    (#{:post :delete} method)
                                    (assoc :headers {"content-type" "application/json"} :body "{}")))]
    [(:status resp) (config/parse (:body resp))]))

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

        (let [[status {:keys [url redirect-uri]}] (api :post (str base "/v1/auth/openai/login"))
              params (oauth/form-decode (second (str/split url #"\?" 2)))]
          (is (= 200 status))
          (testing "keys in lowerCamelCase"
            (is (str/includes? (:body (http-client/post (str base "/v1/auth/openai/login")
                                                        {:headers {"content-type" "application/json"} :body "{}"}))
                               "\"redirectUri\":"))
            (is (str/includes? (:body (http-client/get (str base "/v1/auth/openai"))) "\"signedIn\":false")))
          (is (= (format "http://localhost:%d/auth/callback" port) redirect-uri))
          (is (= redirect-uri (:redirect_uri params)))

          (testing "a callback with another state is refused"
            (is (str/includes? (:body (http-client/get (str callback "?code=c&state=forged")))
                               "doesn't belong"))
            (is (= {:signed-in false :login "pending"} (second (api :get (str base "/v1/auth/openai"))))))

          (testing "the right callback signs in"
            (is (str/includes? (:body (http-client/get (str callback "?code=the-code&state=" (:state params))))
                               "Signed in to grog"))
            (is (= {:signed-in true :login "done" :email "grog@example.com" :account-id "acct-1"}
                   (dissoc (second (api :get (str base "/v1/auth/openai"))) :expires-at)))
            (is (= "access-1" (:access-token (credentials/saved state-dir "openai"))))
            (let [[{:keys [body]}] @(:requests fake)]
              (is (= "the-code" (:code body)))
              (is (= (:code_challenge params) (oauth/challenge (:code_verifier body)))
                  "the verifier matches the challenge the browser got")))

          (testing "status shows the subscription"
            (is (= {:name "grog" :provider "openai" :auth "subscription" :model "gpt-6-luna"
                    :email "grog@example.com" :account-id "acct-1" :changed []}
                   (dissoc (second (api :get (str base "/v1/status"))) :expires-at :usage)))
            (let [out (with-out-str (client/status! base))]
              (is (str/includes? out "Name:     grog\n"))
              (is (str/includes? out "Auth:     subscription, signed in as grog@example.com"))
              (is (str/includes? out "Usage:    not known yet\n") "until it asks, or answers")))

          (testing "models, marking the one in use"
            (is (= "* gpt-6-luna   Fast.\n  gpt-6-astra\n" (with-out-str (client/models! base))))
            (subscription/reset-catalog!))

          (testing "signing out"
            (is (= {:signed-in false :login "done"} (second (api :delete (str base "/v1/auth/openai")))))
            (is (nil? (credentials/saved state-dir "openai")))))
        (finally
          (server/stop! srv))))))

(deftest login-again
  (h/with-fake [fake [(h/token-response "access-1" "refresh-1")]]
    (let [state-dir (h/temp-dir)
          port (h/free-port)
          srv (start-server (:url fake) state-dir port)
          base (str "http://127.0.0.1:" (:port srv))
          login! #(second (api :post (str base "/v1/auth/openai/login")))
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

(deftest cli-login-defaults-to-the-servers-provider
  (let [port (h/free-port)
        srv (start-server "http://127.0.0.1:1" (h/temp-dir) port)
        base (str "http://127.0.0.1:" (:port srv))
        result (future (let [out (java.io.StringWriter.)]
                         (binding [*out* out]
                           [(client/login! base nil) (str out)])))]
    (try
      (is (some (fn [_]
                  (Thread/sleep 100)
                  (= "pending" (:login (second (api :get (str base "/v1/auth/openai"))))))
                (range 50))
          "the CLI started signing in to the server's provider")
      ;; Asking again gives the link the CLI printed.
      (let [[_ {:keys [url]}] (api :post (str base "/v1/auth/openai/login"))
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
    (api :post (str "http://127.0.0.1:" (:port srv) "/v1/auth/openai/login"))
    (with-open [_ (java.net.Socket. "127.0.0.1" (int port))]
      (is true "the browser's way back is open"))
    (server/stop! srv)
    (is (thrown? java.net.ConnectException (java.net.Socket. "127.0.0.1" (int port))))))
