(ns grog.login
  "Signing in to a provider's subscription. It runs in the server, which keeps
  the credentials in its state directory (grog.credentials). Two ways:

    :device   grog gets a short code from the provider, which whoever signs in
              enters at a web page, on any phone or computer (OAuth device
              authorization); grog asks the provider every few seconds
              whether it was. While grog has nothing to pay with, the server
              keeps one going (`keep-up!`), for its skin to show.
    :browser  OAuth with PKCE, the browser coming back to a localhost callback
              (`grog login --browser`): it must run on grog's machine, or
              reach it through an SSH tunnel.

  A provider that supports it has a :login client (see grog.provider):

    :label          what users sign in with, e.g. \"ChatGPT\"
    :needed?        (fn [cfg]): whether grog has nothing to pay with, and
                    signing in would fix it
    :exchange-code! (fn [cfg {:keys [code verifier redirect-uri]}]): trades a
                    code for credentials, saved as they are
    :device-code!   optional, (fn [cfg]): a code to sign in with: {:user-code
                    :url :interval-s ...}, good for `device-ttl-ms`
    :poll-device!   (fn [cfg device]): nil until the code of `device` was
                    entered, then {:code :verifier :redirect-uri} for
                    :exchange-code!
    :device-help    {:text :url}: what may have to be done first, and where
    :redirect-uri   (fn [cfg]): the http://localhost:<port>/<path> the browser
                    comes back to, registered with the provider
    :authorize-url  (fn [cfg {:keys [challenge state redirect-uri]}]): where the
                    browser signs in

  Whoever watches (`watch!`) learns how each sign-in goes: what `view` says."
  (:require [grog.credentials :as credentials]
            [grog.config :as config]
            [grog.log :as log]
            [grog.oauth :as oauth]
            [grog.provider :as provider]
            [org.httpkit.server :as http])
  (:import (java.net URI)))

;; provider -> its last sign-in: {:status :pending|:exchanging|:done|:error|
;;   :expired, :method :device|:browser, :cfg :state :label :error :ended-at,
;;   and by method, :device :code :url :help :expires-at (:device), or
;;   :verifier :url :redirect-uri :server :deadline (:browser)}. :pending waits
;; for whoever signs in; :exchanging trades the code the provider gave back.
(defonce ^:private logins (atom {}))

;; How long a browser sign-in waits, counted from the last `grog login`.
(def ^:private timeout-ms (* 10 60 1000))

(def device-ttl-ms
  "How long the provider's codes are good for (OpenAI's: 15 minutes). Tests
  redefine it."
  (* 15 60 1000))

(def poll-failures-max
  "How many times in a row asking whether a code was entered fails (the
  network, say) before the sign-in does. Tests redefine it."
  5)

;; How long `keep-up!` waits after a sign-in failed before trying again.
(def ^:private retry-ms (* 60 1000))

(def ^:private start-lock (Object.))

(defn ^:private now-ms []
  (System/currentTimeMillis))

(defn ^:private log [provider message]
  (log/info (str "login " provider ": " message)))

(defn ^:private client
  "The :login client of `provider`."
  [provider]
  (or (:login (provider/get-provider provider))
      (throw (ex-info (format "Signing in isn't supported for %s." provider)
                      {:type ::unsupported}))))

(defn view
  "What whoever watches is told of `login`: how it goes and, while waiting
  for a code, the code and where to enter it. Never a browser sign-in's link,
  which carries its state."
  [{:keys [status method label code url help expires-at error]}]
  (cond-> {:status (name status) :method (name method) :label label}
    (and (= :device method) (= :pending status)) (assoc :code code :url url :help help :expires-at expires-at)
    error (assoc :error error)))

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
  "Ends the sign-in with `state`, if still `from`, as `to` (:done, :error or
  :expired)."
  [provider state from to error]
  (when-let [{:keys [server]} (transition! provider state from to {:error error :ended-at (now-ms)})]
    (log provider (case to
                    :done "signed in"
                    :expired "the code expired"
                    (str "failed: " error)))
    (when server
      ;; Leave time for the page to reach the browser.
      (future (Thread/sleep 1000) (stop-server! server)))))

(defn ^:private exchange!
  "Trades `grant` ({:code :verifier :redirect-uri}) for credentials and saves
  them, ending the sign-in with `state`, which is :exchanging. Returns the
  error message, or nil once signed in."
  [cfg provider exchange-code! state grant]
  (try
    (credentials/save! (:state-dir cfg) provider (exchange-code! cfg grant))
    (end! provider state :exchanging :done nil)
    nil
    (catch Exception e
      (end! provider state :exchanging :error (ex-message e))
      (ex-message e))))

(defn ^:private on-callback [cfg provider exchange-code! {:keys [code state error error_description]}]
  ;; Claimed once, so a repeated callback can't trade the code twice.
  (if-let [{:keys [verifier redirect-uri]} (transition! provider state :pending :exchanging nil)]
    (if error
      (let [message (or error_description error)]
        (end! provider state :exchanging :error message)
        (oauth/page "Sign-in failed" message))
      (if-let [failure (exchange! cfg provider exchange-code! state
                                  {:code code :verifier verifier :redirect-uri redirect-uri})]
        (oauth/page "Sign-in failed" failure)
        (oauth/page "Signed in to grog" "You can close this tab.")))
    (let [login (get @logins provider)]
      (log provider (str "refused a callback: "
                         (if (= state (:state login))
                           (str "the sign-in is " (some-> (:status login) name) " already")
                           "it belongs to another sign-in (an older link?)")))
      (oauth/page "Sign-in failed"
                  (str "This page doesn't belong to a sign-in in progress. Start again with: grog login --browser "
                       provider)))))

(defn ^:private expire-later!
  "Ends the browser sign-in with `state` when its deadline passes."
  [provider state]
  (future
    (loop []
      (let [{:keys [status deadline] :as login} (get @logins provider)
            left (- deadline (now-ms))]
        (when (and (= state (:state login)) (= :pending status))
          (if (pos? left)
            (do (Thread/sleep left)
                (recur))
            (end! provider state :pending :error "Timed out waiting for the browser.")))))))

(defn ^:private begin-browser! [cfg provider {:keys [label redirect-uri authorize-url exchange-code!]}]
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
                                    :method :browser
                                    :cfg cfg
                                    :state state
                                    :verifier verifier
                                    :url url
                                    :redirect-uri redirect-uri
                                    :label label
                                    :server server
                                    :deadline (+ (now-ms) timeout-ms)})
      (log provider (str "waiting for the browser at " redirect-uri))
      (expire-later! provider state))))

(defn ^:private poll-once
  "Asks the provider once whether the code of `device` was entered: the
  grant, nil, or the exception it threw."
  [cfg poll-device! device]
  (try
    (poll-device! cfg device)
    (catch Exception e e)))

(defn ^:private poll-later!
  "Asks the provider every :interval-s whether the code of the device sign-in
  with `state` was entered, until it was, the code expired, or asking kept
  failing."
  [cfg provider {:keys [poll-device! exchange-code!]} state {:keys [interval-s] :as device}]
  (future
    (loop [failures 0]
      (Thread/sleep (* 1000 interval-s))
      (let [{:keys [status expires-at] :as login} (get @logins provider)]
        (when (and (= state (:state login)) (= :pending status))
          (if (>= (now-ms) expires-at)
            (end! provider state :pending :expired nil)
            (let [result (poll-once cfg poll-device! device)]
              (cond
                (nil? result)
                (recur 0)

                (instance? Exception result)
                (if (< (inc failures) poll-failures-max)
                  (recur (inc failures))
                  (end! provider state :pending :error (ex-message result)))

                (transition! provider state :pending :exchanging nil)
                (exchange! cfg provider exchange-code! state result)))))))))

(defn ^:private begin-device!
  "Gets a code from the provider, and waits for it to be entered. When
  getting one fails, so does the sign-in (for `keep-up!` to wait a while),
  and the exception goes on."
  [cfg provider {:keys [label device-code! device-help] :as client}]
  (let [state (oauth/random-token)
        login {:method :device :cfg cfg :state state :label label}]
    (stop-server! (get-in @logins [provider :server]))
    (let [device (try
                   (device-code! cfg)
                   (catch Exception e
                     (swap! logins assoc provider (assoc login :status :error :error (ex-message e) :ended-at (now-ms)))
                     (log provider (str "failed: " (ex-message e)))
                     (throw e)))]
      (swap! logins assoc provider (assoc login
                                          :status :pending
                                          :code (:user-code device)
                                          :url (:url device)
                                          :help device-help
                                          :expires-at (+ (now-ms) device-ttl-ms)))
      (log provider "waiting for its code to be entered")
      (poll-later! cfg provider client state device))))

(defn ^:private started
  "What `start!` tells whoever started the sign-in `login`: its view, with a
  browser sign-in's link and where the browser comes back to."
  [login]
  (cond-> (view login)
    (= :browser (:method login)) (merge (select-keys login [:url :redirect-uri]))))

(defn start!
  "Starts signing in to `provider` with `method` (:device or :browser; nil:
  :device when the provider can), or continues the sign-in in progress with
  it, so that its code or link stays the same. Returns `started`'s: with
  :device, the code and the page to enter it at (`view`); with :browser,
  the link to sign in at (:url), and where the browser comes back to
  (:redirect-uri)."
  [config provider method]
  (let [client (client provider)
        method (or method (if (:device-code! client) :device :browser))
        cfg (config/provider-config config provider)]
    (when (and (= :device method) (not (:device-code! client)))
      (throw (ex-info (format "Signing in with a code isn't supported for %s: use --browser." provider)
                      {:type ::unsupported})))
    (locking start-lock
      (let [login (get @logins provider)]
        (if (and (#{:pending :exchanging} (:status login)) (= method (:method login)) (= cfg (:cfg login)))
          (do (when (= :browser method)
                (swap! logins assoc-in [provider :deadline] (+ (now-ms) timeout-ms)))
              (log provider "already waiting, the same way")
              (started login))
          (do (if (= :device method)
                (begin-device! cfg provider client)
                (begin-browser! cfg provider client))
              (started (get @logins provider))))))))

(defn keep-up!
  "Keeps a sign-in with a code going while `provider` needs one (its
  :needed?), for whoever watches to show: a new code once the last one
  expired, and again a while after one failed. Leaves sign-ins in progress
  alone. Does nothing for providers that can't."
  [config provider]
  (let [{:keys [needed? device-code!]} (:login (provider/get-provider provider))
        {:keys [status ended-at]} (get @logins provider)]
    (when (and device-code!
               needed?
               (not (#{:pending :exchanging} status))
               (or (not= :error status) (>= (- (now-ms) ended-at) retry-ms))
               (needed? (config/provider-config config provider)))
      (try
        (start! config provider :device)
        ;; The sign-in failed, saying why (begin-device!).
        (catch Exception _ nil)))))

(defn status
  "Whether credentials are saved for `provider`, and how the last sign-in went:
  :login, its status, with its :method and, while waiting for a code, the
  :code, the :url to enter it at, :code-expires-at, and the :help."
  [config provider]
  (client provider)
  (let [credentials (credentials/saved (config/state-dir config) provider)
        login (get @logins provider)
        {:keys [status method error code url help expires-at]} (some-> login view)]
    (cond-> {:signed-in (some? credentials)}
      credentials (assoc :email (:email credentials)
                         :account-id (:account-id credentials)
                         :expires-at (:expires-at credentials))
      status (assoc :login status :method method)
      code (assoc :code code :url url :help help :code-expires-at expires-at)
      error (assoc :error error))))

(defn logout! [config provider]
  (client provider)
  (credentials/remove! (config/state-dir config) provider))

(defn watch!
  "Calls (f provider view) whenever a sign-in changes what `view` says of it,
  until `unwatch!`ed by `k`."
  [k f]
  (add-watch logins k (fn [_ _ before after]
                        (doseq [provider (set (concat (keys before) (keys after)))
                                :let [was (some-> (get before provider) view)
                                      now (some-> (get after provider) view)]
                                :when (and now (not= was now))]
                          (f provider now)))))

(defn unwatch! [k]
  (remove-watch logins k))

(defn stop-all!
  "Ends every sign-in, stopping the servers waiting for the browser: the
  server is stopping. Those waiting for a code stop asking."
  []
  (let [[before] (reset-vals! logins {})]
    (run! (comp stop-server! :server) (vals before))))
