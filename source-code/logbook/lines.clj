(ns logbook.lines
  "One call made into one line: its parts turned into JSON-ready data
   (text keys, text for what JSON has no type for), then written as JSON or
   readable. Nothing here throws on a value: what can't be written as it is
   is written as its printed form."
  (:require
   [clojure.string :as str]
   [jsonista.core :as json])
  (:import
   (java.io PrintWriter StringWriter)
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)
   (java.time.temporal TemporalAccessor TemporalAmount)
   (java.util IdentityHashMap)))

;; -----------------------------------------------------------------------------
;; Values

(defn print-safely
  "A value's printed form; a value whose printing fails is named by its class."
  [value]
  (try (pr-str value)
       (catch Throwable _
         (str "#<unprintable " (.getName (class value)) ">"))))

(defn turn-key-into-text
  "A key as JSON text: :background-job/id -> \"background-job/id\",
   :status -> \"status\", text as it is, anything else printed."
  [k]
  (cond (keyword? k) (if-let [k-ns (namespace k)] (str k-ns "/" (name k)) (name k))
        (string? k)  k
        :else        (print-safely k)))

(declare turn-value-into-json-data)

(defn- list-causes
  "The causes under an exception, each once (a cause can point back)."
  [^Throwable exception]
  (let [seen (IdentityHashMap.)]
    (.put seen exception true)
    (loop [cause (.getCause exception) causes []]
      (if (or (nil? cause) (.containsKey seen cause))
        causes
        (do (.put seen cause true)
            (recur (.getCause cause) (conj causes cause)))))))

(defn- turn-stack-into-text
  "The JVM's own stack trace: the exception, its frames, and every cause's
   (\"Caused by: …\") and suppressed one's."
  [^Throwable exception]
  (let [writer (StringWriter.)]
    (.printStackTrace exception (PrintWriter. writer))
    (str/trimr (str writer))))

(defn- describe-exception
  "class, message and the ex-data, when there is some."
  [^Throwable exception]
  (cond-> (array-map "class" (.getName (class exception)) "message" (ex-message exception))
    (some? (ex-data exception)) (assoc "data" (turn-value-into-json-data (ex-data exception)))))

(defn turn-exception-into-error
  "An exception as the line's error: {class message data? stack cause?},
   the cause chain nested under cause ({class message data? cause?}); the
   stack is the JVM's whole trace, the causes' frames included."
  [^Throwable exception]
  (let [causes (list-causes exception)
        nested (reduce (fn [inner cause] (cond-> (describe-exception cause) inner (assoc "cause" inner)))
                       nil
                       (reverse causes))]
    (cond-> (assoc (describe-exception exception) "stack" (turn-stack-into-text exception))
      nested (assoc "cause" nested))))

(defn- turn-map-into-json-data [m]
  (persistent! (reduce-kv (fn [result k v] (assoc! result (turn-key-into-text k) (turn-value-into-json-data v)))
                          (transient {})
                          m)))

(defn turn-value-into-json-data
  "A value as JSON data: text, numbers, booleans, nil, maps with text keys
   and vectors. UUIDs, moments, days, decimals and keywords become their
   text (:order/id -> \"order/id\"); an exception, an error map; anything
   JSON has no place for, its printed form."
  [value]
  (cond
    (or (nil? value) (string? value) (boolean? value)) value
    (keyword? value)                                  (turn-key-into-text value)
    (symbol? value)                                   (str value)
    (or (instance? Long value) (instance? Integer value) (instance? Short value) (instance? Byte value)
        (instance? java.math.BigInteger value))       value
    (instance? clojure.lang.BigInt value)             (.toBigInteger ^clojure.lang.BigInt value)
    (or (instance? Double value) (instance? Float value))
    (let [d (double value)] (if (or (Double/isNaN d) (Double/isInfinite d)) (str value) value))
    (decimal? value)                                  (.toPlainString ^BigDecimal value)
    (uuid? value)                                     (str value)
    (or (instance? TemporalAccessor value) (instance? TemporalAmount value)) (str value)
    (instance? Throwable value)                       (turn-exception-into-error value)
    (map? value)                                      (turn-map-into-json-data value)
    (or (sequential? value) (set? value))             (mapv turn-value-into-json-data value)
    :else                                             (print-safely value)))

;; -----------------------------------------------------------------------------
;; A call's parts

(defn- find-exception-key
  "The key of the first exception in the data, by the keys' text, or nil."
  [data]
  (first (sort-by turn-key-into-text (filter #(instance? Throwable (get data %)) (keys data)))))

(def ^:private time-format
  (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") ZoneOffset/UTC))

(defn- turn-data-into-json-data-or-say-why
  "The data as JSON data; data that can't be walked (nested too deep for
   the stack) is said, not thrown."
  [data]
  (try (turn-map-into-json-data data)
       (catch Throwable e
         {"logbook/unwritable-data" (str (.getName (class e)) ": " (ex-message e))})))

(defn make-line
  "A call as the line's data, in its order: time, level, event, context,
   data, and error when the data held an exception (the first, by its
   key's text; any other stays in data, as an error map too)."
  [^Instant now level event context data]
  (let [exception-key (find-exception-key data)
        line          (array-map "time"    (.format ^DateTimeFormatter time-format now)
                                 "level"   (name level)
                                 "event"   (turn-key-into-text event)
                                 "context" (turn-data-into-json-data-or-say-why context)
                                 "data"    (turn-data-into-json-data-or-say-why (dissoc data exception-key)))]
    (cond-> line
      exception-key (assoc "error" (turn-exception-into-error (get data exception-key))))))

;; -----------------------------------------------------------------------------
;; The two formats

(defn write-json-text
  "The line as one JSON object and a newline."
  [line]
  (str (json/write-value-as-string line) "\n"))

(defn- write-section
  "One part of the line, indented, each key on its own line; nothing for an
   empty part."
  [title entries]
  (when (seq entries)
    (let [width (apply max (map (comp count key) entries))]
      (str/join (map-indexed (fn [i [k v]]
                               (str (if (zero? i) (format "    %-8s " title) (apply str (repeat 13 \space)))
                                    k (apply str (repeat (- (inc width) (count k)) \space))
                                    (json/write-value-as-string v) "\n"))
                             entries)))))

(defn- write-error-section
  "The class and message, the ex-data, then the stack's frames and causes
   (the stack's own first lines say the class and message again, with the
   ex-data printed, and are left out)."
  [{:strs [class message stack data]}]
  (let [indent (apply str (repeat 13 \space))
        frames (drop-while #(not (re-find #"^\s+at " %)) (str/split-lines stack))]
    (str "    error    " class (when message (str ": " (str/replace message "\n" (str "\n" indent)))) "\n"
         (when (some? data) (str indent "data " (json/write-value-as-string data) "\n"))
         (str/join (map #(str indent (str/trim %) "\n") frames)))))

(defn write-readable-text
  "The line for a person: the time, level and event on one line; the
   context, the data and the error below it, indented, each value as its
   JSON text."
  [{:strs [time level event context data error]}]
  (str time "  " (format "%-5s" (str/upper-case level)) "  " event "\n"
       (write-section "context" context)
       (write-section "data" data)
       (when error (write-error-section error))))
