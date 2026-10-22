# Four levels, and a lowest level the app sets

**Decision:** a log call's level is one of `:debug`, `:info`, `:warn`, `:error`, in that order. logbook's settings require `:lowest-level`; a call below it writes nothing. Production usually sets `:info`, development `:debug`; there is no default.

**Why:** the four levels every logger knows, so a library knows which to pick; dropping the chatty ones is a choice per environment, written in the settings like every other value.

**Instead of:**
- three levels and nothing dropped: no place for development detail
- any keyword as a level, ordered by the app: every library would guess which level to use
