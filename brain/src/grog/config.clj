(ns grog.config
  "Settings: built-in defaults deep-merged with an optional JSON file.

  The JSON file uses lowerCamelCase keys (as written in the NixOS module), read
  as kebab-case keywords: `maxHistoryMessages` -> `:max-history-messages`."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.time ZoneId)))

(def defaults
  {;; How the assistant calls itself.
   :name "grog"
   ;; The language answers are in, e.g. "English". nil: whatever language the
   ;; user speaks.
   :language nil
   ;; e.g. "America/Sao_Paulo". nil: the system's.
   :timezone nil
   ;; e.g. {:city "São Paulo" :region "São Paulo" :country "BR"}
   ;; (country as ISO 3166-1 alpha-2). Used in the prompt and for web search.
   :location nil
   ;; Replace the built-in system prompt template
   ;; (resources/grog/system-prompt.md): the template itself, or its path.
   :system-prompt nil
   :system-prompt-file nil
   ;; Extra instructions appended to the system prompt.
   :instructions nil
   ;; Which of `providers` to use.
   :provider "openai"
   ;; Messages (user + assistant) kept per conversation.
   :max-history-messages 20
   ;; A conversation is forgotten after this much inactivity.
   :conversation-ttl-minutes 10
   ;; What grog remembers (grog.memory). learn: whether it also learns by
   ;; itself, from conversations once they go quiet, what's worth
   ;; remembering for good (grog.learning).
   :memory {:learn true}
   ;; allowed-hosts: the names clients may reach grog by (the Host header).
   :server {:host "127.0.0.1" :port 8484 :allowed-hosts ["localhost" "127.0.0.1" "[::1]"]}
   :nrepl {:enable false :host "127.0.0.1" :port 1667}
   ;; The unix socket of grog's hands (`grog hands`), which run what its tools
   ;; do on the machine: the volume, a shell... nil: it doesn't touch it.
   :hands {:socket nil}
   ;; grog's skin, a page showing what it's up to (skin/ in the repo), served
   ;; at /: the directory with its files (nil: none). Captions: whether it
   ;; also shows what's said, questions and answers, to anyone who can reach
   ;; grog. Screen off minutes: how long its screen (the skin's fullscreen
   ;; window) stays on once nothing happens, to save energy (nil: for good).
   ;; Screen off: how it goes off then, as its own speakers (HDMI,
   ;; DisplayPort) go quiet without a signal: "auto", dark (a dim clock,
   ;; the signal kept) while grog speaks through them, else off; "dark";
   ;; "off".
   :skin {:dir nil :captions false :screen-off-minutes 10 :screen-off "auto"}
   ;; How the update service (`grog updater`, as root) says an update goes,
   ;; a file: with it and hands, grog can update itself when asked (the
   ;; NixOS module's services.grog.update). nil: it can't.
   :update {:status nil}
   ;; grog's music (grog.music). socket: the unix socket of its player,
   ;; grog-music, in the user's session (nil: it plays nothing itself).
   ;; provider: what plays when asked, by its id (youtube-music, or a
   ;; connected integration's); nil: the first connected, else the first
   ;; there. Changed by asking (the settings tool).
   :music {:socket nil :provider nil}
   ;; The user's computers grog acts on (grog.computers): the ssh it reaches
   ;; them with.
   :computers {:ssh "ssh"}
   ;; Where grog listens on the network while someone at it connects
   ;; something (grog.connect), and only then: the page showing how, and
   ;; what a computer pairing asks (grog.pairing). runner: the unix socket of
   ;; grog's runner (`grog mcp`, grog.runner), which runs the MCP servers
   ;; installed from npm and PyPI apart from grog; nil: grog only has its
   ;; own and remote ones. relay: the project's page OAuth comes back
   ;; through (grog's client ID metadata document is its client.json, and
   ;; its callback/ sends the browser on to grog on the network).
   :integrations {:host "0.0.0.0"
                  :port 8485
                  :runner nil
                  :relay "https://ericdallo.github.io/grog/oauth/"}
   ;; Where grog keeps its files: what's connected to it (integrations.json,
   ;; their secrets in secrets/), the settings changed by asking
   ;; (settings.json), what it remembers (memory.json), whether
   ;; grog-voice's mic is on (voice.json), what it consumed (usage.json),
   ;; the computers paired with it (computers.json, its SSH key and their
   ;; host keys in computers/), the MCP servers the user added (catalog/),
   ;; the tools of those connected (tools/), and what the user chose for
   ;; them (tools.json).
   ;; nil: $STATE_DIRECTORY (set by systemd) or ~/.local/state/grog.
   :state-dir nil
   ;; Settings per provider (see grog.provider).
   :providers {:openai {:model "gpt-6-luna"
                        :base-url "https://api.openai.com"
                        :api-key-env "OPENAI_API_KEY"
                        ;; Includes reasoning tokens. Not sent to the
                        ;; subscription backend, which rejects it.
                        :max-tokens 2048
                        :reasoning-effort "low"
                        :timeout-ms 60000
                        :web-search {:enable true}
                        ;; What an API key pays, in USD, to tell what grog
                        ;; spent: per million tokens of each model (OpenAI's
                        ;; standard rates), and per web search. Turns of
                        ;; models without a price are counted, not priced.
                        :prices {:models {:gpt-6-luna {:input 0.10 :cached-input 0.01 :output 0.50}}
                                 :web-search 0.01}
                        ;; "auto": the ChatGPT subscription when signed in
                        ;; (`grog login openai`), else the API key.
                        ;; "subscription" or "api-key" force one.
                        :auth "auto"
                        ;; The ChatGPT subscription goes through the Codex
                        ;; backend, which has its own model catalog
                        ;; (`grog models`).
                        :subscription {:model "gpt-6-luna"
                                       :base-url "https://chatgpt.com/backend-api/codex"
                                       :auth-url "https://auth.openai.com"
                                       ;; Registered with OpenAI for this
                                       ;; client: can't change in practice.
                                       :callback-port 1455}}}})

(defn camel->kebab [s]
  (-> s
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      str/lower-case))

(defn kebab->camel [s]
  (str/replace s #"-([a-z0-9])" #(str/upper-case (second %))))

(defn setting-name
  "How the setting at `path` is written, e.g. [:server :allowed-hosts] ->
  \"server.allowedHosts\"."
  [path]
  (str/join "." (map (comp kebab->camel name) path)))

(defn deep-merge [& maps]
  (apply merge-with
         (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b))
         maps))

(defn parse
  "JSON with lowerCamelCase keys, as kebab-case keywords: how grog reads its
  settings, and what its HTTP API and its hands are sent."
  [s]
  (json/parse-string s (comp keyword camel->kebab)))

(defn generate
  "`data` as JSON with lowerCamelCase keys: the other way around from
  `parse`. Only keyword keys change: string keys are data (model ids,
  dates), kept as they are. `opts`: cheshire's, like {:pretty true}."
  ([data]
   (generate data nil))
  ([data opts]
   (json/generate-string data (assoc opts :key-fn (comp kebab->camel name)))))

(defn load-config
  "Defaults merged with the JSON file at `path`, when given. Throws ex-info
  saying what's wrong with the file."
  [path]
  (if-not path
    defaults
    (let [text (try (slurp path)
                    (catch java.io.IOException e
                      (throw (ex-info (format "Couldn't read the settings file %s: %s" path (ex-message e))
                                      {:type ::unreadable}))))
          settings (try (parse text)
                        (catch Exception e
                          (throw (ex-info (format "The settings file %s isn't valid JSON: %s" path (ex-message e))
                                          {:type ::invalid-json}))))]
      (when-not (map? settings)
        (throw (ex-info (format "The settings file %s must hold a JSON object." path)
                        {:type ::invalid-json})))
      (deep-merge defaults settings))))

(defn timezone
  "The `timezone` setting, else the system's."
  [config]
  (or (:timezone config) (str (ZoneId/systemDefault))))

(defn state-dir [config]
  (or (not-empty (:state-dir config))
      (not-empty (System/getenv "STATE_DIRECTORY"))
      (str (or (not-empty (System/getenv "XDG_STATE_HOME"))
               (str (System/getProperty "user.home") "/.local/state"))
           "/grog")))

(defn provider-config
  "Settings of `provider` (the active one by default), with the :state-dir."
  ([config] (provider-config config (:provider config)))
  ([config provider]
   (some-> (get-in config [:providers (keyword provider)])
           (assoc :state-dir (state-dir config)))))

(defn non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

(defn port? [v]
  (and (int? v) (<= 0 v 65535)))

(defn url? [v]
  (and (string? v) (boolean (re-matches #"https?://\S+" v))))

(defn ^:private zone? [v]
  (try (ZoneId/of v) true (catch Exception _ false)))

(defn check
  "A problem when the value at `path` in `settings` isn't `pred`: names the
  setting (under `prefix`, where `settings` sit) and what's `expected`."
  ([settings path pred expected]
   (check settings path pred expected []))
  ([settings path pred expected prefix]
   (let [v (get-in settings path)]
     (when-not (pred v)
       (format "%s: expected %s, got %s"
               (setting-name (concat prefix path)) expected (if (nil? v) "nothing" (pr-str v)))))))

(defn problems
  "What's wrong with `config`, as messages naming the settings the way they're
  written. The providers check their own settings (grog.provider/problems)."
  [config]
  (->> [(when-let [unknown (seq (remove (set (keys defaults)) (keys config)))]
          (str "unknown settings: " (str/join ", " (map #(setting-name [%]) unknown))))
        (check config [:name] non-blank-string? "a name")
        (check config [:language] (some-fn nil? non-blank-string?) "a language, like \"English\", or null")
        (check config [:timezone] (some-fn nil? zone?) "a time zone, like \"America/Sao_Paulo\", or null")
        (check config [:location] (some-fn nil? map?) "{city, region, country} or null")
        (check config [:system-prompt] (some-fn nil? string?) "a template or null")
        (check config [:system-prompt-file] (some-fn nil? #(and (string? %) (fs/readable? %)))
               "a readable file or null")
        (when (and (non-blank-string? (:system-prompt config)) (:system-prompt-file config))
          "systemPrompt, systemPromptFile: set only one of them")
        (check config [:instructions] (some-fn nil? string?) "text or null")
        (check config [:max-history-messages] pos-int? "a positive integer")
        (check config [:conversation-ttl-minutes] pos-int? "a positive integer")
        (check config [:memory :learn] boolean? "true or false")
        (check config [:server :host] non-blank-string? "an address, like \"127.0.0.1\"")
        (check config [:server :port] port? "a port number")
        (check config [:server :allowed-hosts] #(and (sequential? %) (every? non-blank-string? %))
               "a list of host names, like [\"localhost\"]")
        (check config [:nrepl :enable] boolean? "true or false")
        (check config [:nrepl :host] non-blank-string? "an address, like \"127.0.0.1\"")
        (check config [:nrepl :port] port? "a port number")
        (check config [:hands :socket] (some-fn nil? non-blank-string?) "a unix socket path, or null")
        (check config [:skin :dir] (some-fn nil? #(and (string? %) (fs/directory? %))) "a directory or null")
        (check config [:skin :captions] boolean? "true or false")
        (check config [:skin :screen-off-minutes] (some-fn nil? #(and (number? %) (pos? %)))
               "a number of minutes, or null to keep the screen on")
        (check config [:skin :screen-off] #{"auto" "dark" "off"} "\"auto\", \"dark\" or \"off\"")
        (check config [:update :status] (some-fn nil? non-blank-string?) "a file path, or null")
        (check config [:music :socket] (some-fn nil? non-blank-string?) "a unix socket path, or null")
        (check config [:music :provider] (some-fn nil? non-blank-string?)
               "what plays music, like \"youtube-music\", or null")
        (check config [:computers :ssh] non-blank-string? "a program, like \"ssh\"")
        (when (contains? (:computers config) :pairing)
          "computers.pairing: now integrations.host and integrations.port")
        (check config [:integrations :host] non-blank-string? "an address, like \"0.0.0.0\"")
        (check config [:integrations :port] port? "a port number")
        (check config [:integrations :runner] (some-fn nil? non-blank-string?) "a unix socket path, or null")
        (check config [:integrations :relay] #(and (url? %) (str/ends-with? % "/"))
               "the address of a page, ending in /")
        (check config [:state-dir] (some-fn nil? non-blank-string?) "a directory or null")
        (check config [:provider] non-blank-string? "a provider, like \"openai\"")
        (check config [:providers] map? "the settings of each provider")]
       (remove nil?)
       vec))
