(ns grog.computers-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.computers :as computers]
            [grog.config :as config]
            [grog.test-helpers :as h]
            [grog.tools :as tools]))

(def ^:private deluxe
  {:name "deluxe" :host "192.168.15.90" :port 22 :user "greg"
   :host-key "ssh-ed25519 AAAAdeluxe" :mac "02:00:5e:00:00:01" :added-at 1792427875000})

(defn ^:private settings
  "Settings with a state directory of their own, reaching computers with
  `ssh`."
  ([] (settings "ssh"))
  ([ssh]
   (-> (assoc config/defaults :state-dir (h/temp-dir) :name "grog")
       (assoc-in [:computers :ssh] ssh))))

(defn ^:private script!
  "An executable bash script `name` in `dir`, running `body`."
  [dir name body]
  (let [f (str (fs/path dir name))]
    (spit f (str "#!" (fs/which "bash") "\n" body "\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    f))

(defn ^:private hands-ssh!
  "A fake ssh in `dir` running grog's hands here, as `grog hands --stdio`
  runs on a computer, with `policy`. It keeps how it was run in ssh-args."
  [dir policy]
  (let [config-home (str (fs/path dir "config"))
        bb (str (fs/read-link "/proc/self/exe"))
        classpath (str/join ":" (map #(str (fs/absolutize %)) ["src" "resources"]))]
    (fs/create-dirs (fs/path config-home "grog"))
    (spit (str (fs/path config-home "grog" "hands.json")) (config/generate policy))
    (script! dir "ssh" (str "printf '%s\\n' \"$@\" > " (fs/path dir "ssh-args") "\n"
                            "XDG_CONFIG_HOME=" config-home " exec " bb " -cp " classpath
                            " -m grog.main hands --stdio"))))

(deftest keeping-them
  (let [config (settings)
        attic (assoc deluxe :name "attic" :host "192.168.15.91" :host-key "ssh-ed25519 AAAAattic")]
    (is (= [] (computers/all config)))
    (computers/add! config deluxe)
    (computers/add! config attic)
    (is (= ["attic" "deluxe"] (map :name (computers/all config))))
    (is (= deluxe (computers/by-name config "deluxe")))
    (is (= [{:name "attic" :user "greg" :host "192.168.15.91" :added-at 1792427875000}
            {:name "deluxe" :user "greg" :host "192.168.15.90" :added-at 1792427875000}]
           (computers/summary config)))
    (testing "their host keys, pinned by name"
      (is (= "attic ssh-ed25519 AAAAattic\ndeluxe ssh-ed25519 AAAAdeluxe\n"
             (slurp (computers/known-hosts-file config)))))
    (testing "only grog can read them"
      (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (computers/file config)))))
      (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (computers/known-hosts-file config))))))
    (testing "one paired again replaces the old one"
      (computers/add! config (assoc deluxe :host "192.168.15.99"))
      (is (= "192.168.15.99" (:host (computers/by-name config "deluxe"))))
      (is (= 2 (count (computers/all config)))))
    (testing "forgotten"
      (computers/remove! config "attic")
      (is (= ["deluxe"] (map :name (computers/all config))))
      (is (= "deluxe ssh-ed25519 AAAAdeluxe\n" (slurp (computers/known-hosts-file config)))))))

(deftest reaching-them-over-ssh
  (let [config (settings "/run/current-system/sw/bin/ssh")
        command (computers/ssh-command config deluxe)]
    (is (= "/run/current-system/sw/bin/ssh" (first command)))
    (is (= ["-p" "22" "-l" "greg" "192.168.15.90" "grog"] (take-last 6 command)))
    (doseq [option ["BatchMode=yes" "StrictHostKeyChecking=yes" "HostKeyAlias=deluxe" "IdentitiesOnly=yes"
                    "GlobalKnownHostsFile=/dev/null" (str "UserKnownHostsFile=" (computers/known-hosts-file config))]]
      (is (some #{option} command) option))
    (testing "not with what ssh would take for its options"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"makes no sense: pair it again"
                            (computers/ssh-command config (assoc deluxe :host "-oProxyCommand=touch /tmp/x"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"makes no sense"
                            (computers/ssh-command config (assoc deluxe :user "-x")))))))

(deftest what-ssh-says
  (is (= "deluxe can't be reached: it may be off or asleep, or somewhere else now"
         (computers/unreachable "deluxe" "ssh: connect to host 192.168.15.90 port 22: No route to host\n")))
  (is (= "deluxe's SSH host key changed: if it was set up again, pair it again"
         (computers/unreachable "deluxe" "Host key verification failed.\n")))
  (is (= "deluxe doesn't let grog in anymore: pair it again"
         (computers/unreachable "deluxe" "greg@192.168.15.90: Permission denied (publickey).\n")))
  (is (= "deluxe doesn't take SSH connections: is its SSH server on?"
         (computers/unreachable "deluxe" "ssh: connect to host 192.168.15.90 port 22: Connection refused\n")))
  (is (= "deluxe can't be reached (kex_exchange_identification: read: Connection reset by peer)"
         (computers/unreachable "deluxe" "\nkex_exchange_identification: read: Connection reset by peer\n"))))

(deftest their-answers
  (is (= {:done true} (computers/answer-in "Welcome to deluxe!\n{\"done\": true}\n")) "past what the shell said")
  (is (nil? (computers/answer-in "nope")))
  (is (nil? (computers/answer-in ""))))

(deftest asking-their-hands
  (testing "through grog's hands, as they run on a computer"
    (let [dir (h/temp-dir)
          config (settings (hands-ssh! dir {:allow ["shell" "status"] :ask [] :deny []}))]
      (computers/add! config deluxe)
      (let [hello (computers/ask! config "deluxe" {:action "hello"} 5000)]
        (is (= 1 (:v hello)))
        (is (= {:allow ["shell" "status"] :ask [] :deny []} (:policy hello))))
      (is (= {:exit 0 :output "hi\n"} (computers/act! config {:computer "deluxe" :action "shell" :command "echo hi"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"doesn't let grog lock the screen"
                            (computers/act! config {:computer "deluxe" :action "lock"})))
      (is (= ["-l" "greg" "192.168.15.90" "grog"]
             (take-last 4 (str/split-lines (slurp (str (fs/path dir "ssh-args")))))))))
  (testing "saying why it can't be reached"
    (let [dir (h/temp-dir)
          config (settings (script! dir "ssh" (str "echo 'ssh: connect to host 192.168.15.90 port 22: No route to "
                                                   "host' >&2; exit 255")))]
      (computers/add! config deluxe)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deluxe can't be reached: it may be off or asleep"
                            (computers/ask! config "deluxe" {:action "hello"} 5000)))))
  (testing "or that it said nothing grog understands"
    (let [dir (h/temp-dir)
          config (settings (script! dir "ssh" "cat > /dev/null; echo 'bash: grog: command not found'; exit 127"))]
      (computers/add! config deluxe)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deluxe gave no answer: is grog still installed there\?"
                            (computers/ask! config "deluxe" {:action "hello"} 5000)))))
  (testing "or not in time"
    (let [dir (h/temp-dir)
          config (settings (script! dir "ssh" "sleep 30"))]
      (computers/add! config deluxe)
      (with-redefs [computers/reach-ms 0]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deluxe took too long to answer"
                              (computers/ask! config "deluxe" {:action "hello"} 300))))))
  (testing "not one it doesn't know"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"there's no computer called attic"
                          (computers/ask! (settings) "attic" {:action "hello"} 5000)))))

(deftest forgetting-them
  (testing "asking its hands to forget grog first"
    (let [dir (h/temp-dir)
          request (str (fs/path dir "request"))
          config (settings (script! dir "ssh" (str "cat > " request "; echo '{\"done\": true}'")))]
      (fs/create-dirs (computers/dir config))
      (spit (str (computers/key-file config) ".pub") "ssh-ed25519 AAAAgrog grog\n")
      (computers/add! config deluxe)
      (is (= {:name "deluxe" :unpaired true} (computers/forget! config "deluxe")))
      (is (= {:v 1 :from "grog" :action "unpair" :key "ssh-ed25519 AAAAgrog grog"} (config/parse (slurp request))))
      (is (= [] (computers/all config)))))
  (testing "even when it can't be reached"
    (let [dir (h/temp-dir)
          config (settings (script! dir "ssh" "exit 255"))]
      (fs/create-dirs (computers/dir config))
      (spit (str (computers/key-file config) ".pub") "ssh-ed25519 AAAAgrog grog\n")
      (computers/add! config deluxe)
      (is (= {:name "deluxe" :unpaired false} (computers/forget! config "deluxe")))
      (is (= [] (computers/all config))))))

(deftest waking-them
  (let [packet (computers/magic-packet "e0:d5:5e:f6:11:0c")]
    (is (= 102 (alength packet)))
    (is (every? #(= -1 %) (take 6 packet)))
    (is (= [-32 -43 94 -10 17 12] (vec (take 6 (drop 6 packet)))))
    (is (= (vec (take 6 (drop 6 packet))) (vec (take-last 6 packet)))))
  (is (= ["255.255.255.255" "192.168.15.255"] (computers/broadcasts "192.168.15.90")))
  (is (= ["255.255.255.255"] (computers/broadcasts "fe80::1")))
  (testing "not without its network card's address"
    (let [config (settings)]
      (computers/add! config (dissoc deluxe :mac))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"doesn't know deluxe's network card"
                            (computers/act! config {:computer "deluxe" :action "wake"}))))))

(deftest the-tool
  (testing "only with computers"
    (is (nil? (computers/tool (settings))))
    (is (not-any? #{"computer"} (map :name (tools/available (settings))))))
  (let [config (settings)]
    (computers/add! config deluxe)
    (let [{:keys [parameters changes?]} (computers/tool config)]
      (is (= ["deluxe"] (get-in parameters [:properties :computer :enum])))
      (is (false? (changes? {:computer "deluxe" :action "status"})))
      (is (true? (changes? {:computer "deluxe" :action "notify"})))
      (is (some #{"computer"} (map :name (tools/available config)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"there's no computer called attic \(there's deluxe\)"
                          (computers/act! config {:computer "attic" :action "status"})))))
