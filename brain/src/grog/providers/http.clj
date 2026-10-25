(ns grog.providers.http
  "HTTP plumbing shared by the providers."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [grog.sse :as sse]))

(defn api-key
  "The API key, read from the environment variable `env-var`."
  [provider env-var]
  (or (not-empty (some-> env-var System/getenv))
      (throw (ex-info (format "Set the %s environment variable to your %s API key." env-var provider)
                      {:type ::missing-api-key :env-var env-var}))))

(defn ^:private error-message [body]
  (or (try (get-in (json/parse-string body true) [:error :message])
           (catch Exception _ nil))
      body))

(defn post-sse!
  "POSTs `body` as JSON and returns the response: its :headers, and its :body,
  an InputStream with the SSE stream. `timeout-ms` bounds the wait for the
  response to start. Throws ex-info carrying the API's error message
  otherwise."
  [provider url headers body timeout-ms]
  (let [resp (try
               (http/post url (cond-> {:headers (merge {"content-type" "application/json"
                                                        "accept" "text/event-stream"}
                                                       headers)
                                       :body (json/generate-string body)
                                       :as :stream
                                       :throw false}
                                timeout-ms (assoc :timeout timeout-ms)))
               (catch java.net.http.HttpTimeoutException e
                 (throw (ex-info (format "The %s API didn't answer within %d s." provider (quot timeout-ms 1000))
                                 {:type ::timeout}
                                 e)))
               (catch java.io.IOException e
                 (throw (ex-info (format "Couldn't connect to the %s API at %s (%s). Is the internet up?"
                                         provider url (or (ex-message e) (.getName (class e))))
                                 {:type ::network-error}
                                 e))))]
    (if (= 200 (:status resp))
      resp
      (throw (ex-info (format "The %s API returned %d: %s"
                              provider (:status resp) (error-message (slurp (:body resp))))
                      {:type ::http-error :status (:status resp)})))))

(defn cancelled
  "What a turn throws once whoever asked went away (its `cancel`, a promise,
  delivered): it stops, as nobody waits for it."
  []
  (ex-info "The turn was cut off: whoever asked went away." {:type ::cancelled}))

(defn read-events!
  "Calls (f events) with the lazy seq of the SSE events in `stream` (from
  `post-sse!`), and closes it. When no event arrives for `idle-ms`, the
  provider stalled: the stream is closed, and this throws saying so. Once
  `cancel` (a promise, or nil) is delivered, it's closed too, and this
  throws `cancelled`."
  ([provider stream idle-ms f]
   (read-events! provider stream idle-ms nil f))
  ([provider ^java.io.InputStream stream idle-ms cancel f]
   (let [last-event (atom (System/currentTimeMillis))
         why (atom nil)
         done (promise)]
     (future
       (loop []
         (when (= ::waiting (deref done 200 ::waiting))
           (cond
             (and cancel (realized? cancel)) (do (reset! why ::cancelled) (.close stream))
             (> (- (System/currentTimeMillis) @last-event) idle-ms) (do (reset! why ::stalled) (.close stream))
             :else (recur)))))
     (try
       (with-open [reader (io/reader stream)]
         (f (map (fn [event]
                   (reset! last-event (System/currentTimeMillis))
                   event)
                 (sse/events reader))))
       (catch Exception e
         (case @why
           ::cancelled (throw (cancelled))
           ::stalled (throw (ex-info (format "The %s API stopped sending the answer for %d s."
                                             provider (quot idle-ms 1000))
                                     {:type ::stalled}))
           (throw e)))
       (finally
         (deliver done true))))))
