(ns logbook.api-test
  "Both formats from the same calls, context handed down, levels dropped,
   errors kept and listed, values JSON has no place for, an exception with
   its cause, bad calls, a failing write, the counts, and lines whole
   under many threads."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as json]
   [logbook.api :as logbook])
  (:import
   (java.time Instant LocalDate)
   (java.util.concurrent CountDownLatch)))

(defn- make-logbook-writing-into
  "A logbook whose lines go into an atom of texts."
  [texts overrides]
  (logbook/make-logbook (merge {:lowest-level :debug :format :json :errors-kept 10
                                :write-to     #(swap! texts conj %)}
                               overrides)))

(defn- read-lines [texts]
  (mapv #(json/read-value %) @texts))

(defn- without-time [line]
  (dissoc line "time"))

(def ^:private order-id #uuid "71000000-0000-4000-8000-000000000071")

(defn- make-the-calls [log]
  (let [request-log (logbook/with-context log {:request-id "7f3a"})
        person-log  (logbook/with-context request-log {:person-id #uuid "41000000-0000-4000-8000-000000000041"})]
    (log :info :house/started {:house/version "1" :house/port 8080})
    (person-log :warn :backroom/gave-up {:background-job/id order-id :background-job/attempts 5
                                         :total 120.50M :on (LocalDate/parse "2026-10-22")
                                         :at (Instant/parse "2026-10-22T23:10:04.120Z") :status :order/paid})))

(deftest a-line-is-time-level-event-context-data
  (let [texts (atom [])
        log   (logbook/log-function (make-logbook-writing-into texts {}))]
    (make-the-calls log)
    (is (every? #(and (str/ends-with? % "\n") (= 1 (count (str/split-lines %)))) @texts) "one line each")
    (is (re-matches #"\{\"time\":\"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z\",\"level\":\"info\",\"event\":\"house/started\",.*\n"
                    (first @texts))
        "time, level and event first, the time to the millisecond in UTC")
    (is (= [{"level" "info" "event" "house/started" "context" {} "data" {"house/version" "1" "house/port" 8080}}
            {"level"   "warn" "event" "backroom/gave-up"
             "context" {"request-id" "7f3a" "person-id" "41000000-0000-4000-8000-000000000041"}
             "data"    {"background-job/id" (str order-id) "background-job/attempts" 5 "total" "120.50"
                        "on" "2026-10-22" "at" "2026-10-22T23:10:04.120Z" "status" "order/paid"}}]
           (mapv without-time (read-lines texts)))
        "namespaced keys keep their namespace; UUIDs, decimals, days, moments and keywords as text")))

(deftest the-readable-format-from-the-same-calls
  (let [texts (atom [])
        log   (logbook/log-function (make-logbook-writing-into texts {:format :readable}))]
    (make-the-calls log)
    (is (= [" INFO   house/started"
            "    data     house/version \"1\""
            "             house/port    8080"
            " WARN   backroom/gave-up"
            "    context  request-id \"7f3a\""
            "             person-id  \"41000000-0000-4000-8000-000000000041\""
            "    data     background-job/id       \"71000000-0000-4000-8000-000000000071\""
            "             background-job/attempts 5"
            "             total                   \"120.50\""
            "             on                      \"2026-10-22\""
            "             at                      \"2026-10-22T23:10:04.120Z\""
            "             status                  \"order/paid\""]
           (mapv #(str/replace % #"^\S+Z " "") (mapcat str/split-lines @texts))))))

(deftest context-is-merged-over-the-old
  (let [texts (atom [])
        log   (logbook/log-function (make-logbook-writing-into texts {}))
        outer (logbook/with-context log {:request-id "a" :person-id nil})
        inner (logbook/with-context outer {:person-id "p"})]
    (inner :info :test/one {})
    (outer :info :test/two {:request-id "the call's own"})
    (log :info :test/three {})
    (is (= [{"request-id" "a" "person-id" "p"} {"request-id" "a" "person-id" nil} {}]
           (mapv #(get % "context") (read-lines texts)))
        "each log keeps its own context; a new one is merged over the one it was made from")
    (is (= {"request-id" "the call's own"} (get-in (read-lines texts) [1 "data"]))
        "a data key never touches the context"))
  (is (= :logbook/not-a-log
         (:type (ex-data (try (logbook/with-context (fn [_ _ _]) {:a 1}) (catch Exception e e))))))
  (is (= :logbook/bad-context
         (:type (ex-data (try (logbook/with-context (logbook/log-function (make-logbook-writing-into (atom []) {})) [:a])
                              (catch Exception e e)))))))

(deftest levels-below-the-lowest-are-dropped
  (let [texts (atom [])
        log   (logbook/log-function (make-logbook-writing-into texts {:lowest-level :warn}))]
    (doseq [level [:debug :info :warn :error]]
      (log level :test/said {:level level}))
    (is (= ["warn" "error"] (mapv #(get % "level") (read-lines texts))))
    (is (= {"level" "warn"} (get-in (read-lines texts) [0 "data"])) "a data key named level stays data")))

(deftest errors-are-kept-bounded-and-listed-by-number
  (let [texts   (atom [])
        lb      (make-logbook-writing-into texts {:errors-kept 3})
        log     (logbook/log-function lb)]
    (is (= [] (logbook/list-errors-since lb 0)))
    (doseq [n (range 1 6)]
      (log :error :test/failed {:n n})
      (log :warn :test/not-kept {:n n}))
    (is (= [[3 3] [4 4] [5 5]] (mapv (juxt #(get % "seq") #(get-in % ["data" "n"])) (logbook/list-errors-since lb 0)))
        "the newest three, oldest first; numbers count every error, so a gap shows what was dropped")
    (is (= [5] (mapv #(get % "seq") (logbook/list-errors-since lb 4))))
    (is (= [] (logbook/list-errors-since lb 5)))
    (is (= ["seq" "time" "level" "event" "context" "data"] (vec (keys (first (logbook/list-errors-since lb 0)))))
        "each as written, its number first")
    (is (= 10 (count @texts)) "every line is written, kept or not"))
  (let [lb (make-logbook-writing-into (atom []) {:errors-kept 0})]
    ((logbook/log-function lb) :error :test/failed {})
    (is (= [] (logbook/list-errors-since lb 0)) "0 keeps none")))

(deftest a-value-json-has-no-place-for-is-printed
  (let [texts (atom [])
        log   (logbook/log-function (make-logbook-writing-into texts {}))
        odd   (reify Object (toString [_] "an odd thing"))
        bad   (reify Object (toString [_] (throw (RuntimeException. "can't print me"))))]
    (log :info :test/odd {:odd odd :nan Double/NaN :ratio 1/3 :fn-key {inc 1} :set #{1} :list '(1 2)
                          :big 123456789012345678901234567890N :nested {:a [{:b :c/d}]}})
    (log :info :test/unprintable {:bad bad})
    (let [[odd-line bad-line] (read-lines texts)
          data                (get odd-line "data")]
      (is (str/includes? (get data "odd") "an odd thing"))
      (is (= ["NaN" "1/3" [1] [1 2] 123456789012345678901234567890 {"a" [{"b" "c/d"}]}]
             (mapv data ["nan" "ratio" "set" "list" "big" "nested"])))
      (is (= 1 (count (get data "fn-key"))) "a key that isn't text or a keyword is printed")
      (is (str/starts-with? (get-in bad-line ["data" "bad"]) "#<unprintable ")))))

(deftest an-exception-in-the-data-is-the-error
  (let [texts (atom [])
        log   (logbook/log-function (make-logbook-writing-into texts {}))
        cause (java.net.SocketTimeoutException. "Read timed out")
        e     (ex-info "The invoice wasn't sent" {:order/id order-id} cause)]
    (log :error :app/invoice-failed {:order/id order-id :failure e})
    (let [[line] (read-lines texts)]
      (is (= {"order/id" (str order-id)} (get line "data")) "the exception leaves the data")
      (is (= {"class"   "clojure.lang.ExceptionInfo"
              "message" "The invoice wasn't sent"
              "data"    {"order/id" (str order-id)}
              "cause"   {"class" "java.net.SocketTimeoutException" "message" "Read timed out"}}
             (dissoc (get line "error") "stack")))
      (is (str/includes? (get-in line ["error" "stack"]) "Caused by: java.net.SocketTimeoutException: Read timed out")
          "the stack is the JVM's whole trace, the cause's included")
      (is (= ["time" "level" "event" "context" "data" "error"] (vec (keys (json/read-value (first @texts) json/default-object-mapper))))))
    (testing "two exceptions: the first by its key is the error, the other an error map in the data"
      (reset! texts [])
      (log :error :app/two {:b (RuntimeException. "second") :a (IllegalStateException. "first")})
      (let [[line] (read-lines texts)]
        (is (= "first" (get-in line ["error" "message"])))
        (is (= "second" (get-in line ["data" "b" "message"])))))
    (testing "readable: the class and message, then the frames, indented"
      (let [texts (atom [])
            log   (logbook/log-function (make-logbook-writing-into texts {:format :readable}))]
        (log :error :app/invoice-failed {:failure e})
        (let [lines (str/split-lines (first @texts))]
          (is (re-find #"ERROR  app/invoice-failed$" (first lines)))
          (is (= "    error    clojure.lang.ExceptionInfo: The invoice wasn't sent"
                 (second lines)))
          (is (= "             data {\"order/id\":\"71000000-0000-4000-8000-000000000071\"}" (nth lines 2)))
          (is (str/starts-with? (nth lines 3) "             at "))
          (is (some #(= "             Caused by: java.net.SocketTimeoutException: Read timed out" %) lines)))))))

(deftest a-bad-call-is-written-at-error-not-thrown
  (let [texts (atom [])
        lb    (make-logbook-writing-into texts {:lowest-level :error})
        log   (logbook/log-function lb)]
    (is (nil? (log :loud :test/said {:a 1})))
    (log :info "not a keyword" {})
    (log :info :test/said "not a map")
    (log :warn :unqualified {})
    (is (= [["error" "test/said" {"a" 1 "logbook/given-level" "loud"}]
            ["error" "logbook/bad-event" {"logbook/given-event" "not a keyword"}]
            ["error" "test/said" {"logbook/given-data" "not a map"}]
            ["error" "logbook/bad-event" {"logbook/given-event" "unqualified"}]]
           (mapv (juxt #(get % "level") #(get % "event") #(get % "data")) (read-lines texts)))
        "written even below the lowest level, so it is seen")
    (is (= 4 (count (logbook/list-errors-since lb 0))) "and kept, as every :error line")))

(deftest a-failing-write-is-said-once-on-standard-error-never-thrown
  (let [calls (atom 0)
        lb    (logbook/make-logbook {:lowest-level :debug :format :json :errors-kept 1
                                     :write-to     (fn [_] (swap! calls inc) (throw (RuntimeException. "disk full")))})
        log   (logbook/log-function lb)
        err   (java.io.ByteArrayOutputStream.)
        old   System/err]
    (System/setErr (java.io.PrintStream. err true "UTF-8"))
    (try
      (is (nil? (log :info :test/one {})))
      (is (nil? (log :error :test/two {})))
      (finally (System/setErr old)))
    (is (= 2 @calls))
    (is (= 1 (count (str/split-lines (str err)))) "said once")
    (is (str/includes? (str err) "java.lang.RuntimeException: disk full"))
    (is (= {:lines {:debug 0 :info 0 :warn 0 :error 0} :write-failures 2 :errors-kept 1}
           (logbook/read-counts lb))
        "two failures counted; the :error line was kept before the write")))

(deftest the-counts-say-what-was-written
  (let [texts (atom [])
        lb    (make-logbook-writing-into texts {:lowest-level :info :errors-kept 2})
        log   (logbook/log-function lb)]
    (is (= {:lines {:debug 0 :info 0 :warn 0 :error 0} :write-failures 0 :errors-kept 0}
           (logbook/read-counts lb))
        "nothing yet")
    (log :debug :test/below {})
    (log :info :test/one {})
    (log :info :test/two {})
    ((logbook/with-context log {:request-id "7f3a"}) :warn :test/three {})
    (dotimes [_ 3] (log :error :test/four {}))
    (log :fifth :test/five {})
    (is (= {:lines {:debug 0 :info 2 :warn 1 :error 4} :write-failures 0 :errors-kept 2}
           (logbook/read-counts lb))
        "below the lowest level, nothing; a bad call at :error; the kept errors stop at :errors-kept")))

(deftest standard-output-gets-the-lines
  (let [out (java.io.ByteArrayOutputStream.)
        old System/out]
    (System/setOut (java.io.PrintStream. out true "UTF-8"))
    (try
      ((logbook/log-function (logbook/make-logbook {:lowest-level :info :format :json :errors-kept 0 :write-to :stdout}))
       :info :test/said {:text "árvíztűrő"})
      (finally (System/setOut old)))
    (is (= {"text" "árvíztűrő"} (get (json/read-value (.toString out "UTF-8")) "data")))))

(deftest lines-stay-whole-across-threads
  (let [texts   (atom [])
        lb      (make-logbook-writing-into texts {:errors-kept 10000})
        ;; the writer itself writes in pieces: only the lock keeps a line whole
        pieces  (StringBuilder.)
        lb      (assoc-in lb [:settings :write-to]
                          (fn [text] (doseq [c text] (.append pieces c) (when (= \newline c) (Thread/yield)))))
        log     (logbook/log-function lb)
        threads 16
        each    200
        start   (CountDownLatch. 1)
        workers (doall (for [t (range threads)]
                         (Thread/startVirtualThread
                          #(do (.await start)
                               (dotimes [n each]
                                 (log (if (even? n) :info :error) :test/said {:thread t :n n :text (apply str (repeat 50 "x"))}))))))]
    (.countDown start)
    (run! #(.join ^Thread %) workers)
    (let [lines (str/split-lines (str pieces))]
      (is (= (* threads each) (count lines)))
      (is (every? #(= 50 (count (get-in (json/read-value %) ["data" "text"]))) lines) "every line parses, whole")
      (is (= (range 1 (inc (/ (* threads each) 2)))
             (map #(get % "seq") (logbook/list-errors-since lb 0)))
          "kept in the order they were numbered"))
    (is (empty? @texts))))

(deftest the-settings-are-checked-all-at-once
  (let [errors (:errors (ex-data (try (logbook/make-logbook {:lowest-level :trace :format :json :surprise 1})
                                      (catch Exception e e))))]
    (is (= #{:lowest-level :errors-kept :write-to :surprise} (set (keys errors))))))
