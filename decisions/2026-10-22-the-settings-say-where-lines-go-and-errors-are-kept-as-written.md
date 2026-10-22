# The settings say where lines go; the errors are kept as written

**Decision:** logbook's settings are four keys, each required, the map closed (`settings-shape`, checked by malli, `:logbook/bad-settings` naming every problem at once):

```clojure
{:lowest-level :info      ; :debug :info :warn :error
 :format       :json      ; or :readable
 :errors-kept  1000       ; the newest :error lines kept for pulling; 0 keeps none
 :write-to     :stdout}   ; or (f text): each line's whole text, newline included, one call at a time
```

The api is five names: `settings-shape`, `make-logbook` (the settings, checked; a logbook), `log-function` (the logbook's log, with no context), `with-context` (a new log, its context merged over the given log's) and `list-errors-since`.

- **`:write-to`** is `:stdout` in an app, as "One line is time, level, event, context, data, error" decides; a function is for a test, or an app that sends lines elsewhere. Standard output is written as UTF-8 bytes and flushed after each line.
- **A kept error is the line as written**, as JSON data with text keys, its sequence number first: `{"seq" 42 "time" … "level" "error" "event" … "context" … "data" … "error" …}`. Numbers start at 1 and count every `:error` line, kept or dropped, so a monitor that sees a gap before the first one it gets knows lines were dropped. `(list-errors-since logbook 41)` returns those after 41, oldest first, ready to be written as JSON by whoever serves them.
- **`with-context` takes only a log made by logbook** (`log-function` or `with-context`), and throws `:logbook/not-a-log` otherwise, and `:logbook/bad-context` for a context that isn't a map. A log is a plain function (`fn?` is true) carrying its logbook and context as metadata. The time is the moment of the call, in UTC, to the millisecond.

**Why:** where lines go is a choice like the other three: a test needs the lines without taking over the JVM's standard output, and writing it out keeps standard output from being a hidden default. Keeping the line as written means the list a monitor pulls is exactly what stdout had, with nothing to convert, in the same shape whichever format the terminal shows. A context that can't be merged would be lost silently; a library hands down the log it was given, so the refusal shows the first time the app passes something else.

**Instead of:**
- standard output always: tests would swap `System/out`, which other threads write to too
- a kept error as Clojure data (keywords, UUIDs): the route serving it would have to write it again, and could write it differently from the line
- `with-context` on any function, the context dropped: lines without their request id, and no sign why
- a clock in the settings: one more key for tests only; they compare lines without the time
