(ns membershipassocorg.sim
  "Demo simulation: 5 scenarios covering happy path, hard checks, and escalation."
  (:require [membershipassocorg.store :as store]
            [membershipassocorg.operation :as operation]))

;; ---------------------- demo scenarios ----------------------

(defn scenario-1-happy-path []
  "Scenario 1: Happy path — schedule member event for verified member."
  (println "\n[Scenario 1] Happy Path: Schedule Member Event")
  (let [s (store/make-store)
        result (operation/run-proposal s
                 {:operation :schedule-member-event
                  :member-id "member-1"
                  :event-id "event-1"
                  :effect :propose})]
    (println (str "  Result: " (:action result)))
    (assert (= :pending-approval (:action result)))
    (println "  ✓ PASS")))

(defn scenario-2-unverified-member []
  "Scenario 2: Hard check — unverified member blocked."
  (println "\n[Scenario 2] Hard Check: Unverified Member Blocked")
  (let [s (store/make-store)
        result (operation/run-proposal s
                 {:operation :schedule-member-event
                  :member-id "member-3"
                  :event-id "event-1"
                  :effect :propose})]
    (println (str "  Result: " (:action result)))
    (assert (= :held (:action result)))
    (println "  ✓ PASS")))

(defn scenario-3-membership-eligibility-blocked []
  "Scenario 3: Scope exclusion — membership-eligibility decision blocked."
  (println "\n[Scenario 3] Scope Exclusion: Membership-Eligibility Blocked")
  (let [s (store/make-store)
        result (operation/run-proposal s
                 {:operation :coordinate-dues-processing-logistics
                  :member-id "member-1"
                  :description "update membership eligibility"
                  :effect :propose})]
    (println (str "  Result: " (:action result)))
    (assert (= :held (:action result)))
    (println "  ✓ PASS")))

(defn scenario-4-certification-blocked []
  "Scenario 4: Scope exclusion — professional certification decision blocked."
  (println "\n[Scenario 4] Scope Exclusion: Certification Blocked")
  (let [s (store/make-store)
        result (operation/run-proposal s
                 {:operation :schedule-staff-shift-proposal
                  :member-id "member-1"
                  :description "verify professional credentialing"
                  :effect :propose})]
    (println (str "  Result: " (:action result)))
    (assert (= :held (:action result)))
    (println "  ✓ PASS")))

(defn scenario-5-safety-escalation []
  "Scenario 5: Escalation — safety/conduct concern always escalates."
  (println "\n[Scenario 5] Escalation: Safety Concern Escalates")
  (let [s (store/make-store)
        result (operation/run-proposal s
                 {:operation :flag-safety-concern
                  :member-id "member-1"
                  :concern-type "member-conduct-issue"
                  :description "Member conduct concern for review"
                  :effect :propose})]
    (println (str "  Result: " (:action result)))
    (assert (= :escalated (:action result)))
    (println "  ✓ PASS")))

;; ---------------------- master sim runner ----------------------

(defn run-all-scenarios []
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║ ISIC-941 Membership Association Coordination Actor         ║")
  (println "║ Simulation: 5 Scenarios                                    ║")
  (println "╚════════════════════════════════════════════════════════════╝")

  (scenario-1-happy-path)
  (scenario-2-unverified-member)
  (scenario-3-membership-eligibility-blocked)
  (scenario-4-certification-blocked)
  (scenario-5-safety-escalation)

  (println "\n╔════════════════════════════════════════════════════════════╗")
  (println "║ All scenarios passed!                                      ║")
  (println "╚════════════════════════════════════════════════════════════╝\n")
  0)

#?(:clj
   (defn -main [& args]
     (run-all-scenarios)))
