(ns grog.runner-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.runner :as runner]
            [grog.test-helpers :as h])
  (:import (java.io BufferedReader)))

(def ^:private npx
  "A fake npx: which server runs depends on the package asked for."
  "case $2 in
     echo@*) printf 'args:'; printf ' %s' \"$@\"; echo
             while IFS= read -r line; do echo \"got $line\"; done ;;
     env@*) env ;;
     fails@*) echo 'npm error 404 not found' >&2; exit 1 ;;
     chatty@*) echo 'installing' >&2; sleep 0.3; echo ready; sleep 0.3
               echo 'what someone said' >&2; cat ;;
     stubborn@*) sleep 30 & echo $!; wait ;;
     deaf@*) trap '' TERM; sleep 30 & echo $!; wait ;;
   esac")

(def ^:private uvx
  "A fake uvx: says how it was run, then echoes."
  "printf 'args:'; printf ' %s' \"$@\"; echo
   while IFS= read -r line; do echo \"got $line\"; done")

(defn ^:private eventually
  "Whether `f` turns true within 5 s."
  [f]
  (loop [tries 250]
    (cond
      (f) true
      (zero? tries) false
      :else (do (Thread/sleep 20) (recur (dec tries))))))

(defn ^:private alive? [pid]
  (let [h (java.lang.ProcessHandle/of pid)]
    (and (.isPresent h) (.isAlive (.get h)))))

(defn ^:private call-with-runner
  "Calls `f` with the socket of a runner using the fake npx and uvx, and
  what it logs."
  [f]
  (let [dir (h/temp-dir)
        socket (str (fs/path dir "mcp.sock"))
        log (java.io.StringWriter.)
        stop (binding [*out* log]
               (runner/start-server! socket {:npx (h/script! dir "npx" npx)
                                             :uvx (h/script! dir "uvx" uvx)
                                             :grace-ms 300}))]
    (try (f socket log)
         (finally (stop)))))

(defmacro ^:private with-runner
  "Runs `body` with `socket` a runner using the fake npx and uvx, and `log`
  what it logs."
  [[socket log] & body]
  `(call-with-runner (fn [~socket ~log] ~@body)))

(defn ^:private reader ^BufferedReader [server]
  (io/reader (:in server)))

(defn ^:private send! [server line]
  (.write (:out server) (.getBytes (str line "\n") "UTF-8"))
  (.flush (:out server)))

(defn ^:private npm [identifier & {:as more}]
  (merge {:registryType "npm" :identifier identifier :version "1.0.0"} more))

(deftest checks-requests
  (let [ok {"registryType" "npm" "identifier" "@scope/pkg" "version" "1.2.3"}]
    (testing "a good one"
      (is (nil? (runner/problem ok)))
      (doseq [version ["0.6.2" "2025.1" "1.0.0-beta.2+build.7" "v1.2.3" "1.0.0rc1"]]
        (is (nil? (runner/problem (assoc ok "version" version))) version))
      (is (nil? (runner/problem {"registryType" "pypi" "identifier" "mcp-server-time" "version" "2025.1.0"
                                 "packageArguments" ["--local-timezone" "America/Sao_Paulo"]
                                 "env" {"API_KEY" "k"}}))))
    (testing "bad ones"
      (doseq [[bad why] [["x" #"object"]
                         [(assoc ok "registryType" "cargo") #"npm or pypi"]
                         [(assoc ok "identifier" "Bad/Name") #"package name"]
                         [(assoc ok "identifier" "--eval=x") #"package name"]
                         [(assoc ok "identifier" (apply str (repeat 215 "a"))) #"package name"]
                         [(assoc ok "registryType" "pypi" "identifier" "a_") #"package name"]
                         [(assoc ok "version" "^1.2.3") #"exact version"]
                         [(assoc ok "version" "latest") #"exact version"]
                         [(assoc ok "version" "1.x") #"exact version"]
                         [(assoc ok "version" "1.2 || 2") #"exact version"]
                         [(assoc ok "version" "") #"exact version"]
                         [(assoc ok "version" nil) #"exact version"]
                         [(assoc ok "packageArguments" "--x") #"packageArguments"]
                         [(assoc ok "packageArguments" [1]) #"packageArguments"]
                         [(assoc ok "packageArguments" ["a\u0000b"]) #"packageArguments"]
                         [(assoc ok "packageArguments" (repeat 65 "a")) #"packageArguments"]
                         [(assoc ok "env" ["A"]) #"env must be"]
                         [(assoc ok "env" {"A-B" "x"}) #"env names"]
                         [(assoc ok "env" {"A" 1}) #"env values"]
                         [(assoc ok "env" {"PATH" "/tmp"}) #"can't set PATH"]
                         [(assoc ok "env" {"HOME" "/tmp"}) #"can't set HOME"]
                         [(assoc ok "env" {"NPM_CONFIG_REGISTRY" "x"}) #"can't set"]
                         [(assoc ok "env" {"npm_config_registry" "x"}) #"can't set"]
                         [(assoc ok "env" {"NODE_OPTIONS" "x"}) #"can't set"]
                         [(assoc ok "env" {"LD_PRELOAD" "x"}) #"can't set"]
                         [(assoc ok "env" {"UV_INDEX_URL" "x"}) #"can't set"]]]
        (is (re-find why (str (runner/problem bad))) (pr-str bad))))))

(deftest makes-commands
  (let [programs {:npx "npx" :uvx "uvx"}]
    (is (= ["npx" "--yes" "@scope/pkg@1.2.3" "--x" "y"]
           (runner/command programs {"registryType" "npm" "identifier" "@scope/pkg" "version" "1.2.3"
                                     "packageArguments" ["--x" "y"]})))
    (is (= ["uvx" "mcp-server-time==2025.1.0"]
           (runner/command programs {"registryType" "pypi" "identifier" "mcp-server-time"
                                     "version" "2025.1.0"})))))

(deftest gives-a-clean-environment
  (is (= {"PATH" "/bin" "HOME" "/home/x" "npm_config_update_notifier" "false"
          "NO_UPDATE_NOTIFIER" "1" "API_KEY" "k"}
         (runner/child-env {"PATH" "/bin" "HOME" "/home/x" "SECRET" "s" "USER" "grog"}
                           {"API_KEY" "k"}))))

(deftest runs-servers
  (with-runner [socket log]
    (testing "only its user and group can reach it"
      (is (= "rw-rw----" (fs/posix->str (fs/posix-file-permissions socket)))))

    (testing "from npm, relaying both ways"
      (let [server (runner/start! socket (npm "echo" :packageArguments ["--x" "a b"]))
            r (reader server)]
        (try
          (is (= "args: --yes echo@1.0.0 --x a b" (.readLine r)))
          (send! server "hello")
          (is (= "got hello" (.readLine r)))
          (send! server "ação")
          (is (= "got ação" (.readLine r)))
          (finally ((:close! server))))
        (is (eventually #(str/includes? (str log) "mcp echo: exited with")))))

    (testing "from PyPI"
      (let [server (runner/start! socket {:registryType "pypi" :identifier "mcp-server-time"
                                          :version "2025.1.0"})
            r (reader server)]
        (try
          (is (= "args: mcp-server-time==2025.1.0" (.readLine r)))
          (finally ((:close! server))))))

    (testing "with a clean environment, and what it asks for"
      (let [server (runner/start! socket (npm "env" :env {"API_KEY" "the key"}))
            env (into {} (for [line (line-seq (reader server))
                               :let [[k v] (str/split line #"=" 2)]]
                           [k v]))
            allowed (into #{"API_KEY" "npm_config_update_notifier" "NO_UPDATE_NOTIFIER"
                            ;; bash's own
                            "PWD" "SHLVL" "_" "OLDPWD"}
                          (keys (runner/child-env (System/getenv) nil)))]
        (is (= "the key" (get env "API_KEY")))
        (is (= "false" (get env "npm_config_update_notifier")))
        (is (= (System/getenv "PATH") (get env "PATH")))
        (is (empty? (remove allowed (keys env))) "nothing else of the runner's")))

    (testing "logging when it starts, not what it's asked"
      (is (str/includes? (str log) "mcp env: started, version 1.0.0"))
      (is (not (str/includes? (str log) "the key")))
      (is (not (str/includes? (str log) "hello"))))))

(deftest refuses-bad-requests
  (with-runner [socket _log]
    (testing "saying why"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exact version"
                            (runner/start! socket (npm "echo" :version "latest"))))
      (try (runner/start! socket (npm "echo" :env {"PATH" "/tmp"}))
           (catch clojure.lang.ExceptionInfo e
             (is (= ::runner/refused (:type (ex-data e))))
             (is (re-find #"PATH" (ex-message e))))))
    (testing "programs it can't run"
      (let [dir (h/temp-dir)
            socket2 (str (fs/path dir "other.sock"))
            stop (binding [*out* (java.io.StringWriter.)]
                   (runner/start-server! socket2 {:npx (str (fs/path dir "nope"))}))]
        (try
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"couldn't start"
                                (runner/start! socket2 (npm "echo"))))
          (finally (stop)))))))

(deftest when-a-server-exits
  (with-runner [socket log]
    (let [server (runner/start! socket (npm "fails"))]
      (testing "the connection closes"
        (is (= -1 (.read (:in server)))))
      (testing "saying how it exited, and what it said before"
        (is (eventually #(str/includes? (str log) "mcp fails: exited with 1")))
        (is (str/includes? (str log) "mcp fails: npm error 404 not found"))))))

(deftest only-logs-stderr-until-it-answers
  (with-runner [socket log]
    (let [server (runner/start! socket (npm "chatty"))
          r (reader server)]
      (try
        (is (= "ready" (.readLine r)))
        (Thread/sleep 600)
        (send! server "ping")
        (is (= "ping" (.readLine r)))
        (finally ((:close! server))))
      (is (str/includes? (str log) "mcp chatty: installing"))
      (is (not (str/includes? (str log) "someone said"))))))

(deftest closing-stops-it
  (with-runner [socket log]
    (testing "and what it started, when it doesn't stop by itself"
      (let [server (runner/start! socket (npm "stubborn"))
            pid (parse-long (.readLine (reader server)))]
        (is (alive? pid))
        ((:close! server))
        (is (eventually #(not (alive? pid))))
        (is (eventually #(str/includes? (str log) "mcp stubborn: exited with")))))
    (testing "even when it ignores being asked to"
      (let [server (runner/start! socket (npm "deaf"))
            pid (parse-long (.readLine (reader server)))]
        ((:close! server))
        (Thread/sleep 400)
        (is (alive? pid) "asked first")
        (is (eventually #(not (alive? pid))) "then made to")))))

(deftest reaching-the-runner
  (testing "nobody listening"
    (try (runner/start! (str (fs/path (h/temp-dir) "none.sock")) (npm "echo"))
         (is false "it threw")
         (catch clojure.lang.ExceptionInfo e
           (is (= ::runner/unreachable (:type (ex-data e)))))))
  (testing "closed before answering"
    (let [socket (str (fs/path (h/temp-dir) "mute.sock"))
          server (doto (java.nio.channels.ServerSocketChannel/open java.net.StandardProtocolFamily/UNIX)
                   (.bind (java.net.UnixDomainSocketAddress/of socket)))]
      (future (.close (.accept server)))
      (try (runner/start! socket (npm "echo"))
           (is false "it threw")
           (catch clojure.lang.ExceptionInfo e
             (is (= ::runner/no-answer (:type (ex-data e))))))
      (.close server))))
