(ns grog.updater
  "Updating grog to the latest grog pushed: what `grog updater` does, run as
  root by the NixOS module's grog-update service (services.grog.update),
  which grog's hands start when someone asks (grog.update).

  It locks the system's flake with grog's input at its latest, in a lock of
  its own, and builds the system with it, as the flake's owner (nix won't
  open someone else's git repository as root). Once nobody is talking to
  grog, it switches to it, staying in the specialisation the machine runs,
  if it runs one; and once the new grog answers, as the revision it was
  updated to, the flake gets the new lock. Should it not answer, the
  machine goes back to the previous system.

  How it goes is in its status file, for grog (and whoever watches it) to
  read, {:stage :from :to :to-date :started-at :at :error :rolled-back}: its
  stages are fetching, current (grog already is the latest: it's over),
  building, waiting (for nobody to be talking to grog), switching,
  verifying, and then done or failed."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.log :as log]))

;; How long it waits at most for nobody to be talking to grog, before
;; switching all the same.
(def ^:private quiet-timeout-ms (* 10 60 1000))

;; How long the new grog has to answer.
(def ^:private answer-timeout-ms (* 3 60 1000))

(def ^:private poll-ms 2000)

(def ^:private nix-options ["--extra-experimental-features" "nix-command flakes"])

(defn locked
  "What `lock` (a flake.lock, with string keys) has locked for the root's
  `input` (\"rev\", \"narHash\", \"lastModified\"...), or nil."
  [lock input]
  (let [nodes (get lock "nodes")
        node (get-in nodes [(get lock "root" "root") "inputs" input])]
    (when (string? node)
      (get-in nodes [node "locked"]))))

(defn newer?
  "Whether the latest grog, as locked in `fresh`, isn't the one running: by
  its revision, when grog knows its own (`revision`), else by what the flake
  had locked (`old`)."
  [revision old fresh]
  (if revision
    (not= revision (get fresh "rev"))
    (not= (select-keys old ["rev" "narHash"]) (select-keys fresh ["rev" "narHash"]))))

(defn toplevel
  "The output of `flake` that is the system `host`."
  [flake host]
  (str flake "#nixosConfigurations."
       (if (re-matches #"[A-Za-z0-9_'-]+" host) host (pr-str host))
       ".config.system.build.toplevel"))

(defn as-owner
  "`argv`, run by `owner`, in their `home` (nix keeps its caches there)."
  [owner home argv]
  (into (if (= "root" owner) [] ["runuser" "-u" owner "--"])
        (into ["env" (str "HOME=" home)] argv)))

(defn switch-commands
  "How to switch to `system` (a NixOS system), making it the system
  `profile`: in specialisation `spec` when the machine runs one, the boot
  menu still coming from the system itself."
  [profile system spec]
  (into [["nix-env" "-p" profile "--set" system]]
        (if spec
          [[(str system "/bin/switch-to-configuration") "boot"]
           [(str system "/specialisation/" spec "/bin/switch-to-configuration") "test"]]
          [[(str system "/bin/switch-to-configuration") "switch"]])))

(defn tail
  "The end of what a command said: enough to tell what went wrong."
  [{:keys [out err]}]
  (let [text (->> (str/split-lines (str err "\n" out))
                  (map str/trimr)
                  (remove str/blank?)
                  (take-last 6)
                  (str/join "\n"))]
    (if (> (count text) 600)
      (str "..." (subs text (- (count text) 600)))
      text)))

(defn home-of
  "Where `user` lives, as `passwd` (like /etc/passwd) says."
  [passwd user]
  (some (fn [line]
          (let [[name _ _ _ _ home] (str/split line #":")]
            (when (= name user) home)))
        (str/split-lines (slurp passwd))))

(defn ^:private real [path]
  (try (str (fs/real-path path)) (catch Exception _ nil)))

(defn specialisation
  "Which of the specialisations of `system` the machine runs (`current`, like
  /run/current-system), if it runs one of them: its name."
  [system current]
  (let [running (real current)
        dir (fs/path system "specialisation")]
    (when (and running (fs/directory? dir))
      (some #(when (= running (real %)) (str (fs/file-name %)))
            (fs/list-dir dir)))))

(defn ^:private read-lock [file]
  (when (fs/exists? file)
    (json/parse-string (slurp file))))

(defn ^:private write-status!
  "`status` in `file`, all at once, for grog to read."
  [file status]
  (let [tmp (str file ".tmp")]
    (some-> (fs/parent file) fs/create-dirs)
    (spit tmp (config/generate status))
    (fs/move tmp file {:replace-existing true :atomic-move true})))

(defn ^:private run-all!
  "Runs `commands` with `sh`, one after the other until one fails: what the
  last one gave."
  [sh commands]
  (reduce (fn [_ argv]
            (let [result (sh argv)]
              (if (zero? (:exit result)) result (reduced result))))
          {:exit 0 :out "" :err ""}
          commands))

(defn ^:private wait-for
  "Waits until grog's status satisfies `pred` (nil: it doesn't answer),
  looking every `poll-ms`, for `timeout-ms` at most: whether it did."
  [{:keys [grog sleep now]} pred timeout-ms]
  (let [deadline (+ (now) timeout-ms)]
    (loop []
      (cond
        (pred (grog)) true
        (>= (now) deadline) false
        :else (do (sleep poll-ms) (recur))))))

(defn ^:private wait-quiet!
  "Waits for nobody to be talking to grog, saying so when someone is."
  [{:keys [grog] :as sys} say!]
  (when (:busy (grog))
    (say! {:stage "waiting"})
    (wait-for sys #(not (:busy %)) quiet-timeout-ms)))

(defn ^:private write-lock!
  "The new lock (`fresh-file`) in the flake, as its `owner`'s."
  [sh lock-file fresh-file owner]
  (let [existed? (fs/exists? lock-file)]
    ;; Over the old one, which stays theirs.
    (spit lock-file (slurp fresh-file))
    (when-not (or existed? (= "root" owner))
      (sh ["chown" (str owner ":") lock-file]))))

(defn ^:private updated?
  "Whether grog's `status` says it's the revision it was updated `to`: any
  will do when either isn't known (the NixOS module may not tell grog its
  own)."
  [to status]
  (boolean (and status (or (nil? to) (nil? (:revision status)) (= to (:revision status))))))

(defn ^:private switch!
  "Switches to `system`, then waits for the new grog to answer (as revision
  `to`, when known), going back to the previous system when it doesn't and
  the previous grog did (`answered?`)."
  [{:keys [sh grog profile current] :as sys} say! {:keys [system to answered? done!]}]
  (let [previous (real profile)
        spec (some-> previous (specialisation current))]
    (say! {:stage "switching"})
    (let [switched (run-all! sh (switch-commands profile system spec))]
      (say! {:stage "verifying"})
      (cond
        (wait-for sys #(updated? to %) answer-timeout-ms)
        (do (done!) (say! {:stage "done"}))

        (and answered? previous)
        (do (run-all! sh (switch-commands profile previous spec))
            (say! {:stage "failed"
                   :rolled-back true
                   :error (str "The new grog didn't answer"
                               (when-not (zero? (:exit switched)) (str " (" (tail switched) ")"))
                               ": back to the previous system.")}))

        :else
        (say! {:stage "failed"
               :error (str "The new grog didn't answer, as the previous one hadn't"
                           (when (grog) " (it answers, as another revision)") ".")})))))

(defn update!
  "Updates grog (see the namespace): the system `host` of `flake`, with
  grog's `input` at its latest, grog's running `revision` being known or
  not. Says how it goes in the `status` file. Returns its last status. The
  world is in `sys` (`system`): :sh running commands, :grog telling what
  grog says of itself, the clock (:now, :sleep), the system :profile, the
  :current system, and :passwd."
  [{:keys [flake host input status revision]} {:keys [sh grog now passwd] :as sys}]
  (let [state (atom {:started-at (now)})
        say! (fn [change]
               (let [s (swap! state merge change {:at (now)})]
                 (log/info (str "update: " (:stage s) (some->> (:error s) (str ": "))))
                 (write-status! status s)
                 s))
        tmp (str (fs/create-temp-dir {:prefix "grog-update"}))]
    (try
      (let [owner (str (fs/owner flake))
            home (or (home-of passwd owner) (if (= "root" owner) "/root" (str "/home/" owner)))
            nix (fn [& args]
                  (sh (as-owner owner home (into ["nix"] (concat nix-options args)))))
            before (grog)
            revision (or revision (:revision before))
            fresh-file (str tmp "/flake.lock")
            lock-file (str flake "/flake.lock")]
        (say! {:stage "fetching" :from revision})
        (when-not (= "root" owner)
          (sh ["chown" owner tmp]))
        (let [fetched (nix "flake" "update" input "--flake" flake "--output-lock-file" fresh-file)
              old (some-> (read-lock lock-file) (locked input))
              fresh (when (zero? (:exit fetched)) (some-> (read-lock fresh-file) (locked input)))
              to (get fresh "rev")
              versions {:from (or revision (get old "rev"))
                        :to to
                        :to-date (some-> (get fresh "lastModified") (* 1000))}]
          (cond
            (not fresh)
            (say! {:stage "failed" :error (str "Couldn't fetch the latest grog: " (tail fetched))})

            (not (newer? revision old fresh))
            (say! (assoc versions :stage "current"))

            :else
            (do (say! (assoc versions :stage "building"))
                (let [built (nix "build" (toplevel flake host) "--reference-lock-file" fresh-file
                                 "--no-write-lock-file" "--no-link" "--print-out-paths")
                      system (some-> (:out built) str/trim not-empty str/split-lines last str/trim)]
                  (if-not (and (zero? (:exit built)) system)
                    (say! {:stage "failed" :error (str "Couldn't build it: " (tail built))})
                    (do (wait-quiet! sys say!)
                        (switch! sys say! {:system system
                                           :to to
                                           :answered? (some? before)
                                           :done! #(write-lock! sh lock-file fresh-file owner)}))))))))
      (catch Exception e
        (say! {:stage "failed" :error (str "Couldn't update: " (or (ex-message e) (.getName (class e))))}))
      (finally
        (fs/delete-tree tmp)))))

(defn ^:private sh!
  "Runs `argv`: {:exit :out :err}, what it said being logged."
  [argv]
  (log/info "update: running" (str/join " " argv))
  (try
    (let [{:keys [exit out err]} @(p/process argv {:out :string :err :string})]
      {:exit exit :out out :err err})
    (catch Exception e
      {:exit 127 :out "" :err (or (ex-message e) (.getName (class e)))})))

(defn ^:private status-of
  "What grog at `url` says of itself ({:revision :busy}), or nil when it
  doesn't answer."
  [url]
  (try
    (let [resp (http/get (str url "/v1/status") {:throw false :timeout 5000})]
      (when (= 200 (:status resp))
        (select-keys (config/parse (:body resp)) [:revision :busy])))
    (catch Exception _ nil)))

(defn system
  "The world `run!` updates, with grog at `url`."
  [url]
  {:sh sh!
   :grog #(status-of url)
   :now #(System/currentTimeMillis)
   :sleep #(Thread/sleep (long %))
   :profile "/nix/var/nix/profiles/system"
   :current "/run/current-system"
   :passwd "/etc/passwd"})
