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
    :by-id?       optional: whether it plays exactly what an :id (with
                  its :kind) names, from its library
    :library!     optional, (fn [ctx kind]): the user's playlists or liked
                  songs (`library-lists`), for screens to choose from

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
  grog-voice is told whether its player plays (`check-playing!`), as it
  listens for its name differently over music, and whoever watches grog
  what it plays (grog.events' :music, for its skin's controls): after
  each action, and now and then (`keep-up!`). Never logged: what's asked
  or played."
  (:require [clojure.string :as str]
            [grog.capabilities :as capabilities]
            [grog.catalog :as catalog]
            [grog.events :as events]
            [grog.log :as log]
            [grog.player :as player]
            [grog.voice :as voice]
            [grog.youtube-music :as youtube-music]))

(def actions
  "What can be asked of music, in the order the model sees it."
  ["play" "pause" "resume" "stop" "next" "previous" "volume" "status"])

(def kinds
  "What can be played."
  ["song" "album" "playlist" "artist" "my-playlist" "liked"])

;; What only plays with the user's account.
(def ^:private library-kinds #{"my-playlist" "liked"})

(def id-kinds
  "What an id can name, to play exactly that: a song, an album, a playlist,
  or one of the user's playlists."
  #{"song" "album" "playlist" "my-playlist"})

(def library-lists
  "What of the user's library screens can list."
  ["playlists" "liked"])

;; How long ducking, pausing and asking whether it plays wait for the
;; player: they're for now.
(def ^:private quick-timeout-ms 2000)

;; How long playing waits to hear that the player started (its clock moving)
;; or failed, asking every so often: YouTube's audio takes yt-dlp a second
;; or two to find. Past it, it's taken as playing.
(def ^:private start-wait-ms 4000)
(def ^:private start-poll-ms 250)

;; How often grog asks its player whether it plays: often while it was
;; last seen playing (the queue ends, someone pauses it), now and then
;; otherwise (something played it without grog, like the CLI's raw player
;; commands).
(def ^:private playing-check-ms 5000)
(def ^:private idle-check-ms 60000)

;; One check at a time, so that an older status never wins over a newer one.
(def ^:private checking (Object.))

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

(defn ^:private not-started
  "Why what grog's player was given at `asked` (epoch ms) doesn't play, as
  it says within `start-wait-ms`: an entry failed, and nothing else plays.
  nil once its clock moves, or when it didn't say in time."
  [config asked]
  (loop []
    (let [{:keys [state error error-at position-s]}
          (try (player/request! config "status" nil quick-timeout-ms)
               (catch Exception _ nil))
          playing (= "playing" state)]
      (cond
        (and (string? error) (number? error-at) (>= error-at asked) (not playing)) error
        (and playing (number? position-s) (pos? position-s)) nil
        (>= (- (System/currentTimeMillis) asked) start-wait-ms) nil
        :else (do (Thread/sleep (long start-poll-ms)) (recur))))))

(defn ^:private play-youtube-music!
  "Plays what `args` say on YouTube Music, a query or exactly what an id
  names: what to answer, once it plays (see `not-started`); ex-info saying
  why when it doesn't."
  [config {:keys [query kind shuffle id] :as args}]
  (let [library? (contains? library-kinds kind)
        query (when-not (or id (blank? query)) (str/trim query))]
    (cond
      (and id (not (contains? id-kinds kind)))
      (throw (ex-info "an id plays a song, an album, a playlist or one of the user's playlists: say which (kind)"
                      {:type ::bad-args}))

      (and (nil? id) (nil? query) (not library?)) (run-player! config "resume" args)
      (and (nil? id) (nil? query) (= "my-playlist" kind)) (throw (ex-info "say which of the user's playlists"
                                                                          {:type ::bad-args}))
      :else (let [token (auth config library?)
                  asked (System/currentTimeMillis)
                  reply (player/request! config "youtube-music.play"
                                         (cond-> {}
                                           id (assoc :id id)
                                           query (assoc :query query)
                                           (contains? (set kinds) kind) (assoc :kind kind)
                                           (boolean? shuffle) (assoc :shuffle shuffle)
                                           token (assoc :auth token)))]
              (when-let [why (not-started config asked)]
                (throw (ex-info why {:type ::not-playing})))
              (said "play" reply)))))

(defn ^:private library-youtube-music!
  "The user's `kind` of `library-lists` on YouTube Music, through grog's
  player, with their account: [{:kind :id :title :artist :count}]."
  [config kind]
  (let [token (try (youtube-music/auth! config)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :grog.youtube-music/signed-out (:type (ex-data e)))
                       nil
                       (throw e))))]
    (when-not token
      (throw (ex-info "connect YouTube Music to see your playlists"
                      {:type ::not-connected :connect youtube-music/id})))
    (mapv #(select-keys % [:kind :id :title :artist :count])
          (:items (player/request! config "youtube-music.library" {:kind kind :auth token})))))

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
               (run-player! config action args))))
   :by-id? true
   :library! (fn [ctx kind] (library-youtube-music! @(:config ctx) kind))})

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
  [{:keys [action query kind shuffle level change id]}]
  (cond-> {:action (some-> action str)}
    (string? query) (assoc :query query)
    (and (string? id) (not (str/blank? id))) (assoc :id (str/trim id))
    (string? kind) (assoc :kind kind)
    (boolean? shuffle) (assoc :shuffle shuffle)
    (number? level) (assoc :level (long level))
    (number? change) (assoc :change (long change))))

(defn playing?
  "Whether the player's `status` says it plays: paused or idle, it doesn't."
  [status]
  (= "playing" (:state status)))

(def ^:private song-keys
  [:state :title :artist :album :artwork :position-s :duration-s :volume :ducked :index :count :error :error-at])

(defn slice
  "What whoever watches grog sees of its player's `status`, asked `at`
  (grog.events' :music): nil without one; no song while it's idle, but why
  the last one couldn't play (:error, at :error-at), if it couldn't."
  [status at]
  (when (map? status)
    (assoc (into {} (remove (comp nil? val))
                 (select-keys status (if (= "idle" (:state status)) [:state :volume :ducked :error :error-at] song-keys)))
           :provider youtube-music/id
           :at at)))

(defn publish?
  "Whether whoever watches grog is told of the player's `slice`, `shown`
  being what they were last told: when anything but where the song is
  changed, and while it plays, at most every 5 s (they move its clock
  themselves in between)."
  [shown slice]
  (let [without #(dissoc % :position-s :at)]
    (boolean
     (or (not= (some-> shown without) (some-> slice without))
         (and (playing? slice)
              (not= (:position-s shown) (:position-s slice))
              (>= (- (:at slice) (:at shown)) playing-check-ms))))))

(defn check-playing!
  "Asks grog's player what it plays, tells grog-voice whether it plays when
  that changed (grog.voice), and whoever watches grog what it plays when
  they should know (`publish?`): whether it plays. It doesn't without a
  player, or when the player doesn't answer soon."
  [ctx]
  (locking checking
    (let [config @(:config ctx)
          at (events/now-ms)
          status (when (player/there? config)
                   (try (player/request! config "status" nil quick-timeout-ms)
                        (catch Exception _ nil)))
          music (slice status at)
          playing (playing? status)]
      (voice/set-music! (:voice ctx) playing)
      (when (publish? (some-> (:events ctx) :state deref :music) music)
        (events/publish! (:events ctx) {:type :music :music music}))
      playing)))

(defn ^:private check-soon!
  "`check-playing!`, in the background."
  [ctx]
  (future
    (try (check-playing! ctx)
         (catch Exception e
           (log/info "music: checking whether it plays failed:" (.getSimpleName (class e)))))))

(defn check-due?
  "Whether to ask the player again whether it plays, `since-ms` after it
  was last asked: soon while it was last seen `playing?`, now and then
  otherwise."
  [playing? since-ms]
  (>= since-ms (if playing? playing-check-ms idle-check-ms)))

(defn keep-up!
  "Asks grog's player whether it plays when it's due (`check-due?`), it was
  last asked at `last-checked` (an atom of epoch milliseconds), now being
  `now-ms`."
  [ctx last-checked now-ms]
  (when (check-due? (voice/music (:voice ctx)) (- now-ms @last-checked))
    (reset! last-checked now-ms)
    (check-playing! ctx)))

(defn ^:private provider!
  "The provider chosen with `config`, or ex-info when there's none."
  [config]
  (or (chosen config)
      (throw (ex-info "nothing plays music here: grog has no player (music.socket), and nothing connected plays it"
                      {:type ::none}))))

(defn perform!
  "Does what `args` ask ({:action :query :kind :shuffle :level :change
  :id}) through the provider chosen: what to answer. Throws ex-info saying
  why it can't. Then, in the background, whether grog's player plays."
  [ctx args]
  (let [{:keys [action id] :as args} (normalize args)
        {:keys [title run! by-id?] :as provider} (provider! @(:config ctx))]
    (when-not (contains? (:actions provider) action)
      (throw (ex-info (str "music can't " (or action "do that") " through " title) {:type ::unsupported})))
    (when (and id (not by-id?))
      (throw (ex-info (str title " doesn't play by id") {:type ::unsupported})))
    (try (run! ctx action args)
         (finally (check-soon! ctx)))))

(defn library!
  "The user's `kind` of `library-lists` (their playlists, or liked songs)
  through the provider chosen: [{:kind :id :title :artist :count}]. Throws
  ex-info saying why it can't: its :type ::none without a provider,
  ::unsupported when it has no library, ::not-connected (with the
  integration to :connect) without the user's account, or the player's."
  [ctx kind]
  (let [{:keys [title library!]} (provider! @(:config ctx))]
    (when-not library!
      (throw (ex-info (str "grog can't show " title "'s library") {:type ::unsupported})))
    (library! ctx kind)))

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
     :detail detail
     :run (fn [ctx args _turn] (perform! ctx args))}))

(defn provider-ids
  "The ids of what can play music with `config`."
  [config]
  (mapv :id (available config)))

(defn pause!
  "Pauses grog's player, if there's one, in the background, whatever goes
  wrong, then checks whether it plays: someone touched grog's face to stop
  it."
  [ctx]
  (let [config @(:config ctx)]
    (when (player/there? config)
      (future
        (try (player/request! config "pause" nil quick-timeout-ms)
             (catch Exception _ nil))
        (check-playing! ctx)))))

;; grog-voice's states while someone talks to grog: hearing them, after its
;; name, waiting for them, and answering; and while it says a timer going
;; off.
(def ^:private ducking-states #{"hearing" "awake" "asked" "answering" "speaking" "announcing"})

(defn ducks?
  "Whether the music goes down while grog-voice is in `voice-state`."
  [voice-state]
  (contains? ducking-states voice-state))

(defn ^:private duck!
  "Takes the player's music down (`down?`), unless it was already
  (`sent`), or back, whatever was sent: a duck that timed out may still
  have gone through. Whether it was then."
  [config down? sent]
  (or (and down? sent)
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
