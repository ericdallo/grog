(ns grog.update-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.events :as events]
            [grog.hands :as hands]
            [grog.test-helpers :as h]
            [grog.update :as update]))

(def ^:private socket "/run/grog-hands/hands.sock")

(defn ^:private settings
  "Settings with the update service's status file (and hands, unless not
  `hands?`)."
  ([status-file] (settings status-file true))
  ([status-file hands?]
   (cond-> (assoc config/defaults :state-dir (h/temp-dir) :timezone "America/Sao_Paulo")
     status-file (assoc-in [:update :status] status-file)
     hands? (assoc-in [:hands :socket] socket))))

(defn ^:private write-status! [file status]
  (spit file (config/generate status)))

(defn ^:private with-hands
  "Runs (f ran) with the hands answering commands with (`answer` command),
  keeping them in `ran`."
  [answer f]
  (let [ran (atom [])]
    (with-redefs [hands/ask! (fn [_ command] (swap! ran conj command) (answer command))]
      (f ran))))

(defn ^:private machine
  "How the hands answer: the update service `active?` or not; starting it
  runs (`on-start`)."
  [active? on-start]
  (fn [command]
    (cond
      (str/includes? command "is-active") {:exit (if active? 0 3) :output ""}
      (str/includes? command "systemctl start") (do (on-start) {:exit 0 :output ""})
      :else {:exit 1 :output "unknown"})))

(deftest revisions
  (is (= "fbd2aad" (update/short-revision "fbd2aadd94d1c3ae9adb3aa40b1e600dbdf7fb33")))
  (is (= "fbd2aad-dirty" (update/short-revision "fbd2aadd94d1c3ae9adb3aa40b1e600dbdf7fb33-dirty")))
  (is (= "abc" (update/short-revision "abc")))
  (is (nil? (update/short-revision nil))))

(deftest what-an-update-tells-the-model
  (let [latest {:from "aaaaaaaaaa" :to "bbbbbbbbbb" :to-date 1792427875000}
        building (update/describe (assoc latest :stage "building"))]
    (is (= "There's nothing new: you're up to date. Say just that."
           (update/describe (assoc latest :stage "current"))))
    (is (= (str "There's an update, under way: you restart on your own once it's ready. If you already said "
                "you'd update, that's your whole answer; otherwise, say just that you're updating.")
           building))
    (testing "nothing that needn't be said: versions, when, how long, the sound"
      (is (not (re-find #"(?i)aaaaaaa|bbbbbbb|2026|minute|sound" building))))
    (is (= "The update failed: say so in a few words. What went wrong: Couldn't build it: oops"
           (update/describe {:stage "failed" :error "Couldn't build it: oops"})))))

(deftest whether-it-can
  (is (true? (update/available? (settings "/run/grog-update/status.json"))))
  (is (false? (update/available? (settings "/run/grog-update/status.json" false))))
  (is (false? (update/available? (settings nil))))
  (is (nil? (update/tool (settings nil)))))

(deftest starting
  (testing "through the hands, without waiting"
    (with-hands (machine false (constantly nil))
      (fn [ran]
        (is (= {:started true} (update/start! (settings "/tmp/none.json"))))
        (is (= 2 (count @ran)))
        (is (str/includes? (second @ran) "systemctl start --no-block grog-update.service")))))
  (testing "not twice: how the one under way goes"
    (let [file (str (fs/path (h/temp-dir) "status.json"))]
      (write-status! file {:stage "building" :started-at 1})
      (with-hands (machine true (constantly nil))
        (fn [ran]
          (is (= {:started false :update {:stage "building" :started-at 1}} (update/start! (settings file))))
          (is (= 1 (count @ran)))))))
  (testing "saying why the machine wouldn't"
    (with-hands (fn [command] (if (str/includes? command "is-active")
                                {:exit 3 :output ""}
                                {:exit 1 :output "Access denied\n"}))
      (fn [_]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"wouldn't start it: Access denied"
                              (update/start! (settings "/tmp/none.json"))))))))

(deftest the-tool
  (let [file (str (fs/path (h/temp-dir) "status.json"))
        ctx (assistant/new-context (settings file))
        run (:run (update/tool @(:config ctx)))
        updater (fn [status] #(future (Thread/sleep 50)
                                      (write-status! file (assoc status :started-at (+ (events/now-ms) 1)))))]
    (testing "it tells there's nothing new"
      (write-status! file {:stage "done" :started-at 1 :to "old"})
      (with-hands (machine false (updater {:stage "current" :to "bbbbbbbbbb" :to-date 1792427875000}))
        (fn [_]
          (is (= "There's nothing new: you're up to date. Say just that." (run ctx {} nil))))))
    (testing "it tells there's a new one, being built"
      (with-hands (machine false (updater {:stage "building" :from "aaaaaaaaaa" :to "bbbbbbbbbb"}))
        (fn [_]
          (is (str/starts-with? (run ctx {} nil) "There's an update, under way: you restart on your own")))))
    (testing "it doesn't wait forever"
      (with-redefs [update/known-timeout-ms 300]
        (with-hands (machine false (updater {:stage "fetching"}))
          (fn [_]
            (is (str/starts-with? (run ctx {} nil) "It's looking for an update: if there's one, you restart"))))))
    (testing "one at a time"
      (with-hands (machine true (constantly nil))
        (fn [_]
          (is (str/starts-with? (run ctx {} nil) "An update was already under way.")))))))

(deftest whoever-watches-sees-it
  (let [file (str (fs/path (h/temp-dir) "status.json"))
        ctx (assistant/new-context (settings file))
        seen (atom nil)]
    (update/watch! ctx seen)
    (is (nil? (:update (events/snapshot (:events ctx)))))
    (write-status! file {:stage "building" :to "bbb"})
    (update/watch! ctx seen)
    (is (= {:stage "building" :to "bbb"} (:update (events/snapshot (:events ctx)))))))
