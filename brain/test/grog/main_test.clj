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
  (is (= {:address "192.168.15.17" :port 8487 :trust true}
         (parse ["pair"] ["192.168.15.17" "--port" "8487" "--trust"])))
  (is (= {:address "192.168.15.17" :trust true} (parse ["pair"] ["--trust" "192.168.15.17"])))
  (is (= {:provider "openai" :browser true} (parse ["login"] ["openai" "--browser"])))
  (is (= {:browser true} (parse ["login"] ["--browser"])))
  (is (= {:name "123"} (parse ["computers" "forget"] ["123"])) "a name stays a name"))
