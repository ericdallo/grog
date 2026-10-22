(ns grog.integrations
  "What grog can connect to (plans/integrations.md), for now its own:
  signing in to the provider's subscription (grog.signin), and the user's
  computers (grog.pairing). Each connects the same way (grog.connect), and
  has its connections, which `summary` tells whoever watches grog and the
  API.

  An integration, besides what grog.connect takes of it (:id :title :heading
  :ways :network? :start! :handle):

    :about        what it's for, in a few words
    :icon         its icon on grog's skin
    :multiple?    whether it can have more than one connection
    :connections  (fn [config]): what's connected, [{:id :name :detail
                  :status :added-at}] (grog.connections)
    :disconnect!  (fn [ctx id]): disconnects connection `id`: what to answer"
  (:require [clojure.string :as str]
            [grog.connect :as connect]
            [grog.pairing :as pairing]
            [grog.signin :as signin]))

(defn all
  "The integrations grog has with `config`."
  [config]
  (vec (remove nil? [(signin/integration (:provider config)) pairing/integration])))

(defn by-id
  "Integration `id`. Throws ex-info saying which there are, when there's
  none."
  [config id]
  (or (some #(when (= id (:id %)) %) (all config))
      (throw (ex-info (format "There's no integration called %s (there's %s)."
                              (pr-str id) (str/join ", " (map :id (all config))))
                      {:type ::unknown}))))

(defn summary
  "What can be connected, and what is, for whoever watches grog and the API:
  [{:id :title :heading :about :icon :ways :multiple :connections}], no
  secrets."
  [config]
  (mapv (fn [{:keys [connections multiple?] :as integration}]
          (assoc (select-keys integration [:id :title :heading :about :icon :ways])
                 :multiple (boolean multiple?)
                 :connections (connections config)))
        (all config)))

(defn open!
  "Opens a session connecting integration `id` `way` (its usual way when
  nil): what grog.connect/open! returns."
  [ctx id way]
  (connect/open! ctx (by-id @(:config ctx) id) way))

(defn disconnect!
  "Disconnects connection `id`, whichever integration's it is: what that
  says. Throws ex-info when there's none."
  [ctx id]
  (let [config @(:config ctx)
        owner (some (fn [{:keys [connections] :as integration}]
                      (when (some #(= id (:id %)) (connections config)) integration))
                    (all config))]
    (when-not owner
      (throw (ex-info (format "There's no connection called %s (grog integrations lists them)." (pr-str id))
                      {:type ::unknown-connection})))
    ((:disconnect! owner) ctx id)))

(defn tool
  "The `connect` tool: the model opens a session when the user asks to
  connect something."
  [config]
  (let [integrations (all config)]
    {:name "connect"
     :description (str "Opens a window, for 10 minutes, for the user to connect something to you, when they ask: "
                       (str/join ", " (for [{:keys [id about]} integrations] (str id " (" about ")")))
                       ". Your screen then shows a link and its QR code to open on their phone or computer, and "
                       "what to do there.")
     :parameters {:type "object"
                  :properties {:integration {:type "string" :enum (mapv :id integrations)}}
                  :required ["integration"]
                  :additionalProperties false}
     ;; It opens grog to the network for a while.
     :changes? (constantly true)
     :detail :integration
     :run (fn [ctx {:keys [integration]} _]
            (let [{:keys [heading link]} (open! ctx integration nil)]
              (str heading ": a window is open for 10 minutes, "
                   (if link
                     "and your screen shows its link and QR code. Tell the user to open it on their phone or computer; don't read the link aloud."
                     "and your screen shows what to do. Tell the user to follow it."))))}))
