(ns pg-realtime.throttle
  (:require [clojure.core.async :refer [chan go-loop <! timeout sliding-buffer alts!]]))

(defn create-throttler
  "Returns a channel `in-ch`.  Any vector of args put into `in-ch` will invoke `(apply f args)` at most
   once per `ms`. Immediately first, then (if more calls arrived) one trailing call with the last args.
   To stop, just `(close! in-ch)`."
  [f ms]
  (let [in-ch    (chan (sliding-buffer 1))]
    (go-loop []
      (when-let [args (<! in-ch)]
        ;; fire first call
        (apply f (if (= args :no-args) [] args))

        (loop [last-args nil]
          (let [[v port] (alts! [(timeout ms) in-ch])]
            (if (= port in-ch)          ; received new args
              (recur v)                 ; overwrite with newest
              (when last-args           ; timeout → maybe fire trailing
                (apply f
                       (if (= last-args :no-args) [] last-args)))))))
      (recur))
    in-ch))