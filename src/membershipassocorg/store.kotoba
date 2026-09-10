(ns membershipassocorg.store
  "SSoT for the ISIC-941 membership association administrative coordination actor.

  This actor coordinates the back-office operations of membership organizations
  (trade associations, chambers of commerce, professional bodies): member enrollment
  logistics, event/meeting scheduling, dues-processing logistics, and facility
  management. It never touches membership-eligibility decisions, professional-
  certification/credentialing, advocacy-position content, or dues-amount/fee-waiver
  decisions -- see `membershipassocorg.governor`'s `scope-exclusion-violations`,
  a HARD, permanent, un-overridable block.

  Two backends implement the same `Store` protocol, the seam every prior
  cloud-itonami actor in this fleet uses (mirrors `cerealops.store`,
  cloud-itonami-isic-0111):

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps). A `members` directory
                        keyed by `:member-id` STRING, an `events`
                        directory keyed by `:event-id` STRING, and an
                        `accounts` directory for dues-tracking.
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store. Pure `.cljc`, offline by default and
                        swappable to a real Datomic Local / kotoba-server
                        pod via `langchain.db`'s `:db-api`. Member/event/
                        account records are opaque, caller-defined maps
                        (unlike the fixed-field shape of e.g.
                        `marketentry.engagement`), so each is stored as a
                        single EDN-blob attribute (`langchain-store.core`'s
                        `enc`/`dec*`) rather than expanded into per-key
                        Datomic attributes -- the same convention every
                        sibling DatomicStore already uses for its own
                        opaque payloads.

  Both pass the same contract (test/membershipassocorg/store_contract_test.cljc).

  A registered/verified member record must exist before ANY proposal for that member
  may ever commit or escalate."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

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

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Member/event/account payloads are EDN-string blobs (via
  `langchain-store.core`) so `langchain.db` doesn't try to expand an
  opaque, caller-defined record into sub-entities. `:ledger/seq` and
  `:coord/seq` back the append-only ledger / coordination-log as
  seq-keyed event streams (`langchain-store.core/read-stream` +
  `append-blob!`). The identity-schema builder, EDN-blob codec, and
  event-log helpers are the shared kotoba-lang/langchain-store
  machinery (ADR-2607141600) -- the seam ~190 actors hand-roll; this
  store keeps only its domain wiring."
  (ls/identity-schema [:member/id :event/id :account/id :ledger/seq :coord/seq]))

(defn- put! [conn id-attr payload-attr id payload]
  (d/transact! conn [{id-attr id payload-attr (ls/enc payload)}])
  payload)

(defn- get-one [conn id-attr payload-attr id]
  (when id
    (ls/dec* (d/q [:find '?p '.
                    :in '$ '?id
                    :where ['?e id-attr '?id] ['?e payload-attr '?p]]
                  (d/db conn) id))))

(defn- get-all [conn payload-attr]
  (->> (d/q [:find ['?p '...]
             :where ['?e payload-attr '?p]]
            (d/db conn))
       (mapv ls/dec*)))

(defrecord DatomicStore [conn]
  Store
  (member [_store member-id]
    (get-one conn :member/id :member/payload member-id))
  (all-members [_store]
    (get-all conn :member/payload))
  (event [_store event-id]
    (get-one conn :event/id :event/payload event-id))
  (all-events [_store]
    (get-all conn :event/payload))
  (account [_store member-id]
    (get-one conn :account/id :account/payload member-id))
  (all-accounts [_store]
    (get-all conn :account/payload))
  (ledger [_store]
    (ls/read-stream conn :ledger/seq :ledger/fact))
  (coordination-log [_store]
    (ls/read-stream conn :coord/seq :coord/fact))
  (commit-record! [store record]
    (ls/append-blob! conn :coord/seq :coord/fact (count (coordination-log store)) record))
  (append-ledger! [store fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger store)) fact))
  (with-members [_store members]
    ;; Upsert every member in `members` (identity-schema on :member/id
    ;; means a re-put updates in place, mirroring MemStore's plain
    ;; `assoc`). NOTE: unlike MemStore's `assoc`, this does not retract
    ;; members present in the OLD directory but absent from the new
    ;; one -- a documented limitation, not a silent divergence: this
    ;; actor only ever calls `with-members`/`with-events`/`with-accounts`
    ;; once, at store-seeding time, so full-replace semantics are never
    ;; exercised in practice.
    (doseq [[member-id member-data] members]
      (put! conn :member/id :member/payload member-id member-data)))
  (with-events [_store events]
    (doseq [[event-id event-data] events]
      (put! conn :event/id :event/payload event-id event-data)))
  (with-accounts [_store accounts]
    (doseq [[member-id account-data] accounts]
      (put! conn :account/id :account/payload member-id account-data))))

(defn make-datomic-store
  "Create a DatomicStore (langchain.db backend) seeded with the same
  demo data as `make-store`."
  []
  (let [store (->DatomicStore (d/create-conn schema))
        {:keys [members events accounts]} (demo-data)]
    (with-members store members)
    (with-events store events)
    (with-accounts store accounts)
    store))
