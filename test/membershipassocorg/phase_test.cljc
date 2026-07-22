(ns membershipassocorg.phase-test
  "Preserves the original test/membershipassocorg/test.cljc `test-phases`
  assertions (converted to `deftest`/`is`), plus new tests for `gate`,
  the graph-routing entry point added when `membershipassocorg.operation`
  became a real langgraph-clj StateGraph."
  (:require [clojure.test :refer [deftest is testing]]
            [membershipassocorg.phase :as phase]))

(deftest phase-0-read-only
  (testing "no auto-commit at phase 0"
    (is (not (phase/auto-commits-at-phase? 0 :schedule-member-event)))))

(deftest phase-1-event-and-dues
  (testing "event and dues auto-commit at phase 1"
    (is (phase/auto-commits-at-phase? 1 :schedule-member-event))
    (is (phase/auto-commits-at-phase? 1 :coordinate-dues-processing-logistics))
    (is (not (phase/auto-commits-at-phase? 1 :coordinate-supply-request)))))

(deftest phase-3-full-auto-commit
  (testing "all routine ops auto-commit at phase 3, safety concerns still always escalate"
    (is (phase/auto-commits-at-phase? 3 :schedule-member-event))
    (is (phase/auto-commits-at-phase? 3 :schedule-staff-shift-proposal))
    (is (phase/always-escalates? 3 :flag-safety-concern))))

;; ---------------------- gate ----------------------

(deftest gate-hold-on-governor-reject
  (testing "a rejected verdict is always :hold, regardless of phase/op"
    (is (= :hold (:disposition (phase/gate 3 :schedule-member-event false))))))

(deftest gate-always-escalate
  (testing ":flag-safety-concern always escalates, even at phase 3"
    (let [r (phase/gate 3 :flag-safety-concern true)]
      (is (= :escalate (:disposition r)))
      (is (= :always-escalate (:reason r))))))

(deftest gate-commit-when-auto
  (testing "a Governor-clean op in this phase's :auto set commits"
    (let [r (phase/gate 3 :schedule-member-event true)]
      (is (= :commit (:disposition r))))))

(deftest gate-escalate-when-not-yet-auto
  (testing "a Governor-clean op NOT yet in this phase's :auto set escalates for human review"
    (let [r (phase/gate 0 :schedule-member-event true)]
      (is (= :escalate (:disposition r)))
      (is (= :not-yet-auto-commit-at-phase (:reason r))))
    (let [r (phase/gate 1 :coordinate-supply-request true)]
      (is (= :escalate (:disposition r))))))
