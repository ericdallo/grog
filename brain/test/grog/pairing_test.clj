(ns grog.pairing-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.computers :as computers]
            [grog.config :as config]
            [grog.events :as events]
            [grog.pairing :as pairing]
            [grog.remote-hands :as remote-hands]
            [grog.server :as server]
            [grog.test-helpers :as h]))

(def ^:private grog-key "ssh-ed25519 AAAAgrogkey grog")
(def ^:private deluxe-key "ssh-ed25519 AAAAdeluxekey")

(defn ^:private script!
  "An executable bash script `name` in `dir`, running `body`."
  [dir name body]
  (let [f (str (fs/path dir name))]
    (spit f (str "#!" (fs/which "bash") "\n" body "\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    f))

(defn ^:private start!
  "A grog whose key is made already, letting computers pair on loopback,
  reaching them with a fake ssh: `hello` says how they answer grog's hello
  (a line of JSON, or bash making ssh fail)."
  [hello]
  (let [dir (h/temp-dir)
        state-dir (str (fs/path dir "state"))
        srv (server/start! (-> config/defaults
                               (assoc :name "grog" :state-dir state-dir)
                               (assoc-in [:server :port] 0)
                               (assoc-in [:computers :pairing] {:host "127.0.0.1" :port (h/free-port)})
                               (assoc-in [:computers :ssh] (script! dir "ssh" (str "cat > /dev/null; " hello)))
                               ;; Not OpenAI's.
                               (assoc-in [:providers :openai :subscription :auth-url] "http://127.0.0.1:1")))
        config @(:config (:ctx srv))]
    (fs/create-dirs (computers/dir config))
    (spit (str (computers/key-file config) ".pub") (str grog-key "\n"))
    (assoc srv :config config :url (str "http://127.0.0.1:" (:port srv)))))

(defn ^:private api
  "A request to grog's local API: [status body]."
  [method url]
  (let [resp (http/request {:method method :uri url :throw false
                            :headers {"content-type" "application/json"} :body "{}"})]
    [(:status resp) (config/parse (:body resp))]))

(defn ^:private pairing-view [srv]
  (second (api :get (str (:url srv) "/v1/computers"))))

(defn ^:private eventually [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 50) (recur))))))

(defn ^:private computer
  "A computer about to pair, its files in a directory of its own."
  []
  (let [dir (h/temp-dir)]
    {:host-key deluxe-key
     :hostname "Deluxe.lan"
     :user "greg"
     :authorized-keys (str (fs/path dir ".ssh" "authorized_keys"))
     :policy-file (str (fs/path dir "config" "grog" "hands.json"))
     :grog "/etc/profiles/per-user/greg/bin/grog"
     :mac (constantly "a0:c5:89:84:d4:ec")
     :sshd? (constantly true)}))

(defn ^:private pairing!
  "Pairs `here` with the grog of `srv` in the background: [the future of
  whether it worked, what it said]."
  ([srv here] (pairing! srv here {}))
  ([srv here opts]
   (let [said (atom [])]
     [(future (pairing/pair! "127.0.0.1"
                             (merge {:port (get-in (:config srv) [:computers :pairing :port])
                                     :say (fn [& lines] (swap! said into lines))}
                                    opts)
                             here))
      said])))

(defn ^:private code-said [said]
  (some #(second (re-matches #"  (\d{3} \d{3})" %)) @said))

(deftest codes
  (testing "the same from the same keys and nonces, only then"
    (let [c (pairing/code grog-key "aa" deluxe-key "bb")]
      (is (re-matches #"\d{6}" c))
      (is (= c (pairing/code grog-key "aa" deluxe-key "bb")))
      (is (not= c (pairing/code "ssh-ed25519 AAAAevil grog" "aa" deluxe-key "bb")))
      (is (not= c (pairing/code grog-key "aa" deluxe-key "bc")))))
  (is (= "482 913" (pairing/spaced "482913")))
  (is (= (pairing/commitment "abc") (pairing/commitment "abc")))
  (is (re-matches #"[0-9a-f]{64}" (pairing/commitment "abc"))))

(deftest what-a-computer-writes
  (is (= "192.168.15.0/24" (pairing/network "192.168.15.17")))
  (is (= "fe80::1" (pairing/network "fe80::1")))
  (is (= (str "restrict,from=\"192.168.15.0/24\",command=\"/run/current-system/sw/bin/grog hands --stdio\" "
              grog-key)
         (pairing/authorized-line grog-key "192.168.15.0/24" "/run/current-system/sw/bin/grog")))
  (is (= "deluxe" (pairing/computer-name "Deluxe.lan")))
  (is (= "greg-s-laptop" (pairing/computer-name "Greg's laptop")))
  (let [dir (h/temp-dir)
        ed (str (fs/path dir "ed.pub"))]
    (spit ed "ssh-ed25519 AAAAhost root@deluxe\n")
    (is (= "ssh-ed25519 AAAAhost" (pairing/host-key [(str (fs/path dir "none.pub")) ed])))
    (is (nil? (pairing/host-key [(str (fs/path dir "none.pub"))])))))

(deftest pairing-a-computer
  (let [srv (start! "echo '{\"v\": 1, \"host\": \"deluxe\", \"user\": \"greg\", \"policy\": {\"allow\": [\"status\"]}}'")
        here (computer)]
    (try
      (is (= 200 (first (api :post (str (:url srv) "/v1/pairing")))))
      (is (= "open" (:status (:pairing (pairing-view srv)))))
      (let [[done said] (pairing! srv here)]
        (testing "both screens show the same code"
          (is (eventually 10000 #(= "asking" (get-in (pairing-view srv) [:pairing :status]))))
          (let [{:keys [name user code]} (:pairing (pairing-view srv))]
            (is (= ["deluxe" "greg"] [name user]))
            (is (eventually 5000 #(code-said said)))
            (is (= (pairing/spaced code) (code-said said)))
            (is (= code (get-in (events/snapshot (:events (:ctx srv))) [:pairing :code])) "for the skin")))
        (testing "allowed at grog, the computer lets grog in"
          (is (= 200 (first (api :post (str (:url srv) "/v1/pairing/allow")))))
          (is (true? (deref done 20000 :late)))
          (is (= (str "restrict,from=\"127.0.0.0/24\",command=\"/etc/profiles/per-user/greg/bin/grog hands --stdio\" "
                      grog-key "\n")
                 (slurp (:authorized-keys here))))
          (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (:authorized-keys here)))))
          (is (= remote-hands/default-policy (config/parse (slurp (:policy-file here)))) "the default policy")
          (is (some #(str/includes? % "Paired: grog can act on this computer now") @said)))
        (testing "and grog keeps it"
          (is (= {:name "deluxe" :host "127.0.0.1" :port 22 :user "greg" :host-key deluxe-key
                  :mac "a0:c5:89:84:d4:ec"}
                 (dissoc (computers/by-name (:config srv) "deluxe") :added-at)))
          (is (= "paired" (get-in (pairing-view srv) [:pairing :status])))))
      (finally
        (server/stop! srv)))))

(deftest trusting-it
  (let [srv (start! "echo '{\"v\": 1}'")
        here (computer)]
    (try
      (api :post (str (:url srv) "/v1/pairing"))
      (let [[done _] (pairing! srv here {:trust true})]
        (is (eventually 10000 #(= "asking" (get-in (pairing-view srv) [:pairing :status]))))
        (api :post (str (:url srv) "/v1/pairing/allow"))
        (is (true? (deref done 20000 :late)))
        (is (= remote-hands/trusting-policy (config/parse (slurp (:policy-file here))))))
      (finally
        (server/stop! srv)))))

(deftest not-allowing-it
  (let [srv (start! "echo '{\"v\": 1}'")
        here (computer)]
    (try
      (api :post (str (:url srv) "/v1/pairing"))
      (let [[done said] (pairing! srv here)]
        (is (eventually 10000 #(= "asking" (get-in (pairing-view srv) [:pairing :status]))))
        (is (= 200 (first (api :post (str (:url srv) "/v1/pairing/deny")))))
        (is (false? (deref done 20000 :late)))
        (is (some #(str/includes? % "It wasn't allowed on grog") @said))
        (is (not (fs/exists? (:authorized-keys here))))
        (is (= [] (computers/all (:config srv))))
        (is (= "denied" (get-in (pairing-view srv) [:pairing :status]))))
      (testing "nor when nobody asks"
        (is (= [400 {:error "no computer is asking to pair"}] (api :post (str (:url srv) "/v1/pairing/allow")))))
      (finally
        (server/stop! srv)))))

(deftest when-grog-cant-reach-it
  (let [srv (start! "echo 'ssh: connect to host 127.0.0.1 port 22: Connection refused' >&2; exit 255")
        here (computer)]
    (try
      (api :post (str (:url srv) "/v1/pairing"))
      (let [[done said] (pairing! srv here)]
        (is (eventually 10000 #(= "asking" (get-in (pairing-view srv) [:pairing :status]))))
        (api :post (str (:url srv) "/v1/pairing/allow"))
        (is (false? (deref done 20000 :late)))
        (is (some #(str/includes? % "grog can't reach this computer: deluxe doesn't take SSH connections") @said))
        (is (= "" (slurp (:authorized-keys here))) "grog's line goes")
        (is (= [] (computers/all (:config srv))) "nor does grog keep it")
        (is (= "failed" (get-in (pairing-view srv) [:pairing :status]))))
      (finally
        (server/stop! srv)))))

(deftest only-while-its-open
  (let [srv (start! "echo '{\"v\": 1}'")]
    (try
      (testing "nothing listens before"
        (let [[done said] (pairing! srv (computer))]
          (is (false? (deref done 20000 :late)))
          (is (some #(str/includes? % "grog doesn't let a computer pair at") @said))))
      (testing "nor once it's closed"
        (api :post (str (:url srv) "/v1/pairing"))
        (is (= "closed" (:status (second (api :delete (str (:url srv) "/v1/pairing")))))))
      (finally
        (server/stop! srv)))))

(deftest one-computer-at-a-time
  (let [srv (start! "echo '{\"v\": 1}'")]
    (try
      (api :post (str (:url srv) "/v1/pairing"))
      (let [[_ _] (pairing! srv (computer))]
        (is (eventually 10000 #(= "asking" (get-in (pairing-view srv) [:pairing :status]))))
        (let [[done said] (pairing! srv (assoc (computer) :hostname "attic"))]
          (is (false? (deref done 20000 :late)))
          (is (some #(str/includes? % "another computer is pairing with grog") @said)))
        (api :post (str (:url srv) "/v1/pairing/deny")))
      (finally
        (server/stop! srv)))))

(deftest a-computer-that-isnt-ready
  (let [srv (start! "echo '{\"v\": 1}'")]
    (try
      (api :post (str (:url srv) "/v1/pairing"))
      (doseq [[here message] [[(assoc (computer) :host-key nil) "There's no SSH server here"]
                              [(assoc (computer) :sshd? (constantly false)) "Nothing takes SSH connections here"]
                              [(assoc (computer) :grog nil) "Install grog here first"]]]
        (let [[done said] (pairing! srv here)]
          (is (false? (deref done 5000 :late)))
          (is (some #(str/includes? % message) @said) message)))
      (finally
        (server/stop! srv)))))

(deftest what-the-network-may-send
  (let [srv (start! "echo '{\"v\": 1}'")
        port (get-in (:config srv) [:computers :pairing :port])
        pair-url (str "http://127.0.0.1:" port "/v1/pair")
        post (fn [body & [headers]]
               (let [resp (http/post pair-url {:throw false
                                               :headers (or headers {"content-type" "application/json"})
                                               :body (config/generate body)})]
                 [(:status resp) (config/parse (:body resp))]))
        request {:v 1 :name "deluxe" :user "greg" :host-key deluxe-key
                 :commitment (pairing/commitment "00112233445566778899aabbccddeeff")}]
    (try
      (api :post (str (:url srv) "/v1/pairing"))
      (testing "only JSON: web pages can't"
        (is (= 415 (first (post request {"content-type" "text/plain"})))))
      (testing "what makes no sense"
        (is (= [400 {:error "user: a user's name"}] (post (assoc request :user "-oProxyCommand=x"))))
        (is (= 400 (first (post (assoc request :host-key "ssh-ed25519 not base64!")))))
        (is (= 400 (first (post (dissoc request :commitment))))))
      (testing "a nonce it didn't commit to"
        (let [[status {:keys [id key nonce]}] (post request)]
          (is (= 200 status))
          (is (= grog-key key))
          (is (re-matches #"[0-9a-f]{32}" nonce))
          (let [resp (http/post (str pair-url "/" id "/nonce")
                                {:throw false :headers {"content-type" "application/json"}
                                 :body (config/generate {:nonce "ffffffffffffffffffffffffffffffff"})})]
            (is (= 400 (:status resp))))
          (is (= "open" (get-in (pairing-view srv) [:pairing :status])) "nothing shows at grog")))
      (finally
        (server/stop! srv)))))
