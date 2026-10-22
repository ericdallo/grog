(ns grog.music
  "grog's music (plans/integrations.md, music and targets): the `music`
  tool, a capability tool, and what's behind it, its providers, which are
  integrations, or come with them. A provider:

    :id :title    what it is: its id (the `music.provider` setting names
                  it) and its name
    :integration  the id of the integration it comes with, or nil
    :available?   (fn [config]): whether it can play now
    :connected?   (fn [config]): whether the user's account is connected
                  to it
    :actions      what it does, of `actions`
    :params       optional: the tool's arguments it takes, besides the
                  action (all without)
    :note         optional, (fn [config]): what the model should know of
                  it, or nil
    :run!         (fn [ctx action args]): does `action`, what to answer

  Two kinds: grog's own, playing through its player (grog.player):
  YouTube Music (grog.youtube-music), which plays without an account and
  with it the user's playlists and liked songs; and the connected MCP
  servers whose shipped entries map `music` in their capabilities, as
  `lights` is (grog.capabilities): play {query kind}, pause, resume, next,
  previous, stop, volume {level change}, status.

  What plays: the provider the `music.provider` setting names, when it's
  there; else the first connected; else the first there.

  While someone talks to grog (grog-voice heard its name, and until it
  waits for it again), its player's music goes down (`watch-voice!`).
  Never logged: what's asked or played."
  (:require [clojure.string :as str]
            [grog.capabilities :as capabilities]
            [grog.catalog :as catalog]
            [grog.log :as log]
            [grog.player :as player]
            [grog.youtube-music :as youtube-music]))

(def actions
  "What can be asked of music, in the order the model sees it."
  ["play" "pause" "resume" "stop" "next" "previous" "volume" "status"])

(def kinds
  "What can be played."
  ["song" "album" "playlist" "artist" "my-playlist" "liked"])

;; What only plays with the user's account.
(def ^:private library-kinds #{"my-playlist" "liked"})

;; How long ducking and pausing wait for the player: they're for now.
(def ^:private quick-timeout-ms 2000)

(def ^:private properties
  "What the model can say with `music`, by argument, in order."
  [["query" {"type" "string"
             "description" (str "What to play: a song, an artist, an album, a playlist, or one of the user's "
                                "playlists, as the user said it.")}]
   ["kind" {"type" "string" "enum" kinds
            "description" (str "What the query is, if known: my-playlist is one of the user's own playlists; "
                               "liked, their liked songs (no query needed).")}]
   ["shuffle" {"type" "boolean" "description" "Shuffled."}]
   ["level" {"type" "integer" "minimum" 0 "maximum" 100 "description" "The volume, 0 to 100."}]
   ["change" {"type" "integer" "description" "Turns the volume up (positive) or down (negative) by this much."}]])

(defn ^:private blank? [v]
  (or (nil? v) (and (string? v) (str/blank? v))))

(defn ^:private clock
  "`s` seconds as 3:05."
  [s]
  (let [s (long s)]
    (format "%d:%02d" (quot s 60) (mod s 60))))

(defn ^:private track
  "What plays, in words: \"Song by Artist\"."
  [{:keys [title artist]}]
  (str (if (blank? title) "something" title) (when-not (blank? artist) (str " by " artist))))

(defn status-text
  "The player's `status`, in words."
  [{:keys [state album position-s duration-s volume index count] :as status}]
  (if (= "idle" state)
    (str "Nothing is playing" (when volume (str " (volume " volume ")")) ".")
    (str (if (= "paused" state) "Paused: " "Playing ")
         (track status)
         (when-not (blank? album) (str ", from " album))
         (when (and (number? position-s) (number? duration-s) (pos? duration-s))
           (str " (" (clock position-s) " of " (clock duration-s) ")"))
         (when (and (number? index) (number? count) (> count 1))
           (str ", " (inc index) " of " count))
         (when volume (str ", volume " volume))
         ".")))

(defn said
  "What the player's `reply` to `action` says, in words."
  [action {:keys [playing count volume] :as reply}]
  (case action
    "play" (if playing
             (str "Playing " (track playing)
                  (when (and (number? count) (> count 1)) (str ", then " (dec count) " more"))
                  ".")
             (status-text reply))
    "pause" "Paused."
    "stop" "Stopped."
    "volume" (str "Volume " volume ".")
    (status-text reply)))

(defn ^:private volume-args
  "What the player's volume takes from `args`: {:level} or {:change}."
  [{:keys [level change]}]
  (cond
    (and (number? level) (<= 0 level 100)) {:level (long level)}
    (and (number? change) (not (zero? change))) {:change (long change)}
    :else (throw (ex-info "say a level (0 to 100), or a change (up, positive; down, negative)" {:type ::bad-args}))))

(defn ^:private run-player!
  "Does `action` through grog's player, as it does for anything it plays:
  what to answer."
  [config action args]
  (said action (if (= "volume" action)
                 (player/request! config "volume" (volume-args args))
                 (player/request! config action))))

(defn ^:private auth
  "The YouTube Music account's tokens for the player, or nil when it isn't
  connected. For what's the user's own (`library?`), it must be."
  [config library?]
  (if library?
    (or (youtube-music/auth! config)
        (throw (ex-info (str "the user's playlists and liked songs need their YouTube Music account connected: "
                             "ask whether to connect youtube-music")
                        {:type ::not-connected})))
    (try (youtube-music/auth! config)
         (catch clojure.lang.ExceptionInfo _ nil))))

(defn ^:private play-youtube-music!
  "Plays what `args` say on YouTube Music: what to answer."
  [config {:keys [query kind shuffle] :as args}]
  (let [library? (contains? library-kinds kind)
        query (when-not (blank? query) (str/trim query))]
    (cond
      (and (nil? query) (not library?)) (run-player! config "resume" args)
      (and (nil? query) (= "my-playlist" kind)) (throw (ex-info "say which of the user's playlists" {:type ::bad-args}))
      :else (let [token (auth config library?)]
              (said "play" (player/request! config "youtube-music.play"
                                            (cond-> {}
                                              query (assoc :query query)
                                              (contains? (set kinds) kind) (assoc :kind kind)
                                              (boolean? shuffle) (assoc :shuffle shuffle)
                                              token (assoc :auth token))))))))

(def youtube-music
  "YouTube Music, through grog's player: anonymous, or with the user's
  account connected."
  {:id youtube-music/id
   :title "YouTube Music"
   :integration youtube-music/id
   :available? player/there?
   :connected? (fn [config] (and (player/there? config) (youtube-music/connected? config)))
   :actions (set actions)
   :note (fn [config]
           (when-not (youtube-music/connected? config)
             (str "The user's own playlists and liked songs need their YouTube Music account connected "
                  "(connect youtube-music); the rest plays without it.")))
   :run! (fn [ctx action args]
           (let [config @(:config ctx)]
             (if (= "play" action)
               (play-youtube-music! config args)
               (run-player! config action args))))})

(defn ^:private catalog-provider
  "The provider an MCP server mapping `music` is (`source`, see
  grog.capabilities/sources)."
  [{:keys [id entry mapping] :as source}]
  {:id id
   :title (catalog/title entry)
   :integration id
   :available? (constantly true)
   :connected? (constantly true)
   :actions (set (keys mapping))
   :params (capabilities/params-of mapping)
   :run! (fn [ctx _ args] (capabilities/call! ctx source "music" args))})

(defn providers
  "What can play music with `config`, in order: grog's own, then the
  connected MCP servers that can (their shipped entries map at least
  `play` to a tool they have)."
  [config]
  (into [youtube-music]
        (map catalog-provider)
        (capabilities/sources config "music" actions ["play"])))

(defn available
  "The providers there with `config`, in order."
  [config]
  (filterv #((:available? %) config) (providers config)))

(defn chosen
  "What plays with `config`: the provider `music.provider` names, when it's
  there; else the first connected; else the first there; nil without any."
  [config]
  (let [there (available config)
        wanted (get-in config [:music :provider])]
    (or (some #(when (= wanted (:id %)) %) there)
        (some #(when ((:connected? %) config) %) there)
        (first there))))

(defn ^:private normalize
  "The tool's (or the API's) `args`, as providers take them."
  [{:keys [action query kind shuffle level change]}]
  (cond-> {:action (some-> action str)}
    (string? query) (assoc :query query)
    (string? kind) (assoc :kind kind)
    (boolean? shuffle) (assoc :shuffle shuffle)
    (number? level) (assoc :level (long level))
    (number? change) (assoc :change (long change))))

(defn perform!
  "Does what `args` ask ({:action :query :kind :shuffle :level :change})
  through the provider chosen: what to answer. Throws ex-info saying why
  it can't."
  [ctx args]
  (let [{:keys [action] :as args} (normalize args)
        {:keys [title run!] :as provider} (chosen @(:config ctx))]
    (when-not provider
      (throw (ex-info "nothing plays music here: grog has no player (music.socket), and nothing connected plays it"
                      {:type ::none})))
    (when-not (contains? (:actions provider) action)
      (throw (ex-info (str "music can't " (or action "do that") " through " title) {:type ::unsupported})))
    (run! ctx action args)))

(defn detail
  "What a `music` call does, in a few words, never what was asked for:
  \"play\", \"volume 40\"."
  [{:keys [action kind shuffle level change]}]
  (case action
    "play" (str/join ", " (remove nil? ["play" (when (contains? library-kinds kind) kind)
                                        (when (true? shuffle) "shuffled")]))
    "volume" (cond (number? level) (str "volume " level)
                   (and (number? change) (pos? change)) (str "volume +" change)
                   (number? change) (str "volume " change)
                   :else "volume")
    action))

(defn ^:private parameters
  "The schema of `music` for `provider`: its actions, and what it takes."
  [{provided :actions :keys [params]}]
  {"type" "object"
   "properties" (into {"action" {"type" "string" "enum" (filterv provided actions)}}
                      (filter #(or (nil? params) (contains? params (first %))))
                      properties)
   "required" ["action"]
   "additionalProperties" false})

(defn ^:private description [config {:keys [title note]}]
  (str "Plays music through " title " when the user asks: a song, an artist, an album or a playlist; pauses, "
       "resumes, skips to the next or previous one, stops it, sets its volume (0 to 100, or up or down by a "
       "change), or tells what's playing. "
       (when note (note config))))

(defn tool
  "The `music` tool, through the provider chosen with `config`, or nil
  without any."
  [config]
  (when-let [provider (chosen config)]
    {:name "music"
     :description (str/trimr (description config provider))
     :parameters (parameters provider)
     :changes? (fn [args] (not= "status" (:action args)))
     :open-world? false
     :detail detail
     :run (fn [ctx args _turn] (perform! ctx args))}))

(defn provider-ids
  "The ids of what can play music with `config`."
  [config]
  (mapv :id (available config)))

(defn pause!
  "Pauses grog's player, if there's one, in the background, whatever goes
  wrong: someone touched grog's face to stop it."
  [config]
  (when (player/there? config)
    (future
      (try (player/request! config "pause" nil quick-timeout-ms)
           (catch Exception _ nil)))))

;; grog-voice's states while someone talks to grog: hearing them, after its
;; name, waiting for them, and answering.
(def ^:private ducking-states #{"hearing" "awake" "asked" "answering" "speaking"})

(defn ducks?
  "Whether the music goes down while grog-voice is in `voice-state`."
  [voice-state]
  (contains? ducking-states voice-state))

(defn ^:private duck!
  "Takes the player's music down (`down?`) or back, unless it was
  already (`sent`): whether it was then."
  [config down? sent]
  (or (= down? sent)
      (try
        (player/request! config (if down? "duck" "unduck") nil quick-timeout-ms)
        true
        (catch Exception e
          (when-not (= :grog.player/unreachable (:type (ex-data e)))
            (log/info "music: ducking failed:" (or (ex-message e) (.getSimpleName (class e)))))
          false))))

(defn watch-voice!
  "Takes grog's player's music down while someone talks to grog, as
  grog-voice says (grog.events), and back once it waits for its name
  again: in the background, one change at a time, the last one winning."
  [ctx]
  (let [want (atom nil)
        sent (atom false)
        lock (Object.)]
    (add-watch (:state (:events ctx)) ::ducking
               (fn [_ _ before after]
                 (let [down? (ducks? (get-in after [:voice :state]))
                       config @(:config ctx)]
                   (when (and (not= down? (ducks? (get-in before [:voice :state])))
                              (player/there? config))
                     (reset! want down?)
                     (future
                       (locking lock
                         (let [down? @want]
                           (when (duck! config down? @sent)
                             (reset! sent down?)))))))))))

(defn unwatch-voice!
  "Stops what `watch-voice!` started."
  [ctx]
  (remove-watch (:state (:events ctx)) ::ducking))
