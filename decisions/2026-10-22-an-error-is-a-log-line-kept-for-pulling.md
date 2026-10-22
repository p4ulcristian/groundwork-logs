# An error is a log line, kept for pulling

**Decision:** every call at `:error` is written like any line and also kept in memory: logbook holds the newest `:errors-kept` of them (a required setting), each with a sequence number, and `list-errors-since` returns those after a given number, oldest first. The app's composition serves them on a route whose path and token are settings, for a monitor to pull. The browser's uncaught errors and unhandled promise rejections are sent to the server as a message and logged there at `:error`, so they land in the same list with the same context.

**Why:** one path for every error, server or browser; the list is bounded, so a crash loop can't fill memory; pulling means the app holds no outbound credential and exposes nothing beyond one guarded route (the way Iron Rainbow's errors reach Eye on Rainbow).

**The price:** kept only in memory: errors not pulled before a restart are lost from the list (stdout still has them).

**Instead of:**
- a separate errors library: every error would be two calls, log it and record it, which can drift
- pushing each error to a service: the app would need an outbound credential
