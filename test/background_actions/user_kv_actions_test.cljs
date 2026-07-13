(ns background-actions.user-kv-actions-test
  (:require ["vitest" :refer [describe test expect]]
            [background-actions.user-kv-actions :as user-kv]))

(defn- test-coerce-bucket-key-keyword-and-string-match []
  (.toBe (expect (user-kv/coerce-bucket-key :my/settings))
         (user-kv/coerce-bucket-key "my/settings")))

(defn- test-blob-get-keyword-and-string-same-bucket []
  (let [blob (user-kv/blob-set {} :my/settings "val")]
    (.toBe (expect (user-kv/blob-get blob "my/settings")) "val")
    (.toBe (expect (user-kv/blob-get blob :my/settings)) "val")))

(defn- test-blob-get-miss-returns-nil []
  (.toBeFalsy (expect (user-kv/blob-get {} :missing)))
  (.toBeFalsy (expect (user-kv/blob-get {"other" "x"} :missing))))

(defn- test-blob-set-overwrites-existing-value []
  (let [blob (-> {}
                 (user-kv/blob-set :k "first")
                 (user-kv/blob-set :k "second"))]
    (.toBe (expect (user-kv/blob-get blob :k)) "second")))

(defn- test-blob-remove-missing-key-unchanged []
  (let [blob {"a" "1"}
        result (user-kv/blob-remove blob :missing)]
    (.toEqual (expect result) {"a" "1"})))

(defn- test-blob-remove-existing-key-gone []
  (let [blob {"a" "1" "b" "2"}
        result (user-kv/blob-remove blob :a)]
    (.toEqual (expect result) {"b" "2"})))

(defn- test-blob-clear-empties-map []
  (let [blob {"a" "1" "b" "2"}
        cleared (user-kv/blob-clear blob)]
    (.toEqual (expect cleared) {})))

(defn- test-blob-keys-sorted []
  (let [blob (-> {}
                 (user-kv/blob-set :zebra "z")
                 (user-kv/blob-set :alpha "a")
                 (user-kv/blob-set :middle "m"))]
    (.toEqual (expect (user-kv/blob-keys blob))
              ["alpha" "middle" "zebra"])))

(defn- test-edn-strings-opaque-round-trip []
  (let [edn-str "{:count 42 :name \"widget\"}"
        blob (user-kv/blob-set {} :prefs edn-str)]
    (.toBe (expect (user-kv/blob-get blob :prefs)) edn-str)))

(defn- test-blob-set-never-invents-extension-keys []
  (let [blob (user-kv/blob-set {} :user/prefs "42")]
    (.toEqual (expect (set (keys blob))) #{"user/prefs"})
    (.toBe (expect (contains? blob "scripts")) false)
    (.toBe (expect (contains? blob "settings")) false)))

(describe "user-kv/coerce-bucket-key"
          (fn []
            (test "keyword and string coerce to same bucket key"
                  test-coerce-bucket-key-keyword-and-string-match)))

(describe "user-kv/blob-get"
          (fn []
            (test "keyword and string read same bucket" test-blob-get-keyword-and-string-same-bucket)
            (test "missing key returns nil" test-blob-get-miss-returns-nil)))

(describe "user-kv/blob-set"
          (fn []
            (test "overwrites existing value" test-blob-set-overwrites-existing-value)
            (test "does not invent extension keys" test-blob-set-never-invents-extension-keys)
            (test "stores EDN strings opaquely" test-edn-strings-opaque-round-trip)))

(describe "user-kv/blob-remove"
          (fn []
            (test "remove missing key leaves map unchanged" test-blob-remove-missing-key-unchanged)
            (test "remove existing key removes it" test-blob-remove-existing-key-gone)))

(describe "user-kv/blob-clear"
          (fn []
            (test "empties the blob map" test-blob-clear-empties-map)))

(describe "user-kv/blob-keys"
          (fn []
            (test "returns sorted key vector" test-blob-keys-sorted)))
