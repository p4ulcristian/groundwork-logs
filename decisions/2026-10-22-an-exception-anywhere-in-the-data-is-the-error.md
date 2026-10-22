# An exception anywhere in the data is the line's error

**Decision:** any exception among the data map's values leaves `data` and becomes the line's `error`, whatever its key: `(log :error :mailroom/handler-failed {:mailroom/message-name n :error e})` and `{… :failure e}` write the same line. With more than one, the first by its key's text is the `error`, and every other stays in `data` as an error map of its own. Only the data's own values are looked at, not values nested deeper (those are written as error maps where they are).

The `error` is `{class message data stack cause}`:

    "error":{"class":"clojure.lang.ExceptionInfo","message":"The invoice wasn't sent",
             "data":{"order/id":"71000000-…"},
             "stack":"clojure.lang.ExceptionInfo: The invoice wasn't sent {…}\n\tat …\nCaused by: java.net.SocketTimeoutException: Read timed out\n\tat …",
             "cause":{"class":"java.net.SocketTimeoutException","message":"Read timed out"}}

- `data`, an `ex-info`'s data, written like the line's data; left out when there is none.
- `stack`, the JVM's own trace (`printStackTrace`), whole: its frames, then each cause's and each suppressed one's.
- `cause`, the chain under it, nested, each `{class message data cause}` without a stack (the stack above has every frame); each cause once, so a chain that points back ends.

The readable format writes the class and message, the ex-data, then the frames and causes, indented.

**Why:** "One line is time, level, event, context, data, error" says the error is there when the data held an exception; any key keeps that literal, so a library can name the key for what failed and never lose the stack by picking the wrong one. The cause chain as data lets Loki's `| json` filter on `error_cause_class` (a timeout under a wrapping `ex-info`) without reading the stack.

**Instead of:**
- one dedicated key (`:error`): an exception under any other key would be written as its printed form, without its stack
- each cause with its own stack: the frames written twice, since the JVM's trace already holds them
- only the class and message of the top exception: the timeout under an `ex-info` would be found only by reading the stack text
