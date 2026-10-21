(ns grog.memory-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.memory :as memory]
            [grog.test-helpers :as h]))

(defn ^:private context [state-dir]
  (assistant/new-context (assoc config/defaults :state-dir state-dir)))

(deftest remembers-and-forgets
  (let [state-dir (h/temp-dir)
        ctx (context state-dir)]
    (is (= {:remembering 1} (memory/change! ctx {:remember " O lixo passa terça. "})))
    (memory/change! ctx {:remember "A Ana é minha esposa."})
    (testing "once"
      (memory/change! ctx {:remember "o lixo passa terça."})
      (is (= ["O lixo passa terça." "A Ana é minha esposa."] @(:memory ctx))))

    (testing "kept for grog's user only, and picked up after a restart"
      (let [f (memory/file @(:config ctx))]
        (is (= {:facts ["O lixo passa terça." "A Ana é minha esposa."]} (json/parse-string (slurp f) true)))
        (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions f)))))
      (is (= @(:memory ctx) @(:memory (context state-dir)))))

    (testing "forgets what's said, as listed or by a part only it has"
      (memory/change! ctx {:forget "a ana é minha esposa."})
      (is (= ["O lixo passa terça."] @(:memory ctx)))
      (memory/change! ctx {:remember "O gás acaba no fim do mês."})
      (memory/change! ctx {:forget "lixo"})
      (is (= ["O gás acaba no fim do mês."] @(:memory ctx))))

    (testing "and says when it can't"
      (memory/change! ctx {:remember "A Bia gosta de gatos."})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"more than one" (memory/change! ctx {:forget "o"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing like that" (memory/change! ctx {:forget "cachorro"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"say what" (memory/change! ctx {}))))))

(deftest limits
  (let [ctx (context (h/temp-dir))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too long"
                          (memory/change! ctx {:remember (str/join (repeat (inc memory/max-fact-chars) "a"))})))
    (doseq [i (range memory/max-facts)]
      (memory/change! ctx {:remember (str "Fato " i ".")}))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"forget some first"
                          (memory/change! ctx {:remember "Mais um."})))
    (testing "forgetting makes room in the same call"
      (memory/change! ctx {:forget "Fato 0." :remember "Mais um."})
      (is (= "Mais um." (peek @(:memory ctx)))))))

(deftest ignores-a-broken-file
  (let [state-dir (h/temp-dir)]
    (spit (str (fs/path state-dir "memory.json")) "{\"facts\": \"not a list\"}")
    (let [ctx (volatile! nil)
          log (with-out-str (vreset! ctx (context state-dir)))]
      (is (= [] @(:memory @ctx)))
      (is (str/includes? log "ignoring what was remembered"))
      (is (not (str/includes? log "not a list"))))))
