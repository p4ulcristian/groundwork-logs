# What logbook has written is counted, and read by the app

**Decision:** logbook counts what it writes, and `read-counts` returns it, now:

```clojure
(logbook/read-counts book)
;=> {:lines {:debug 0 :info 1520 :warn 3 :error 1}  ; lines written whole, by level
;    :write-failures 0                             ; calls whose line couldn't be written
;    :errors-kept 1}                               ; :error lines kept now, for list-errors-since
```

- **`:lines`** counts a line once it is written whole, by the level it was written at: a bad call is counted at `:error`, as it is written; a call below `:lowest-level` is no line and isn't counted.
- **`:write-failures`** counts every call whose line couldn't be made or written (logbook says only the first on standard error).
- **`:errors-kept`** is how many `:error` lines are kept now: it stops at the settings' `:errors-kept`.

Each count is a `LongAdder`, added to inside the lock a line is already written under. logbook does not depend on meter and declares no metric: the app declares read metrics (meter's `:read`) that call `read-counts` when the metrics are written. Since v0.1.1.

**Why:** a log that fails to write, or a run of `:error` lines, is something a monitor should see without reading the log itself; Prometheus reads it from the metrics. meter depends on logbook (its own lines go to the app's log), so logbook can't take a meter: the dependency would go both ways. A number logbook already holds, read when the text is written, is one source of truth and costs nothing on the path of a log call ("A metric is declared as data", meter).

**The price:** the app writes the read metrics itself, three declarations in its composition. The counts start at 0 when the logbook is made.

**Instead of:**
- logbook taking `:meter`: logbook and meter would need each other
- a fourth library between them holding the metrics of the log: one function's worth of code
- counting calls below `:lowest-level` too: they cost nothing and say nothing, and production drops `:debug` by the thousand
