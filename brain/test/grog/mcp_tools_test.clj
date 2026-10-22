(ns grog.mcp-tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.asks :as asks]
            [grog.assistant :as assistant]
            [grog.catalog :as catalog]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.connections :as connections]
            [grog.mcp-tools :as mcp-tools]
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

(def ^:private home-tools
  [{:name "HassTurnOff" :title "Turn off" :description "Turns something off."
    :inputSchema {:$schema "http://json-schema.org/draft-07/schema#" :type "object"
                  :properties {:name {:type "string"}}}
    :annotations {:destructiveHint false}}
   {:name "GetLiveContext" :description "What's on." :annotations {:readOnlyHint true :openWorldHint false}}
   {:name "Unlock" :annotations {:destructiveHint true}}
   {:name "read.mail" :annotations {:readOnlyHint true}}])

(defn ^:private connected!
  "Connects `entry` (added by the user, unless `shipped?`) with `listed`
  tools: its id."
  [ctx entry listed & [shipped?]]
  (let [config @(:config ctx)
        id (if shipped? (catalog/id-of entry) (catalog/add! config entry))]
    (connections/save! (config/state-dir config) {:id id :integration id :name "home"} {:values []})
    (servers/save-tools! config id fake-client listed)
    id))

(def ^:private home
  (-> (registry/url-entry "http://192.168.15.10:8123/api/mcp")
      (assoc :title "Home Assistant")
      (assoc-in [:_meta catalog/meta-key] {:id "home" :prefix "ha" :policy {:Unlock "deny"}})))

(defn ^:private by-name [ts]
  (into {} (map (juxt :name identity)) ts))

(deftest names
  (is (= ["ha__HassTurnOff" "ha__read_mail"] (mcp-tools/tool-names "ha" ["HassTurnOff" "read.mail"])))
  (let [long-names [(apply str (repeat 80 "a")) (str (apply str (repeat 80 "a")) "b")]
        [a b] (mcp-tools/tool-names "ha" long-names)]
    (is (every? #(<= (count %) 64) [a b]))
    (is (not= a b) "a hash of the whole name keeps them apart")
    (is (re-matches #"ha__a+_[0-9a-f]{4}" a)))
  (let [[a b] (mcp-tools/tool-names "ha" ["a.b" "a_b"])]
    (is (not= a b) "nor those the same once sanitized")
    (is (str/starts-with? a "ha__a_b_")))
  (is (= "my_server" (mcp-tools/prefix (assoc-in home [:_meta catalog/meta-key] {:id "my.server"})))))

(deftest matching-names
  (testing "the same, or ending in __<name>"
    (is (mcp-tools/matches? "HassTurnOn" "HassTurnOn"))
    (is (mcp-tools/matches? "HassTurnOn" "intent__HassTurnOn"))
    (is (mcp-tools/matches? "HassTurnOn" "device-control__intent__HassTurnOn")))
  (testing "not a part of a name"
    (is (not (mcp-tools/matches? "HassTurnOn" "XHassTurnOn")))
    (is (not (mcp-tools/matches? "HassTurnOn" "intent_HassTurnOn")))
    (is (not (mcp-tools/matches? "HassTurnOn" "HassTurnOnAll")))
    (is (not (mcp-tools/matches? "" "intent__"))))
  (let [ts [{:name "a__HassTurnOn"} {:name "HassTurnOn"} {:name "HassTurnOff"}]]
    (is (= {:name "HassTurnOn"} (mcp-tools/find-tool ts "HassTurnOn")) "the same name first")
    (is (= {:name "a__HassTurnOn"} (mcp-tools/find-tool [(first ts) (last ts)] "HassTurnOn")))
    (is (nil? (mcp-tools/find-tool ts "HassLightSet")))))

(deftest guards
  (testing "trusted: as annotated, MCP's defaults otherwise"
    (is (= {:read-only true :changes false :open-world false :destructive false}
           (mcp-tools/guards true {:annotations {:readOnlyHint true :openWorldHint false}})))
    (is (= {:read-only false :changes true :open-world true :destructive true} (mcp-tools/guards true {})))
    (is (= {:read-only false :changes true :open-world true :destructive false}
           (mcp-tools/guards true {:annotations {:destructiveHint false}}))))
  (testing "untrusted: it changes things and reads what others wrote, whatever it says"
    (is (= {:read-only true :changes true :open-world true :destructive false}
           (mcp-tools/guards false {:annotations {:readOnlyHint true :openWorldHint false}})))))

(deftest the-models-tools
  (let [ctx (context)]
    (with-redefs [catalog/shipped (constantly [home])]
      (let [config @(:config ctx)
            id (connected! ctx home home-tools true)
            ts (by-name (mcp-tools/tools config))
            off (ts "ha__HassTurnOff")]
        (testing "named, described, their schemas as they are"
          (is (= #{"ha__HassTurnOff" "ha__GetLiveContext" "ha__Unlock" "ha__read_mail"} (set (keys ts))))
          (is (= "Home Assistant: Turn off. Turns something off." (:description off)))
          (is (= {:type "object" :properties {:name {:type "string"}}} (:parameters off)) "without $schema")
          (is (= {:type "object" :properties {}} (:parameters (ts "ha__Unlock"))) "an object, when it says none")
          (is (= "Turn off" ((:detail off) {:name "x"}))))
        (testing "guards"
          (is ((:changes? off) {}))
          (is (not ((:changes? (ts "ha__GetLiveContext")) {})))
          (is (false? (:open-world? (ts "ha__GetLiveContext"))))
          (is (true? (:open-world? off))))
        (testing "the policy: its defaults, the entry's, the user's"
          (is (= [["HassTurnOff" "allow" true] ["GetLiveContext" "allow" true] ["Unlock" "deny" true]
                  ["read.mail" "allow" true]]
                 (map (juxt :name :mode :shown) (mcp-tools/tool-settings config id))))
          (is (= "ask" (:mode (mcp-tools/set-tool! config id "HassTurnOff" {:mode "ask"}))))
          (is (= "allow" (:default-mode (first (mcp-tools/tool-settings config id)))))
          (is (= "allow" (:mode (mcp-tools/set-tool! config id "HassTurnOff" {:mode nil}))) "back to its default")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mode: allow, ask or deny"
                                (mcp-tools/set-tool! config id "HassTurnOff" {:mode "maybe"})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no tool called"
                                (mcp-tools/set-tool! config id "Nope" {:mode "ask"})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no MCP server connected"
                                (mcp-tools/tool-settings config "openai"))))
        (testing "denied"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the user doesn't let Jarvis use it"
                                ((:run (ts "ha__Unlock")) ctx {} {:id "c" :n 1}))))
        (testing "allowed: called, the result's text for the model"
          (with-redefs [servers/call! (fn [_ id' tool args]
                                        (is (= [id "HassTurnOff" {:name "kitchen"}] [id' tool args]))
                                        {:content [{:type "text" :text "done"}]})]
            (is (= "done" ((:run off) ctx {:name "kitchen"} {:id "c" :n 1})))))
        (testing "failing, saying why"
          (with-redefs [servers/call! (fn [& _] (throw (ex-info "it went away" {})))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Home Assistant: it went away"
                                  ((:run off) ctx {:name "kitchen"} {:id "c" :n 1})))))
        (testing "shown or hidden, by the entry, then the user"
          (with-redefs [catalog/shipped (constantly [(assoc-in home [:_meta catalog/meta-key :show] ["GetLiveContext"])])]
            (is (= ["ha__GetLiveContext"] (map :name (mcp-tools/tools config))))
            (mcp-tools/set-tool! config id "Unlock" {:show true})
            (is (= ["ha__GetLiveContext" "ha__Unlock"] (map :name (mcp-tools/tools config))))))
        (testing "not while signed out"
          (connections/set-status! (config/state-dir config) id "signed-out")
          (is (= [] (mcp-tools/tools config))))))))

(deftest names-that-change-by-version
  (let [entry (assoc-in home [:_meta catalog/meta-key :show] ["HassTurnOff" "Unlock"])]
    (with-redefs [catalog/shipped (constantly [entry])]
      (let [ctx (context)
            config @(:config ctx)
            id (connected! ctx entry (mapv #(update % :name (partial str "intent__")) home-tools) true)]
        (testing "the entry's show and policy match prefixed names"
          (is (= ["ha__intent__HassTurnOff" "ha__intent__Unlock"] (map :name (mcp-tools/tools config))))
          (is (= [["intent__HassTurnOff" "allow" true] ["intent__GetLiveContext" "allow" false]
                  ["intent__Unlock" "deny" true] ["intent__read.mail" "allow" false]]
                 (map (juxt :name :mode :shown) (mcp-tools/tool-settings config id)))))))))

(deftest a-server-the-user-added
  (let [ctx (context)
        config @(:config ctx)
        many (vec (for [i (range 45)] {:name (str "t" i) :annotations {:readOnlyHint true :destructiveHint false}}))
        id (connected! ctx (assoc-in home [:_meta catalog/meta-key :policy] {:t0 "deny"}) many)
        ts (mcp-tools/tools config)]
    (is (= "192-168-15-10-8123-api-mcp" id))
    (testing "the first 40 shown, the rest to show"
      (is (= 40 (count ts)))
      (mcp-tools/set-tool! config id "t44" {:show true})
      (is (= 41 (count (mcp-tools/tools config)))))
    (testing "what it says of itself doesn't count"
      (is (= "allow" (:mode (first (mcp-tools/tool-settings config id)))) "its _meta policy was dropped")
      (is ((:changes? (first ts)) {}) "read-only, it says")
      (is (true? (:open-world? (first ts)))))))

(deftest reading-what-others-wrote
  (with-redefs [catalog/shipped (constantly [home])
                servers/call! (fn [_ _ tool _] {:content [{:type "text" :text (str "did " tool)}]})]
    (let [ctx (context)
          _ (connected! ctx home [{:name "read" :annotations {:readOnlyHint true}}
                                  {:name "write" :annotations {:destructiveHint false}}]
                        true)
          run (tools/runner ctx (mcp-tools/tools @(:config ctx)) {:id "c" :n 1 :searched? (constantly false)})]
      (is (= "did write" (run "ha__write" {})))
      (is (= "did read" (run "ha__read" {})) "it may read mail, say")
      (is (str/starts-with? (run "ha__write" {}) "error: nothing is changed in a turn that read what others wrote"))
      (is (= "did read" (run "ha__read" {})) "reading still works"))))

(defn ^:private session [ctx]
  (connect/view @(:connect ctx)))

(deftest asking-first
  (let [ctx (context)
        _ (connected! ctx home [{:name "Unlock" :title "Unlock the door"}])
        unlock (first (mcp-tools/tools @(:config ctx)))
        calls (atom [])
        run (fn [args n] ((:run unlock) ctx args {:id "c" :n n}))]
    (with-redefs [servers/call! (fn [_ _ tool args] (swap! calls conj [tool args]) {:content [{:type "text" :text "unlocked"}]})]
      (try
        (testing "asked: not done, the model told to ask"
          (is (= (str "not done yet: Unlock the door asks the user first. Ask them in a short yes-or-no question "
                      "whether to do it; if they say yes, call it again with the same arguments.")
                 (run {:door "front" :how {:b 1 :a 2}} 1)))
          (is (= [] @calls))
          (is (= {:integration "ask" :status "open"
                  :step {:kind "confirm" :question "Home Assistant: Unlock the door?"
                         :detail "It can change or delete things there."}}
                 (select-keys (session ctx) [:integration :status :step]))
              "on the card too, without its arguments"))
        (testing "called again in the same turn: still asks"
          (is (str/starts-with? (run {:door "front" :how {:b 1 :a 2}} 1) "not done yet")))
        (testing "with other arguments, the next turn: asks"
          (is (str/starts-with? (run {:door "back"} 2) "not done yet")))
        (testing "the same, the next turn (the user said yes): done, and the card with it"
          (is (= "unlocked" (run {:how {:a 2 :b 1} :door "front"} 2)))
          (is (= [["Unlock" {:how {:a 2 :b 1} :door "front"}]] @calls))
          (is (= "done" (:status (session ctx))))
          (is (str/starts-with? (run {:door "front" :how {:a 2 :b 1}} 3) "not done yet") "and only once"))
        (testing "too long ago: asks again"
          (with-redefs [asks/ttl-ms 0]
            (is (str/starts-with? (run {:door "side"} 4) "not done yet"))
            (is (str/starts-with? (run {:door "side"} 5) "not done yet"))))
        (finally
          (connect/stop! ctx))))))

(deftest allowed-on-grogs-screen
  (let [ctx (context)
        _ (connected! ctx home [{:name "Unlock" :title "Unlock the door"}])
        unlock (first (mcp-tools/tools @(:config ctx)))
        replied (promise)
        run (fn [n] ((:run unlock) ctx {:door "front"} {:id "c" :n n}))]
    (with-redefs [servers/call! (fn [& _] {:content [{:type "text" :text "unlocked"}]})
                  assistant/reply! (fn [_ request _ _] (deliver replied request) "ok")]
      (try
        (testing "Allow: grog answers for the user, and the call runs"
          (run 1)
          (connect/decide! ctx true)
          (is (= {:id "c" :message "Yes, go ahead (allowed on Jarvis's screen)."} (deref replied 5000 nil)))
          (is (= "unlocked" (run 1)) "allowed, even in the same turn")
          (is (= "done" (:status (session ctx)))))
        (testing "Deny: forgotten"
          (run 2)
          (connect/decide! ctx false)
          (is (= "denied" (:status (session ctx))))
          (is (str/starts-with? (run 3) "not done yet")))
        (testing "something else on the card: only the model asks"
          (connect/cancel! ctx)
          (connect/open! ctx {:id "other" :title "Other" :heading "Other" :ways [{:id "x"}]
                              :start! (fn [ctx {:keys [generation]} _] (connect/step! ctx generation {:kind "wait"}))}
                         nil)
          (is (str/starts-with? (run 3) "not done yet"))
          (is (= "other" (:integration (session ctx)))))
        (finally
          (connect/stop! ctx))))))
