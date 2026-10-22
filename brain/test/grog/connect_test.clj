(ns grog.connect-test
  "The engine connecting things to grog, with made-up integrations: each
  step, one session at a time, the page on the network and its time."
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.events :as events]
            [grog.oauth :as oauth]
            [grog.test-helpers :as h])
  (:import (java.net InetAddress ServerSocket Socket)))

(defn ^:private context
  "What a session needs, grog listening on loopback (`port`: any free one
  by default)."
  ([] (context 0))
  ([port]
   (assistant/new-context (-> config/defaults
                              (assoc :name "Jarvis" :state-dir (h/temp-dir))
                              (update :integrations merge {:host "127.0.0.1" :port port})))))

(defn ^:private session [ctx]
  (connect/view @(:connect ctx)))

(defn ^:private eventually [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 25) (recur))))))

(defn ^:private status-becomes [ctx status]
  (eventually 10000 #(= status (:status (session ctx)))))

(defn ^:private get* [url]
  (let [resp (http/get url {:throw false})]
    [(:status resp) (try (config/parse (:body resp)) (catch Exception _ (:body resp))) (:headers resp)]))

(defn ^:private post [url body & [content-type]]
  (let [resp (http/post url {:throw false
                             :headers {"content-type" (or content-type "application/json")}
                             :body (config/generate body)})]
    [(:status resp) (config/parse (:body resp))]))

(defn ^:private refused? [port]
  (try (with-open [_ (Socket. "127.0.0.1" (int port))] false)
       (catch java.net.ConnectException _ true)))

(defn ^:private port-of [link]
  (parse-long (second (re-find #":(\d+)/" link))))

(defn ^:private form-integration
  "Connects with a form: what was entered goes to `entered`; an address
  ending in bad doesn't answer."
  [entered]
  {:id "fake"
   :title "Fake"
   :heading "Connect Fake"
   :ways [{:id "form" :label "Fill in a form"}]
   :start! (fn [ctx {:keys [generation]} _]
             (connect/form! ctx generation
                            {:fields [{:id "url" :label "Its address" :example "http://fake.local"}
                                      {:id "token" :label "Its token" :secret true :optional true}]
                             :submit! (fn [values]
                                        (reset! entered values)
                                        (when (str/ends-with? (values "url") "bad")
                                          (throw (ex-info "nothing answers there" {})))
                                        (connect/done! ctx generation "fake.local"))}))})

(deftest a-session-and-its-page
  (with-redefs [connect/linger-ms 300]
    (let [ctx (context)
          entered (atom nil)]
      (try
        (let [now (System/currentTimeMillis)
              opened (connect/open! ctx (form-integration entered) nil)
              {:keys [link] :as view} (session ctx)]
          (testing "open, on the network, behind a token"
            (is (= {:integration "fake" :title "Fake" :heading "Connect Fake" :way "form"
                    :ways [{:id "form" :label "Fill in a form"}] :status "open" :kept false}
                   (select-keys view [:integration :title :heading :way :ways :status :kept])))
            (is (re-matches #"http://127\.0\.0\.1:\d+/c/[2-9A-HJ-NP-Z]{10}" link))
            (is (<= (+ now connect/window-ms) (:until view) (+ now connect/window-ms 1000)) "for 10 minutes")
            (is (= "form" (get-in view [:step :kind])))
            (is (= view opened) "the same for whoever opened it")
            (is (= view (:connect (events/snapshot (:events ctx)))) "and whoever watches"))
          (testing "the page"
            (let [[status body headers] (get* link)]
              (is (= 200 status))
              (is (str/includes? body "<h1 id=\"heading\">Connect Fake</h1>"))
              (is (str/includes? body "data-name=\"Jarvis\""))
              (is (= "no-referrer" (get headers "referrer-policy")) "its address has the token")
              (is (str/starts-with? (get headers "content-security-policy") "default-src 'none'")))
            (is (= 200 (first (get* (str/lower-case link)))) "however the token is typed"))
          (testing "what it shows"
            (let [[status state] (get* (str link "/state"))]
              (is (= 200 status))
              (is (= {:name "Jarvis" :title "Fake" :heading "Connect Fake" :status "open" :way "form"
                      :step {:kind "form"
                             :fields [{:id "url" :label "Its address" :example "http://fake.local"}
                                      {:id "token" :label "Its token" :secret true :optional true}]}}
                     (select-keys state [:name :title :heading :status :way :step])))
              (is (<= 590 (:left-s state) 600))))
          (testing "nothing without the token"
            (is (= 404 (first (get* (str/replace link #"/c/.*" "/c/ABCDEFGHJK")))))
            (is (= 404 (first (get* (str/replace link #"/c/.*" "/"))))))
          (testing "what's entered: JSON only, what makes sense"
            (is (= 415 (first (post (str link "/form") {:url "http://fake.local"} "text/plain"))))
            (is (= [400 {:error "Its address: fill it in"}] (post (str link "/form") {:token "t"})))
            (is (= [400 {:error "nothing answers there"}] (post (str link "/form") {:url "http://bad"})))
            (is (= "open" (:status (session ctx))))
            (is (= [200 {:ok true}] (post (str link "/form") {:url " http://fake.local " :token ""})))
            (is (= {"url" "http://fake.local"} @entered) "trimmed, without what's blank"))
          (testing "connected"
            (is (= {:status "done" :connected "fake.local"} (select-keys (session ctx) [:status :connected])))
            (is (= "done" (:status (second (get* (str link "/state")))))))
          (testing "then grog leaves the network"
            (is (eventually 5000 #(refused? (port-of link))))
            (is (nil? (:link (session ctx))))))
        (finally
          (connect/stop! ctx))))))

(defn ^:private device-integration
  "Connects with codes from `codes`, good for `ttl-ms`, asking whether one
  was entered with (poll code)."
  [codes ttl-ms poll]
  {:id "fake"
   :title "Fake"
   :heading "Sign in to Fake"
   :ways [{:id "device" :label "Enter a code instead"}]
   :start! (fn [ctx {:keys [generation]} _]
             (connect/device-code! ctx generation
                                   {:code! #(let [[code] (swap-vals! codes rest)]
                                              (if-let [c (first code)]
                                                {:user-code c :url "https://fake.example/device" :interval-s 1}
                                                (throw (ex-info "no more codes" {}))))
                                    :poll! poll
                                    :finish! (fn [grant] (str "greg+" grant "@example.com"))
                                    :ttl-ms ttl-ms
                                    :help {:text "Allow codes first." :url "https://fake.example/settings"}
                                    :note "Signing in…"}))})

(deftest a-device-code
  (testing "entered: connected"
    (let [ctx (context)
          polls (atom [nil :boom "g1"])]
      (try
        (connect/open! ctx (device-integration (atom ["CODE-1"]) 60000
                                               (fn [code]
                                                 (is (= "CODE-1" (:user-code code)))
                                                 (let [[p] (swap-vals! polls rest)]
                                                   (if (= :boom (first p)) (throw (ex-info "offline" {})) (first p)))))
                       nil)
        (is (= {:kind "device-code" :code "CODE-1" :url "https://fake.example/device"
                :help {:text "Allow codes first." :url "https://fake.example/settings"}}
               (dissoc (:step (session ctx)) :expires-at)))
        (is (status-becomes ctx "done"))
        (is (= "greg+g1@example.com" (:connected (session ctx))))
        (finally
          (connect/stop! ctx)))))
  (testing "a new code once one expires"
    (let [ctx (context)]
      (try
        (connect/open! ctx (device-integration (atom ["OLD-1" "NEW-2"]) 500 (constantly nil)) nil)
        (is (eventually 5000 #(= "NEW-2" (get-in (session ctx) [:step :code]))))
        (is (= "open" (:status (session ctx))))
        (finally
          (connect/stop! ctx)))))
  (testing "asking keeps failing"
    (with-redefs [connect/poll-failures-max 2]
      (let [ctx (context)]
        (try
          (connect/open! ctx (device-integration (atom ["X-1"]) 60000 (fn [_] (throw (ex-info "Boom." {})))) nil)
          (is (status-becomes ctx "failed"))
          (is (= "Boom." (:error (session ctx))))
          (finally
            (connect/stop! ctx))))))
  (testing "no code at all"
    (let [ctx (context)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no more codes"
                              (connect/open! ctx (device-integration (atom []) 60000 (constantly nil)) nil)))
        (is (= {:status "failed" :error "no more codes"} (select-keys (session ctx) [:status :error])))
        (finally
          (connect/stop! ctx))))))

(defn ^:private oauth-integration
  "Signs in through a browser coming back to `redirect-uri`: the grant
  traded goes to `granted`; `opts` go to oauth! too."
  [redirect-uri granted & [opts]]
  {:id "fake"
   :title "Fake"
   :heading "Sign in to Fake"
   :ways [{:id "browser" :label "Sign in in the browser instead"}]
   :start! (fn [ctx {:keys [generation]} _]
             (connect/oauth! ctx generation
                             {:redirect-uri redirect-uri
                              :issuer (:issuer opts)
                              :iss-required? (:iss-required? opts)
                              :authorize-url (fn [{:keys [challenge state redirect-uri]}]
                                               (str "https://fake.example/authorize?"
                                                    (oauth/form-encode {:code_challenge challenge :state state
                                                                        :redirect_uri redirect-uri})))
                              :finish! (fn [grant]
                                         (reset! granted grant)
                                         (when (= "bad" (:code grant))
                                           (throw (ex-info "Fake refused the code" {})))
                                         "greg@example.com")
                              :note "Signing in…"}))})

(defn ^:private query [url]
  (oauth/form-decode (second (str/split url #"\?" 2))))

(deftest signing-in-in-a-browser
  (testing "coming back to grog's machine"
    (let [ctx (context)
          port (h/free-port)
          granted (atom nil)
          callback (format "http://127.0.0.1:%d/auth/callback" port)]
      (try
        (let [{:keys [step]} (connect/open! ctx (oauth-integration (format "http://localhost:%d/auth/callback" port) granted)
                                            nil)
              {:keys [state code_challenge redirect_uri]} (query (:url step))]
          (is (= (format "http://localhost:%d/auth/callback" port) (:back-to step) redirect_uri))
          (testing "whoever watches never sees where the browser signs in, which carries its state"
            (is (= {:kind "oauth" :back-to redirect_uri} (:step (session ctx)))))
          (testing "a callback of another sign-in is refused"
            (is (str/includes? (:body (http/get (str callback "?code=c&state=forged"))) "doesn't belong"))
            (is (= "open" (:status (session ctx)))))
          (testing "the right one signs in"
            (is (str/includes? (:body (http/get (str callback "?code=the-code&state=" state))) "Signed in to Jarvis"))
            (is (= {:status "done" :connected "greg@example.com"} (select-keys (session ctx) [:status :connected])))
            (is (= "the-code" (:code @granted)))
            (is (= code_challenge (oauth/challenge (:verifier @granted))) "with the verifier of its challenge")
            (is (= redirect_uri (:redirect-uri @granted))))
          (testing "and only once"
            (is (str/includes? (:body (http/get (str callback "?code=the-code&state=" state))) "Sign-in failed")))
          (testing "then grog stops listening there"
            (is (eventually 5000 #(refused? port)))))
        (finally
          (connect/stop! ctx)))))
  (testing "pasting where the browser ended, on the page"
    (let [ctx (context)
          granted (atom nil)]
      (try
        (let [{:keys [step link]} (connect/open! ctx (oauth-integration (format "http://localhost:%d/cb" (h/free-port))
                                                                        granted)
                                                 nil)
              {:keys [state]} (query (:url step))]
          (is (= (:url step) (get-in (second (get* (str link "/state"))) [:step :url])) "the page has it")
          (is (= [400 {:error "There's no code in it: paste the whole address the browser ended at."}]
                 (post (str link "/oauth") {:url (str "http://localhost/cb?state=" state)})))
          (is (= [200 {:ok true}] (post (str link "/oauth") {:url (str "http://localhost/cb?code=pasted&state=" state)})))
          (is (= "pasted" (:code @granted)))
          (is (= "done" (:status (session ctx)))))
        (finally
          (connect/stop! ctx)))))
  (testing "coming back to grog's page on the network"
    (let [ctx (context)
          granted (atom nil)]
      (try
        (let [{:keys [step link]} (connect/open! ctx (oauth-integration :lan granted) nil)
              {:keys [state]} (query (:url step))
              back-to (str (str/replace link #"/c/.*" "") "/c/callback")]
          (is (= back-to (:back-to step)))
          (is (str/includes? (:body (http/get (str back-to "?code=lan&state=" state))) "Signed in to Jarvis"))
          (is (= "lan" (:code @granted))))
        (finally
          (connect/stop! ctx)))))
  (testing "coming back through the relay page"
    (let [ctx (context)
          granted (atom nil)]
      (try
        (let [{:keys [step link]} (connect/open! ctx (oauth-integration {:relay "https://relay.example/oauth/"} granted) nil)
              {:keys [state redirect_uri]} (query (:url step))
              at (second (re-find #"http://([^/]+)/c/" link))
              back-to (str "http://" at "/c/callback?")]
          (is (= "https://relay.example/oauth/callback/" redirect_uri (:back-to step)))
          (is (re-matches #"127\.0\.0\.1:\d+~[A-Za-z0-9_-]{43}" state) "saying where grog is")
          (is (= at (first (str/split state #"~"))))
          (testing "the whole state, not only its random part"
            (is (str/includes? (:body (http/get (str back-to (oauth/form-encode {:code "c" :state (second (str/split state #"~"))}))))
                               "doesn't belong"))
            (is (= "open" (:status (session ctx)))))
          (is (str/includes? (:body (http/get (str back-to (oauth/form-encode {:code "relayed" :state state}))))
                             "Signed in to Jarvis"))
          (is (= {:code "relayed" :redirect-uri "https://relay.example/oauth/callback/"}
                 (select-keys @granted [:code :redirect-uri]))))
        (finally
          (connect/stop! ctx)))))
  (testing "pasting the relay page's address"
    (let [ctx (context)
          granted (atom nil)]
      (try
        (let [{:keys [step link]} (connect/open! ctx (oauth-integration {:relay "https://relay.example/oauth/"} granted) nil)
              {:keys [state]} (query (:url step))]
          (is (= [200 {:ok true}]
                 (post (str link "/oauth")
                       {:url (str "https://relay.example/oauth/callback/?" (oauth/form-encode {:code "pasted" :state state}))})))
          (is (= "pasted" (:code @granted))))
        (finally
          (connect/stop! ctx)))))
  (testing "the relay page, without grog on the network"
    (with-open [busy (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
      (let [ctx (context (.getLocalPort busy))]
        (try
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"isn't on the network"
                                (connect/open! ctx (oauth-integration {:relay "https://relay.example/oauth/"} (atom nil)) nil)))
          (finally
            (connect/stop! ctx))))))
  (testing "an issuer that always says who it is"
    (doseq [[params outcome] [[{} "failed"]
                              [{:iss "https://evil.example"} "failed"]
                              [{:iss "https://fake.example"} "done"]]]
      (let [ctx (context)]
        (try
          (let [{:keys [step]} (connect/open! ctx (oauth-integration :lan (atom nil) {:issuer "https://fake.example"
                                                                                      :iss-required? true})
                                              nil)
                {:keys [state]} (query (:url step))]
            (http/get (str (:back-to step) "?" (oauth/form-encode (merge {:code "c" :state state} params))))
            (is (= outcome (:status (session ctx))) (pr-str params)))
          (finally
            (connect/stop! ctx))))))
  (testing "refused by the provider, or what it gave back"
    (doseq [[params error] [["error=access_denied&error_description=Denied" "Denied"]
                            ["code=bad" "Fake refused the code"]]]
      (let [ctx (context)
            port (h/free-port)]
        (try
          (let [{:keys [step]} (connect/open! ctx (oauth-integration (format "http://localhost:%d/cb" port) (atom nil)) nil)
                {:keys [state]} (query (:url step))]
            (is (str/includes? (:body (http/get (format "http://127.0.0.1:%d/cb?%s&state=%s" port params state))) error))
            (is (= {:status "failed" :error error} (select-keys (session ctx) [:status :error]))))
          (finally
            (connect/stop! ctx))))))
  (testing "a port that's busy"
    (let [ctx (context)]
      (with-open [busy (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
        (try
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Port \d+ is busy"
                                (connect/open! ctx (oauth-integration (format "http://localhost:%d/cb" (.getLocalPort busy))
                                                                      (atom nil))
                                               nil)))
          (finally
            (connect/stop! ctx)))))))

(defn ^:private compare-integration []
  {:id "gadget"
   :title "Gadgets"
   :heading "Add a gadget"
   :ways [{:id "code" :label "Compare codes"}]
   :start! (fn [ctx {:keys [generation]} _]
             (connect/code-compare! ctx generation
                                    {:code "482913" :name "lamp" :user "greg"
                                     :decide! #(if %
                                                 (connect/done! ctx generation "lamp")
                                                 (connect/deny! ctx generation))}))})

(deftest the-same-code-on-both-screens
  (let [ctx (context)]
    (try
      (connect/open! ctx (compare-integration) nil)
      (is (= {:kind "code-compare" :code "482913" :name "lamp" :user "greg"} (:step (session ctx))))
      (is (= "done" (:status (connect/decide! ctx true))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing is waiting to be allowed" (connect/decide! ctx true)))
      (connect/open! ctx (compare-integration) nil)
      (is (= "denied" (:status (connect/decide! ctx false))))
      (finally
        (connect/stop! ctx)))))

(defn ^:private ways-integration
  "Connects two ways, telling `ended` how each way ended."
  [ended]
  {:id "twofold"
   :title "Twofold"
   :heading "Connect Twofold"
   :ways [{:id "one" :label "The first way"} {:id "two" :label "The second way"}]
   :start! (fn [ctx {:keys [generation]} way]
             (connect/on-end! ctx generation #(swap! ended conj [way %]))
             (connect/step! ctx generation {:kind "wait" :note way}))})

(deftest one-session-at-a-time
  (let [ctx (context)
        ended (atom [])]
    (try
      (let [first-link (:link (connect/open! ctx (ways-integration ended) nil))]
        (testing "opened again the same way: the same, for longer"
          (let [until (:until (session ctx))]
            (Thread/sleep 10)
            (is (= first-link (:link (connect/open! ctx (ways-integration ended) "one"))))
            (is (< until (:until (session ctx))))
            (is (= [] @ended))))
        (testing "another way: the same page"
          (is (= first-link (:link (connect/open! ctx (ways-integration ended) "two"))))
          (is (= {:way "two" :step {:kind "wait" :note "two"}} (select-keys (session ctx) [:way :step])))
          (is (= [["one" "switched"]] @ended)))
        (testing "or from the page"
          (let [[status state] (post (str first-link "/way") {:way "one"})]
            (is (= [200 "one"] [status (:way state)])))
          (is (= [400 {:error "Twofold connects one or two, not \"three\""}] (post (str first-link "/way") {:way "three"}))))
        (testing "something else: that one ends, and its page goes"
          (let [{:keys [link integration]} (connect/open! ctx (compare-integration) nil)]
            (is (= "gadget" integration))
            (is (not= first-link link))
            (is (= [["one" "switched"] ["two" "switched"] ["one" "cancelled"]] @ended))
            (is (= 404 (first (get* first-link))))
            (is (= 200 (first (get* link)))))))
      (testing "cancelled"
        (is (= "cancelled" (:status (connect/cancel! ctx)))))
      (testing "an unknown way"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Gadgets connects code, not \"pigeon\""
                              (connect/open! ctx (compare-integration) "pigeon"))))
      (finally
        (connect/stop! ctx)))))

(deftest its-time
  (with-redefs [connect/window-ms 300
                connect/linger-ms 300]
    (let [ctx (context)]
      (try
        (let [{:keys [link]} (connect/open! ctx (compare-integration) nil)]
          (is (status-becomes ctx "expired"))
          (is (thrown? clojure.lang.ExceptionInfo (connect/decide! ctx true)) "nothing to allow then")
          (is (eventually 5000 #(refused? (port-of link)))))
        (finally
          (connect/stop! ctx))))))

(deftest grog-going-away
  (let [ctx (context)
        port (h/free-port)
        {:keys [link]} (connect/open! ctx (oauth-integration (format "http://localhost:%d/cb" port) (atom nil)) nil)]
    (connect/stop! ctx)
    (is (= "cancelled" (:status (session ctx))))
    (is (refused? (port-of link)) "at once")
    (is (eventually 5000 #(refused? port)) "and where the browser comes back")))

(deftest without-the-network
  (with-open [busy (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
    (let [ctx (context (.getLocalPort busy))]
      (try
        (testing "a session that can do without its page"
          (connect/open! ctx (device-integration (atom ["CODE-1"]) 60000 (constantly nil)) nil)
          (is (= {:status "open" :code "CODE-1"} {:status (:status (session ctx)) :code (get-in (session ctx) [:step :code])}))
          (is (nil? (:link (session ctx)))))
        (testing "and one that can't"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"grog can't listen on 127\.0\.0\.1:\d+"
                                (connect/open! ctx (assoc (compare-integration) :network? true) nil))))
        (finally
          (connect/stop! ctx))))))

(deftest an-integrations-own-requests
  (let [ctx (context)
        hello {:handle (fn [_ state req]
                         {:status 200 :body (str (:uri req) " while " (:status state))})}]
    (try
      (let [{:keys [link]} (connect/open! ctx (merge (compare-integration) hello) nil)
            root (str/replace link #"/c/.*" "")]
        (is (= "/hello while open" (:body (http/get (str root "/hello")))))
        (connect/open! ctx (ways-integration (atom [])) nil)
        (is (= 404 (:status (http/get (str root "/hello") {:throw false}))) "only while its session is the last"))
      (finally
        (connect/stop! ctx)))))

(defn ^:private ask-integration
  "Asks whoever is at grog `question`, telling `decided` the answer."
  [question decided]
  {:id "ask"
   :title "Tools"
   :heading "Allow a tool?"
   :ways [{:id "ask"}]
   :start! (fn [ctx {:keys [generation]} _]
             (connect/confirm! ctx generation
                               {:question question
                                :detail "ha__HassTurnOff"
                                :decide! #(do (reset! decided %)
                                              (if %
                                                (connect/done! ctx generation nil)
                                                (connect/deny! ctx generation)))}))})

(deftest asking-at-grog
  (let [ctx (context)
        decided (atom nil)]
    (try
      (testing "a session made up for it, while none is open"
        (let [{:keys [link] :as opened} (connect/open! ctx (ask-integration "Turn off the alarm?" decided) nil
                                                       {:unless-busy? true})]
          (is (= {:integration "ask" :heading "Allow a tool?" :ways [{:id "ask"}] :status "open"
                  :step {:kind "confirm" :question "Turn off the alarm?" :detail "ha__HassTurnOff"}}
                 (select-keys opened [:integration :heading :ways :status :step])))
          (is (= opened (:connect (events/snapshot (:events ctx)))))
          (testing "its page"
            (is (= 200 (first (get* link))))
            (is (= {:kind "confirm" :question "Turn off the alarm?" :detail "ha__HassTurnOff"}
                   (:step (second (get* (str link "/state"))))))
            (is (= 200 (first (post (str link "/way") {:way "ask"})))))
          (testing "another, while it's open: busy"
            (let [e (try (connect/open! ctx (ask-integration "Unlock the door?" decided) nil {:unless-busy? true})
                         nil
                         (catch clojure.lang.ExceptionInfo e e))]
              (is (= ::connect/busy (:type (ex-data e))))
              (is (= "Turn off the alarm?" (get-in (session ctx) [:step :question])))))
          (testing "allowed at grog"
            (is (= "done" (:status (connect/decide! ctx true))))
            (is (true? @decided)))))
      (testing "or not"
        (connect/open! ctx (ask-integration "Unlock the door?" decided) nil {:unless-busy? true})
        (is (= "denied" (:status (connect/decide! ctx false))))
        (is (false? @decided)))
      (testing "not while something is connecting"
        (connect/open! ctx (compare-integration) nil)
        (is (thrown? clojure.lang.ExceptionInfo
                     (connect/open! ctx (ask-integration "Unlock the door?" decided) nil {:unless-busy? true})))
        (is (= "gadget" (:integration (session ctx)))))
      (finally
        (connect/stop! ctx)))))

(deftest asking-for-a-while
  (let [ctx (context)
        now (System/currentTimeMillis)]
    (try
      (let [{:keys [until]} (connect/open! ctx (ask-integration "Turn off the alarm?" (atom nil)) nil {:window-ms 300})]
        (is (<= (+ now 300) until (+ now 1300)))
        (is (status-becomes ctx "expired")))
      (finally
        (connect/stop! ctx)))))

(defn ^:private link-integration [done]
  {:id "elicit"
   :title "GitHub"
   :heading "GitHub asks"
   :ways [{:id "ask"}]
   :start! (fn [ctx {:keys [generation]} _]
             (connect/link! ctx generation
                            {:url "https://github.com/login/device?x=1"
                             :message "Sign in to GitHub to go on"
                             :done! #(do (reset! done true)
                                         (connect/done! ctx generation "github"))}))})

(deftest a-link-to-open
  (let [ctx (context)
        done (atom false)]
    (try
      (let [{:keys [link step]} (connect/open! ctx (link-integration done) nil)]
        (is (= {:kind "link" :url "https://github.com/login/device?x=1" :message "Sign in to GitHub to go on"} step))
        (is (= step (:step (session ctx))) "whoever watches sees it, for its QR code")
        (is (= step (:step (second (get* (str link "/state"))))))
        (testing "done there"
          (is (= {:status "done" :connected "github"} (select-keys (connect/decide! ctx true) [:status :connected])))
          (is (true? @done))))
      (testing "cancelled"
        (reset! done false)
        (connect/open! ctx (link-integration done) nil)
        (is (= "cancelled" (:status (connect/cancel! ctx))))
        (is (false? @done)))
      (testing "or not"
        (connect/open! ctx (link-integration done) nil)
        (is (= "denied" (:status (connect/decide! ctx false))))
        (is (false? @done)))
      (finally
        (connect/stop! ctx)))))

(defn ^:private value-of [field v]
  (get (connect/form-values [(assoc field :id "x" :label "X")] {:x v}) "x" ::none))

(defn ^:private refusal [field v]
  (try (value-of field v)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(def ^:private colors
  [{:value "red" :label "Red"} {:value "green" :label "Green"} {:value "blue" :label "Blue"}])

(deftest typed-fields
  (testing "text, as before"
    (is (= "hi" (value-of {} " hi ")))
    (is (= ::none (value-of {:optional true} "  ")))
    (is (= "X: fill it in" (refusal {} nil)))
    (is (= "X: text" (refusal {} 3)))
    (is (= "X: too long" (refusal {} (apply str (repeat 2049 "a"))))))
  (testing "text in a format"
    (is (= "greg@example.com" (value-of {:format "email"} "greg@example.com")))
    (is (= "X: not an email address" (refusal {:format "email"} "greg")))
    (is (= "https://example.com/a" (value-of {:format "uri"} "https://example.com/a")))
    (is (= "X: not an address (https://...)" (refusal {:format "uri"} "example com")))
    (is (= "2026-10-22" (value-of {:format "date"} "2026-10-22")))
    (is (some? (refusal {:format "date"} "2026-13-40")))
    (is (= "2026-10-22T14:05" (value-of {:format "date-time"} "2026-10-22T14:05")))
    (is (= "2026-10-22T14:05:00Z" (value-of {:format "date-time"} "2026-10-22T14:05:00Z")))
    (is (some? (refusal {:format "date-time"} "tomorrow")))
    (is (= "X: at least 3 characters" (refusal {:min 3} "ab"))))
  (testing "numbers"
    (is (= 2.5 (value-of {:type "number"} "2.5")))
    (is (= 2.5 (value-of {:type "number"} 2.5)))
    (is (= 4 (value-of {:type "integer"} "4")))
    (is (instance? Long (value-of {:type "integer"} 4)))
    (is (= "X: a whole number" (refusal {:type "integer"} "4.5")))
    (is (= "X: a number" (refusal {:type "number"} "four")))
    (is (= "X: a number" (refusal {:type "number"} "NaN")))
    (is (= "X: at least 1" (refusal {:type "integer" :min 1 :max 10} 0)))
    (is (= "X: at most 10" (refusal {:type "integer" :min 1 :max 10} "11")))
    (is (= "X: fill it in" (refusal {:type "number"} "")))
    (is (= ::none (value-of {:type "number" :optional true} ""))))
  (testing "yes or no: unchecked is no"
    (is (true? (value-of {:type "boolean"} true)))
    (is (false? (value-of {:type "boolean"} nil)))
    (is (false? (value-of {:type "boolean" :optional true} nil)))
    (is (= "X: yes or no" (refusal {:type "boolean"} "maybe"))))
  (testing "one choice"
    (is (= "green" (value-of {:type "choice" :choices colors} "green")))
    (is (= "X: pick one of the choices" (refusal {:type "choice" :choices colors} "pink")))
    (is (= "X: pick one" (refusal {:type "choice" :choices colors} "")))
    (is (= ::none (value-of {:type "choice" :choices colors :optional true} nil))))
  (testing "choices"
    (is (= ["red" "blue"] (value-of {:type "choices" :choices colors} ["red" "blue" "red"])))
    (is (= "X: pick among the choices" (refusal {:type "choices" :choices colors} ["red" "pink"])))
    (is (= "X: at most 1 of them" (refusal {:type "choices" :choices colors :max 1} ["red" "blue"])))
    (is (= "X: at least 2 of them" (refusal {:type "choices" :choices colors :min 2} ["red"])))
    (is (= "X: pick one" (refusal {:type "choices" :choices colors} [])))
    (is (= [] (value-of {:type "choices" :choices colors :min 0} [])))
    (is (= ::none (value-of {:type "choices" :choices colors :optional true} [])))))

(deftest a-typed-form-on-the-page
  (let [ctx (context)
        entered (atom nil)
        fields [{:id "volume" :label "Volume" :type "integer" :min 0 :max 100 :default 40}
                {:id "loud" :label "Loud" :type "boolean" :description "Louder at night too"}
                {:id "color" :label "Color" :type "choice" :choices colors :default "red"}
                {:id "rooms" :label "Rooms" :type "choices" :choices colors :optional true}
                {:id "mail" :label "Mail" :format "email" :optional true}]
        integration {:id "typed" :title "Typed" :heading "A few things" :ways [{:id "ask"}]
                     :start! (fn [ctx {:keys [generation]} _]
                               (connect/form! ctx generation
                                              {:fields fields
                                               :submit! #(do (reset! entered %)
                                                             (connect/done! ctx generation nil))}))}]
    (try
      (let [{:keys [link]} (connect/open! ctx integration nil)]
        (is (= 200 (first (get* link))))
        (is (= {:kind "form" :fields fields} (:step (second (get* (str link "/state"))))))
        (is (= [400 {:error "Volume: at most 100"}] (post (str link "/form") {:volume "200" :color "red"})))
        (is (= [200 {:ok true}] (post (str link "/form") {:volume "70" :loud true :color "blue" :rooms ["green"]})))
        (is (= {"volume" 70 "loud" true "color" "blue" "rooms" ["green"]} @entered)))
      (finally
        (connect/stop! ctx)))))
