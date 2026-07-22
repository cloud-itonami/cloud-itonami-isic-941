(ns membershipassocorg.store-test
  "Preserves the original test/membershipassocorg/test.cljc `test-store`
  assertions (converted from bare `assert` to `clojure.test`'s
  `deftest`/`is` so `clojure -M:dev:test` actually runs them)."
  (:require [clojure.test :refer [deftest is testing]]
            [membershipassocorg.store :as store]))

(deftest member-lookup
  (testing "member-1 found and verified"
    (let [s (store/make-store)]
      (is (= "Alice Chen" (:name (store/member s "member-1")))))))

(deftest all-members-count
  (testing "3 members in store"
    (let [s (store/make-store)]
      (is (= 3 (count (store/all-members s)))))))

(deftest event-lookup
  (testing "event-1 found"
    (let [s (store/make-store)]
      (is (= "Monthly Board Meeting" (:name (store/event s "event-1")))))))

(deftest account-lookup
  (testing "member-1 account is current"
    (let [s (store/make-store)
          acct (store/account s "member-1")]
      (is (= "current" (:status acct))))))

(deftest ledger-append
  (testing "ledger append works"
    (let [s (store/make-store)]
      (store/append-ledger! s {:event "test-fact"})
      (is (= 1 (count (store/ledger s)))))))

(deftest coordination-log-commit
  (testing "commit-record! appends to the coordination log"
    (let [s (store/make-store)]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s {:op :schedule-member-event :member-id "member-1"})
      (is (= 1 (count (store/coordination-log s)))))))
