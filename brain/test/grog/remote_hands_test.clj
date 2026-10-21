(ns grog.remote-hands-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.config :as config]
            [grog.remote-hands :as rh]
            [grog.test-helpers :as h]))

(defn ^:private ok
  ([] (ok ""))
  ([output] {:exit 0 :output output :timed-out false :truncated false}))

(defn ^:private computer
  "A computer whose commands answer (`answer` command), keeping them in
  :ran, with `policy`."
  ([answer]
   (computer answer rh/default-policy))
  ([answer policy]
   (let [ran (atom [])]
     {:ran ran
      :run (fn [command _] (swap! ran conj command) (answer command))
      :policy (constantly policy)
      :host "deluxe"
      :user "greg"
      :home "/home/greg"
      :authorized-keys "/nonexistent/authorized_keys"})))

(defn ^:private request [m]
  (merge {:v rh/protocol-version :from "grog"} m))

(defn ^:private asking? [command]
  (and (vector? command) (= "notify-send" (first command)) (some #{"--wait"} command)))

(deftest policies
  (testing "what the default lets grog do"
    (is (= :allow (rh/mode rh/default-policy "status")))
    (is (= :allow (rh/mode rh/default-policy "lock")))
    (is (= :ask (rh/mode rh/default-policy "shell")))
    (is (= :ask (rh/mode rh/default-policy "power-off")))
    (is (= :deny (rh/mode rh/default-policy "format-disk"))))
  (testing "denying wins"
    (is (= :deny (rh/mode {:allow ["shell"] :deny ["shell"]} "shell"))))
  (testing "trusting"
    (is (every? #(= :allow (rh/mode rh/trusting-policy %)) rh/actions))))

(deftest reading-a-policy
  (let [dir (h/temp-dir)
        file (str (fs/path dir "hands.json"))]
    (is (= rh/default-policy (rh/read-policy file)) "none: the default")
    (spit file (config/generate {:allow ["status" "shell"]}))
    (is (= {:allow ["status" "shell"] :ask [] :deny []} (rh/read-policy file)))
    (spit file "{nope")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be \{\"allow\"" (rh/read-policy file)))
    (spit file (config/generate {:allow "shell"}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be \{\"allow\"" (rh/read-policy file)))))

(deftest requests
  (testing "what makes sense"
    (is (= {:action "status" :from "grog"} (rh/check (request {:action "status"}))))
    (is (= {:action "notify" :text "Dinner!" :from "Jarvis"}
           (rh/check (request {:action "notify" :text "Dinner!" :from "Jarvis"}))))
    (is (= "grog" (:from (rh/check {:v rh/protocol-version :action "lock"}))) "grog, unless it says")
    (is (= {:action "shell" :command "ls" :timeout-ms rh/default-timeout-ms :from "grog"}
           (rh/check (request {:action "shell" :command "ls"}))))
    (is (= {:action "open" :target "https://example.com/a b" :from "grog"}
           (rh/check (request {:action "open" :target "https://example.com/a b"}))))
    (is (= "~/notes.txt" (:target (rh/check (request {:action "open" :target "~/notes.txt"})))))
    (is (= {:action "volume" :change -10 :from "grog"} (rh/check (request {:action "volume" :change -10})))))
  (testing "what doesn't, saying why"
    (doseq [[req message] [[nil #"send a JSON object"]
                           [{:action "status"} #"speaks version 1"]
                           [(request {:action "format"}) #"action: one of hello, unpair, status"]
                           [(request {:action "notify"}) #"text: text of up to"]
                           [(request {:action "open" :target "javascript:alert(1)"}) #"target: a link"]
                           [(request {:action "open" :target "https://a\nb"}) #"on one line"]
                           [(request {:action "media" :media "rewind"}) #"media: one of next"]
                           [(request {:action "volume" :level 300}) #"level must be a number"]
                           [(request {:action "shell" :command "ls" :timeout-ms 0}) #"timeoutMs"]
                           [(request {:action "lock" :from "<script>"}) #"from: a name"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo message (rh/check req)) (pr-str req)))))

(deftest saying-hello
  (let [c (computer (constantly (ok)))]
    (is (= {:v 1 :host "deluxe" :user "greg" :policy rh/default-policy}
           (rh/handle c (request {:action "hello"}))))
    (is (empty? @(:ran c)) "nothing runs")))

(deftest what-it-allows-runs-right-away
  (let [c (computer (constantly (ok)))]
    (is (= {:done true} (rh/handle c (request {:action "notify" :text "Dinner!"}))))
    (is (= [["notify-send" "--app-name" "grog" "grog" "Dinner!"]] @(:ran c))))
  (testing "opening, in the user's session"
    (let [c (computer (constantly (ok)))]
      (rh/handle c (request {:action "open" :target "~/notes.txt"}))
      (is (= ["systemd-run" "--user" "--collect" "--quiet" "--" "xdg-open" "/home/greg/notes.txt"]
             (first @(:ran c))))))
  (testing "the volume, as it is then"
    (let [c (computer (constantly (ok "Volume: 0.40 [MUTED]\n")))]
      (is (= {:level 40 :muted true} (rh/handle c (request {:action "volume" :mute true}))))
      (is (str/includes? (first @(:ran c)) "wpctl set-mute @DEFAULT_AUDIO_SINK@ 1"))))
  (testing "how it's doing"
    (let [c (computer (constantly (ok "name: deluxe\nup: 1 days\n")))]
      (is (= {:status "name: deluxe\nup: 1 days"} (rh/handle c (request {:action "status"}))))))
  (testing "saying why it failed"
    (let [c (computer (constantly {:exit 1 :output "No players found\n"}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"playerctl failed: No players found"
                            (rh/handle c (request {:action "media" :media "next"})))))))

(deftest what-it-denies
  (let [c (computer (constantly (ok)) {:allow ["status"] :ask [] :deny ["lock"]})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deluxe doesn't let grog lock the screen \(its policy: "
                          (rh/handle c (request {:action "lock"}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"deluxe doesn't let grog run a command"
                          (rh/handle c (request {:action "shell" :command "ls"}))) "nor what it doesn't name")
    (is (empty? @(:ran c)))))

(deftest what-it-asks
  (testing "runs once someone at it allows it, seeing the command"
    (let [c (computer #(if (asking? %) (ok "17\nallow\n") (ok "hi\n")))]
      (is (= {:exit 0 :output "hi\n" :timed-out false :truncated false}
             (rh/handle c (request {:action "shell" :command "echo hi"}))))
      (let [[ask run] @(:ran c)]
        (is (= ["grog wants to run a command" "echo hi"] (take-last 2 ask)))
        (is (= "echo hi" run)))))
  (testing "not when they close it"
    (let [c (computer #(if (asking? %) (ok "17\n") (ok)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nobody allowed it on deluxe \(someone at it has 30 s to\)"
                            (rh/handle c (request {:action "power-off"}))))
      (is (= 1 (count @(:ran c))) "it isn't turned off")))
  (testing "nor when nobody answers in time: the notification goes away"
    (let [c (computer #(if (asking? %) {:exit nil :output "17\n" :timed-out true :truncated false} (ok)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nobody allowed it"
                            (rh/handle c (request {:action "suspend"}))))
      (is (= ["CloseNotification" "u" "17"] (take-last 3 (second @(:ran c)))))))
  (testing "nor when there's no session to ask in"
    (let [c (computer #(if (asking? %) {:exit 1 :output "Cannot connect to the bus\n"} (ok)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nobody is there to allow it on deluxe"
                            (rh/handle c (request {:action "shell" :command "ls"}))))))
  (testing "suspending: in a moment, as the user may"
    (let [c (computer #(if (asking? %) (ok "3\nallow\n") (ok)))]
      (is (= {:done true} (rh/handle c (request {:action "suspend"}))))
      (is (= ["bash" "-c" "computer-power" "suspend"] (let [[bash flag _ name verb] (second @(:ran c))]
                                                        [bash flag name verb])))
      (is (= ["grog wants to suspend the computer" "Allow it?"] (take-last 2 (first @(:ran c))))))))

(deftest unpairing
  (let [file (str (fs/path (h/temp-dir) "authorized_keys"))
        mine "ssh-ed25519 AAAAmine me@laptop"
        grogs "restrict,from=\"192.168.15.0/24\",command=\"/bin/grog hands --stdio\" ssh-ed25519 AAAAgrog grog@grognix"]
    (spit file (str mine "\n" grogs "\n"))
    (is (= {:done true} (rh/handle (assoc (computer (constantly (ok))) :authorized-keys file)
                                   (request {:action "unpair" :key "ssh-ed25519 AAAAgrog grog@grognix"}))))
    (is (= (str mine "\n") (slurp file)) "only grog's line goes")
    (is (= (str mine "\n") (rh/without-key (str mine "\n") "ssh-ed25519 AAAAgrog")))))

(deftest reaching-the-session
  (let [env {"XDG_RUNTIME_DIR" "/run/user/1000"}]
    (is (= {"XDG_RUNTIME_DIR" "/run/user/1000" "DBUS_SESSION_BUS_ADDRESS" "unix:path=/run/user/1000/bus"}
           (rh/session-env env (fn [] (throw (ex-info "not needed" {}))))))
    (is (= {"XDG_RUNTIME_DIR" "/run/user/1001" "DBUS_SESSION_BUS_ADDRESS" "unix:path=/run/user/1001/bus"}
           (rh/session-env {} (constantly "1001"))))
    (is (= "unix:abstract=/tmp/dbus" (get (rh/session-env (assoc env "DBUS_SESSION_BUS_ADDRESS" "unix:abstract=/tmp/dbus")
                                                          (constantly "1000"))
                                          "DBUS_SESSION_BUS_ADDRESS")))))

(deftest answers-to-asking
  (is (= {:id "42" :allowed true} (rh/answer-of "42\nallow\n")))
  (is (= {:id "42" :allowed true} (rh/answer-of "42\ndefault\n")) "clicking it")
  (is (= {:id "42" :allowed false} (rh/answer-of "42\n")))
  (is (= {:id nil :allowed false} (rh/answer-of ""))))

(deftest over-stdio
  (let [c (computer (constantly (ok)))]
    (is (= {:done true}
           (config/parse (with-out-str (with-in-str (str (config/generate (request {:action "lock"})) "\n")
                                         (rh/serve! c))))))
    (is (= {:error "send a JSON object"}
           (config/parse (with-out-str (with-in-str "nope\n" (rh/serve! c))))))
    (is (= {:error "send a JSON object"}
           (config/parse (with-out-str (with-in-str "" (rh/serve! c))))) "nothing at all")))
