(ns grog.hands-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.hands :as hands]
            [grog.test-helpers :as h])
  (:import (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels ServerSocketChannel)))

(deftest runs-commands
  (testing "stdout and stderr together, and the exit code"
    (is (= {:exit 3 :output "out\nerr\n" :timed-out false :truncated false}
           (hands/run-command "echo out; echo err >&2; exit 3" 5000))))
  (testing "without input"
    (is (= {:exit 0 :output "" :timed-out false :truncated false}
           (hands/run-command "cat" 5000))))
  (testing "up to a point"
    (let [{:keys [output truncated]} (hands/run-command "head -c 20000 /dev/zero | tr '\\0' a" 5000)]
      (is (= hands/max-output-chars (count output)))
      (is (true? truncated))))
  (testing "in any language"
    (is (= "ação ☀\n" (:output (hands/run-command "echo 'ação ☀'" 5000)))))
  (testing "for a while: then, it and what it started are killed"
    (let [started (System/nanoTime)
          {:keys [output timed-out exit]} (hands/run-command "echo started; sleep 30 & wait" 300)]
      (is (true? timed-out))
      (is (= "started\n" output))
      (is (some? exit))
      (is (< (- (System/nanoTime) started) 5e9) "didn't wait for the sleep"))))

(deftest what-a-command-leaves-in-the-background-goes-on
  (let [started (System/nanoTime)
        result (hands/run-command "sleep 30 & echo hi" 5000)]
    (is (= {:exit 0 :output "hi\n" :timed-out false :truncated false} result))
    (is (< (- (System/nanoTime) started) 2e9) "without waiting for it")))

(deftest over-a-unix-socket
  (let [socket (str (fs/path (h/temp-dir) "hands.sock"))
        log (java.io.StringWriter.)
        stop (binding [*out* log] (hands/start! socket))]
    (try
      (testing "only its user and group can reach it"
        (is (= "rw-rw----" (fs/posix->str (fs/posix-file-permissions socket)))))
      (testing "it runs what grog asks"
        (is (= {:exit 0 :output "a secret\n" :timed-out false :truncated false}
               (hands/ask! socket "echo a secret" 5000))))
      (testing "and logs how it went, not what"
        (is (str/includes? (str log) "hands: ran a command: exit 0 in"))
        (is (not (str/includes? (str log) "secret"))))
      (testing "a request without a command"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused" (hands/ask! socket nil 5000))))
      (testing "for a time that makes no sense"
        (doseq [timeout-ms [0 -1 (inc hands/max-timeout-ms)]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refused it: timeoutMs must be"
                                (hands/ask! socket "true" timeout-ms))
              (str timeout-ms))))
      (finally
        (stop))))
  (testing "nobody listening"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"can't be reached"
                          (hands/ask! (str (fs/path (h/temp-dir) "none.sock")) "true" 1000)))))

(deftest hands-hanging-up-without-an-answer
  (let [socket (str (fs/path (h/temp-dir) "mute.sock"))
        server (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of socket)))]
    (try
      (future (with-open [ch (.accept server)]
                (.readLine (io/reader (Channels/newInputStream ch)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gave no answer" (hands/ask! socket "true" 1000)))
      (finally
        (.close server)))))
