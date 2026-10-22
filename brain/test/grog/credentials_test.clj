(ns grog.credentials-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.connections :as connections]
            [grog.credentials :as credentials]
            [grog.test-helpers :as h]))

(deftest save-get-remove
  (let [dir (h/temp-dir)]
    (is (nil? (credentials/saved dir "openai")))
    (credentials/save! dir "openai" {:access-token "a" :email "grog@example.com"})
    (credentials/save! dir "other" {:access-token "b"})
    (is (= {:access-token "a" :email "grog@example.com"} (credentials/saved dir "openai")))
    (testing "the provider's connection, named after the account"
      (is (= {:id "openai" :integration "openai" :name "grog@example.com" :status "connected"}
             (dissoc (connections/by-id dir "openai") :added-at))))
    (credentials/remove! dir "openai")
    (is (nil? (credentials/saved dir "openai")))
    (is (nil? (connections/by-id dir "openai")))
    (is (= {:access-token "b"} (credentials/saved dir "other")))))

(deftest without-a-state-dir
  (is (nil? (credentials/saved nil "openai"))))

(deftest once-they-stop-working
  (let [dir (h/temp-dir)]
    (credentials/save! dir "openai" {:access-token "a"})
    (credentials/signed-out! dir "openai")
    (is (nil? (credentials/saved dir "openai")) "like none")
    (is (= "signed-out" (:status (connections/by-id dir "openai"))) "it needs signing in again")
    (credentials/save! dir "openai" {:access-token "b"})
    (is (= {:access-token "b"} (credentials/saved dir "openai")) "until it is")))

(deftest from-older-grogs
  (let [dir (h/temp-dir)
        old (str (fs/path dir "auth.json"))]
    (spit old (json/generate-string {:openai {:type "chatgpt" :access-token "a" :email "grog@example.com"}}))
    (is (str/includes? (with-out-str (credentials/migrate! dir)) "moved the credentials in"))
    (is (= {:type "chatgpt" :access-token "a" :email "grog@example.com"} (credentials/saved dir "openai")))
    (is (= "grog@example.com" (:name (connections/by-id dir "openai"))))
    (is (not (fs/exists? old)))
    (testing "only once"
      (is (= "" (with-out-str (credentials/migrate! dir)))))
    (testing "never over a newer sign-in"
      (credentials/save! dir "openai" {:access-token "new"})
      (spit old (json/generate-string {:openai {:access-token "old"}}))
      (with-out-str (credentials/migrate! dir))
      (is (= {:access-token "new"} (credentials/saved dir "openai"))))
    (testing "what can't be read stays, logged"
      (spit old "{\"openai\": {")
      (is (str/includes? (with-out-str (credentials/migrate! dir)) "couldn't move the credentials in"))
      (is (fs/exists? old)))))
