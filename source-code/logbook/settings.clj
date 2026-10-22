(ns logbook.settings
  "logbook's settings, as a malli shape, checked before anything is
   written. Nothing has a default: every key is written out, and a missing
   or unknown one refuses, named, with every other problem at once."
  (:require
   [malli.core :as malli]
   [malli.error :as malli-error]))

(def levels
  "The four levels, lowest first."
  [:debug :info :warn :error])

(def settings-shape
  "make-logbook's settings, every key required.
   :lowest-level: :debug, :info, :warn or :error; a call below it writes
   nothing (production :info, development :debug).
   :format: :json, one JSON object per line, for a shipper (Vector, Loki);
   :readable, the time, level and event on one line and the context, data
   and error indented below it, for a person at a terminal.
   :errors-kept: how many of the newest :error lines are kept in memory,
   for list-errors-since; 0 keeps none.
   :write-to: :stdout, standard output; or (f text), handed each line's
   whole text, its newline included, one call at a time (a test, another
   stream)."
  [:map {:closed true}
   [:lowest-level (into [:enum] levels)]
   [:format [:enum :json :readable]]
   [:errors-kept [:int {:min 0}]]
   [:write-to [:or [:= :stdout] fn?]]])

(defn check-settings
  "Returns the settings; throws {:type :logbook/bad-settings :errors …}
   with every problem at once."
  [settings]
  (when-let [explanation (malli/explain settings-shape settings)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str "The settings have problems: " (pr-str errors))
                      {:type :logbook/bad-settings :errors errors}))))
  settings)
