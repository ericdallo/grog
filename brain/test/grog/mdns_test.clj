(ns grog.mdns-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [grog.mdns :as mdns]
            [grog.test-helpers :as h])
  (:import (java.net DatagramPacket DatagramSocket InetAddress)))

(def ^:private codes {:a 1 :ptr 12 :txt 16 :aaaa 28 :srv 33})

(defn ^:private u16b [n]
  [(bit-and (bit-shift-right n 8) 0xff) (bit-and n 0xff)])

(defn ^:private encode-name
  "Appends `name` (a string, or a vector of labels) to the packet in
  `state`, pointing to where a suffix of it already is."
  [state name]
  (loop [labels (if (string? name) (str/split name #"\.") name)
         state state]
    (let [suffix (str/lower-case (str/join "." labels))]
      (cond
        (empty? labels) (update state :out conj 0)
        (get-in state [:offsets suffix])
        (update state :out into (let [at (get-in state [:offsets suffix])]
                                  [(bit-or 0xc0 (bit-shift-right at 8)) (bit-and at 0xff)]))
        :else
        (let [b (.getBytes ^String (first labels) "UTF-8")]
          (recur (rest labels)
                 (-> state
                     (assoc-in [:offsets suffix] (count (:out state)))
                     (update :out into (cons (count b) b)))))))))

(defn ^:private encode-rdata [state type data]
  (case type
    (:ptr) (encode-name state data)
    (:srv) (-> state
               (update :out into (mapcat u16b [(:priority data 0) (:weight data 0) (:port data)]))
               (encode-name (:target data)))
    (:txt) (update state :out into (mapcat (fn [^String s] (let [b (.getBytes s "UTF-8")] (cons (count b) b))) data))
    (:a) (update state :out into (map parse-long (str/split data #"\.")))
    (:aaaa) (update state :out into data)))

(defn ^:private encode-record [state {:keys [name type data]}]
  (let [state (-> (encode-name state name)
                  (update :out into (concat (u16b (codes type)) (u16b 0x8001) [0 0 0 120])))
        at (count (:out state))
        state (encode-rdata (update state :out into [0 0]) type data)
        [hi lo] (u16b (- (count (:out state)) at 2))]
    (update state :out #(-> % (assoc at hi) (assoc (inc at) lo)))))

(defn ^:private response
  "The bytes of a DNS response with compressed names: {:id :questions
  [[name type]] :answers :additional}, records {:name :type :data}."
  [{:keys [id questions answers additional]}]
  (let [state {:out (vec (mapcat u16b [(or id 0) 0x8400 (count questions) (count answers) 0 (count additional)]))
               :offsets {}}
        state (reduce (fn [state [name type]]
                        (-> (encode-name state name)
                            (update :out into (concat (u16b (codes type)) (u16b 1)))))
                      state questions)
        state (reduce encode-record state (concat answers additional))]
    (byte-array (map unchecked-byte (:out state)))))

(defn ^:private bytes-of [& ints]
  (byte-array (map unchecked-byte (flatten ints))))

(def ^:private service "_home-assistant._tcp")

(def ^:private home "Home._home-assistant._tcp.local")

(def ^:private home-records
  [{:name "_home-assistant._tcp.local" :type :ptr :data home}
   {:name home :type :srv :data {:port 8123 :target "abc.local"}}
   {:name home :type :txt :data ["base_url=http://192.168.15.50:8123" "Version=2026.10.1" "requires_api_password"]}
   {:name "abc.local" :type :a :data "192.168.15.50"}
   {:name "abc.local" :type :aaaa :data [0xfe 0x80 0 0 0 0 0 0 0 0 0 0 0 0 0 1]}])

(defn ^:private malformed? [data]
  (try (mdns/parse-packet (bytes-of data)) false
       (catch clojure.lang.ExceptionInfo e (= ::mdns/malformed (:type (ex-data e))))))

(deftest parse-packet-reads-records
  (let [packet (mdns/parse-packet (response {:id 7
                                             :questions [["_home-assistant._tcp.local" :ptr]]
                                             :answers (take 1 home-records)
                                             :additional (drop 1 home-records)}))]
    (is (= 7 (:id packet)))
    (is (:response? packet))
    (is (= [["_home-assistant._tcp.local" :ptr]] (:questions packet)))
    (is (= [{:name "_home-assistant._tcp.local" :type :ptr :ttl 120 :data home}] (:answers packet)))
    (is (= [{:name home :type :srv :ttl 120 :data {:priority 0 :weight 0 :port 8123 :target "abc.local"}}
            {:name home :type :txt :ttl 120 :data {"base_url" "http://192.168.15.50:8123"
                                                   "version" "2026.10.1"
                                                   "requires_api_password" ""}}
            {:name "abc.local" :type :a :ttl 120 :data "192.168.15.50"}
            {:name "abc.local" :type :aaaa :ttl 120 :data "fe80::1"}]
           (:additional packet)))))

(deftest parse-packet-follows-compression-pointers
  (let [data (response {:answers home-records})]
    (testing "the helper compressed the names"
      (is (some #{(unchecked-byte 0xc0)} data)))
    (is (= ["_home-assistant._tcp.local" home home "abc.local" "abc.local"] (map :name (:answers (mdns/parse-packet data)))))))

(deftest parse-packet-reads-txt-strictly
  (is (= {"a" "1" "b" "" "c" "x=y"}
         (-> (response {:answers [{:name home :type :txt :data ["A=1" "a=2" "b" "=ignored" "" "c=x=y"]}]})
             mdns/parse-packet :answers first :data))))

(deftest parse-packet-writes-ipv6-canonically
  (let [aaaa (fn [groups]
               (-> (response {:answers [{:name "h.local" :type :aaaa :data (mapcat u16b groups)}]})
                   mdns/parse-packet :answers first :data))]
    (is (= "::" (aaaa [0 0 0 0 0 0 0 0])))
    (is (= "2001:db8::1" (aaaa [0x2001 0xdb8 0 0 0 0 0 1])))
    (is (= "2001:db8:0:1:1:1:1:1" (aaaa [0x2001 0xdb8 0 1 1 1 1 1])))
    (is (= "2001:0:0:1::1" (aaaa [0x2001 0 0 1 0 0 0 1])))
    (is (= "1:2:3:4:5:6:7:8" (aaaa [1 2 3 4 5 6 7 8])))
    (is (= "fe80::abcd:12" (aaaa [0xfe80 0 0 0 0 0 0xabcd 0x12])))))

(deftest parse-packet-escapes-dots-inside-labels
  (is (= "a\\.b\\\\c._x._tcp.local"
         (-> (response {:answers [{:name "_x._tcp.local" :type :ptr :data ["a.b\\c" "_x" "_tcp" "local"]}]})
             mdns/parse-packet :answers first :data))))

(deftest parse-packet-skips-what-it-doesnt-read
  (let [data (bytes-of (u16b 1) (u16b 0x8400) (u16b 0) (u16b 2) (u16b 0) (u16b 0)
                       1 (int \h) 0 (u16b 47) (u16b 1) [0 0 0 1] (u16b 2) [9 9]
                       1 (int \h) 0 (u16b 1) (u16b 3) [0 0 0 1] (u16b 4) [1 2 3 4]
                       1 (int \h) 0 (u16b 1) (u16b 1) [0 0 0 1] (u16b 4) [1 2 3 4])]
    (testing "an NSEC, an A of class CH; the extra record past the counts is ignored"
      (is (= [] (:answers (mdns/parse-packet data)))))))

(deftest parse-packet-rejects-malformed-packets
  (let [good (vec (response {:answers home-records}))]
    (testing "truncated"
      (is (malformed? [0 1 0]))
      (is (malformed? (subvec good 0 (quot (count good) 2))))
      (is (malformed? (subvec good 0 (dec (count good))))))
    (testing "a pointer to itself"
      (is (malformed? [(u16b 1) (u16b 0x8400) (u16b 1) (u16b 0) (u16b 0) (u16b 0) 0xc0 12 (u16b 12) (u16b 1)])))
    (testing "a pointer back to the start of the name it's in"
      (is (malformed? [(u16b 1) (u16b 0x8400) (u16b 1) (u16b 0) (u16b 0) (u16b 0)
                       1 (int \a) 0xc0 12 (u16b 12) (u16b 1)])))
    (testing "two pointers to each other"
      (is (malformed? [(u16b 1) (u16b 0x8400) (u16b 0) (u16b 2) (u16b 0) (u16b 0)
                       0xc0 30 (u16b 1) (u16b 1) [0 0 0 1] (u16b 4) [1 2 3 4]
                       0xc0 12 (u16b 1) (u16b 1) [0 0 0 1] (u16b 4) [1 2 3 4]])))
    (testing "a pointer past the end"
      (is (malformed? [(u16b 1) (u16b 0x8400) (u16b 1) (u16b 0) (u16b 0) (u16b 0) 0xff 0xff (u16b 12) (u16b 1)])))
    (testing "an A record of the wrong size"
      (is (malformed? [(u16b 1) (u16b 0x8400) (u16b 0) (u16b 1) (u16b 0) (u16b 0)
                       1 (int \h) 0 (u16b 1) (u16b 1) [0 0 0 1] (u16b 3) [1 2 3]])))
    (testing "a TXT string past its record"
      (is (malformed? [(u16b 1) (u16b 0x8400) (u16b 0) (u16b 1) (u16b 0) (u16b 0)
                       1 (int \h) 0 (u16b 16) (u16b 1) [0 0 0 1] (u16b 2) [5 (int \a)] (repeat 8 0)])))
    (testing "too big"
      (is (malformed? (concat good (repeat 65536 0)))))))

(deftest parse-packet-keeps-at-most-200-records
  (let [records (for [i (range 250)] {:name "h.local" :type :a :data (str "10.0." (quot i 256) "." (mod i 256))})]
    (is (= 200 (count (:answers (mdns/parse-packet (response {:answers records}))))))))

(deftest query-packet-round-trips
  (let [questions [["_home-assistant._tcp.local" :ptr]
                   ["Living\\032Room\\.1._home-assistant._tcp.local" :srv]
                   [home :txt]
                   ["abc.local" :a]
                   ["abc.local" :aaaa]]
        packet (mdns/parse-packet (mdns/query-packet 513 questions))]
    (is (= {:id 513 :response? false :answers [] :additional []
            :questions (assoc-in questions [1 0] "Living Room\\.1._home-assistant._tcp.local")}
           packet)))
  (is (thrown? clojure.lang.ExceptionInfo (mdns/query-packet 1 [[(str (apply str (repeat 64 "a")) ".local") :a]])))
  (is (thrown? clojure.lang.ExceptionInfo (mdns/query-packet 1 [["a.local" :mx]]))))

(defn ^:private parsed [records]
  (:answers (mdns/parse-packet (response {:answers records}))))

(deftest instances-join-the-records
  (is (= [{:instance home
           :name "Home"
           :host "abc.local"
           :port 8123
           :addresses ["192.168.15.50" "fe80::1"]
           :txt {"base_url" "http://192.168.15.50:8123" "version" "2026.10.1" "requires_api_password" ""}}]
         (mdns/instances (parsed home-records) service))))

(deftest instances-tell-what-is-missing
  (testing "no SRV, no TXT"
    (is (= [{:instance home :name "Home" :host nil :port nil :addresses [] :txt {}}]
           (mdns/instances (parsed (take 1 home-records)) service))))
  (testing "no addresses"
    (is (= [{:instance home :name "Home" :host "abc.local" :port 8123 :addresses [] :txt {}}]
           (mdns/instances (parsed (take 2 home-records)) service))))
  (testing "nothing of the service"
    (is (= [] (mdns/instances (parsed (drop 1 home-records)) service)))
    (is (= [] (mdns/instances (parsed home-records) "_hue._tcp")))))

(deftest instances-compare-names-case-insensitively
  (let [records [{:name "_HOME-assistant._tcp.local" :type :ptr :data "Kitchen._home-assistant._tcp.local"}
                 {:name "_home-assistant._tcp.local" :type :ptr :data "KITCHEN._home-assistant._TCP.local"}
                 {:name "_home-assistant._tcp.local" :type :ptr :data "Attic._home-assistant._tcp.local"}
                 {:name "_home-assistant._tcp.local" :type :ptr :data "_home-assistant._tcp.local"}
                 {:name "_home-assistant._tcp.local" :type :ptr :data "Other._hue._tcp.local"}
                 {:name "kitchen._home-assistant._tcp.local" :type :srv :data {:port 1 :target "K.local"}}
                 {:name "k.LOCAL" :type :a :data "10.0.0.2"}
                 {:name "k.local" :type :a :data "10.0.0.1"}]]
    (is (= [["Attic" nil []] ["Kitchen" "K.local" ["10.0.0.1" "10.0.0.2"]]]
           (map (juxt :name :host :addresses) (mdns/instances (parsed records) service))))))

(deftest instances-decode-escapes-and-pick-the-best-srv
  (let [instance "Living\\032Room\\.1._home-assistant._tcp.local"
        records [{:name "_home-assistant._tcp.local" :type :ptr :data instance}
                 {:name instance :type :srv :ttl 1 :data {:priority 1 :weight 0 :port 1 :target "b.local"}}
                 {:name instance :type :srv :ttl 1 :data {:priority 0 :weight 5 :port 2 :target "a.local"}}
                 {:name instance :type :srv :ttl 1 :data {:priority 0 :weight 1 :port 3 :target "c.local"}}]]
    (is (= [["Living Room.1" 2 "a.local"]]
           (map (juxt :name :port :host) (mdns/instances records service))))))

(deftest instances-are-at-most-50
  (let [records (for [i (range 60)]
                  {:name "_home-assistant._tcp.local" :type :ptr :data (format "H%02d._home-assistant._tcp.local" i)})]
    (is (= 50 (count (mdns/instances records service))))
    (is (= "H00" (:name (first (mdns/instances records service)))))))

(defn ^:private start-responder!
  "A fake mDNS responder on loopback: `answer` takes each query, parsed,
  and returns the packets to send back to whoever asked."
  [answer]
  (let [socket (DatagramSocket. 0 (InetAddress/getByName "127.0.0.1"))
        queries (atom [])]
    (future
      (try
        (loop []
          (let [buffer (byte-array 65536)
                packet (DatagramPacket. buffer (alength buffer))]
            (.receive socket packet)
            (let [query (mdns/parse-packet (java.util.Arrays/copyOf buffer (.getLength packet)))]
              (swap! queries conj query)
              (doseq [^bytes data (answer query)]
                (.send socket (DatagramPacket. data (alength data) (.getSocketAddress packet)))))
            (recur)))
        (catch Exception _ nil)))
    {:port (.getLocalPort socket) :queries queries :stop! #(.close socket)}))

(def ^:private no-avahi "grog-test-no-such-avahi-browse")

(defn ^:private browse
  ([responder timeout-ms] (browse responder timeout-ms no-avahi))
  ([responder timeout-ms avahi]
   (mdns/browse! service {:timeout-ms timeout-ms :target "127.0.0.1" :port (:port responder) :avahi avahi})))

(def ^:private home-instance
  (first (mdns/instances (parsed home-records) service)))

(deftest browse-finds-what-answers-in-one-packet
  (let [responder (start-responder! (fn [{:keys [id questions]}]
                                      [(response {:id id :questions questions
                                                  :answers (take 1 home-records)
                                                  :additional (drop 1 home-records)})]))]
    (try
      (is (= [home-instance] (browse responder 300)))
      (is (= [[["_home-assistant._tcp.local" :ptr]]] (map :questions @(:queries responder))))
      (finally ((:stop! responder))))))

(deftest browse-asks-for-what-is-missing
  (let [answers {:ptr (take 1 home-records)
                 :srv [(nth home-records 1)]
                 :txt [(nth home-records 2)]
                 :a [(nth home-records 3)]
                 :aaaa [(nth home-records 4)]}
        responder (start-responder! (fn [{:keys [id questions]}]
                                      [(response {:id id :questions questions
                                                  :answers (mapcat (comp answers second) questions)})]))]
    (try
      (is (= [home-instance] (browse responder 500)))
      (is (= [[["_home-assistant._tcp.local" :ptr]]
              [[home :srv] [home :txt]]
              [["abc.local" :a] ["abc.local" :aaaa]]]
             (map :questions @(:queries responder))))
      (finally ((:stop! responder))))))

(deftest browse-returns-nothing-when-nothing-answers
  (let [responder (start-responder! (constantly []))
        start (System/currentTimeMillis)]
    (try
      (is (= [] (browse responder 200)))
      (is (<= 200 (- (System/currentTimeMillis) start) 1500))
      (is (= 1 (count @(:queries responder))))
      (finally ((:stop! responder))))))

(deftest browse-ignores-garbage-and-other-answers
  (let [responder (start-responder! (fn [{:keys [id questions]}]
                                      [(bytes-of [1 2 3])
                                       (bytes-of (u16b id) (u16b 0x8400) (u16b 0) (u16b 1) (u16b 0) (u16b 0) 0xc0 12)
                                       (response {:id (bit-xor id 1) :answers [(assoc (first home-records) :data "Other._home-assistant._tcp.local")]})
                                       (mdns/query-packet id [["_home-assistant._tcp.local" :ptr]])
                                       (response {:id id :questions questions :answers home-records})]))]
    (try
      (is (= [home-instance] (browse responder 300)))
      (finally ((:stop! responder))))))

(deftest browse-never-throws-for-network-trouble
  (is (= [] (mdns/browse! service {:timeout-ms 100 :target "127.0.0.1" :port 0 :avahi no-avahi}))))

(def ^:private avahi-output
  (str/join "\n" ["+;wlo1;IPv4;Living\\032Room;_home-assistant._tcp;local"
                  "=;wlo1;IPv6;Living\\032Room;_home-assistant._tcp;local;abc.local;fe80::1;8123;\"location_name=Living Room\" \"base_url=http://192.168.15.50:8123\" \"Version=2026.10.1\" \"internal_url=http://a/?x=1&y=2\" \"q=say \\\"hi\\\"\" \"requires_api_password\""
                  "=;wlo1;IPv4;Living\\032Room;_home-assistant._tcp;local;abc.local;192.168.15.50;8123;\"location_name=Living Room\" \"base_url=http://192.168.15.50:8123\" \"Version=2026.10.1\" \"internal_url=http://a/?x=1&y=2\" \"q=say \\\"hi\\\"\" \"requires_api_password\""
                  "=;lo;IPv4;living\\032room;_home-assistant._tcp;local;abc.local;192.168.15.50;8123;"
                  "=;docker0;IPv4;Living\\032Room;_home-assistant._tcp;local;abc.local;172.17.0.1;8123;"
                  "=;lo;IPv4;Living\\032Room;_home-assistant._tcp;local;abc.local;127.0.0.1;8123;"
                  "=;wlo1;IPv6;Living\\032Room;_home-assistant._tcp;local;abc.local;2804:1b3::5;8123;"
                  "=;wlo1;IPv4;Semi\\;colon\\\\x\\.y;_home-assistant._tcp;local;b.local;10.0.0.2;8123;"
                  "=;wlo1;IPv4;Ch\\195\\162teau;_home-assistant._tcp;local;c.local;10.0.0.4;80;\"a=1\""
                  "=;wlo1;IPv4;Bad\\032port;_home-assistant._tcp;local;d.local;10.0.0.3;99999;"
                  "=;wlo1;IPv5;Bad\\032protocol;_home-assistant._tcp;local;d.local;10.0.0.3;1;"
                  "=;wlo1;IPv4;Short;_home-assistant._tcp;local"
                  "=;wlo1;IPv4;Bad\\032txt;_home-assistant._tcp;local;d.local;10.0.0.3;1;\"unterminated"
                  "=;wlo1;IPv4;No\\032host;_home-assistant._tcp;local;;10.0.0.3;1;"
                  "garbage"
                  ""]))

(def ^:private avahi-living-room
  {:instance "Living Room._home-assistant._tcp.local"
   :name "Living Room"
   :host "abc.local"
   :port 8123
   :addresses ["172.17.0.1" "192.168.15.50" "127.0.0.1" "2804:1b3::5" "fe80::1"]
   :txt {"location_name" "Living Room"
         "base_url" "http://192.168.15.50:8123"
         "version" "2026.10.1"
         "internal_url" "http://a/?x=1&y=2"
         "q" "say \"hi\""
         "requires_api_password" ""}})

(deftest avahi-instances-parses-resolved-lines
  (is (= [{:instance "Château._home-assistant._tcp.local" :name "Château" :host "c.local" :port 80
           :addresses ["10.0.0.4"] :txt {"a" "1"}}
          avahi-living-room
          {:instance "Semi;colon\\\\x\\.y._home-assistant._tcp.local" :name "Semi;colon\\x.y" :host "b.local"
           :port 8123 :addresses ["10.0.0.2"] :txt {}}]
         (mdns/avahi-instances avahi-output service))))

(deftest avahi-instances-are-bounded
  (let [line (fn [i] (format "=;wlo1;IPv4;H%03d;_home-assistant._tcp;local;h.local;10.0.0.1;1;" i))]
    (is (= 50 (count (mdns/avahi-instances (str/join "\n" (map line (range 80))) service))))
    (is (= [] (mdns/avahi-instances (str (str/join "\n" (repeat 1000 "garbage")) "\n" (line 1)) service)))
    (is (= [] (mdns/avahi-instances (str (line 1) (apply str (repeat 5000 " "))) service)))
    (is (= [] (mdns/avahi-instances "" service)))))

(defn ^:private fake-avahi!
  "A fake avahi-browse running `body`, which logs its arguments to `log`."
  [body]
  (let [dir (h/temp-dir)
        log (str (fs/path dir "args"))]
    {:program (h/script! dir "avahi-browse" (str "printf '%s\\n' \"$@\" > " log "\n" body))
     :log log}))

(defn ^:private printing [output]
  (str "cat <<'GROG_EOF'\n" output "\nGROG_EOF"))

(deftest browse-prefers-avahi
  (let [avahi (fake-avahi! (printing avahi-output))
        responder (start-responder! (fn [{:keys [id questions]}]
                                      [(response {:id id :questions questions :answers home-records})]))]
    (try
      (is (= avahi-living-room (second (browse responder 300 (:program avahi)))))
      (is (= ["--resolve" "--parsable" "--terminate" service] (str/split-lines (slurp (:log avahi)))))
      (is (= [] @(:queries responder)) "no query of its own")
      (finally ((:stop! responder))))))

(deftest browse-falls-back-on-its-own-query
  (let [responder (start-responder! (fn [{:keys [id questions]}]
                                      [(response {:id id :questions questions :answers home-records})]))]
    (try
      (testing "avahi fails"
        (is (= [home-instance]
               (browse responder 300 (:program (fake-avahi! "echo 'Failed to create client object: Daemon not running' >&2; exit 1"))))))
      (testing "avahi finds nothing"
        (is (= [home-instance] (browse responder 300 (:program (fake-avahi! (printing "+;wlo1;IPv4;Home;_home-assistant._tcp;local")))))))
      (testing "avahi isn't there"
        (is (= [home-instance] (browse responder 300 no-avahi))))
      (testing "avahi takes too long"
        (let [start (System/currentTimeMillis)]
          (is (= [home-instance] (browse responder 100 (:program (fake-avahi! "sleep 30")))))
          (is (< (- (System/currentTimeMillis) start) 5000))))
      (is (= 4 (count @(:queries responder))))
      (finally ((:stop! responder))))))
