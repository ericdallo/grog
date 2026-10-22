(ns grog.music-test
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.actions :as actions]
            [grog.assistant :as assistant]
            [grog.catalog :as catalog]
            [grog.client :as client]
            [grog.config :as config]
            [grog.connections :as connections]
            [grog.events :as events]
            [grog.music :as music]
            [grog.oauth :as oauth]
            [grog.registry :as registry]
            [grog.server :as server]
            [grog.servers :as servers]
            [grog.settings :as settings]
            [grog.test-helpers :as h]
            [grog.tools :as tools]
            [grog.youtube-music :as youtube-music]))

(defn ^:private settings-of [socket provider]
  (-> config/defaults
      (assoc :name "Jarvis" :state-dir (h/temp-dir) :music {:socket socket :provider provider})
      (update :integrations merge {:host "127.0.0.1" :port 0})))

(defn ^:private context
  ([socket] (context socket nil))
  ([socket provider] (assistant/new-context (settings-of socket provider))))

(def ^:private now-playing
  {:state "playing" :title "Song" :artist "Band" :album "LP" :position-s 83 :duration-s 225 :volume 60
   :ducked false :index 2 :count 50})

(defn ^:private answer [{:keys [command level change]}]
  (case command
    "youtube-music.play" (assoc now-playing :ok true :playing {:title "Song" :artist "Band"} :index 0)
    "pause" (assoc now-playing :ok true :state "paused")
    "volume" (assoc now-playing :ok true :volume (or level (+ 60 change)))
    "stop" {:ok true :state "idle" :volume 60}
    (assoc now-playing :ok true)))

(defn ^:private with-player [f]
  (let [player (h/start-player! answer)]
    (try (f player)
         (finally ((:stop! player))))))

(defn ^:private connect-account!
  "The YouTube Music account, connected, its tokens good until `expires-at`."
  [ctx expires-at]
  (connections/save! (config/state-dir @(:config ctx)) {:id "youtube-music" :integration "youtube-music" :name "greg@example.com"}
                     {:access-token "at-1" :refresh-token "rt-1" :expires-at expires-at :scope youtube-music/scope
                      :token-type "Bearer" :client-id "cid" :client-secret "cs" :way "google" :email "greg@example.com"}))

(def ^:private fake-client
  {:state (atom {:server-info {:name "spot" :version "1"}})})

(def ^:private spot-mapping
  {:play {:tool "play_music" :args {:q "{query}" :type "{kind}" :device "grog"}}
   :pause {:tool "pause_music" :args {}}
   :volume {:tool "set_volume" :args {:volume_percent "{level}"}}})

(def ^:private spot
  (-> (registry/url-entry "https://spot.example.com/mcp")
      (assoc :title "Spot")
      (assoc-in [:_meta catalog/meta-key] {:id "spot" :capabilities {:music spot-mapping}})))

(defn ^:private connect-spot! [ctx tool-names]
  (let [config @(:config ctx)]
    (connections/save! (config/state-dir config) {:id "spot" :integration "spot" :name "spot"} {:values []})
    (servers/save-tools! config "spot" fake-client (mapv (fn [n] {:name n :inputSchema {:type "object"}}) tool-names))))

(defn ^:private run [ctx args]
  ((:run (music/tool @(:config ctx))) ctx args {:id "c" :n 1}))

(defn ^:private commands [player]
  (mapv :command @(:requests player)))

(deftest which-plays
  (with-redefs [catalog/shipped (constantly [spot])]
    (testing "nothing, without a player or anything connected that plays"
      (let [ctx (context nil)]
        (is (nil? (music/chosen @(:config ctx))))
        (is (nil? (music/tool @(:config ctx))))
        (is (not-any? #{"music"} (map :name (tools/available @(:config ctx)))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing plays music here"
                              (music/perform! ctx {:action "status"})))))
    (testing "with a player, YouTube Music, without an account"
      (let [ctx (context "/run/music.sock")]
        (is (= "youtube-music" (:id (music/chosen @(:config ctx)))))
        (is (= ["youtube-music"] (music/provider-ids @(:config ctx))))
        (testing "before lights, after the machine's tools"
          (let [names (mapv :name (tools/available @(:config ctx)))]
            (is (some #{"music"} names))
            (is (< (.indexOf names "music") (.indexOf names "connect")))))))
    (testing "the first connected: a server mapping music, over YouTube Music without an account"
      (let [ctx (context "/run/music.sock")]
        (connect-spot! ctx ["play_music" "pause_music" "set_volume"])
        (is (= ["youtube-music" "spot"] (music/provider-ids @(:config ctx))))
        (is (= "spot" (:id (music/chosen @(:config ctx)))))
        (testing "and YouTube Music with it, first"
          (connect-account! ctx (+ (System/currentTimeMillis) 3600000))
          (is (= "youtube-music" (:id (music/chosen @(:config ctx))))))))
    (testing "the one the user chose, when it's there"
      (let [ctx (context "/run/music.sock" "spot")]
        (connect-account! ctx (+ (System/currentTimeMillis) 3600000))
        (connect-spot! ctx ["play_music"])
        (is (= "spot" (:id (music/chosen @(:config ctx))))))
      (let [ctx (context "/run/music.sock" "nothing-like-it")]
        (is (= "youtube-music" (:id (music/chosen @(:config ctx)))))))
    (testing "a server without the tool its play maps to doesn't"
      (let [ctx (context nil)]
        (connect-spot! ctx ["pause_music"])
        (is (nil? (music/chosen @(:config ctx))))))))

(deftest the-tool
  (let [ctx (context "/run/music.sock")
        {:keys [description parameters changes? open-world? detail]} (music/tool @(:config ctx))]
    (is (str/starts-with? description "Plays music through YouTube Music when the user asks"))
    (is (str/includes? description "need their YouTube Music account connected (connect youtube-music)"))
    (is (= music/actions (get-in parameters ["properties" "action" "enum"])))
    (is (= #{"action" "query" "kind" "shuffle" "level" "change"} (set (keys (parameters "properties")))))
    (is (= ["song" "album" "playlist" "artist" "my-playlist" "liked"] (get-in parameters ["properties" "kind" "enum"])))
    (is (= ["action"] (parameters "required")))
    (is (false? (parameters "additionalProperties")))
    (is (false? open-world?))
    (testing "changes things, unless it tells what's playing"
      (is (every? #(changes? {:action %}) (remove #{"status"} music/actions)))
      (is (not (changes? {:action "status"}))))
    (testing "its detail never says what was asked for"
      (is (= "play" (detail {:action "play" :query "the secret song" :kind "song"})))
      (is (= "play, liked, shuffled" (detail {:action "play" :kind "liked" :shuffle true})))
      (is (= "volume 40" (detail {:action "volume" :level 40})))
      (is (= "volume +10" (detail {:action "volume" :change 10})))
      (is (= "volume -10" (detail {:action "volume" :change -10})))
      (is (= "pause" (detail {:action "pause"}))))
    (testing "once the account is connected, no word of it"
      (connect-account! ctx (+ (System/currentTimeMillis) 3600000))
      (is (not (str/includes? (:description (music/tool @(:config ctx))) "account"))))))

(deftest youtube-music-plays
  (with-player
    (fn [player]
      (let [ctx (context (:socket player))]
        (testing "what the user asks for, anonymously"
          (is (= "Playing Song by Band, then 49 more." (run ctx {:action "play" :query " a song " :kind "song" :shuffle true})))
          (is (= {:command "youtube-music.play" :query "a song" :kind "song" :shuffle true} (last @(:requests player)))))
        (testing "the user's own, only with their account"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"need their YouTube Music account connected"
                                (run ctx {:action "play" :kind "liked"})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"need their YouTube Music account connected"
                                (run ctx {:action "play" :kind "my-playlist" :query "road trip"})))
          (is (= 1 (count @(:requests player))) "asked nothing of the player"))
        (testing "play without what: it goes on"
          (is (= "Playing Song by Band, from LP (1:23 of 3:45), 3 of 50, volume 60." (run ctx {:action "play"})))
          (is (= "resume" (last (commands player)))))
        (testing "the rest, through the player"
          (is (= "Paused." (run ctx {:action "pause"})))
          (is (= "Playing Song by Band, from LP (1:23 of 3:45), 3 of 50, volume 60." (run ctx {:action "next"})))
          (is (= "Stopped." (run ctx {:action "stop"})))
          (is (= "Volume 40." (run ctx {:action "volume" :level 40.0})))
          (is (= {:command "volume" :level 40} (last @(:requests player))))
          (is (= "Volume 50." (run ctx {:action "volume" :change -10})))
          (is (= {:command "volume" :change -10} (last @(:requests player))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"say a level" (run ctx {:action "volume"})))
          (is (= "Playing Song by Band, from LP (1:23 of 3:45), 3 of 50, volume 60." (run ctx {:action "status"})))
          (is (= ["pause" "next" "stop" "volume" "volume" "status"] (subvec (commands player) 2))))))))

(deftest with-the-account
  (with-player
    (fn [player]
      (let [ctx (context (:socket player))
            refused (atom false)
            google (h/start-handler!
                    (fn [req]
                      (let [{:keys [grant_type refresh_token client_id client_secret]} (oauth/form-decode (slurp (:body req)))]
                        (if (and (not @refused) (= ["refresh_token" "rt-1" "cid" "cs"]
                                                   [grant_type refresh_token client_id client_secret]))
                          (h/json-response {:access_token "at-2" :expires_in 3599 :token_type "Bearer"})
                          {:status 400 :body "{\"error\": \"invalid_grant\"}"}))))]
        (try
          (with-redefs [youtube-music/endpoints {:token (str (:url google) "/token")}]
            (testing "its tokens go with what's played"
              (connect-account! ctx (+ (System/currentTimeMillis) 3600000))
              (run ctx {:action "play" :kind "liked" :shuffle false})
              (is (= {:command "youtube-music.play" :kind "liked" :shuffle false
                      :auth {:access-token "at-1" :refresh-token "rt-1" :client-id "cid" :client-secret "cs"
                             :scope youtube-music/scope :token-type "Bearer"}}
                     (update (last @(:requests player)) :auth dissoc :expires-at)))
              (is (str/includes? (last @(:lines player)) "\"refreshToken\":\"rt-1\"")))
            (testing "refreshed first when they expire within a minute"
              (connect-account! ctx (+ (System/currentTimeMillis) 30000))
              (run ctx {:action "play" :query "a song"})
              (let [{:keys [access-token refresh-token expires-at]} (:auth (last @(:requests player)))]
                (is (= ["at-2" "rt-1"] [access-token refresh-token]))
                (is (> expires-at (+ (System/currentTimeMillis) 3000000))))
              (is (= "at-2" (:access-token (connections/secrets (config/state-dir @(:config ctx)) "youtube-music")))))
            (testing "refused: signed out, and what isn't the user's plays without it"
              (reset! refused true)
              (connect-account! ctx (+ (System/currentTimeMillis) 30000))
              (run ctx {:action "play" :query "a song"})
              (is (not (contains? (last @(:requests player)) :auth)))
              (is (= "signed-out" (:status (connections/by-id (config/state-dir @(:config ctx)) "youtube-music"))))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"need their YouTube Music account connected"
                                    (run ctx {:action "play" :kind "liked"})))))
          (finally ((:stop! google))))))))

(deftest through-a-connected-server
  (with-redefs [catalog/shipped (constantly [spot])]
    (let [ctx (context nil)
          _ (connect-spot! ctx ["play_music" "pause_music" "set_volume"])
          {:keys [description parameters]} (music/tool @(:config ctx))
          calls (atom [])]
      (is (str/starts-with? description "Plays music through Spot when"))
      (is (= ["play" "pause" "volume"] (get-in parameters ["properties" "action" "enum"])))
      (is (= #{"action" "query" "kind" "level"} (set (keys (parameters "properties")))) "what its templates use")
      (with-redefs [servers/call! (fn [_ id tool args]
                                    (swap! calls conj [id tool args])
                                    {:content [{:type "text" :text "ok"}]})]
        (is (= "ok" (run ctx {:action "play" :query "a song" :kind "song"})))
        (is (= "ok" (run ctx {:action "volume" :level 30})))
        (is (= "ok" (run ctx {:action "pause"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"music can't next through Spot"
                              (run ctx {:action "next"}))))
      (is (= [["spot" "play_music" {:q "a song" :type "song" :device "grog"}]
              ["spot" "set_volume" {:volume_percent 30}]
              ["spot" "pause_music" {}]]
             @calls)))))

(deftest ducking
  (with-player
    (fn [player]
      (let [ctx (context (:socket player))
            voice! #(events/publish! (:events ctx) {:type :voice :state % :conversation "c"})
            sent #(filterv #{"duck" "unduck"} (commands player))]
        (music/watch-voice! ctx)
        (try
          (voice! "listening")
          (Thread/sleep 100)
          (is (= [] (sent)) "nothing while it waits for its name")
          (testing "down once grog-voice hears someone talk to grog"
            (voice! "hearing")
            (is (h/eventually 2000 #(= ["duck"] (sent))))
            (is (= {:command "duck"} (last @(:requests player)))))
          (testing "while grog answers, still down"
            (voice! "answering")
            (voice! "speaking")
            (Thread/sleep 100)
            (is (= ["duck"] (sent))))
          (testing "back once it waits for its name again"
            (voice! "listening")
            (is (h/eventually 2000 #(= ["duck" "unduck"] (sent)))))
          (testing "after its name alone, too"
            (voice! "awake")
            (is (h/eventually 2000 #(= ["duck" "unduck" "duck"] (sent))))
            (voice! "muted")
            (is (h/eventually 2000 #(= ["duck" "unduck" "duck" "unduck"] (sent)))))
          (finally (music/unwatch-voice! ctx))))))
  (testing "nothing without a player, and nothing goes wrong"
    (let [ctx (context nil)]
      (music/watch-voice! ctx)
      (events/publish! (:events ctx) {:type :voice :state "hearing" :conversation "c"})
      (is (= "hearing" (get-in (events/snapshot (:events ctx)) [:voice :state])))
      (music/unwatch-voice! ctx))))

(deftest touching-its-face-pauses-the-music
  (with-player
    (fn [player]
      (let [ctx (context (:socket player))]
        (testing "without grog-voice too"
          (is (= {:ok true} (actions/perform! ctx "stop")))
          (is (h/eventually 2000 #(= ["pause"] (commands player))))))))
  (testing "without a player nor grog-voice, there's nothing to stop"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"its voice isn't there"
                          (actions/perform! (context nil) "stop")))))

(deftest chosen-by-asking
  (with-redefs [catalog/shipped (constantly [spot])
                settings/try-model! (fn [_ _])]
    (let [ctx (context "/run/music.sock")]
      (connect-spot! ctx ["play_music"])
      (is (= "spot" (:id (music/chosen @(:config ctx)))))
      (is (= "youtube-music" (:music_provider (settings/change! ctx {:music_provider "youtube-music"}))))
      (is (= "youtube-music" (:id (music/chosen @(:config ctx)))))
      (is (= "youtube-music" (get-in (settings/load-overrides @(:config ctx)) [:music :provider])) "kept")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing called \"tidal\" plays music here \(there's youtube-music, spot\)"
                            (settings/change! ctx {:music_provider "tidal"})))
      (settings/change! ctx {:music_provider nil})
      (is (= "spot" (:id (music/chosen @(:config ctx))))))))

(deftest the-cli-through-the-api
  (with-player
    (fn [player]
      (let [{:keys [port] :as running} (server/start! (-> (settings-of (:socket player) nil)
                                                          (assoc-in [:server :port] 0)))
            url (str "http://127.0.0.1:" port)]
        (try
          (is (= "Playing Song by Band, from LP (1:23 of 3:45), 3 of 50, volume 60.\n"
                 (with-out-str (is (true? (client/music! url []))))))
          (is (= "Playing Song by Band, then 49 more.\n" (with-out-str (client/music! url ["play" "a" "song"]))))
          (is (= {:command "youtube-music.play" :query "a song"} (last @(:requests player))))
          (is (= "Volume 50.\n" (with-out-str (client/music! url ["volume" "-10"]))))
          (is (= "Paused.\n" (with-out-str (client/music! url ["pause"]))))
          (is (= "[error] Say: grog music [play WHAT | pause | resume | next | previous | stop | volume N|+N|-N]\n"
                 (with-out-str (is (false? (client/music! url ["dance"]))))))
          (testing "the route answers what plays"
            (let [resp (http/post (str url "/v1/music")
                                  {:headers {"content-type" "application/json"}
                                   :body "{\"action\": \"stop\"}" :throw false})]
              (is (= {:provider "youtube-music" :text "Stopped."} (config/parse (:body resp)))))
            (let [resp (http/post (str url "/v1/music")
                                  {:headers {"content-type" "application/json"}
                                   :body "{\"action\": \"dance\"}" :throw false})]
              (is (= 400 (:status resp)))))
          (finally (server/stop! running)))))))

(deftest what-grog-music-asks
  (is (= {:action "status"} (client/music-request [])))
  (is (= {:action "play" :query "the beatles"} (client/music-request ["play" "the" "beatles"])))
  (is (= {:action "play"} (client/music-request ["play"])))
  (is (= {:action "next"} (client/music-request ["next"])))
  (is (= {:action "volume" :level 40} (client/music-request ["volume" "40"])))
  (is (= {:action "volume" :change 10} (client/music-request ["volume" "+10"])))
  (is (= {:action "volume" :change -5} (client/music-request ["volume" "-5"])))
  (is (nil? (client/music-request ["volume" "200"])))
  (is (nil? (client/music-request ["volume"])))
  (is (nil? (client/music-request ["pause" "now"]))))
