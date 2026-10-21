(ns grog.tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.actions :as actions]
            [grog.config :as config]
            [grog.tools :as tools]))

(def ^:private echo
  {:name "echo"
   :description "Says it back."
   :parameters {:type "object"}
   :changes? (fn [args] (contains? args :change))
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
      (is (= ["settings" "memory" "microphone"] (names config/defaults)))
      (is (= ["settings" "memory" "microphone" "volume" "system_status" "shell" "power"] (names hands))))
    (testing "as a service, it can restart itself"
      (with-redefs [actions/service? (constantly true)]
        (is (= ["settings" "memory" "microphone" "power"] (names config/defaults)))))))

(deftest runs-tools-for-the-model
  (let [searched? (atom false)
        run (tools/runner {} [echo] {:id "c" :n 3 :searched? #(deref searched?)})]
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
    (testing "after a web search, it doesn't change anything, but still reads"
      (reset! searched? true)
      (is (str/starts-with? (run "echo" {:change 1}) "error: nothing is changed in a turn that searched the web"))
      (is (= "{\"say\":\"oi\"}" (run "echo" {:say "oi"}))))))
