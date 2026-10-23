# A log call is level, event, data

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

**Decision:** logging in manor is one function with three arguments: `(log level event data)`, e.g. `(log :warn :backroom/gave-up {:background-job/id id :attempts 5})`. The event is a namespaced keyword naming what happened; the data is a map. A library is handed this one function in its settings (`:log`, required) and never decides how a line is written.

**Why:** one function is one value to hand to every library. Keeping the event and the data apart means no data key can overwrite the level or the event's name.

**Instead of:**
- one map, `(log {:level :warn :event :backroom/gave-up :attempts 5})` (Iron Rainbow's shape until now): one argument, but a data key named `:level` or `:event` clashes
- a function per level, `(log/warn :backroom/gave-up {…})`: reads well, but every library would be handed four functions
