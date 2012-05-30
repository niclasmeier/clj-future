(ns clj-future.core-test
  (:refer-clojure :exclude [future promise])
  (:use midje.sweet
        clj-future.core))

(let [p (promise)]
  (facts "about delivering promises"
    (try-success p 1) => truthy
    (try-success p 2) => falsey
    @p => 1
    (deref p 1 java.util.concurrent.TimeUnit/MINUTES) => 1))


(let [a (atom nil)
      p (future
    (do (java.lang.Thread/sleep 1000)
      42)
    (on-success [v] (swap! a (fn [x] v))))]
  (facts "about delivering promises"
    @a => nil
    @p => 42
    @a => 42))

(let [a (atom nil)
      e (Exception. "Test")
      p (future
    (do (java.lang.Thread/sleep 1000)
      (throw e))
    (on-failure [v] (swap! a (fn [x] v))))]
  (facts "about delivering promises"
    @a => nil
    @p => e
    @a => e))