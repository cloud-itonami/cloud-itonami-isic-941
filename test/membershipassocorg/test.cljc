(ns membershipassocorg.test
  "Test suite for the membership association coordination actor.

  Tests cover the three HARD governor checks and the happy path proposal flow."
  (:require [membershipassocorg.store :as store]
            [membershipassocorg.governor :as governor]
            [membershipassocorg.operation :as operation]
            [membershipassocorg.phase :as phase]))

;; ---------------------- test utilities ----------------------

(defn run-test-group [name f]
  (println (str "\n" name))
  (f))

;; ---------------------- store tests ----------------------

(defn test-store []
  (run-test-group "=== Store Tests ===" (fn []
    (let [s (store/make-store)]
      (println "[1] Member lookup")
      (assert (= "Alice Chen" (:name (store/member s "member-1"))))
      (println "    ✓ member-1 found and verified")

      (println "[2] All members")
      (let [all (store/all-members s)]
        (assert (= 3 (count all)))
        (println "    ✓ 3 members in store"))

      (println "[3] Event lookup")
      (assert (= "Monthly Board Meeting" (:name (store/event s "event-1"))))
      (println "    ✓ event-1 found")

      (println "[4] Account lookup")
      (let [acct (store/account s "member-1")]
        (assert (= "current" (:status acct)))
        (println "    ✓ member-1 account is current"))

      (println "[5] Ledger append")
      (store/append-ledger! s {:event "test-fact"})
      (assert (= 1 (count (store/ledger s))))
      (println "    ✓ ledger append works")))))

;; ---------------------- governor tests ----------------------

(defn test-governor []
  (run-test-group "=== Governor Tests ===" (fn []
    (let [s (store/make-store)]
      (println "[1] Member unverified check")
      (let [violations (governor/member-unverified-violations s "member-3")]
        (assert (= 1 (count violations)))
        (assert (= :member-unverified (get-in violations [0 :check/id])))
        (println "    ✓ unverified member blocked"))

      (println "[2] Effect not :propose check")
      (let [proposal {:operation :schedule-member-event :effect :commit :member-id "member-1"}
            violations (governor/effect-not-propose-violations proposal)]
        (assert (= 1 (count violations)))
        (println "    ✓ non-:propose effect blocked"))

      (println "[3] Scope exclusion (membership eligibility)")
      (let [proposal {:operation :coordinate-dues-processing-logistics
                      :member-id "member-1"
                      :description "update membership eligibility"
                      :effect :propose}
            violations (governor/scope-exclusion-violations proposal)]
        (assert (= 1 (count violations)))
        (println "    ✓ membership eligibility blocked"))

      (println "[4] Scope exclusion (certification)")
      (let [proposal {:operation :schedule-staff-shift-proposal
                      :member-id "member-1"
                      :description "verify credentialing"
                      :effect :propose}
            violations (governor/scope-exclusion-violations proposal)]
        (assert (= 1 (count violations)))
        (println "    ✓ certification blocked"))

      (println "[5] Scope exclusion (dues waiver)")
      (let [proposal {:operation :coordinate-dues-processing-logistics
                      :member-id "member-1"
                      :description "process dues waiver request"
                      :effect :propose}
            violations (governor/scope-exclusion-violations proposal)]
        (assert (= 1 (count violations)))
        (println "    ✓ dues waiver blocked"))

      (println "[6] Flag-safety-concern allowed (legitimate use)")
      (let [proposal {:operation :flag-safety-concern
                      :member-id "member-1"
                      :concern-type "conduct-issue"
                      :effect :propose}
            violations (governor/scope-exclusion-violations proposal)]
        (assert (= 0 (count violations)))
        (println "    ✓ flag-safety-concern not auto-blocked"))

      (println "[7] Full governor decision (pass)")
      (let [proposal {:operation :schedule-member-event
                      :member-id "member-1"
                      :event-id "event-1"
                      :effect :propose}
            result (governor/govern s proposal)]
        (assert (:passes? result))
        (assert (= :APPROVE (:decision result)))
        (println "    ✓ governance passes for verified member"))))))

;; ---------------------- operation tests ----------------------

(defn test-operations []
  (run-test-group "=== Operation Tests ===" (fn []
    (let [s (store/make-store)]
      (println "[1] Event scheduling (happy path)")
      (let [result (operation/run-proposal s
                     {:operation :schedule-member-event
                      :member-id "member-1"
                      :event-id "event-1"
                      :effect :propose})]
        (assert (= :pending-approval (:action result)))
        (println "    ✓ event scheduling completes"))

      (println "[2] Unverified member rejection")
      (let [result (operation/run-proposal s
                     {:operation :schedule-member-event
                      :member-id "member-3"
                      :event-id "event-1"
                      :effect :propose})]
        (assert (= :held (:action result)))
        (println "    ✓ unverified member held"))

      (println "[3] Safety concern escalation")
      (let [result (operation/run-proposal s
                     {:operation :flag-safety-concern
                      :member-id "member-1"
                      :concern-type "member-conduct"
                      :description "Concern for review"
                      :effect :propose})]
        (assert (= :escalated (:action result)))
        (println "    ✓ safety concern escalates"))

      (println "[4] Dues logistics (happy path)")
      (let [result (operation/run-proposal s
                     {:operation :coordinate-dues-processing-logistics
                      :member-id "member-1"
                      :effect :propose})]
        (assert (= :pending-approval (:action result)))
        (println "    ✓ dues logistics completes"))

      (println "[5] Supply request (happy path)")
      (let [result (operation/run-proposal s
                     {:operation :coordinate-supply-request
                      :effect :propose})]
        (assert (= :pending-approval (:action result)))
        (println "    ✓ supply request completes"))))))

;; ---------------------- phase tests ----------------------

(defn test-phases []
  (run-test-group "=== Phase Tests ===" (fn []
    (println "[1] Phase 0 (read-only)")
    (assert (not (phase/auto-commits-at-phase? 0 :schedule-member-event)))
    (println "    ✓ no auto-commit at phase 0")

    (println "[2] Phase 1 (event + dues)")
    (assert (phase/auto-commits-at-phase? 1 :schedule-member-event))
    (assert (phase/auto-commits-at-phase? 1 :coordinate-dues-processing-logistics))
    (assert (not (phase/auto-commits-at-phase? 1 :coordinate-supply-request)))
    (println "    ✓ event and dues auto-commit at phase 1")

    (println "[3] Phase 3 (full auto-commit)")
    (assert (phase/auto-commits-at-phase? 3 :schedule-member-event))
    (assert (phase/auto-commits-at-phase? 3 :schedule-staff-shift-proposal))
    (assert (phase/always-escalates? 3 :flag-safety-concern))
    (println "    ✓ all ops auto-commit at phase 3"))))

;; ---------------------- master test runner ----------------------

(defn run-all-tests []
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║ ISIC-941 Membership Association Coordination Actor Tests   ║")
  (println "╚════════════════════════════════════════════════════════════╝")

  (test-store)
  (test-governor)
  (test-operations)
  (test-phases)

  (println "\n╔════════════════════════════════════════════════════════════╗")
  (println "║ All tests passed!                                          ║")
  (println "╚════════════════════════════════════════════════════════════╝\n")
  0)

#?(:clj
   (defn -main [& args]
     (run-all-tests)))
