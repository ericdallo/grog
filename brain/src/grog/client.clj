(ns grog.client
  "Command line client of a running server. Its JSON keys are in
  lowerCamelCase, read as kebab-case keywords (grog.config/parse)."
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.pairing :as pairing]
            [grog.update :as update]))

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
  (let [[ok? {:keys [provider model auth email error changed usage revision] assistant :name :as body}]
        (call :get url "/v1/status")]
    (if-not ok?
      (fail body)
      (do (println "Name:    " assistant)
          (println "Revision:" (or (update/short-revision revision) "unknown"))
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

(defn mic!
  "Turns grog-voice's mic on or off (`on?`), for good."
  [url on?]
  (let [[ok? body] (call :post url "/v1/actions/mic" {:on on?})]
    (if ok?
      (do (println (if (= "on" (:mic body))
                     "The mic is on."
                     "The mic is off: nothing is heard until it's on again, even after restarts."))
          true)
      (fail body))))

(defn update-line
  "What `grog update` says of an update going through `status`'s stage
  (grog.updater)."
  [{:keys [stage from to to-date error]}]
  (let [latest (str (update/short-revision to) (some->> (update/when-pushed {} to-date) (format " (%s)")))]
    (case stage
      "fetching" "Looking for a new grog..."
      "current" (str "grog already is the latest: " latest ".")
      "building" (str "A new grog: " latest (some->> (update/short-revision from) (str ", this is "))
                      ". Building it...")
      "waiting" "Waiting for nobody to be talking to grog..."
      "switching" "Switching to it..."
      "verifying" "Waiting for the new grog to answer..."
      "done" (str "Done: grog is " latest ".")
      "failed" (str "[error] " error)
      (str "Updating (" stage ")..."))))

;; How long `grog update` waits for the update to start, and for grog to
;; come back, restarting.
(def ^:private update-start-ms 60000)
(def ^:private update-gone-ms (* 5 60 1000))

(defn ^:private follow-update
  "Says each stage the update started since `since` (ms) goes through, until
  it's over, grog restarting along the way: whether it went well, or there
  was nothing new."
  [url since]
  (loop [said nil
         gone-since nil]
    (Thread/sleep 1000)
    (let [[ok? body] (call :get url "/v1/status")
          {:keys [stage started-at] :as status} (when ok? (:update body))
          now (System/currentTimeMillis)]
      (cond
        (and (not ok?) gone-since (> (- now gone-since) update-gone-ms))
        (fail "grog didn't come back: see journalctl -u grog-update -u grog")

        (not ok?)
        (recur said (or gone-since now))

        (< (or started-at 0) since)
        (if (> (- now since) update-start-ms)
          (fail "The update didn't start: see journalctl -u grog-update")
          (recur said nil))

        :else
        (do (when (not= stage said)
              (println (update-line status))
              (flush))
            (if (contains? update/under-way stage)
              (recur stage nil)
              (not= "failed" stage)))))))

(defn ^:private update-without-grog!
  "Updates through systemd, as grog doesn't answer: whether it went well."
  [url]
  (println (str "grog doesn't answer at " url ": updating through systemd..."))
  (flush)
  (let [since (System/currentTimeMillis)
        {:keys [exit]} @(p/process ["systemctl" "start" "grog-update.service"] {:inherit true})
        status (update/read-status update/default-status-file)]
    (if (>= (or (:started-at status) 0) since)
      (do (println (update-line status))
          (not= "failed" (:stage status)))
      (fail (if (zero? exit)
              "The update said nothing: see journalctl -u grog-update"
              "Couldn't start it: try sudo systemctl start grog-update")))))

(defn update!
  "Asks grog to update itself to the latest grog pushed, then follows how it
  goes until it's over: whether it went well, or there was nothing new.
  When grog doesn't answer, starts the update service itself."
  [url]
  (let [since (System/currentTimeMillis)]
    (try
      (let [resp (http/post (str url "/v1/actions/update")
                            {:headers {"content-type" "application/json"} :body "{}" :throw false})
            body (try (config/parse (:body resp)) (catch Exception _ nil))]
        (cond
          (not= 200 (:status resp)) (fail (or (:error body) (:body resp)))
          (:started body) (follow-update url since)
          :else (do (println "An update is already under way.")
                    (follow-update url (or (get-in body [:update :started-at]) 0)))))
      (catch java.io.IOException _
        (update-without-grog! url)))))

(defn ^:private day
  "The day of `ms`, here: 2026-10-20."
  [ms]
  (str (.toLocalDate (.atZone (java.time.Instant/ofEpochMilli ms) (java.time.ZoneId/systemDefault)))))

(defn computers!
  "Lists the computers grog acts on, paired with it."
  [url]
  (let [[ok? body] (call :get url "/v1/computers")]
    (cond
      (not ok?) (fail body)
      (empty? (:computers body)) (do (println "No computers yet: pair one with `grog computers add`.")
                                     true)
      :else (do (doseq [{:keys [name user host added-at]} (:computers body)]
                  (println (format "%-16s %s@%s, paired %s" name user host (day added-at))))
                true))))

(defn pair-address
  "Where a computer reaches grog to pair, host:port, as pairing `view`
  says."
  [{:keys [addresses port]}]
  (let [address (or (first addresses) "<grog's address>")]
    (str (if (str/includes? address ":") (str "[" address "]") address) ":" port)))

(defn add-computer!
  "Lets a computer pair with grog, saying what to run on it, then asks here
  whether to allow the one asking (someone at grog's screen may first):
  whether one was paired."
  [url]
  (let [[ok? view] (call :post url "/v1/pairing")]
    (if-not ok?
      (fail view)
      (do (println "grog lets a computer pair for the next 5 minutes. In a terminal on it, run:")
          (println)
          (println (str "  " (pairing/command (pair-address view))))
          (println)
          (println (format "or open %s in its browser. It takes an SSH server there, and nothing to install."
                           (pair-address view)))
          (println "Waiting for it (Ctrl-C to stop)...")
          (flush)
          (loop [asked nil]
            (Thread/sleep 1000)
            (let [[ok? body] (call :get url "/v1/computers")
                  {:keys [status name user code error]} (when ok? (:pairing body))]
              (cond
                (not ok?) (fail body)

                (and (= "asking" status) (not= asked code))
                (do (println (format "%s (%s) asks to pair, with the code %s." name user (pairing/spaced code)))
                    (print "Is it the code that computer shows? Allow it? [y/N] ")
                    (flush)
                    (let [yes? (contains? #{"y" "yes"} (str/lower-case (str/trim (or (read-line) ""))))]
                      ;; Someone at grog's screen may have answered first.
                      (call :post url (if yes? "/v1/pairing/allow" "/v1/pairing/deny"))
                      (recur code)))

                (= "paired" status) (do (println (format "Paired: %s. Ask grog to act on it." name))
                                        true)
                (= "denied" status) (fail "It wasn't allowed.")
                (= "expired" status) (fail "No computer asked to pair in time.")
                (= "closed" status) (fail "Pairing was closed.")
                (= "failed" status) (fail (str "Pairing failed: " error))
                :else (recur asked))))))))

(defn forget-computer!
  "Forgets computer `name`, which also stops letting grog in, if it can be
  reached."
  [url name]
  (let [[ok? body] (call :delete url (str "/v1/computers/" name))]
    (cond
      (not ok?) (fail body)
      (:unpaired body) (do (println (format "Forgot %s: it doesn't let grog in anymore." name))
                           true)
      :else (do (println (format (str "Forgot %s. It couldn't be reached: on it, take grog's line out of "
                                      "~/.ssh/authorized_keys.")
                                 name))
                true))))

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
