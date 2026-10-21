(ns grog.validation
  "What's wrong with settings: checked at startup (grog.main), and when the
  model changes them (grog.settings)."
  (:require [grog.config :as config]
            [grog.prompt :as prompt]
            [grog.provider :as provider]))

(defn problems
  "What's wrong with `config`, as messages naming the settings the way
  they're written: grog's own, then, when those are fine, the provider's and
  the prompt's."
  [config]
  (or (not-empty (config/problems config))
      (vec (concat (provider/problems config) (prompt/problems config)))))
