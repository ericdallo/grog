(ns grog.test-runner
  "Runs every *_test.clj namespace under test/."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn ^:private test-namespaces []
  (->> (fs/glob "test" "**_test.clj")
       (map #(-> (str (fs/relativize "test" %))
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn run-tests! []
  (let [namespaces (test-namespaces)]
    (apply require namespaces)
    (let [{:keys [fail error]} (apply t/run-tests namespaces)]
      (when (pos? (+ fail error))
        (System/exit 1)))))
