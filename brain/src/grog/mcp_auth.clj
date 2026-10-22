(ns grog.mcp-auth
  "Signing in to remote MCP servers (MCP 2026-07-28 authorization,
  plans/integrations.md): finding the server's authorization server
  (`discover!`, RFC 9728 and RFC 8414), getting a client there
  (`register!`: the user's own, grog's client ID metadata document, or
  dynamic registration), where the browser signs in (`authorize-url`, PKCE
  and the resource, RFC 8707), turning the code into tokens (`exchange!`)
  and keeping them alive (`refresh!`, `fresh!`). The connect engine
  (grog.connect) does the browser's part.

  What's kept of a sign-in, the auth: {:issuer :token-endpoint :resource
  :scope :client-id :client-secret :auth-method :access-token
  :refresh-token :expires-at}, :expires-at in epoch ms, or nil when the
  server didn't say."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.log :as log]
            [grog.oauth :as oauth])
  (:import (java.net URI URLEncoder)
           (java.util Base64)))

;; How long a request to a server may take.
(def ^:private timeout-ms 10000)

;; How long before it expires a token is refreshed.
(def ^:private refresh-margin-ms 60000)

(defn ^:private now []
  (System/currentTimeMillis))

(defn ^:private unreachable [url e]
  (let [host (.getHost (URI. (str url)))]
    (log/info "mcp-auth: couldn't reach" host (.getName (class e)))
    (ex-info (str "Couldn't reach " host) {:type ::unreachable})))

(defn ^:private parse-json [s]
  (try (json/parse-string s) (catch Exception _ nil)))

(defn ^:private get-json
  "The JSON object at `url`, string keys, or nil when there's none."
  [url]
  (let [resp (try (http/get url {:headers {"accept" "application/json"} :throw false :timeout timeout-ms})
                  (catch java.io.IOException e (throw (unreachable url e))))
        body (when (= 200 (:status resp)) (parse-json (:body resp)))]
    (when (map? body) body)))

(defn ^:private origin
  "scheme://host[:port] of `uri`, scheme and host in lowercase, without
  their default port."
  [^URI uri]
  (let [scheme (str/lower-case (.getScheme uri))
        port (.getPort uri)
        default? (or (= -1 port) (and (= "https" scheme) (= 443 port)) (and (= "http" scheme) (= 80 port)))]
    (str scheme "://" (str/lower-case (.getHost uri)) (when-not default? (str ":" port)))))

(defn ^:private trimmed-path
  "The path of `uri`, without its trailing slash: \"\" for none."
  [^URI uri]
  (str/replace (or (.getRawPath uri) "") #"/+$" ""))

(defn canonical-resource
  "The canonical URI of the MCP server at `url` (RFC 8707): scheme, host,
  port and path, scheme and host in lowercase, without a query, a fragment
  or a trailing slash (but a path of just \"/\")."
  [url]
  (let [uri (URI. (str url))
        path (or (.getRawPath uri) "")]
    (str (origin uri) (if (= "/" path) "/" (trimmed-path uri)))))

(defn ^:private resource-metadata-urls
  "Where the server at `url` may keep its protected resource metadata, in
  the order to try them (RFC 9728)."
  [url]
  (let [uri (URI. (str url))
        path (trimmed-path uri)
        base (str (origin uri) "/.well-known/oauth-protected-resource")]
    (if (str/blank? path) [base] [(str base path) base])))

(defn ^:private authorization-metadata-urls
  "Where the authorization server `issuer` may keep its metadata, in the
  order to try them (RFC 8414 and OpenID Connect discovery)."
  [issuer]
  (let [uri (URI. issuer)
        path (trimmed-path uri)
        base (origin uri)]
    (if (str/blank? path)
      [(str base "/.well-known/oauth-authorization-server")
       (str base "/.well-known/openid-configuration")]
      [(str base "/.well-known/oauth-authorization-server" path)
       (str base "/.well-known/openid-configuration" path)
       (str base path "/.well-known/openid-configuration")])))

(defn ^:private first-json [urls]
  (some get-json urls))

(defn ^:private resource-allowed?
  "Whether the resource the server's metadata names covers `resource`, the
  server's own: the same origin, its path the same or above."
  [named resource]
  (try
    (let [named-uri (URI. (str named))
          uri (URI. resource)
          named-path (trimmed-path named-uri)
          path (trimmed-path uri)]
      (and (= (origin named-uri) (origin uri))
           (or (= named-path path) (str/starts-with? path (str named-path "/")))))
    (catch Exception _ false)))

(defn ^:private scopes [s]
  (remove str/blank? (str/split (or s "") #"\s+")))

(defn ^:private join-scopes [xs]
  (when (seq xs) (str/join " " (distinct xs))))

(defn ^:private scope-for
  "What to ask for: what the server challenged with, else all it supports,
  and offline_access when the authorization server has it (for a refresh
  token)."
  [challenge resource-metadata metadata]
  (let [asked (if (seq (scopes (:scope challenge)))
                (scopes (:scope challenge))
                (filter string? (get resource-metadata "scopes_supported")))
        offline (when (some #{"offline_access"} (get metadata "scopes_supported")) ["offline_access"])]
    (join-scopes (concat asked offline))))

(defn ^:private authorization-metadata
  "The metadata of the authorization server `issuer`, checked: its issuer
  is the one asked, and it takes S256 PKCE."
  [issuer]
  (let [metadata (or (first-json (authorization-metadata-urls issuer))
                     (throw (ex-info (str "No authorization server metadata at " issuer) {:type ::no-metadata})))]
    (when-not (= issuer (get metadata "issuer"))
      (throw (ex-info (str "The authorization server at " issuer " says it's another one") {:type ::bad-issuer})))
    (when-not (some #{"S256"} (get metadata "code_challenge_methods_supported"))
      (throw (ex-info (str issuer " doesn't take PKCE (S256)") {:type ::no-pkce})))
    metadata))

(defn discover!
  "Finds how to sign in to the MCP server at `server-url`, from what its
  401 said (`challenge`: {:resource-metadata :scope :error}, or nil):
  {:resource :issuer :metadata :resource-metadata :scope}, the metadata as
  the servers wrote them (string keys). Throws ex-info ::no-metadata,
  ::bad-issuer, ::bad-resource, ::no-pkce or ::unreachable."
  [server-url challenge]
  (let [resource (canonical-resource server-url)
        resource-metadata (or (first-json (if-let [url (:resource-metadata challenge)]
                                            [url]
                                            (resource-metadata-urls server-url)))
                              (throw (ex-info (str "No protected resource metadata for " resource)
                                              {:type ::no-metadata})))
        named (get resource-metadata "resource")
        issuer (first (filter string? (get resource-metadata "authorization_servers")))]
    (when (and named (not (resource-allowed? named resource)))
      (throw (ex-info (str "The server's metadata is for another server: " named) {:type ::bad-resource})))
    (when-not issuer
      (throw (ex-info (str "The metadata of " resource " names no authorization server") {:type ::no-metadata})))
    (let [metadata (authorization-metadata issuer)]
      {:resource (if named (canonical-resource named) resource)
       :issuer issuer
       :metadata metadata
       :resource-metadata resource-metadata
       :scope (scope-for challenge resource-metadata metadata)})))

(defn iss-required?
  "Whether the browser must come back with `iss` from the authorization
  server (RFC 9207): when it says it sends it."
  [discovery]
  (true? (get-in discovery [:metadata "authorization_response_iss_parameter_supported"])))

(defn ^:private own-auth-method
  "How a client the user made authenticates: none without a secret, else
  HTTP Basic unless the server only takes it in the body."
  [metadata client-secret]
  (let [supported (get metadata "token_endpoint_auth_methods_supported")]
    (cond
      (str/blank? client-secret) "none"
      (and (seq supported) (not-any? #{"client_secret_basic"} supported) (some #{"client_secret_post"} supported))
      "client_secret_post"
      :else "client_secret_basic")))

(defn ^:private registration-request [redirect-uri]
  {:client_name "grog"
   :redirect_uris [redirect-uri]
   :grant_types ["authorization_code" "refresh_token"]
   :response_types ["code"]
   :token_endpoint_auth_method "none"
   :application_type "web"
   :client_uri "https://github.com/ericdallo/grog"})

(defn ^:private register-dynamically!
  "Registers grog at `endpoint` (RFC 7591, deprecated by MCP but still the
  only way some servers have)."
  [endpoint redirect-uri]
  (let [resp (try (http/post endpoint {:headers {"content-type" "application/json" "accept" "application/json"}
                                       :body (json/generate-string (registration-request redirect-uri))
                                       :throw false
                                       :timeout timeout-ms})
                  (catch java.io.IOException e (throw (unreachable endpoint e))))
        body (parse-json (:body resp))]
    (if (and (#{200 201} (:status resp)) (map? body) (string? (get body "client_id")))
      body
      (throw (ex-info (format "Registering grog failed (%d)" (:status resp)) {:type ::no-registration})))))

(defn register!
  "A client for grog at the authorization server `discovery` found: the
  user's own (`client`: {:client-id :client-secret}, or one registered
  before, with its :auth-method and :how), grog's client ID metadata
  document at `client-id-url` when the server takes those, else one it
  registers (dynamic registration, to keep by issuer: pass it as `client`
  next time). `redirect-uri`: where the browser comes back to.
  {:client-id :client-secret :auth-method :issuer :how}, :how being
  \"own\", \"metadata-document\" or \"registered\". Throws ex-info
  ::no-registration when there's no way: then the user makes a client."
  [{:keys [issuer metadata]} {:keys [client-id-url redirect-uri client]}]
  (cond
    (:client-id client)
    {:client-id (:client-id client)
     :client-secret (:client-secret client)
     :auth-method (or (:auth-method client) (own-auth-method metadata (:client-secret client)))
     :issuer issuer
     :how (or (:how client) "own")}

    (and client-id-url (true? (get metadata "client_id_metadata_document_supported")))
    {:client-id client-id-url :client-secret nil :auth-method "none" :issuer issuer :how "metadata-document"}

    (string? (get metadata "registration_endpoint"))
    (let [registered (register-dynamically! (get metadata "registration_endpoint") redirect-uri)
          secret (get registered "client_secret")]
      (log/info "mcp-auth: registered grog at" issuer)
      {:client-id (get registered "client_id")
       :client-secret secret
       :auth-method (or (get registered "token_endpoint_auth_method")
                        (if (str/blank? secret) "none" "client_secret_basic"))
       :issuer issuer
       :how "registered"})

    :else
    (throw (ex-info (str issuer " has no way for grog to register: it needs a client id") {:type ::no-registration}))))

(defn authorize-url
  "Where the browser signs in: the authorization endpoint with the code
  flow's parameters, PKCE (`challenge`, S256), `state`, `redirect-uri`, the
  resource, and the scope (`scope`, else the one discovered)."
  [{:keys [metadata resource] :as discovery} {:keys [client-id]} {:keys [challenge state redirect-uri scope]}]
  (let [endpoint (get metadata "authorization_endpoint")
        scope (or scope (:scope discovery))]
    (str endpoint (if (str/includes? endpoint "?") "&" "?")
         (oauth/form-encode (cond-> [["response_type" "code"]
                                     ["client_id" client-id]
                                     ["redirect_uri" redirect-uri]
                                     ["state" state]
                                     ["code_challenge" challenge]
                                     ["code_challenge_method" "S256"]
                                     ["resource" resource]]
                              (not (str/blank? scope)) (conj ["scope" scope]))))))

(defn ^:private basic [client-id client-secret]
  (let [enc #(URLEncoder/encode (str %) "UTF-8")]
    (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes (str (enc client-id) ":" (enc client-secret)) "UTF-8")))))

(defn ^:private token-request
  "The token endpoint's request for `params`, the client authenticating as
  it registered."
  [{:keys [client-id client-secret auth-method]} params]
  (let [secret? (not (str/blank? client-secret))
        params (cond-> (assoc params "client_id" client-id)
                 (and secret? (= "client_secret_post" auth-method)) (assoc "client_secret" client-secret))]
    {:headers (cond-> {"content-type" "application/x-www-form-urlencoded" "accept" "application/json"}
                (and secret? (= "client_secret_basic" auth-method)) (assoc "authorization" (basic client-id client-secret)))
     :body (oauth/form-encode params)
     :throw false
     :timeout timeout-ms}))

(defn ^:private request-tokens!
  "Asks `endpoint` for tokens: the token response (string keys). Throws
  ex-info ::refused when it says no (400, 401), ::failed otherwise,
  ::unreachable."
  [endpoint client params]
  (let [resp (try (http/post endpoint (token-request client params))
                  (catch java.io.IOException e (throw (unreachable endpoint e))))
        body (parse-json (:body resp))
        status (:status resp)
        why (when (map? body) (or (get body "error_description") (get body "error")))]
    (cond
      (and (= 200 status) (map? body) (string? (get body "access_token"))) body
      (#{400 401} status) (throw (ex-info (str "The sign-in was refused" (when why (str ": " why))) {:type ::refused}))
      :else (throw (ex-info (format "The authorization server answered %d" status) {:type ::failed})))))

(defn ^:private expires-at [body at]
  (when-let [s (get body "expires_in")]
    (let [seconds (if (string? s) (parse-long s) s)]
      (when (number? seconds) (+ at (* 1000 (long seconds)))))))

(defn ^:private with-tokens
  "`auth` with what the token endpoint answered (`body`) at `at`, keeping
  its refresh token and scope when none came."
  [auth body at]
  (assoc auth
         :access-token (get body "access_token")
         :refresh-token (or (get body "refresh_token") (:refresh-token auth))
         :scope (or (get body "scope") (:scope auth))
         :expires-at (expires-at body at)))

(defn exchange!
  "Turns the `code` the browser came back with into the auth to keep,
  with the PKCE `verifier` and the `redirect-uri` it was asked with.
  Throws ex-info ::refused, ::failed or ::unreachable."
  [{:keys [metadata resource issuer] :as discovery} client {:keys [code verifier redirect-uri scope]}]
  (let [endpoint (get metadata "token_endpoint")
        at (now)
        body (request-tokens! endpoint client {"grant_type" "authorization_code"
                                               "code" code
                                               "redirect_uri" redirect-uri
                                               "code_verifier" verifier
                                               "resource" resource})]
    (with-tokens {:issuer issuer
                  :token-endpoint endpoint
                  :resource resource
                  :scope (or scope (:scope discovery))
                  :client-id (:client-id client)
                  :client-secret (:client-secret client)
                  :auth-method (:auth-method client)}
      body at)))

(defn refresh!
  "`auth` with new tokens, from its refresh token. Throws ex-info ::refused
  when the server won't (signed out: sign in again), ::failed or
  ::unreachable."
  [{:keys [token-endpoint refresh-token resource] :as auth}]
  (when (str/blank? refresh-token)
    (throw (ex-info "There's no refresh token: sign in again" {:type ::refused})))
  (let [at (now)]
    (with-tokens auth
      (request-tokens! token-endpoint auth {"grant_type" "refresh_token"
                                            "refresh_token" refresh-token
                                            "resource" resource})
      at)))

(defn fresh!
  "`auth`, refreshed when its token expires within a minute of `now-ms`.
  Without an expiry, or a refresh token while it's still valid, as it is."
  [{:keys [expires-at refresh-token] :as auth} now-ms]
  (cond
    (or (nil? expires-at) (> expires-at (+ now-ms refresh-margin-ms))) auth
    (not (str/blank? refresh-token)) (refresh! auth)
    (> expires-at now-ms) auth
    :else (throw (ex-info "The token expired: sign in again" {:type ::refused}))))

(defn step-up-scope
  "What to ask for when a server wants more (a 403 with insufficient_scope):
  the scopes asked for before (`previous`, space-separated) and those
  `challenge` names, or nil when it names none."
  [previous challenge]
  (when (seq (scopes (:scope challenge)))
    (join-scopes (concat (scopes previous) (scopes (:scope challenge))))))
