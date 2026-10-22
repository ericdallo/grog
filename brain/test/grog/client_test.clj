(ns grog.client-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.client :as client]
            [grog.config :as config]
            [grog.test-helpers :as h]))

(deftest what-status-says-was-consumed
  (testing "with the subscription, how much of its limits is used, and when they reset"
    (is (= "Usage:    5h 12% used (resets in 2h 10m), 7d 41% used (resets in 3d 4h)"
           (client/usage-line {:auth "subscription"
                               :quota {:windows [{:label "5h" :used-percent 12.4 :resets-at (+ 1000 7800)}
                                                 {:label "7d" :used-percent 40.6 :resets-at (+ 1000 273600)}]}}
                              1000)))
    (is (= "Usage:    5h 100% used" (client/usage-line {:auth "subscription"
                                                        :quota {:windows [{:label "5h" :used-percent 100
                                                                           :resets-at 900}]}}
                                                       1000))
        "a reset gone by")
    (is (= "Usage:    not known yet" (client/usage-line {:auth "subscription"} 1000))))
  (testing "with an API key, what it spent"
    (is (= "Spent:    $1.25 this month, $0.0312 today"
           (client/usage-line {:auth "api-key" :today {:usd 0.03124} :month {:usd 1.2499 :unpriced 0}} 1000)))
    (is (= "Spent:    $0.0000 this month, $0.0000 today (and 3 turns of models without a price)"
           (client/usage-line {:auth "api-key" :today {:usd 0} :month {:usd 0 :unpriced 3}} 1000))))
  (testing "nothing without credentials"
    (is (nil? (client/usage-line {:auth "none"} 1000)))))

(deftest durations
  (is (= ["<1m" "45m" "2h" "2h 10m" "3d" "3d 4h"]
         (map client/duration [30 2700 7200 7800 259200 273600]))))

(deftest what-grog-update-says
  (is (= "Looking for a new grog..." (client/update-line {:stage "fetching"})))
  (is (= "grog already is the latest: bbbbbbb." (client/update-line {:stage "current" :to "bbbbbbbbbb"})))
  (is (= "A new grog: bbbbbbb, this is aaaaaaa. Building it..."
         (client/update-line {:stage "building" :from "aaaaaaaaaa" :to "bbbbbbbbbb"})))
  (is (= "Done: grog is bbbbbbb." (client/update-line {:stage "done" :to "bbbbbbbbbb"})))
  (is (= "[error] Couldn't build it: oops" (client/update-line {:stage "failed" :error "Couldn't build it: oops"}))))

(deftest what-grog-integrations-says
  (is (= "Weather (grog connect weather): added by you, in the cloud"
         (client/integration-heading {:id "weather" :title "Weather" :mcp true :added true :cloud true})))
  (is (= "Home (grog connect home)" (client/integration-heading {:id "home" :title "Home" :mcp true})))
  (is (= "  github             GitHub, 12 tools"
         (client/connection-line {:id "github" :name "GitHub" :detail "12 tools"}))))

(deftest what-adding-asks-for
  (is (= {:package "npm:@foo/weather@1.0.0"} (client/add-request " npm:@foo/weather@1.0.0 ")))
  (is (= {:package "pypi:notes==0.5"} (client/add-request "pypi:notes==0.5")))
  (is (= {:url "https://mcp.example.com/mcp"} (client/add-request "https://mcp.example.com/mcp")))
  (is (= {:name "io.github.foo/weather"} (client/add-request "io.github.foo/weather")))
  (testing "what it says once added"
    (is (= ["Added weather: connect it with grog connect weather"
            "grog didn't check it: it runs code from npm (npm:w@1.0.0), apart from grog."]
           (client/added-lines {:package "npm:w@1.0.0"} "weather" {:cloud false})))
    (is (= ["Added mcp: connect it with grog connect mcp"
            "grog didn't check it: grog talks to https://x.example/mcp and does what it says."
            "It's in the cloud: what grog sends it leaves home."]
           (client/added-lines {:url "https://x.example/mcp"} "mcp" {:cloud true})))))

(deftest what-searching-lists
  (is (= "io.github.foo/weather 1.0.2 (npm)  Weather: The weather."
         (client/search-line {:name "io.github.foo/weather" :version "1.0.2" :runs "npm"
                              :title "Weather" :description "The weather."})))
  (is (= "x/y 1 (can't run here)" (client/search-line {:name "x/y" :version "1"})))
  (let [line (client/search-line {:name "x/y" :version "1" :runs "remote" :description (apply str (repeat 200 "a"))})]
    (is (= 100 (count line)))
    (is (str/ends-with? line "…"))))

(deftest what-grog-tools-lists
  (is (= "  create_issue  ask (default)  shown   Create an issue"
         (client/tool-line 12 {:name "create_issue" :title "Create an issue" :mode "ask" :default-mode "ask"
                               :shown true})))
  (is (= "  forecast      deny           hidden"
         (client/tool-line 12 {:name "forecast" :title "forecast" :mode "deny" :default-mode "allow" :shown false}))))

(defn ^:private fake-grog
  "Calls `f` with the URL of a fake grog API answering with `handler`, its
  requests in `requests`."
  [requests handler f]
  (let [server (h/start-handler! (fn [req]
                                   (let [body (some-> (:body req) slurp)]
                                     (swap! requests conj {:method (:request-method req) :uri (:uri req)
                                                           :query (:query-string req)
                                                           :body (some-> body not-empty config/parse)})
                                     (handler (assoc req :body body)))))]
    (try (f (:url server))
         (finally ((:stop! server))))))

(deftest the-cli-against-grog
  (let [requests (atom [])
        tool {:name "alerts.clear" :title "Clear alerts" :mode "deny" :defaultMode "ask" :shown true}]
    (fake-grog requests
               (fn [{:keys [request-method uri]}]
                 (case [request-method uri]
                   [:post "/v1/integrations"] (h/json-response {:id "weather" :integration {:id "weather" :cloud false}})
                   [:delete "/v1/integrations/weather"] (h/json-response {:id "weather" :removed true})
                   [:delete "/v1/integrations/nope"] {:status 400 :body "{\"error\": \"There's no integration called \\\"nope\\\".\"}"}
                   [:get "/v1/integrations/search"] (h/json-response {:servers []})
                   [:get "/v1/connections/weather/tools"] (h/json-response {:tools [tool]})
                   [:put "/v1/connections/weather/tools/alerts.clear"] (h/json-response tool)
                   {:status 404 :body "{}"}))
               (fn [url]
                 (is (= "Added weather: connect it with grog connect weather\ngrog didn't check it: it runs code from npm or PyPI apart from grog, or talks to its address.\n"
                        (with-out-str (is (true? (client/add-integration! url "io.github.foo/weather"))))))
                 (is (= {:name "io.github.foo/weather"} (:body (last @requests))))
                 (is (= "Removed weather.\n" (with-out-str (client/remove-integration! url "weather"))))
                 (is (= "[error] There's no integration called \"nope\".\n"
                        (with-out-str (is (false? (client/remove-integration! url "nope"))))))
                 (is (str/includes? (with-out-str (client/search! url "the weather")) "Nothing found for \"the weather\"."))
                 (is (= "q=the+weather" (:query (last @requests))))
                 (is (= (str "  alerts.clear  deny           shown   Clear alerts\n\n"
                             "Change one: grog tools weather <tool> allow|ask|deny|show|hide|default\n")
                        (with-out-str (client/tools! url "weather"))))
                 (is (= "alerts.clear  deny           shown   Clear alerts\n"
                        (with-out-str (client/set-tool! url "weather" "alerts.clear" "default"))))
                 (is (= {:method :put :body {:mode nil :show nil}} (select-keys (last @requests) [:method :body])))
                 (with-out-str (client/set-tool! url "weather" "alerts.clear" "hide"))
                 (is (= {:show false} (:body (last @requests))))))))

(deftest connecting-an-mcp-server
  (let [sessions (atom [{:integration "weather" :status "open" :step {:kind "wait" :note "Connecting…"}}
                        {:integration "weather" :status "open" :step {:kind "confirm" :question "Weather: Go?"}}
                        {:integration "weather" :status "done" :connected "Weather"}])]
    (fake-grog (atom [])
               (fn [{:keys [request-method uri]}]
                 (case [request-method uri]
                   [:post "/v1/integrations/weather/connect"]
                   (h/json-response {:integration "weather" :title "Weather" :status "open" :link "http://grog/c/t"
                                     :step {:kind "form" :fields [{:id "key" :label "Key"}]}})
                   [:get "/v1/integrations"] (h/json-response {:integrations [{:id "weather" :mcp true}]})
                   [:get "/v1/connect"] (h/json-response (ffirst (swap-vals! sessions #(if (next %) (rest %) %))))
                   {:status 404 :body "{}"}))
               (fn [url]
                 (let [out (with-out-str (is (true? (client/connect! url "weather" nil))))]
                   (is (str/includes? out "fill them in on grog's page"))
                   (is (str/includes? out "  http://grog/c/t"))
                   (is (str/includes? out "Connecting…"))
                   (is (str/includes? out "Weather: Go? (answer on grog's screen)"))
                   (is (str/ends-with? out "Connected: Weather. Ask grog to use it (grog tools weather lists its tools).\n")))))))
