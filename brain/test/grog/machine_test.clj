(ns grog.machine-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.hands :as hands]
            [grog.machine :as machine]
            [grog.test-helpers :as h]))

(defn ^:private context []
  (assistant/new-context (-> config/defaults
                             (assoc :state-dir (h/temp-dir))
                             (assoc-in [:hands :socket] "/run/grog-hands/hands.sock"))))

(defn ^:private run [tool ctx args turn]
  ((:run tool) ctx args turn))

(deftest volume
  (let [ctx (context)
        ran (atom [])
        answer (atom {:exit 0 :output "Volume: 0.45\n"})]
    (with-redefs [hands/ask! (fn [_ command] (swap! ran conj command) @answer)]
      (testing "tells it"
        (is (= {:level 45 :muted false} (run machine/volume-tool ctx {} {})))
        (is (= ["wpctl get-volume @DEFAULT_AUDIO_SINK@"] @ran)))
      (testing "sets it"
        (reset! ran [])
        (run machine/volume-tool ctx {:level 60} {})
        (is (= ["wpctl set-volume -l 1 @DEFAULT_AUDIO_SINK@ 60% && wpctl get-volume @DEFAULT_AUDIO_SINK@"] @ran)))
      (testing "changes it, unmuting"
        (reset! ran [])
        (run machine/volume-tool ctx {:change -10 :mute false} {})
        (is (= [(str "wpctl set-mute @DEFAULT_AUDIO_SINK@ 0 && wpctl set-volume -l 1 @DEFAULT_AUDIO_SINK@ 10%- && "
                     "wpctl get-volume @DEFAULT_AUDIO_SINK@")]
               @ran)))
      (testing "muted"
        (reset! answer {:exit 0 :output "Volume: 0.30 [MUTED]\n"})
        (is (= {:level 30 :muted true} (run machine/volume-tool ctx {:mute true} {}))))
      (testing "only numbers, in range, get to the machine"
        (reset! ran [])
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"level must be a number from 0 to 100"
                              (run machine/volume-tool ctx {:level 150} {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"level must be"
                              (run machine/volume-tool ctx {:level "50; reboot"} {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mute must be true or false"
                              (run machine/volume-tool ctx {:mute "yes"} {})))
        (is (= [] @ran)))
      (testing "when it can't"
        (reset! answer {:exit 1 :output "Error: no default sink\n"})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"couldn't be changed: Error: no default sink"
                              (run machine/volume-tool ctx {} {})))))))

(deftest volume-of-a-speaker-by-its-name
  (testing "its id found at run time, or the default one while it isn't there"
    (is (= (str "sink=$(pw-dump -N 'bluez_output.1' 2>/dev/null | grep -m 1 -o '\"id\": *[0-9]*' | grep -o '[0-9]*$'); "
                "sink=${sink:-@DEFAULT_AUDIO_SINK@}; "
                "wpctl set-volume -l 1 \"$sink\" 50% && wpctl get-volume \"$sink\"")
           (machine/volume-command {:level 50} "bluez_output.1"))))
  (testing "a name as one word, whatever it holds"
    (is (str/starts-with? (machine/volume-command {} "it's; reboot")
                          "sink=$(pw-dump -N 'it'\\''s; reboot' "))))

(deftest system-status
  (let [ran (atom [])]
    (with-redefs [hands/ask! (fn [_ command]
                               (swap! ran conj command)
                               {:exit 0 :output "up: up 2 hours\nload: 0.10 0.20 0.30\n"})]
      (is (= "up: up 2 hours\nload: 0.10 0.20 0.30" (run machine/status-tool (context) {} {})))
      (is (str/includes? (first @ran) "/proc/uptime")))))

(deftest shell-runs-right-away
  (let [ctx (context)
        ran (atom [])]
    (with-redefs [hands/ask! (fn [_ command]
                               (swap! ran conj command)
                               {:exit 0 :output "freed 2 GB\n" :timed-out false :truncated false})]
      (testing "through the hands, telling the model how it went"
        (is (= {:exit 0 :output "freed 2 GB\n"}
               (run machine/shell-tool ctx {:command "nix-collect-garbage -d"} {:id "c" :n 1})))
        (is (= ["nix-collect-garbage -d"] @ran))))
    (testing "and whether it ran too long, or printed too much"
      (with-redefs [hands/ask! (constantly {:exit 143 :output "..." :timed-out true :truncated true})]
        (is (= {:exit 143 :output "..." :timed_out true :truncated true}
               (run machine/shell-tool ctx {:command "sleep 99"} {:id "c" :n 2})))))
    (testing "a command is needed"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"which command"
                            (run machine/shell-tool ctx {:command " "} {:id "c" :n 3}))))))
