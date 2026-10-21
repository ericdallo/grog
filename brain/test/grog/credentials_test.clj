(ns grog.credentials-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [grog.credentials :as credentials]
            [grog.test-helpers :as h]))

(deftest save-get-remove
  (let [dir (h/temp-dir)]
    (is (nil? (credentials/saved dir "openai")))
    (credentials/save! dir "openai" {:access-token "a"})
    (credentials/save! dir "other" {:access-token "b"})
    (is (= {:access-token "a"} (credentials/saved dir "openai")))
    (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions (fs/path dir "auth.json")))))
    (credentials/remove! dir "openai")
    (is (nil? (credentials/saved dir "openai")))
    (is (= {:access-token "b"} (credentials/saved dir "other")))))

(deftest without-a-state-dir
  (is (nil? (credentials/saved nil "openai"))))

(deftest a-corrupt-file-means-signed-out
  (let [dir (h/temp-dir)]
    (spit (str (fs/path dir "auth.json")) "{\"openai\": {")
    (is (str/includes? (with-out-str (is (nil? (credentials/saved dir "openai"))))
                       "isn't valid JSON")
        "logged, not thrown")
    (with-out-str (credentials/save! dir "openai" {:access-token "a"}))
    (is (= {:access-token "a"} (credentials/saved dir "openai")) "signing in again rewrites it")))
