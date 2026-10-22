# One line is time, level, event, context, data, error

**Decision:** logbook writes one JSON object per line on standard output:

    {"time":"2026-10-22T23:10:04.120Z","level":"warn","event":"backroom/gave-up",
     "context":{"request-id":"7f3a…","person-id":"41b2…"},
     "data":{"background-job/id":"9c1e…","background-job/attempts":5},
     "error":{"class":"java.net.SocketTimeoutException","message":"Read timed out","stack":"…"}}

`time`, `level` and `event` come first, always. `context` says who and which request; it is filled by whoever runs the work (the app's composition, its message handling), never by the log call. `data` is exactly the map the call gave. `error` is there only when the data held an exception, which leaves `data` and becomes class, message and stack. Namespaced keys keep their namespace as text (`"background-job/id"`), as wire writes them.

For Loki: labels stay few and are set by the shipper, not the line (`app`, `env`, `level`); everything else is found with `| json`, which turns `data."background-job/attempts"` into `data_background_job_attempts` and `context."request-id"` into `context_request_id`.

**Why:** the same split as OpenTelemetry's log model (timestamp, severity, body, attributes) and Telemere's signals (`:inst :level :id :ctx :data :error`). Each part is its own map, so a data key can never overwrite the time, level or event, and context never overwrites data: no renaming rule. Request ids stay out of Loki's labels, which slow down with many values.

**Instead of:**
- data flat beside time, level and event (Iron Rainbow's shape until now): shortest queries, but a data key named `level` or `event` needs a renaming rule
- context mixed into data: a call's own `:request-id` and the running request's would clash
