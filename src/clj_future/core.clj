(ns clj-future.core
  (:refer-clojure :exclude [future promise])
  (:import [java.util.concurrent Executors ExecutorService]))

(defprotocol Future
  (value [this])
  (on-complete [this value])
  (on-success [this value])
  (on-failure [this value])
  )



(def ^{:private true :dynamic true} *current-executor* nil)

(def ^:private default-executor* (atom nil))

(defn default-executor [] (and @default-executor*)
  (swap! default-executor* (fn [_] (let [s (Executors/newCachedThreadPool)]
                                     (fn [^Runnable r] (.submit s r))))))

(defprotocol Promise
  (future* [promise])
  (complete-with [promise future])
  (on-complete* [promise listener])
  (try-success* [promise x])
  (try-failure* [promise x])
  (status* [promise])
  )

(defn- notify [x & listeners]
  (doseq [listener listeners :when listener] (listener x)))

(defn is-successful? [x] (instance? java.lang.Throwable x))

(defn- try-complete-promise [^java.util.concurrent.CountDownLatch d v s x complete variable]
  (if (pos? (.getCount d))
    (if (compare-and-set! v nil [s x])
      (do
        (.countDown d)
        (apply notify x (concat @complete @variable))
        true)
      false)))

(defn- create-list [listener] (atom (if (sequential? listener) listener (list listener))))

(declare try-complete)

(defn promise [& {:keys [on-complete on-success on-failure] :as params}]
  "Creates a new promise instance"
  (let [d (java.util.concurrent.CountDownLatch. 1)
        v (atom nil)
        on-complete (create-list on-complete)
        on-success (create-list on-success)
        on-failure (create-list on-failure)]
    (reify
      Promise
      (future* [this] this)
      (complete-with [this future] (.on-complete* future (fn [v] (try-complete this v))))
      (on-complete* [this listener] (swap! on-complete conj listener))
      (try-success* [_ x] (try-complete-promise d v :success x on-complete on-success))
      (try-failure* [_ x] (try-complete-promise d v :failure x on-complete on-failure))
      (status* [this] (if (.isRealized this) (first @v) :pending ))
      clojure.lang.IDeref
      (deref [_] (.await d) (second @v))
      clojure.lang.IBlockingDeref
      (deref
        [_ timeout-ms timeout-val]
        (if (.await d timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
          (second @v)
          timeout-val))
      clojure.lang.IPending
      (isRealized [this]
        (zero? (.getCount d)))
      clojure.lang.IFn
      (invoke
        [this x]
        (when (and (pos? (.getCount d))
                (compare-and-set! v d x))
          (.countDown d)
          this))
      ))
  )

(defn status [promise] (.status* promise))

(defn try-success [promise x] (.try-success* promise x))
(defn success [promise x] (if (try-success promise x) promise (throw (IllegalStateException. "Promise has already been written."))))


(defn try-failure [promise x] (.try-failure* promise x))
(defn failure [promise x] (if (try-failure promise x) promise (throw (IllegalStateException. "Promise has already been written."))))

(defn try-complete [promise x & {:keys [rating-fn] :or {rating-fn is-successful?}}]
  (if (rating-fn x) (try-success promise x) (try-failure promise x)))
(defn complete [promise x & params] (if (apply try-complete promise x params) promise (throw (IllegalStateException. "Promise has already been written."))))

(defn execute-future [& {:keys [body on-complete on-success on-failure executor]
                         :or {body (fn [] nil)
                              executor (or *current-executor* (default-executor))}
                         :as params}]
  (let [p (apply promise (reduce (fn [l e] (concat l e)) (select-keys params [:on-complete :on-success :on-failure ])))
        run (fn [] (try
                     (try-success p (body))
                     (catch java.lang.Exception e
                       (try-failure p e))))]
    (executor run)
    p))

(defmacro with-executor
  "Selects/sets an executor for all futures in this block."
  [executor & body] `(binding [*current-executor* ~executor] ~@body))


(defmacro future [body & callbacks]
  (let [params (reduce (fn [l e] (concat l e)) [:body `(fn [] ~body)]
    (select-keys
      (reduce (fn [m form] (let [[k & r] form
                                 fn (cons 'fn r)] (assoc m (keyword k) fn))) nil callbacks) [:on-complete :on-success :on-failure ]))]
    `(execute-future ~@params)
    ))