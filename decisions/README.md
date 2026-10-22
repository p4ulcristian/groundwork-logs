# logbook decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

logbook is manor's logging (manor: house and the libraries it puts together). The first six were decided before any code; the next three settled what they left open, as v0.1.0 was built; the last came with meter, manor's metrics (v0.1.1).

- [A log call is level, event, data](2026-10-22-a-log-call-is-level-event-data.md)
- [Four levels, and a lowest level the app sets](2026-10-22-four-levels-and-a-lowest-level.md)
- [One line is time, level, event, context, data, error](2026-10-22-one-line-is-time-level-event-context-data-error.md)
- [Context is handed down as a value](2026-10-22-context-is-handed-down-as-a-value.md)
- [An error is a log line, kept for pulling](2026-10-22-an-error-is-a-log-line-kept-for-pulling.md)
- [A format for production, and one for reading](2026-10-22-a-format-for-production-and-one-for-reading.md)
- [A log call never throws](2026-10-22-a-log-call-never-throws.md)
- [An exception anywhere in the data is the line's error](2026-10-22-an-exception-anywhere-in-the-data-is-the-error.md)
- [The settings say where lines go; the errors are kept as written](2026-10-22-the-settings-say-where-lines-go-and-errors-are-kept-as-written.md)
- [What logbook has written is counted, and read by the app](2026-10-23-what-is-written-is-counted-and-read-by-the-app.md)
