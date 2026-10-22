# A log call never throws

**Decision:** `(log level event data)` returns nil and never throws, whatever it is given, and a line is never lost silently:

- **A bad call is written at `:error`**, even below `:lowest-level`, and kept like every `:error` line, with what it was given in its data: a level that isn't one of the four keeps its event and gets `:logbook/given-level`; an event that isn't a namespaced keyword becomes `:logbook/bad-event` with `:logbook/given-event`; data that isn't a map becomes `{:logbook/given-data …}`.
- **A value JSON has no place for is written as its printed form** (`pr-str`); one whose printing fails, as `#<unprintable its.Class>`. UUIDs, moments, days, decimals (`toPlainString`), keywords (`"order/paid"`) and symbols are text; NaN and the infinities are text; sets and lists are arrays; a key that is neither a keyword nor text is printed. Data nested too deep to walk is written as `{"logbook/unwritable-data": "java.lang.StackOverflowError: …"}`.
- **A line that can't be written** (standard output refuses it, the `:write-to` function throws) is said on standard error, once per logbook, with the failure's class and message; later failures are not said again, and none reaches the caller.
- **Each line is written whole**: made outside the lock, then numbered, kept and written under one lock, one line at a time, so lines from many threads never interleave and the kept errors are in the order they were written.

**Why:** a log call sits in the paths that are already failing: a handler's catch, a background job's failure, a listener that broke. A throw there would replace the failure being reported with one about the report. Writing a bad call at `:error` makes a library's mistake show up in the errors a monitor pulls, rather than vanish below the lowest level or crash a request. Saying a broken output once keeps standard error readable when every line after it would fail the same way.

**The price:** a lazy sequence in the data is walked whole, as printing it would be: an endless one never returns. A failing output is said only once per logbook, so a second, different failure isn't seen.

**Instead of:**
- throwing on an unknown level, event or data: the mistake surfaces where it hurts most, in an error path
- dropping a bad call: a library's mistake would never be seen
- writing a bad level at `:info` or as given: below `:lowest-level` it could vanish, and as given it would not be one of the four a shipper's level label knows
- a bound on how much of a sequence is written: one more number to set, for a mistake the caller can see in its own code
- saying every failed write on standard error: a full disk turns every line into two
