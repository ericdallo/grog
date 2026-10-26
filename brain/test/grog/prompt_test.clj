(ns grog.prompt-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.config :as config]
            [grog.prompt :as prompt]
            [grog.test-helpers :as h])
  (:import (java.time ZoneId ZonedDateTime)))

(def ^:private now
  (ZonedDateTime/of 2026 10 15 9 5 0 0 (ZoneId/of "America/Sao_Paulo")))

(deftest format-date-time
  (is (= "Thursday, October 15, 2026, 09:05" (prompt/format-date-time now))))

(deftest time-note
  (is (= {:role "developer" :content "It is now Thursday, October 15, 2026, 09:05 (America/Sao_Paulo time zone)."}
         (prompt/time-note now))))

(deftest built-in-prompt
  (testing "defaults: named grog, the user's language, web search"
    (is (= (str "You are grog, a personal voice assistant that lives in the user's home.\n"
                "\n"
                "Your answers are read aloud, so:\n"
                "- Answer in the language the user speaks to you.\n"
                "- Be natural and direct, in at most two or three sentences, unless asked for more detail.\n"
                "- Don't use markdown, lists, tables, emojis, links or URLs.\n"
                "- Write numbers, dates, times and units the way they are spoken.\n"
                "- If you don't know or aren't sure, say so instead of making things up.\n"
                "- Use web search when the answer depends on recent or changing information, like news, weather, scores, prices and schedules.\n"
                "\n"
                "Context:\n"
                "- The date and time come just before each question (the home's time zone is America/Sao_Paulo).\n"
                "- You don't know where the home is. When an answer depends on it, like the weather, ask the user which city they live in, saying you'll save it. When they say it, save it with the settings tool.\n"
                "- You run on the AI model gpt-6-luna.")
           (prompt/system-prompt config/defaults now))))

  (testing "name, language, location and extra instructions"
    (let [p (prompt/system-prompt (assoc config/defaults
                                         :name "Friday"
                                         :language "Brazilian Portuguese"
                                         :location {:city "São Paulo" :region "SP" :country "BR"}
                                         :instructions "Call the user boss.\n")
                                  now)]
      (is (str/starts-with? p "You are Friday, a personal voice assistant"))
      (is (str/includes? p "\n- Always answer in Brazilian Portuguese.\n"))
      (is (not (str/includes? p "the language the user speaks")))
      (is (str/includes? p "\n- The home is in São Paulo, SP, BR.\n- You run on the AI model gpt-6-luna.\n\nCall the user boss."))
      (is (not (str/includes? p "where the home is")))
      (is (str/ends-with? p "boss."))))

  (testing "the model in use"
    (let [config (-> config/defaults
                     (assoc :state-dir (h/temp-dir))
                     (assoc-in [:providers :openai :model] "gpt-6-astra"))]
      (is (str/includes? (prompt/system-prompt config now) "\n- You run on the AI model gpt-6-astra."))))

  (testing "web search disabled isn't mentioned"
    (let [p (prompt/system-prompt (assoc-in config/defaults [:providers :openai :web-search :enable] false) now)]
      (is (not (str/includes? p "web search")))
      (is (not (str/includes? p "searching the web")))
      (is (str/includes? p "\n- If you don't know or aren't sure, say so instead of making things up.\n\nContext:\n"))
      (is (str/includes? p "they live in, saying you'll save it. When they say it, save it with the settings tool.\n")))))

(deftest what-it-remembers
  (is (str/ends-with? (prompt/system-prompt config/defaults now {:memory ["A Ana é minha esposa." "O lixo passa terça."]})
                      (str "save it with the settings tool.\n"
                           "- You run on the AI model gpt-6-luna.\n"
                           "\n"
                           "What you remember, asked to or learned from earlier conversations (follow what it"
                           " says about how to answer):\n"
                           "- A Ana é minha esposa.\n"
                           "- O lixo passa terça.")))
  (is (not (str/includes? (prompt/system-prompt config/defaults now {:memory []}) "remember"))))

(deftest custom-prompt
  (testing "systemPrompt replaces the whole template, variables included"
    (is (= "Be grog, in America/Sao_Paulo. It's Thursday, October 15, 2026, 09:05.\n\nAlways in rhymes."
           (prompt/system-prompt (assoc config/defaults
                                        :system-prompt "Be {{name}}, in {{timezone}}. It's {{now}}."
                                        :instructions "Always in rhymes.")
                                 now))))

  (testing "systemPromptFile, read on each turn"
    (let [file (str (fs/path (h/temp-dir) "prompt.md"))
          config (assoc config/defaults :system-prompt-file file)]
      (spit file "You are {{name}}.{% if location %} Home: {{location}}.{% endif %}")
      (is (= "You are grog." (prompt/system-prompt config now)))
      (spit file "Hi, {{name}}.")
      (is (= "Hi, grog." (prompt/system-prompt config now))))))

(deftest problems
  (is (nil? (prompt/problems config/defaults)))
  (is (= ["systemPrompt: the template doesn't render: No closing tag found for :if"]
         (prompt/problems (assoc config/defaults :system-prompt "{% if name %}Hi")))))
