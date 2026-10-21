(ns grog.client
  "Command line client of a running server. Its JSON keys are in
  lowerCamelCase, read as kebab-case keywords (grog.config/parse)."
  (:require [babashka.http-client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config]))

(def default-url "http://127.0.0.1:8484")

(defn server-url [url]
  (or (not-empty url)
      (not-empty (System/getenv "GROG_URL"))
      default-url))

(defn ^:private unreachable [url ^Exception e]
  (format "Couldn't reach the server at %s (%s). Is it running? Check: systemctl status grog"
          url (or (ex-message e) (.getName (class e)))))

(defn ^:private call
  "A JSON request to the server, POST and DELETE sending `params`. Returns
  [true body] or [false error-message]."
  ([method url path]
   (call method url path {}))
  ([method url path params]
   (try
     (let [resp (http/request (cond-> {:method method :uri (str url path) :throw false}
                                (#{:post :delete} method)
                                (assoc :headers {"content-type" "application/json"} :body (config/generate params))))
           body (try (config/parse (:body resp)) (catch Exception _ nil))]
       (if (= 200 (:status resp))
         [true body]
         [false (or (:error body) (:body resp))]))
     (catch java.io.IOException e
       [false (unreachable url e)]))))

(defn ^:private fail [message]
  (println (str "[error] " message))
  false)

(defn ^:private resolve-provider
  "`provider`, or the one the server uses when nil. nil, after saying why, when
  the server can't tell."
  [url provider]
  (or provider
      (let [[ok? body] (call :get url "/v1/status")]
        (if ok? (:provider body) (fail body)))))

(defn ^:private signed-in [status]
  (println (format "Signed in as %s: grog now uses your subscription." (or (:email status) "you")))
  true)

(defn ^:private print-code
  "How to sign in with the code of `login` (grog.login/view)."
  [{:keys [label url code help expires-at]}]
  (println (str "Sign in with your " label " account: on any phone or computer, open"))
  (println)
  (println (str "  " url))
  (println)
  (println (format "and enter the code (good for %d minutes):"
                   (max 1 (Math/round (/ (- expires-at (System/currentTimeMillis)) 60000.0)))))
  (println)
  (println (str "  " code))
  (when help
    (println)
    (println (:text help))
    (println (str "  " (:url help))))
  (println)
  (println "Waiting (Ctrl-C to stop)...")
  (flush))

(defn ^:private wait-for-code
  "Waits for the code of the sign-in `login` to be entered, with a new code
  whenever the last one expires."
  [url provider login]
  (print-code login)
  (loop [code (:code login)]
    (Thread/sleep 1000)
    (let [[ok? status] (call :get url (str "/v1/auth/" provider))]
      (cond
        (not ok?) (fail status)
        (= "done" (:login status)) (signed-in status)
        (= "error" (:login status)) (fail (str "Sign-in failed: " (:error status)))
        (= "expired" (:login status)) (let [[ok? login] (call :post url (str "/v1/auth/" provider "/login")
                                                              {:method "device"})]
                                        (if ok?
                                          (do (println (str "That code expired: enter this one instead, " (:code login)))
                                              (flush)
                                              (recur (:code login)))
                                          (fail login)))
        ;; A new code, from another `grog login` or for the skin.
        (and (:code status) (not= code (:code status))) (do (println (str "Enter this code instead: " (:code status)))
                                                            (flush)
                                                            (recur (:code status)))
        :else (recur code)))))

(defn ^:private wait-for-browser
  "Waits for the browser to come back from signing in at the link of `login`."
  [url provider {:keys [label redirect-uri] :as login}]
  (let [port (second (re-find #":(\d+)/" redirect-uri))]
    (println (str "Sign in with your " label " account at:"))
    (println)
    (println (str "  " (:url login)))
    (println)
    (println (str "The browser then goes to " redirect-uri ", which has to reach"))
    (println "grog on this machine. From another machine, forward the port first:")
    (println (format "  ssh -L %s:127.0.0.1:%s <user>@<this machine>" port port))
    (println)
    (println "Waiting for the browser (Ctrl-C to stop)...")
    (flush)
    (loop [waited-ms 0]
      (Thread/sleep 1000)
      (let [[ok? status] (call :get url (str "/v1/auth/" provider))]
        (cond
          (not ok?) (fail status)
          (= "done" (:login status)) (signed-in status)
          (= "error" (:login status)) (fail (str "Sign-in failed: " (:error status)))
          (> waited-ms (* 11 60 1000)) (fail "Gave up waiting for the browser.")
          :else (recur (+ waited-ms 1000)))))))

(defn login!
  "Signs in to the subscription of `provider` (the server's when nil): with a
  code entered on any phone or computer, the provider's way, or with a
  browser coming back to this machine (`browser?`). Waits until it's done.
  Returns false when it failed."
  ([url provider]
   (login! url provider false))
  ([url provider browser?]
   (let [provider (resolve-provider url provider)
         [ok? login] (when provider
                       (call :post url (str "/v1/auth/" provider "/login") (if browser? {:method "browser"} {})))]
     (cond
       (not provider) false
       (not ok?) (fail login)
       (= "device" (:method login)) (wait-for-code url provider login)
       :else (wait-for-browser url provider login)))))

(defn logout!
  "Signs out of `provider` (the server's when nil)."
  [url provider]
  (let [provider (resolve-provider url provider)
        [ok? body] (when provider (call :delete url (str "/v1/auth/" provider)))]
    (cond
      (not provider) false
      ok? (do (println (str "Signed out of " provider "."))
              true)
      :else (fail body))))

(defn usd
  "An amount in dollars, with cents to spare when it's small."
  [x]
  (format (if (< x 0.1) "$%.4f" "$%.2f") (double x)))

(defn duration
  "`s` seconds, roughly: 45m, 2h 10m, 3d 4h."
  [s]
  (let [s (max 0 (long s))]
    (cond
      (< s 60) "<1m"
      (< s 3600) (str (quot s 60) "m")
      (< s 86400) (let [h (quot s 3600) m (quot (mod s 3600) 60)]
                    (if (zero? m) (str h "h") (str h "h " m "m")))
      :else (let [d (quot s 86400) h (quot (mod s 86400) 3600)]
              (if (zero? h) (str d "d") (str d "d " h "h"))))))

(defn usage-line
  "What grog consumed (see grog.usage), as `grog status` says it, or nil:
  how much of the subscription's limits is used, or what the API key spent."
  [{:keys [auth today month quota]} now-s]
  (case auth
    "subscription"
    (str "Usage:    "
         (if-let [windows (seq (:windows quota))]
           (str/join ", " (for [{:keys [label used-percent resets-at]} windows]
                            (str label " " (Math/round (double used-percent)) "% used"
                                 (when (and resets-at (> resets-at now-s))
                                   (str " (resets in " (duration (- resets-at now-s)) ")")))))
           "not known yet"))

    "api-key"
    (str "Spent:    " (usd (:usd month)) " this month, " (usd (:usd today)) " today"
         (when (pos? (:unpriced month 0))
           (format " (and %d turns of models without a price)" (:unpriced month))))

    nil))

(defn status! [url]
  (let [[ok? {:keys [provider model auth email error changed usage] assistant :name :as body}]
        (call :get url "/v1/status")]
    (if-not ok?
      (fail body)
      (do (println "Name:    " assistant)
          (println "Provider:" provider)
          (println "Model:   " model)
          (println "Auth:    " (case auth
                                 "subscription" (str "subscription, signed in as " (or email "?"))
                                 "api-key" "API key"
                                 (str "none: " error)))
          (some-> (usage-line usage (quot (System/currentTimeMillis) 1000)) println)
          (when (seq changed)
            (println "Changed: " (str (str/join ", " changed) " (asked to; `grog settings reset` undoes it)")))
          true))))

(defn reset-settings!
  "Puts the settings changed through the model's tool back as configured."
  [url]
  (let [[ok? body] (call :delete url "/v1/settings")]
    (if ok?
      (do (println "The settings are back as configured.")
          true)
      (fail body))))

(defn models! [url]
  (let [[ok? body] (call :get url "/v1/models")]
    (if-not ok?
      (fail body)
      (let [models (:models body)
            width (reduce max 1 (map (comp count :id) models))]
        ;; * marks the model in use.
        (doseq [{:keys [id description current]} models]
          (println (str/trimr (format (str "%s %-" width "s  %s")
                                      (if current "*" " ") id (or description "")))))
        true))))

(defn ask!
  "Sends `message` within `conversation-id` (a new conversation when `new?`),
  writing the answer to `out` as it streams in. Returns false when the request
  failed."
  [url {:keys [conversation-id new?]} message ^java.io.Writer out]
  (try
    (let [resp (http/post (str url "/v1/chat")
                          {:headers {"content-type" "application/json"}
                           :body (config/generate (cond-> {:message message
                                                           :conversation-id conversation-id}
                                                    new? (assoc :new true)))
                           :as :stream
                           :throw false})]
      (with-open [reader (io/reader (:body resp))]
        (if (= 200 (:status resp))
          (let [buf (char-array 512)]
            (loop []
              (let [n (.read reader buf)]
                (when (pos? n)
                  (.write out buf 0 n)
                  (.flush out)
                  (recur))))
            true)
          (let [body (slurp reader)
                error (try (:error (config/parse body))
                           (catch Exception _ nil))]
            (.write out (str "[error] " (or error body)))
            false))))
    (catch java.io.IOException e
      (.write out (str "[error] " (unreachable url e)))
      false)))

(defn ^:private new-conversation-id []
  (str (random-uuid)))

(defn chat!
  "Interactive prompt: each line is a message to the assistant."
  [url]
  (println "Talking to grog. /new starts a new conversation, /quit (or Ctrl-D) exits.")
  (loop [conversation-id (new-conversation-id)]
    (print "\nyou> ")
    (flush)
    (when-let [line (some-> (read-line) str/trim)]
      (cond
        (str/blank? line) (recur conversation-id)
        (#{"/quit" "/exit"} line) nil
        (= "/new" line) (do (println "(new conversation)")
                            (recur (new-conversation-id)))
        :else (do (ask! url {:conversation-id conversation-id} line *out*)
                  (println)
                  (recur conversation-id))))))
