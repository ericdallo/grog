(ns grog.elicit-test
  (:require [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.elicit :as elicit]
            [grog.mcp :as mcp]
            [grog.test-helpers :as h]))

(defn ^:private context []
  (assistant/new-context (-> config/defaults
                             (assoc :state-dir (h/temp-dir))
                             (update :integrations merge {:host "127.0.0.1" :port 0}))))

(defn ^:private session [ctx]
  (connect/view @(:connect ctx)))

(defn ^:private step-becomes [ctx kind]
  (h/eventually 5000 #(= ["open" kind] [(:status (session ctx)) (get-in (session ctx) [:step :kind])])))

(def ^:private schema
  {:type "object"
   :properties {:room {:type "string" :title "Room" :description "Where" :minLength 2}
                :count {:type "integer" :minimum 1 :maximum 5 :default 1}
                :loud {:type "boolean"}
                :color {:type "string" :enum ["r" "g"] :enumNames ["Red" "Green"]}
                :size {:type "string" :oneOf [{:const "s" :title "Small"} {:const "l" :title "Large"}]}
                :days {:type "array" :items {:anyOf [{:const "mon" :title "Monday"} {:const "tue"}]} :maxItems 1}
                :mail {:type "string" :format "email"}}
   :required ["room" "count"]})

(deftest fields-from-a-schema
  (is (= [[:room {:id "f1" :label "Room" :description "Where" :min 2}]
          [:count {:id "f2" :label "count" :default 1 :type "integer" :min 1 :max 5}]
          [:loud {:id "f3" :label "loud" :optional true :type "boolean"}]
          [:color {:id "f4" :label "color" :optional true :type "choice"
                   :choices [{:value "r" :label "Red"} {:value "g" :label "Green"}]}]
          [:size {:id "f5" :label "size" :optional true :type "choice"
                  :choices [{:value "s" :label "Small"} {:value "l" :label "Large"}]}]
          [:days {:id "f6" :label "days" :optional true :type "choices" :max 1
                  :choices [{:value "mon" :label "Monday"} {:value "tue" :label "tue"}]}]
          [:mail {:id "f7" :label "mail" :optional true :format "email"}]]
         (elicit/form-fields schema))))

(deftest a-form
  (let [ctx (context)]
    (try
      (let [answer (future (elicit/ask! ctx "Lights" {:mode "form" :message "Which room?" :requestedSchema schema}))]
        (is (step-becomes ctx "form"))
        (is (= {:integration "elicit" :title "Lights" :heading "Lights asks"} (select-keys (session ctx) [:integration :title :heading])))
        (is (= "Which room? Where" (get-in (session ctx) [:step :fields 0 :description])) "the message shown")
        (testing "what's wrong, said on the page"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Room: at least 2 characters"
                                ((get-in @(:connect ctx) [:handlers :submit "form"]) {:f1 "a" :f2 "2"}))))
        ((get-in @(:connect ctx) [:handlers :submit "form"]) {:f1 "sala" :f2 "2" :f4 "g"})
        (is (= {:action "accept" :content {:room "sala" :count 2 :loud false :color "g"}} (deref answer 5000 nil)))
        (is (= "done" (:status (session ctx)))))
      (testing "cancelled"
        (let [answer (future (elicit/ask! ctx "Lights" {:mode "form" :message "Which room?" :requestedSchema schema}))]
          (is (step-becomes ctx "form"))
          (connect/cancel! ctx)
          (is (= {:action "cancel"} (deref answer 5000 nil)))))
      (testing "nobody answers in time"
        (with-redefs [elicit/window-ms 200]
          (is (= {:action "cancel"} (elicit/ask! ctx "Lights" {:mode "form" :message "?" :requestedSchema schema})))))
      (finally
        (connect/stop! ctx)))))

(deftest a-link
  (let [ctx (context)]
    (try
      (let [answer (future (elicit/ask! ctx "GitHub" {:mode "url" :message "Sign in to go on" :url "https://github.com/x"}))]
        (is (step-becomes ctx "link"))
        (is (= {:kind "link" :url "https://github.com/x" :message "Sign in to go on"} (:step (session ctx))))
        (connect/decide! ctx true)
        (is (= {:action "accept"} (deref answer 5000 nil))))
      (testing "said no"
        (let [answer (future (elicit/ask! ctx "GitHub" {:mode "url" :message "Sign in" :url "https://github.com/x"}))]
          (is (step-becomes ctx "link"))
          (connect/decide! ctx false)
          (is (= {:action "decline"} (deref answer 5000 nil)))))
      (testing "not a web address"
        (is (= {:action "cancel"} (elicit/ask! ctx "GitHub" {:mode "url" :message "x" :url "file:///etc/passwd"}))))
      (finally
        (connect/stop! ctx)))))

(deftest while-something-else-is-open
  (let [ctx (context)]
    (try
      (connect/open! ctx {:id "other" :title "Other" :heading "Other" :ways [{:id "x"}]
                          :start! (fn [ctx {:keys [generation]} _] (connect/step! ctx generation {:kind "wait"}))}
                     nil)
      (is (= {:action "cancel"} (elicit/ask! ctx "Lights" {:mode "url" :message "x" :url "https://x.example"})))
      (is (= "other" (:integration (session ctx))))
      (finally
        (connect/stop! ctx)))))

(deftest a-server-asking-mid-call
  (let [ctx (context)
        fake (h/fake-stdio-mcp! {:call (fn [_ _] {:resultType "input_required"
                                                  :inputRequests {:q {:method "elicitation/create"
                                                                      :params {:mode "url" :message "Open it"
                                                                               :url "https://x.example"}}}})})
        client (mcp/connect! (:transport fake) {:on-elicit #(elicit/ask! ctx "Fake" %)})]
    (try
      (let [call (future (try (mcp/call-tool! client "x" {}) (catch Exception e e)))]
        (is (step-becomes ctx "link"))
        (connect/decide! ctx true)
        (is (h/eventually 5000 #(some (fn [m] (= {:q {:action "accept"}} (get-in m [:params :inputResponses])))
                                      @(:received fake))))
        (future-cancel call))
      (finally
        (mcp/close! client)
        (connect/stop! ctx)))))
