(ns grog.player-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.player :as player]
            [grog.test-helpers :as h]))

(defn ^:private config-of [socket]
  {:music {:socket socket}})

(deftest asks-the-player
  (let [{:keys [socket lines requests stop!]}
        (h/start-player! (fn [{:keys [command]}]
                           (case command
                             "status" {:ok true :state "playing" :title "Song" :position-s 12 :duration-s 200}
                             "youtube-music.play" {:ok true :playing {:title "Song" :artist "Band"} :count 3}
                             "volume" {:ok false :error "level must be 0 to 100"}
                             "late" (do (Thread/sleep 1000) {:ok true})
                             "mute" nil)))
        config (config-of socket)]
    (try
      (testing "what it answers, without its ok"
        (is (= {:state "playing" :title "Song" :position-s 12 :duration-s 200} (player/request! config "status")))
        (is (= {:command "status"} (last @requests))))
      (testing "the command and its arguments, in lowerCamelCase"
        (is (= {:playing {:title "Song" :artist "Band"} :count 3}
               (player/request! config "youtube-music.play" {:query "a song" :auth {:access-token "at" :expires-at 1}})))
        (is (str/includes? (last @lines) "\"accessToken\":\"at\""))
        (is (str/includes? (last @lines) "\"command\":\"youtube-music.play\"")))
      (testing "what it refuses, saying why"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^level must be 0 to 100$"
                              (player/request! config "volume" {:level 200}))))
      (testing "no answer"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gave no answer" (player/request! config "mute"))))
      (testing "too slow"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"took too long" (player/request! config "late" nil 100))))
      (finally (stop!)))))

(deftest without-a-player
  (testing "none set"
    (is (not (player/there? {:music {:socket nil}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"grog has no player here \(music\.socket\)"
                          (player/request! {:music {:socket nil}} "status"))))
  (testing "not running"
    (let [config (config-of (str (fs/path (h/temp-dir) "none.sock")))]
      (is (player/there? config))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"grog's player isn't running"
                            (player/request! config "status"))))))
