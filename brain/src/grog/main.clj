(ns grog.main
  (:require [babashka.cli :as cli]
            [clojure.string :as str]
            [grog.client :as client]
            [grog.config :as config]
            [grog.hands :as hands]
            [grog.server :as server]
            [grog.validation :as validation]))

(def usage
  (str "Usage: grog <command> [options]

Commands:
  serve [--config file.json]  start the server (what runs under systemd)
  hands --socket PATH         run commands on the machine for the server (what
                              runs as the speaker's user, see `hands.socket`)
  chat  [--url URL]           interactive chat with the server
  ask   [--url URL] [--new] [question]
                              ask a question and print the answer
                              (reads it from stdin when omitted). Follow-up
                              questions continue the conversation until it's
                              idle for a while; --new (-n) starts a new one
  login [provider] [--browser]
                              sign in to the provider's subscription (openai:
                              ChatGPT; the server's provider by default) with
                              a code entered on any phone or computer, or
                              with a browser on this machine (--browser)
  logout [provider]           sign out
  status                      provider, model and credentials in use, and the
                              settings changed by asking grog
  settings reset              puts those back as configured
  models                      models the provider offers
  mic on|off                  turns grog-voice's mic on or off (off, nothing
                              is heard until it's on again, even after
                              restarts)

Server: $GROG_URL or " client/default-url))

(defn ^:private die [message]
  (binding [*out* *err*]
    (println message))
  (System/exit 1))

(defn ^:private startup-settings
  "The settings at `path` over the defaults, checked. Exits saying what's
  wrong otherwise."
  [path]
  (let [config (try (config/load-config path)
                    (catch clojure.lang.ExceptionInfo e
                      (die (ex-message e))))
        problems (validation/problems config)]
    (when (seq problems)
      (die (str "Invalid settings" (when path (str " in " path)) ":\n"
                (str/join "\n" (map #(str "- " %) problems)))))
    config))

(defn ^:private serve [{:keys [opts]}]
  (server/serve! (startup-settings (or (:config opts) (System/getenv "GROG_CONFIG")))))

(defn ^:private url [opts]
  (client/server-url (:url opts)))

(defn ^:private exit [ok?]
  (when-not ok? (System/exit 1)))

(defn ^:private chat [{:keys [opts]}]
  (client/chat! (url opts)))

(defn ^:private ask [{:keys [opts args]}]
  (let [question (str/trim (if (seq args) (str/join " " args) (slurp *in*)))]
    (when (str/blank? question)
      (binding [*out* *err*] (println usage))
      (System/exit 2))
    (let [ok? (client/ask! (url opts) {:conversation-id "default" :new? (:new opts)} question *out*)]
      (println)
      (exit ok?))))

(defn ^:private login [{:keys [opts args]}]
  (exit (client/login! (url opts) (first args) (:browser opts))))

(defn ^:private logout [{:keys [opts args]}]
  (exit (client/logout! (url opts) (first args))))

(defn ^:private status [{:keys [opts]}]
  (exit (client/status! (url opts))))

(defn ^:private models [{:keys [opts]}]
  (exit (client/models! (url opts))))

(defn ^:private reset-settings [{:keys [opts]}]
  (exit (client/reset-settings! (url opts))))

(defn ^:private mic [{:keys [opts args]}]
  (case (first args)
    "on" (exit (client/mic! (url opts) true))
    "off" (exit (client/mic! (url opts) false))
    (do (binding [*out* *err*] (println "Usage: grog mic on|off"))
        (System/exit 2))))

(defn ^:private serve-hands [{:keys [opts]}]
  (if (str/blank? (:socket opts))
    (die "Usage: grog hands --socket PATH")
    (hands/serve! (:socket opts))))

(defn ^:private help [_]
  (println usage))

(def commands
  [{:cmds ["serve"] :fn serve}
   {:cmds ["hands"] :fn serve-hands}
   {:cmds ["chat"] :fn chat}
   {:cmds ["ask"] :fn ask :spec {:new {:coerce :boolean :alias :n}}}
   {:cmds ["login"] :fn login :spec {:browser {:coerce :boolean}}}
   {:cmds ["logout"] :fn logout}
   {:cmds ["status"] :fn status}
   {:cmds ["settings" "reset"] :fn reset-settings}
   {:cmds ["models"] :fn models}
   {:cmds ["mic"] :fn mic}
   {:cmds [] :fn help}])

(defn -main [& args]
  (cli/dispatch commands args))
