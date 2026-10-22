# logbook

Logging for a Clojure app and its libraries, in one function:
`(log level event data)`. The app makes it once and hands it to every
library, which never decides how a line is written. A line is time, level,
event, context, data, and error when the data held an exception; written to
standard output as JSON (for a shipper like Vector into Loki) or readable
(for a terminal). Context, the request and the person, is handed down as a
value with `with-context`, not a dynamic var. The newest `:error` lines are
kept in memory, numbered, for a monitor to pull.

```clojure
io.github.p4ulcristian/logbook {:git/tag "v0.1.1" :git/sha "51a2c93"}
```

Needs only jsonista and malli. One public namespace, `logbook.api`.

## The api

| name | does |
|---|---|
| `make-logbook` `settings-shape` | a logbook from its settings, every key required, checked first |
| `log-function` | the logbook's log, `(log level event data)`, no context; returns nil, never throws |
| `with-context` | a new log writing this context into every line, merged over the given log's |
| `list-errors-since` | the kept `:error` lines numbered after `n`, oldest first, as written |
| `read-counts` | what it has written, now: lines by level, lines that couldn't be written, `:error` lines kept; for the app's metrics |

```clojure
(require '[logbook.api :as logbook])

(def book (logbook/make-logbook {:lowest-level :info      ; :debug :info :warn :error; below it, nothing
                                 :format       :json      ; or :readable
                                 :errors-kept  1000       ; the newest :error lines, for list-errors-since
                                 :write-to     :stdout})) ; or (f text), each line whole
(def log (logbook/log-function book))

(log :info :house/started {:house/version "2026-10-22.1"})

(def request-log (logbook/with-context log {:request-id "7f3a…"}))
(def person-log  (logbook/with-context request-log {:person-id #uuid "41b2…"}))
(person-log :warn :backroom/gave-up {:background-job/id job-id :background-job/attempts 5})
;; {"time":"2026-10-22T23:10:04.120Z","level":"warn","event":"backroom/gave-up",
;;  "context":{"request-id":"7f3a…","person-id":"41b2…"},
;;  "data":{"background-job/id":"9c1e…","background-job/attempts":5}}

(person-log :error :mailroom/handler-failed {:mailroom/message-name :orders/of-shop :error e})
;; … "error":{"class":"java.net.SocketTimeoutException","message":"Read timed out","stack":"…"}

(logbook/list-errors-since book 0)
;=> [{"seq" 1 "time" "…" "level" "error" "event" "mailroom/handler-failed" …}]

(logbook/read-counts book)
;=> {:lines {:debug 0 :info 1 :warn 1 :error 1} :write-failures 0 :errors-kept 1}
```

`read-counts` is for the app's metrics: logbook counts nothing into a
meter itself (meter's own log is a logbook), so the app reads these when
its metrics are written
([why](decisions/2026-10-23-what-is-written-is-counted-and-read-by-the-app.md)).

`:readable` writes the same calls as

```
2026-10-22T23:10:04.120Z  WARN   backroom/gave-up
    context  request-id "7f3a…"
             person-id  "41b2…"
    data     background-job/id       "9c1e…"
             background-job/attempts 5
```

Namespaced keys keep their namespace as text; UUIDs, moments, days,
decimals and keywords are text; any other value JSON has no place for is
its printed form. An exception under any key of the data is the line's
`error`: class, message, ex-data, the JVM's stack, and the cause chain. A
bad call (a fifth level, an event that isn't a namespaced keyword, data that
isn't a map) is written at `:error` saying what it was given; a line that
can't be written is said once on standard error. Lines are written whole,
one at a time. A lazy sequence in the data is walked whole: never log an
endless one.

## In Loki

Labels stay few and come from the shipper, not the line: `app`, `env`,
`level`. Everything else is found with `| json`, which turns
`context."request-id"` into `context_request_id` and
`data."background-job/attempts"` into `data_background_job_attempts`:

```logql
{app="shop", env="production", level="error"}
{app="shop"} | json | context_request_id="7f3a…"
{app="shop"} | json | context_person_id="41b2…" | event="mailroom/handler-failed"
{app="shop"} | json | event="backroom/gave-up" | data_background_job_attempts >= 5
{app="shop", level="error"} | json | error_cause_class="java.net.SocketTimeoutException"
```

## Tests

```sh
clojure -X:test
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
