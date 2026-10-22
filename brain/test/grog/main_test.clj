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
  (is (= {:id "computer:123"} (parse ["disconnect"] ["computer:123"])) "an id stays an id"))
