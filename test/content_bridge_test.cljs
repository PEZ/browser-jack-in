(ns content-bridge-test
  (:require ["vitest" :refer [describe test expect]]
            [content-bridge :refer [message-registry]]))

(defn- test-all-entries-have-required-keys []
  (doseq [[_ entry] message-registry]
    (-> (expect (:msg/sources entry))
        (.toBeTruthy))
    (-> (expect (:msg/response? entry))
        (.not.toBeUndefined))))

(defn- test-all-sources-are-valid-source-strings []
  (let [valid-sources #{"epupp-page" "epupp-userscript"}]
    (doseq [[_ entry] message-registry]
      (doseq [source (:msg/sources entry)]
        (-> (expect (contains? valid-sources source))
            (.toBe true))))))



(defn- test-response-type-overrides-are-present-where-expected []
  (-> (expect (:msg/response-type (get message-registry "load-manifest")))
      (.toBe "manifest-response")))

(describe "message-registry integrity"
          (fn []
            (test "all entries have required keys"
                  test-all-entries-have-required-keys)
            (test "all sources are valid source strings"
                  test-all-sources-are-valid-source-strings)
            (test "response type overrides are present where expected"
                  test-response-type-overrides-are-present-where-expected)))

(defn- test-unregistered-types-return-nil []
  (-> (expect (get message-registry "evil-message"))
      (.toBeUndefined))
  (-> (expect (get message-registry "pattern-approved"))
      (.toBeUndefined))
  (-> (expect (get message-registry "evaluate-script"))
      (.toBeUndefined)))

(defn- test-source-filtering-restricts-access []
  ;; list-scripts should only be accessible from epupp-page
  (let [list-entry (get message-registry "list-scripts")]
    (-> (expect (contains? (:msg/sources list-entry) "epupp-page"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources list-entry) "epupp-userscript"))
        (.toBe false)))
  ;; sponsor-status should only be accessible from epupp-userscript
  (let [sponsor-entry (get message-registry "sponsor-status")]
    (-> (expect (contains? (:msg/sources sponsor-entry) "epupp-userscript"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources sponsor-entry) "epupp-page"))
        (.toBe false)))
  ;; save-script should only be accessible from epupp-page (WS-gated FS sync)
  (let [save-entry (get message-registry "save-script")]
    (-> (expect (contains? (:msg/sources save-entry) "epupp-page"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources save-entry) "epupp-userscript"))
        (.toBe false))))

(describe "message-registry access control"
          (fn []
            (test "unregistered message types return nil"
                  test-unregistered-types-return-nil)
            (test "source filtering restricts access correctly"
                  test-source-filtering-restricts-access)))

;; ============================================================
;; Web Installer Message Registry Tests
;; ============================================================

(defn- test-check-script-exists-registry-entry []
  (let [entry (get message-registry "check-script-exists")]
    ;; Must be registered
    (-> (expect entry) (.toBeTruthy))
    ;; Only accessible from epupp-page
    (-> (expect (contains? (:msg/sources entry) "epupp-page"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources entry) "epupp-userscript"))
        (.toBe false))
    ;; Response-bearing message
    (-> (expect (:msg/response? entry))
        (.toBe true))))

(defn- test-web-installer-save-script-registry-entry []
  (let [entry (get message-registry "web-installer-save-script")]
    ;; Must be registered
    (-> (expect entry) (.toBeTruthy))
    ;; Only accessible from epupp-page
    (-> (expect (contains? (:msg/sources entry) "epupp-page"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources entry) "epupp-userscript"))
        (.toBe false))
    ;; Response-bearing message
    (-> (expect (:msg/response? entry))
        (.toBe true))))

(describe "web installer message registry"
          (fn []
            (test "check-script-exists has correct registry entry"
                  test-check-script-exists-registry-entry)
            (test "web-installer-save-script has correct registry entry"
                  test-web-installer-save-script-registry-entry)))

;; ============================================================
;; Screenshot Capture Message Registry Tests
;; ============================================================

(defn- test-capture-element-registry-entry []
  (let [entry (get message-registry "capture-element")]
    ;; Must be registered
    (-> (expect entry) (.toBeTruthy))
    ;; Only accessible from epupp-page
    (-> (expect (contains? (:msg/sources entry) "epupp-page"))
        (.toBe true))
    (-> (expect (contains? (:msg/sources entry) "epupp-userscript"))
        (.toBe false))
    ;; Response-bearing message
    (-> (expect (:msg/response? entry))
        (.toBe true))))

(describe "screenshot capture message registry"
          (fn []
            (test "capture-element has correct registry entry"
                  test-capture-element-registry-entry)))

;; ============================================================
;; User storage message registry tests
;; ============================================================

(defn- storage-registry-entry? [msg-type]
  (let [entry (get message-registry msg-type)]
    (and entry
         (contains? (:msg/sources entry) "epupp-page")
         (not (contains? (:msg/sources entry) "epupp-userscript"))
         (= true (:msg/response? entry)))))

(defn- test-storage-get-registry-entry []
  (-> (expect (storage-registry-entry? "storage-get")) (.toBe true)))

(defn- test-storage-set-registry-entry []
  (-> (expect (storage-registry-entry? "storage-set")) (.toBe true)))

(defn- test-storage-remove-registry-entry []
  (-> (expect (storage-registry-entry? "storage-remove")) (.toBe true)))

(defn- test-storage-keys-registry-entry []
  (-> (expect (storage-registry-entry? "storage-keys")) (.toBe true)))

(defn- test-storage-clear-registry-entry []
  (-> (expect (storage-registry-entry? "storage-clear")) (.toBe true)))

(describe "user storage message registry"
          (fn []
            (test "storage-get has correct registry entry" test-storage-get-registry-entry)
            (test "storage-set has correct registry entry" test-storage-set-registry-entry)
            (test "storage-remove has correct registry entry" test-storage-remove-registry-entry)
            (test "storage-keys has correct registry entry" test-storage-keys-registry-entry)
            (test "storage-clear has correct registry entry" test-storage-clear-registry-entry)))
