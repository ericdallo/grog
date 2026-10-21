(ns grog.login
  "`grog login`: signs in to a provider's subscription with OAuth (PKCE, the
  browser coming back to a localhost callback). It runs in the server, which
  keeps the credentials in its state directory (grog.credentials).

  A provider that supports it has a :login client (see grog.provider):

    :label          what users sign in with, e.g. \"ChatGPT\"
    :redirect-uri   (fn [cfg]): the http://localhost:<port>/<path> the browser
                    comes back to, registered with the provider
    :authorize-url  (fn [cfg {:keys [challenge state redirect-uri]}]): where the
                    browser signs in
    :exchange-code! (fn [cfg {:keys [code verifier redirect-uri]}]): trades the
                    code for credentials, saved as they are"
  (:require [grog.credentials :as credentials]
            [grog.config :as config]
            [grog.log :as log]
            [grog.oauth :as oauth]
            [grog.provider :as provider]
            [org.httpkit.server :as http])
  (:import (java.net URI)))

;; provider -> its last sign-in: {:status :pending|:exchanging|:done|:error,
;;   :cfg :state :verifier :url :redirect-uri :label :server :deadline :error}
;; :pending waits for the browser; :exchanging trades the code it brought back.
(defonce ^:private logins (atom {}))

;; How long a sign-in waits for the browser, counted from the last `grog login`.
(def ^:private timeout-ms (* 10 60 1000))

(def ^:private start-lock (Object.))

(defn ^:private log [provider message]
  (log/info (str "login " provider ": " message)))

(defn ^:private client
  "The :login client of `provider`."
  [provider]
  (or (:login (provider/get-provider provider))
      (throw (ex-info (format "Signing in isn't supported for %s." provider)
                      {:type ::unsupported}))))

(defn ^:private stop-server!
  "Stops `server`, waiting for its port to be free again."
  [server]
  (when (and server (= :running (http/server-status server)))
    (deref (http/server-stop! server) 2000 nil)))

(defn ^:private transition!
  "Moves the sign-in with `state` from status `from` to `to`, merging
  `changes`. Returns the sign-in as it was when it moved, else nil (it had
  moved on, or another sign-in replaced it)."
  [provider state from to changes]
  (let [moves? #(and (= state (:state %)) (= from (:status %)))
        [before] (swap-vals! logins update provider
                             #(cond-> % (moves? %) (merge changes {:status to})))
        login (get before provider)]
    (when (moves? login)
      login)))

(defn ^:private end!
  "Ends the sign-in with `state`, if still `from`, as `to` (:done or :error)."
  [provider state from to error]
  (when-let [{:keys [server]} (transition! provider state from to {:error error})]
    (log provider (if error (str "failed: " error) "signed in"))
    ;; Leave time for the page to reach the browser.
    (future (Thread/sleep 1000) (stop-server! server))))

(defn ^:private on-callback [cfg provider exchange-code! {:keys [code state error error_description]}]
  ;; Claimed once, so a repeated callback can't trade the code twice.
  (if-let [{:keys [verifier redirect-uri]} (transition! provider state :pending :exchanging nil)]
    (if error
      (let [message (or error_description error)]
        (end! provider state :exchanging :error message)
        (oauth/page "Sign-in failed" message))
      (try
        (credentials/save! (:state-dir cfg) provider
                           (exchange-code! cfg {:code code :verifier verifier :redirect-uri redirect-uri}))
        (end! provider state :exchanging :done nil)
        (oauth/page "Signed in to grog" "You can close this tab.")
        (catch Exception e
          (end! provider state :exchanging :error (ex-message e))
          (oauth/page "Sign-in failed" (ex-message e)))))
    (let [login (get @logins provider)]
      (log provider (str "refused a callback: "
                         (if (= state (:state login))
                           (str "the sign-in is " (some-> (:status login) name) " already")
                           "it belongs to another sign-in (an older link?)")))
      (oauth/page "Sign-in failed"
                  (str "This page doesn't belong to a sign-in in progress. Start again with: grog login "
                       provider)))))

(defn ^:private expire-later!
  "Ends the sign-in with `state` when its deadline passes."
  [provider state]
  (future
    (loop []
      (let [{:keys [status deadline] :as login} (get @logins provider)
            left (- deadline (System/currentTimeMillis))]
        (when (and (= state (:state login)) (= :pending status))
          (if (pos? left)
            (do (Thread/sleep left)
                (recur))
            (end! provider state :pending :error "Timed out waiting for the browser.")))))))

(defn ^:private begin! [cfg provider {:keys [label redirect-uri authorize-url exchange-code!]}]
  (let [redirect-uri (redirect-uri cfg)
        callback (URI. redirect-uri)
        {:keys [verifier challenge]} (oauth/pkce)
        state (oauth/random-token)
        url (authorize-url cfg {:challenge challenge :state state :redirect-uri redirect-uri})]
    ;; The last sign-in's server stops a second after it ends, or it was
    ;; started with other settings.
    (stop-server! (get-in @logins [provider :server]))
    (let [server (try
                   (oauth/start-callback-server! (.getPort callback) (.getPath callback)
                                                 #(on-callback cfg provider exchange-code! %))
                   (catch java.net.BindException _
                     (throw (ex-info (format "Port %d is busy: is another program signing in?"
                                             (.getPort callback))
                                     {:type ::port-busy}))))]
      (swap! logins assoc provider {:status :pending
                                    :cfg cfg
                                    :state state
                                    :verifier verifier
                                    :url url
                                    :redirect-uri redirect-uri
                                    :label label
                                    :server server
                                    :deadline (+ (System/currentTimeMillis) timeout-ms)})
      (log provider (str "waiting for the browser at " redirect-uri))
      (expire-later! provider state)
      {:url url :redirect-uri redirect-uri :label label})))

(defn start!
  "Starts signing in to `provider`, or continues the sign-in in progress so
  the link it gave stays valid. Returns {:url :redirect-uri :label}: the
  browser signs in (with a :label account) at :url and comes back to
  :redirect-uri."
  [config provider]
  (let [client (client provider)
        cfg (config/provider-config config provider)]
    (locking start-lock
      (let [login (get @logins provider)]
        (if (and (#{:pending :exchanging} (:status login)) (= cfg (:cfg login)))
          (do (swap! logins assoc-in [provider :deadline] (+ (System/currentTimeMillis) timeout-ms))
              (log provider "already waiting for the browser, same link")
              (select-keys login [:url :redirect-uri :label]))
          (begin! cfg provider client))))))

(defn status
  "Whether credentials are saved for `provider`, and how the last sign-in went."
  [config provider]
  (client provider)
  (let [credentials (credentials/saved (config/state-dir config) provider)
        {:keys [status error]} (get @logins provider)]
    (cond-> {:signed-in (some? credentials)}
      credentials (assoc :email (:email credentials)
                         :account-id (:account-id credentials)
                         :expires-at (:expires-at credentials))
      status (assoc :login (name status))
      error (assoc :error error))))

(defn logout! [config provider]
  (client provider)
  (credentials/remove! (config/state-dir config) provider))

(defn stop-all!
  "Ends every sign-in, stopping the servers waiting for the browser: the
  server is stopping."
  []
  (let [[before] (reset-vals! logins {})]
    (run! (comp stop-server! :server) (vals before))))
