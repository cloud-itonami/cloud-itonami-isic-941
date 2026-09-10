(ns membershipassocorg.advisor
  "MembershipAssocAdvisor -- the contained LLM/decision node. This actor's
  intelligence layer proposes back-office administrative coordination
  actions (event/meeting scheduling, dues-processing logistics, supply
  coordination, staff-shift proposals, safety/conduct concern flags)
  based on member/event/account state and operator input. The advisor
  is SEALED into the `:advise` step of the operation flow (see
  `membershipassocorg.operation`); every proposal is routed through the
  independent Governor before committing.

  The advisor makes proposals but has NO direct authority. Proposals are
  always censored by:
    1. Governor (`membershipassocorg.governor` -- member verification,
       :propose-only effect, scope exclusions)
    2. Phase gate (rollout stage, `membershipassocorg.phase`)
    3. Human operator (for escalated actions)

  Current implementation is a deterministic mock advisor for testing/
  demo. Production should use langchain/Claude or similar LLM backend
  behind the SAME `Advisor` protocol (same seam point as
  `cerealops.advisor`, cloud-itonami-isic-0111) -- this is the ONE swap
  point between mock and real intelligence; the Governor, Phase gate,
  and Store are unaffected by which Advisor is injected."
  )

;; Protocol for swappable advisor implementations -- the injection
;; boundary. A real-LLM advisor implements the SAME `-advise` method
;; signature; the Governor censors its output identically to the mock's.
(defprotocol Advisor
  (-advise [advisor store proposal]
    "Given store and a proposal map (with :operation, :effect, and any
    op-specific request keys, e.g. :member-id/:event-id/:description),
    return the proposal augmented with :advisor-reasoning and
    :confidence. Must NOT change :operation, :effect, or any field the
    Governor independently re-derives from the store -- the advisor
    reasons ABOUT the proposal, it doesn't get to alter the facts the
    Governor checks."))

;; Mock advisor for testing/demo -- the pre-existing deterministic
;; `case` logic, now wrapped behind the `Advisor` protocol instead of a
;; bare function, so a real-LLM advisor can be swapped in without
;; touching `membershipassocorg.operation`.
(defrecord MockAdvisor []
  Advisor
  (-advise [_advisor _store proposal]
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

        ;; unknown op -- the Governor's closed allowlist independently
        ;; rejects this regardless of what the advisor says.
        (assoc proposal :confidence 0.5)))))

(defn mock-advisor []
  (MockAdvisor.))

;; Back-compat function form (pre-protocol call shape), delegating to
;; the mock advisor -- kept so any external caller of the old bare
;; function keeps working.
(defn advise-proposal
  "Add advisor reasoning and confidence to a proposal via the mock
  advisor. Preserves all original fields and adds :advisor-reasoning
  and :confidence."
  [store proposal]
  (-advise (mock-advisor) store proposal))

(defn trace
  "Audit trail entry for an advisor proposal. Recorded whenever a
  proposal is generated, regardless of whether it's approved."
  [proposal]
  {:t :advisor-proposal
   :op (:operation proposal)
   :member-id (:member-id proposal)
   :advisor-reasoning (:advisor-reasoning proposal)
   :confidence (:confidence proposal)})
