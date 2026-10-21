(ns grog.test-helpers
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.sse :as sse]
            [org.httpkit.server :as http])
  (:import (java.util Base64)))

(defn sse
  "Serializes events (maps with a :type) as a Server-Sent Events body."
  [& events]
  (apply str (for [event events]
               (sse/message (:type event) event))))

(defn start-handler!
  "Starts an HTTP server on loopback running the ring `handler`.
  Returns {:url :stop!}."
  [handler]
  (let [server (http/run-server handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    {:url (str "http://127.0.0.1:" (http/server-port server))
     :stop! #(http/server-stop! server)}))

(defn start-fake!
  "Starts an HTTP server that answers each request with the next of
  `responses` (ring response maps, SSE content type by default, optionally
  with a :delay-ms before answering) and records the requests.
  Returns {:url :requests :stop!}."
  [responses]
  (let [requests (atom [])
        queue (atom responses)
        server (start-handler!
                (fn [req]
                  (swap! requests conj {:method (:request-method req)
                                        :uri (:uri req)
                                        :query (:query-string req)
                                        :headers (:headers req)
                                        :body (some-> (:body req) slurp (json/parse-string true))})
                  (let [[pending] (swap-vals! queue rest)]
                    (if-let [{:keys [delay-ms] :as resp} (first pending)]
                      (do (some-> delay-ms Thread/sleep)
                          (merge {:status 200 :headers {"Content-Type" "text/event-stream"}}
                                 (dissoc resp :delay-ms)))
                      {:status 500 :body "{\"error\": {\"message\": \"no more fake responses\"}}"}))))]
    (assoc server :requests requests)))

(defmacro with-fake
  "Binds `sym` to a fake server (see `start-fake!`) while running `body`."
  [[sym responses] & body]
  `(let [~sym (start-fake! ~responses)]
     (try ~@body
          (finally ((:stop! ~sym))))))

(defn collector
  "Returns [on-text chunks-atom]."
  []
  (let [chunks (atom [])]
    [#(swap! chunks conj %) chunks]))

(defn json-response [body]
  {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string body)})

(defn jwt
  "An unsigned JWT carrying `claims`."
  [claims]
  (str "e30."
       (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes (json/generate-string claims) "UTF-8"))
       ".sig"))

(defn token-response
  "What OpenAI's token endpoint answers."
  [access-token refresh-token]
  (json-response {:access_token access-token
                  :refresh_token refresh-token
                  :id_token (jwt {"email" "grog@example.com"
                                  "https://api.openai.com/auth" {"chatgpt_account_id" "acct-1"}})
                  :expires_in 3600}))

(defn temp-dir []
  (str (fs/create-temp-dir {:prefix "grog-test"})))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn sh-quote
  "`s` quoted for bash."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn script!
  "An executable bash script `name` in `dir`, running `body`."
  [dir name body]
  (let [f (str (fs/path dir name))]
    (fs/create-dirs dir)
    (spit f (str "#!" (fs/which "bash") "\n" body "\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    f))

(def computer-sh
  "A computer's side of grog, resources/grog/computer.sh: what pairs it,
  then grog's hands there."
  (str (io/file (io/resource "grog/computer.sh"))))

(def quiet-commands
  "What grog's hands run on a computer, faked: nothing happens, and nobody's
  in (no sessions, nothing playing). notify-send answers being asked with
  $ANSWER, and wpctl says the volume is $VOLUME."
  {"notify-send" "if [[ \" $* \" == *\" --wait \"* ]]; then echo 17; printf '%s\\n' \"${ANSWER-}\"; fi"
   "systemd-run" ""
   "xdg-open" ""
   "playerctl" ""
   "wpctl" "if [[ $1 == get-volume ]]; then echo \"Volume: ${VOLUME:-0.40}\"; fi"
   "loginctl" ""
   "busctl" ""
   "sudo" "exit 1"})

(defn computer!
  "A computer of its own, in a temp directory: its user's home, and
  `commands` (name -> bash, over `quiet-commands`) first in its PATH,
  keeping how they were run (`ran`). What its hands run with in :env."
  ([] (computer! {}))
  ([commands]
   (let [dir (temp-dir)
         home (str (fs/path dir "home"))
         bin (str (fs/path dir "bin"))
         log (sh-quote (fs/path dir "ran"))]
     (fs/create-dirs home)
     (fs/create-dirs (fs/path dir "run"))
     (doseq [[name body] (merge quiet-commands commands)]
       (script! bin name (str "printf '%s\\x1f' \"${0##*/}\" \"$@\" >> " log "\nprintf '\\x1e' >> " log "\n" body)))
     {:dir dir
      :home home
      :authorized-keys (str (fs/path home ".ssh" "authorized_keys"))
      :policy-file (str (fs/path home ".config" "grog" "hands.json"))
      :hands-file (str (fs/path home ".local" "share" "grog" "computer.sh"))
      :env {"HOME" home
            "XDG_CONFIG_HOME" (str (fs/path home ".config"))
            "XDG_DATA_HOME" (str (fs/path home ".local" "share"))
            "XDG_RUNTIME_DIR" (str (fs/path dir "run"))
            "PATH" (str bin ":" (System/getenv "PATH"))}})))

(defn ran
  "How `computer`'s faked commands were run, in order: [[name arg...]...]."
  [{:keys [dir]}]
  (let [log (fs/file dir "ran")]
    (if (fs/exists? log)
      (mapv #(vec (butlast (str/split % #"\u001f" -1))) (remove empty? (str/split (slurp log) #"\u001e")))
      [])))

(defn env-words
  "`env` as bash words: NAME='value'..."
  [env]
  (str/join " " (for [[k v] env] (str k "=" (sh-quote v)))))
