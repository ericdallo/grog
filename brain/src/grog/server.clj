(ns grog.server
  "The server: grog's HTTP API (grog.api) and, when enabled, an nREPL, with
  what runs in the background: pings to whoever watches what grog is up
  to, and to grog-voice, keeping what they know of its usage current, and of
  how an update goes (grog.update), a sign-in with a code going while
  grog has nothing to pay with, for its skin to show (grog.signin), and
  its music going down while someone talks to it (grog.music)."
  (:require [babashka.nrepl.server :as nrepl]
            [grog.api :as api]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.connect :as connect]
            [grog.credentials :as credentials]
            [grog.events :as events]
            [grog.log :as log]
            [grog.music :as music]
            [grog.provider :as provider]
            [grog.servers :as servers]
            [grog.signin :as signin]
            [grog.update :as update]
            [grog.usage :as usage]
            [grog.voice :as voice]
            [org.httpkit.server :as http]))

;; Context of the running server, handy from the nREPL, e.g.
;; (swap! (:config @current) assoc-in [:providers :openai :reasoning-effort] "medium")
(defonce current (atom nil))

;; How often watchers get a ping: it finds those gone, and keeps proxies from
;; closing the connection.
(def ^:private ping-ms 15000)

;; How old what grog knows of the subscription's limits gets before it asks
;; again: they're also used elsewhere (ChatGPT, Codex), and reset as time goes
;; by. It checks every minute, so that a sign-in, or a new day, shows soon.
(def ^:private quota-max-age-s (* 5 60))
(def ^:private usage-check-ms (* 60 1000))

;; How soon a new sign-in shows once the last one is over
;; (grog.signin/keep-up!).
(def ^:private login-check-ms (* 10 1000))

;; How soon whoever watches sees how an update goes: a small file to read.
(def ^:private update-check-ms 1000)

;; How often MCP servers unused for a while are stopped (grog.servers).
(def ^:private servers-check-ms (* 60 1000))

(defn ^:private every!
  "Runs `f` every `ms`, in the background, starting now or after `ms`
  (`now?`). Returns the future, to cancel."
  [ms now? f]
  (future
    (when-not now? (Thread/sleep ms))
    (loop []
      (try (f)
           (catch InterruptedException e (throw e))
           (catch Exception e (log/info "a background task failed:" (.getSimpleName (class e)))))
      (Thread/sleep ms)
      (recur))))

(defn ^:private watch-connected!
  "Once a session connected something (grog.connect), whoever watches
  learns how grog is now: what's connected to it, how it's paid."
  [ctx]
  (add-watch (:connect ctx) ::connected
             (fn [_ _ before after]
               (when (and (= "done" (:status after))
                          (not (and (= "done" (:status before)) (= (:generation before) (:generation after)))))
                 (assistant/publish-status! ctx)))))

(defn start!
  "Starts the HTTP server, and the nREPL server when enabled.
  Returns {:ctx :server :port :nrepl :background}."
  [config]
  (credentials/migrate! (config/state-dir config))
  (let [ctx (assistant/new-context config)
        {:keys [host port]} (:server config)
        ;; Through the var, so nREPL redefinitions of `route` apply live.
        server (http/run-server #(#'api/route ctx %) {:ip host :port port :legacy-return-value? false})
        port (http/server-port server)
        {:keys [provider auth model]} (provider/describe-quietly config)]
    (reset! current ctx)
    (watch-connected! ctx)
    (music/watch-voice! ctx)
    (assistant/publish-status! ctx)
    (log/info (format "listening on http://%s:%d (provider=%s auth=%s model=%s)" host port provider auth model))
    {:ctx ctx
     :server server
     :port port
     :background [(every! ping-ms false #(do (events/ping! (:events ctx))
                                             (voice/ping! (:voice ctx))))
                  (every! usage-check-ms true #(usage/refresh! ctx quota-max-age-s
                                                               (quot (System/currentTimeMillis) 1000)))
                  (every! login-check-ms true #(signin/keep-up! ctx))
                  (let [seen (atom nil)]
                    (every! update-check-ms true #(update/watch! ctx seen)))
                  (every! servers-check-ms false #(servers/stop-idle! ctx))]
     :nrepl (when (get-in config [:nrepl :enable])
              (nrepl/start-server! (select-keys (:nrepl config) [:host :port])))}))

(defn stop!
  "Stops what `start!` started, and the session connecting something, if
  one is open, grog leaving the network (grog.connect)."
  [{:keys [ctx server nrepl background]}]
  (run! future-cancel background)
  (when nrepl
    (nrepl/stop-server! nrepl))
  (remove-watch (:connect ctx) ::connected)
  (music/unwatch-voice! ctx)
  (connect/stop! ctx)
  (servers/stop-all! ctx)
  (http/server-stop! server)
  (compare-and-set! current ctx nil))

(defn serve!
  "Starts the servers and blocks forever."
  [config]
  (start! config)
  @(promise))
