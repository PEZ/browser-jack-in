(ns tampers.storage-api-exercise
  (:require [epupp.storage :as storage]))

(comment
  ;; ===== STORAGE API (no FS sync required) =====

  ;; Set and get - nested map with keywords preserved
  (defn ^:async set-get-roundtrip []
    (let [stored (await (storage/set! :ui/prefs {:ui/theme :theme/dark
                                                 :ui/nested {:count 3}}))
          fetched (await (storage/get :ui/prefs))]
      (def stored-result stored)
      (def fetched-result fetched)))
  (set-get-roundtrip)

  ;; Keyword key coerces to namespaced path (my/settings)
  (defn ^:async keyword-key-form []
    (let [_ (await (storage/set! :my/settings {:enabled true}))
          result (await (storage/get :my/settings))]
      (def keyword-key-result result)))
  (keyword-key-form)

  ;; String key form uses the same bucket path
  (defn ^:async string-key-form []
    (let [_ (await (storage/set! "my/settings" {:via "string-key"}))
          result (await (storage/get "my/settings"))]
      (def string-key-result result)))
  (string-key-form)

  ;; Get missing key returns nil
  (defn ^:async get-missing []
    (let [result (await (storage/get :does/not-exist))]
      (def get-missing-result result)))
  (get-missing)

  ;; remove! is idempotent
  (defn ^:async remove-idempotent []
    (await (storage/set! :temp/key "value"))
    (def remove-first (await (storage/remove! :temp/key)))
    (def remove-second (await (storage/remove! :temp/key))))
  (remove-idempotent)

  ;; keys returns vector of keywords
  (defn ^:async list-keys []
    (await (storage/set! :e2e/a 1))
    (await (storage/set! :e2e/b 2))
    (let [result (await (storage/keys))]
      (def keys-result result)))
  (list-keys)

  ;; clear! empties user bucket only (epuppUserKv)
  (defn ^:async clear-bucket []
    (await (storage/set! :e2e/clear-test "x"))
    (def clear-result (await (storage/clear!))))
  (clear-bucket)

  ;; ===== CLEANUP =====
  (defn ^:async cleanup []
    (await (storage/clear!)))
  (cleanup)

  :rcf)
