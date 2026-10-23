# Context is handed down as a value

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** `(logbook/with-context log {:request-id "7f3a…"})` returns a new log function that writes that context into every line's `context`, merged over what the given log already carried. Whoever runs the work hands the log down: for each message, the app's message handling gives its steps a log that knows the request; the step that finds the person adds the person; a library called from a step is passed that same log. No dynamic var, no thread-local.

**Why:** a value goes wherever it is passed: to another thread, a virtual thread, a callback, a background job, the browser. Manor's libraries pass values to each other, and the log is one more.

**The price:** the log has to be passed along to whatever logs; a function that wasn't given one can't log with the request's context.

**Instead of:**
- a dynamic binding around the work (`binding [*context* …]`, Iron Rainbow's `with-context` until now): nothing to pass, but it is lost on another thread unless carried over by hand, and the browser has no such thing
