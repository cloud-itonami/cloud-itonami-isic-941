(ns membershipassocorg.advisor
  "LLM advisor layer (deterministic demo for now) that adds reasoning to
  proposals for membership organization administrative coordination tasks."
  (:require [membershipassocorg.store :as store]))

(defn advise-proposal
  "Add advisor reasoning and confidence to a proposal.
  Preserves all original fields and adds :advisor-reasoning and :confidence."
  [store proposal]
  (let [op-id (:operation proposal)]
    (case op-id
      :schedule-member-event
      (assoc proposal
        :advisor-reasoning "Member has registered status; event scheduling is administrative coordination"
        :confidence 0.85)

      :coordinate-dues-processing-logistics
      (assoc proposal
        :advisor-reasoning "Member account tracking is administrative logistics"
        :confidence 0.8)

      :coordinate-supply-request
      (assoc proposal
        :advisor-reasoning "Non-content consumables are within administrative scope"
        :confidence 0.75)

      :schedule-staff-shift-proposal
      (assoc proposal
        :advisor-reasoning "Staff scheduling is administrative coordination (proposal only, never binding)"
        :confidence 0.7)

      :flag-safety-concern
      (assoc proposal
        :advisor-reasoning "Safety/conduct concerns are escalated for human review"
        :confidence 0.9)

      ;; unknown op
      (assoc proposal :confidence 0.5))))
