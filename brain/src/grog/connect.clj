(ns grog.connect
  "Connecting something to grog (plans/integrations.md): a session, which
  someone at grog opens (its skin, its voice, its CLI: the local API only),
  one at a time, for `window-ms`, going through the steps of the
  integration it connects (grog.integrations): a code to enter on a
  provider's site (`device-code!`), signing in in a browser (`oauth!`),
  fields to fill in (`form!`), the same code on both screens, allowed at
  grog (`code-compare!`), a command to run (a script step, with
  :command), a question allowed at grog (`confirm!`), or a link to open
  (`link!`). Asking something of whoever is at grog (a tool call, what an
  MCP server needs mid-call) opens a session too. While it's open, and `linger-ms` after, for whoever follows it
  to hear how it went, grog listens on the network (`integrations.host`,
  `.port`), where a page shows its steps, behind a token only grog's screen
  and CLI show:

    GET  /c/<token>          the page (resources/grog/connect.html)
    GET  /c/<token>/state    what it shows now (`page-view`), polled
    POST /c/<token>/<what>   what's entered there, as JSON: a form's fields
                             (form), where a browser sign-in ended (oauth),
                             another way to connect ({way}, way)
    GET  /c/callback         where a browser sign-in comes back to, when it
                             comes back to grog on the network (or through
                             the relay page)

  The integration answers the rest, if it does (its :handle). Whoever
  watches grog sees the session as `view` tells (`connect` in grog.events),
  never its secrets.

  What the engine takes of an integration:

    :id :title  what it is, and its name on the screen
    :heading    what connecting it is called, the card's title: \"Sign in
                with ChatGPT\"
    :ways       how it connects, [{:id :label}], the usual way first
    :network?   whether it can't connect without grog on the network
    :start!     (fn [ctx state way]): starts connecting `way`, showing the
                session's first step (`step!`, or a step's own function),
                or throws ex-info saying why it can't. `state`: the
                session's, with its :generation, and where grog is on the
                network (`address`)
    :handle     optional, (fn [ctx state req]): answers what else comes on
                the network while its session is the last one, `state`
                saying how it is"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.events :as events]
            [grog.log :as log]
            [grog.oauth :as oauth]
            [org.httpkit.server :as http]
            [selmer.parser :as selmer])
  (:import (java.net BindException DatagramSocket InetAddress URI)
           (java.security MessageDigest SecureRandom)))

(def window-ms
  "How long a session stays open, from when it was last opened. Tests
  redefine it."
  (* 10 60 1000))

(def linger-ms
  "How long grog stays on the network once a session is over, for whoever
  follows it there to hear how it went. Tests redefine it."
  15000)

(def poll-failures-max
  "How many times in a row asking whether a code was entered fails (the
  network, say) before the session does. Tests redefine it."
  5)

;; What a request may weigh.
(def ^:private max-body-bytes 16384)

;; What a session's token is made of: neither 0 and O, nor 1 and I.
(def ^:private token-chars "23456789ABCDEFGHJKLMNPQRSTUVWXYZ")
(def ^:private token-length 10)

;; Listening, and leaving the network, one at a time.
(def ^:private listen-lock (Object.))

;; Opening sessions, one at a time.
(def ^:private open-lock (Object.))

(defn ^:private now []
  (System/currentTimeMillis))

(defn ^:private new-token []
  (let [random (SecureRandom.)]
    (apply str (repeatedly token-length #(nth token-chars (.nextInt random (count token-chars)))))))

(defn ^:private live?
  "Whether `state` is the session `generation`, still open."
  [state generation]
  (and (some? generation) (= generation (:generation state)) (= "open" (:status state))))

(defn ^:private host-port [addresses port]
  (let [address (or (first addresses) "127.0.0.1")]
    (str (if (str/includes? address ":") (str "[" address "]") address) ":" port)))

(defn address
  "Where devices reach grog on the network, host:port (an IPv6 address in
  brackets), as `state` says, or nil while grog isn't listening there."
  [{:keys [addresses port stop]}]
  (when (and stop port)
    (host-port addresses port)))

(defn link
  "The session's page, for grog's screen to show, or nil while grog isn't
  on the network."
  [{:keys [token] :as state}]
  (when-let [at (and token (address state))]
    (str "http://" at "/c/" token)))

(defn view
  "The session as whoever watches grog sees it (grog.events) and the API
  tells it, or nil before any: {:integration :title :heading :way :ways
  :status :kept :until :at}, what its :step shows but what only its page
  does, its :link while grog is on the network, the :error when it failed,
  and what got :connected once it's done (the account, the computer).
  Statuses: open, done, denied, expired, cancelled, failed. :kept: grog
  opened it itself."
  [{:keys [integration way kept? status step until at error connected] :as state}]
  (when status
    (cond-> {:integration (:id integration)
             :title (:title integration)
             :heading (:heading integration)
             :way way
             :ways (:ways integration)
             :status status
             :kept (boolean kept?)
             :until until
             :at at}
      step (assoc :step (dissoc step :page))
      (link state) (assoc :link (link state))
      error (assoc :error error)
      connected (assoc :connected connected))))

(defn ^:private whole-step [step]
  (some-> step (dissoc :page) (merge (:page step))))

(defn page-view
  "What the session's page shows, for whoever has its link: {:name :title
  :heading :status :way :ways :left-s}, its :step whole, the :error when it
  failed, and what got :connected."
  [config {:keys [integration way status step until error connected]}]
  (cond-> {:name (:name config)
           :title (:title integration)
           :heading (:heading integration)
           :status status
           :way way
           :ways (:ways integration)
           :left-s (max 0 (quot (- (or until 0) (now)) 1000))}
    step (assoc :step (whole-step step))
    error (assoc :error error)
    connected (assoc :connected connected)))

(defn ^:private opened
  "What whoever opened the session is told: its view, with its step whole."
  [state]
  (cond-> (view state)
    (:step state) (assoc :step (whole-step (:step state)))))

(defn ^:private publish! [ctx]
  (events/publish! (:events ctx) {:type :connect :connect (view @(:connect ctx))}))

(defn ^:private run-all!
  "Runs the cleanups of `state` (`on-end!`), saying how it ended."
  [state how]
  (doseq [f (:cleanups state)]
    (try (f how)
         (catch Exception e (log/info "connect: a cleanup failed:" (.getSimpleName (class e)))))))

(defn ^:private stop-listener!
  "Leaves the network, if grog was there."
  [ctx]
  (locking listen-lock
    (let [[before] (swap-vals! (:connect ctx) #(some-> % (dissoc :stop)))]
      (when-let [stop (:stop before)]
        (deref (stop) 2000 nil)
        (log/info "connect: no longer on the network")))))

(defn ^:private end!
  "Ends the session `generation`, if it's open, as `status`, with
  `changes`: whoever watches is told, what its steps started stops, and grog
  leaves the network `linger-ms` later, unless another session opens.
  Whether it did."
  [ctx generation status changes]
  (let [[before] (swap-vals! (:connect ctx) #(if (live? % generation)
                                               (-> (merge % changes)
                                                   (assoc :status status :at (now))
                                                   (dissoc :handlers :cleanups))
                                               %))]
    (when (live? before generation)
      (log/info (format "connect %s: %s" (get-in before [:integration :id]) status))
      (publish! ctx)
      (run-all! before status)
      (future
        (Thread/sleep linger-ms)
        (when (= generation (:generation @(:connect ctx)))
          (stop-listener! ctx)))
      true)))

(defn done!
  "The session `generation` connected `connected` (an account, a
  computer: its name on the screen)."
  [ctx generation connected]
  (end! ctx generation "done" {:connected connected}))

(defn fail!
  "The session `generation` failed, saying why."
  [ctx generation error]
  (end! ctx generation "failed" {:error error}))

(defn deny!
  "Someone at grog didn't allow what the session `generation` asked."
  [ctx generation]
  (end! ctx generation "denied" {}))

(defn later!
  "Runs (f) in `ms`, while the session `generation` is still open then."
  [ctx generation ms f]
  (future
    (Thread/sleep ms)
    (when (live? @(:connect ctx) generation)
      (try (f)
           (catch Exception e (log/info "connect:" (.getSimpleName (class e))))))))

(defn on-end!
  "Runs (f how) once the session `generation` is over (how: its status), or
  goes another way (\"switched\"): to stop what a step started. At once,
  when it's over already."
  [ctx generation f]
  (let [[before] (swap-vals! (:connect ctx) #(cond-> % (live? % generation) (update :cleanups (fnil conj []) f)))]
    (when-not (live? before generation)
      (f (:status before)))))

(defn step!
  "Shows `step` in the session `generation` ({:kind ...}, what only its page
  shows under :page), answering with `handlers` while it's shown: {:decide
  (fn [allow?]) :submit {what (fn [body]) -> what to answer} :callback (fn
  [params]) -> {:ok :message}}. Whether the session is still open."
  ([ctx generation step]
   (step! ctx generation step nil))
  ([ctx generation step handlers]
   (let [[before] (swap-vals! (:connect ctx) #(cond-> % (live? % generation) (assoc :step step :handlers handlers :at (now))))]
     (when (live? before generation)
       (publish! ctx)
       true))))

(defn change!
  "Applies (f state) to the session `generation` while it's open (to keep
  what an integration needs in its :data), telling whoever watches when
  that changes what they see: the state after, or nil when it wasn't open."
  [ctx generation f]
  (let [[before after] (swap-vals! (:connect ctx) #(if (live? % generation) (f %) %))]
    (when (live? before generation)
      (when (not= (view before) (view after))
        (publish! ctx))
      after)))

(defn ^:private claim!
  "Claims the session `generation` to finish it, once (a repeated callback
  can't trade a code twice), showing `note` meanwhile. Whether it did."
  [ctx generation note]
  (let [claims? #(and (live? % generation) (not (:finishing? %)))
        [before] (swap-vals! (:connect ctx) #(cond-> %
                                               (claims? %) (assoc :finishing? true
                                                                  :step {:kind "wait" :note note}
                                                                  :handlers nil
                                                                  :at (now))))]
    (when (claims? before)
      (publish! ctx)
      true)))

(defn ^:private complete!
  "Finishes the session `generation` with (f): what got connected. nil once
  it's done; otherwise why not."
  [ctx generation note f]
  (if (claim! ctx generation note)
    (try
      (done! ctx generation (f))
      nil
      (catch Exception e
        (let [message (or (ex-message e) (.getSimpleName (class e)))]
          (fail! ctx generation message)
          message)))
    "it's past that: see what grog's screen says"))

(defn device-code!
  "Takes the session `generation` through a device-code step (RFC 8628):
  (code!) gets a code, {:user-code :url :interval-s}, good for `ttl-ms`, to
  enter at its :url, `help` ({:text :url}) saying what may have to be done
  first; (poll! code) tells whether it was entered, nil until then, then a
  grant, which (finish! grant) turns into what got connected, showing `note`
  meanwhile. A new code once one expires. The session fails once getting
  one fails, or asking keeps failing (`poll-failures-max` times in a row).
  Throws what (code!) throws the first time."
  [ctx generation {:keys [code! poll! finish! ttl-ms help note]}]
  (let [show! #(step! ctx generation (cond-> {:kind "device-code"
                                              :code (:user-code %)
                                              :url (:url %)
                                              :expires-at (+ (now) ttl-ms)}
                                       help (assoc :help help)))
        first-code (code!)]
    (show! first-code)
    (future
      (loop [code first-code
             failures 0]
        (Thread/sleep (* 1000 (:interval-s code)))
        (let [state @(:connect ctx)]
          (when (and (live? state generation) (not (:finishing? state)))
            (if (>= (now) (get-in state [:step :expires-at] 0))
              (let [next-code (try (code!) (catch Exception e e))]
                (if (instance? Exception next-code)
                  (fail! ctx generation (ex-message next-code))
                  (do (show! next-code)
                      (recur next-code 0))))
              (let [result (try (poll! code) (catch Exception e e))]
                (cond
                  (nil? result)
                  (recur code 0)

                  (instance? Exception result)
                  (if (< (inc failures) poll-failures-max)
                    (recur code (inc failures))
                    (fail! ctx generation (ex-message result)))

                  :else
                  (complete! ctx generation note #(finish! result)))))))))
    nil))

(defn ^:private same? [^String a ^String b]
  (and (string? a) (string? b) (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))

(defn ^:private callback!
  "What a browser brought back from signing in (`params`, from a callback,
  or pasted) does to the oauth step of the session `generation`:
  {:ok :message}."
  [ctx generation {:keys [state verifier redirect-uri issuer iss-required? finish! note]} params]
  (let [{:keys [code error error_description iss]} params]
    (cond
      (not (live? @(:connect ctx) generation))
      {:ok false :message "This doesn't belong to a sign-in in progress: start again."}

      (not (same? state (:state params)))
      (do (log/info "connect: refused a callback of another sign-in (an older link?)")
          {:ok false :message "This doesn't belong to the sign-in in progress (an older link?): start again."})

      (and iss-required? (str/blank? iss))
      (do (fail! ctx generation "the sign-in came back without saying where from")
          {:ok false :message "It came back without saying where from (no iss)."})

      (and issuer iss (not= issuer iss))
      (do (fail! ctx generation (str "the sign-in came back from " iss ", not " issuer))
          {:ok false :message (str "It came back from " iss ", not " issuer ".")})

      error
      (let [message (str (or error_description error))]
        (fail! ctx generation message)
        {:ok false :message message})

      (str/blank? code)
      {:ok false :message "There's no code in it: paste the whole address the browser ended at."}

      :else
      (if-let [failure (complete! ctx generation note #(finish! {:code code :verifier verifier :redirect-uri redirect-uri}))]
        {:ok false :message failure}
        {:ok true :message "Signed in."}))))

(defn ^:private callback-page
  "What the browser shows once it came back from signing in, as `result`
  says."
  [config {:keys [ok message]}]
  (if ok
    (oauth/page (str "Signed in to " (:name config)) "You can close this tab.")
    (oauth/page "Sign-in failed" message)))

(defn ^:private pasted
  "The parameters of `url`, the address a browser ended at, signing in: {}
  without any."
  [url]
  (or (when (and (string? url) (<= (count url) 8192))
        (when-let [[_ query] (re-find #"\?([^#\s]*)" url)]
          (try (oauth/form-decode query)
               (catch IllegalArgumentException _ nil))))
      {}))

(defn ^:private stop-server!
  "Stops `server`, waiting for its port to be free again."
  [server]
  (when (= :running (http/server-status server))
    (deref (http/server-stop! server) 2000 nil)))

(defn oauth!
  "Takes the session `generation` through an oauth step: the authorization
  code flow, with PKCE (S256) and a state bound to the session. The browser
  signs in at (authorize-url {:challenge :state :redirect-uri}), then comes
  back to `redirect-uri`: a loopback address grog listens on for it, as
  the provider has it (http://localhost:<port><path>); :lan, grog's page
  on the network (/c/callback); or {:relay base}, the relay page's
  callback/ (`integrations.relay`), which sends the browser on to grog's
  page, the state saying where grog is (<address>~<random>). Wherever the
  browser is, whoever signs in can paste the address it ended at on the
  page. (finish! {:code :verifier :redirect-uri}) turns the code into what
  got connected, showing `note` meanwhile. When `issuer` is given, the
  authorization server must be it, if it says (RFC 9207), and must say so
  with `iss-required?`. Returns where the browser signs in. Throws ex-info
  when grog can't listen where the browser comes back."
  [ctx generation {:keys [redirect-uri authorize-url finish! issuer iss-required? note]}]
  (let [{:keys [verifier challenge]} (oauth/pkce)
        relay (when (map? redirect-uri) (:relay redirect-uri))
        local? (string? redirect-uri)
        at (when-not local?
             (or (address @(:connect ctx))
                 (throw (ex-info "grog isn't on the network for the browser to come back to"
                                 {:type ::not-listening}))))
        state (if relay (str at "~" (oauth/random-token)) (oauth/random-token))
        redirect (cond relay (str relay "callback/")
                       local? redirect-uri
                       :else (str "http://" at "/c/callback"))
        url (authorize-url {:challenge challenge :state state :redirect-uri redirect})
        on-callback #(callback! ctx generation {:state state :verifier verifier :redirect-uri redirect :issuer issuer
                                                :iss-required? iss-required? :finish! finish! :note note}
                                %)]
    (when local?
      (let [uri (URI. redirect)
            server (try
                     (oauth/start-callback-server! (.getPort uri) (.getPath uri)
                                                   #(callback-page @(:config ctx) (on-callback %)))
                     (catch BindException _
                       (throw (ex-info (format "Port %d is busy: is another program signing in?" (.getPort uri))
                                       {:type ::port-busy}))))]
        ;; Time for its page to reach the browser.
        (on-end! ctx generation (fn [_] (future (Thread/sleep 1000) (stop-server! server))))))
    (step! ctx generation {:kind "oauth" :back-to redirect :page {:url url}}
           {:callback on-callback
            :submit {"oauth" (fn [{:keys [url]}]
                               (let [{:keys [ok message]} (on-callback (pasted url))]
                                 (if ok
                                   {:ok true}
                                   (throw (ex-info message {:type ::not-signed-in})))))}})
    url))

(defn ^:private bad [label what]
  (throw (ex-info (str label ": " what) {:type ::bad-field})))

(defn ^:private blank? [v]
  (or (nil? v) (and (string? v) (str/blank? v)) (and (sequential? v) (empty? v))))

(defn ^:private parse-time [f s]
  (try (f s) true (catch java.time.format.DateTimeParseException _ false)))

(defn ^:private formatted?
  "Whether the text `s` looks like `format` (loosely)."
  [format s]
  (case format
    "email" (boolean (re-matches #"[^@\s]+@[^@\s]+" s))
    "uri" (boolean (try (some? (.getScheme (URI. s))) (catch Exception _ false)))
    "date" (and (boolean (re-matches #"\d{4}-\d{2}-\d{2}" s)) (parse-time #(java.time.LocalDate/parse %) s))
    "date-time" (or (parse-time #(java.time.OffsetDateTime/parse %) s)
                    (parse-time #(java.time.LocalDateTime/parse %) s))
    true))

(def ^:private format-names
  {"email" "an email address" "uri" "an address (https://...)" "date" "a date (YYYY-MM-DD)"
   "date-time" "a date and time"})

(defn ^:private within
  "`n` (a number, or how many), unless `min` or `max` say otherwise."
  [{:keys [label min max]} n what]
  (cond (and min (< n min)) (bad label (str "at least " min what))
        (and max (> n max)) (bad label (str "at most " max what))
        :else n))

(defn ^:private text-value [{:keys [label format] :as field} v]
  (cond
    (not (string? v)) (bad label "text")
    (> (count v) 2048) (bad label "too long")
    :else (let [s (str/trim v)]
            (within field (count s) " characters")
            (if (formatted? format s) s (bad label (str "not " (format-names format)))))))

(defn ^:private number-value [{:keys [label type] :as field} v]
  (let [n (cond (number? v) (double v)
                (string? v) (try (Double/parseDouble (str/trim v)) (catch NumberFormatException _ nil)))]
    (cond
      (or (nil? n) (Double/isNaN n) (Double/isInfinite n)) (bad label "a number")
      (and (= "integer" type) (not= n (Math/floor n))) (bad label "a whole number")
      :else (do (within field n "")
                (if (= "integer" type) (long n) n)))))

(defn ^:private boolean-value [{:keys [label]} v]
  (cond (boolean? v) v
        (nil? v) false
        (#{"true" "on"} v) true
        (= "false" v) false
        :else (bad label "yes or no")))

(defn ^:private choice-of
  "The choice of `field` whose value is `v`, or nil."
  [{:keys [choices]} v]
  (some #(when (= (str (:value %)) (str v)) %) choices))

(defn ^:private choice-value [{:keys [label] :as field} v]
  (if-let [choice (and (not (coll? v)) (choice-of field v))]
    (:value choice)
    (bad label "pick one of the choices")))

(defn ^:private choices-value [{:keys [label] :as field} v]
  (let [vs (cond (sequential? v) v (nil? v) [] :else (bad label "pick among the choices"))
        picked (mapv #(or (and (not (coll? %)) (:value (choice-of field %)))
                          (bad label "pick among the choices"))
                     (distinct vs))]
    (within field (count picked) " of them")
    picked))

(defn ^:private field-value
  "What was entered in `field` (`v`, from JSON), as its type has it."
  [{:keys [type] :as field} v]
  (case (or type "text")
    ("number" "integer") (number-value field v)
    "boolean" (boolean-value field v)
    "choice" (choice-value field v)
    "choices" (choices-value field v)
    (text-value field v)))

(defn form-values
  "What was entered in `fields` (see `form!`), as `body` says: {id value},
  typed (text trimmed, a double, a long, true or false, a choice's value, a
  vector of them), without what's blank (but booleans: unchecked is
  false). Throws ex-info saying what's missing or wrong."
  [fields body]
  (reduce (fn [values {:keys [id label optional type min] :as field}]
            (let [v (get body (keyword id))]
              (cond
                (= "boolean" type) (assoc values id (field-value field v))
                (and (string? v) (> (count v) 2048)) (bad label "too long")
                (not (blank? v)) (assoc values id (field-value field v))
                optional values
                (and (= "choices" type) (= 0 min)) (assoc values id [])
                (#{"choice" "choices"} type) (bad label "pick one")
                :else (bad label "fill it in"))))
          {}
          fields))

(def ^:private field-keys
  [:id :label :example :secret :optional :type :choices :default :min :max :description :format])

(defn form!
  "Takes the session `generation` through a form step: `fields` to fill in
  on its page, which warns that what's :secret goes over the network as it
  is. Each field: {:id (kebab-case) :label}, and maybe :example, :secret,
  :optional, :description, :default (filled in at first), and its :type:
  text (the usual: :format email, uri, date or date-time; :min and :max
  characters), number or integer (between :min and :max), boolean (a
  checkbox), choice (one of :choices, [{:value :label}]) or choices
  (between :min and :max of them). (submit! values), with what was entered
  (`form-values`), takes the session on (another step, or done!), or throws
  ex-info saying what's wrong, which the page shows."
  [ctx generation {:keys [fields submit!]}]
  (step! ctx generation {:kind "form" :fields (mapv #(select-keys % field-keys) fields)}
         {:submit {"form" (fn [body]
                            (submit! (form-values fields body))
                            {:ok true})}}))

(defn code-compare!
  "Takes the session `generation` through a code-compare step: `code` shows
  on grog's screen, like on what asks to connect (`name`, as `user`), and
  someone at grog allows it, or not: (decide! allow?)."
  [ctx generation {:keys [code name user decide!]}]
  (step! ctx generation (cond-> {:kind "code-compare" :code code}
                          name (assoc :name name)
                          user (assoc :user user))
         {:decide decide!}))

(defn confirm!
  "Takes the session `generation` through a confirm step: `question` (and
  its `detail`) shows on grog's screen, where someone allows it, or not:
  (decide! allow?). Its page only says to answer at grog."
  [ctx generation {:keys [question detail decide!]}]
  (step! ctx generation (cond-> {:kind "confirm" :question question}
                          detail (assoc :detail detail))
         {:decide decide!}))

(defn link!
  "Takes the session `generation` through a link step: `url` to open (its
  QR code on grog's screen, the link on its page), saying why (`message`);
  once someone at grog says it's done there, (done!). Saying no denies the
  session; Cancel cancels it."
  [ctx generation {:keys [url message done!]}]
  (step! ctx generation {:kind "link" :url url :message message}
         {:decide #(if % (done!) (deny! ctx generation))}))

(defn decide!
  "Allows what the session's step asks to be allowed (`allow?`), or not:
  the session then (`view`). Throws ex-info when nothing asks."
  [ctx allow?]
  (let [[before] (swap-vals! (:connect ctx) #(cond-> % (get-in % [:handlers :decide]) (update :handlers dissoc :decide)))
        f (get-in before [:handlers :decide])]
    (when-not (and f (live? before (:generation before)))
      (throw (ex-info "nothing is waiting to be allowed" {:type ::nothing-to-decide})))
    (f allow?)
    (view @(:connect ctx))))

(defn ^:private json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"
             "Cache-Control" "no-store"}
   :body (config/generate body)})

(defn ^:private read-json [req]
  (try (some-> (:body req) slurp config/parse)
       (catch Exception _ nil)))

(defn ^:private json? [{:keys [headers]}]
  (boolean (some-> (get headers "content-type") str/lower-case (str/starts-with? "application/json"))))

;; The page only talks to grog, from where it came, and sends no Referer
;; (its address has the token) to the sites it links to.
(def ^:private page-headers
  {"Content-Type" "text/html; charset=utf-8"
   "Cache-Control" "no-store"
   "Referrer-Policy" "no-referrer"
   "X-Content-Type-Options" "nosniff"
   "Content-Security-Policy" (str "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                                  "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")})

(defn ^:private page [config state]
  {:status 200
   :headers page-headers
   :body (selmer/render (slurp (io/resource "grog/connect.html"))
                        {:name (:name config) :heading (get-in state [:integration :heading])})})

(defn ^:private token-of?
  "Whether `token` is the session's (as typed: in any case)."
  [state token]
  (same? (:token state) (str/upper-case (str token))))

(declare open!)

(defn ^:private submit
  "What the session's page sends, `what` saying what it is."
  [ctx state what req]
  (let [body (read-json req)
        f (get-in state [:handlers :submit what])]
    (cond
      (not (live? state (:generation state)))
      (json-response 409 {:error "It's over: see what the page says now."})

      (not (map? body))
      (json-response 400 {:error "Send a JSON object."})

      (= "way" what)
      (try (open! ctx (:integration state) (:way body) (select-keys state [:kept? :window-ms]))
           (json-response 200 (page-view @(:config ctx) @(:connect ctx)))
           (catch clojure.lang.ExceptionInfo e
             (json-response 400 {:error (ex-message e)})))

      (nil? f)
      (json-response 409 {:error "It's past that: see what the page says now."})

      :else
      (try (json-response 200 (or (f body) {:ok true}))
           (catch clojure.lang.ExceptionInfo e
             (json-response 400 {:error (ex-message e)}))))))

(defn handle
  "Answers what comes on the network while grog is there (see the ns)."
  [ctx {:keys [request-method uri query-string] :as req}]
  (let [config @(:config ctx)
        state @(:connect ctx)
        [_ token what] (re-matches #"/c/([A-Za-z0-9]{10,64})(?:/([a-z]+))?" uri)]
    (cond
      (and (= :post request-method) (str/starts-with? uri "/c/") (not (json? req)))
      (json-response 415 {:error "Send requests with Content-Type: application/json."})

      (= [:get "/c/callback"] [request-method uri])
      (let [f (get-in state [:handlers :callback])
            params (try (oauth/form-decode query-string)
                        (catch IllegalArgumentException _ {}))]
        {:status 200
         :headers (select-keys page-headers ["Content-Type" "Cache-Control" "Referrer-Policy"])
         :body (callback-page config (if f
                                       (f params)
                                       {:ok false :message "This doesn't belong to a sign-in in progress: start again."}))})

      (and token (token-of? state token))
      (cond
        (and (= :get request-method) (nil? what)) (page config state)
        (and (= :get request-method) (= "state" what)) (json-response 200 (page-view config state))
        (= :post request-method) (submit ctx state what req)
        :else (json-response 404 {:error "Not found."}))

      (and (nil? token) (:status state) (get-in state [:integration :handle]))
      ((get-in state [:integration :handle]) ctx state req)

      :else
      (json-response 404 {:error "Not found."}))))

(defn address-toward
  "grog's address on the way to `host`, or nil: where it comes from."
  [host]
  (try
    (with-open [socket (DatagramSocket.)]
      (.connect socket (InetAddress/getByName host) 9)
      (some-> (.getLocalAddress socket) .getHostAddress))
    (catch Exception _ nil)))

(defn ^:private addresses
  "Where devices reach grog: the address it listens on, or else its own on
  the way out."
  [config]
  (let [host (get-in config [:integrations :host])]
    (if (contains? #{"0.0.0.0" "::"} host)
      (vec (keep identity [(address-toward "192.0.2.1")]))
      [host])))

(defn ^:private listen!
  "Goes on the network (`integrations.host`, `.port`), unless grog is
  there already. Throws ex-info when it can't."
  [ctx config]
  (locking listen-lock
    (when-not (:stop @(:connect ctx))
      (let [{:keys [host port]} (:integrations config)
            server (try
                     (http/run-server #(handle ctx %) {:ip host :port port :max-body max-body-bytes
                                                       :legacy-return-value? false})
                     (catch java.io.IOException e
                       (throw (ex-info (format "grog can't listen on %s:%d: %s"
                                               host port (or (ex-message e) (.getSimpleName (class e))))
                                       {:type ::cant-listen}))))]
        (log/info (format "connect: listening on %s:%d" host (http/server-port server)))
        (swap! (:connect ctx) assoc
               :stop #(http/server-stop! server)
               :port (http/server-port server)
               :addresses (addresses config))))))

(defn ^:private expire-later!
  "Ends the session `generation` as expired once its time is up, which
  opening it again pushes back."
  [ctx generation]
  (future
    (loop []
      (let [state @(:connect ctx)
            left (- (or (:until state) 0) (now))]
        (when (live? state generation)
          (if (pos? left)
            (do (Thread/sleep left)
                (recur))
            (end! ctx generation "expired" {})))))))

(defn ^:private start-way!
  "Starts the session `generation` going `way` (the integration's :start!).
  The session fails when it can't, and what was thrown goes on."
  [ctx generation integration way]
  (expire-later! ctx generation)
  (try
    ((:start! integration) ctx @(:connect ctx) way)
    (catch Exception e
      (fail! ctx generation (or (ex-message e) (.getSimpleName (class e))))
      (throw e))))

(defn ^:private way-of
  "`way` of `integration` (its usual one when nil). Throws ex-info when it
  has no such way."
  [integration way]
  (let [ways (map :id (:ways integration))]
    (cond
      (nil? way) (first ways)
      (some #{way} ways) way
      :else (throw (ex-info (format "%s connects %s, not %s"
                                    (:title integration) (str/join " or " ways) (pr-str way))
                            {:type ::unknown-way})))))

(defn open!
  "Opens a session connecting `integration` `way` (its usual way when nil),
  or goes on with the one open: the same way, its time starting over;
  another way, on the same page. Ends any other session first, unless
  `unless-busy?`, when it throws ex-info (::busy) instead. `kept?`: grog
  opened it itself (to sign in while it has nothing to pay with, see
  grog.signin). `window-ms`: how long it stays open (`window-ms` by
  default). `integration` needn't be one of grog.integrations: asking
  something of whoever is at grog makes up its own. Returns the session
  (`view`), with its step whole. Throws ex-info when it can't."
  ([ctx integration way]
   (open! ctx integration way nil))
  ([ctx integration way {:keys [kept? unless-busy?] :as opts}]
   (let [config @(:config ctx)
         way (way-of integration way)
         window (or (:window-ms opts) window-ms)]
     (locking open-lock
       (let [state @(:connect ctx)
             open? (live? state (:generation state))
             same? (and open? (= (:id integration) (get-in state [:integration :id])))]
         (cond
           (and open? unless-busy?)
           (throw (ex-info (str "grog is busy: " (get-in state [:integration :heading])) {:type ::busy}))

           (and same? (= way (:way state)))
           (do (swap! (:connect ctx) #(cond-> (assoc % :until (+ (now) window) :window-ms (:window-ms opts))
                                        (not kept?) (assoc :kept? false)))
               (publish! ctx))

           same?
           (let [generation (inc (:generation state))]
             (run-all! state "switched")
             (swap! (:connect ctx) assoc
                    :generation generation :way way :step nil :handlers nil :cleanups nil :finishing? false
                    :until (+ (now) window) :window-ms (:window-ms opts) :at (now))
             (log/info (format "connect %s: %s" (:id integration) way))
             (publish! ctx)
             (start-way! ctx generation integration way))

           :else
           (do (when open?
                 (end! ctx (:generation state) "cancelled" {}))
               (try
                 (listen! ctx config)
                 (catch clojure.lang.ExceptionInfo e
                   (if (:network? integration)
                     (throw e)
                     (log/info (str "connect: without a page on the network, as " (ex-message e))))))
               (let [generation (inc (or (:generation @(:connect ctx)) 0))]
                 (swap! (:connect ctx) merge {:generation generation :token (new-token) :integration integration
                                              :way way :kept? (boolean kept?) :status "open" :step nil
                                              :handlers nil :cleanups nil :finishing? false :data nil
                                              :until (+ (now) window) :window-ms (:window-ms opts) :at (now)
                                              :error nil :connected nil})
                 (log/info (format "connect %s: %s" (:id integration) way))
                 (publish! ctx)
                 (start-way! ctx generation integration way))))
         (opened @(:connect ctx)))))))

(defn cancel!
  "Cancels the session, if it's open: the session then (`view`)."
  [ctx]
  (end! ctx (:generation @(:connect ctx)) "cancelled" {})
  (view @(:connect ctx)))

(defn stop!
  "Ends the session at once, and leaves the network: grog is going away."
  [ctx]
  (end! ctx (:generation @(:connect ctx)) "cancelled" {})
  (stop-listener! ctx))
