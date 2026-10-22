(ns grog.elicit
  "What an MCP server asks the user in the middle of a call (MCP
  elicitation, plans/integrations.md): a form to fill in, or a link to open,
  on grog's card and its page on the network, through a session of its own
  (grog.connect), unless another is open. The call waits for the answer, for
  as long as the session's window."
  (:require [clojure.string :as str]
            [grog.connect :as connect]
            [grog.log :as log]))

(def window-ms
  "How long whoever is at grog has to answer. Tests redefine it."
  (* 3 60 1000))

;; How long the message gets on the card.
(def ^:private message-max-chars 300)

(defn ^:private short-text [s]
  (let [s (str/trim (str/replace (str s) #"\s+" " "))]
    (if (> (count s) message-max-chars) (str (subs s 0 (dec message-max-chars)) "…") s)))

(defn ^:private choices-of
  "The choices an enum (with its enumNames) or a oneOf/anyOf of consts
  offers, or nil."
  [{:keys [enum enumNames oneOf anyOf]}]
  (cond
    (seq enum) (mapv (fn [v label] {:value v :label (str label)}) enum (concat enumNames (drop (count enumNames) enum)))
    (seq (or oneOf anyOf)) (vec (for [{:keys [const title]} (or oneOf anyOf)
                                      :when (some? const)]
                                  {:value const :label (str (or title const))}))))

(defn ^:private field
  "The form field asking for property `prop`, as its schema says, with id
  `id`."
  [id prop {:keys [type title description default format minLength maxLength minimum maximum
                   items minItems maxItems] :as schema} required?]
  (let [choices (choices-of schema)
        item-choices (when (= "array" type) (choices-of items))
        base (cond-> {:id id :label (or (not-empty title) (name prop))}
               (not (str/blank? description)) (assoc :description description)
               (not required?) (assoc :optional true)
               (some? default) (assoc :default default))]
    (cond
      item-choices (cond-> (assoc base :type "choices" :choices item-choices)
                     minItems (assoc :min minItems)
                     maxItems (assoc :max maxItems))
      choices (assoc base :type "choice" :choices choices)
      (#{"number" "integer"} type) (cond-> (assoc base :type type)
                                     (number? minimum) (assoc :min minimum)
                                     (number? maximum) (assoc :max maximum))
      (= "boolean" type) (assoc base :type "boolean")
      :else (cond-> base
              (#{"email" "uri" "date" "date-time"} format) (assoc :format format)
              (number? minLength) (assoc :min minLength)
              (number? maxLength) (assoc :max maxLength)))))

(defn form-fields
  "The fields of a form asking what `schema` (an elicitation's
  requestedSchema) asks for: [[property field]...], their ids f1, f2..."
  [schema]
  (let [required (set (map keyword (:required schema)))]
    (vec (map-indexed (fn [i [prop s]] [prop (field (str "f" (inc i)) prop s (contains? required (keyword prop)))])
                      (:properties schema)))))

(defn ^:private answer!
  "Delivers `answer` to whoever waits, once."
  [answered answer]
  (deliver answered answer))

(defn ^:private ended
  "What a session ending `how` answers the server."
  [how]
  (if (= "denied" how) {:action "decline"} {:action "cancel"}))

(defn ^:private form-start [answered message schema]
  (fn [ctx {:keys [generation]} _]
    (let [props (form-fields schema)
          fields (map-indexed (fn [i [_ f]]
                                (let [d (str/join " " (remove str/blank? [message (:description f)]))]
                                  (cond-> f (and (zero? i) (not (str/blank? d))) (assoc :description d))))
                              props)]
      (connect/on-end! ctx generation #(answer! answered (ended %)))
      (connect/form! ctx generation
                     {:fields fields
                      :submit! (fn [entered]
                                 (answer! answered {:action "accept"
                                                    :content (into {} (for [[prop f] props
                                                                            :let [v (get entered (:id f) ::none)]
                                                                            :when (not= ::none v)]
                                                                        [prop v]))})
                                 (connect/done! ctx generation nil))}))))

(defn ^:private link-start [answered message url]
  (fn [ctx {:keys [generation]} _]
    (connect/on-end! ctx generation #(answer! answered (ended %)))
    (connect/link! ctx generation {:url url
                                   :message message
                                   :done! #(do (answer! answered {:action "accept"})
                                               (connect/done! ctx generation nil))})))

(defn ask!
  "Asks whoever is at grog what the server of integration `title` asks for
  (`params`, an elicitation's: {:mode \"form\" :message :requestedSchema}
  or {:mode \"url\" :message :url}), and waits for the answer, as MCP takes
  it: {:action \"accept\" :content {...}} once they answer (the content
  only for a form), \"decline\" when they say no, \"cancel\" when nobody
  answers in time, or something else is open on grog's card."
  [ctx title {:keys [mode message requestedSchema url]}]
  (let [answered (promise)
        message (short-text message)
        url? (= "url" mode)
        fields? (seq (:properties requestedSchema))
        integration {:id "elicit"
                     :title title
                     :heading (if (or url? fields?) (str title " asks") (str title " asks: " message))
                     :ways [{:id "ask"}]
                     :network? (not url?)
                     :start! (if url?
                               (link-start answered message url)
                               (form-start answered message requestedSchema))}]
    (if (and url? (not (re-matches #"(?i)https?://\S+" (str url))))
      {:action "cancel"}
      (try
        (connect/open! ctx integration nil {:unless-busy? true :window-ms window-ms})
        (deref answered (+ window-ms 10000) {:action "cancel"})
        (catch Exception e
          (log/info "elicit: couldn't ask," (name (or (:type (ex-data e)) (.getSimpleName (class e)))))
          (answer! answered {:action "cancel"})
          @answered)))))
