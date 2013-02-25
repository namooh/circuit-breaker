(ns circuit-breaker.core
  (:require
    [clj-time.core :as time]
    [clojure.tools.logging :as logger]
    [circuit-breaker.map :as map]))

(def _circuit-breakers-counters (map/new-map))
(def _circuit-breakers-config   (map/new-map))
(def _circuit-breakers-open     (map/new-map))

(defn- failure-threshold [circuit-name]
  (:threshold (map/get _circuit-breakers-config circuit-name)))

(defn- timeout-in-seconds [circuit-name]
  (:timeout (map/get _circuit-breakers-config circuit-name)))

(defn- time-since-broken [circuit-name]
  (map/get _circuit-breakers-open circuit-name))

(defn- exception-counter [circuit-name]
  (or (map/get _circuit-breakers-counters circuit-name) 0))

(defn- inc-counter [circuit-name]
  (let [circuit-count (or (map/get _circuit-breakers-counters circuit-name) 0)]
    (map/put _circuit-breakers-counters circuit-name (inc circuit-count))))

(defn- failure-count [circuit-name]
  (exception-counter circuit-name))

(defn- record-opening! [circuit-name]
  (map/put _circuit-breakers-open circuit-name (time/now)))

(defn- breaker-open? [circuit-name]
  (not (not (time-since-broken circuit-name))))

(defn- timeout-exceeded? [circuit-name]
  (> (time/in-secs (time/interval (time-since-broken circuit-name) (time/now))) (timeout-in-seconds circuit-name)))

(defn record-failure! [circuit-name]
  (inc-counter circuit-name)
  (when (> (failure-count circuit-name) (failure-threshold circuit-name))
    (record-opening! circuit-name)))

(defn record-success! [circuit-name]
  (map/put _circuit-breakers-open circuit-name nil)
  (map/put _circuit-breakers-counters circuit-name 0))

(defn- closed-circuit-path [circuit-name method-that-might-error]
  (try
    (let [result (method-that-might-error)]
      (record-success! circuit-name)
      result)
    (catch Exception e
      (record-failure! circuit-name)
      (throw e))))

(defn reset-all-circuit-counters! []
  (map/clear _circuit-breakers-counters))

(defn reset-all-circuits! []
  (map/clear _circuit-breakers-counters)
  (map/clear _circuit-breakers-config)
  (map/clear _circuit-breakers-open))

(defn tripped? [circuit-name]
  (and (breaker-open? circuit-name) (not (timeout-exceeded? circuit-name))))

(defn defncircuitbreaker [circuit-name settings]
  (map/put _circuit-breakers-counters circuit-name 0)
  (map/put _circuit-breakers-config circuit-name settings))

(defn wrap-with-circuit-breaker [circuit-name method-that-might-error]
  (if (tripped? circuit-name)
    nil
    (closed-circuit-path circuit-name method-that-might-error)))

(defn with-circuit-breaker [circuit {:keys [tripped connected]}]
  (if (tripped? circuit)
    (tripped)
    (connected)))