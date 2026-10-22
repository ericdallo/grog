(ns grog.pairing-test
  "Pairing a computer: grog's side, and the computer's (computer.sh), run as
  a computer runs it, against a grog that reaches computers with a fake
  ssh."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.client :as client]
            [grog.computers :as computers]
            [grog.config :as config]
            [grog.events :as events]
            [grog.pairing :as pairing]
            [grog.server :as server]
            [grog.test-helpers :as h])
  (:import (java.net InetAddress ServerSocket)))

(def ^:private grog-key "ssh-ed25519 AAAAgrogkey grog")
(def ^:private deluxe-key "ssh-ed25519 AAAAdeluxekey")

(defn ^:private start!
  "A grog whose key is made already, letting computers pair on loopback,
  reaching them with a fake ssh running `ssh` (bash)."
  [ssh]
  (let [dir (h/temp-dir)
        srv (server/start! (-> config/defaults
                               (assoc :name "grog" :state-dir (str (fs/path dir "state")))
                               (assoc-in [:server :port] 0)
                               (update :integrations merge {:host "127.0.0.1" :port (h/free-port)})
                               (assoc-in [:computers :ssh] (h/script! dir "ssh" ssh))
                               ;; Not OpenAI's.
                               (assoc-in [:providers :openai :subscription :auth-url] "http://127.0.0.1:1")))
        config @(:config (:ctx srv))]
    (fs/create-dirs (computers/dir config))
    (spit (str (computers/key-file config) ".pub") (str grog-key "\n"))
    (assoc srv
           :config config
           :url (str "http://127.0.0.1:" (:port srv))
           :address (str "127.0.0.1:" (get-in config [:integrations :port])))))

(defn ^:private api
  "A request to grog's local API: [status body]."
  [method url]
  (let [resp (http/request {:method method :uri url :throw false
                            :headers {"content-type" "application/json"} :body "{}"})]
    [(:status resp) (config/parse (:body resp))]))

(defn ^:private open!
  "Lets a computer pair with grog of `srv`: [status session]."
  [srv]
  (api :post (str (:url srv) "/v1/integrations/computer/connect")))

(defn ^:private session [srv]
  (second (api :get (str (:url srv) "/v1/connect"))))

(defn ^:private stage
  "Where the session letting a computer pair is: its step while it's open
  (script, code-compare, wait), else how it ended."
  [srv]
  (let [{:keys [status step]} (session srv)]
    (if (= "open" status) (:kind step) status)))

(defn ^:private eventually [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 50) (recur))))))

(defn ^:private computer
  "A computer about to pair (grog.test-helpers/computer!): its SSH server
  listening (:sshd, a port), its host key in a file of its own (:key-file),
  and its network card on the way to grog."
  []
  (let [c (h/computer! {"ip" (str "case \"$1 $2\" in\n"
                                  "  'route get') echo \"$3 dev eth9 src 127.0.0.1 uid 1000\" ;;\n"
                                  "  '-o link') echo '2: eth9: <UP> mtu 1500\\    link/ether A0:C5:89:84:D4:EC brd ff:ff:ff:ff:ff:ff' ;;\n"
                                  "esac")})
        sshd (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        key-file (str (fs/path (:dir c) "ssh_host_ed25519_key.pub"))]
    (spit key-file (str deluxe-key " root@deluxe\n"))
    (assoc c :sshd sshd :key-file key-file)))

(defn ^:private options
  "What tells the pairing who `computer` is, here."
  [computer]
  (str "--name deluxe --ssh-port " (.getLocalPort ^ServerSocket (:sshd computer))
       " --host-key " (h/sh-quote (:key-file computer))))

(defn ^:private sshd
  "What grog's ssh does reaching `computer`: as its SSH server would for
  grog's key, it runs what its authorized_keys lets the key run, grog's
  hands there (with bash, as the build sandbox has no /usr/bin/env)."
  [computer]
  (str "grep -qF " (h/sh-quote (str "command=\"" (:hands-file computer) " hands\" " grog-key)) " "
       (h/sh-quote (:authorized-keys computer)) " 2>/dev/null ||\n"
       "  { echo 'greg@127.0.0.1: Permission denied (publickey).' >&2; exit 255; }\n"
       "exec env " (h/env-words (:env computer)) " bash " (h/sh-quote (:hands-file computer)) " hands"))

(defn ^:private pair!
  "Runs `command` (bash) on `computer`, pairing it, in the background: the
  process, what it says going to :said, a file."
  [computer command]
  (let [said (str (fs/path (:dir computer) "said"))]
    {:proc (p/process {:cmd ["bash" "-c" command] :out :write :out-file (fs/file said) :err :out
                       :extra-env (:env computer)})
     :said said}))

(defn ^:private said [{:keys [said]}]
  (if (fs/exists? said) (slurp said) ""))

(defn ^:private code-said [run]
  (second (re-find #"\n  (\d{3} \d{3})\n" (said run))))

(defn ^:private exit-of
  "How the pairing `run` ended: its exit status, once it did."
  [run]
  (:exit (deref (:proc run) 20000 {:exit :late})))

(defn ^:private served
  "Pairing with grog of `srv` as it says: running what it serves."
  [srv]
  (str "curl -fsS " (:address srv) " | bash -s -- "))

(defn ^:private kept
  "Pairing with grog of `srv` with the script a computer keeps, saying where
  grog is."
  [srv]
  (str "bash " (h/sh-quote h/computer-sh) " pair " (:address srv) " "))

(def ^:private user
  "Who the computer's user is, here."
  (delay (str/trim (:out (p/shell {:out :string} "id" "-un")))))

(deftest codes
  (testing "the same from the same keys and nonces, only then"
    (let [c (pairing/code grog-key "aa" deluxe-key "bb")]
      (is (re-matches #"\d{6}" c))
      (is (= c (pairing/code grog-key "aa" deluxe-key "bb")))
      (is (not= c (pairing/code "ssh-ed25519 AAAAevil grog" "aa" deluxe-key "bb")))
      (is (not= c (pairing/code grog-key "aa" deluxe-key "bc")))))
  (testing "on the computer, too"
    (doseq [[key nonce host-key host-nonce] [[grog-key "aa" deluxe-key "bb"]
                                             ["ssh-ed25519 AAAAgrog Jarvis" "0123456789abcdef" deluxe-key "ff"]]]
      (is (= (pairing/code key nonce host-key host-nonce)
             (:out (p/shell {:out :string} "bash" "-c" "source \"$0\" help >/dev/null; pairing_code \"$@\""
                            h/computer-sh key nonce host-key host-nonce))))))
  (is (= "482 913" (pairing/spaced "482913")))
  (is (= (pairing/commitment "abc") (pairing/commitment "abc")))
  (is (re-matches #"[0-9a-f]{64}" (pairing/commitment "abc"))))

(deftest pairing-a-computer
  (let [c (computer)
        srv (start! (sshd c))]
    (try
      (is (= 200 (first (open! srv))))
      (testing "what to run on it"
        (is (= {:kind "script" :command (str "curl -fsS " (:address srv) " | bash") :address (:address srv)
                :note "It takes an SSH server there, and nothing to install."}
               (:step (session srv)))))
      (let [run (pair! c (str (served srv) (options c)))]
        (testing "both screens show the same code"
          (is (eventually 10000 #(= "code-compare" (stage srv))))
          (let [{:keys [name code] as-user :user} (:step (session srv))]
            (is (= ["deluxe" @user] [name as-user]))
            (is (eventually 5000 #(code-said run)))
            (is (= (pairing/spaced code) (code-said run)))
            (is (= code (get-in (events/snapshot (:events (:ctx srv))) [:connect :step :code])) "for the skin")))
        (testing "allowed at grog, the computer lets grog in, to run its hands there"
          (is (= 200 (first (api :post (str (:url srv) "/v1/connect/allow")))))
          (is (= 0 (exit-of run)) (said run))
          (is (= (str "restrict,from=\"127.0.0.0/24\",command=\"" (:hands-file c) " hands\" " grog-key "\n")
                 (slurp (:authorized-keys c))))
          (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (:authorized-keys c)))))
          (is (= (pairing/script (str "http://" (:address srv))) (slurp (:hands-file c))) "as grog served them")
          (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions (:hands-file c)))))
          (is (= {:allow ["status" "notify" "open" "media" "volume" "lock"] :ask ["suspend" "power-off" "shell"] :deny []}
                 (config/parse (slurp (:policy-file c))))
              "the default policy")
          (is (str/includes? (said run) "Paired: grog can act on this computer now")))
        (testing "and grog keeps it, to act on it"
          (is (= {:name "deluxe" :host "127.0.0.1" :port (.getLocalPort ^ServerSocket (:sshd c))
                  :user @user :host-key deluxe-key :mac "a0:c5:89:84:d4:ec"}
                 (dissoc (computers/by-name (:config srv) "deluxe") :added-at)))
          (is (= {:status "done" :connected "deluxe"} (select-keys (session srv) [:status :connected])))
          (is (re-find #"(?m)^name: " (computers/act! (:config srv) {:computer "deluxe" :action "status"}))))
        (testing "a connection of the computer integration, for whoever watches"
          (let [{:keys [integrations]} (events/snapshot (:events (:ctx srv)))]
            (is (= [{:id "computer:deluxe" :name "deluxe" :detail (str @user "@127.0.0.1") :status "connected"}]
                   (map #(dissoc % :added-at) (:connections (some #(when (= "computer" (:id %)) %) integrations))))))))
      (finally
        (server/stop! srv)
        (.close ^ServerSocket (:sshd c))))))

(deftest grog-connect-computer
  (let [c (computer)
        srv (start! (sshd c))
        out (java.io.StringWriter.)
        result (future (binding [*out* out
                                 *in* (java.io.BufferedReader. (java.io.StringReader. "y\n"))]
                         (client/connect! (:url srv) "computer" nil)))]
    (try
      (is (eventually 10000 #(= "script" (stage srv))))
      (let [run (pair! c (str (served srv) (options c)))]
        (testing "it says what to run there, then asks here whether to allow it"
          (is (true? (deref result 20000 :late)))
          (is (= 0 (exit-of run)) (said run))
          (let [out (str out)]
            (is (str/includes? out (str "In a terminal on it, run:\n\n  curl -fsS " (:address srv) " | bash\n")))
            (is (re-find #"deluxe \(\S+\) asks to pair, with the code \d{3} \d{3}\." out))
            (is (str/includes? out "Paired: deluxe. Ask grog to act on it.")))))
      (testing "listed, then forgotten, letting grog in no more"
        (is (re-find #"(?m)^  computer:deluxe +\S+@127\.0\.0\.1, since \d{4}-"
                     (with-out-str (client/integrations! (:url srv)))))
        (is (= "Forgot deluxe: it doesn't let grog in anymore.\n"
               (with-out-str (client/disconnect! (:url srv) "computer:deluxe"))))
        (is (= [] (computers/all (:config srv))))
        (is (not (str/includes? (slurp (:authorized-keys c)) grog-key))))
      (finally
        (future-cancel result)
        (server/stop! srv)
        (.close ^ServerSocket (:sshd c))))))

(deftest trusting-it
  (testing "from the copy it kept, saying where grog is"
    (let [c (computer)
          srv (start! (sshd c))]
      (try
        (open! srv)
        (let [run (pair! c (str (kept srv) (options c) " --trust"))]
          (is (eventually 10000 #(= "code-compare" (stage srv))))
          (api :post (str (:url srv) "/v1/connect/allow"))
          (is (= 0 (exit-of run)) (said run))
          (is (= {:allow ["status" "notify" "open" "media" "volume" "lock" "suspend" "power-off" "shell"] :ask [] :deny []}
                 (config/parse (slurp (:policy-file c))))))
        (finally
          (server/stop! srv)
          (.close ^ServerSocket (:sshd c)))))))

(deftest not-allowing-it
  (let [c (computer)
        srv (start! (sshd c))]
    (try
      (open! srv)
      (let [run (pair! c (str (served srv) (options c)))]
        (is (eventually 10000 #(= "code-compare" (stage srv))))
        (is (= 200 (first (api :post (str (:url srv) "/v1/connect/deny")))))
        (is (= 1 (exit-of run)))
        (is (str/includes? (said run) "[error] It wasn't allowed on grog."))
        (is (not (fs/exists? (:authorized-keys c))))
        (is (not (fs/exists? (:hands-file c))))
        (is (= [] (computers/all (:config srv))))
        (is (= "denied" (stage srv))))
      (testing "nor when nobody asks"
        (is (= [400 {:error "nothing is waiting to be allowed"}] (api :post (str (:url srv) "/v1/connect/allow")))))
      (finally
        (server/stop! srv)
        (.close ^ServerSocket (:sshd c))))))

(deftest when-grog-cant-reach-it
  (let [c (computer)
        srv (start! "cat > /dev/null; echo 'ssh: connect to host 127.0.0.1 port 22: Connection refused' >&2; exit 255")]
    (try
      (open! srv)
      (fs/create-dirs (fs/parent (:authorized-keys c)))
      (spit (:authorized-keys c) "ssh-ed25519 AAAAmine me@laptop\n")
      (let [run (pair! c (str (served srv) (options c)))]
        (is (eventually 10000 #(= "code-compare" (stage srv))))
        (api :post (str (:url srv) "/v1/connect/allow"))
        (is (= 1 (exit-of run)))
        (is (str/includes? (said run) "grog can't reach this computer: deluxe doesn't take SSH connections"))
        (is (= "ssh-ed25519 AAAAmine me@laptop\n" (slurp (:authorized-keys c))) "grog's line goes, the rest stays")
        (is (= [] (computers/all (:config srv))) "nor does grog keep it")
        (is (= "failed" (stage srv))))
      (finally
        (server/stop! srv)
        (.close ^ServerSocket (:sshd c))))))

(deftest only-while-its-open
  (let [c (computer)
        srv (start! (sshd c))]
    (try
      (testing "nothing listens before"
        (let [run (pair! c (str (kept srv) (options c)))]
          (is (= 1 (exit-of run)))
          (is (str/includes? (said run) (str "grog doesn't let a computer pair at http://" (:address srv))))))
      (testing "nor once it's cancelled"
        (open! srv)
        (is (= "cancelled" (:status (second (api :delete (str (:url srv) "/v1/connect"))))))
        (let [run (pair! c (str (kept srv) (options c)))]
          (is (= 1 (exit-of run)))
          (is (str/includes? (said run) "grog doesn't let a computer pair now"))))
      (testing "nor while something else connects"
        (h/with-fake [fake [(h/json-response {:device_auth_id "dev" :user_code "X-1" :interval "60"})]]
          (swap! (:config (:ctx srv)) assoc-in [:providers :openai :subscription :auth-url] (:url fake))
          (open! srv)
          (api :post (str (:url srv) "/v1/integrations/openai/connect"))
          (is (= 404 (:status (http/get (str "http://" (:address srv) "/computer.sh") {:throw false}))))))
      (finally
        (server/stop! srv)
        (.close ^ServerSocket (:sshd c))))))

(deftest one-computer-at-a-time
  (let [c (computer)
        attic (computer)
        srv (start! (sshd c))]
    (try
      (open! srv)
      (let [run (pair! c (str (served srv) (options c)))]
        (is (eventually 10000 #(= "code-compare" (stage srv))))
        (let [other (pair! attic (str (served srv) (str/replace (options attic) "deluxe" "attic")))]
          (is (= 1 (exit-of other)))
          (is (str/includes? (said other) "another computer is pairing with grog")))
        (api :post (str (:url srv) "/v1/connect/deny"))
        (exit-of run))
      (finally
        (server/stop! srv)
        (run! #(.close ^ServerSocket (:sshd %)) [c attic])))))

(deftest a-computer-that-isnt-ready
  (let [c (computer)
        srv (start! (sshd c))
        port (.getLocalPort ^ServerSocket (:sshd c))]
    (try
      (open! srv)
      (doseq [[args message] [[(str "--name deluxe --ssh-port " port " --host-key /nonexistent")
                               "There's no SSH host key in /nonexistent"]
                              [(str "--name deluxe --ssh-port " (h/free-port) " --host-key " (h/sh-quote (:key-file c)))
                               "Nothing takes SSH connections here on port"]
                              [(str (options c) " --name 'Deluxe!'") "Say what grog calls this computer with --name"]]]
        (let [run (pair! c (str (served srv) args))]
          (is (= 1 (exit-of run)) message)
          (is (str/includes? (said run) message) (said run))))
      (testing "nor where grog makes no sense"
        (let [run (pair! c (str "bash " (h/sh-quote h/computer-sh) " pair 'grog;x' " (options c)))]
          (is (= 1 (exit-of run)))
          (is (str/includes? (said run) "grog's address makes no sense"))))
      (is (= "script" (stage srv)) "none asked")
      (finally
        (server/stop! srv)
        (.close ^ServerSocket (:sshd c))))))

(defn ^:private fields
  "What grog answered the computer: a line of key=value each."
  [body]
  (into {} (for [line (str/split-lines (str body))
                 :let [[k v] (str/split line #"=" 2)]
                 :when v]
             [(keyword k) v])))

(deftest what-the-network-may-send
  (let [srv (start! "exit 255")
        pair-url (str "http://" (:address srv) "/v1/pair")
        post (fn [url body & [headers]]
               (let [resp (http/post url {:throw false
                                          :headers (or headers {"content-type" "application/json"})
                                          :body (config/generate body)})]
                 [(:status resp) (fields (:body resp))]))
        request {:v computers/protocol-version :name "deluxe" :user "greg" :host-key deluxe-key
                 :commitment (pairing/commitment "00112233445566778899aabbccddeeff")}]
    (try
      (open! srv)
      (testing "only JSON: web pages can't"
        (is (= 415 (first (post pair-url request {"content-type" "text/plain"})))))
      (testing "what makes no sense"
        (is (= [400 {:error "user: a user's name"}] (post pair-url (assoc request :user "-oProxyCommand=x"))))
        (is (= 400 (first (post pair-url (assoc request :host-key "ssh-ed25519 not base64!")))))
        (is (= 400 (first (post pair-url (dissoc request :commitment)))))
        (is (re-find #"this grog speaks version \d+ of pairing"
                     (str (:error (second (post pair-url (assoc request :v 1))))))))
      (testing "a nonce it didn't commit to"
        (let [[status {:keys [id key nonce expiresIn]}] (post pair-url request)]
          (is (= 200 status))
          (is (= grog-key key))
          (is (re-matches #"[0-9a-f]{32}" nonce))
          (is (<= 590 (parse-long expiresIn) 600))
          (is (= 400 (first (post (str pair-url "/" id "/nonce") {:nonce "ffffffffffffffffffffffffffffffff"}))))
          (is (= "script" (stage srv)) "nothing shows at grog")))
      (finally
        (server/stop! srv)))))

(deftest what-grog-serves
  (let [ctx {:config (atom (assoc config/defaults :name "Jarvis & co"))
             :connect (atom {:status "open" :addresses ["192.168.15.10"] :port 8485 :stop (fn [])})}
        serve (fn [uri headers] (pairing/handle ctx @(:connect ctx) {:request-method :get :uri uri :headers headers}))
        url-in #(second (re-find #"\ngrog_url='([^']*)'\n" %))]
    (testing "the script, knowing where the computer reaches grog"
      (let [{:keys [status headers body]} (serve "/" {"host" "grognix.local:8485" "accept" "*/*"})]
        (is (= 200 status))
        (is (str/starts-with? (get headers "Content-Type") "text/plain"))
        (is (str/starts-with? body "#!/usr/bin/env bash\n"))
        (is (= "http://grognix.local:8485" (url-in body)))
        (is (= body (:body (serve "/computer.sh" {"host" "grognix.local:8485"}))))))
    (testing "grog's own address, when the computer says nothing that makes sense"
      (is (= "http://192.168.15.10:8485" (url-in (:body (serve "/" {"host" "x';rm -rf ~;'"})))))
      (is (= "http://192.168.15.10:8485" (url-in (:body (serve "/" {}))))))
    (testing "for a browser, a page saying what to run"
      (let [{:keys [headers body]} (serve "/" {"host" "192.168.15.10:8485"
                                               "accept" "text/html,application/xhtml+xml,*/*;q=0.8"})]
        (is (str/starts-with? (get headers "Content-Type") "text/html"))
        (is (str/includes? body "<pre>curl -fsS 192.168.15.10:8485 | bash</pre>"))
        (is (str/includes? body "Pair this computer with Jarvis &amp; co"))))
    (testing "where it was reached"
      (is (= "grognix.local:8485" (pairing/reached-at "grognix.local:8485" 8485)))
      (is (= "192.168.15.10:8485" (pairing/reached-at "192.168.15.10" 8485)))
      (is (= "[fe80::1]:9000" (pairing/reached-at "[fe80::1]:9000" 8485)))
      (is (nil? (pairing/reached-at "grog'x" 8485)))
      (is (nil? (pairing/reached-at nil 8485))))))
