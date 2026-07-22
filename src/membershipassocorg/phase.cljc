(ns membershipassocorg.phase
  "Rollout phases 0–3 controlling which operations auto-commit and which
  escalate. `gate` is the entry point `membershipassocorg.operation`'s
  compiled StateGraph calls from its `:decide` node -- it combines the
  Governor's pass/fail verdict with the phase's auto/always-escalate
  sets into a single {:disposition :reason} the graph routes on.")

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

(defn describe-phase
  "Human-readable phase description."
  [phase]
  (let [config (get phase-config phase {})]
    (str "Phase " phase ": " (:name config))))

(def default-phase 0)

(defn gate
  "Combine the Governor's `passes?` verdict with this phase's auto /
  always-escalate sets into a single graph-routing disposition:
  {:disposition :commit|:escalate|:hold :reason nil|keyword}

  - Governor rejected (`passes?` false) -> `:hold`, unconditionally
    (a HARD violation is never phase-negotiable).
  - Op is in this phase's `:always-escalate` set (e.g.
    `:flag-safety-concern`, every phase) -> `:escalate`.
  - Op is in this phase's `:auto` set -> `:commit`.
  - Otherwise (Governor-clean, but this phase hasn't turned on
    auto-commit for this op yet -- e.g. ANY op at phase 0, or
    `:coordinate-supply-request` at phase 0/1) -> `:escalate`, pending
    human approval. This is the phase-0 \"read-only\" behavior: nothing
    ever auto-commits until its op is explicitly enabled."
  [phase op-id passes?]
  (cond
    (not passes?)
    {:disposition :hold :reason nil}

    (always-escalates? phase op-id)
    {:disposition :escalate :reason :always-escalate}

    (auto-commits-at-phase? phase op-id)
    {:disposition :commit :reason nil}

    :else
    {:disposition :escalate :reason :not-yet-auto-commit-at-phase}))
