(ns grog.computer-sh-test
  "grog's hands on a computer, `computer.sh hands`: run with bash, what they
  run faked (grog.test-helpers/computer!), asked and read as grog does
  (grog.computers)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.computers :as computers]
            [grog.config :as config]
            [grog.test-helpers :as h]))

(defn ^:private asked!
  "What `computer`'s hands print when grog sends them `line`, with `env`."
  [computer line env]
  (:out @(p/process {:cmd ["bash" h/computer-sh "hands"] :in line :out :string :err :string
                     :extra-env (merge (:env computer) env)})))

(defn ^:private hands!
  "What `computer`'s hands answer grog asking for `request` (with `env`), as
  grog reads it."
  ([computer request]
   (hands! computer request {}))
  ([computer request env]
   (computers/answer-in (asked! computer
                                (computers/request-line (merge {:v computers/protocol-version :from "grog"} request))
                                env))))

(defn ^:private policy!
  "Says what `computer` lets grog do, as a user would."
  [computer policy]
  (fs/create-dirs (fs/parent (:policy-file computer)))
  (spit (:policy-file computer) (config/generate policy {:pretty true})))

(defn ^:private command-of
  "What runs `id`, as its user."
  [id]
  (str/trim (:out (p/shell {:out :string} "id" id))))

(def ^:private default-policy
  {:allow ["status" "notify" "open" "media" "volume" "lock"]
   :ask ["suspend" "power-off" "shell"]
   :deny []})

(deftest saying-hello
  (let [c (h/computer!)
        hello (hands! c {:action "hello"})]
    (is (= computers/protocol-version (:v hello)))
    (is (= default-policy (:policy hello)) "the default policy, without one")
    (is (= (command-of "-un") (:user hello)))
    (is (string? (:host hello)))
    (is (empty? (h/ran c)) "nothing runs")))

(deftest policies
  (testing "the user's, written by hand"
    (let [c (h/computer!)]
      (policy! c {:allow ["status" "shell"]})
      (is (= {:allow ["status" "shell"] :ask [] :deny []} (:policy (hands! c {:action "hello"}))))
      (spit (:policy-file c) "{\"deny\": [\"lock\"],\n \"allow\": [\"lock\", \"status\"]}\n")
      (is (= {:allow ["lock" "status"] :ask [] :deny ["lock"]} (:policy (hands! c {:action "hello"}))))))
  (testing "denying wins, and what it doesn't name is denied"
    (let [c (h/computer!)]
      (policy! c {:allow ["status" "lock"] :deny ["lock"]})
      (is (re-matches #".+ doesn't let grog lock the screen \(its policy: .+/grog/hands\.json\)"
                      (str (:error (hands! c {:action "lock"})))))
      (is (re-find #"doesn't let grog run a command" (str (:error (hands! c {:action "shell" :command "ls"})))))
      (is (empty? (h/ran c)))))
  (testing "one that makes no sense: grog may do nothing"
    (let [c (h/computer!)]
      (fs/create-dirs (fs/parent (:policy-file c)))
      (doseq [policy ["{nope" "{\"allow\": \"shell\"}" "" "{\"allow\": [\"status\"], \"also\": 1}"]]
        (spit (:policy-file c) policy)
        (is (re-find #"its policy, .+/grog/hands\.json, should be \{\"allow\": \[\.\.\.\]"
                     (str (:error (hands! c {:action "status"}))))
            policy))
      (is (empty? (h/ran c))))))

(deftest what-it-allows-runs-right-away
  (testing "a notification"
    (let [c (h/computer!)]
      (is (= {:done true} (hands! c {:action "notify" :text "Dinner!\nAt 8."})))
      (is (= [["notify-send" "--app-name=grog" "--" "grog" "Dinner!\nAt 8."]] (h/ran c)))))
  (testing "opening, in the user's session"
    (let [c (h/computer!)]
      (is (= {:done true} (hands! c {:action "open" :target "~/notes.txt"})))
      (is (= [["systemd-run" "--user" "--collect" "--quiet" "--" "xdg-open" (str (:home c) "/notes.txt")]]
             (h/ran c)))))
  (testing "the volume, as it is then"
    (let [c (h/computer!)]
      (is (= {:level 40 :muted true} (hands! c {:action "volume" :mute true :change -10} {"VOLUME" "0.40 [MUTED]"})))
      (is (= [["wpctl" "set-mute" "@DEFAULT_AUDIO_SINK@" "1"]
              ["wpctl" "set-volume" "-l" "1" "@DEFAULT_AUDIO_SINK@" "10%-"]
              ["wpctl" "get-volume" "@DEFAULT_AUDIO_SINK@"]]
             (h/ran c)))
      (is (= {:level 100 :muted false} (hands! c {:action "volume" :level 100} {"VOLUME" "1.00"})))
      (is (= {:level 35 :muted false} (hands! c {:action "volume"} {"VOLUME" "0.345"})) "telling it, rounded")))
  (testing "how it's doing"
    (let [c (h/computer! {"loginctl" (str "case $1 in\n"
                                          "  list-sessions) echo \"7 1000 $(id -un) seat0 tty2\" ;;\n"
                                          "  show-session) if [[ $4 == Type ]]; then echo wayland; else echo no; fi ;;\n"
                                          "esac")
                          "playerctl" "echo 'playing: playing, Arctic Monkeys - 505 (spotify)'"})
          status (:status (hands! c {:action "status"}))]
      (is (re-find #"(?m)^name: .+$" status))
      (is (re-find #"(?m)^load: [\d.]+ [\d.]+ [\d.]+, \d+ CPUs$" status))
      (is (re-find #"(?m)^screen: .+'s \(wayland\), unlocked$" status))
      (is (re-find #"(?m)^playing: playing, Arctic Monkeys - 505 \(spotify\)$" status))
      (is (re-find #"(?m)^volume: 0\.40$" status))))
  (testing "saying why it failed"
    (let [c (h/computer! {"playerctl" "echo 'No players found'; exit 1"})]
      (is (= {:error "playerctl failed: No players found"} (hands! c {:action "media" :media "next"}))))
    (let [c (h/computer! {"loginctl" ""})]
      (is (re-matches #"it couldn't be locked: no screen of .+ to lock" (str (:error (hands! c {:action "lock"}))))))))

(deftest what-it-asks
  (testing "runs once someone at it allows it, seeing the command"
    (let [c (h/computer!)]
      (is (= {:exit 0 :output "hi\n" :timed-out false :truncated false}
             (hands! c {:action "shell" :command "echo hi"} {"ANSWER" "allow"})))
      (let [[ask] (h/ran c)]
        (is (= "notify-send" (first ask)))
        (is (some #{"--wait"} ask))
        (is (= ["grog wants to run a command" "echo hi"] (take-last 2 ask))))))
  (testing "suspending: in a moment, as the user may, once someone clicked it"
    (let [c (h/computer! {"busctl" "if [[ $* == *CanSuspend* ]]; then echo 's \"yes\"'; fi"})]
      (is (= {:done true} (hands! c {:action "suspend"} {"ANSWER" "default"})))
      (let [[ask & after] (h/ran c)]
        (is (= ["grog wants to suspend the computer" "Allow it?"] (take-last 2 ask)))
        (is (= ["systemd-run" "--user" "--collect" "--quiet" "--on-active=1" "systemctl" "suspend"] (last after))))))
  (testing "not when they close it"
    (let [c (h/computer!)]
      (is (re-matches #"nobody allowed it on .+ \(someone at it has 30 s to\)"
                      (str (:error (hands! c {:action "power-off"})))))
      (is (= 1 (count (h/ran c))) "it isn't turned off")))
  (testing "nor when nobody answers in time: the notification goes away"
    (let [c (h/computer! {"timeout" (str "if [[ $2 == notify-send ]]; then echo 17; exit 124; fi\n"
                                         "exec " (h/sh-quote (fs/which "timeout")) " \"$@\"")})]
      (is (re-find #"nobody allowed it" (str (:error (hands! c {:action "power-off"})))))
      (is (some #{["busctl" "--user" "call" "org.freedesktop.Notifications" "/org/freedesktop/Notifications"
                   "org.freedesktop.Notifications" "CloseNotification" "u" "17"]}
                (h/ran c)))))
  (testing "nor when there's no session to ask in"
    (let [c (h/computer! {"notify-send" "echo 'Cannot connect to the bus' >&2; exit 1"})]
      (is (re-matches #"nobody is there to allow it on .+: it can't ask anyone \(no session, or no notify-send\)"
                      (str (:error (hands! c {:action "shell" :command "ls"}))))))))

(deftest running-commands
  (let [c (h/computer!)]
    (policy! c {:allow ["shell"]})
    (testing "what they print, as it is"
      (is (= {:exit 3 :output "quote \" backslash \\ tab \t ação 🎉\nerr\n" :timed-out false :truncated false}
             (hands! c {:action "shell"
                        :command "printf 'quote \" backslash \\\\ tab \\t ação 🎉\\n'; echo err >&2; exit 3"}))))
    (testing "without input"
      (is (= {:exit 0 :output "" :timed-out false :truncated false} (hands! c {:action "shell" :command "cat"}))))
    (testing "only so much of what they print"
      (let [{:keys [output truncated]} (hands! c {:action "shell" :command "head -c 40000 /dev/zero | tr '\\0' x"})]
        (is (= 32000 (count output)))
        (is (true? truncated))))
    (testing "past their time, killed"
      (let [started (System/currentTimeMillis)]
        (is (= {:output "started\n" :timed-out true :truncated false}
               (dissoc (hands! c {:action "shell" :command "echo started; sleep 10" :timeout-ms 300}) :exit)))
        (is (< (- (System/currentTimeMillis) started) 5000))))
    (testing "what they leave running goes on without them"
      (let [started (System/currentTimeMillis)]
        (is (= {:exit 0 :output "" :timed-out false :truncated false} (hands! c {:action "shell" :command "sleep 10 &"})))
        (is (< (- (System/currentTimeMillis) started) 5000))))
    (testing "in reach of the user's session, which SSH may not say"
      (let [uid (command-of "-u")]
        (is (= (format "/run/user/%s unix:path=/run/user/%s/bus\n" uid uid)
               (:output (hands! c {:action "shell" :command "echo $XDG_RUNTIME_DIR $DBUS_SESSION_BUS_ADDRESS"}
                                {"XDG_RUNTIME_DIR" "" "DBUS_SESSION_BUS_ADDRESS" ""}))))))))

(deftest requests-that-make-no-sense
  (let [c (h/computer!)
        error #(str (:error (hands! c %)))]
    (doseq [[request message] [[{:action "format"}
                                #"action: one of hello, unpair, status, notify, open, media, volume, lock, suspend, power-off, shell"]
                               [{:action "notify"} #"text: text of up to 1000 characters"]
                               [{:action "notify" :text "  "} #"text: text of up to 1000 characters"]
                               [{:action "open" :target "javascript:alert(1)"} #"target: a link"]
                               [{:action "open" :target "https://a\nb"} #"on one line"]
                               [{:action "media" :media "rewind"} #"media: one of next, pause, play, play-pause, previous, stop"]
                               [{:action "volume" :level 300} #"level must be a number from 0 to 100"]
                               [{:action "volume" :change "08"} #"change must be a number from -100 to 100"]
                               [{:action "volume" :mute "maybe"} #"mute must be true or false"]
                               [{:action "shell" :command "ls" :timeout-ms 0} #"timeoutMs: a whole number of milliseconds"]
                               [{:action "lock" :from "evil\u001b[31m"} #"from: a short name"]
                               [{:action "unpair" :key "AAAAgrog"} #"key: an SSH public key"]]]
      (is (re-find message (error request)) (pr-str request)))
    (testing "nor from another grog"
      (is (= "this computer's hands are newer than grog: update grog" (error {:action "status" :v 1})))
      (is (= "this computer's hands are older than grog: pair it again" (error {:action "status" :v 3})))
      (doseq [line ["{\"v\": 1, \"action\": \"status\"}\n" "" "v=Mg== action=!!\n"]]
        (is (= {:error "send a request: a line of key=value fields, each value in base64"}
               (computers/answer-in (asked! c line {})))
            (pr-str line))))
    (is (empty? (h/ran c)) "nothing runs")))

(deftest unpairing
  (let [c (h/computer!)
        mine "ssh-ed25519 AAAAmine me@laptop"
        grogs (str "restrict,from=\"192.168.15.0/24\",command=\"" (:hands-file c) " hands\" ssh-ed25519 AAAAgrog grog")]
    (fs/create-dirs (fs/parent (:authorized-keys c)))
    (spit (:authorized-keys c) (str mine "\n" grogs "\n"))
    (is (= {:done true} (hands! c {:action "unpair" :key "ssh-ed25519 AAAAgrog grog"})))
    (is (= (str mine "\n") (slurp (:authorized-keys c))) "only grog's line goes")
    (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (:authorized-keys c)))))))
