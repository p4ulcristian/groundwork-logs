(ns groundwork-logs.api-names-test
  "The public names of groundwork-logs's api. Adding or removing one fails here,
   so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [groundwork-logs.api]))

(deftest the-api-names
  (is (= '#{settings-shape make-logger log-function with-context list-errors-since read-counts}
         (set (keys (ns-publics 'groundwork-logs.api))))))
