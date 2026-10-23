(ns grog.wifi-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.actions :as actions]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.hands :as hands]
            [grog.test-helpers :as h]
            [grog.wifi :as wifi]))

;; What wifi.sh's status said on a laptop with a USB Wi-Fi adapter too, both
;; on the same network (a hidden one aside, and a name with a colon).
(def ^:private seen
  (str "radio:enabled\n"
       "--devices\n"
       "wlp0s20f0u2:wifi:connected:GREGSON-2G USB\n"
       "wlo1:wifi:connected:GREGSON-2G\n"
       "lo:loopback:connected (externally):lo\n"
       "p2p-dev-wlo1:wifi-p2p:disconnected:\n"
       "wlan9:wifi:unmanaged:\n"
       "--networks\n"
       " :GREGSON-2G:100:WPA2:wlp0s20f0u2\n"
       " :GREGSON-5G:100:WPA2:wlp0s20f0u2\n"
       " :JOAO1808:90:WPA2:wlp0s20f0u2\n"
       "*:GREGSON-2G:84:WPA2:wlp0s20f0u2\n"
       " :#CLARO-WIFI:67::wlp0s20f0u2\n"
       " :Office\\: 2nd floor:40:WPA2 802.1X:wlp0s20f0u2\n"
       " ::64:WPA2:wlp0s20f0u2\n"
       "\n"
       " :JOAO1808:100:WPA2:wlo1\n"
       "*:GREGSON-2G:83:WPA2:wlo1\n"
       "--saved\n"
       "GREGSON-2G\n"
       "GREGSON-2G\n"
       "GREGSON-5G\n"
       "Hilton Honors\n"))

(deftest made-sense-of
  (testing "nmcli's terse fields, unescaped"
    (is (= ["*" "Office: 2nd floor" "40" "a\\b" ""] (wifi/fields "*:Office\\: 2nd floor:40:a\\\\b:"))))
  (let [{:keys [radio devices networks]} (wifi/status seen)]
    (is (= "on" radio))
    (is (= [{:device "wlp0s20f0u2" :state "connected" :connection "GREGSON-2G USB"}
            {:device "wlo1" :state "connected" :connection "GREGSON-2G"}]
           devices)
        "the Wi-Fi devices NetworkManager manages")
    (testing "each network once, its best signal, the one it's on first"
      (is (= [{:ssid "GREGSON-2G" :signal 100 :security "password" :device "wlp0s20f0u2" :active true :saved true}
              {:ssid "GREGSON-5G" :signal 100 :security "password" :device "wlp0s20f0u2" :active false :saved true}
              {:ssid "JOAO1808" :signal 100 :security "password" :device "wlo1" :active false :saved false}
              {:ssid "#CLARO-WIFI" :signal 67 :security "open" :device "wlp0s20f0u2" :active false :saved false}
              {:ssid "Office: 2nd floor" :signal 40 :security "enterprise" :device "wlp0s20f0u2" :active false
               :saved false}]
             networks))))
  (testing "Wi-Fi off, nothing seen"
    (is (= {:radio "off" :devices [] :networks []} (wifi/status "radio:disabled\n--devices\n--networks\n--saved\n")))))

(defn ^:private context []
  (assistant/new-context (-> config/defaults
                             (assoc :state-dir (h/temp-dir))
                             (assoc-in [:hands :socket] "/run/grog-hands/hands.sock"))))

(defn ^:private what
  "What wifi.sh was asked by `command`: its arguments."
  [command]
  (let [[_ args] (re-find #"^set -- (.*)\n" command)]
    (mapv #(str/replace % #"^'|'$" "") (re-seq #"'(?:[^']|'\\'')*'" args))))

(defn ^:private with-hands
  "Runs (f ran) with the hands answering wifi.sh as `answer` (a function of
  its arguments), what they were asked in `ran`."
  [answer f]
  (let [ran (atom [])]
    (with-redefs [hands/ask! (fn [_ command _]
                               (is (str/includes? command "wifi.sh: status, connect or on"))
                               (swap! ran conj (what command))
                               (answer (what command)))]
      (f ran))))

(defn ^:private ok [output]
  {:exit 0 :output output})

(deftest tells-and-joins
  (testing "tells the Wi-Fi, looking again when nmcli thinks it's time"
    (with-hands (constantly (ok seen))
      (fn [ran]
        (let [answer (actions/perform! (context) "wifi" {})]
          (is (= "on" (:radio answer)))
          (is (= "GREGSON-2G" (:ssid (first (:networks answer)))))
          (is (= [["status" "auto"]] @ran))))))
  (testing "or now"
    (with-hands (constantly (ok seen))
      (fn [ran]
        (actions/perform! (context) "wifi" {:rescan true})
        (is (= [["status" "yes"]] @ran)))))
  (testing "joins one on the device seeing it best, with its password, never said anywhere"
    (with-hands #(ok (if (= "connect" (first %)) "Device 'wlo1' successfully activated with 'x'." seen))
      (fn [ran]
        (let [log (with-out-str
                    (is (= "JOAO1808" (:joined (actions/perform! (context) "wifi" {:connect "JOAO1808"
                                                                                   :password "s3cret: 'quoted'"})))))]
          (is (= [["status" "no"] ["connect" "JOAO1808" "wlo1" "s3cret: '\\''quoted'\\''"] ["status" "no"]] @ran))
          (is (not (str/includes? log "s3cret")))))))
  (testing "a saved one, or an open one, without"
    (doseq [ssid ["GREGSON-5G" "#CLARO-WIFI"]]
      (with-hands (constantly (ok seen))
        (fn [ran]
          (actions/perform! (context) "wifi" {:connect ssid})
          (is (= ["connect" ssid "wlp0s20f0u2"] (second @ran)))))))
  (testing "nothing to do on the one it's on"
    (with-hands (constantly (ok seen))
      (fn [ran]
        (actions/perform! (context) "wifi" {:connect "GREGSON-2G"})
        (is (= [["status" "no"] ["status" "no"]] @ran))))))

(deftest says-why-it-can-not
  (let [refused (fn [output] #(if (= "connect" (first %)) {:exit 4 :output output} (ok seen)))]
    (doseq [[args answer message] [[{:connect "JOAO1808"} (refused "") #"needs its password"]
                                   [{:connect "Nope"} (refused "") #"isn't in sight"]
                                   [{:connect "Office: 2nd floor"} (refused "") #"wants more than a password"]
                                   [{:connect "JOAO1808" :password "wrong"}
                                    (refused (str "Error: Connection activation failed: Secrets were required, "
                                                  "but not provided.\n"))
                                    #"^the password didn't work$"]
                                   [{:connect "JOAO1808" :password "pass"}
                                    (refused "Error: Connection activation failed: 802.1X supplicant took too long to authenticate.\n")
                                    #"^Connection activation failed: 802.1X supplicant took too long to authenticate$"]
                                   [{} (constantly {:exit 1 :output "Error: NetworkManager is not running.\n"})
                                    #"^NetworkManager isn't running here$"]
                                   [{:connect "x" :password "a\nb"} (refused "") #"say what to do"]
                                   [{:password "alone"} (refused "") #"say what to do"]
                                   [{:connect (apply str (repeat 33 "x"))} (refused "") #"say what to do"]
                                   [{:on false} (refused "") #"say what to do"]]]
      (with-hands answer
        (fn [_]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo message (actions/perform! (context) "wifi" args))
              (pr-str args))))))
  (testing "with hands only"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no hands"
                          (actions/perform! (assistant/new-context (assoc config/defaults :state-dir (h/temp-dir)))
                                            "wifi" {})))))
