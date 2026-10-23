(ns grog.voice-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.assistant :as assistant]
            [grog.config :as config]
            [grog.events :as events]
            [grog.test-helpers :as h]
            [grog.voice :as voice]))

(defn ^:private context [state-dir]
  (assistant/new-context (assoc config/defaults :state-dir state-dir)))

(deftest the-mic-is-on-until-turned-off-for-good
  (let [dir (h/temp-dir)
        ctx (context dir)
        sent (atom [])]
    (is (= "on" (voice/mic (:voice ctx))))
    (voice/watch! (:voice ctx) :w #(do (swap! sent conj %) true))
    (is (= {:mic "off"} (voice/set-mic! ctx false)))
    (testing "grog-voice is told, after what it got first"
      (is (= 2 (count @sent)))
      (is (str/starts-with? (first @sent) "event: snapshot\ndata: {\"mic\":\"on\",\"music\":false,\"speaker\":null}"))
      (is (str/starts-with? (second @sent) "event: mic\ndata: {\"mic\":\"off\"}")))
    (testing "and so is whoever watches grog"
      (is (= "off" (:mic (events/snapshot (:events ctx))))))
    (testing "kept, for grog's user only"
      (is (= "off" (voice/mic (:voice (context dir)))))
      (let [file (voice/file (assoc config/defaults :state-dir dir))]
        (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions file))))))))

(deftest a-bad-file-leaves-it-on
  (let [dir (h/temp-dir)]
    (spit (voice/file (assoc config/defaults :state-dir dir)) "{\"mic\": \"maybe\"}")
    (let [log (with-out-str (is (true? (voice/load-mic (assoc config/defaults :state-dir dir)))))]
      (is (str/includes? log "ignoring whether the mic was on")))))

(deftest asks-whoever-watches
  (let [channel (voice/new-channel (assoc config/defaults :state-dir (h/temp-dir)))
        sent (atom [])]
    (is (not (voice/there? channel)))
    (voice/watch! channel :w #(do (swap! sent conj %) (< (count @sent) 3)))
    (is (voice/there? channel))
    (voice/ask! channel :listen)
    (is (= "event: listen\ndata: {}\n\n" (last @sent)))
    (testing "until it's gone"
      (voice/ask! channel :stop)
      (is (not (voice/there? channel))))))

(deftest told-whether-music-plays-when-it-changes
  (let [channel (voice/new-channel (assoc config/defaults :state-dir (h/temp-dir)))
        sent (atom [])]
    (is (false? (voice/music channel)))
    (voice/watch! channel :w #(do (swap! sent conj %) true))
    (with-out-str
      (is (false? (voice/set-music! channel false)))
      (is (= 1 (count @sent)) "nothing when it's the same")
      (is (true? (voice/set-music! channel true)))
      (is (false? (voice/set-music! channel true))))
    (is (= ["event: music\ndata: {\"music\":true}\n\n"] (rest @sent)))
    (testing "and those who come later, at once"
      (voice/watch! channel :v #(do (swap! sent conj %) true))
      (is (str/starts-with? (last @sent) "event: snapshot\ndata: {\"mic\":\"on\",\"music\":true,\"speaker\":null}")))))

(deftest the-speaker-picked-is-kept-and-told
  (let [dir (h/temp-dir)
        ctx (context dir)
        sent (atom [])
        bt {:name "bluez_output.1" :label "EDIFIER"}]
    (is (nil? (voice/speaker (:voice ctx))) "its own, at first")
    (voice/watch! (:voice ctx) :w #(do (swap! sent conj %) true))
    (with-out-str
      (is (= bt (voice/set-speaker! ctx bt))))
    (testing "grog-voice is told its name"
      (is (= "event: speaker\ndata: {\"speaker\":\"bluez_output.1\"}\n\n" (last @sent))))
    (testing "and so is whoever watches grog"
      (is (= bt (:speaker (events/snapshot (:events ctx))))))
    (testing "kept with the mic, even when that changes"
      (with-out-str (voice/set-mic! ctx false))
      (let [again (context dir)]
        (is (= bt (voice/speaker (:voice again))))
        (is (= "off" (voice/mic (:voice again))))
        (voice/watch! (:voice again) :w #(do (swap! sent conj %) true))
        (is (str/starts-with? (last @sent) "event: snapshot\ndata: {\"mic\":\"off\",\"music\":false,\"speaker\":\"bluez_output.1\"}"))))
    (testing "until it speaks on its own again"
      (with-out-str (voice/set-speaker! ctx nil))
      (is (= "event: speaker\ndata: {\"speaker\":null}\n\n" (last @sent)))
      (is (nil? (voice/speaker (:voice (context dir))))))))

(deftest a-bad-speaker-is-forgotten
  (let [config (assoc config/defaults :state-dir (h/temp-dir))]
    (spit (voice/file config) "{\"mic\": \"off\", \"speaker\": {\"name\": \"\"}}")
    (let [log (with-out-str (is (nil? (voice/load-speaker config))))]
      (is (str/includes? log "ignoring the speaker picked")))
    (is (false? (voice/load-mic config)) "but not the mic")))

(deftest asks-grog-voice-for-its-speakers
  (let [channel (voice/new-channel (assoc config/defaults :state-dir (h/temp-dir)))
        sent (atom [])
        asked (fn [] (some->> (last @sent) (re-find #"event: speakers\ndata: (.*)\n") second))]
    (voice/watch! channel :w #(do (swap! sent conj %) true))
    (testing "and waits for what it says"
      (let [answer (future (voice/speakers! channel "bt"))]
        (is (h/eventually 2000 #(= "{\"id\":1,\"music\":\"bt\"}" (asked))))
        (is (false? (voice/speakers-said! channel {:id 2 :speakers []})) "not what was asked")
        (is (true? (voice/speakers-said! channel {:id 1 :speakers [{:name "bt"}]})))
        (is (= [{:name "bt"}] (deref answer 2000 :not-yet)))))
    (testing "or why it can't"
      (let [answer (future (try (voice/speakers! channel nil) (catch Exception e (ex-message e))))]
        (is (h/eventually 2000 #(= "{\"id\":2}" (asked))))
        (voice/speakers-said! channel {:id 2 :error "pw-dump exited with 1"})
        (is (= "pw-dump exited with 1" (deref answer 2000 :not-yet)))))
    (testing "and not forever"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"didn't say which speakers"
                            (voice/speakers! channel nil 50)))
      (is (false? (voice/speakers-said! channel {:id 3 :speakers []})) "no longer asked"))))

(deftest the-model-turns-it-off
  (let [ctx (context (h/temp-dir))]
    (is (= {:mic "off"} ((:run voice/tool) ctx {:on false} {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"on must be true or false"
                          ((:run voice/tool) ctx {:on "no"} {})))))
