(ns grog.main
  (:require [babashka.cli :as cli]
            [clojure.string :as str]
            [grog.client :as client]
            [grog.config :as config]
            [grog.hands :as hands]
            [grog.runner :as runner]
            [grog.server :as server]
            [grog.update :as update]
            [grog.updater :as updater]
            [grog.validation :as validation]))

(def usage
  (str "Usage: grog <command> [options]

Commands:
  serve [--config file.json]  start the server (what runs under systemd)
  hands --socket PATH         run commands on the machine for the server (what
                              runs as the speaker's user, see `hands.socket`)
  mcp --socket PATH           run MCP servers from npm and PyPI for the server,
                              apart from it (see `integrations.runner`)
  chat  [--url URL]           interactive chat with the server
  ask   [--url URL] [--new] [question]
                              ask a question and print the answer
                              (reads it from stdin when omitted). Follow-up
                              questions continue the conversation until it's
                              idle for a while; --new (-n) starts a new one
  integrations                what grog can connect to, and what's connected
  integrations add NAME|npm:PKG@VERSION|pypi:PKG==VERSION|https://...
                              adds an MCP server to it: from the MCP
                              Registry, a package (run apart from grog), or
                              an address; grog doesn't check them
  integrations remove ID      removes one you added, disconnecting it
  integrations search TEXT    servers of the MCP Registry whose name holds
                              TEXT (it can take a while)
  connect ID [--browser]      connects one, saying what to do, with a page
                              on the network for any phone or computer:
                              openai signs in to the ChatGPT subscription,
                              with a code (or in a browser, --browser);
                              computer pairs one of your computers (nothing
                              to install there); an MCP server asks what it
                              needs on that page
  disconnect ID               disconnects one (`grog integrations` lists
                              them); a computer stops letting grog in
  tools ID                    the tools of a connected MCP server: what grog
                              does with each (allow, ask first, deny) and
                              whether it sees it
  tools ID TOOL allow|ask|deny|show|hide|default
                              changes that for one tool (default: back to
                              how it comes)
  login [provider] [--browser]
                              grog connect <provider>: the server's by
                              default
  logout [provider]           signs out of it
  status                      provider, model and credentials in use, and the
                              settings changed by asking grog
  settings reset              puts those back as configured
  models                      models the provider offers
  mic on|off                  turns grog-voice's mic on or off (off, nothing
                              is heard until it's on again, even after
                              restarts)
  update                      updates grog to the latest grog pushed, saying
                              how it goes (services.grog.update on NixOS)
  updater --flake DIR --host NAME --input NAME --status FILE
                              what updates grog, as root (what the
                              grog-update service runs)

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

(defn ^:private login [{:keys [opts]}]
  (exit (client/login! (url opts) (:provider opts) (:browser opts))))

(defn ^:private logout [{:keys [opts]}]
  (exit (client/logout! (url opts) (:provider opts))))

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

(defn ^:private serve-mcp [{:keys [opts]}]
  (if (str/blank? (:socket opts))
    (die "Usage: grog mcp --socket PATH")
    (runner/serve! (:socket opts))))

(defn ^:private update-grog [{:keys [opts]}]
  (exit (client/update! (url opts))))

(defn ^:private run-updater [{:keys [opts]}]
  (let [{:keys [flake host input status]} opts]
    (when (some str/blank? [flake host input status])
      (die "Usage: grog updater --flake DIR --host NAME --input NAME --status FILE"))
    (exit (not= "failed" (:stage (updater/update! {:flake flake :host host :input input :status status
                                                   :revision (update/revision)}
                                                  (updater/system (client/server-url nil))))))))

(defn ^:private integrations [{:keys [opts]}]
  (exit (client/integrations! (url opts))))

(defn ^:private connect [{:keys [opts]}]
  (if (str/blank? (:id opts))
    (die "Usage: grog connect ID [--browser] (grog integrations lists them)")
    (exit (client/connect! (url opts) (:id opts) (when (:browser opts) "browser")))))

(defn ^:private disconnect [{:keys [opts]}]
  (if (str/blank? (:id opts))
    (die "Usage: grog disconnect ID (grog integrations lists them)")
    (exit (client/disconnect! (url opts) (:id opts)))))

(defn ^:private usage-error [message]
  (binding [*out* *err*] (println message))
  (System/exit 2))

(defn ^:private add-integration [{:keys [opts]}]
  (if (str/blank? (:what opts))
    (usage-error "Usage: grog integrations add NAME|npm:PKG@VERSION|pypi:PKG==VERSION|https://...")
    (exit (client/add-integration! (url opts) (:what opts)))))

(defn ^:private remove-integration [{:keys [opts]}]
  (if (str/blank? (:id opts))
    (usage-error "Usage: grog integrations remove ID (grog integrations lists them)")
    (exit (client/remove-integration! (url opts) (:id opts)))))

(defn ^:private search-integrations [{:keys [opts args]}]
  (let [text (str/trim (str/join " " args))]
    (if (str/blank? text)
      (usage-error "Usage: grog integrations search TEXT")
      (exit (client/search! (url opts) text)))))

(defn ^:private tools [{:keys [opts]}]
  (let [{:keys [id tool choice]} opts]
    (cond
      (str/blank? id) (usage-error "Usage: grog tools ID [TOOL allow|ask|deny|show|hide|default]")
      (str/blank? tool) (exit (client/tools! (url opts) id))
      (not (contains? client/tool-choices choice))
      (usage-error "Usage: grog tools ID TOOL allow|ask|deny|show|hide|default")
      :else (exit (client/set-tool! (url opts) id tool choice)))))

(defn ^:private help [_]
  (println usage))

;; Positional arguments become options (:args->opts), for the options after
;; them to be read too: grog login openai --browser.
(def commands
  [{:cmds ["serve"] :fn serve}
   {:cmds ["hands"] :fn serve-hands}
   {:cmds ["mcp"] :fn serve-mcp}
   {:cmds ["chat"] :fn chat}
   {:cmds ["ask"] :fn ask :spec {:new {:coerce :boolean :alias :n}}}
   {:cmds ["login"] :fn login :args->opts [:provider] :spec {:provider {:coerce :string}
                                                             :browser {:coerce :boolean}}}
   {:cmds ["logout"] :fn logout :args->opts [:provider] :spec {:provider {:coerce :string}}}
   {:cmds ["status"] :fn status}
   {:cmds ["settings" "reset"] :fn reset-settings}
   {:cmds ["models"] :fn models}
   {:cmds ["mic"] :fn mic}
   {:cmds ["update"] :fn update-grog}
   {:cmds ["updater"] :fn run-updater :spec {:flake {:coerce :string}
                                             :host {:coerce :string}
                                             :input {:coerce :string}
                                             :status {:coerce :string}}}
   {:cmds ["integrations" "add"] :fn add-integration :args->opts [:what] :spec {:what {:coerce :string}}}
   {:cmds ["integrations" "remove"] :fn remove-integration :args->opts [:id] :spec {:id {:coerce :string}}}
   {:cmds ["integrations" "search"] :fn search-integrations}
   {:cmds ["integrations"] :fn integrations}
   {:cmds ["tools"] :fn tools :args->opts [:id :tool :choice] :spec {:id {:coerce :string}
                                                                     :tool {:coerce :string}
                                                                     :choice {:coerce :string}}}
   {:cmds ["connect"] :fn connect :args->opts [:id] :spec {:id {:coerce :string} :browser {:coerce :boolean}}}
   {:cmds ["disconnect"] :fn disconnect :args->opts [:id] :spec {:id {:coerce :string}}}
   {:cmds [] :fn help}])

(defn -main [& args]
  (cli/dispatch commands args))
