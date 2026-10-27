(ns grog.tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.actions :as actions]
            [grog.capabilities :as capabilities]
            [grog.computers :as computers]
            [grog.config :as config]
            [grog.mcp-tools :as mcp-tools]
            [grog.tools :as tools]))

(def ^:private echo
  {:name "echo"
   :description "Says it back."
   :parameters {:type "object"}
   :run (fn [_ args turn]
          (cond
            (:fail args) (throw (ex-info "it can't" {}))
            (:bug args) (throw (IllegalStateException. (str "it heard " (:bug args))))
            (:turn args) (select-keys turn [:id :n])
            :else args))})

(deftest definitions
  (is (= [{:name "echo" :description "Says it back." :parameters {:type "object"}}]
         (tools/definitions [echo]))))

(deftest offers-the-machine-only-with-hands
  (let [names #(mapv :name (tools/available %))
        hands (assoc-in config/defaults [:hands :socket] "/run/grog-hands/hands.sock")]
    (with-redefs [actions/service? (constantly false)]
      (is (= ["settings" "memory" "microphone" "timers" "weather" "connect"] (names config/defaults)))
      (is (= ["settings" "memory" "microphone" "timers" "weather" "volume" "system_status" "shell" "power" "connect"]
             (names hands))))
    (testing "as a service, it can restart itself"
      (with-redefs [actions/service? (constantly true)]
        (is (= ["settings" "memory" "microphone" "timers" "weather" "power" "connect"] (names config/defaults)))))
    (testing "with the update service, and hands to start it, it can update itself"
      (with-redefs [actions/service? (constantly false)]
        (is (= ["settings" "memory" "microphone" "timers" "weather" "volume" "system_status" "shell" "power" "update"
                "connect"]
               (names (assoc-in hands [:update :status] "/run/grog-update/status.json"))))
        (is (= ["settings" "memory" "microphone" "timers" "weather" "connect"]
               (names (assoc-in config/defaults [:update :status] "/run/grog-update/status.json"))))))))

(deftest offers-capabilities-before-mcp-tools
  (with-redefs [actions/service? (constantly false)
                capabilities/tools (constantly [{:name "lights"}])
                mcp-tools/tools (constantly [{:name "ha__GetLiveContext"}])]
    (is (= ["settings" "memory" "microphone" "timers" "weather" "lights" "ha__GetLiveContext" "connect"]
           (mapv :name (tools/available config/defaults))))))

(deftest runs-tools-for-the-model
  (let [run (tools/runner {} [echo] {:id "c" :n 3})]
    (testing "results go back as JSON, or as they are when text"
      (is (= "{\"say\":\"oi\"}" (run "echo" {:say "oi"}))))
    (testing "tools know the turn"
      (is (= "{\"id\":\"c\",\"n\":3}" (run "echo" {:turn true}))))
    (testing "it tells the model what went wrong, rather than failing the turn"
      (is (= "error: there's no tool called nope" (run "nope" {})))
      (is (= "error: it can't" (run "echo" {:fail true}))))
    (testing "a bug is logged as where it happened, without its message"
      (let [log (with-out-str (is (= "error: IllegalStateException" (run "echo" {:bug "a secret"}))))]
        (is (str/includes? log "the echo tool failed: java.lang.IllegalStateException"))
        (is (not (str/includes? log "secret")))))
    (testing "as many calls as the model makes in a turn"
      (is (= (repeat 3 "{\"say\":\"oi\"}") (repeatedly 3 #(run "echo" {:say "oi"})))))))

(deftest tells-what-a-tool-is-used-with
  (let [told (atom [])
        tool (assoc echo :detail (fn [{:keys [say bug]}] (if bug (throw (Exception. "oops")) say)))
        run (tools/runner {} [tool] {:detail! #(swap! told conj [%1 %2])})]
    (run "echo" {:say "oi"})
    (run "echo" {})
    (run "echo" {:bug true :say "x"})
    (is (= [["echo" "oi"]] @told) "nothing when there's nothing to say, or it fails"))
  (testing "on one line, and short"
    (is (= "a b" (tools/short-detail "  a\n  b ")))
    (is (nil? (tools/short-detail " ")))
    (is (= 120 (count (tools/short-detail (apply str (repeat 300 "x"))))))))

(deftest what-each-tool-is-used-with
  (let [detail (fn [tool-name args]
                 (let [tool (->> (assoc-in config/defaults [:hands :socket] "/run/grog-hands/hands.sock")
                                 tools/available
                                 (some #(when (= tool-name (:name %)) %)))]
                   ((:detail tool) args)))]
    (with-redefs [actions/service? (constantly true)
                  computers/all (constantly [{:name "deluxe"}])]
      (is (= "40%" (detail "volume" {:level 40})))
      (is (= "-10 mute" (detail "volume" {:change -10 :mute true})))
      (is (= "uptime" (detail "shell" {:command "uptime"})))
      (is (= "the trash goes out on Tuesdays" (detail "memory" {:remember "the trash goes out on Tuesdays"})))
      (is (= "forget: x" (detail "memory" {:forget "x"})))
      (is (= "name Friday, location" (detail "settings" {:name "Friday" :location {:city "Recife"}})))
      (is (= "off" (detail "microphone" {:on false})))
      (is (= "restart" (detail "power" {:action "restart"})))
      (is (= "deluxe: shell uptime" (detail "computer" {:computer "deluxe" :action "shell" :command "uptime"})))
      (is (= "deluxe: volume 30%" (detail "computer" {:computer "deluxe" :action "volume" :level 30})))
      (is (= "deluxe: lock" (detail "computer" {:computer "deluxe" :action "lock"})))
      (is (= "computer" (detail "connect" {:integration "computer"}))))))
