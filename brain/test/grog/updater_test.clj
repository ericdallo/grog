(ns grog.updater-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.config :as config]
            [grog.test-helpers :as h]
            [grog.updater :as updater]))

(defn ^:private lock [rev]
  {"nodes" {"root" {"inputs" {"grog" "grog"}}
            "grog" {"locked" {"type" "github" "owner" "ericdallo" "repo" "grog"
                              "rev" rev "narHash" (str "sha256-" rev) "lastModified" 1792427875}}}
   "root" "root"
   "version" 7})

(defn ^:private nix-args
  "What nix was asked, past its options, when `argv` runs nix."
  [argv]
  (some->> (seq (drop-while #(not= "nix" %) argv)) (drop 3) vec))

(defn ^:private stc
  "The action asked of switch-to-configuration, when `argv` runs it: [system
  action]."
  [argv]
  (when (str/ends-with? (first argv) "/bin/switch-to-configuration")
    [(str/replace (first argv) "/bin/switch-to-configuration" "") (second argv)]))

(defn ^:private world
  "A machine to update: a flake locking grog at `locked`, grog running
  `running` and answering (unless :silent), the latest grog being `latest`.
  Fetching or building fail with :fetch-fails or :build-fails; switching
  makes grog the latest, unless :stays; :busy polls say someone talks to
  grog; :spec: the machine runs that specialisation."
  [{:keys [locked running latest silent fetch-fails build-fails stays busy spec]}]
  (let [dir (str (fs/real-path (h/temp-dir)))
        flake (str (fs/create-dirs (fs/path dir "flake")))
        owner (str (fs/owner flake))
        previous (str (fs/create-dirs (fs/path dir "systems" "previous")))
        _ (when spec (fs/create-dirs (fs/path previous "specialisation" spec)))
        profile (str (fs/create-sym-link (fs/path dir "profile") previous))
        current (str (fs/create-sym-link (fs/path dir "current")
                                         (if spec (fs/path previous "specialisation" spec) previous)))
        passwd (str (fs/path dir "passwd"))
        commands (atom [])
        grog (atom (when-not silent {:revision running :busy false}))
        busy (atom (or busy 0))
        clock (atom 0)]
    (spit (str flake "/flake.lock") (json/generate-string (lock locked)))
    (spit passwd (str "root:x:0:0::/root:/bin/sh\n" owner ":x:1000:100::/home/" owner ":/bin/sh\n"))
    {:dir dir
     :flake flake
     :owner owner
     :previous previous
     :status (str (fs/path dir "status.json"))
     :commands commands
     :sys {:sh (fn [argv]
                 (swap! commands conj argv)
                 (let [[cmd & args] (nix-args argv)]
                   (cond
                     (and (= "flake" cmd) (= "update" (first args)))
                     (if fetch-fails
                       {:exit 1 :out "" :err "error: unable to download 'https://github.com': Couldn't resolve host"}
                       (do (spit (last args) (json/generate-string (lock latest)))
                           {:exit 0 :out "" :err ""}))

                     (= "build" cmd)
                     (if build-fails
                       {:exit 1 :out "" :err "error: builder for 'grog-voice.drv' failed with exit code 1"}
                       {:exit 0 :out "/nix/store/new-system\n" :err ""})

                     :else
                     (do (when-let [[_ action] (stc argv)]
                           (when (and (#{"switch" "test"} action) (not stays))
                             (swap! grog #(some-> % (assoc :revision latest)))))
                         {:exit 0 :out "" :err ""}))))
           :grog (fn []
                   (when-let [g @grog]
                     (if (pos? @busy)
                       (do (swap! busy dec) (assoc g :busy true))
                       g)))
           :now #(deref clock)
           :sleep #(swap! clock + %)
           :profile profile
           :current current
           :passwd passwd}}))

(defn ^:private update! [{:keys [flake status sys]} revision]
  (updater/update! {:flake flake :host "grognix" :input "grog" :status status :revision revision} sys))

(defn ^:private flake-rev [{:keys [flake]}]
  (get-in (json/parse-string (slurp (str flake "/flake.lock"))) ["nodes" "grog" "locked" "rev"]))

(defn ^:private switches
  "What was asked of switch-to-configuration, in order."
  [{:keys [commands]}]
  (keep stc @commands))

(deftest pure-parts
  (testing "what the flake has locked for an input"
    (is (= "abc" (get (updater/locked (lock "abc") "grog") "rev")))
    (is (nil? (updater/locked (lock "abc") "nixpkgs"))))
  (testing "whether the latest grog is another one: by revision, else by lock"
    (is (false? (updater/newer? "abc" nil {"rev" "abc"})))
    (is (true? (updater/newer? "abc-dirty" nil {"rev" "abc"})))
    (is (false? (updater/newer? nil {"rev" "abc" "narHash" "x"} {"rev" "abc" "narHash" "x"})))
    (is (true? (updater/newer? nil {"narHash" "x"} {"narHash" "y"}))))
  (testing "the system's output, quoting hosts nix would split"
    (is (= "/etc/nixos#nixosConfigurations.grognix.config.system.build.toplevel"
           (updater/toplevel "/etc/nixos" "grognix")))
    (is (= "/f#nixosConfigurations.\"my.host\".config.system.build.toplevel"
           (updater/toplevel "/f" "my.host"))))
  (testing "commands run by the flake's owner, in their home"
    (is (= ["env" "HOME=/root" "nix" "build"] (updater/as-owner "root" "/root" ["nix" "build"])))
    (is (= ["runuser" "-u" "alice" "--" "env" "HOME=/home/alice" "nix" "build"]
           (updater/as-owner "alice" "/home/alice" ["nix" "build"]))))
  (testing "switching, in a specialisation or not"
    (is (= [["nix-env" "-p" "/p" "--set" "/s"] ["/s/bin/switch-to-configuration" "switch"]]
           (updater/switch-commands "/p" "/s" nil)))
    (is (= [["nix-env" "-p" "/p" "--set" "/s"]
            ["/s/bin/switch-to-configuration" "boot"]
            ["/s/specialisation/grog/bin/switch-to-configuration" "test"]]
           (updater/switch-commands "/p" "/s" "grog"))))
  (testing "the end of what a command said"
    (is (= "b\nc" (updater/tail {:out "c\n\n" :err "b\n"})))
    (is (= 603 (count (updater/tail {:err (apply str (repeat 700 "x"))})))))
  (testing "where a user lives"
    (let [passwd (str (fs/path (h/temp-dir) "passwd"))]
      (spit passwd "root:x:0:0::/root:/bin/sh\nalice:x:1000:100:Alice:/home/alice:/bin/sh\n")
      (is (= "/home/alice" (updater/home-of passwd "alice")))
      (is (nil? (updater/home-of passwd "bob"))))))

(deftest already-the-latest
  (let [w (world {:locked "abc" :running "abc" :latest "abc"})
        result (update! w "abc")]
    (is (= "current" (:stage result)))
    (is (= {:stage "current" :from "abc" :to "abc" :to-date 1792427875000}
           (select-keys result [:stage :from :to :to-date])))
    (testing "nothing is built, nor switched"
      (is (empty? (filter #(= "build" (first (nix-args %))) @(:commands w))))
      (is (empty? (switches w))))
    (testing "the status file says so, for grog"
      (is (= "current" (:stage (config/parse (slurp (:status w)))))))
    (testing "the flake's owner fetched, in a lock of its own"
      (let [fetch (first (filter #(= "flake" (first (nix-args %))) @(:commands w)))]
        (is (= (updater/as-owner (:owner w) (str (if (= "root" (:owner w)) "/root" (str "/home/" (:owner w))))
                                 ["nix" "--extra-experimental-features" "nix-command flakes"
                                  "flake" "update" "grog" "--refresh" "--flake" (:flake w) "--output-lock-file"])
               (vec (butlast fetch))))
        (is (not= (str (:flake w) "/flake.lock") (last fetch)))))))

(deftest updating
  (let [w (world {:locked "abc" :running "abc" :latest "def"})
        result (update! w "abc")]
    (is (= {:stage "done" :from "abc" :to "def"} (select-keys result [:stage :from :to])))
    (testing "it built the system with the new lock, and switched to it"
      (let [build (first (filter #(= "build" (first (nix-args %))) @(:commands w)))]
        (is (= ["build" "/flake#nixosConfigurations.grognix.config.system.build.toplevel"]
               [(first (nix-args build)) (str/replace (second (nix-args build)) (:flake w) "/flake")]))
        (is (some #{"--reference-lock-file"} build)))
      (is (some #(= ["nix-env" "-p" (get-in w [:sys :profile]) "--set" "/nix/store/new-system"] %) @(:commands w)))
      (is (= [["/nix/store/new-system" "switch"]] (switches w))))
    (testing "the flake has the new lock, once it worked"
      (is (= "def" (flake-rev w))))
    (testing "the temporary lock is gone"
      (let [fetch (first (filter #(= "flake" (first (nix-args %))) @(:commands w)))]
        (is (not (fs/exists? (fs/parent (last fetch))))))))
  (testing "a grog that isn't the one locked (a checkout, say) goes to the latest"
    (let [w (world {:locked "abc" :running "abc-dirty" :latest "abc"})]
      (is (= "done" (:stage (update! w "abc-dirty"))))))
  (testing "a grog that doesn't know its revision: by what the flake locked, and it only has to answer"
    (let [w (world {:locked "abc" :running nil :latest "def" :stays true})]
      (is (= "done" (:stage (update! w nil))))
      (is (= "def" (flake-rev w))))
    (let [w (world {:locked "abc" :running nil :latest "abc"})]
      (is (= "current" (:stage (update! w nil)))))))

(deftest in-a-specialisation
  (let [w (world {:locked "abc" :running "abc" :latest "def" :spec "grog"})]
    (is (= "done" (:stage (update! w "abc"))))
    (testing "the boot menu from the system, the running system from its specialisation"
      (is (= [["/nix/store/new-system" "boot"] ["/nix/store/new-system/specialisation/grog" "test"]]
             (switches w))))))

(deftest failing
  (testing "to fetch"
    (let [w (world {:locked "abc" :running "abc" :latest "def" :fetch-fails true})
          result (update! w "abc")]
      (is (= "failed" (:stage result)))
      (is (str/includes? (:error result) "Couldn't fetch the latest grog: error: unable to download"))
      (is (= "abc" (flake-rev w)))))
  (testing "to build"
    (let [w (world {:locked "abc" :running "abc" :latest "def" :build-fails true})
          result (update! w "abc")]
      (is (= "failed" (:stage result)))
      (is (str/includes? (:error result) "Couldn't build it: error: builder for 'grog-voice.drv' failed"))
      (is (empty? (switches w)))
      (is (= "abc" (flake-rev w))))))

(deftest going-back
  (testing "when the new grog doesn't answer, to the previous system"
    (let [w (world {:locked "abc" :running "abc" :latest "def" :stays true})
          result (update! w "abc")]
      (is (= {:stage "failed" :rolled-back true} (select-keys result [:stage :rolled-back])))
      (is (str/includes? (:error result) "back to the previous system"))
      (is (= [["/nix/store/new-system" "switch"] [(:previous w) "switch"]] (switches w)))
      (is (some #(= ["nix-env" "-p" (get-in w [:sys :profile]) "--set" (:previous w)] %) @(:commands w)))
      (is (= "abc" (flake-rev w)))))
  (testing "not when the previous one didn't answer either"
    (let [w (world {:locked "abc" :running "abc" :latest "def" :silent true})
          result (update! w "abc")]
      (is (= "failed" (:stage result)))
      (is (nil? (:rolled-back result)))
      (is (= [["/nix/store/new-system" "switch"]] (switches w))))))

(deftest waiting-for-silence
  (let [w (world {:locked "abc" :running "abc" :latest "def" :busy 3})
        seen (atom [])
        sys (update (:sys w) :grog (fn [grog]
                                     (fn []
                                       (when (fs/exists? (:status w))
                                         (swap! seen conj [(:stage (config/parse (slurp (:status w))))
                                                           (count (switches w))]))
                                       (grog))))]
    (is (= "done" (:stage (update! (assoc w :sys sys) "abc"))))
    (testing "it switched once nobody talked to grog, saying it waited"
      (is (some #(= ["waiting" 0] %) @seen))
      (is (= [["/nix/store/new-system" "switch"]] (switches w))))))
