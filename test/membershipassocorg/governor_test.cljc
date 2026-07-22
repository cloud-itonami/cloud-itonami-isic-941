(ns membershipassocorg.governor-test
  "Preserves the original test/membershipassocorg/test.cljc
  `test-governor` assertions (converted to `deftest`/`is`)."
  (:require [clojure.test :refer [deftest is testing]]
            [membershipassocorg.governor :as governor]
            [membershipassocorg.store :as store]))

(deftest member-unverified-check
  (testing "unverified member blocked"
    (let [s (store/make-store)
          violations (governor/member-unverified-violations s "member-3")]
      (is (= 1 (count violations)))
      (is (= :member-unverified (get-in violations [0 :check/id]))))))

(deftest member-not-found-check
  (testing "member not found in store is also blocked (never falls through)"
    (let [s (store/make-store)
          violations (governor/member-unverified-violations s "no-such-member")]
      (is (= 1 (count violations)))
      (is (= :member-unverified (get-in violations [0 :check/id]))))))

(deftest effect-not-propose-check
  (testing "non-:propose effect blocked"
    (let [proposal {:operation :schedule-member-event :effect :commit :member-id "member-1"}
          violations (governor/effect-not-propose-violations proposal)]
      (is (= 1 (count violations))))))

(deftest scope-exclusion-membership-eligibility
  (testing "membership eligibility blocked"
    (let [proposal {:operation :coordinate-dues-processing-logistics
                     :member-id "member-1"
                     :description "update membership eligibility"
                     :effect :propose}
          violations (governor/scope-exclusion-violations proposal)]
      (is (= 1 (count violations))))))

(deftest scope-exclusion-certification
  (testing "certification blocked"
    (let [proposal {:operation :schedule-staff-shift-proposal
                     :member-id "member-1"
                     :description "verify credentialing"
                     :effect :propose}
          violations (governor/scope-exclusion-violations proposal)]
      (is (= 1 (count violations))))))

(deftest scope-exclusion-dues-waiver
  (testing "dues waiver blocked"
    (let [proposal {:operation :coordinate-dues-processing-logistics
                     :member-id "member-1"
                     :description "process dues waiver request"
                     :effect :propose}
          violations (governor/scope-exclusion-violations proposal)]
      (is (= 1 (count violations))))))

(deftest scope-exclusion-disciplinary
  (testing "disciplinary action blocked"
    (let [proposal {:operation :coordinate-dues-processing-logistics
                     :member-id "member-1"
                     :description "recommend disciplinary action"
                     :effect :propose}
          violations (governor/scope-exclusion-violations proposal)]
      (is (= 1 (count violations))))))

(deftest flag-safety-concern-not-self-blocked
  (testing "flag-safety-concern not auto-blocked (legitimate use)"
    (let [proposal {:operation :flag-safety-concern
                     :member-id "member-1"
                     :concern-type "conduct-issue"
                     :effect :propose}
          violations (governor/scope-exclusion-violations proposal)]
      (is (= 0 (count violations))))))

(deftest full-governor-decision-pass
  (testing "governance passes for verified member"
    (let [s (store/make-store)
          proposal {:operation :schedule-member-event
                     :member-id "member-1"
                     :event-id "event-1"
                     :effect :propose}
          result (governor/govern s proposal)]
      (is (:passes? result))
      (is (= :APPROVE (:decision result))))))

(deftest full-governor-decision-reject
  (testing "governance rejects for unverified member"
    (let [s (store/make-store)
          proposal {:operation :schedule-member-event
                     :member-id "member-3"
                     :event-id "event-1"
                     :effect :propose}
          result (governor/govern s proposal)]
      (is (not (:passes? result)))
      (is (= :REJECT (:decision result))))))

(deftest no-member-id-skips-member-check
  (testing "a proposal with no :member-id doesn't trip the member check (e.g. facility-wide ops)"
    (let [s (store/make-store)
          proposal {:operation :coordinate-supply-request :effect :propose}
          result (governor/govern s proposal)]
      (is (:passes? result)))))
