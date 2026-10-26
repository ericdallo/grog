(ns grog.config-test
  (:require [grog.config :as config]
            [clojure.test :refer [deftest is]]))

(deftest camel->kebab
  (is (= "max-history-messages" (config/camel->kebab "maxHistoryMessages")))
  (is (= "base-url" (config/camel->kebab "baseURL")))
  (is (= "openai" (config/camel->kebab "openai"))))

(deftest json-keys
  (is (= {:conversation-id "c" :heard {:text "oi"}} (config/parse "{\"conversationId\": \"c\", \"heard\": {\"text\": \"oi\"}}")))
  (is (= "{\"redirectUri\":\"u\",\"models\":{\"gpt-6-luna\":{\"usedPercent\":1}},\"days\":{\"2026-10-16\":2}}"
         (config/generate {:redirect-uri "u" :models {"gpt-6-luna" {:used-percent 1}} :days {"2026-10-16" 2}}))
      "string keys are data: they stay as they are")
  (is (= "{\n  \"maxTokens\" : 1\n}" (config/generate {:max-tokens 1} {:pretty true}))))

(deftest load-config-merges-file-over-defaults
  (let [file (java.io.File/createTempFile "grog" ".json")]
    (try
      (spit file "{\"name\": \"Friday\",
                   \"maxHistoryMessages\": 6,
                   \"providers\": {\"openai\": {\"model\": \"other\", \"reasoningEffort\": null,
                                                \"subscription\": {\"model\": \"sub\"}}}}")
      (let [cfg (config/load-config (str file))]
        (is (= "Friday" (:name cfg)))
        (is (= 6 (:max-history-messages cfg)))
        (is (= "other" (get-in cfg [:providers :openai :model])))
        (is (nil? (get-in cfg [:providers :openai :reasoning-effort])))
        (is (= "sub" (get-in cfg [:providers :openai :subscription :model])))
        (is (= "https://chatgpt.com/backend-api/codex" (get-in cfg [:providers :openai :subscription :base-url]))
            "nested defaults are kept")
        (is (= "https://api.openai.com" (get-in cfg [:providers :openai :base-url])))
        (is (= (:server config/defaults) (:server cfg))))
      (finally (.delete file)))))

(deftest no-file-means-defaults
  (is (= config/defaults (config/load-config nil))))

(deftest bad-files
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Couldn't read the settings file /nope.json"
                        (config/load-config "/nope.json")))
  (let [file (java.io.File/createTempFile "grog" ".json")]
    (try
      (spit file "{\"name\": ")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"isn't valid JSON"
                            (config/load-config (str file))))
      (spit file "[1]")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must hold a JSON object"
                            (config/load-config (str file))))
      (finally (.delete file)))))

(deftest timezone
  (is (= "America/Sao_Paulo" (config/timezone (assoc config/defaults :timezone "America/Sao_Paulo"))))
  (is (= (str (java.time.ZoneId/systemDefault)) (config/timezone config/defaults))
      "the system's by default"))

(deftest problems
  (is (= [] (config/problems config/defaults)))
  (is (= ["unknown settings: maxHistory"
          "name: expected a name, got \"\""
          "timezone: expected a time zone, like \"America/Sao_Paulo\", or null, got \"Mars/Olympus\""
          "systemPromptFile: expected a readable file or null, got \"/nope.md\""
          "systemPrompt, systemPromptFile: set only one of them"
          "maxHistoryMessages: expected a positive integer, got \"20\""
          "memory.learn: expected true or false, got \"yes\""
          "server.port: expected a port number, got 99999"
          "nrepl.enable: expected true or false, got nothing"
          "nrepl.host: expected an address, like \"127.0.0.1\", got \"\""
          "skin.screenOffMinutes: expected a number of minutes, or null to keep the screen on, got 0"]
         (config/problems (-> config/defaults
                              (assoc :max-history 3
                                     :name ""
                                     :timezone "Mars/Olympus"
                                     :system-prompt "Hi."
                                     :system-prompt-file "/nope.md"
                                     :max-history-messages "20")
                              (assoc-in [:memory :learn] "yes")
                              (assoc-in [:server :port] 99999)
                              (assoc-in [:nrepl :enable] nil)
                              (assoc-in [:nrepl :host] "")
                              (assoc-in [:skin :screen-off-minutes] 0))))))

(deftest screen-off-minutes
  (is (= 10 (get-in config/defaults [:skin :screen-off-minutes])) "on by default, after 10 minutes")
  (doseq [minutes [nil 0.5 30]]
    (is (= [] (config/problems (assoc-in config/defaults [:skin :screen-off-minutes] minutes))) (pr-str minutes)))
  (doseq [minutes [-1 "10" true]]
    (is (= 1 (count (config/problems (assoc-in config/defaults [:skin :screen-off-minutes] minutes))))
        (pr-str minutes))))

(deftest settings-that-moved
  (is (= ["computers.pairing: now integrations.host and integrations.port"
          "integrations.port: expected a port number, got \"8485\""]
         (config/problems (-> config/defaults
                              (assoc-in [:computers :pairing] {:host "0.0.0.0" :port 8485})
                              (assoc-in [:integrations :port] "8485"))))))

(deftest provider-config
  (is (= "gpt-6-luna" (:model (config/provider-config config/defaults))))
  (is (nil? (config/provider-config (assoc config/defaults :provider "nope")))))
