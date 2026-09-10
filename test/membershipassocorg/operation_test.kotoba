(ns membershipassocorg.operation-test
  "Integration tests driving the COMPILED langgraph-clj StateGraph
  (`membershipassocorg.operation/build`) end-to-end, replacing the
  original test/membershipassocorg/test.cljc `test-operations` group
  (which drove the old hand-rolled `run-proposal` pipeline). Same
  scenarios, now exercised through the real graph: intake -> advise ->
  govern -> decide -> commit | request-approval -> commit | hold."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [membershipassocorg.operation :as operation]
            [membershipassocorg.store :as store]))

(def phase-3-ctx {:actor-id "coordinator-01" :phase 3})
(def phase-1-ctx {:actor-id "coordinator-01" :phase 1})
;; No :phase key at all -- phase/default-phase (0) applies, mirroring
;; the pre-StateGraph pipeline's "everything pending approval" behavior.
(def default-ctx {:actor-id "coordinator-01"})

(defn- exec [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest event-scheduling-happy-path-auto-commits-at-phase-3
  (testing "event scheduling for a verified member auto-commits at phase 3"
    (let [s (store/make-store)
          actor (operation/build s)
          r (exec actor "op-t1"
                  {:operation :schedule-member-event :member-id "member-1"
                   :event-id "event-1" :effect :propose}
                  phase-3-ctx)]
      (is (= :done (:status r)))
      (is (= :commit (:disposition (:state r))))
      (is (= 1 (count (store/ledger s))))
      (is (= :committed (:t (first (store/ledger s)))))
      (is (= 1 (count (store/coordination-log s)))))))

(deftest event-scheduling-default-phase-pending-approval
  (testing "at the default phase (0), event scheduling is not yet auto-commit -- interrupts for human review, same as the original pipeline's :pending-approval"
    (let [s (store/make-store)
          actor (operation/build s)
          r (exec actor "op-t2"
                  {:operation :schedule-member-event :member-id "member-1"
                   :event-id "event-1" :effect :propose}
                  default-ctx)]
      (is (= :interrupted (:status r)))
      (is (= [:request-approval] (:frontier r)))
      (is (empty? (store/ledger s)) "no commit/hold recorded until a human decides"))))

(deftest unverified-member-rejection
  (testing "unverified member is a HARD hold -- no interrupt, regardless of phase"
    (let [s (store/make-store)
          actor (operation/build s)
          r (exec actor "op-t3"
                  {:operation :schedule-member-event :member-id "member-3"
                   :event-id "event-1" :effect :propose}
                  phase-3-ctx)]
      (is (= :done (:status r)))
      (is (= :hold (:disposition (:state r))))
      (is (= 1 (count (store/ledger s))))
      (is (= :governor-hold (:t (first (store/ledger s))))))))

(deftest safety-concern-always-escalates-then-approved-commits
  (testing "flag-safety-concern ALWAYS interrupts for human review, even at phase 3; approval commits"
    (let [s (store/make-store)
          actor (operation/build s)
          r1 (exec actor "op-t4"
                   {:operation :flag-safety-concern :member-id "member-1"
                    :concern-type "member-conduct" :description "Concern for review"
                    :effect :propose}
                   phase-3-ctx)]
      (is (= :interrupted (:status r1)))
      (let [r2 (g/run* actor {:approval {:status :approved :by "director-01"}}
                        {:thread-id "op-t4" :resume? true})]
        (is (= :done (:status r2)))
        (is (= :commit (:disposition (:state r2))))
        (is (= 1 (count (store/ledger s))))
        (is (= :committed (:t (first (store/ledger s)))))))))

(deftest supply-request-escalates-at-phase-1-then-rejected-holds
  (testing "coordinate-supply-request isn't auto-commit at phase 1 -- escalates; rejection holds"
    (let [s (store/make-store)
          actor (operation/build s)
          r1 (exec actor "op-t5"
                   {:operation :coordinate-supply-request :member-id "member-1"
                    :description "Order name badges" :effect :propose}
                   phase-1-ctx)]
      (is (= :interrupted (:status r1)))
      (let [r2 (g/run* actor {:approval {:status :rejected :by "director-01"}}
                        {:thread-id "op-t5" :resume? true})]
        (is (= :done (:status r2)))
        (is (= :hold (:disposition (:state r2))))
        (is (= 1 (count (store/ledger s))))
        (is (= :approval-rejected (:t (first (store/ledger s)))))
        (is (empty? (store/coordination-log s)) "a rejected proposal never lands in the coordination log")))))

(deftest dues-logistics-happy-path-auto-commits-at-phase-3
  (testing "dues-processing logistics for a verified member commits at phase 3"
    (let [s (store/make-store)
          actor (operation/build s)
          r (exec actor "op-t6"
                  {:operation :coordinate-dues-processing-logistics
                   :member-id "member-1" :effect :propose}
                  phase-3-ctx)]
      (is (= :commit (:disposition (:state r)))))))

(deftest scope-exclusion-blocks-in-the-real-graph
  (testing "a proposal touching membership-eligibility is HARD-held by the real graph too"
    (let [s (store/make-store)
          actor (operation/build s)
          r (exec actor "op-t7"
                  {:operation :coordinate-dues-processing-logistics
                   :member-id "member-1"
                   :description "update membership eligibility"
                   :effect :propose}
                  phase-3-ctx)]
      (is (= :hold (:disposition (:state r)))))))
