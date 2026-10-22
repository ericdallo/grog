(ns grog.connections-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.config :as config]
            [grog.connections :as connections]
            [grog.test-helpers :as h]))

(defn ^:private mode [& path]
  (fs/posix->str (fs/posix-file-permissions (apply fs/path path))))

(deftest keeps-connections-and-their-secrets-apart
  (let [dir (h/temp-dir)]
    (is (= [] (connections/all dir)))
    (connections/save! dir {:id "spotify" :integration "spotify" :name "greg@example.com"} {:refresh-token "r1"})
    (connections/save! dir {:id "openai" :integration "openai" :name "grog@example.com"} {:access-token "a"})
    (testing "by id, connected"
      (is (= [["openai" "openai" "grog@example.com" "connected"] ["spotify" "spotify" "greg@example.com" "connected"]]
             (map (juxt :id :integration :name :status) (connections/all dir))))
      (is (pos-int? (:added-at (connections/by-id dir "openai")))))
    (testing "their secrets in files of their own, which only grog can read"
      (is (= {:access-token "a"} (connections/secrets dir "openai")))
      (is (= {:refresh-token "r1"} (connections/secrets dir "spotify")))
      (is (= "rwx------" (mode dir "secrets")))
      (is (= "rw-------" (mode dir "secrets" "openai.json")))
      (is (= "rw-------" (mode dir "integrations.json")))
      (is (not (str/includes? (slurp (connections/file dir)) "r1")) "never with the connections"))
    (testing "saved again: new secrets, added when it first was"
      (let [added (:added-at (connections/by-id dir "spotify"))]
        (Thread/sleep 5)
        (connections/save! dir {:id "spotify" :integration "spotify" :name "greg@example.com"} {:refresh-token "r2"})
        (is (= added (:added-at (connections/by-id dir "spotify"))))
        (is (= {:refresh-token "r2"} (connections/secrets dir "spotify")))))
    (testing "how one is"
      (connections/set-status! dir "spotify" "signed-out")
      (is (= "signed-out" (:status (connections/by-id dir "spotify"))))
      (connections/set-status! dir "nope" "signed-out")
      (is (nil? (connections/by-id dir "nope")) "only those there"))
    (testing "forgotten, with its secrets"
      (connections/remove! dir "spotify")
      (is (= ["openai"] (map :id (connections/all dir))))
      (is (nil? (connections/secrets dir "spotify")))
      (is (not (fs/exists? (fs/path dir "secrets" "spotify.json")))))
    (testing "in lowerCamelCase, like grog's other files"
      (is (str/includes? (slurp (connections/file dir)) "\"addedAt\"")))))

(deftest ids-name-files
  (let [dir (h/temp-dir)]
    (doseq [id ["../auth" "Open AI" "" "a/b"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a connection can't be called"
                            (connections/save! dir {:id id :integration "x"} {}))
          id))))

(deftest what-cant-be-read-is-like-nothing
  (let [dir (h/temp-dir)]
    (spit (connections/file dir) "{\"connections\": [")
    (is (str/includes? (with-out-str (is (= [] (connections/all dir)))) "ignoring the connections in")
        "logged, not thrown")
    (fs/create-dirs (fs/path dir "secrets"))
    (spit (str (fs/path dir "secrets" "openai.json")) "{")
    (is (str/includes? (with-out-str (is (nil? (connections/secrets dir "openai")))) "ignoring the secrets in"))
    (with-out-str (connections/save! dir {:id "openai" :integration "openai"} {:access-token "a"}))
    (is (= {:access-token "a"} (connections/secrets dir "openai")) "connecting again rewrites them")
    (is (= {:connections [{:id "openai" :integration "openai" :added-at (:added-at (connections/by-id dir "openai"))
                           :status "connected"}]}
           (config/parse (slurp (connections/file dir)))))))
