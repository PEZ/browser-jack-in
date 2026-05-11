(ns utils)

(defn kw-namespace [k]
  (let [s (str k)
        idx (.indexOf s "/")]
    (when (pos? idx)
      (subs s 0 idx))))
