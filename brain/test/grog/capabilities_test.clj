(ns grog.capabilities-test
  (:require [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.capabilities :as capabilities]
            [grog.catalog :as catalog]
            [grog.config :as config]
            [grog.connections :as connections]
            [grog.registry :as registry]
            [grog.servers :as servers]
            [grog.test-helpers :as h]
            [grog.tools :as tools]))

(defn ^:private context []
  (assistant/new-context (-> config/defaults
                             (assoc :name "Jarvis" :state-dir (h/temp-dir))
                             (update :integrations merge {:host "127.0.0.1" :port 0}))))

(def ^:private fake-client
  {:state (atom {:server-info {:name "home" :version "1"}})})

(def ^:private lights
  {:on {:tool "HassTurnOn" :args {:name "{name}" :area "{area}" :domain ["light"]}}
   :off {:tool "HassTurnOff" :args {:name "{name}" :area "{area}" :domain ["light"]}}
   :set {:tool "HassLightSet" :args {:name "{name}" :area "{area}" :brightness "{brightness}"
                                     :color "{color}" :temperature "{temperature}"}}
   :state {:tool "GetLiveContext" :args {:name "{name}" :area "{area}" :domain "light"}}})

(defn ^:private entry [id title capabilities]
  (-> (registry/url-entry (str "http://192.168.15.10:8123/" id))
      (assoc :title title)
      (assoc-in [:_meta catalog/meta-key] {:id id :capabilities capabilities})))

(def ^:private home (entry "home" "Home Assistant" {:lights lights}))

(defn ^:private server-tools [& names]
  (mapv (fn [n] {:name n :inputSchema {:type "object"}}) names))

(def ^:private all-tools
  (server-tools "HassTurnOn" "HassTurnOff" "HassLightSet" "GetLiveContext" "HassCancelAllTimers"))

(defn ^:private connect! [ctx id listed]
  (let [config @(:config ctx)]
    (connections/save! (config/state-dir config) {:id id :integration id :name "home"} {:values []})
    (servers/save-tools! config id fake-client listed)))

(defn ^:private recording
  "A fake servers/call!, keeping its calls in `calls`, answering `result`."
  [calls result]
  (fn [_ id tool args]
    (swap! calls conj [id tool args])
    result))

(deftest filling-templates
  (is (= {:name "lamp" :domain ["light"]} (capabilities/fill {:name "{name}" :area "{area}" :domain ["light"]}
                                                             {:name "lamp" :area " "})))
  (is (= {:brightness 40} (capabilities/fill {:brightness "{brightness}" :color "{color}"} {:brightness 40 :color nil})))
  (is (= {:x "{not a param}" :y "a {name}"} (capabilities/fill {:x "{not a param}" :y "a {name}"} {:name "lamp"}))))

(deftest offered
  (with-redefs [catalog/shipped (constantly [home])]
    (testing "not until connected"
      (is (= [] (capabilities/tools @(:config (context))))))
    (testing "connected, with the tools: lights"
      (let [ctx (context)]
        (connect! ctx "home" all-tools)
        (is (= ["lights"] (map :name (capabilities/tools @(:config ctx)))))
        (testing "not while signed out"
          (connections/set-status! (config/state-dir @(:config ctx)) "home" "signed-out")
          (is (= [] (capabilities/tools @(:config ctx)))))))
    (testing "not without off"
      (let [ctx (context)]
        (connect! ctx "home" (server-tools "HassTurnOn" "GetLiveContext"))
        (is (= [] (capabilities/tools @(:config ctx))))))
    (testing "nor with no tools kept"
      (let [ctx (context)
            config @(:config ctx)]
        (connections/save! (config/state-dir config) {:id "home" :integration "home" :name "home"} {:values []})
        (is (= [] (capabilities/tools config))))))
  (testing "the first in catalog order"
    (with-redefs [catalog/shipped (constantly [(entry "b-home" "B" {:lights lights}) home])]
      (let [ctx (context)]
        (connect! ctx "home" all-tools)
        (connect! ctx "b-home" all-tools)
        (is (re-find #"through B\." (:description (first (capabilities/tools @(:config ctx))))))))))

(deftest the-schema
  (with-redefs [catalog/shipped (constantly [home])]
    (let [ctx (context)
          _ (connect! ctx "home" all-tools)
          {:keys [description parameters detail]} (first (capabilities/tools @(:config ctx)))]
      (is (re-find #"through Home Assistant\. For other devices, use Home Assistant's own tools\." description))
      (is (= ["on" "off" "set" "state"] (get-in parameters ["properties" "action" "enum"])))
      (is (= #{"action" "name" "area" "brightness" "color" "temperature"} (set (keys (parameters "properties")))))
      (is (= {"type" "integer" "minimum" 0 "maximum" 100} (dissoc (get-in parameters ["properties" "brightness"]) "description")))
      (is (= ["action"] (parameters "required")))
      (is (false? (parameters "additionalProperties")))
      (is (= "off, living room" (detail {:action "off" :area "living room"})))
      (is (= "set, lamp, 40%, 2700 K" (detail {:action "set" :name "lamp" :brightness 40 :temperature 2700})))))
  (testing "only what the entry maps"
    (with-redefs [catalog/shipped (constantly [(entry "home" "Home Assistant" {:lights (select-keys lights [:on :off])})])]
      (let [ctx (context)
            _ (connect! ctx "home" all-tools)
            {:keys [parameters run]} (first (capabilities/tools @(:config ctx)))]
        (is (= ["on" "off"] (get-in parameters ["properties" "action" "enum"])))
        (is (= #{"action" "name" "area"} (set (keys (parameters "properties")))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lights can't set through Home Assistant"
                              (run ctx {:action "set" :brightness 10} {:id "c" :n 1})))))))

(deftest calling
  (doseq [prefix ["" "intent__" "device-control__intent__"]]
    (testing (str "server tools named " (pr-str (str prefix "HassTurnOn")))
      (with-redefs [catalog/shipped (constantly [home])]
        (let [ctx (context)
              _ (connect! ctx "home" (mapv #(update % :name (partial str prefix)) all-tools))
              run (:run (first (capabilities/tools @(:config ctx))))
              calls (atom [])]
          (with-redefs [servers/call! (recording calls {:content [{:type "text" :text "done"}]})]
            (is (= "done" (run ctx {:action "on" :name "lamp"} {:id "c" :n 1})))
            (is (= "done" (run ctx {:action "off" :area "kitchen" :name ""} {:id "c" :n 1})))
            (is (= "done" (run ctx {:action "set" :name "lamp" :brightness 40 :color nil :temperature 2700}
                               {:id "c" :n 1})))
            (is (= "done" (run ctx {:action "state" :area "kitchen"} {:id "c" :n 1}))))
          (is (= [["home" (str prefix "HassTurnOn") {:name "lamp" :domain ["light"]}]
                  ["home" (str prefix "HassTurnOff") {:area "kitchen" :domain ["light"]}]
                  ["home" (str prefix "HassLightSet") {:name "lamp" :brightness 40 :temperature 2700}]
                  ["home" (str prefix "GetLiveContext") {:area "kitchen" :domain "light"}]]
                 @calls))
          (is (integer? (get-in @calls [2 2 :brightness]))))))))

(deftest failing
  (with-redefs [catalog/shipped (constantly [home])]
    (let [ctx (context)
          _ (connect! ctx "home" all-tools)
          run (:run (first (capabilities/tools @(:config ctx))))]
      (testing "an error result, as text"
        (with-redefs [servers/call! (constantly {:isError true :content [{:type "text" :text "no such light"}]})]
          (is (= "error: no such light" (run ctx {:action "on" :name "moon"} {:id "c" :n 1})))))
      (testing "the server failing, saying why"
        (with-redefs [servers/call! (fn [& _] (throw (ex-info "it went away" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Home Assistant: it went away"
                                (run ctx {:action "on"} {:id "c" :n 1})))))
      (testing "an action it doesn't have"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lights can't dance through Home Assistant"
                              (run ctx {:action "dance"} {:id "c" :n 1})))))))

(deftest among-grogs-tools
  (with-redefs [catalog/shipped (constantly [home])]
    (let [ctx (context)
          _ (connect! ctx "home" all-tools)
          names (mapv :name (tools/available @(:config ctx)))]
      (is (some #{"lights"} names))
      (is (< (.indexOf names "lights") (.indexOf names "home__HassTurnOn")) "before the MCP tools")
      (is (= "connect" (peek names))))))
