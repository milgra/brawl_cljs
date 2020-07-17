(ns brawl.defaults)


(defn local-storage-supported?
  []
  (let [item "workshub-test-ls"]
    (try
      (.setItem js/localStorage item item)
      (.removeItem js/localStorage item)
      true
      (catch :default _ false))))


(defn load-defaults!
  [state]
  (if (and (local-storage-supported?) (= "true" (.getItem js/localStorage "state-saved?")))
    (let [state-js (cljs.reader/read-string (.getItem js/localStorage "state"))]
      ;; todo validate with specs
      (merge state state-js))
    state))


(defn save-defaults!
  [state]
  (if (local-storage-supported?)
    (let [state-js {:level (:level state)
                    :physics (:physics state)
                    :volumes {:music (get-in state [:volumes :music])
                              :effects (get-in state [:volumes :effects])}
                    :metrics (get-in state [:world :actors :hero :metrics :base])}]
      (.setItem js/localStorage "state-saved?" true)
      (.setItem js/localStorage "state" state-js)
      state)
    state))
