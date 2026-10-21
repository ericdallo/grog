(ns grog.conversation-test
  (:require [grog.conversation :as conversation]
            [clojure.test :refer [deftest is]]))

(defn ^:private msg [role content] {:role role :content content})

(deftest trim
  (let [messages [(msg "user" 1) (msg "assistant" 1) (msg "user" 2) (msg "assistant" 2)]]
    (is (= messages (conversation/trim messages 10)))
    (is (= [(msg "user" 2) (msg "assistant" 2)] (conversation/trim messages 2)))
    (is (= [(msg "user" 2) (msg "assistant" 2)] (conversation/trim messages 3))
        "never starts with an assistant message")
    (is (= [] (conversation/trim [] 4)))))

(deftest history-expires
  (let [store (conversation/new-store)
        opts {:ttl-ms 1000 :max-messages 10}
        messages [(msg "user" "hi") (msg "assistant" "hello")]]
    (is (= [] (conversation/history store "a" 0 1000)))
    (conversation/save! store "a" messages 0 opts)
    (is (= messages (conversation/history store "a" 1000 1000)))
    (is (= [] (conversation/history store "a" 1001 1000)))
    (is (= [] (conversation/history store "b" 0 1000)))))

(deftest forget
  (let [store (conversation/new-store)
        opts {:ttl-ms 1000 :max-messages 10}]
    (conversation/save! store "a" [(msg "user" "x")] 0 opts)
    (conversation/save! store "b" [(msg "user" "y")] 0 opts)
    (conversation/forget! store "a")
    (is (= [] (conversation/history store "a" 0 1000)))
    (is (= [(msg "user" "y")] (conversation/history store "b" 0 1000)))))

(deftest save-drops-expired-conversations
  (let [store (conversation/new-store)
        opts {:ttl-ms 1000 :max-messages 10}]
    (conversation/save! store "old" [(msg "user" "x")] 0 opts)
    (conversation/save! store "new" [(msg "user" "y")] 5000 opts)
    (is (= #{"new"} (set (keys @store))))))
