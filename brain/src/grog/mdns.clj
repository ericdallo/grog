(ns grog.mdns
  "Finding services on the home network: DNS-SD (RFC 6763) over multicast
  DNS (RFC 6762). `browse!` asks who serves a service
  (`_home-assistant._tcp`): avahi's daemon first (avahi-browse), else
  itself, with one-shot legacy unicast queries (RFC 6762 §6.7): sent from
  an ephemeral port to the mDNS group, so grog needs no port 5353 of its
  own (avahi may hold it), and responders answer to that port. It returns
  the instances found: their name, where they are, their TXT. Names compare case-insensitively; they're kept in presentation form,
  dots and backslashes inside a label escaped."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [grog.log :as log])
  (:import (clojure.lang ExceptionInfo)
           (java.io ByteArrayOutputStream)
           (java.net DatagramPacket DatagramSocket InetAddress SocketTimeoutException)
           (java.security SecureRandom)
           (java.util Arrays)))

(def ^:private type-codes {:a 1 :ptr 12 :txt 16 :aaaa 28 :srv 33})

(def ^:private code-types (into {} (map (fn [[k v]] [v k])) type-codes))

(def ^:private max-packet
  "The largest packet read."
  65536)

(def ^:private max-records
  "The most records kept, from a packet or from a whole browse."
  200)

(def ^:private max-instances 50)

(defn ^:private malformed [why]
  (throw (ex-info (str "malformed DNS packet: " why) {:type ::malformed})))

(defn ^:private unescape
  "The bytes of a label in presentation form: `\\.`, `\\\\` and `\\032`
  decoded."
  ^bytes [^String label]
  (let [out (ByteArrayOutputStream.)
        n (count label)
        write! (fn [^String s] (let [b (.getBytes s "UTF-8")] (.write out b 0 (alength b))))]
    (loop [i 0]
      (if (>= i n)
        (.toByteArray out)
        (let [c (.charAt label i)
              digits (when (and (= \\ c) (<= (+ i 4) n)) (subs label (inc i) (+ i 4)))]
          (cond
            (and digits (re-matches #"[0-9]{3}" digits) (< (parse-long digits) 256))
            (do (.write out (int (parse-long digits))) (recur (+ i 4)))
            (and (= \\ c) (< (inc i) n))
            (let [cp (.codePointAt label (inc i))]
              (write! (String. (Character/toChars cp)))
              (recur (+ i 1 (Character/charCount cp))))
            :else
            (let [cp (.codePointAt label i)]
              (write! (String. (Character/toChars cp)))
              (recur (+ i (Character/charCount cp))))))))))

(defn ^:private split-labels
  "The labels of a name in presentation form, split on unescaped dots."
  [name]
  (re-seq #"(?:\\.|[^.\\])+" name))

(defn ^:private label-text
  "A label's bytes in presentation form."
  [^bytes b]
  (str/replace (String. b "UTF-8") #"[.\\]" #(str "\\" %)))

(defn ^:private display
  "A label as people read it, its escapes decoded."
  [label]
  (String. (unescape label) "UTF-8"))

(defn ^:private write-u16! [^ByteArrayOutputStream out n]
  (.write out (int (bit-and (bit-shift-right n 8) 0xff)))
  (.write out (int (bit-and n 0xff))))

(defn ^:private write-name! [^ByteArrayOutputStream out name]
  (let [labels (map unescape (split-labels name))]
    (when (or (some #(> (alength ^bytes %) 63) labels)
              (> (reduce + 1 (map #(inc (alength ^bytes %)) labels)) 255))
      (throw (ex-info "name too long for DNS" {:name name})))
    (doseq [^bytes label labels]
      (.write out (alength label))
      (.write out label 0 (alength label)))
    (.write out 0)))

(defn query-packet
  "The bytes of a DNS query with `id` asking `questions`, [[name type]],
  type :ptr :srv :txt :a or :aaaa, class IN."
  [id questions]
  (let [out (ByteArrayOutputStream.)]
    (doseq [n [id 0 (count questions) 0 0 0]]
      (write-u16! out n))
    (doseq [[name type] questions]
      (write-name! out name)
      (write-u16! out (or (type-codes type) (throw (ex-info "unknown DNS type" {:type type}))))
      (write-u16! out 1))
    (.toByteArray out)))

(defn ^:private u8 [^bytes data i]
  (when (>= i (alength data)) (malformed "truncated"))
  (bit-and (aget data i) 0xff))

(defn ^:private u16 [data i]
  (bit-or (bit-shift-left (u8 data i) 8) (u8 data (inc i))))

(defn ^:private u32 [data i]
  (bit-or (bit-shift-left (u16 data i) 16) (u16 data (+ i 2))))

(defn ^:private read-name
  "The name at `offset` and the offset after it. A compression pointer must
  point before where the name, or the part it last jumped to, began: names
  never loop."
  [^bytes data offset]
  (loop [i offset floor offset labels [] size 1 after nil]
    (let [len (u8 data i)]
      (cond
        (zero? len)
        [(str/join "." labels) (or after (inc i))]

        (= 0xc0 (bit-and len 0xc0))
        (let [target (bit-and (u16 data i) 0x3fff)]
          (when (>= target floor) (malformed "compression pointer forward or looping"))
          (recur target target labels size (or after (+ i 2))))

        (pos? (bit-and len 0xc0))
        (malformed "unknown label type")

        :else
        (let [size (+ size 1 len)
              end (+ i 1 len)]
          (when (> size 255) (malformed "name too long"))
          (when (> end (alength data)) (malformed "truncated"))
          (recur end floor (conj labels (label-text (Arrays/copyOfRange data (int (inc i)) (int end))))
                 size after))))))

(defn ^:private add-txt
  "`acc` with the TXT string `s`: its key lowercased, the first of a key
  kept, a key without = taken as \"\", strings starting with = ignored."
  [acc s]
  (let [eq (str/index-of s "=")
        k (str/lower-case (if eq (subs s 0 eq) s))]
    (if (or (= "" k) (contains? acc k))
      acc
      (assoc acc k (if eq (subs s (inc eq)) "")))))

(defn ^:private txt
  "A TXT record's strings as a map (`add-txt`)."
  [^bytes data start end]
  (loop [i start acc {}]
    (if (>= i end)
      acc
      (let [len (u8 data i)
            next (+ i 1 len)]
        (when (> next end) (malformed "TXT string past its record"))
        (recur next (add-txt acc (String. data (int (inc i)) (int len) "UTF-8")))))))

(defn ^:private ipv6-text
  "Eight 16-bit groups as RFC 5952 text: lowercase, the longest run of two
  or more zero groups, the first of equal ones, written ::."
  [groups]
  (let [groups (vec groups)
        runs (for [i (range (count groups))
                   :when (and (zero? (groups i)) (or (zero? i) (pos? (groups (dec i)))))]
               [i (count (take-while zero? (subvec groups i)))])
        [start len] (reduce (fn [best run] (if (> (second run) (second best)) run best)) [0 0] runs)
        hex (fn [gs] (str/join ":" (map #(Integer/toHexString %) gs)))]
    (if (< len 2)
      (hex groups)
      (str (hex (subvec groups 0 start)) "::" (hex (subvec groups (+ start len)))))))

(defn ^:private rdata [data kind start end]
  (let [size! (fn [ok?] (when-not ok? (malformed (str "bad " (name kind) " record"))))
        within! (fn [[value after]] (size! (<= after end)) value)]
    (case kind
      :ptr (within! (read-name data start))
      :srv (do (size! (>= (- end start) 7))
               {:priority (u16 data start)
                :weight (u16 data (+ start 2))
                :port (u16 data (+ start 4))
                :target (within! (read-name data (+ start 6)))})
      :txt (txt data start end)
      :a (do (size! (= 4 (- end start)))
             (str/join "." (map #(u8 data %) (range start end))))
      :aaaa (do (size! (= 16 (- end start)))
                (ipv6-text (map #(u16 data %) (range start end 2)))))))

(defn ^:private read-record
  "The record at `offset`, nil when it's of a type or class grog doesn't
  read, and the offset after it."
  [^bytes data offset]
  (let [[name i] (read-name data offset)
        code (u16 data i)
        class (bit-and (u16 data (+ i 2)) 0x7fff)
        ttl (u32 data (+ i 4))
        start (+ i 10)
        end (+ start (u16 data (+ i 8)))
        kind (code-types code)]
    (when (> end (alength data)) (malformed "truncated"))
    [(when (and kind (= 1 class))
       {:name name :type kind :ttl ttl :data (rdata data kind start end)})
     end]))

(defn parse-packet
  "A DNS packet: {:id :response? :questions [[name type]] :answers
  :additional}, records {:name :type :ttl :data} of the types grog reads
  (the rest skipped), at most 200 of them. Throws ex-info of type
  ::malformed when it can't be read."
  [^bytes data]
  (when (> (alength data) max-packet) (malformed "too big"))
  (let [[qd an ns ar] (map #(u16 data %) [4 6 8 10])
        [questions offset] (reduce (fn [[qs i] _]
                                     (let [[name after] (read-name data i)
                                           code (u16 data after)]
                                       (u16 data (+ after 2))
                                       [(conj qs [name (code-types code code)]) (+ after 4)]))
                                   [[] 12] (range qd))
        total (min (+ an ns ar) max-records)]
    (loop [i offset k 0 answers [] additional []]
      (if (= k total)
        {:id (u16 data 0)
         :response? (bit-test (u16 data 2) 15)
         :questions questions
         :answers answers
         :additional additional}
        (let [[record next] (read-record data i)]
          (recur next (inc k)
                 (cond-> answers (and record (< k an)) (conj record))
                 (cond-> additional (and record (>= k (+ an ns))) (conj record))))))))

(defn ^:private lower [s]
  (str/lower-case s))

(defn ^:private local-only?
  "Whether an address only reaches this machine or its link."
  [addr]
  (boolean (re-find #"^(127\.|169\.254\.|::1$|fe[89ab][0-9a-f]:)" (lower addr))))

(defn ^:private addresses
  "Addresses without repeats, IPv4 first, loopback and link-local last of
  each."
  [addrs]
  (vec (sort-by (juxt #(str/includes? % ":") local-only? identity) (distinct addrs))))

(defn ^:private describe [instance suffix by-name]
  (let [srv (->> (by-name [:srv (lower instance)])
                 (map :data)
                 (sort-by (juxt :priority (comp - :weight)))
                 first)
        host (:target srv)]
    {:instance instance
     :name (display (subs instance 0 (- (count instance) (count suffix) 1)))
     :host host
     :port (:port srv)
     :addresses (if host
                  (addresses (map :data (concat (by-name [:a (lower host)])
                                                (by-name [:aaaa (lower host)]))))
                  [])
     :txt (or (:data (first (by-name [:txt (lower instance)]))) {})}))

(defn instances
  "The instances of `service` (\"_home-assistant._tcp\") in `records`,
  following each PTR to its SRV, TXT and the SRV target's addresses:
  [{:instance :name :host :port :addresses :txt}], :host and :port nil
  while its SRV is missing. Sorted by name, without repeats, at most 50."
  [records service]
  (let [suffix (lower (str service ".local"))
        by-name (group-by (juxt :type (comp lower :name)) records)
        names (->> (by-name [:ptr suffix])
                   (map :data)
                   (filter #(and (str/ends-with? (lower %) (str "." suffix))
                                 (> (count %) (inc (count suffix))))))]
    (->> (reduce (fn [seen n] (cond-> seen (not (contains? seen (lower n))) (assoc (lower n) n))) {} names)
         vals
         (map #(describe % suffix by-name))
         (sort-by (juxt (comp lower :name) (comp lower :instance)))
         (take max-instances)
         vec)))

(def ^:private max-lines
  "The most lines of avahi-browse's output read, and the longest one."
  [1000 4096])

(defn ^:private split-fields
  "A line of avahi-browse's parsable output split on unescaped `;`, the
  fields still escaped."
  [line]
  (loop [chars (seq line) field (StringBuilder.) fields []]
    (let [[c d & more] chars]
      (cond
        (nil? c) (conj fields (str field))
        (and (= \\ c) d) (recur more (doto field (.append c) (.append d)) fields)
        (= \; c) (recur (next chars) (StringBuilder.) (conj fields (str field)))
        :else (recur (next chars) (.append field c) fields)))))

(defn ^:private avahi-txt
  "avahi's TXT field, space-separated double-quoted strings with
  backslash escapes, as a map (`add-txt`), or nil when it can't be read."
  [field]
  (let [strings (re-seq #"\"((?:\\.|[^\"\\])*)\"|(\S+)" field)]
    (when-not (some #(nth % 2) strings)
      (reduce (fn [acc [_ s]] (add-txt acc (display s))) {} strings))))

(defn ^:private avahi-row
  "A resolved line of avahi-browse (`=;iface;protocol;name;type;domain;
  hostname;address;port;txt`), or nil when it isn't one or is malformed."
  [service line]
  (let [[mark _ protocol name _ domain host address port & txt] (split-fields line)
        port (some-> port parse-long)
        txt (when txt (avahi-txt (str/join ";" txt)))]
    (when (and (= "=" mark) (#{"IPv4" "IPv6"} protocol) (seq name) (seq domain)
               (seq host) (seq address) port (<= 0 port 65535) txt)
      (let [label (display name)]
        {:instance (str (label-text (.getBytes label "UTF-8")) "." service "." domain)
         :name label
         :host host
         :port port
         :address address
         :txt txt}))))

(defn avahi-instances
  "The instances of `service` in the output of `avahi-browse --resolve
  --parsable --terminate <service>`, as `instances` returns them: the rows
  of an instance (its addresses, its interfaces) merged. Malformed lines
  are ignored."
  [output service]
  (let [[most longest] max-lines]
    (->> (str/split-lines output)
         (take most)
         (filter #(<= (count %) longest))
         (keep #(avahi-row service %))
         (group-by (comp lower :instance))
         vals
         (map (fn [[row :as rows]]
                {:instance (:instance row)
                 :name (:name row)
                 :host (:host row)
                 :port (:port row)
                 :addresses (addresses (map :address rows))
                 :txt (or (first (filter seq (map :txt rows))) {})}))
         (sort-by (juxt (comp lower :name) (comp lower :instance)))
         (take max-instances)
         vec)))

(defn ^:private merge-records
  "`records` with those of `new` not already among them, at most 200."
  [records new]
  (let [key-of (juxt :type (comp lower :name) :data)
        seen (set (map key-of records))]
    (->> new
         (reduce (fn [[acc seen] r]
                   (if (contains? seen (key-of r))
                     [acc seen]
                     [(conj acc r) (conj seen (key-of r))]))
                 [records seen])
         first
         (take max-records)
         vec)))

(defn ^:private follow-ups
  "What to ask about instances found without their SRV, or without the
  addresses of its target."
  [found]
  (vec (distinct (mapcat (fn [{:keys [instance host addresses]}]
                           (cond
                             (nil? host) [[instance :srv] [instance :txt]]
                             (empty? addresses) [[host :a] [host :aaaa]]))
                         found))))

(defn ^:private receive!
  "The next packet's bytes, or nil when none comes before the socket's
  timeout."
  [^DatagramSocket socket ^bytes buffer]
  (let [packet (DatagramPacket. buffer (alength buffer))]
    (try
      (.receive socket packet)
      (Arrays/copyOf buffer (.getLength packet))
      (catch SocketTimeoutException _ nil))))

(defn ^:private answer-records
  "The records of `data` when it answers query `id`, else nil."
  [id data]
  (try
    (let [{:keys [response? answers additional] :as packet} (parse-packet data)]
      (when (and response? (= id (:id packet)))
        (concat answers additional)))
    (catch ExceptionInfo _
      (log/info "mdns: ignoring a malformed answer")
      nil)
    (catch Exception e
      (log/info "mdns: ignoring an answer:" (.getName (class e)))
      nil)))

(defn ^:private avahi-browse!
  "The instances of `service` avahi's daemon finds, through the program
  `avahi`, killed when it takes much over `timeout-ms`; nil when it isn't
  there or fails."
  [service avahi timeout-ms]
  (when-let [program (fs/which avahi)]
    (try
      (let [proc (p/process {:cmd [(str program) "--resolve" "--parsable" "--terminate" service]
                             :in "" :out :string :err :string})
            {:keys [exit out] :as result} (deref proc (+ timeout-ms 1000) nil)]
        (cond
          (nil? result) (do (p/destroy-tree proc)
                            (log/info "mdns: avahi-browse took too long")
                            nil)
          (zero? exit) (avahi-instances out service)
          :else (do (log/info "mdns: avahi-browse failed, exit" exit) nil)))
      (catch Exception e
        (log/info "mdns: avahi-browse failed:" (.getName (class e)))
        nil))))

(defn ^:private multicast-browse!
  "The instances of `service` answering a legacy unicast query sent to
  `target` and `port` within `timeout-ms`, asking again for what answers
  left out."
  [service timeout-ms target port]
  (let [records (volatile! [])
        deadline (+ (System/currentTimeMillis) timeout-ms)
        id (.nextInt (SecureRandom.) 65536)]
    (try
      (with-open [socket (DatagramSocket.)]
        (let [address (InetAddress/getByName target)
              buffer (byte-array max-packet)
              ask! (fn [questions]
                     (let [^bytes p (query-packet id questions)]
                       (.send socket (DatagramPacket. p (alength p) address (int port)))))]
          (ask! [[(str service ".local") :ptr]])
          (loop [asked #{}]
            (let [left (- deadline (System/currentTimeMillis))]
              (when (pos? left)
                (.setSoTimeout socket (int left))
                (when-let [data (receive! socket buffer)]
                  (vswap! records merge-records (answer-records id data)))
                (let [questions (remove asked (follow-ups (instances @records service)))]
                  (when (seq questions) (ask! questions))
                  (recur (into asked questions))))))))
      (catch Exception e
        (log/info "mdns: browsing failed:" (.getName (class e)))))
    (instances @records service)))

(defn browse!
  "The instances of `service` (\"_home-assistant._tcp\") on the network.
  Asks avahi's daemon first, with the program `avahi` (avahi-browse): on
  grognix the firewall only lets in what comes to port 5353, so replies to
  grog's own queries may not reach it. When avahi isn't there, fails or
  finds nothing, asks itself, with a legacy unicast query to `target` (the
  mDNS group) and `port` (5353), asking again for what answers left out.
  Each takes about `timeout-ms` (2000) at most. Never throws for network
  trouble: logs it and returns what it has."
  ([service] (browse! service {}))
  ([service {:keys [timeout-ms target port avahi]
             :or {timeout-ms 2000 target "224.0.0.251" port 5353 avahi "avahi-browse"}}]
   (let [found (avahi-browse! service avahi timeout-ms)]
     (if (seq found)
       found
       (multicast-browse! service timeout-ms target port)))))
