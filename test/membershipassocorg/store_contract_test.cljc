(ns membershipassocorg.store-contract-test
  "MemStore ≡ DatomicStore parity for the Store protocol. Mirrors
  `cerealops.store-contract-test` (cloud-itonami-isic-0111)."
  (:require [clojure.test :refer [deftest is]]
            [membershipassocorg.store :as store]))

(deftest mem-and-datomic-seed-parity
  (let [mem (store/make-store)
        dat (store/make-datomic-store)]
    (is (= (store/member mem "member-1") (store/member dat "member-1")))
    ;; `all-members`/`all-accounts` order is unspecified by the Store
    ;; protocol on EITHER backend (MemStore: `vals` on a plain map;
    ;; DatomicStore: a set-backed datalog query) -- compare as sets.
    (is (= (set (store/all-members mem)) (set (store/all-members dat))))
    (is (= (store/event mem "event-1") (store/event dat "event-1")))
    (is (= (set (store/all-events mem)) (set (store/all-events dat))))
    (is (= (store/account mem "member-1") (store/account dat "member-1")))
    (is (= (set (store/all-accounts mem)) (set (store/all-accounts dat))))
    (is (nil? (store/member dat "no-such-member")))))

(deftest mem-and-datomic-mutation-parity
  (let [mem (store/make-store)
        dat (store/make-datomic-store)]
    (doseq [s [mem dat]]
      (store/append-ledger! s {:t :committed :op :schedule-member-event :subject "member-1"})
      (store/append-ledger! s {:t :approval-requested :op :flag-safety-concern :subject "member-1"})
      (store/commit-record! s {:op :schedule-member-event :member-id "member-1"}))
    (is (= 2 (count (store/ledger mem))))
    (is (= 2 (count (store/ledger dat))))
    (is (= (store/ledger mem) (store/ledger dat)))
    (is (= 1 (count (store/coordination-log mem))))
    (is (= 1 (count (store/coordination-log dat))))
    (is (= (store/coordination-log mem) (store/coordination-log dat)))))

(deftest datomic-with-members-upserts
  (let [dat (store/make-datomic-store)
        updated {:member-id "member-1" :name "Alice Chen-Rodriguez"
                  :registered? true :verified? true}]
    (store/with-members dat {"member-1" updated})
    (is (= updated (store/member dat "member-1")))))
