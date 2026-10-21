(ns grog.pairing
  "Pairing a computer with grog, for grog to act on it (grog.computers).
  Someone at grog lets one pair (its skin, `grog computers add`, `POST
  /v1/computers/pairing`): for a few minutes, grog listens on the network
  for one asking (`computers.pairing`). Its user runs `grog pair <grog's
  address>` there (`pair!`); both screens show a code made from both keys,
  and someone at grog allows it when they're the same. The computer then
  lets grog's key in, only to run grog's hands there (grog.remote-hands),
  and only from grog's network.

  Someone in between, swapping keys, can't make the codes match (but once
  in a million): the computer commits to its nonce (sends its hash) before
  it gets grog's, and only reveals it then.

  On the network, while pairing is open:
    POST /v1/pair             {v, name, user, hostKey, mac, sshPort,
                              commitment} -> {id, key, nonce, name,
                              expiresAt}: grog's key and nonce
    POST /v1/pair/<id>/nonce  {nonce} -> {status}: grog shows the code
    GET  /v1/pair/<id>        -> {status}: asking, allowed (with from, the
                              address grog comes from), denied, expired,
                              closed, failed (with error), paired
    POST /v1/pair/<id>/check  once the computer let grog in, grog says
                              hello -> {status: paired, policy}"
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.string :as str]
            [grog.computers :as computers]
            [grog.config :as config]
            [grog.events :as events]
            [grog.log :as log]
            [grog.remote-hands :as remote-hands]
            [org.httpkit.server :as server])
  (:import (java.net DatagramSocket InetAddress InetSocketAddress Socket)
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
  computer's host key and nonce."
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

(defn ^:private json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (config/generate body)})

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
      (not= remote-hands/protocol-version v)
      (problem 400 (format "this grog speaks version %d of pairing: update the older grog"
                           remote-hands/protocol-version))

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
         :expires-at (:until state)}

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
      (let [hello (computers/ask! config computer {:action "hello"} 10000)]
        (log/info (format "pairing: %s paired" computer))
        (finish! ctx "paired")
        {:status "paired" :policy (:policy hello)})
      (catch clojure.lang.ExceptionInfo e
        (computers/remove! config computer)
        (finish! ctx "failed" (ex-message e))
        (problem 502 (str "grog can't reach this computer: " (ex-message e)))))))

(defn ^:private refusal
  "A response refusing `req`, or nil: web pages can't post here, as they
  can't send JSON to another site without a CORS preflight, which grog never
  allows."
  [{:keys [request-method headers]}]
  (when (and (= :post request-method)
             (not (some-> (get headers "content-type") str/lower-case (str/starts-with? "application/json"))))
    (json-response 415 {:error "Send requests with Content-Type: application/json."})))

(defn handle
  "Answers a computer asking to pair, on the network (see the ns)."
  [ctx {:keys [request-method uri] :as req}]
  (let [[_ id step] (re-matches #"/v1/pair/([0-9a-f]{32})(?:/(nonce|check))?" uri)]
    (or (refusal req)
        (try
          (cond
            (= [:post "/v1/pair"] [request-method uri]) (json-response 200 (start! ctx req))
            (and id (= "nonce" step) (= :post request-method)) (json-response 200 (reveal! ctx id (read-json req)))
            (and id (nil? step) (= :get request-method)) (json-response 200 (status-of ctx id))
            (and id (= "check" step) (= :post request-method)) (json-response 200 (check! ctx id))
            :else (json-response 404 {:error "Not found."}))
          (catch clojure.lang.ExceptionInfo e
            (json-response (:status (ex-data e) 400) {:error (ex-message e)}))))))

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

(def host-key-files
  "Where a computer's SSH host keys are, the ones grog prefers first."
  ["/etc/ssh/ssh_host_ed25519_key.pub" "/etc/ssh/ssh_host_ecdsa_key.pub" "/etc/ssh/ssh_host_rsa_key.pub"])

(defn host-key
  "The computer's SSH host key, from the first of `files` there is: its
  type and base64, or nil."
  [files]
  (some (fn [file]
          (when (fs/readable? file)
            (let [[type blob] (str/split (str/trim (slurp file)) #"\s+")
                  key (str type " " blob)]
              (when (re-matches host-key-pattern key) key))))
        files))

(defn computer-name
  "What grog calls a computer whose host name is `hostname`."
  [hostname]
  (let [n (-> (str/lower-case (str hostname))
              (str/replace #"\..*$" "")
              (str/replace #"[^a-z0-9-]+" "-")
              (str/replace #"^-+|-+$" ""))]
    (subs n 0 (min 32 (count n)))))

(defn network
  "Where grog's key may come from, around grog's `address` as the computer
  sees it: its /24 for IPv4 (a new address on the same network still
  works), only itself otherwise."
  [address]
  (if-let [[_ prefix] (re-matches #"(\d+\.\d+\.\d+)\.\d+" (str address))]
    (str prefix ".0/24")
    (str address)))

(defn authorized-line
  "The line of authorized_keys letting grog's `key` in: from `network` only,
  to run its hands (`grog`, the command, here) only, without a terminal or
  forwarding anything."
  [key network grog]
  (format "restrict,from=\"%s\",command=\"%s hands --stdio\" %s" network grog key))

(defn ^:private let-in!
  "Lets grog in with `line` (its `key`) in authorized_keys at `file`, in
  place of the lines letting it in before."
  [file line key]
  (let [dir (fs/parent file)]
    (when-not (fs/exists? dir)
      (fs/create-dirs dir)
      (fs/set-posix-file-permissions dir "rwx------"))
    (let [before (if (fs/exists? file) (remote-hands/without-key (slurp file) key) "")]
      (spit file (str before line "\n"))
      (fs/set-posix-file-permissions file "rw-------"))))

(defn ^:private keep-out!
  "Takes grog's `key` out of authorized_keys at `file`."
  [file key]
  (when (fs/exists? file)
    (spit file (remote-hands/without-key (slurp file) key))))

(defn ^:private write-policy!
  "What grog may do here, the default, unless its user already said; or
  everything (`trust`)."
  [file trust]
  (when (or trust (not (fs/exists? file)))
    (fs/create-dirs (fs/parent file))
    (spit file (str (config/generate (if trust remote-hands/trusting-policy remote-hands/default-policy)
                                     {:pretty true})
                    "\n"))))

(defn mac-toward
  "The address of the network card on the way to `address`, or nil: what
  grog wakes it with (Linux)."
  [address]
  (try
    (let [{:keys [out]} @(p/process {:cmd ["ip" "route" "get" address] :out :string :err :string})
          dev (second (re-find #"\bdev (\S+)" (str out)))
          mac (when dev (str/trim (slurp (str "/sys/class/net/" dev "/address"))))]
      (when (and mac (re-matches computers/mac-pattern mac) (not= "00:00:00:00:00:00" mac))
        mac))
    (catch Exception _ nil)))

(defn ^:private listening?
  "Whether something takes connections on `port` here."
  [port]
  (try
    (with-open [socket (Socket.)]
      (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) 1000)
      true)
    (catch Exception _ false)))

(defn here
  "This computer, for `pair!`: its SSH host key, its host name, its user,
  their authorized_keys, its policy file, the grog the key runs, the
  network card on the way to grog (`mac`), and whether it takes SSH
  connections (`sshd?`)."
  []
  {:host-key (host-key host-key-files)
   :hostname (remote-hands/hostname)
   :user (System/getProperty "user.name")
   :authorized-keys (str (fs/path (System/getProperty "user.home") ".ssh" "authorized_keys"))
   :policy-file (remote-hands/policy-file)
   :grog (some-> (fs/which "grog") str)
   :mac mac-toward
   :sshd? listening?})

(defn ^:private call
  "A request to grog's pairing at `url`: [true body] or [false why]."
  [method url path body]
  (try
    (let [resp (http/request (cond-> {:method method :uri (str url path) :throw false :timeout 15000}
                               body (assoc :headers {"content-type" "application/json"}
                                           :body (config/generate body))))
          parsed (try (config/parse (:body resp)) (catch Exception _ nil))]
      (if (= 200 (:status resp))
        [true parsed]
        [false (or (:error parsed) (str "HTTP " (:status resp)))]))
    (catch java.io.IOException e
      [false (format "grog doesn't let a computer pair at %s (%s): let one pair on grog first (its menu, or grog computers add)"
                     url (or (ex-message e) (.getSimpleName (class e))))])))

(defn ^:private wait-for-grog
  "Waits until someone at grog allowed this computer, or not, for pairing
  `id` going on until `until`: its status then."
  [url id until]
  (loop []
    (let [[ok? body] (call :get url (str "/v1/pair/" id) nil)]
      (cond
        (not ok?) {:status "failed" :error body}
        (not (contains? #{"started" "asking"} (:status body))) body
        (> (now) (+ until finish-ms)) {:status "expired"}
        :else (do (Thread/sleep 1000) (recur))))))

(defn ^:private resolve-address
  "`address` as an IP address, or itself when it can't be resolved."
  [address]
  (try (.getHostAddress (InetAddress/getByName address))
       (catch Exception _ address)))

(defn ^:private finish-here!
  "grog allowed this computer: lets its `key` in from `from`, saying what it
  may do here, then asks grog to check it can come in: whether it worked."
  [{:keys [authorized-keys policy-file grog]} {:keys [url id key from trust say]}]
  (let [line (authorized-line key (network from) grog)]
    (let-in! authorized-keys line key)
    (write-policy! policy-file trust)
    (let [[ok? body] (call :post url (str "/v1/pair/" id "/check") {})]
      (if ok?
        (do (say "" (str "Paired: grog can act on this computer now. What it may do here, and what it asks first: "
                         policy-file))
            true)
        (do (keep-out! authorized-keys key)
            (say (str "[error] " body))
            false)))))

(defn pair!
  "Pairs this computer (`here`) with grog at `address`, listening on `port`
  for it, saying how it goes (`say`, lines): whether it worked. `name`:
  what grog calls it (its host name by default); `ssh-port`: where its SSH
  server listens; `trust`: grog may do anything here, without asking."
  [address {:keys [port name ssh-port trust say]} here]
  (let [say (or say (fn [& lines] (run! println lines) (flush)))
        fail (fn [message] (say (str "[error] " message)) false)
        ssh-port (or ssh-port 22)
        name (or name (computer-name (:hostname here)))
        url (format "http://%s:%d" (if (str/includes? address ":") (str "[" address "]") address)
                    (or port (get-in config/defaults [:computers :pairing :port])))]
    (cond
      (not (:host-key here))
      (fail (str "There's no SSH server here: grog reaches computers over SSH. Install one and start it "
                 "(on NixOS: services.openssh.enable = true)."))

      (not ((:sshd? here) ssh-port))
      (fail (format (str "Nothing takes SSH connections here on port %d: start the SSH server (on NixOS: "
                         "services.openssh.enable = true), or say its port with --ssh-port.")
                    ssh-port))

      (not (:grog here))
      (fail (str "Install grog here first: its hands run here when grog connects (nix profile install "
                 "github:ericdallo/grog, or on NixOS, environment.systemPackages)."))

      (not (re-matches computers/name-pattern name))
      (fail "Say what grog calls this computer with --name: lowercase letters, digits and dashes, up to 32.")

      :else
      (let [nonce (random-hex 16)
            grog-ip (resolve-address address)
            mac ((:mac here) grog-ip)
            [ok? started] (call :post url "/v1/pair" (cond-> {:v remote-hands/protocol-version :name name
                                                              :user (:user here) :host-key (:host-key here)
                                                              :ssh-port ssh-port :commitment (commitment nonce)}
                                                       mac (assoc :mac mac)))
            {:keys [id key expires-at]} started]
        (cond
          (not ok?)
          (fail started)

          (not (and (string? key) (re-matches host-key-pattern (str/join " " (take 2 (str/split key #"\s+"))))
                    (string? (:nonce started)) (re-matches #"[0-9a-f]{32}" (:nonce started))
                    (string? id) (re-matches #"[0-9a-f]{32}" id)))
          (fail "grog's answer makes no sense: is it grog there?")

          :else
          (let [[ok? revealed] (call :post url (str "/v1/pair/" id "/nonce") {:nonce nonce})]
            (if-not ok?
              (fail revealed)
              (do (say (format "Pairing %s with %s. On grog's screen, check the code is" name (or (:name started) "grog"))
                       ""
                       (str "  " (spaced (code key (:nonce started) (:host-key here) nonce)))
                       ""
                       "and allow it there. Waiting (Ctrl-C to stop)...")
                  (let [{:keys [status error from]} (wait-for-grog url id (or expires-at (now)))]
                    (case status
                      "allowed" (finish-here! here {:url url :id id :key key :from (or from grog-ip)
                                                    :trust trust :say say})
                      "denied" (fail "It wasn't allowed on grog.")
                      "expired" (fail "Pairing closed on grog before it was allowed.")
                      "closed" (fail "Pairing closed on grog before it was allowed.")
                      (fail (or error (str "Pairing " status)))))))))))))
