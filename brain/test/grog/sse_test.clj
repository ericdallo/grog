(ns grog.sse-test
  (:require [grog.sse :as sse]
            [clojure.test :refer [deftest is]]))

(defn ^:private parse [s]
  (with-open [reader (java.io.StringReader. s)]
    (doall (sse/events reader))))

(deftest events
  (is (= [{:type "a" :n 1} {:type "b"}]
         (parse (str ": comment\n"
                     "event: a\n"
                     "data: {\"type\": \"a\", \"n\": 1}\n"
                     "\n"
                     "event: b\n"
                     "data:{\"type\": \"b\"}\n"
                     "\n")))))

(deftest multi-line-data-is-joined
  (is (= [{:type "a"}]
         (parse "data: {\"type\":\ndata: \"a\"}\n\n"))))

(deftest ignores-done-sentinel-and-empty-events
  (is (= [{:type "a"}]
         (parse "data: {\"type\": \"a\"}\n\n\n\ndata: [DONE]\n\n"))))

(deftest events-are-handed-over-without-reading-ahead
  (let [in (java.io.PipedInputStream.)
        out (java.io.PipedOutputStream. in)
        events (sse/events in)]
    (.write out (.getBytes "data: {\"type\": \"a\"}\n\n"))
    (.flush out)
    ;; Nothing else was written: reading ahead would block here.
    (is (= {:type "a"} (deref (future (first events)) 2000 :blocked)))
    (.close out)))

(deftest last-event-without-trailing-blank-line
  (is (= [{:type "a"}]
         (parse "data: {\"type\": \"a\"}"))))

(deftest writes-events-with-keys-in-camel-case
  (is (= "event: done\ndata: {\"doneAt\":1,\"recent\":[{\"ok\":true}]}\n\n"
         (sse/message "done" {:done-at 1 :recent [{:ok true}]})))
  (is (= [{:doneAt 1}] (parse (sse/message "done" {:done-at 1}))) "as the reader reads them"))
