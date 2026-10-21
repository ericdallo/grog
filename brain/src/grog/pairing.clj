(ns grog.pairing
  "Pairing a computer with grog, for grog to act on it (grog.computers).
  Someone at grog lets one pair (its skin, `grog computers add`, `POST
  /v1/pairing`): for a few minutes, grog listens on the network for one
  asking (`computers.pairing`), and serves what pairs it,
  resources/grog/computer.sh, so there's nothing to install there: its user
  runs `curl -fsS <grog's address>:8485 | bash`, or opens that address in a
  browser, which says so. Both screens show a code made from both keys, and
  someone at grog allows it when they're the same. The computer then lets
  grog's key in, only to run grog's hands there (a copy of the script), and
  only from grog's network.

  Someone in between, swapping keys, can't make the codes match (but once
  in a million): the computer commits to its nonce (sends its hash) before
  it gets grog's, and only reveals it then. Someone who can swap the script
  as it goes, over plain HTTP, could run anything there though: pairing
  trusts the network for those minutes.

  On the network, while pairing is open, answering text the script reads
  without a JSON parser, a line of key=value each (`answer-text`):
    GET  /                    the script; a page saying to run it, for
                              browsers (Accept: text/html)
    GET  /computer.sh         the script
    POST /v1/pair             {v, name, user, hostKey, mac, sshPort,
                              commitment} -> id, key, nonce, name,
                              expiresIn: grog's key and nonce
    POST /v1/pair/<id>/nonce  {nonce} -> status: grog shows the code
    GET  /v1/pair/<id>        -> status: started, asking, allowed (with
                              from, the address grog comes from), denied,
                              expired, closed, failed (with error), paired
    POST /v1/pair/<id>/check  once the computer let grog in, grog says
                              hello -> status: paired
  What fails answers error."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.computers :as computers]
            [grog.config :as config]
            [grog.events :as events]
            [grog.log :as log]
            [org.httpkit.server :as server]
            [selmer.parser :as selmer])
  (:import (java.net DatagramSocket InetAddress)
           (java.nio ByteBuffer)
           (java.security MessageDigest SecureRandom)))

(def window-ms
  "How long someone at grog lets a computer pair."
  (* 5 60 1000))

;; How long grog still answers the computer once pairing is over, for it
;; to hear how it went.
(def ^:private linger-ms 15000)

;; How long a computer has to reveal its nonce, before another may ask.
(def ^:private reveal-ms 30000)

;; How long an allowed computer has to let grog in.
(def ^:private finish-ms 60000)

;; What a request may weigh.
(def ^:private max-body-bytes 16384)

(def host-key-pattern
  "An SSH public key: its type, then its base64."
  #"(ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|ssh-rsa) [A-Za-z0-9+/]+={0,3}")

;; Opening and stopping the listener, one at a time.
(def ^:private lock (Object.))

(defn ^:private now []
  (System/currentTimeMillis))

(defn ^:private hex [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn ^:private sha-256 [^String s]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))

(defn random-hex
  "`n` random bytes, in hex."
  [n]
  (let [b (byte-array n)]
    (.nextBytes (SecureRandom.) b)
    (hex b)))

(defn commitment
  "What a computer sends before revealing its `nonce`: its hash."
  [nonce]
  (hex (sha-256 nonce)))

(defn code
  "The 6 digits both screens show: from grog's key and nonce, and the
  computer's host key and nonce (computer.sh's `pairing_code` too)."
  [key nonce host-key host-nonce]
  (let [digest (sha-256 (str/join "\n" [key nonce host-key host-nonce]))]
    (format "%06d" (mod (bit-and (.getInt (ByteBuffer/wrap digest)) 0x7fffffff) 1000000))))

(defn spaced
  "A code as it's shown: 482 913."
  [code]
  (str (subs code 0 3) " " (subs code 3)))

(defn address-toward
  "grog's address on the way to `host`, or nil: what it comes from."
  [host]
  (try
    (with-open [socket (DatagramSocket.)]
      (.connect socket (InetAddress/getByName host) 9)
      (some-> (.getLocalAddress socket) .getHostAddress))
    (catch Exception _ nil)))

(defn ^:private addresses
  "Where computers reach grog: the address it listens on, or else its own on
  the way out."
  [config]
  (let [host (get-in config [:computers :pairing :host])]
    (if (contains? #{"0.0.0.0" "::"} host)
      (vec (keep identity [(address-toward "192.0.2.1")]))
      [host])))

(defn view
  "How pairing goes, as pairing `state` says, for whoever watches
  (grog.events) and the API: nil until it's opened; then {:status :until
  :addresses :port :at}, the computer's :name, :user and the :code once
  one asks, and the :error when it failed. Statuses: open (waiting for a
  computer), asking (someone at grog allows it, or not), allowed, paired,
  denied, expired, closed, failed."
  [state]
  (when-let [{:keys [status until addresses port session error at]} state]
    (cond-> {:status status :until until :addresses addresses :port port :at at}
      (:code session) (merge (select-keys (:request session) [:name :user]) {:code (:code session)})
      error (assoc :error error))))

(defn ^:private publish! [ctx]
  (events/publish! (:events ctx) {:type :pairing :pairing (view @(:pairing ctx))}))

(defn ^:private stop-listener!
  "Stops listening on the network, if grog was."
  [ctx]
  (locking lock
    (let [[before _] (swap-vals! (:pairing ctx) #(some-> % (dissoc :stop)))]
      (when-let [stop (:stop before)]
        (stop)
        (log/info "pairing: no longer listening")))))

(defn ^:private later!
  "Runs (f) in `ms`, unless pairing was opened again since."
  [ctx ms f]
  (let [generation (:generation @(:pairing ctx))]
    (future
      (Thread/sleep ms)
      (when (= generation (:generation @(:pairing ctx)))
        (try (f)
             (catch Exception e (log/info "pairing:" (.getSimpleName (class e)))))))))

(defn ^:private finish!
  "Ends pairing with `status` (and `error`): whoever watches is told, and
  grog stops listening a moment later, once the computer heard."
  ([ctx status]
   (finish! ctx status nil))
  ([ctx status error]
   (swap! (:pairing ctx) assoc :status status :error error :at (now))
   (publish! ctx)
   (later! ctx linger-ms #(stop-listener! ctx))))

(defn answer-text
  "`fields` as the computer's script reads them (computer.sh's `call`): a
  line each, key=value, keys in lowerCamelCase, values on one line."
  [fields]
  (apply str (for [[k v] fields
                   :when (some? v)]
               (str (config/kebab->camel (name k)) "=" (str/replace (str v) #"[\r\n]+" " ") "\n"))))

(defn ^:private text-response [status fields]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (answer-text fields)})

(defn ^:private read-json [req]
  (try (some-> (:body req) slurp config/parse)
       (catch Exception _ nil)))

(defn ^:private problem
  "Ends a request with `status` and `message`."
  [status message]
  (throw (ex-info message {:status status})))

(defn check-request
  "What a computer asking to pair says, when it makes sense: {:name :user
  :host-key :mac :ssh-port :commitment}. Throws ex-info saying what's wrong
  otherwise."
  [body]
  (let [{:keys [v name user host-key mac ssh-port commitment]} (when (map? body) body)
        ssh-port (or ssh-port 22)]
    (cond
      (not= computers/protocol-version v)
      (problem 400 (format "this grog speaks version %d of pairing: run what it serves now, curl -fsS <its address> | bash"
                           computers/protocol-version))

      (not (and (string? name) (re-matches computers/name-pattern name)))
      (problem 400 "name: lowercase letters, digits and dashes, up to 32")

      (not (and (string? user) (re-matches computers/user-pattern user)))
      (problem 400 "user: a user's name")

      (not (and (string? host-key) (re-matches host-key-pattern host-key)))
      (problem 400 "hostKey: the computer's SSH host key, like \"ssh-ed25519 AAAA...\"")

      (not (or (nil? mac) (and (string? mac) (re-matches computers/mac-pattern mac))))
      (problem 400 "mac: the address of its network card, or nothing")

      (not (and (int? ssh-port) (<= 1 ssh-port 65535)))
      (problem 400 "sshPort: a port number")

      (not (and (string? commitment) (re-matches #"[0-9a-f]{64}" commitment)))
      (problem 400 "commitment: the SHA-256 of a nonce, in hex")

      :else
      {:name name :user user :host-key host-key :mac (some-> mac str/lower-case) :ssh-port ssh-port
       :commitment commitment})))

(defn ^:private can-start?
  "Whether a computer may ask to pair now, as pairing `state` says: while
  it's open, and no other is asking (but one that didn't go on)."
  [{:keys [status until session]} at]
  (and (= "open" status)
       (< at until)
       (or (nil? session)
           (and (= "started" (:status session)) (> (- at (:at session)) reveal-ms)))))

(defn ^:private start!
  "A computer asks to pair: grog's key and nonce."
  [ctx req]
  (let [config @(:config ctx)
        request (check-request (read-json req))
        host (:remote-addr req)
        at (now)
        session {:id (random-hex 16) :nonce (random-hex 16) :request request :host host :status "started" :at at}]
    (when-not (and (string? host) (re-matches computers/host-pattern host))
      (problem 400 "grog can't tell where this computer is"))
    (let [state (swap! (:pairing ctx) #(if (can-start? % at) (assoc % :session session) %))]
      (cond
        (= session (:session state))
        {:id (:id session) :key (computers/public-key! config) :nonce (:nonce session) :name (:name config)
         :expires-in (max 0 (quot (- (:until state) at) 1000))}

        (contains? #{"asking" "allowed"} (:status state))
        (problem 409 "another computer is pairing with grog: try again in a minute")

        :else
        (problem 403 "grog doesn't let a computer pair now: let one on grog first")))))

(defn ^:private session-of
  "The pairing `id` names, in `state`: a 404 when it's not the one going on."
  [state id]
  (let [session (:session state)]
    (if (= id (:id session))
      session
      (problem 404 "there's no such pairing going on"))))

(defn ^:private reveal!
  "The computer reveals its nonce: the code shows at grog."
  [ctx id body]
  (let [nonce (when (map? body) (:nonce body))
        session (session-of @(:pairing ctx) id)
        computer (get-in session [:request :name])]
    (when-not (= "started" (:status session))
      (problem 409 "it's past that"))
    (when-not (and (string? nonce)
                   (re-matches #"[0-9a-f]{32,128}" nonce)
                   (= (commitment nonce) (get-in session [:request :commitment])))
      (problem 400 "nonce: not the one this computer committed to"))
    (let [shown (code (computers/public-key! @(:config ctx)) (:nonce session) (get-in session [:request :host-key]) nonce)]
      (swap! (:pairing ctx) (fn [state]
                              (if (= id (get-in state [:session :id]))
                                (-> state
                                    (update :session assoc :status "asking" :code shown)
                                    (assoc :status "asking" :at (now)))
                                state)))
      (log/info (format "pairing: %s asks" computer))
      (publish! ctx)
      {:status "asking"})))

(defn ^:private status-of
  "How the pairing `id` goes, for the computer."
  [ctx id]
  (let [{:keys [status error] :as state} @(:pairing ctx)
        session (session-of state id)]
    (case status
      "allowed" {:status status :from (address-toward (:host session))}
      "open" {:status "started"}
      (cond-> {:status status}
        error (assoc :error error)))))

(defn ^:private check!
  "The computer let grog in: grog says hello, and pairing is over."
  [ctx id]
  (let [config @(:config ctx)
        state @(:pairing ctx)
        computer (get-in (session-of state id) [:request :name])]
    (when-not (= "allowed" (:status state))
      (problem 409 "it isn't allowed"))
    (try
      (computers/ask! config computer {:action "hello"} 10000)
      (log/info (format "pairing: %s paired" computer))
      (finish! ctx "paired")
      {:status "paired"}
      (catch clojure.lang.ExceptionInfo e
        (computers/remove! config computer)
        (finish! ctx "failed" (ex-message e))
        (problem 502 (str "grog can't reach this computer: " (ex-message e)))))))

(defn reached-at
  "Where a computer reached grog, `host:port`, as the Host its request says:
  where it reaches grog again. `port` when it doesn't say one. nil when it
  makes no sense."
  [host port]
  (cond
    (nil? host) nil
    (re-matches #"(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+):\d{1,5}" host) host
    (re-matches #"\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+" host) (str host ":" port)))

(defn ^:private address-of
  "Where the computer asking reaches grog, `host:port`: as it says, or
  else grog's own address."
  [ctx req]
  (let [{:keys [addresses port]} @(:pairing ctx)
        own (or (first addresses) "127.0.0.1")]
    (or (reached-at (get-in req [:headers "host"]) port)
        (str (if (str/includes? own ":") (str "[" own "]") own) ":" port))))

(defn script
  "What grog serves for a computer to pair, resources/grog/computer.sh,
  knowing grog is at `url` (http://host:port): run without saying where
  grog is, it pairs with grog there."
  [url]
  (str/replace-first (slurp (io/resource "grog/computer.sh"))
                     "\ngrog_url=''\n"
                     (str "\ngrog_url='" url "'\n")))

(defn command
  "What to run on a computer to pair it with grog at `address`
  (host:port)."
  [address]
  (str "curl -fsS " address " | bash"))

(defn ^:private page
  "What a browser on the computer shows at grog's `address`: what to run."
  [config address]
  (selmer/render (slurp (io/resource "grog/pairing.html"))
                 {:name (:name config) :command (command address)}))

(defn ^:private served
  "The script, or for a browser asking for a page (`page?`), the page
  saying to run it."
  [ctx req page?]
  (let [address (address-of ctx req)]
    {:status 200
     :headers {"Content-Type" (if page? "text/html; charset=utf-8" "text/plain; charset=utf-8")
               "Cache-Control" "no-store"}
     :body (if page? (page @(:config ctx) address) (script (str "http://" address)))}))

(defn ^:private refusal
  "A response refusing `req`, or nil: web pages can't post here, as they
  can't send JSON to another site without a CORS preflight, which grog never
  allows."
  [{:keys [request-method headers]}]
  (when (and (= :post request-method)
             (not (some-> (get headers "content-type") str/lower-case (str/starts-with? "application/json"))))
    (text-response 415 {:error "Send requests with Content-Type: application/json."})))

(defn handle
  "Answers a computer asking to pair, on the network (see the ns)."
  [ctx {:keys [request-method uri headers] :as req}]
  (let [[_ id step] (re-matches #"/v1/pair/([0-9a-f]{32})(?:/(nonce|check))?" uri)]
    (or (refusal req)
        (try
          (cond
            (= [:get "/"] [request-method uri])
            (served ctx req (str/includes? (str (get headers "accept")) "text/html"))

            (= [:get "/computer.sh"] [request-method uri]) (served ctx req false)
            (= [:post "/v1/pair"] [request-method uri]) (text-response 200 (start! ctx req))
            (and id (= "nonce" step) (= :post request-method)) (text-response 200 (reveal! ctx id (read-json req)))
            (and id (nil? step) (= :get request-method)) (text-response 200 (status-of ctx id))
            (and id (= "check" step) (= :post request-method)) (text-response 200 (check! ctx id))
            :else (text-response 404 {:error "Not found."}))
          (catch clojure.lang.ExceptionInfo e
            (text-response (:status (ex-data e) 400) {:error (ex-message e)}))))))

(defn ^:private expire!
  "Pairing's time is up: over, unless a computer was allowed and finishes."
  [ctx]
  (when (contains? #{"open" "asking"} (:status @(:pairing ctx)))
    (log/info "pairing: expired")
    (finish! ctx "expired")))

(defn open!
  "Lets a computer pair for the next few minutes (again, if it was), grog
  listening on the network for it (`computers.pairing`): how pairing goes
  then (`view`). Throws ex-info when grog can't listen there."
  [ctx]
  (let [config @(:config ctx)
        {:keys [host port]} (get-in config [:computers :pairing])]
    (locking lock
      (let [listener (when-not (:stop @(:pairing ctx))
                       (try (server/run-server #(handle ctx %) {:ip host :port port :max-body max-body-bytes
                                                                :legacy-return-value? false})
                            (catch java.io.IOException e
                              (throw (ex-info (format "grog can't listen on %s:%d for a computer: %s"
                                                      host port (or (ex-message e) (.getSimpleName (class e))))
                                              {:type ::cant-listen})))))]
        (when listener
          (log/info (format "pairing: listening on %s:%d" host (server/server-port listener))))
        (swap! (:pairing ctx) (fn [state]
                                (cond-> (assoc state
                                               :generation (inc (:generation state 0))
                                               :until (+ (now) window-ms)
                                               :addresses (addresses config)
                                               :at (now))
                                  listener (assoc :stop #(server/server-stop! listener)
                                                  :port (server/server-port listener))
                                  (not (contains? #{"asking" "allowed"} (:status state)))
                                  (assoc :status "open" :session nil :error nil))))))
    (later! ctx window-ms #(expire! ctx))
    (publish! ctx)
    (view @(:pairing ctx))))

(defn close!
  "Stops letting a computer pair, but one already allowed: how pairing goes
  then (`view`)."
  [ctx]
  (when (contains? #{"open" "asking"} (:status @(:pairing ctx)))
    (finish! ctx "closed"))
  (view @(:pairing ctx)))

(defn stop!
  "Stops listening on the network at once: grog is going away."
  [ctx]
  (stop-listener! ctx))

(defn decide!
  "Allows the computer asking to pair (`allow?`), or not: how pairing goes
  then (`view`). Throws ex-info when none is asking."
  [ctx allow?]
  (let [config @(:config ctx)
        {:keys [status session]} @(:pairing ctx)
        {:keys [request host]} session]
    (when-not (= "asking" status)
      (throw (ex-info "no computer is asking to pair" {:type ::nobody})))
    (log/info (format "pairing: %s %s" (:name request) (if allow? "allowed" "denied")))
    (if allow?
      (do (computers/add! config {:name (:name request) :host host :port (:ssh-port request) :user (:user request)
                                  :host-key (:host-key request) :mac (:mac request) :added-at (now)})
          (swap! (:pairing ctx) assoc :status "allowed" :at (now))
          (publish! ctx)
          (later! ctx finish-ms #(when (= "allowed" (:status @(:pairing ctx)))
                                   (computers/remove! config (:name request))
                                   (finish! ctx "failed" "the computer didn't finish pairing"))))
      (finish! ctx "denied"))
    (view @(:pairing ctx))))
