(ns grog.test-helpers
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [grog.sse :as sse]
            [org.httpkit.server :as http])
  (:import (java.util Base64)))

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
