(ns membershipassocorg.phase
  "Rollout phases 0–3 controlling which operations auto-commit and which escalate.")

;; ---------------------- phase definitions ----------------------

(def phase-config
  "Phase definitions for membership organization actor rollout."
  {0 {:name "read-only"
      :auto #{}
      :always-escalate #{:flag-safety-concern}}

   1 {:name "event-scheduling + dues-logistics"
      :auto #{:schedule-member-event :coordinate-dues-processing-logistics}
      :always-escalate #{:flag-safety-concern}}

   2 {:name "+ supply + staff-shift"
      :auto #{:schedule-member-event
              :coordinate-dues-processing-logistics
              :coordinate-supply-request
              :schedule-staff-shift-proposal}
      :always-escalate #{:flag-safety-concern}}

   3 {:name "full auto-commit + escalation"
      :auto #{:schedule-member-event
              :coordinate-dues-processing-logistics
              :coordinate-supply-request
              :schedule-staff-shift-proposal}
      :always-escalate #{:flag-safety-concern}}})

(defn auto-commits-at-phase?
  "Does the operation auto-commit at this phase?"
  [phase op-id]
  (let [config (get phase-config phase {})]
    (contains? (:auto config) op-id)))

(defn always-escalates?
  "Does the operation always escalate at this phase?"
  [phase op-id]
  (let [config (get phase-config phase {})]
    (contains? (:always-escalate config) op-id)))

(defn describe-phase [phase]
  "Human-readable phase description."
  (let [config (get phase-config phase {})]
    (str "Phase " phase ": " (:name config))))
