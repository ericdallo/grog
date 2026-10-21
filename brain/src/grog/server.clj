(ns grog.server
  "The server: grog's HTTP API (grog.api) and, when enabled, an nREPL, with
  what runs in the background: pings to whoever watches what grog is up
  to, and to grog-voice, keeping what they know of its usage current, and a
  sign-in with a code going while grog has nothing to pay with, for its
  skin to show (grog.login)."
  (:require [babashka.nrepl.server :as nrepl]
            [grog.api :as api]
            [grog.assistant :as assistant]
            [grog.events :as events]
            [grog.log :as log]
            [grog.login :as login]
            [grog.provider :as provider]
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

;; How soon a new code shows once the last one expired (grog.login/keep-up!).
(def ^:private login-check-ms (* 10 1000))

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

(defn ^:private on-login
  "Tells whoever watches how a sign-in to the provider grog uses goes, and,
  once it's done, how grog is paid now."
  [ctx provider view]
  (when (= provider (:provider @(:config ctx)))
    (events/publish! (:events ctx) {:type :login :login (assoc view :provider provider)})
    (when (= "done" (:status view))
      (assistant/publish-status! ctx))))

(defn ^:private keep-up-login!
  "A sign-in with a code while grog has nothing to pay with, for its skin to
  show: without one, `grog login` gets the code."
  [ctx]
  (let [config @(:config ctx)]
    (when (get-in config [:skin :dir])
      (login/keep-up! config (:provider config)))))

(defn start!
  "Starts the HTTP server, and the nREPL server when enabled.
  Returns {:ctx :server :port :nrepl :background}."
  [config]
  (let [ctx (assistant/new-context config)
        {:keys [host port]} (:server config)
        ;; Through the var, so nREPL redefinitions of `route` apply live.
        server (http/run-server #(#'api/route ctx %) {:ip host :port port :legacy-return-value? false})
        port (http/server-port server)
        {:keys [provider auth model]} (provider/describe-quietly config)]
    (reset! current ctx)
    (login/watch! ctx #(on-login ctx %1 %2))
    (assistant/publish-status! ctx)
    (log/info (format "listening on http://%s:%d (provider=%s auth=%s model=%s)" host port provider auth model))
    {:ctx ctx
     :server server
     :port port
     :background [(every! ping-ms false #(do (events/ping! (:events ctx))
                                             (voice/ping! (:voice ctx))))
                  (every! usage-check-ms true #(usage/refresh! ctx quota-max-age-s
                                                               (quot (System/currentTimeMillis) 1000)))
                  (every! login-check-ms true #(keep-up-login! ctx))]
     :nrepl (when (get-in config [:nrepl :enable])
              (nrepl/start-server! (select-keys (:nrepl config) [:host :port])))}))

(defn stop!
  "Stops what `start!` started, and the sign-ins in progress (grog.login)."
  [{:keys [ctx server nrepl background]}]
  (run! future-cancel background)
  (when nrepl
    (nrepl/stop-server! nrepl))
  (http/server-stop! server)
  (login/unwatch! ctx)
  (login/stop-all!)
  (compare-and-set! current ctx nil))

(defn serve!
  "Starts the servers and blocks forever."
  [config]
  (start! config)
  @(promise))
