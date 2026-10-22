(ns grog.mcp-auth-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.mcp-auth :as mcp-auth]
            [grog.oauth :as oauth]
            [grog.test-helpers :refer [json-response start-handler!]])
  (:import (java.util Base64)))

(defn ^:private serve!
  "A fake server answering (routes base) by path: a response, or a function
  of the request. Returns {:url :requests :stop!}, the requests with their
  :uri :query :headers and :body as text."
  [routes]
  (let [base (promise)
        requests (atom [])
        server (start-handler!
                (fn [req]
                  (let [body (some-> (:body req) slurp)
                        req (assoc req :body body)]
                    (swap! requests conj (select-keys req [:request-method :uri :query-string :headers :body]))
                    (let [answer (get (routes @base) (:uri req))]
                      (cond
                        (fn? answer) (answer req)
                        answer answer
                        :else {:status 404 :body "Not found"})))))]
    (deliver base (:url server))
    (assoc server :requests requests)))

(defn ^:private with-server
  "Calls `f` with a fake server answering `routes` (see `serve!`)."
  [routes f]
  (let [server (serve! routes)]
    (try (f server)
         (finally ((:stop! server))))))

(defn ^:private as-metadata
  "Authorization server metadata for `issuer`, at `base`."
  [issuer base & {:as more}]
  (merge {:issuer issuer
          :authorization_endpoint (str base "/authorize")
          :token_endpoint (str base "/token")
          :code_challenge_methods_supported ["S256"]}
         more))

(defn ^:private ex-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn ^:private paths [server]
  (mapv :uri @(:requests server)))

(deftest canonical-resource
  (is (= "https://mcp.example.com/mcp" (mcp-auth/canonical-resource "HTTPS://MCP.Example.com/mcp/")))
  (is (= "https://mcp.example.com" (mcp-auth/canonical-resource "https://mcp.example.com")))
  (is (= "https://mcp.example.com/" (mcp-auth/canonical-resource "https://mcp.example.com/")))
  (is (= "https://mcp.example.com:8443/a/b" (mcp-auth/canonical-resource "https://mcp.example.com:8443/a/b?x=1#frag")))
  (is (= "https://mcp.example.com/mcp" (mcp-auth/canonical-resource "https://mcp.example.com:443/mcp"))))

(deftest discovers-through-the-challenge
  (with-server (fn [base]
                 {"/meta" (json-response {:resource (str base "/mcp")
                                          :authorization_servers [base]
                                          :scopes_supported ["files:read" "files:write"]})
                  "/.well-known/oauth-authorization-server"
                  (json-response (as-metadata base base :scopes_supported ["files:read" "offline_access"]))})
    (fn [s]
      (let [d (mcp-auth/discover! (str (:url s) "/mcp") {:resource-metadata (str (:url s) "/meta")})]
        (is (= (str (:url s) "/mcp") (:resource d)))
        (is (= (:url s) (:issuer d)))
        (is (= (str (:url s) "/token") (get-in d [:metadata "token_endpoint"])))
        (is (= "files:read files:write offline_access" (:scope d)) "all it supports, and offline_access")
        (is (= ["/meta" "/.well-known/oauth-authorization-server"] (paths s))))
      (testing "the challenge's scope wins"
        (is (= "files:read offline_access"
               (:scope (mcp-auth/discover! (str (:url s) "/mcp") {:resource-metadata (str (:url s) "/meta")
                                                                  :scope "files:read"}))))))))

(deftest discovers-through-the-well-known-addresses
  (testing "the endpoint's path first"
    (with-server (fn [base]
                   {"/.well-known/oauth-protected-resource/mcp" (json-response {:authorization_servers [base]})
                    "/.well-known/oauth-authorization-server" (json-response (as-metadata base base))})
      (fn [s]
        (let [d (mcp-auth/discover! (str (:url s) "/mcp/") nil)]
          (is (= (str (:url s) "/mcp") (:resource d)))
          (is (nil? (:scope d)) "nothing to ask for")))))
  (testing "then the root"
    (with-server (fn [base]
                   {"/.well-known/oauth-protected-resource" (json-response {:authorization_servers [base]})
                    "/.well-known/oauth-authorization-server" (json-response (as-metadata base base))})
      (fn [s]
        (is (= (:url s) (:issuer (mcp-auth/discover! (str (:url s) "/mcp") nil))))
        (is (= ["/.well-known/oauth-protected-resource/mcp" "/.well-known/oauth-protected-resource"]
               (take 2 (paths s)))))))
  (testing "nothing there"
    (with-server (constantly {})
      (fn [s]
        (is (= ::mcp-auth/no-metadata (ex-type #(mcp-auth/discover! (str (:url s) "/mcp") nil))))))))

(deftest authorization-server-metadata
  (testing "an issuer with a path: RFC 8414 first"
    (with-server (fn [base]
                   {"/.well-known/oauth-protected-resource" (json-response {:authorization_servers [(str base "/tenant1")]})
                    "/.well-known/oauth-authorization-server/tenant1" (json-response (as-metadata (str base "/tenant1") base))})
      (fn [s]
        (is (= (str (:url s) "/tenant1") (:issuer (mcp-auth/discover! (:url s) nil)))))))
  (testing "an issuer with a path: OpenID Connect, both ways"
    (doseq [path ["/.well-known/openid-configuration/tenant1" "/tenant1/.well-known/openid-configuration"]]
      (with-server (fn [base]
                     {"/.well-known/oauth-protected-resource" (json-response {:authorization_servers [(str base "/tenant1")]})
                      path (json-response (as-metadata (str base "/tenant1") base))})
        (fn [s]
          (is (= (str (:url s) "/tenant1") (:issuer (mcp-auth/discover! (:url s) nil))))
          (is (= path (last (paths s))))))))
  (testing "an issuer without a path: OpenID Connect after RFC 8414"
    (with-server (fn [base]
                   {"/.well-known/oauth-protected-resource" (json-response {:authorization_servers [base]})
                    "/.well-known/openid-configuration" (json-response (as-metadata base base))})
      (fn [s]
        (is (= (:url s) (:issuer (mcp-auth/discover! (:url s) nil))))
        (is (= ["/.well-known/oauth-protected-resource"
                "/.well-known/oauth-authorization-server"
                "/.well-known/openid-configuration"]
               (paths s))))))
  (testing "another issuer is refused"
    (with-server (fn [base]
                   {"/.well-known/oauth-protected-resource" (json-response {:authorization_servers [base]})
                    "/.well-known/oauth-authorization-server" (json-response (as-metadata "https://evil.example.com" base))})
      (fn [s]
        (is (= ::mcp-auth/bad-issuer (ex-type #(mcp-auth/discover! (:url s) nil)))))))
  (testing "no S256 is refused"
    (doseq [methods [nil ["plain"]]]
      (with-server (fn [base]
                     {"/.well-known/oauth-protected-resource" (json-response {:authorization_servers [base]})
                      "/.well-known/oauth-authorization-server"
                      (json-response (as-metadata base base :code_challenge_methods_supported methods))})
        (fn [s]
          (is (= ::mcp-auth/no-pkce (ex-type #(mcp-auth/discover! (:url s) nil))))))))
  (testing "metadata for another server is refused"
    (with-server (fn [base]
                   {"/.well-known/oauth-protected-resource" (json-response {:resource "https://elsewhere.example.com/mcp"
                                                                            :authorization_servers [base]})
                    "/.well-known/oauth-authorization-server" (json-response (as-metadata base base))})
      (fn [s]
        (is (= ::mcp-auth/bad-resource (ex-type #(mcp-auth/discover! (:url s) nil))))))))

(deftest iss-required
  (is (mcp-auth/iss-required? {:metadata {"authorization_response_iss_parameter_supported" true}}))
  (is (not (mcp-auth/iss-required? {:metadata {}}))))

(def ^:private redirect "https://ericdallo.github.io/grog/oauth/callback/")
(def ^:private client-id-url "https://ericdallo.github.io/grog/oauth/client.json")

(deftest registers
  (testing "the user's own client"
    (is (= {:client-id "mine" :client-secret nil :auth-method "none" :issuer "https://as" :how "own"}
           (mcp-auth/register! {:issuer "https://as" :metadata {"client_id_metadata_document_supported" true}}
                               {:client-id-url client-id-url :redirect-uri redirect :client {:client-id "mine"}})))
    (is (= "client_secret_basic"
           (:auth-method (mcp-auth/register! {:issuer "https://as" :metadata {}}
                                             {:client {:client-id "mine" :client-secret "s"}}))))
    (is (= "client_secret_post"
           (:auth-method (mcp-auth/register! {:issuer "https://as"
                                              :metadata {"token_endpoint_auth_methods_supported" ["client_secret_post"]}}
                                             {:client {:client-id "mine" :client-secret "s"}})))))
  (testing "grog's client ID metadata document, when the server takes them"
    (is (= {:client-id client-id-url :client-secret nil :auth-method "none" :issuer "https://as" :how "metadata-document"}
           (mcp-auth/register! {:issuer "https://as" :metadata {"client_id_metadata_document_supported" true
                                                                "registration_endpoint" "https://as/register"}}
                               {:client-id-url client-id-url :redirect-uri redirect}))))
  (testing "dynamic registration otherwise"
    (with-server (constantly {"/register" {:status 201 :headers {"Content-Type" "application/json"}
                                           :body (json/generate-string {:client_id "dyn-1"
                                                                        :client_secret "dyn-secret"
                                                                        :token_endpoint_auth_method "client_secret_post"})}})
      (fn [s]
        (is (= {:client-id "dyn-1" :client-secret "dyn-secret" :auth-method "client_secret_post"
                :issuer (:url s) :how "registered"}
               (mcp-auth/register! {:issuer (:url s) :metadata {"registration_endpoint" (str (:url s) "/register")}}
                                   {:client-id-url client-id-url :redirect-uri redirect})))
        (let [{:keys [headers body]} (first @(:requests s))]
          (is (str/starts-with? (get headers "content-type") "application/json"))
          (is (= {"client_name" "grog"
                  "redirect_uris" [redirect]
                  "grant_types" ["authorization_code" "refresh_token"]
                  "response_types" ["code"]
                  "token_endpoint_auth_method" "none"
                  "application_type" "web"
                  "client_uri" "https://github.com/ericdallo/grog"}
                 (json/parse-string body)))))))
  (testing "a registration refused"
    (with-server (constantly {"/register" {:status 400 :body "{\"error\":\"invalid_client_metadata\"}"}})
      (fn [s]
        (is (= ::mcp-auth/no-registration
               (ex-type #(mcp-auth/register! {:issuer (:url s) :metadata {"registration_endpoint" (str (:url s) "/register")}}
                                             {:redirect-uri redirect})))))))
  (testing "no way to register"
    (is (= ::mcp-auth/no-registration
           (ex-type #(mcp-auth/register! {:issuer "https://as" :metadata {}} {:client-id-url client-id-url :redirect-uri redirect}))))))

(defn ^:private query [url]
  (oauth/form-decode (second (str/split url #"\?" 2))))

(deftest authorize-url
  (let [d {:resource "https://mcp.example.com/mcp"
           :issuer "https://auth.example.com"
           :metadata {"authorization_endpoint" "https://auth.example.com/authorize"}
           :scope "files:read offline_access"}
        url (mcp-auth/authorize-url d {:client-id client-id-url} {:challenge "chal" :state "grognix.local~xyz" :redirect-uri redirect})]
    (is (str/starts-with? url "https://auth.example.com/authorize?"))
    (is (= {:response_type "code"
            :client_id client-id-url
            :redirect_uri redirect
            :state "grognix.local~xyz"
            :code_challenge "chal"
            :code_challenge_method "S256"
            :resource "https://mcp.example.com/mcp"
            :scope "files:read offline_access"}
           (query url)))
    (testing "a scope given, for a step up"
      (is (= "a b" (:scope (query (mcp-auth/authorize-url d {:client-id "c"} {:challenge "x" :state "s" :redirect-uri redirect
                                                                              :scope "a b"}))))))
    (testing "no scope, and an endpoint with a query"
      (let [url (mcp-auth/authorize-url (assoc d :scope nil :metadata {"authorization_endpoint" "https://auth.example.com/authorize?tenant=1"})
                                        {:client-id "c"} {:challenge "x" :state "s" :redirect-uri redirect})]
        (is (str/starts-with? url "https://auth.example.com/authorize?tenant=1&"))
        (is (not (contains? (query url) :scope)))))))

(defn ^:private token-server
  "A token endpoint answering `responses` in turn."
  [responses]
  (let [queue (atom responses)]
    (serve! (constantly {"/token" (fn [_] (let [[pending] (swap-vals! queue rest)] (first pending)))}))))

(defn ^:private discovery [base]
  {:resource "https://mcp.example.com/mcp"
   :issuer base
   :metadata {"token_endpoint" (str base "/token")}
   :scope "files:read"})

(defn ^:private basic-of [id secret]
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes (str id ":" secret) "UTF-8"))))

(deftest exchanges-the-code
  (testing "a public client"
    (let [s (token-server [(json-response {:access_token "at-1" :refresh_token "rt-1" :expires_in 3600 :token_type "Bearer"})])]
      (try
        (let [before (System/currentTimeMillis)
              auth (mcp-auth/exchange! (discovery (:url s)) {:client-id "c1" :auth-method "none"}
                                       {:code "code-1" :verifier "ver-1" :redirect-uri redirect})
              {:keys [headers body query-string]} (first @(:requests s))]
          (is (= {:issuer (:url s) :token-endpoint (str (:url s) "/token") :resource "https://mcp.example.com/mcp"
                  :scope "files:read" :client-id "c1" :client-secret nil :auth-method "none"
                  :access-token "at-1" :refresh-token "rt-1"}
                 (dissoc auth :expires-at)))
          (is (<= (+ before 3600000) (:expires-at auth) (+ (System/currentTimeMillis) 3600000)))
          (is (nil? query-string))
          (is (nil? (get headers "authorization")))
          (is (= {:grant_type "authorization_code" :code "code-1" :redirect_uri redirect :code_verifier "ver-1"
                  :resource "https://mcp.example.com/mcp" :client_id "c1"}
                 (oauth/form-decode body))))
        (finally ((:stop! s))))))
  (testing "a secret in the body"
    (let [s (token-server [(json-response {:access_token "at-1"})])]
      (try
        (let [auth (mcp-auth/exchange! (discovery (:url s)) {:client-id "c1" :client-secret "sec" :auth-method "client_secret_post"}
                                       {:code "code-1" :verifier "ver-1" :redirect-uri redirect})
              {:keys [headers body]} (first @(:requests s))]
          (is (nil? (:expires-at auth)))
          (is (nil? (:refresh-token auth)))
          (is (= "sec" (:client_secret (oauth/form-decode body))))
          (is (nil? (get headers "authorization"))))
        (finally ((:stop! s))))))
  (testing "a secret over HTTP Basic"
    (let [s (token-server [(json-response {:access_token "at-1"})])]
      (try
        (mcp-auth/exchange! (discovery (:url s)) {:client-id "c1" :client-secret "sec" :auth-method "client_secret_basic"}
                            {:code "code-1" :verifier "ver-1" :redirect-uri redirect})
        (let [{:keys [headers body]} (first @(:requests s))]
          (is (= (basic-of "c1" "sec") (get headers "authorization")))
          (is (not (contains? (oauth/form-decode body) :client_secret))))
        (finally ((:stop! s))))))
  (testing "a code refused"
    (let [s (token-server [{:status 400 :body "{\"error\":\"invalid_grant\"}"}])]
      (try
        (is (= ::mcp-auth/refused (ex-type #(mcp-auth/exchange! (discovery (:url s)) {:client-id "c1"}
                                                                {:code "c" :verifier "v" :redirect-uri redirect}))))
        (finally ((:stop! s)))))))

(defn ^:private auth-at [base]
  {:issuer base :token-endpoint (str base "/token") :resource "https://mcp.example.com/mcp" :scope "files:read"
   :client-id "c1" :client-secret nil :auth-method "none"
   :access-token "at-1" :refresh-token "rt-1" :expires-at 0})

(deftest refreshes
  (testing "a new refresh token"
    (let [s (token-server [(json-response {:access_token "at-2" :refresh_token "rt-2" :expires_in 60})])]
      (try
        (let [auth (mcp-auth/refresh! (auth-at (:url s)))]
          (is (= ["at-2" "rt-2"] [(:access-token auth) (:refresh-token auth)]))
          (is (< (System/currentTimeMillis) (:expires-at auth)))
          (is (= {:grant_type "refresh_token" :refresh_token "rt-1" :resource "https://mcp.example.com/mcp" :client_id "c1"}
                 (oauth/form-decode (:body (first @(:requests s)))))))
        (finally ((:stop! s))))))
  (testing "keeping the old refresh token"
    (let [s (token-server [(json-response {:access_token "at-2"})])]
      (try
        (let [auth (mcp-auth/refresh! (auth-at (:url s)))]
          (is (= ["at-2" "rt-1" nil] [(:access-token auth) (:refresh-token auth) (:expires-at auth)])))
        (finally ((:stop! s))))))
  (testing "refused"
    (doseq [status [400 401]]
      (let [s (token-server [{:status status :body "{\"error\":\"invalid_grant\"}"}])]
        (try
          (is (= ::mcp-auth/refused (ex-type #(mcp-auth/refresh! (auth-at (:url s))))))
          (finally ((:stop! s)))))))
  (testing "the server failing isn't a refusal"
    (let [s (token-server [{:status 503 :body "down"}])]
      (try
        (is (= ::mcp-auth/failed (ex-type #(mcp-auth/refresh! (auth-at (:url s))))))
        (finally ((:stop! s))))))
  (testing "unreachable"
    (let [s (token-server [])
          base (:url s)]
      ((:stop! s))
      (Thread/sleep 100)
      (is (= ::mcp-auth/unreachable (ex-type #(mcp-auth/refresh! (auth-at base))))))))

(deftest fresh
  (let [s (token-server (repeat (json-response {:access_token "at-2" :expires_in 3600})))
        auth (auth-at (:url s))]
    (try
      (is (= (assoc auth :expires-at nil) (mcp-auth/fresh! (assoc auth :expires-at nil) 0)) "without an expiry")
      (is (= "at-1" (:access-token (mcp-auth/fresh! (assoc auth :expires-at 61001) 1000))) "valid for over a minute")
      (is (= "at-2" (:access-token (mcp-auth/fresh! (assoc auth :expires-at 60000) 1000))) "valid for under a minute")
      (is (= "at-2" (:access-token (mcp-auth/fresh! (assoc auth :expires-at 500) 1000))) "expired")
      (is (= 2 (count @(:requests s))))
      (testing "without a refresh token"
        (let [auth (assoc auth :refresh-token nil)]
          (is (= "at-1" (:access-token (mcp-auth/fresh! (assoc auth :expires-at 30000) 1000))) "still valid")
          (is (= ::mcp-auth/refused (ex-type #(mcp-auth/fresh! (assoc auth :expires-at 500) 1000))) "expired")))
      (finally ((:stop! s))))))

(deftest step-up-scope
  (is (= "files:read files:write" (mcp-auth/step-up-scope "files:read" {:error "insufficient_scope" :scope "files:write"})))
  (is (= "files:read offline_access files:write"
         (mcp-auth/step-up-scope "files:read offline_access" {:scope "files:write files:read"})))
  (is (= "files:write" (mcp-auth/step-up-scope nil {:scope "files:write"})))
  (is (nil? (mcp-auth/step-up-scope "files:read" {:error "insufficient_scope"}))))
