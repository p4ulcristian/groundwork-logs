(ns logbook.api
  "groundwork's logging: one function, (log level event data), which the app
   makes once and hands to every library; a library never decides how a
   line is written. logbook's one public namespace.

     (def logbook (logbook/make-logbook {:lowest-level :info
                                         :format       :json
                                         :errors-kept  1000
                                         :write-to     :stdout}))
     (def log (logbook/log-function logbook))

     (log :warn :backroom/gave-up {:background-job/id id :background-job/attempts 5})

     ;; whoever runs the work hands down a log that knows it
     (def request-log (logbook/with-context log {:request-id \"7f3a…\"}))
     (request-log :error :mailroom/handler-failed {:mailroom/message-name :orders/of-shop :error e})

     ;; the :error lines kept, after the last one a monitor has
     (logbook/list-errors-since logbook 41)

     ;; what it has written, for the app's metrics
     (logbook/read-counts logbook)

   A line is time, level, event, context, data, and error when the data
   held an exception:

     {\"time\":\"2026-10-22T23:10:04.120Z\",\"level\":\"warn\",\"event\":\"backroom/gave-up\",
      \"context\":{\"request-id\":\"7f3a…\"},\"data\":{\"background-job/id\":\"9c1e…\",\"background-job/attempts\":5}}

   A log call never throws: a level, event or data that isn't one is
   written at :error and says so; a value JSON has no place for is written
   as its printed form; a line that can't be written is said once on
   standard error. Each line is written whole, one at a time."
  (:require
   [logbook.lines :as lines]
   [logbook.settings :as settings])
  (:import
   (java.io PrintStream)
   (java.nio.charset StandardCharsets)
   (java.time Instant)
   (java.util.concurrent.atomic AtomicBoolean LongAdder)
   (java.util.concurrent.locks ReentrantLock)))

(def settings-shape
  "make-logbook's settings, every key required:
   {:lowest-level :debug|:info|:warn|:error :format :json|:readable
    :errors-kept n :write-to :stdout-or-(f text)}. See
   logbook.settings/settings-shape for each key."
  settings/settings-shape)

(def ^:private level-ranks
  (zipmap settings/levels (range)))

(defn make-logbook
  "A logbook from its settings (settings-shape), every key required,
   checked first: a missing or unknown one refuses, named, with every other
   problem at once (:logbook/bad-settings). Returns the logbook, for
   log-function and list-errors-since."
  [logbook-settings]
  (let [{:keys [lowest-level] :as checked} (settings/check-settings logbook-settings)]
    {:settings      checked
     :lowest-rank   (level-ranks lowest-level)
     :lock          (ReentrantLock.)
     :errors        (atom {:last-seq 0 :kept clojure.lang.PersistentQueue/EMPTY})
     :failed-before (AtomicBoolean. false)
     ;; for read-counts: lines written whole, by level, and lines that couldn't be
     :lines-written (zipmap settings/levels (repeatedly #(LongAdder.)))
     :write-failed  (LongAdder.)}))

;; -----------------------------------------------------------------------------
;; Writing

(defn- turn-bad-call-into-good-one
  "[level event data] as written: a call whose level isn't one of the four,
   whose event isn't a namespaced keyword or whose data isn't a map is
   written at :error, with what it was given in its data."
  [level event data]
  (let [good-level? (contains? level-ranks level)
        good-event? (qualified-keyword? event)
        good-data?  (map? data)]
    (if (and good-level? good-event? good-data?)
      [level event data]
      [:error
       (if good-event? event :logbook/bad-event)
       (cond-> (if good-data? data {:logbook/given-data data})
         (not good-level?) (assoc :logbook/given-level level)
         (not good-event?) (assoc :logbook/given-event event))])))

(defn- say-failure-once! [{:keys [^AtomicBoolean failed-before]} ^Throwable failure]
  (when (.compareAndSet failed-before false true)
    (try (.println System/err (str "logbook: a line couldn't be written, and later failures won't be said: "
                                   (.getName (class failure)) ": " (ex-message failure)))
         (catch Throwable _ nil))))

(defn- write-text! [{:keys [write-to]} ^String text]
  (if (= :stdout write-to)
    (let [^PrintStream out System/out
          bytes          (.getBytes text StandardCharsets/UTF_8)]
      (.write out bytes 0 (alength bytes))
      (.flush out)
      (when (.checkError out)
        (throw (java.io.IOException. "standard output refused the line"))))
    (write-to text)))

(defn- keep-error!
  "Keeps an :error line with the next sequence number, dropping the oldest
   past :errors-kept."
  [{:keys [errors] {:keys [errors-kept]} :settings} line]
  (swap! errors (fn [{:keys [last-seq kept]}]
                  (let [seq-number (inc last-seq)
                        kept       (conj kept (into (array-map "seq" seq-number) line))]
                    {:last-seq seq-number
                     :kept     (if (> (count kept) errors-kept) (pop kept) kept)}))))

(defn- write-line!
  [{:keys [lowest-rank ^ReentrantLock lock lines-written ^LongAdder write-failed]
    {line-format :format :as logbook-settings} :settings :as logbook}
   context level event data]
  (try
    (let [[level event data] (turn-bad-call-into-good-one level event data)]
      (when (>= (level-ranks level) lowest-rank)
        (let [line (lines/make-line (Instant/now) level event context data)
              text (case line-format
                     :json     (lines/write-json-text line)
                     :readable (lines/write-readable-text line))]
          ;; one line at a time, whole: written and kept in the same order
          (.lock lock)
          (try
            (when (= :error level) (keep-error! logbook line))
            (write-text! logbook-settings text)
            (.increment ^LongAdder (lines-written level))
            (finally (.unlock lock))))))
    (catch Throwable failure
      (.increment write-failed)
      (say-failure-once! logbook failure)))
  nil)

;; -----------------------------------------------------------------------------
;; Log functions

(defn- make-log [logbook context]
  (with-meta (fn log [level event data] (write-line! logbook context level event data))
    {::logbook logbook ::context context}))

(defn log-function
  "The logbook's log function, with no context: (log level event data).
   level is :debug, :info, :warn or :error; event a namespaced keyword
   naming what happened; data a map, {} for none, any exception in it
   written as the line's error. Returns nil, never throws."
  [logbook]
  (make-log logbook {}))

(defn with-context
  "A new log function writing this context into every line's context,
   merged over what the given log already carried. log must be one made by
   log-function or with-context (:logbook/not-a-log), context a map
   (:logbook/bad-context)."
  [log context]
  (let [{::keys [logbook] :as made} (meta log)]
    (when-not logbook
      (throw (ex-info "with-context takes a log made by logbook's log-function or with-context"
                      {:type :logbook/not-a-log})))
    (when-not (map? context)
      (throw (ex-info "A context is a map" {:type :logbook/bad-context :context context})))
    (make-log logbook (merge (::context made) context))))

(defn list-errors-since
  "The :error lines kept (the newest :errors-kept) whose sequence number is
   after since, oldest first: each the line as written, as JSON data with
   its number first, {\"seq\" 42 \"time\" … \"level\" \"error\" \"event\" …}.
   Numbers start at 1 and count every :error line, kept or not: a gap
   before the first means lines were dropped."
  [logbook since]
  (filterv #(> (get % "seq") since) (:kept @(:errors logbook))))

(defn read-counts
  "What the logbook has written since it was made, now, for a monitor
   (the app hands them to its metrics):

     {:lines          {:debug 0 :info 1520 :warn 3 :error 1}  ; lines written whole, by level
      :write-failures 0                                     ; calls whose line couldn't be written
      :errors-kept    1}                                    ; :error lines kept now, for list-errors-since

   A call below :lowest-level is no line and isn't counted; a bad call is
   counted at :error, as it is written. :lines and :write-failures only
   go up; :errors-kept stops at the settings' :errors-kept."
  [{:keys [lines-written ^LongAdder write-failed errors]}]
  {:lines          (update-vals lines-written #(.sum ^LongAdder %))
   :write-failures (.sum write-failed)
   :errors-kept    (count (:kept @errors))})
