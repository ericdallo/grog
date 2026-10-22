(ns grog.main-test
  (:require [babashka.cli :as cli]
            [clojure.test :refer [deftest is]]
            [grog.main :as main]))

(defn ^:private parse
  "The options `grog <cmds> <args...>` reads."
  [cmds args]
  (let [command (some #(when (= cmds (:cmds %)) %) main/commands)]
    (:opts (cli/parse-args args (select-keys command [:spec :args->opts])))))

(deftest options-after-what-a-command-is-about
  (is (= {:provider "openai" :browser true} (parse ["login"] ["openai" "--browser"])))
  (is (= {:provider "openai" :browser true} (parse ["login"] ["--browser" "openai"])))
  (is (= {:browser true} (parse ["login"] ["--browser"])))
  (is (= {:id "openai" :browser true} (parse ["connect"] ["openai" "--browser"])))
  (is (= {:id "computer:123"} (parse ["disconnect"] ["computer:123"])) "an id stays an id")
  (is (= {:what "npm:@foo/weather@1.0.0"} (parse ["integrations" "add"] ["npm:@foo/weather@1.0.0"])))
  (is (= {:id "weather"} (parse ["integrations" "remove"] ["weather"])))
  (is (= {:id "github"} (parse ["tools"] ["github"])))
  (is (= {:id "github" :tool "create_issue" :choice "deny"} (parse ["tools"] ["github" "create_issue" "deny"])))
  (is (= {:id "github" :tool "1.0" :choice "ask"} (parse ["tools"] ["github" "1.0" "ask"])) "a tool's name stays a name"))

(deftest integrations-subcommands-come-first
  (is (= [["integrations" "add"] ["integrations" "remove"] ["integrations" "search"] ["integrations"]]
         (filter #(= "integrations" (first %)) (map :cmds main/commands)))))
