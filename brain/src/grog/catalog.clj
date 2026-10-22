(ns grog.catalog
  "The MCP servers grog knows (plans/integrations.md, the catalog): each an
  entry in the official MCP Registry's server.json format, parsed with
  keyword keys and no case conversion, grog's own fields under _meta's
  \"io.github.ericdallo.grog\":

    id       its integration's id (else the registry's short id of its name)
    prefix   what its tools' names start with, for the model (else its id)
    icon     its icon on grog's skin
    about    what it's for, in a few words
    show     which of its tools the model sees at first
    policy   {tool \"allow\"|\"ask\"|\"deny\"}, over the defaults
    cloud    whether what grog sends it leaves home
    added    the user added it, at addedAt (epoch ms)

  Those shipped with grog are in resources/grog/integrations/*.json, their
  versions pinned, and trusted: their annotations and _meta count. Those the
  user adds (`add!`, from the registry, a package or an address: see
  grog.registry) are in <state-dir>/catalog/<id>.json, and aren't: grog's
  _meta of theirs is dropped.

  An entry runs as a package (npm or PyPI, through grog's runner,
  `integrations.runner`) or a remote (streamable HTTP). What it needs from
  the user (`inputs`): its package's environment variables, its remote's
  headers, and the {variables} in its remote's address or in their values,
  asked in a form (`fields`), kept as the connection's secrets (`values`)
  and turned into what starts it (`launch`)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [grog.config :as config]
            [grog.connections :as connections]
            [grog.log :as log]
            [grog.provider :as provider]
            [grog.registry :as registry]
            [grog.state :as state])
  (:import (java.net URI)))

(def meta-key
  "Where grog's fields are in an entry's _meta."
  :io.github.ericdallo.grog)

;; Additions and removals, one at a time.
(def ^:private lock (Object.))

;; The integrations' ids that aren't servers.
(def ^:private reserved #{"computer" "ask" "elicit"})

(defn grog-meta
  "grog's fields of `entry`, or {}."
  [entry]
  (or (get-in entry [:_meta meta-key]) {}))

(defn id-of
  "The integration id of `entry`."
  [entry]
  (or (:id (grog-meta entry)) (registry/id-of entry)))

(defn added?
  "Whether the user added `entry` (it isn't shipped with grog)."
  [entry]
  (true? (:added (grog-meta entry))))

(defn trusted?
  "Whether `entry` is shipped with grog, so what it says of its tools
  counts."
  [entry]
  (not (added? entry)))

(defn title
  "What `entry` is called on the screen."
  [entry]
  (or (not-empty (:title entry)) (registry/id-of entry)))

(defn ^:private read-entry
  "The entry in file `f`, or nil when it can't be read (logged, without
  what's in it)."
  [f]
  (try
    (let [entry (json/parse-string (slurp (str f)) true)]
      (when (map? entry) entry))
    (catch Exception e
      (log/info (format "catalog: ignoring %s: %s" (fs/file-name f) (.getSimpleName (class e))))
      nil)))

(defn shipped
  "The entries shipped with grog, from resources/grog/integrations/ when
  it's there. Tests redefine it."
  []
  (let [url (io/resource "grog/integrations")]
    (if (and url (= "file" (.getProtocol url)))
      (->> (fs/glob (io/file url) "*.json")
           (sort-by str)
           (keep read-entry)
           (mapv #(cond-> % (added? %) (update-in [:_meta meta-key] dissoc :added))))
      [])))

(defn ^:private dir [config]
  (str (fs/path (config/state-dir config) "catalog")))

(defn ^:private added
  "The entries the user added."
  [config]
  (let [d (dir config)]
    (if (fs/directory? d)
      (->> (fs/glob d "*.json")
           (keep read-entry)
           (filter added?)
           (sort-by id-of)
           vec)
      [])))

(defn entries
  "The entries grog knows with `config`: those shipped, then those the user
  added, one per id."
  [config]
  (let [shipped (shipped)
        ids (set (map id-of shipped))]
    (into shipped (remove #(ids (id-of %)) (added config)))))

(defn entry
  "Entry `id`, or nil."
  [config id]
  (some #(when (= id (id-of %)) %) (entries config)))

(defn ^:private free-id
  "`base`, or base-2, base-3... the first of them nobody has."
  [base taken]
  (first (remove taken (cons base (map #(str base "-" %) (iterate inc 2))))))

(defn add!
  "Keeps `entry` (a server.json, see grog.registry/runnable) as one the
  user added, dropping what it says of grog's fields: its id, the registry's
  short id of its name, or with -2, -3... when that's taken. Returns the id.
  Throws ex-info when grog can't run it."
  [config entry]
  (let [entry (registry/runnable entry)]
    (locking lock
      (let [taken (into reserved (concat (keys provider/providers)
                                         (map id-of (entries config))
                                         (map :id (connections/all (config/state-dir config)))))
            id (free-id (registry/id-of entry) taken)
            kept (-> entry
                     (update :_meta #(dissoc % meta-key))
                     (assoc-in [:_meta meta-key] {:id id :added true :addedAt (System/currentTimeMillis)}))]
        (state/write! (str (fs/path (dir config) (str id ".json"))) (json/generate-string kept {:pretty true}))
        (log/info "catalog: added" id)
        id))))

(defn remove!
  "Forgets entry `id`, which the user added (disconnect it first). Throws
  ex-info when there's no such entry, or it's shipped with grog."
  [config id]
  (locking lock
    (let [found (entry config id)]
      (cond
        (nil? found) (throw (ex-info (str "There's no integration called " (pr-str id) ".") {:type ::unknown}))
        (not (added? found)) (throw (ex-info (str (title found) " comes with grog: it can't be removed.")
                                             {:type ::shipped})))
      (fs/delete-if-exists (fs/path (dir config) (str id ".json")))
      (log/info "catalog: removed" id)
      {:id id :removed true})))

(defn how
  "How grog runs `entry` with `config`: :package (through the runner) when
  it has one and the runner is set, or it has no remote; else :remote."
  [config entry]
  (let [package? (seq (:packages entry))
        remote? (seq (:remotes entry))]
    (cond
      (and package? (or (get-in config [:integrations :runner]) (not remote?))) :package
      remote? :remote
      :else (throw (ex-info (str (title entry) " can't be run here.") {:type ::unrunnable})))))

(defn private-host?
  "Whether `host` is on the local network (a private, loopback or
  link-local address, or a .local name)."
  [host]
  (#'registry/private-host? host))

(defn ^:private host-of [url]
  (try (.getHost (URI. (str url))) (catch Exception _ nil)))

(defn cloud?
  "Whether what grog sends `entry` leaves home: as it says, when it's
  shipped; else whether it's a remote whose host isn't on the local
  network."
  [config entry]
  (let [said (:cloud (grog-meta entry))]
    (if (boolean? said)
      said
      (boolean (and (= :remote (how config entry))
                    (when-let [host (host-of (get-in entry [:remotes 0 :url]))]
                      (not (private-host? host))))))))

(defn ^:private placeholders
  "The {variables} in `s`."
  [s]
  (distinct (map second (re-seq #"\{([A-Za-z0-9_.-]+)\}" (str s)))))

(defn ^:private value-inputs
  "What a key-value input `kv` (an environment variable, a header) needs:
  itself when it has no value, or the variables in its value."
  [kind kv]
  (let [value (:value kv)]
    (cond
      (nil? value) [{:kind kind :name (:name kv) :input kv}]
      (seq (placeholders value)) (for [v (placeholders value)]
                                   {:kind "var" :name v :input (assoc (get-in kv [:variables (keyword v)] {}) :name v)})
      :else [])))

(defn inputs
  "What `entry` needs from the user to run with `config`: [{:kind \"env\"
  | \"header\" | \"var\" :name :input}], :input being its server.json
  description, once each."
  [config entry]
  (let [found (if (= :package (how config entry))
                (let [pkg (first (:packages entry))]
                  (concat (mapcat #(value-inputs "env" %) (:environmentVariables pkg))
                          (for [arg (:packageArguments pkg)
                                v (placeholders (:value arg))]
                            {:kind "var" :name v :input (assoc (get-in arg [:variables (keyword v)] {}) :name v)})))
                (let [remote (first (:remotes entry))]
                  (concat (for [v (placeholders (:url remote))]
                            {:kind "var" :name v :input (assoc (get-in remote [:variables (keyword v)] {}) :name v)})
                          (mapcat #(value-inputs "header" %) (:headers remote)))))]
    (vec (vals (reduce (fn [m {:keys [kind name] :as i}] (cond-> m (not (contains? m [kind name])) (assoc [kind name] i)))
                       (array-map)
                       found)))))

(defn ^:private field [id {:keys [name description isSecret isRequired default choices format]}]
  (cond-> {:id id :label name}
    (not (str/blank? description)) (assoc :description description)
    isSecret (assoc :secret true)
    (false? isRequired) (assoc :optional true)
    (some? default) (assoc :default default)
    (seq choices) (assoc :type "choice" :choices (mapv (fn [c] {:value (str c) :label (str c)}) choices))
    (and (empty? choices) (= "number" format)) (assoc :type "number")
    (and (empty? choices) (= "boolean" format)) (assoc :type "boolean")))

(defn fields
  "The form fields asking for `inputs` (grog.connect/form!), their ids f1,
  f2..."
  [inputs]
  (vec (map-indexed (fn [i {:keys [input]}] (field (str "f" (inc i)) input)) inputs)))

(defn values
  "What was entered in the `fields` of `inputs` (grog.connect/form-values),
  as kept with the connection's secrets: [{:kind :name :value}], values as
  text."
  [inputs entered]
  (vec (keep-indexed (fn [i {:keys [kind name]}]
                       (let [v (get entered (str "f" (inc i)))]
                         (when (some? v)
                           {:kind kind :name name :value (if (and (number? v) (== v (Math/floor v)))
                                                           (str (long v))
                                                           (str v))})))
                     inputs)))

(defn ^:private value-of [values kind name]
  (some #(when (and (= kind (:kind %)) (= name (:name %))) (:value %)) values))

(defn ^:private fill
  "`s` with its {variables} as `values` say, else their defaults."
  [s values variables]
  (str/replace (str s) #"\{([A-Za-z0-9_.-]+)\}"
               (fn [[_ v]]
                 (str/re-quote-replacement
                  (str (or (value-of values "var" v) (get-in variables [(keyword v) :default]) ""))))))

(defn ^:private resolved
  "Key-value inputs `kvs` as `values` fill them: {name value}, without
  those left out."
  [kind kvs values]
  (into {}
        (keep (fn [{:keys [name value variables] :as kv}]
                (let [v (if (some? value)
                          (fill value values variables)
                          (or (value-of values kind name) (some-> (:default kv) str)))]
                  (when (some? v) [name v]))))
        kvs))

(defn ^:private arguments
  "A package's arguments as the runner takes them, strings."
  [args values]
  (vec (mapcat (fn [{:keys [type name value default variables]}]
                 (let [v (some-> (or value default) (fill values variables))]
                   (case type
                     "named" (cond-> [name] (some? v) (conj v))
                     (when (some? v) [v]))))
               args)))

(defn launch
  "What starts `entry` with `config`, filled in with `values` (see
  `values`): {:kind :package :request} for the runner (grog.runner/start!),
  or {:kind :remote :url :headers}. Throws ex-info when a remote's address
  isn't one grog goes to."
  [config entry values]
  (if (= :package (how config entry))
    (let [{:keys [registryType identifier version packageArguments environmentVariables]} (first (:packages entry))]
      {:kind :package
       :request {:registryType registryType
                 :identifier identifier
                 :version version
                 :packageArguments (arguments packageArguments values)
                 :env (resolved "env" environmentVariables values)}})
    (let [{:keys [url variables headers]} (first (:remotes entry))
          url (fill url values variables)]
      (registry/url-entry url)
      {:kind :remote
       :url url
       :headers (resolved "header" headers values)})))
