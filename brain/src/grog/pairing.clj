(ns grog.pairing
  "Pairing a computer with grog, for grog to act on it (grog.computers): how
  the `computer` integration connects (`integration`, grog.connect).
  Someone at grog opens a session for it (Add a computer on its skin, `grog
  connect computer`), and grog serves what pairs one on the network
  meanwhile, resources/grog/computer.sh, so there's nothing to install
  there: its user runs `curl -fsS <grog's address>:8485 | bash` (a script
  step), or opens that address in a browser, which says so. Both screens
  show a code made from both keys (a code-compare step), and someone at grog
  allows it when they're the same. The computer then lets grog's key in,
  only to run grog's hands there (a copy of the script), and only from
  grog's network.

  Someone in between, swapping keys, can't make the codes match (but once
  in a million): the computer commits to its nonce (sends its hash) before
  it gets grog's, and only reveals it then. Someone who can swap the script
  as it goes, over plain HTTP, could run anything there though: pairing
  trusts the network for those minutes.

  On the network, while the session is the last one, answering text the
  script reads without a JSON parser, a line of key=value each
  (`answer-text`):
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
            [grog.connect :as connect]
            [grog.log :as log]
            [selmer.parser :as selmer])
  (:import (java.nio ByteBuffer)
           (java.security MessageDigest SecureRandom)))

;; How long a computer has to reveal its nonce, before another may ask.
(def ^:private reveal-ms 30000)

;; How long an allowed computer has to let grog in.
(def ^:private finish-ms 60000)

(def host-key-pattern
  "An SSH public key: its type, then its base64."
  #"(ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|ssh-rsa) [A-Za-z0-9+/]+={0,3}")

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
  "Whether a computer may ask to pair now, as the session's `state` says:
  while it's open, and no other is asking (but one that didn't go on)."
  [{:keys [status until data]} at]
  (and (= "open" status)
       (< at until)
       (or (nil? data)
           (and (= "started" (:status data)) (> (- at (:at data)) reveal-ms)))))

(defn ^:private start!
  "A computer asks to pair: grog's key and nonce."
  [ctx state req]
  (let [config @(:config ctx)
        request (check-request (read-json req))
        host (:remote-addr req)
        at (now)
        pairing {:id (random-hex 16) :nonce (random-hex 16) :request request :host host :status "started" :at at}]
    (when-not (and (string? host) (re-matches computers/host-pattern host))
      (problem 400 "grog can't tell where this computer is"))
    (let [after (connect/change! ctx (:generation state) #(cond-> % (can-start? % at) (assoc :data pairing)))]
      (cond
        (= pairing (:data after))
        {:id (:id pairing) :key (computers/public-key! config) :nonce (:nonce pairing) :name (:name config)
         :expires-in (max 0 (quot (- (:until after) at) 1000))}

        (:data after)
        (problem 409 "another computer is pairing with grog: try again in a minute")

        :else
        (problem 403 "grog doesn't let a computer pair now: let one on grog first")))))

(defn ^:private pairing-of
  "The pairing `id` names, in the session's `state`: a 404 when it's not
  the one going on."
  [state id]
  (let [pairing (:data state)]
    (if (= id (:id pairing))
      pairing
      (problem 404 "there's no such pairing going on"))))

(defn ^:private decide!
  "Someone at grog allows the computer asking in the session `generation`
  (`allow?`), or not: allowed, grog keeps it, and waits for it to let grog
  in, for a while; and forgets it unless it does."
  [ctx generation allow?]
  (let [config @(:config ctx)
        {:keys [request host]} (:data @(:connect ctx))
        computer (:name request)]
    (log/info (format "pairing: %s %s" computer (if allow? "allowed" "denied")))
    (if-not allow?
      (connect/deny! ctx generation)
      (do (computers/add! config {:name computer :host host :port (:ssh-port request) :user (:user request)
                                  :host-key (:host-key request) :mac (:mac request) :added-at (now)})
          (connect/on-end! ctx generation #(when (not= "done" %) (computers/remove! config computer)))
          (connect/change! ctx generation #(assoc-in % [:data :status] "allowed"))
          (connect/step! ctx generation {:kind "wait" :note (str "waiting for " computer " to finish")})
          (connect/later! ctx generation finish-ms #(connect/fail! ctx generation "the computer didn't finish pairing"))))))

(defn ^:private reveal!
  "The computer reveals its nonce: the code shows at grog."
  [ctx state id body]
  (let [nonce (when (map? body) (:nonce body))
        generation (:generation state)
        {:keys [request] :as pairing} (pairing-of state id)
        shown (delay (code (computers/public-key! @(:config ctx)) (:nonce pairing) (:host-key request) nonce))]
    (when-not (and (= "open" (:status state)) (= "started" (:status pairing)))
      (problem 409 "it's past that"))
    (when-not (and (string? nonce)
                   (re-matches #"[0-9a-f]{32,128}" nonce)
                   (= (commitment nonce) (:commitment request)))
      (problem 400 "nonce: not the one this computer committed to"))
    (when-not (connect/change! ctx generation #(cond-> % (= id (get-in % [:data :id])) (assoc-in [:data :status] "asking")))
      (problem 409 "it's past that"))
    (connect/code-compare! ctx generation {:code @shown :name (:name request) :user (:user request)
                                           :decide! #(decide! ctx generation %)})
    (log/info (format "pairing: %s asks" (:name request)))
    {:status "asking"}))

(defn ^:private status-of
  "How the pairing `id` goes, for the computer, as the session's `state`
  says."
  [state id]
  (let [{:keys [host] :as pairing} (pairing-of state id)]
    (case (:status state)
      "open" (case (:status pairing)
               "allowed" {:status "allowed" :from (connect/address-toward host)}
               "asking" {:status "asking"}
               {:status "started"})
      "done" {:status "paired"}
      "denied" {:status "denied"}
      "expired" {:status "expired"}
      "cancelled" {:status "closed"}
      {:status "failed" :error (:error state)})))

(defn ^:private check!
  "The computer let grog in: grog says hello, and it's paired."
  [ctx state id]
  (let [config @(:config ctx)
        generation (:generation state)
        computer (get-in (pairing-of state id) [:request :name])]
    (when-not (and (= "open" (:status state)) (= "allowed" (get-in state [:data :status])))
      (problem 409 "it isn't allowed"))
    (try
      (computers/ask! config computer {:action "hello"} 10000)
      (log/info (format "pairing: %s paired" computer))
      (connect/done! ctx generation computer)
      {:status "paired"}
      (catch clojure.lang.ExceptionInfo e
        ;; grog forgets it then (decide!).
        (connect/fail! ctx generation (ex-message e))
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
  [state req]
  (or (reached-at (get-in req [:headers "host"]) (:port state))
      (connect/address state)))

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
  [ctx state req page?]
  (let [address (address-of state req)]
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
  "Answers a computer asking to pair, on the network (see the ns), as the
  session's `state` says."
  [ctx state {:keys [request-method uri headers] :as req}]
  (let [[_ id step] (re-matches #"/v1/pair/([0-9a-f]{32})(?:/(nonce|check))?" uri)]
    (or (refusal req)
        (try
          (cond
            (= [:get "/"] [request-method uri])
            (served ctx state req (str/includes? (str (get headers "accept")) "text/html"))

            (= [:get "/computer.sh"] [request-method uri]) (served ctx state req false)
            (= [:post "/v1/pair"] [request-method uri]) (text-response 200 (start! ctx state req))
            (and id (= "nonce" step) (= :post request-method)) (text-response 200 (reveal! ctx state id (read-json req)))
            (and id (nil? step) (= :get request-method)) (text-response 200 (status-of state id))
            (and id (= "check" step) (= :post request-method)) (text-response 200 (check! ctx state id))
            :else (text-response 404 {:error "Not found."}))
          (catch clojure.lang.ExceptionInfo e
            (text-response (:status (ex-data e) 400) {:error (ex-message e)}))))))

(defn ^:private begin!
  "Lets a computer pair, in the session `state`: what to run on it."
  [ctx state _]
  (let [address (connect/address state)]
    (connect/step! ctx (:generation state) {:kind "script" :command (command address) :address address
                                            :note "It takes an SSH server there, and nothing to install."})))

(def integration
  "The user's computers, for grog to act on (grog.integrations)."
  {:id "computer"
   :title "Computers"
   :heading "Add a computer"
   :about "your computers, to act on them"
   :icon "computer"
   :ways [{:id "script" :label "Run a command on it"}]
   :network? true
   :multiple? true
   :start! #'begin!
   :handle #'handle
   :connections #'computers/connections
   :disconnect! (fn [ctx id] (computers/disconnect! ctx id))})
