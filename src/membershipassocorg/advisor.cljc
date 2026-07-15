(ns membershipassocorg.advisor
  "LLM advisor layer (deterministic demo for now) that generates proposals
  for membership organization administrative coordination tasks."
  (:require [membershipassocorg.store :as store]))

(defn generate-member-event-proposal
  "Generate a proposal to schedule a member event."
  [store member-id event-id]
  (let [member (store/member store member-id)
        event (store/event store event-id)]
    {:operation :schedule-member-event
     :member-id member-id
     :event-id event-id
     :reasoning "Member has registered status; event scheduling is administrative coordination"
     :confidence 0.85
     :effect :propose}))

(defn generate-dues-logistics-proposal
  "Generate a proposal to coordinate dues-processing logistics."
  [store member-id]
  (let [account (store/account store member-id)]
    {:operation :coordinate-dues-processing-logistics
     :member-id member-id
     :reasoning "Member account tracking is administrative logistics"
     :confidence 0.8
     :effect :propose}))

(defn generate-supply-request-proposal
  "Generate a proposal for non-content supply requests."
  [store]
  {:operation :coordinate-supply-request
   :reasoning "Non-content consumables are within administrative scope"
   :confidence 0.75
   :effect :propose})

(defn generate-staff-shift-proposal
  "Generate a proposal for staff shift scheduling (PROPOSAL only, never binding)."
  [store member-id]
  {:operation :schedule-staff-shift-proposal
   :member-id member-id
   :reasoning "Staff scheduling is administrative coordination (proposal only, never binding)"
   :confidence 0.7
   :effect :propose})

(defn generate-safety-concern-proposal
  "Generate a proposal to flag safety or conduct concerns for HUMAN review."
  [store member-id concern-type]
  {:operation :flag-safety-concern
   :member-id member-id
   :concern-type concern-type
   :reasoning "Safety/conduct concerns are escalated for human review"
   :confidence 0.9
   :effect :propose})
