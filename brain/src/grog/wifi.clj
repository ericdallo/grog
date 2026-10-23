(ns grog.wifi
  "The machine's Wi-Fi, through its hands (grog.hands) and NetworkManager,
  for whoever's at grog's screen (the `wifi` action): whether it's on, the
  networks it sees and which it's on, and joining one, with a password
  typed there when it needs one. resources/grog/wifi.sh does it with nmcli:
  a password only goes there, never in a log or an answer.

  nmcli's `device wifi connect` uses a profile saved for the network, the
  password given going in it, or makes one, bound to the device it joins
  on: wifi.sh unbinds it once it works (any Wi-Fi device can use it then,
  a USB one plugged elsewhere too), and deletes it when it doesn't, as a
  wrong password would leave it there otherwise."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.hands :as hands]))

(def ^:private script
  (delay (slurp (io/resource "grog/wifi.sh"))))

;; nmcli waits 40 s for a network to join (wifi.sh): a bit more for it, and
;; for the profiles it looks at.
(def ^:private connect-timeout-ms 60000)

;; What an SSID can be (32 bytes) and a password (WPA's 63 characters, or 64
;; hex digits, more than WEP's).
(def ^:private max-ssid-bytes 32)
(def ^:private max-password-chars 64)

(defn command
  "The hands' command running wifi.sh with `args`: what to do, then what
  with."
  [& args]
  (str "set -- " (str/join " " (map hands/quoted args)) "\n" @script))

(defn fields
  "The fields of a line of nmcli's terse output (-t -e yes), unescaped."
  [line]
  (loop [cs (seq line)
         field (StringBuilder.)
         out []]
    (if-let [[c & more] cs]
      (cond
        (and (= \\ c) more) (recur (next more) (.append field (first more)) out)
        (= \: c) (recur more (StringBuilder.) (conj out (str field)))
        :else (recur more (.append field c) out))
      (conj out (str field)))))

(defn ^:private sections
  "What wifi.sh's status printed, by section: {:radio line, \"devices\"
  [lines]...}."
  [output]
  (loop [[line & more] (str/split-lines (str output))
         section nil
         out {}]
    (cond
      (nil? line) out
      (str/starts-with? line "radio:") (recur more section (assoc out :radio (subs line 6)))
      (str/starts-with? line "--") (recur more (subs line 2) out)
      (and section (not (str/blank? line))) (recur more section (update out section (fnil conj []) line))
      :else (recur more section out))))

(defn ^:private security
  "What a network asks to join it, as nmcli says its security: open,
  password, or enterprise (a user and more, which grog can't take)."
  [s]
  (cond
    (str/includes? s "802.1X") "enterprise"
    (or (str/blank? s) (re-matches #"OWE(-TM)?" (str/trim s))) "open"
    :else "password"))

(defn ^:private signal [s]
  (or (parse-long (str s)) 0))

(defn status
  "What wifi.sh's status printed, made sense of: {:radio \"on\" or \"off\",
  :devices [{:device :state :connection}], the Wi-Fi ones NetworkManager
  manages, :networks
  [{:ssid :signal :security :device :active :saved}]}. Each network once,
  by its SSID, hidden ones aside: its best signal and the device seeing it
  so, active when a device is on it; those it's on first, then the
  strongest."
  [output]
  (let [{:keys [radio] :as s} (sections output)
        saved (set (map (comp first fields) (get s "saved")))
        seen (for [line (get s "networks")
                   :let [[in-use ssid strength sec device] (fields line)]
                   :when (and device (not (str/blank? ssid)))]
               {:ssid ssid
                :signal (signal strength)
                :security (security sec)
                :device device
                :active (= "*" in-use)})]
    {:radio (if (= "enabled" (some-> radio str/trim)) "on" "off")
     :devices (vec (for [line (get s "devices")
                         :let [[device type state connection] (fields line)]
                         :when (and (= "wifi" type) (not= "unmanaged" state))]
                     {:device device :state state :connection (not-empty connection)}))
     :networks (->> (group-by :ssid seen)
                    (map (fn [[ssid all]]
                           (assoc (apply max-key :signal all)
                                  :active (boolean (some :active all))
                                  :saved (contains? saved ssid))))
                    (sort-by (juxt (comp not :active) (comp - :signal) :ssid))
                    vec)}))

(defn ^:private failure
  "What went wrong joining a network, in a few words, from what nmcli said:
  never anything typed, which it doesn't repeat."
  [output]
  (let [text (str output)
        said (some->> (str/split-lines text)
                      (filter #(str/starts-with? % "Error: "))
                      last
                      (#(subs % 7)))]
    (cond
      (re-find #"Secrets were required" text) "the password didn't work"
      (re-find #"No network with SSID" text) "that network isn't in sight now"
      (re-find #"NetworkManager is not running" text) "NetworkManager isn't running here"
      said (str/replace said #"\.$" "")
      :else "it didn't join it")))

(defn ^:private script!
  "Runs wifi.sh with `args` through the hands, for at most `timeout-ms`: its
  output, or ex-info with `error` (a function of the output) when it fails."
  [config timeout-ms error & args]
  (let [{:keys [exit output timed-out]} (hands/ask! (get-in config [:hands :socket]) (apply command args) timeout-ms)]
    (cond
      timed-out (throw (ex-info "the Wi-Fi took too long to answer" {:type ::late}))
      (not= 0 exit) (throw (ex-info (error output) {:type ::failed}))
      :else output)))

(defn ^:private what-status-said
  "What a failing status said: NetworkManager's one line, or less."
  [output]
  (let [line (last (str/split-lines (str/trim (str output))))]
    (if (re-find #"NetworkManager is not running" (str line))
      "NetworkManager isn't running here"
      (str "the Wi-Fi couldn't be read" (when-not (str/blank? line) (str ": " (str/replace line #"^Error: " "")))))))

(defn status!
  "The machine's Wi-Fi now (see `status`), rescanning as nmcli's --rescan
  says (`rescan`: auto, yes or no)."
  [config rescan]
  (status (script! config hands/default-timeout-ms what-status-said "status" rescan)))

(defn ^:private ssid? [v]
  (and (string? v) (not (str/blank? v)) (<= (count (.getBytes ^String v "UTF-8")) max-ssid-bytes)))

(defn ^:private password? [v]
  (and (string? v) (<= 1 (count v) max-password-chars) (not (re-find #"[\p{Cntrl}]" v))))

(defn join!
  "Joins network `ssid`, in sight, on the device seeing it best, with
  `password` when given (or the one saved for it), unless a device is on it
  already: ex-info saying why it can't, or didn't."
  [config ssid password]
  (let [network (some #(when (= ssid (:ssid %)) %) (:networks (status! config "no")))]
    (cond
      (nil? network)
      (throw (ex-info "that network isn't in sight now" {:type ::unseen}))

      (= "enterprise" (:security network))
      (throw (ex-info "that network wants more than a password, which can't be typed here" {:type ::unsupported}))

      (and (= "password" (:security network)) (not (:saved network)) (nil? password))
      (throw (ex-info "that network needs its password" {:type ::password}))

      (and (:active network) (nil? password))
      nil

      :else
      (apply script! config connect-timeout-ms failure "connect" ssid (:device network) (when password [password])))))

(defn act!
  "What the `wifi` action asks (`args`): {} tells the Wi-Fi, {:rescan true}
  after looking again, {:on true} after turning it on, {:connect ssid
  :password ...} after joining that network. Returns `status`, with :joined
  the network it joined."
  [{:keys [config]} {:keys [connect password rescan on]}]
  (when-not (and (or (nil? connect) (ssid? connect))
                 (or (nil? password) (and connect (password? password)))
                 (or (nil? rescan) (boolean? rescan))
                 (or (nil? on) (true? on)))
    (throw (ex-info (str "say what to do: {}, {\"rescan\": true}, {\"on\": true}, or {\"connect\": a network's "
                         "name, and maybe its \"password\"}")
                    {:type ::bad-args})))
  (let [config @config]
    (when on
      (script! config hands/default-timeout-ms what-status-said "on"))
    (when connect
      (join! config connect password))
    (cond-> (status! config (cond rescan "yes" (or on connect) "no" :else "auto"))
      connect (assoc :joined connect))))
