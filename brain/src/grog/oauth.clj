(ns grog.oauth
  "OAuth 2.0 helpers for signing in (grog.connect): PKCE, form encoding, JWT
  claims and the local server the browser is redirected to."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http])
  (:import (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64)))

(defn ^:private base64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn random-token
  "A random URL-safe string with 256 bits of entropy."
  []
  (let [bs (byte-array 32)]
    (.nextBytes (SecureRandom.) bs)
    (base64url bs)))

(defn challenge
  "The S256 PKCE code challenge of `verifier`."
  [^String verifier]
  (base64url (.digest (MessageDigest/getInstance "SHA-256")
                      (.getBytes verifier StandardCharsets/US_ASCII))))

(defn pkce []
  (let [verifier (random-token)]
    {:verifier verifier :challenge (challenge verifier)}))

(defn form-encode
  "`pairs` (a map or a seq of [k v]) as application/x-www-form-urlencoded."
  [pairs]
  (str/join "&" (for [[k v] pairs]
                  (str (URLEncoder/encode (name k) "UTF-8") "=" (URLEncoder/encode (str v) "UTF-8")))))

(defn form-decode
  "A query string as a map with keyword keys."
  [s]
  (into {}
        (for [pair (str/split (or s "") #"&")
              :when (seq pair)
              :let [[k v] (str/split pair #"=" 2)]]
          [(keyword (URLDecoder/decode k "UTF-8")) (URLDecoder/decode (or v "") "UTF-8")])))

(defn jwt-claims
  "The claims (string keys) of a JWT, without verifying it. nil when it isn't one."
  [token]
  (try
    (some-> token
            (str/split #"\.")
            second
            (->> (.decode (Base64/getUrlDecoder)))
            (String. StandardCharsets/UTF_8)
            json/parse-string)
    (catch Exception _ nil)))

(defn ^:private escape-html
  "`s` as text in an HTML element."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;"}))

(defn page
  "A minimal HTML page for the browser after the redirect. `title` and
  `message` are text (they may come from the query string)."
  [title message]
  (format "<!doctype html><html><head><meta charset=\"utf-8\"><title>grog</title></head>
<body style=\"font-family: sans-serif; text-align: center; margin-top: 4em\"><h1>%s</h1><p>%s</p></body></html>"
          (escape-html title) (escape-html message)))

(defn start-callback-server!
  "Serves http://127.0.0.1:`port``path`. `on-callback` gets the query
  parameters and returns the HTML to show. Returns the http-kit server."
  [port path on-callback]
  (http/run-server (fn [{:keys [uri query-string]}]
                     (if (= path uri)
                       {:status 200
                        :headers {"Content-Type" "text/html; charset=utf-8"}
                        :body (on-callback (form-decode query-string))}
                       {:status 404 :body "Not found"}))
                   {:ip "127.0.0.1" :port port :legacy-return-value? false}))
