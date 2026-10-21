(ns grog.server-test
  (:require [clojure.test :refer [deftest is testing]]
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
