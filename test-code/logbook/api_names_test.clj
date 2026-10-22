(ns logbook.api-names-test
  "The public names of logbook's api. Adding or removing one fails here,
   so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [logbook.api]))

(deftest the-api-names
  (is (= '#{settings-shape make-logbook log-function with-context list-errors-since}
         (set (keys (ns-publics 'logbook.api))))))
