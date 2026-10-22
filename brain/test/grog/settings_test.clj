(ns grog.settings-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.credentials :as credentials]
            [grog.provider :as provider]
            [grog.settings :as settings]
            [grog.test-helpers :as h]
            [grog.usage :as usage]))

(defn ^:private startup-config [state-dir & {:as changes}]
  (merge (assoc config/defaults :state-dir state-dir) changes))

(defn ^:private saved [ctx]
  (let [f (settings/file @(:config ctx))]
    (when (fs/exists? f)
      (json/parse-string (slurp f) true))))

(deftest changes-and-keeps-settings
  (let [state-dir (h/temp-dir)
        ctx (assistant/new-context (startup-config state-dir))]
    ;; A new reasoning effort is tried first (see below).
    (with-redefs [settings/try-model! (fn [_ _])]
      (testing "with no arguments, it tells them, changing nothing"
        (is (= {:name "grog" :language nil :location nil :timezone (config/timezone config/defaults)
                :model "gpt-6-luna" :reasoning_effort "low" :music_provider nil :changed []}
               (settings/change! ctx {})))
        (is (nil? (saved ctx))))

      (testing "changes take effect right away"
        (is (= {:name "Jarvis" :language nil :location {:city "Campinas" :country "BR"}
                :timezone (config/timezone config/defaults) :model "gpt-6-luna"
                :reasoning_effort "medium" :music_provider nil :changed ["name" "location" "reasoning_effort"]}
               (settings/change! ctx {:name " Jarvis " :location {:city "Campinas" :region " " :country "BR"}
                                      :reasoning_effort "medium"})))
        (is (= "Jarvis" (:name @(:config ctx))))
        (is (= "medium" (get-in @(:config ctx) [:providers :openai :reasoning-effort]))))

      (testing "and are kept, written like the settings file, for grog's user only"
        (is (= {:name "Jarvis" :location {:city "Campinas" :country "BR"}
                :providers {:openai {:reasoningEffort "medium"}}}
               (saved ctx)))
        (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (settings/file @(:config ctx)))))))

      (testing "a restart picks them up"
        (let [again (assistant/new-context (startup-config state-dir))]
          (is (= "Jarvis" (:name @(:config again))))
          (is (= ["name" "location" "reasoning_effort"] (settings/changed again)))))

      (testing "some go back to how grog started"
        (settings/change! ctx {:reset ["name" "reasoning_effort"]})
        (is (= "grog" (:name @(:config ctx))))
        (is (= "low" (get-in @(:config ctx) [:providers :openai :reasoning-effort])))
        (is (= {:location {:city "Campinas" :country "BR"}} (saved ctx))))

      (testing "all of them"
        (settings/reset-all! ctx)
        (is (= [] (settings/changed ctx)))
        (is (nil? (:location @(:config ctx))))
        (is (nil? (saved ctx)))))))

(deftest switches-the-model
  (let [ctx (assistant/new-context (startup-config (h/temp-dir)))
        tried (atom [])]
    (with-redefs [settings/try-model! (fn [_ config] (swap! tried conj (provider/model config)))]
      (testing "tried, then kept where the provider keeps the model in use"
        (is (= "gpt-6-astra" (:model (settings/change! ctx {:model " gpt-6-astra "}))))
        (is (= ["gpt-6-astra"] @tried))
        (is (= "gpt-6-astra" (get-in @(:config ctx) [:providers :openai :model])))
        (is (= {:providers {:openai {:model "gpt-6-astra"}}} (saved ctx)))
        (is (= ["model"] (settings/changed ctx))))
      (testing "a new reasoning effort is tried too"
        (settings/change! ctx {:reasoning_effort "high"})
        (is (= ["gpt-6-astra" "gpt-6-astra"] @tried)))
      (testing "other settings aren't, nor going back"
        (settings/change! ctx {:name "Jarvis"})
        (settings/change! ctx {:reset ["model" "reasoning_effort"]})
        (is (= 2 (count @tried)))
        (is (= "gpt-6-luna" (provider/model @(:config ctx))))))))

(deftest switches-the-subscriptions-model-when-paying-with-it
  (let [state-dir (h/temp-dir)
        _ (credentials/save! state-dir "openai" {:type "chatgpt" :access-token "t"})
        ctx (assistant/new-context (startup-config state-dir))]
    (with-redefs [settings/try-model! (fn [_ _])]
      (settings/change! ctx {:model "gpt-6-astra"})
      (is (= "gpt-6-astra" (get-in @(:config ctx) [:providers :openai :subscription :model])))
      (is (= "gpt-6-luna" (get-in @(:config ctx) [:providers :openai :model])))
      (is (= {:providers {:openai {:subscription {:model "gpt-6-astra"}}}} (saved ctx))))))

(deftest keeps-track-of-the-subscriptions-model-after-signing-out
  (let [state-dir (h/temp-dir)
        _ (credentials/save! state-dir "openai" {:type "chatgpt" :access-token "t"})
        ctx (assistant/new-context (startup-config state-dir))
        subscription-model #(get-in @(:config %) [:providers :openai :subscription :model])]
    (with-redefs [settings/try-model! (fn [_ _])]
      (settings/change! ctx {:model "gpt-6-astra"})
      (credentials/remove! state-dir "openai")
      (testing "it's still listed as changed"
        (is (= ["model"] (settings/changed ctx))))
      (testing "a restart keeps it"
        (is (= "gpt-6-astra" (subscription-model (assistant/new-context (startup-config state-dir))))))
      (testing "a new model goes to the API's, now in use"
        (settings/change! ctx {:model "gpt-6-sol"})
        (is (= {:providers {:openai {:model "gpt-6-sol" :subscription {:model "gpt-6-astra"}}}} (saved ctx))))
      (testing "and resetting the model resets both"
        (settings/change! ctx {:reset ["model"]})
        (is (= "gpt-6-luna" (subscription-model ctx)))
        (is (= "gpt-6-luna" (get-in @(:config ctx) [:providers :openai :model])))
        (is (= [] (settings/changed ctx)))
        (is (nil? (saved ctx)))))))

(deftest tries-a-new-model-the-way-turns-ask
  (let [ctx (assistant/new-context (startup-config (h/temp-dir) :location {:city "Campinas" :country "BR"}))
        asked (atom nil)]
    (with-redefs [provider/stream-reply! (fn [config request _]
                                           (reset! asked [(provider/model config) request
                                                          (Thread/holdsLock (:lock ctx))])
                                           {:text "OK."
                                            :usage {:auth "api-key" :model "gpt-6-astra" :input-tokens 10
                                                    :cached-tokens 0 :output-tokens 1 :web-searches 0 :usd 0.25}})]
      (settings/change! ctx {:model "gpt-6-astra"})
      (let [[model request locked?] @asked]
        (is (= "gpt-6-astra" model))
        (is (= {:city "Campinas" :country "BR"} (:location request)))
        (is (= (config/timezone config/defaults) (:timezone request)))
        (is (false? locked?) "without holding up the turns' usage and memory"))
      (is (= "gpt-6-astra" (provider/model @(:config ctx))))
      (testing "what it cost counts"
        (is (= [1 0.25] ((juxt :turns :usd) (get-in (usage/summary ctx) [:today]))))))))

(deftest keeps-no-model-that-doesnt-answer
  (let [ctx (assistant/new-context (startup-config (h/temp-dir)))]
    (testing "an error, with the models on offer"
      (with-redefs [provider/stream-reply! (fn [& _] (throw (ex-info "The OpenAI API returned 400: unknown model" {})))
                    provider/models (constantly [{:id "gpt-6-luna" :description "Fast." :current true}
                                                 {:id "gpt-6-astra" :description "Deep."}])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"^gpt-7 didn't work, so nothing changed: The OpenAI API returned 400: unknown model\. Models on offer: gpt-6-luna, gpt-6-astra$"
                              (settings/change! ctx {:model "gpt-7"})))))
    (testing "no answer, from a provider that doesn't list its models"
      (with-redefs [provider/stream-reply! (constantly {:text " "})
                    provider/models (fn [_] (throw (ex-info "unsupported" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^gpt-7 didn't work, so nothing changed: it gave no answer$"
                              (settings/change! ctx {:model "gpt-7"})))))
    (testing "nothing changed"
      (is (= "gpt-6-luna" (provider/model @(:config ctx))))
      (is (nil? (saved ctx))))))

(deftest reading-them-lists-the-models-on-offer
  (let [ctx (assistant/new-context (startup-config (h/temp-dir)))]
    (with-redefs [provider/models (constantly [{:id "gpt-6-luna" :description "Fast." :current true}])]
      (is (= [{:id "gpt-6-luna" :description "Fast."}] (:models (settings/change! ctx {})))))
    (testing "unless the provider can't tell"
      (with-redefs [provider/models (fn [_] (throw (java.io.IOException. "offline")))]
        (is (not (contains? (settings/change! ctx {}) :models)))))))

(deftest a-location-replaces-the-configured-one
  (let [state-dir (h/temp-dir)
        configured {:city "São Paulo" :region "SP" :country "BR"}
        ctx (assistant/new-context (startup-config state-dir :location configured))]
    (settings/change! ctx {:location {:city "Lisboa" :country "PT"}})
    (is (= {:city "Lisboa" :country "PT"} (:location @(:config ctx))))
    (is (= {:city "Lisboa" :country "PT"}
           (:location @(:config (assistant/new-context (startup-config state-dir :location configured))))))))

(deftest refuses-what-grog-wouldnt-start-with
  (let [ctx (assistant/new-context (startup-config (h/temp-dir)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timezone: expected a time zone"
                          (settings/change! ctx {:name "Jarvis" :timezone "Mars/Phobos"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name: expected a name"
                          (settings/change! ctx {:name "  "})))
    (testing "and changes nothing"
      (is (= "grog" (:name @(:config ctx))))
      (is (nil? (saved ctx))))
    (testing "blank text clears a setting"
      (settings/change! ctx {:language "English"})
      (settings/change! ctx {:language ""})
      (is (nil? (:language @(:config ctx)))))))

(deftest keeps-what-was-changed-live
  (let [ctx (assistant/new-context (startup-config (h/temp-dir)))]
    ;; Like from the nREPL.
    (swap! (:config ctx) assoc :max-history-messages 4)
    (settings/change! ctx {:name "Jarvis"})
    (is (= 4 (:max-history-messages @(:config ctx))))))

(defn ^:private start-logging
  "A new context over the settings saved in `state-dir`, and what it logged."
  [state-dir]
  (let [ctx (volatile! nil)
        log (with-out-str (vreset! ctx (assistant/new-context (startup-config state-dir))))]
    [@ctx log]))

(deftest ignores-saved-settings-that-dont-make-sense
  (testing "invalid ones, logging which settings but not their values"
    (let [state-dir (h/temp-dir)]
      (spit (str (fs/path state-dir "settings.json")) "{\"name\": \"Jarvis\", \"timezone\": \"Mars/Phobos\"}")
      (let [[ctx log] (start-logging state-dir)]
        (is (= "grog" (:name @(:config ctx))))
        (is (str/includes? log "invalid: timezone"))
        (is (not (str/includes? log "Mars"))))))
  (testing "settings the tool doesn't change (anymore)"
    (let [state-dir (h/temp-dir)]
      (spit (str (fs/path state-dir "settings.json"))
            "{\"name\": \"Jarvis\", \"server\": {\"port\": 1}, \"instructions\": \"Rhyme.\"}")
      (let [ctx (assistant/new-context (startup-config state-dir))]
        (is (= "Jarvis" (:name @(:config ctx))))
        (is (= 8484 (get-in @(:config ctx) [:server :port])))
        (is (nil? (:instructions @(:config ctx)))))))
  (testing "a file that isn't JSON"
    (let [state-dir (h/temp-dir)]
      (spit (str (fs/path state-dir "settings.json")) "{oops")
      (let [[ctx log] (start-logging state-dir)]
        (is (= "grog" (:name @(:config ctx))))
        (is (str/includes? log "ignoring the settings changed earlier"))))))

(deftest the-tool
  (let [{:keys [changes?]} settings/tool]
    (is (false? (changes? {})))
    (is (true? (changes? {:name "Jarvis"})))
    (is (true? (changes? {:language nil})))
    (is (true? (changes? {:model "gpt-6-astra"})))
    (is (true? (changes? {:reset ["name"]})))
    (testing "how to answer is the memory's business"
      (is (false? (changes? {:instructions "Be brief."}))))))
