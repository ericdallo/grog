(ns grog.assist
  "Commands a connected Home Assistant does by itself: before the model,
  what was asked goes to its own conversation agent (Assist, which matches
  sentences against its own, no model: milliseconds), which acts at once on
  what it understands (\"liga a luz da cozinha\"). When it did, what it
  said (\"Ligado\") is the answer, seconds sooner. Otherwise the model
  answers as usual: when it didn't understand, when it answered a question
  (from what it alone knows, not what grog remembers), or when it can't be
  asked (not connected, signed out, slow). The `assist` setting turns it
  off. Never logged: what was asked, or said."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.connections :as connections]
            [grog.log :as log]
            [grog.servers :as servers]))

;; The integration whose conversation agent it asks.
(def ^:private integration "home-assistant")

;; Its own agent, matching sentences: not one the user set a model up as.
(def ^:private agent-id "conversation.home_assistant")

(def timeout-ms
  "How long it has to answer: milliseconds, when it's well. Tests redefine
  it."
  1000)

(defn base-url
  "Home Assistant's address, from its MCP server's (`<it>/api/mcp`, or
  `<it>/api/mcp/assist`)."
  [mcp-url]
  (str/replace (str mcp-url) #"/api/mcp(/assist)?/?$" ""))

(defn acted
  "What Home Assistant's agent said, in its answer to
  /api/conversation/process (`body`, parsed), when it acted (`action_done`:
  it did something, or said something it knows by doing so, like the
  time): what it did is done then, either way. nil otherwise: it didn't
  understand, couldn't, or answered a question."
  [body]
  (let [{:keys [response_type speech]} (:response body)
        said (get-in speech [:plain :speech])]
    (when (and (= "action_done" response_type) (string? said) (not (str/blank? said)))
      (str/trim said))))

(defn ^:private connected
  "The connected Home Assistant's connection: its id, or nil."
  [dir]
  (some #(when (and (= integration (:integration %)) (= "connected" (:status %))) (:id %))
        (connections/all dir)))

(defn ^:private outcome
  "How Home Assistant's agent answered (`body`), in a word or two: never
  what it said."
  [body]
  (let [{:keys [response_type data]} (:response body)]
    (str/join " " (remove nil? [(or response_type "nothing") (:code data)]))))

(defn act!
  "What the connected Home Assistant said once it acted on `message` by
  itself (see the ns), or nil when it didn't, or couldn't be asked (logged,
  its kind only)."
  [ctx message]
  (let [config @(:config ctx)
        dir (config/state-dir config)
        id (when (get-in config [:assist :enable]) (connected dir))
        url (some->> id (connections/secrets dir) :url base-url)]
    (when url
      (let [started (System/nanoTime)
            ms #(quot (- (System/nanoTime) started) 1000000)]
        (try
          (let [resp (http/post (str url "/api/conversation/process")
                                {:headers (assoc (servers/headers! ctx id) "Content-Type" "application/json")
                                 :body (json/generate-string {:text message :agent_id agent-id})
                                 :throw false
                                 :timeout timeout-ms})]
            (if (= 200 (:status resp))
              (let [body (json/parse-string (:body resp) true)]
                (log/info (format "assist: %s in %d ms" (outcome body) (ms)))
                (acted body))
              (do (log/info (format "assist: Home Assistant answered %s in %d ms" (:status resp) (ms)))
                  nil)))
          (catch Exception e
            (log/info (format "assist: couldn't ask Home Assistant (%s) in %d ms" (.getSimpleName (class e)) (ms)))
            nil))))))
