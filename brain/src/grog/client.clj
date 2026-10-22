(ns grog.client
  "Command line client of a running server. Its JSON keys are in
  lowerCamelCase, read as kebab-case keywords (grog.config/parse)."
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.update :as update])
  (:import (java.net URLEncoder)))

(def default-url "http://127.0.0.1:8484")

(defn server-url [url]
  (or (not-empty url)
      (not-empty (System/getenv "GROG_URL"))
      default-url))

(defn ^:private unreachable [url ^Exception e]
  (format "Couldn't reach the server at %s (%s). Is it running? Check: systemctl status grog"
          url (or (ex-message e) (.getName (class e)))))

(defn ^:private call
  "A JSON request to the server, POST, PUT and DELETE sending `params`. Returns
  [true body] or [false error-message]."
  ([method url path]
   (call method url path {}))
  ([method url path params]
   (try
     (let [resp (http/request (cond-> {:method method :uri (str url path) :throw false}
                                (#{:post :put :delete} method)
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

(defn spaced
  "A pairing code as it's shown: 482 913."
  [code]
  (let [s (str code)]
    (if (re-matches #"\d{6}" s) (str (subs s 0 3) " " (subs s 3)) s)))

(defn ^:private say-device-code
  "How to sign in with the code of the session's step."
  [{:keys [title link] {:keys [url code help expires-at]} :step}]
  (println (str "Sign in with your " title " account: on any phone or computer, open"))
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
  (when link
    (println)
    (println (str "Or open " link " there: grog's page has it all.")))
  (println)
  (println "Waiting (Ctrl-C to stop)..."))

(defn ^:private loopback?
  "Whether `url` comes back to this machine."
  [url]
  (boolean (re-find #"^https?://(localhost|127\.0\.0\.1|\[::1\])[:/]" (str url))))

(defn ^:private say-oauth
  "How to sign in in a browser, at the session's step: where, when this is
  who opened it."
  [{:keys [title link] {:keys [url back-to]} :step}]
  (cond
    (and url (not (loopback? back-to)))
    (do (println (str "Sign in with your " title " account, on a phone or computer on grog's network, at:"))
        (println)
        (println (str "  " url))
        (when link
          (println)
          (println (str "or open grog's page there: " link))))

    url
    (let [port (second (re-find #":(\d+)/" (str back-to)))]
      (println (str "Sign in with your " title " account at:"))
      (println)
      (println (str "  " url))
      (println)
      (println (str "The browser then goes to " back-to ", which has to reach"))
      (println "grog on this machine. From another machine, forward the port first:")
      (println (format "  ssh -L %s:127.0.0.1:%s <user>@<this machine>" port port))
      (when link
        (println (str "or paste the address it ended at on grog's page, " link))))

    :else
    (println (str "Sign in on grog's page: " link)))
  (println)
  (println "Waiting for the browser (Ctrl-C to stop)..."))

(defn ^:private say-script
  "What to run on a computer to pair it, at the session's step."
  [{{:keys [command address]} :step}]
  (println "grog lets a computer pair for the next 10 minutes. In a terminal on it, run:")
  (println)
  (println (str "  " command))
  (println)
  (println (format "or open %s in its browser. It takes an SSH server there, and nothing to install." address))
  (println "Waiting for it (Ctrl-C to stop)..."))

(defn ^:private say-form
  "Where to fill in what the session's step asks for: grog's page."
  [{:keys [title link]}]
  (println (str title " needs a few things from you: fill them in on grog's page, from a phone or computer"))
  (println "on its network:")
  (println)
  (println (str "  " link))
  (println)
  (println "Waiting (Ctrl-C to stop)..."))

(defn ^:private say-link
  "The page the session's step asks to open."
  [{{:keys [url message]} :step}]
  (when-not (str/blank? message) (println message))
  (println (str "Open: " url)))

(defn ^:private ask-to-allow!
  "Asks here whether to allow the computer asking, at the session's step
  (someone at grog's screen may answer first)."
  [url {{:keys [name user code]} :step}]
  (println (format "%s (%s) asks to pair, with the code %s." name user (spaced code)))
  (print "Is it the code that computer shows? Allow it? [y/N] ")
  (flush)
  (let [yes? (contains? #{"y" "yes"} (str/lower-case (str/trim (or (read-line) ""))))]
    (call :post url (if yes? "/v1/connect/allow" "/v1/connect/deny"))))

(defn ^:private said-of
  "What was said of the session's step, for it to be said once."
  [{{:keys [kind code back-to command] :as step} :step}]
  [kind (case kind
          ("device-code" "code-compare") code
          "oauth" back-to
          "script" command
          "form" (mapv :id (:fields step))
          "wait" (:note step)
          "confirm" (:question step)
          "link" (:url step)
          nil)])

(defn ^:private say!
  "Says what to do at the session's step, unless it was (`said`): what was
  said then."
  [url {:keys [step] :as session} said]
  (let [what (said-of session)]
    (if (or (nil? step) (contains? said what))
      said
      (do (case (:kind step)
            "device-code" (if (some #(= "device-code" (first %)) said)
                            (println (str "That code expired: enter this one instead, " (:code step)))
                            (say-device-code session))
            "oauth" (say-oauth session)
            "script" (say-script session)
            "code-compare" (ask-to-allow! url session)
            "form" (say-form session)
            "wait" (some-> (:note step) println)
            "confirm" (println (str (:question step) " (answer on grog's screen)"))
            "link" (say-link session)
            nil)
          (flush)
          (conj said what)))))

;; What `grog connect` says once it's over, by integration: the provider's
;; sign-in by default, :mcp for MCP servers.
(def ^:private outcomes
  {"computer" {"done" "Paired: %s. Ask grog to act on it."
               "failed" "Pairing failed: %s"
               "denied" "It wasn't allowed."
               "expired" "No computer asked to pair in time."
               "cancelled" "Pairing was closed."}
   :sign-in {"done" "Signed in as %s: grog now uses your subscription."
             "failed" "Sign-in failed: %s"
             "denied" "It wasn't allowed."
             "expired" "The sign-in expired."
             "cancelled" "The sign-in was cancelled."}
   :mcp {"done" "Connected: %s. Ask grog to use it (grog tools <id> lists its tools)."
         "failed" "Connecting failed: %s"
         "denied" "It wasn't allowed."
         "expired" "It took too long: try again."
         "cancelled" "It was cancelled."}})

(defn ^:private follow
  "Says what to do as the `session` just opened goes, until it's over:
  whether it connected. Signing in with codes goes on with a new one when
  the session's time is up."
  [url session mcp?]
  (loop [{:keys [status integration] :as session} session
         said #{}]
    (let [said (say! url session said)
          texts (if mcp? (:mcp outcomes) (get outcomes integration (:sign-in outcomes)))]
      (case status
        "open"
        (do (Thread/sleep 1000)
            (let [[ok? now] (call :get url "/v1/connect")]
              (cond
                (not ok?) (fail now)
                (not= integration (:integration now)) (fail "Something else is being connected to grog now.")
                :else (recur now said))))

        "done"
        (do (println (str/replace (format (texts "done") (:connected session)) "<id>" integration))
            true)

        "expired"
        (if (some #(= "device-code" (first %)) said)
          (let [[ok? again] (call :post url (str "/v1/integrations/" integration "/connect") {:way (:way session)})]
            (if ok? (recur again said) (fail again)))
          (fail (texts "expired")))

        "failed"
        (fail (format (texts "failed") (:error session)))

        (fail (get texts status "It's over."))))))

(defn connect!
  "Connects integration `id` (`way`: how, nil for its usual way), saying
  what to do as it goes, until it's over: whether it connected."
  [url id way]
  (let [[ok? session] (call :post url (str "/v1/integrations/" id "/connect") (cond-> {} way (assoc :way way)))
        [_ listed] (when ok? (call :get url "/v1/integrations"))
        mcp? (some #(and (= id (:id %)) (:mcp %)) (:integrations listed))]
    (if ok?
      (follow url session (boolean mcp?))
      (fail session))))

(defn login!
  "Signs in to the subscription of `provider` (the server's when nil), like
  `grog connect <provider>`: with a code entered on any phone or computer,
  or in a browser coming back to this machine (`browser?`). Whether it
  did."
  ([url provider]
   (login! url provider false))
  ([url provider browser?]
   (if-let [provider (resolve-provider url provider)]
     (connect! url provider (when browser? "browser"))
     false)))

(defn logout!
  "Signs out of `provider` (the server's when nil)."
  [url provider]
  (let [provider (resolve-provider url provider)
        [ok? body] (when provider (call :delete url (str "/v1/connections/" provider)))]
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

(defn connection-line
  "A connection as `grog integrations` lists it: its id, then what it is
  (its name, unless its id says it), how it is, and since when."
  [{:keys [id name detail status added-at]}]
  (let [said (remove nil? [(when-not (or (= id name) (str/ends-with? (str id) (str ":" name))) name)
                           detail
                           (when (= "signed-out" status) "needs signing in again")
                           (some->> added-at day (str "since "))])]
    (str/trimr (format "  %-18s %s" id (str/join ", " said)))))

(defn integration-heading
  "An integration as `grog integrations` heads it: its title, how to
  connect it, and for MCP servers whether the user added it (grog didn't
  check it) and whether it's in the cloud."
  [{:keys [id title added cloud]}]
  (let [notes (remove nil? [(when added "added by you") (when cloud "in the cloud")])]
    (str (format "%s (grog connect %s)" title id)
         (when (seq notes) (str ": " (str/join ", " notes))))))

(defn add-request
  "What `grog integrations add` asks grog for, from what it was given: a
  package (npm:..., pypi:...), an address, or a name in the MCP Registry."
  [what]
  (let [what (str/trim (str what))]
    (cond
      (re-find #"^(npm|pypi):" what) {:package what}
      (re-find #"(?i)^https?://" what) {:url what}
      :else {:name what})))

(defn added-lines
  "What `grog integrations add` says once grog added the server `request`
  asked for, as integration `id`."
  [request id {:keys [cloud]}]
  (let [{:keys [package url]} request
        kind (some->> package (re-find #"^(npm|pypi):") second {"npm" "npm" "pypi" "PyPI"})]
    (remove nil?
            [(format "Added %s: connect it with grog connect %s" id id)
             (cond
               package (str "grog didn't check it: it runs code from " kind " (" package "), apart from grog.")
               url (str "grog didn't check it: grog talks to " url " and does what it says.")
               :else "grog didn't check it: it runs code from npm or PyPI apart from grog, or talks to its address.")
             (when cloud "It's in the cloud: what grog sends it leaves home.")])))

(defn add-integration!
  "Adds the MCP server `what` names to what grog can connect to."
  [url what]
  (let [request (add-request what)
        [ok? {:keys [id integration] :as body}] (call :post url "/v1/integrations" request)]
    (if ok?
      (do (run! println (added-lines request id integration))
          true)
      (fail body))))

(defn remove-integration!
  "Removes the MCP server the user added as `id`, disconnecting it first."
  [url id]
  (let [[ok? body] (call :delete url (str "/v1/integrations/" id))]
    (if ok?
      (do (println (str "Removed " id "."))
          true)
      (fail body))))

(def ^:private line-max 100)

(defn ^:private cut [s n]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if (> (count s) n) (str (subs s 0 (max 0 (dec n))) "…") s)))

(defn search-line
  "A server the MCP Registry found, as `grog integrations search` lists it:
  its name, version, what grog runs it with, and what it is, cut short."
  [{:keys [name version runs title description]}]
  (let [head (format "%s %s (%s)" name (or version "?") (or runs "can't run here"))
        about (str/join ": " (remove str/blank? [title description]))]
    (if (str/blank? about)
      head
      (str head "  " (cut about (- line-max 2 (count head)))))))

(defn search!
  "Lists the servers of the MCP Registry whose name holds `text`."
  [url text]
  (println "Searching the MCP Registry (it can take a while)...")
  (flush)
  (let [[ok? body] (call :get url (str "/v1/integrations/search?q=" (URLEncoder/encode (str text) "UTF-8")))]
    (cond
      (not ok?) (fail body)
      (empty? (:servers body)) (do (println (str "Nothing found for \"" text "\"."))
                                   true)
      :else (do (run! (comp println search-line) (:servers body))
                (println)
                (println "Add one: grog integrations add <name>")
                true))))

(defn ^:private encode-path [s]
  (str/replace (URLEncoder/encode (str s) "UTF-8") "+" "%20"))

(defn tool-line
  "A tool of a connection as `grog tools` lists it, its name `width`
  wide: what grog does with it, whether the model sees it, its title."
  [width {:keys [name title mode default-mode shown]}]
  (str/trimr (format (str "  %-" width "s  %-14s %-7s %s")
                     name
                     (str mode (when (= mode default-mode) " (default)"))
                     (if shown "shown" "hidden")
                     (if (= title name) "" (or title "")))))

(defn tools!
  "Lists the tools of connection `id`, an MCP server's, and what grog does
  with each."
  [url id]
  (let [[ok? body] (call :get url (str "/v1/connections/" id "/tools"))
        tools (:tools body)
        width (reduce max 4 (map (comp count :name) tools))]
    (cond
      (not ok?) (fail body)
      (empty? tools) (do (println (str id " has no tools."))
                         true)
      :else (do (run! (comp println (partial tool-line width)) tools)
                (println)
                (println (str "Change one: grog tools " id " <tool> allow|ask|deny|show|hide|default"))
                true))))

(def tool-choices
  "What `grog tools <connection> <tool>` can set, and what it asks grog for."
  {"allow" {:mode "allow"}
   "ask" {:mode "ask"}
   "deny" {:mode "deny"}
   "show" {:show true}
   "hide" {:show false}
   "default" {:mode nil :show nil}})

(defn set-tool!
  "Sets what grog does with `tool` of connection `id`: `choice`, one of
  `tool-choices`."
  [url id tool choice]
  (if-let [chosen (tool-choices choice)]
    (let [[ok? body] (call :put url (str "/v1/connections/" id "/tools/" (encode-path tool)) chosen)]
      (if ok?
        (do (println (str/triml (tool-line (count (:name body)) body)))
            true)
        (fail body)))
    (fail (str "Say allow, ask, deny, show, hide or default, not " (pr-str choice) "."))))

(defn integrations!
  "Lists what grog can connect to, and what's connected."
  [url]
  (let [[ok? body] (call :get url "/v1/integrations")]
    (if-not ok?
      (fail body)
      (do (doseq [{:keys [connections] :as integration} (:integrations body)]
            (println (integration-heading integration))
            (if (seq connections)
              (run! (comp println connection-line) connections)
              (println "  nothing connected")))
          true))))

(defn disconnect!
  "Disconnects connection `id` (`grog integrations` lists them). A computer
  also stops letting grog in, if it can be reached."
  [url id]
  (let [[ok? {:keys [name unpaired] :as body}] (call :delete url (str "/v1/connections/" id))]
    (cond
      (not ok?) (fail body)
      unpaired (do (println (format "Forgot %s: it doesn't let grog in anymore." name))
                   true)
      (contains? body :unpaired) (do (println (format (str "Forgot %s. It couldn't be reached: on it, take grog's "
                                                           "line out of ~/.ssh/authorized_keys.")
                                                      name))
                                     true)
      :else (do (println (str "Disconnected " id "."))
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
