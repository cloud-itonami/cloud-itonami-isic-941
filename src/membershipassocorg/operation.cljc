(ns membershipassocorg.operation
  "Operation flow: intake → advise → govern → decide → commit | hold | escalate.
  This is a simplified demo StateGraph-equivalent for single-turn proposals."
  (:require [membershipassocorg.store :as store]
            [membershipassocorg.governor :as governor]
            [membershipassocorg.advisor :as advisor]))

;; ---------------------- state machine steps ----------------------

(defn intake-proposal [proposal]
  "Intake: validate structure."
  (if (and (:operation proposal) (:effect proposal))
    {:state :intake-ok :proposal proposal}
    {:state :intake-fail :reason "Missing :operation or :effect"}))

(defn advise-proposal [store proposal]
  "Advise: generate reasoning and confidence."
  (let [op-id (:operation proposal)]
    (case op-id
      :schedule-member-event
      (advisor/generate-member-event-proposal store (:member-id proposal) (:event-id proposal))

      :coordinate-dues-processing-logistics
      (advisor/generate-dues-logistics-proposal store (:member-id proposal))

      :coordinate-supply-request
      (advisor/generate-supply-request-proposal store)

      :schedule-staff-shift-proposal
      (advisor/generate-staff-shift-proposal store (:member-id proposal))

      :flag-safety-concern
      (advisor/generate-safety-concern-proposal store (:member-id proposal) (:concern-type proposal))

      ;; unknown op
      proposal)))

(defn govern-proposal [store proposal]
  "Govern: apply three HARD checks."
  (governor/govern store proposal))

(defn decide-proposal [govern-result]
  "Decide: translate governor decision + special handling for escalation ops."
  (let [op-id (:operation (:proposal govern-result))
        decision (:decision govern-result)]
    (if (not (:passes? govern-result))
      {:action :held
       :reason (str "Governor rejected: " (mapv :violation (:violations govern-result)))}
      (if (= op-id :flag-safety-concern)
        {:action :escalated
         :reason "Safety/conduct concerns always escalate for human review"}
        {:action :pending-approval
         :reason "Proposal passed governance, awaiting approval"}))))

(defn commit-proposal [store proposal result]
  "Commit: record the decision and any state changes."
  (store/append-ledger! store
    {:proposal proposal
     :result result
     :timestamp "2026-07-15T00:00:00Z"})
  result)

;; ---------------------- end-to-end flow ----------------------

(defn run-proposal
  "Run a single proposal through the full pipeline."
  [store proposal]
  (let [intake (intake-proposal proposal)]
    (if (= :intake-fail (:state intake))
      {:action :failed :reason (:reason intake)}
      (let [advised (advise-proposal store proposal)
            governed (govern-proposal store advised)
            decided (decide-proposal governed)]
        (commit-proposal store advised decided)
        decided))))
