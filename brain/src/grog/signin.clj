(ns grog.signin
  "Signing in to the provider's subscription (ChatGPT's): the provider's
  integration (grog.integrations), connected like any other
  (grog.connect), two ways:

    device   grog gets a short code from the provider, which whoever signs in
             enters at its page, on any phone or computer (a device-code
             step); grog asks the provider every few seconds whether it was.
             While grog has nothing to pay with, it keeps one going
             (`keep-up!`), for its skin to show.
    browser  the browser signs in at the provider, then comes back to a
             localhost callback (an oauth step): on grog's machine, or
             through an SSH tunnel, or whoever signs in pastes where it ended
             on grog's page.

  The credentials go to the provider's connection (grog.credentials).

  A provider that supports it has a :login client (see grog.provider):

    :label          what users sign in with, e.g. \"ChatGPT\"
    :needed?        (fn [cfg]): whether grog has nothing to pay with, and
                    signing in would fix it
    :exchange-code! (fn [cfg {:keys [code verifier redirect-uri]}]): trades a
                    code for credentials, saved as they are, with the
                    account's :email
    :device-code!   optional, (fn [cfg]): a code to sign in with: {:user-code
                    :url :interval-s ...}, good for `device-ttl-ms`
    :poll-device!   (fn [cfg device]): nil until the code of `device` was
                    entered, then {:code :verifier :redirect-uri} for
                    :exchange-code!
    :device-help    {:text :url}: what may have to be done first, and where
    :redirect-uri   (fn [cfg]): the http://localhost:<port>/<path> the browser
                    comes back to, registered with the provider
    :authorize-url  (fn [cfg {:keys [challenge state redirect-uri]}]): where the
                    browser signs in"
  (:require [grog.config :as config]
            [grog.connect :as connect]
            [grog.connections :as connections]
            [grog.credentials :as credentials]
            [grog.log :as log]
            [grog.provider :as provider]
            [grog.usage :as usage]))

(def device-ttl-ms
  "How long the provider's codes are good for (OpenAI's: 15 minutes). Tests
  redefine it."
  (* 15 60 1000))

;; How long `keep-up!` waits after a sign-in failed before trying again.
(def ^:private retry-ms (* 60 1000))

(defn ^:private now []
  (System/currentTimeMillis))

(defn ^:private finish!
  "Trades `grant` for credentials, kept for `provider`: the account's email,
  or else what it signs in with."
  [cfg provider {:keys [exchange-code! label]} grant]
  (let [credentials (exchange-code! cfg grant)]
    (credentials/save! (:state-dir cfg) provider credentials)
    (log/info (str "signin " provider ": signed in"))
    (or (:email credentials) label)))

(defn ^:private starter
  "How a sign-in to `provider` with `client` starts, each way."
  [provider client]
  (fn [ctx {:keys [generation]} way]
    (let [cfg (config/provider-config @(:config ctx) provider)
          finish #(finish! cfg provider client %)]
      (case way
        "device" (connect/device-code! ctx generation {:code! #((:device-code! client) cfg)
                                                       :poll! #((:poll-device! client) cfg %)
                                                       :finish! finish
                                                       :ttl-ms device-ttl-ms
                                                       :help (:device-help client)
                                                       :note "Signing in…"})
        "browser" (connect/oauth! ctx generation {:redirect-uri ((:redirect-uri client) cfg)
                                                  :authorize-url #((:authorize-url client) cfg %)
                                                  :finish! finish
                                                  :note "Signing in…"})))))

(defn ^:private connections-of
  "The sign-in to `provider`, as a connection, if there's one."
  [config provider]
  (if-let [connection (connections/by-id (config/state-dir config) provider)]
    [(select-keys connection [:id :name :status :added-at])]
    []))

(defn ^:private sign-out!
  "Forgets the credentials of `provider`, and what grog knew of its
  subscription's limits."
  [ctx provider]
  (credentials/remove! (config/state-dir @(:config ctx)) provider)
  (usage/forget-quota! ctx)
  (log/info (str "signin " provider ": signed out"))
  {:id provider :disconnected true})

(defn integration
  "The integration signing in to `provider`'s subscription, or nil when it
  has none (grog.integrations)."
  [provider]
  (when-let [{:keys [label device-code!] :as client} (some-> (get provider/providers provider) deref :login)]
    {:id provider
     :title label
     :heading (str "Sign in with " label)
     :about (str "your " label " subscription pays for the answers")
     :icon "key"
     :ways (cond-> []
             device-code! (conj {:id "device" :label "Enter a code instead"})
             true (conj {:id "browser" :label "Sign in in the browser instead"}))
     :start! (starter provider client)
     :connections #(connections-of % provider)
     :disconnect! (fn [ctx _] (sign-out! ctx provider))
     :needed? (:needed? client)}))

(defn keep-up!
  "Keeps a sign-in with a code going while grog has nothing to pay with
  (its provider's :needed?), for its skin to show: a session grog opens
  itself, opened again once it's over, and a minute after one failed.
  Leaves other sessions alone. Does nothing without a skin, nor for
  providers that can't."
  [ctx]
  (let [config @(:config ctx)
        provider (:provider config)
        {:keys [needed? ways] :as signin} (integration provider)
        {:keys [status at] :as session} (connect/view @(:connect ctx))]
    (when (and (get-in config [:skin :dir])
               signin
               (some #(= "device" (:id %)) ways)
               (not= "open" status)
               (not (and (= "failed" status) (= provider (:integration session)) (< (- (now) at) retry-ms)))
               (needed? (config/provider-config config provider)))
      (try
        (connect/open! ctx signin "device" {:kept? true})
        ;; The session failed, saying why.
        (catch Exception _ nil)))))
