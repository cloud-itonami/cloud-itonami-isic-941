(ns membershipassocorg.store
  "SSoT for the ISIC-941 membership association administrative coordination actor.

  This actor coordinates the back-office operations of membership organizations
  (trade associations, chambers of commerce, professional bodies): member enrollment
  logistics, event/meeting scheduling, dues-processing logistics, and facility
  management. It never touches membership-eligibility decisions, professional-
  certification/credentialing, advocacy-position content, or dues-amount/fee-waiver
  decisions -- see `membershipassocorg.governor`'s `scope-exclusion-violations`,
  a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo (no deps).
  A `members` directory keyed by `:member-id` STRING, an `events` directory keyed by
  `:event-id` STRING, and an `accounts` directory for dues-tracking.

  A registered/verified member record must exist before ANY proposal for that member
  may ever commit or escalate."
  )

(defprotocol Store
  (member [s member-id] "Registered member record, or nil.
    Member map: {:member-id .. :name .. :registered? bool :verified? bool}.")
  (all-members [s])
  (event [s event-id] "Event record, or nil.")
  (all-events [s])
  (account [s member-id] "Member dues account record, or nil.")
  (all-accounts [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-members [s members] "replace/seed the member directory")
  (with-events [s events] "replace/seed the event directory")
  (with-accounts [s accounts] "replace/seed the dues account directory"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained member, event, and account directory covering both the
  happy path and the governor's own hard checks, so the actor + tests run offline."
  []
  {:members
   {"member-1" {:member-id "member-1" :name "Alice Chen"
                :registered? true :verified? true
                :title "Director" :organization "TechCorp"}
    "member-2" {:member-id "member-2" :name "Bob Martinez"
                :registered? true :verified? true
                :title "Manager" :organization "SoftwareInc"}
    "member-3" {:member-id "member-3" :name "Charlie Wong"
                :registered? true :verified? false
                :title "Engineer" :organization "StartupXYZ"}}
   :events
   {"event-1" {:event-id "event-1" :name "Monthly Board Meeting"
               :date "2026-07-25" :location "Conference Room A" :organizer-member "member-1"}
    "event-2" {:event-id "event-2" :name "Annual Gala"
               :date "2026-08-15" :location "Grand Hotel" :organizer-member "member-1"}}
   :accounts
   {"member-1" {:member-id "member-1" :dues-balance 0.0 :status "current"
                :last-payment-date "2026-07-01"}
    "member-2" {:member-id "member-2" :dues-balance 0.0 :status "current"
                :last-payment-date "2026-06-15"}
    "member-3" {:member-id "member-3" :dues-balance 150.0 :status "overdue"
                :last-payment-date "2026-05-01"}}
   :ledger []
   :coordination-log []})

;; ----------------------------- MemStore implementation --------------------

(deftype MemStore [atom]
  Store
  (member [_ member-id]
    (get (:members @atom) member-id))
  (all-members [_]
    (vals (:members @atom)))
  (event [_ event-id]
    (get (:events @atom) event-id))
  (all-events [_]
    (vals (:events @atom)))
  (account [_ member-id]
    (get (:accounts @atom) member-id))
  (all-accounts [_]
    (vals (:accounts @atom)))
  (ledger [_]
    (:ledger @atom))
  (coordination-log [_]
    (:coordination-log @atom))
  (commit-record! [_ record]
    (swap! atom (fn [db]
                  (update db :coordination-log conj record))))
  (append-ledger! [_ fact]
    (swap! atom (fn [db]
                  (update db :ledger conj fact))))
  (with-members [_ members]
    (swap! atom assoc :members members))
  (with-events [_ events]
    (swap! atom assoc :events events))
  (with-accounts [_ accounts]
    (swap! atom assoc :accounts accounts)))

(defn make-store
  "Create a new MemStore instance with demo data."
  []
  (MemStore. (atom (demo-data))))
