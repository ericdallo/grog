(ns grog.integrations-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.computers :as computers]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.credentials :as credentials]
            [grog.integrations :as integrations]
            [grog.test-helpers :as h]))

(defn ^:private context []
  (assistant/new-context (-> config/defaults
                             (assoc :state-dir (h/temp-dir))
                             (assoc :integrations {:host "127.0.0.1" :port 0}))))

(deftest what-can-be-connected
  (let [ctx (context)
        config @(:config ctx)]
    (testing "the provider's sign-in, and computers, with nothing connected yet"
      (is (= [{:id "openai" :title "ChatGPT" :heading "Sign in with ChatGPT"
               :about "your ChatGPT subscription pays for the answers" :icon "key"
               :ways [{:id "device" :label "Enter a code instead"} {:id "browser" :label "Sign in in the browser instead"}]
               :multiple false :connections []}
              {:id "computer" :title "Computers" :heading "Add a computer" :about "your computers, to act on them"
               :icon "computer" :ways [{:id "script" :label "Run a command on it"}] :multiple true :connections []}]
             (integrations/summary config))))
    (testing "then what is, never its secrets"
      (credentials/save! (config/state-dir config) "openai" {:access-token "secret" :email "grog@example.com"})
      (computers/add! config {:name "deluxe" :host "192.168.15.90" :port 22 :user "greg"
                              :host-key "ssh-ed25519 AAAA" :added-at 1792427875000})
      (let [summary (integrations/summary config)]
        (is (= [["openai" "grog@example.com" "connected"]] (map (juxt :id :name :status) (:connections (first summary)))))
        (is (= [["computer:deluxe" "deluxe" "greg@192.168.15.90"]]
               (map (juxt :id :name :detail) (:connections (second summary)))))
        (is (not (str/includes? (pr-str summary) "secret")))))
    (testing "disconnecting, whichever's it is"
      (is (= {:id "openai" :disconnected true} (integrations/disconnect! ctx "openai")))
      (is (nil? (credentials/saved (config/state-dir config) "openai")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"There's no connection called \"openai\""
                            (integrations/disconnect! ctx "openai"))))))

(deftest the-model-opens-a-session-when-asked
  (let [ctx (context)
        {:keys [run parameters changes?] :as tool} (integrations/tool @(:config ctx))]
    (try
      (is (= "connect" (:name tool)))
      (is (= ["openai" "computer"] (get-in parameters [:properties :integration :enum])))
      (is (str/includes? (:description tool) "computer (your computers, to act on them)"))
      (is (changes? {}) "not in a turn that searched the web: it opens grog to the network")
      (is (= (str "Add a computer: a window is open for 10 minutes, and your screen shows its link and QR code. Tell "
                  "the user to open it on their phone or computer; don't read the link aloud.")
             (run ctx {:integration "computer"} nil)))
      (is (= "computer" (:integration (connect/view @(:connect ctx)))))
      (finally
        (connect/stop! ctx)))))
