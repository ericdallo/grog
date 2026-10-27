(ns grog.server-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [grog.config :as config]
            [grog.server :as server]
            [grog.test-helpers :as h]))

(defn ^:private start [config]
  (binding [*out* (java.io.StringWriter.)]
    (server/start! (-> config
                       (assoc :state-dir (h/temp-dir))
                       (assoc-in [:server :port] 0)))))

(deftest stop-stops-the-nrepl
  (let [port (h/free-port)
        srv (start (assoc config/defaults :nrepl {:enable true :host "127.0.0.1" :port port}))]
    (with-open [_ (java.net.Socket. "127.0.0.1" (int port))]
      (is true "the nREPL listens"))
    (server/stop! srv)
    (is (thrown? java.net.ConnectException (java.net.Socket. "127.0.0.1" (int port))))))

(deftest the-running-servers-context
  (let [srv (start config/defaults)]
    (is (= (:ctx srv) @server/current))
    (testing "until it stops"
      (server/stop! srv)
      (is (nil? @server/current)))))

(deftest says-the-model-as-changed-by-asking
  (let [dir (h/temp-dir)
        _ (spit (str (fs/path dir "settings.json")) "{\"providers\": {\"openai\": {\"model\": \"gpt-6-astra\"}}}")
        log (java.io.StringWriter.)
        srv (binding [*out* log]
              (server/start! (-> config/defaults
                                 (assoc :state-dir dir)
                                 (assoc-in [:providers :openai :api-key-env] "GROG_TEST_KEY")
                                 (assoc-in [:server :port] 0))))]
    (try
      (is (re-find #"listening on .* model=gpt-6-astra\)" (str log)))
      (finally (server/stop! srv)))))
