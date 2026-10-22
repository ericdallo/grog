(ns grog.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [grog.registry :as registry]
            [grog.test-helpers :as h]))

(defn ^:private official [latest? status]
  {:io.modelcontextprotocol.registry/official {:status status :isLatest latest?}})

(def ^:private npm-server
  {:name "io.github.foo/weather-mcp"
   :title "Weather"
   :description "The weather."
   :version "1.0.2"
   :packages [{:registryType "npm"
               :registryBaseUrl "https://registry.npmjs.org"
               :identifier "@foo/weather-mcp"
               :version "1.0.2"
               :runtimeHint "npx"
               :transport {:type "stdio"}
               :environmentVariables [{:name "WEATHER_KEY" :isRequired true :isSecret true}]}]})

(def ^:private pypi-server
  {:name "io.github.foo/notes"
   :version "0.5.0"
   :packages [{:registryType "pypi" :identifier "notes-mcp" :version "0.5.0" :runtimeHint "uvx"
               :transport {:type "stdio"}}]})

(def ^:private oci-server
  {:name "io.github.foo/docker-only"
   :version "3.1.0"
   :packages [{:registryType "oci" :identifier "docker.io/foo/bar:3.1.0" :transport {:type "stdio"}}]})

(def ^:private sse-server
  {:name "com.example/events"
   :version "1.0.0"
   :remotes [{:type "sse" :url "https://events.example.com/sse"}]})

(def ^:private remote-server
  {:name "com.example/remote"
   :version "2.0.0"
   :remotes [{:type "streamable-http" :url "https://mcp.example.com/http"}]})

(defn ^:private with-registry
  "Calls `f` with a fake registry answering with `handler`, its requests in
  :requests."
  [handler f]
  (let [requests (atom [])
        server (h/start-handler! (fn [req]
                                   (swap! requests conj {:uri (:uri req) :query (:query-string req)})
                                   (handler req)))]
    (try (f (assoc server :requests requests))
         (finally ((:stop! server))))))

(defn ^:private thrown-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest search-lists-latest-active-servers
  (with-registry
    (fn [_]
      (h/json-response
       {:servers [{:server npm-server :_meta (official true "active")}
                  {:server (assoc npm-server :version "1.0.1") :_meta (official false "active")}
                  {:server pypi-server :_meta (official true "active")}
                  {:server oci-server :_meta (official true "active")}
                  {:server sse-server :_meta (official true "deprecated")}
                  {:server remote-server}]
        :metadata {:count 6 :nextCursor "x"}}))
    (fn [fake]
      (is (= [{:name "io.github.foo/weather-mcp" :title "Weather" :description "The weather." :version "1.0.2" :runs "npm"}
              {:name "io.github.foo/notes" :title nil :description nil :version "0.5.0" :runs "pypi"}
              {:name "io.github.foo/docker-only" :title nil :description nil :version "3.1.0" :runs nil}
              {:name "com.example/remote" :title nil :description nil :version "2.0.0" :runs "remote"}]
             (registry/search! (:url fake) " weather map ")))
      (let [{:keys [uri query]} (first @(:requests fake))]
        (is (= "/v0.1/servers" uri))
        (is (= "version=latest&limit=20&search=weather%20map" query))))))

(deftest search-fails-plainly
  (testing "a registry answering nonsense"
    (with-registry (fn [_] {:status 500 :body "oops"})
      (fn [fake]
        (is (= ::registry/bad-answer (thrown-type #(registry/search! (:url fake) "x")))))))
  (testing "a registry that isn't there"
    (let [{:keys [url stop!]} (h/start-handler! (fn [_] (h/json-response {})))]
      (stop!)
      (is (= ::registry/unreachable (thrown-type #(registry/search! url "x")))))))

(deftest fetch-gets-the-latest-version-by-name
  (with-registry
    (fn [{:keys [uri]}]
      (if (= "/v0.1/servers/io.github.foo%2Fweather-mcp/versions/latest" uri)
        (h/json-response {:server npm-server :_meta (official true "active")})
        {:status 404 :body "{\"title\":\"Not Found\",\"status\":404,\"detail\":\"Server not found\"}"}))
    (fn [fake]
      (is (= npm-server (registry/fetch! (str (:url fake) "/") "io.github.foo/weather-mcp")))
      (is (= ::registry/not-found (thrown-type #(registry/fetch! (:url fake) "io.github.foo/nope")))))))

(deftest fetch-fails-plainly
  (testing "a registry answering nonsense"
    (with-registry (fn [_] (h/json-response {:servers []}))
      (fn [fake]
        (is (= ::registry/bad-answer (thrown-type #(registry/fetch! (:url fake) "a.b/c")))))))
  (testing "a registry that isn't there"
    (let [{:keys [url stop!]} (h/start-handler! (fn [_] (h/json-response {})))]
      (stop!)
      (is (= ::registry/unreachable (thrown-type #(registry/fetch! url "a.b/c")))))))

(deftest runnable-keeps-what-grog-can-run
  (testing "an npm package"
    (is (= npm-server (registry/runnable npm-server))))
  (testing "a PyPI package, without a runtime hint"
    (let [server (update-in pypi-server [:packages 0] dissoc :runtimeHint)]
      (is (= server (registry/runnable server)))))
  (testing "a package and a remote, dropping what can't run"
    (let [server (assoc npm-server
                        :packages (into (:packages oci-server) (:packages npm-server))
                        :remotes (into (:remotes sse-server) (:remotes remote-server)))]
      (is (= (assoc npm-server :remotes (:remotes remote-server))
             (registry/runnable server)))))
  (testing "a remote only"
    (is (= remote-server (registry/runnable remote-server)))))

(deftest runnable-says-why-it-cant
  (letfn [(problem [server]
            (try (registry/runnable server) nil
                 (catch clojure.lang.ExceptionInfo e
                   (is (= ::registry/unsupported (:type (ex-data e))))
                   (ex-message e))))]
    (is (= "It can't be run here: it only comes as an OCI image." (problem oci-server)))
    (is (= "It can't be run here: it only comes as an SSE remote." (problem sse-server)))
    (is (= "It can't be run here: it only comes as an OCI image, or an SSE remote."
           (problem (merge oci-server (select-keys sse-server [:remotes])))))
    (is (= "It can't be run here: it has neither a package nor a remote."
           (problem {:name "a.b/c" :version "1.0.0"})))
    (doseq [version ["latest" "^1.0.2" "~1" ">=1.0" "1.x" "1.2.X" "*" "" nil]]
      (is (re-find #"isn't pinned" (str (problem (assoc-in npm-server [:packages 0 :version] version))))
          (pr-str version)))
    (is (re-find #"run with bunx" (problem (assoc-in npm-server [:packages 0 :runtimeHint] "bunx"))))
    (is (re-find #"served over sse" (problem (assoc-in npm-server [:packages 0 :transport] {:type "sse" :url "http://localhost:1/sse"}))))
    (is (re-find #"from https://npm.example.com" (problem (assoc-in npm-server [:packages 0 :registryBaseUrl] "https://npm.example.com"))))
    (is (re-find #"named \"Foo Bar\"" (problem (assoc-in npm-server [:packages 0 :identifier] "Foo Bar"))))))

(deftest package-entry-makes-a-server
  (is (= {:name "local.npm/scope-name"
          :title "@scope/name"
          :version "1.2.3"
          :packages [{:registryType "npm" :identifier "@scope/name" :version "1.2.3" :runtimeHint "npx"
                      :transport {:type "stdio"}}]}
         (registry/package-entry "npm:@scope/name@1.2.3")))
  (is (= "local.npm/weather" (:name (registry/package-entry "npm:weather@0.1.0-beta.2"))))
  (is (= {:name "local.pypi/mcp-server-time"
          :title "mcp-server-time"
          :version "2025.9.25"
          :packages [{:registryType "pypi" :identifier "mcp-server-time" :version "2025.9.25" :runtimeHint "uvx"
                      :transport {:type "stdio"}}]}
         (registry/package-entry " pypi:mcp-server-time==2025.9.25 ")))
  (testing "packages it makes can run"
    (is (registry/runnable (registry/package-entry "npm:@scope/name@1.2.3")))))

(deftest package-entry-refuses-bad-specs
  (doseq [spec ["@scope/name@1.2.3" "cargo:foo@1.0.0" "npm:@scope/name" "npm:name@latest" "npm:name@^1.2"
                "npm:name@1.x" "npm:Name@1.0.0" "npm:@/name@1.0.0" "pypi:name" "pypi:name==" "pypi:name>=1.0"
                "pypi:-name==1.0" "pypi:name==1.0,<2" nil ""]]
    (is (= ::registry/bad-spec (thrown-type #(registry/package-entry spec))) (pr-str spec))))

(deftest url-entry-makes-a-server
  (is (= {:name "local.url/mcp.example.com-mcp"
          :title "mcp.example.com"
          :version "0.0.0"
          :remotes [{:type "streamable-http" :url "https://mcp.example.com/mcp"}]}
         (registry/url-entry "https://mcp.example.com/mcp")))
  (doseq [url ["http://10.0.0.5/mcp" "http://172.20.1.1:8080/mcp" "http://192.168.1.10:8123/api/mcp"
               "http://127.0.0.1:3000/mcp" "http://homeassistant.local:8123/api/mcp" "http://169.254.3.4/mcp"
               "http://localhost:3000/mcp" "http://[::1]:3000/mcp" "http://[fd00::1]/mcp" "http://[fe80::1]/mcp"]]
    (is (= url (get-in (registry/url-entry url) [:remotes 0 :url])) url))
  (is (= "local.url/homeassistant.local-8123-api-mcp" (:name (registry/url-entry "http://homeassistant.local:8123/api/mcp")))))

(deftest url-entry-refuses-bad-urls
  (doseq [url ["http://mcp.example.com/mcp" "http://8.8.8.8/mcp" "http://172.32.0.1/mcp" "http://[2001:db8::1]/mcp"
               "ftp://mcp.example.com" "mcp.example.com/mcp" "https:///mcp" "https://user:pw@mcp.example.com/mcp"
               "http://10.0.0.5.example.com/mcp" "not a url" nil]]
    (is (= ::registry/bad-url (thrown-type #(registry/url-entry url))) (pr-str url))))

(deftest id-of-makes-short-ids
  (is (= "weather-mcp" (registry/id-of {:name "io.github.foo/weather-mcp"})))
  (is (= "my-server-v2" (registry/id-of {:name "com.example/My_Server.V2"})))
  (is (= "scope-name" (registry/id-of (registry/package-entry "npm:@scope/name@1.2.3"))))
  (is (= "homeassistant-local-8123-api-mcp" (registry/id-of (registry/url-entry "http://homeassistant.local:8123/api/mcp"))))
  (is (= "server" (registry/id-of {:name "a.b/___"})))
  (is (= "server" (registry/id-of {})))
  (let [id (registry/id-of {:name (str "a.b/" (apply str (repeat 39 "x")) "-yyyy")})]
    (is (= (apply str (repeat 39 "x")) id))))
