# A format for production, and one for reading

**Decision:** logbook's settings require `:format`: `:json` writes one JSON object per line (the shape in "One line is time, level, event, context, data, error"), for a shipper like Vector and Loki; `:readable` writes the time, level and event on one line and the context, data and error indented below it, for a person reading a terminal. There is no default: production writes `:json`, development usually `:readable`.

**Why:** JSON lines are exact for machines and hard to read while working; a choice per environment, written in the settings.

**The price:** two writers to keep in step; tests check both from the same calls.

**Instead of:**
- always JSON: one writer, but development reads it through jq
