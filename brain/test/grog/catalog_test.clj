(ns grog.catalog-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [grog.catalog :as catalog]
            [grog.config :as config]
            [grog.registry :as registry]
            [grog.test-helpers :as h]))

(defn ^:private config []
  (assoc config/defaults :state-dir (h/temp-dir)))

(def ^:private weather
  (-> (registry/package-entry "npm:@fake/weather@1.0.0")
      (assoc :title "Weather")
      (assoc-in [:packages 0 :environmentVariables]
                [{:name "API_KEY" :description "Its key" :isSecret true :isRequired true}
                 {:name "UNITS" :choices ["metric" "imperial"] :default "metric" :isRequired false}
                 {:name "MODE" :value "fixed"}
                 {:name "HOME_URL" :value "{host}/home" :variables {:host {:description "Where" :default "http://a"}}}])
      (assoc-in [:packages 0 :packageArguments]
                [{:type "positional" :value "serve"} {:type "named" :name "--port" :value "8080"}])
      (assoc-in [:_meta catalog/meta-key] {:policy {:delete "allow"} :cloud false})))

(deftest adding-and-removing
  (let [config (config)]
    (testing "none at first"
      (is (= [] (catalog/entries config))))
    (testing "added: its short id, then another"
      (is (= "fake-weather" (catalog/add! config weather)))
      (is (= "fake-weather-2" (catalog/add! config weather)))
      (is (= ["fake-weather" "fake-weather-2"] (map catalog/id-of (catalog/entries config)))))
    (testing "kept in the state directory, without what it said of grog's fields"
      (let [kept (json/parse-string (slurp (str (fs/path (:state-dir config) "catalog" "fake-weather.json"))) true)]
        (is (= #{:id :added :addedAt} (set (keys (get-in kept [:_meta catalog/meta-key])))))
        (is (true? (get-in kept [:_meta catalog/meta-key :added])))
        (is (= "fixed" (get-in kept [:packages 0 :environmentVariables 2 :value]))))
      (is (catalog/added? (catalog/entry config "fake-weather")))
      (is (not (catalog/trusted? (catalog/entry config "fake-weather")))))
    (testing "what grog can't run isn't added"
      (is (thrown? clojure.lang.ExceptionInfo (catalog/add! config {:name "x/y" :packages [{:registryType "oci"}]}))))
    (testing "removed"
      (is (= {:id "fake-weather-2" :removed true} (catalog/remove! config "fake-weather-2")))
      (is (nil? (catalog/entry config "fake-weather-2")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no integration called" (catalog/remove! config "nope"))))
    (testing "those shipped with grog come first, are trusted, and stay"
      (with-redefs [catalog/shipped (constantly [(assoc-in weather [:_meta catalog/meta-key :id] "weather")])]
        (is (= ["weather" "fake-weather"] (map catalog/id-of (catalog/entries config))))
        (is (catalog/trusted? (catalog/entry config "weather")))
        (is (= "weather-2" (catalog/id-of (catalog/entry config (catalog/add! config (assoc weather :name "x/weather"))))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"comes with grog" (catalog/remove! config "weather")))))
    (testing "never the id of grog's own integrations"
      (is (= "computer-2" (catalog/add! config (assoc weather :name "x/computer")))))))

(deftest what-a-package-needs
  (let [config (assoc-in (config) [:integrations :runner] "/run/grog-mcp/runner.sock")
        inputs (catalog/inputs config weather)]
    (is (= :package (catalog/how config weather)))
    (is (= [["env" "API_KEY"] ["env" "UNITS"] ["var" "host"]] (map (juxt :kind :name) inputs)))
    (is (= [{:id "f1" :label "API_KEY" :description "Its key" :secret true}
            {:id "f2" :label "UNITS" :optional true :default "metric" :type "choice"
             :choices [{:value "metric" :label "metric"} {:value "imperial" :label "imperial"}]}
            {:id "f3" :label "host" :description "Where" :default "http://a"}]
           (catalog/fields inputs)))
    (let [values (catalog/values inputs {"f1" "s3cret" "f3" "http://b"})]
      (is (= [{:kind "env" :name "API_KEY" :value "s3cret"} {:kind "var" :name "host" :value "http://b"}] values))
      (is (= {:kind :package
              :request {:registryType "npm" :identifier "@fake/weather" :version "1.0.0"
                        :packageArguments ["serve" "--port" "8080"]
                        :env {"API_KEY" "s3cret" "UNITS" "metric" "MODE" "fixed" "HOME_URL" "http://b/home"}}}
             (catalog/launch config weather values))))
    (testing "not in the cloud"
      (is (false? (catalog/cloud? config weather))))))

(deftest what-a-remote-needs
  (let [config (config)
        entry {:name "x/home" :version "1.0"
               :remotes [{:type "streamable-http" :url "{url}/api/mcp"
                          :variables {:url {:description "Its address"}}
                          :headers [{:name "X-Key" :isSecret true}
                                    {:name "X-Fixed" :value "yes"}]}]}
        inputs (catalog/inputs config entry)]
    (is (= :remote (catalog/how config entry)))
    (is (= [["var" "url"] ["header" "X-Key"]] (map (juxt :kind :name) inputs)))
    (is (= {:kind :remote :url "http://homeassistant.local:8123/api/mcp" :headers {"X-Key" "k" "X-Fixed" "yes"}}
           (catalog/launch config entry (catalog/values inputs {"f1" "http://homeassistant.local:8123" "f2" "k"}))))
    (testing "an address grog doesn't go to"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plain http is only for the local network"
                            (catalog/launch config entry (catalog/values inputs {"f1" "http://example.com" "f2" "k"})))))
    (testing "in the cloud, unless it's at home"
      (is (true? (catalog/cloud? config (registry/url-entry "https://mcp.example.com/mcp"))))
      (is (false? (catalog/cloud? config (registry/url-entry "http://192.168.15.10:8123/mcp"))))
      (is (false? (catalog/cloud? config entry)) "an address yet to be given"))
    (testing "a package without the runner: its remote"
      (is (= :remote (catalog/how config (assoc entry :packages (:packages weather)))))
      (is (= :package (catalog/how config weather)) "or the package, when it has nothing else"))))
