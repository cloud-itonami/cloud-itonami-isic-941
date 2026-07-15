(ns membershipassocorg.governor
  "Governor with three HARD, permanent, un-overridable checks for the
  membership association administrative coordination actor.

  1. Member/event-record unverified — target must exist in store AND be
     independently :registered?/:verified?, re-derived every time.
  2. Effect not :propose — rejected outright.
  3. Scope exclusion — any proposal touching membership-eligibility/expulsion,
     professional-certification/credentialing, advocacy-position/policy-content,
     dues-amount/fee-waiver, or disciplinary action is permanently blocked."
  (:require [membershipassocorg.store :as store]
            [clojure.string :as str]))

;; ---------------------- hard checks ----------------------

(defn member-unverified-violations
  "Check 1: Member must be registered AND verified.
  This is re-derived from the member's own :registered?/:verified? fields,
  never from proposal self-report."
  [store member-id]
  (let [member (store/member store member-id)]
    (cond
      (nil? member)
      [{:check/id :member-unverified
        :violation "Member not found in store"}]

      (not (:registered? member))
      [{:check/id :member-unverified
        :violation "Member is not registered"}]

      (not (:verified? member))
      [{:check/id :member-unverified
        :violation "Member is not verified"}]

      :else
      [])))

(defn effect-not-propose-violations
  "Check 2: Effect must be :propose. Any other effect is rejected outright."
  [proposal]
  (if (not= (:effect proposal) :propose)
    [{:check/id :effect-not-propose
      :violation (str "Effect is " (:effect proposal) ", not :propose")}]
    []))

(defn scope-exclusion-violations
  "Check 3: Block proposals touching excluded territory.
  Excluded: membership-eligibility/expulsion decisions, professional-certification/
  credentialing, advocacy-position/policy-content, dues-amount/fee-waiver decisions,
  disciplinary action.

  Uses qualified substring scan (EN+JA) so legitimate :flag-safety-concern
  ops that mention 'safety' aren't self-blocked."
  [proposal]
  (let [forbidden-patterns
        [;; EN patterns
         #"(?i)membership.?eligib"
         #"(?i)membership.?expul"
         #"(?i)membership.?remov"
         #"(?i)certification"
         #"(?i)credentialing"
         #"(?i)credent"
         #"(?i)professional.?standing"
         #"(?i)advocacy"
         #"(?i)policy.?position"
         #"(?i)dues.?amount"
         #"(?i)dues.?waiv"
         #"(?i)fee.?waiv"
         #"(?i)disciplinary"
         #"(?i)censure"
         #"(?i)suspension"
         ;; JA patterns (common membership org terminology)
         #"会員.?資格"
         #"会員.?除名"
         #"会員.?削除"
         #"認定"
         #"資格.?審査"
         #"政策.?立場"
         #"会費.?減免"
         #"懲罰"
         #"処分"]

        ;; Allowed operations that legitimately mention concerns
        allowed-ops #{:flag-safety-concern}

        op-id (:operation proposal)
        proposal-str (str proposal)

        ;; Check if any forbidden pattern matches
        has-forbidden-match (some #(re-find % proposal-str) forbidden-patterns)
        is-allowed-op (allowed-ops op-id)

        ;; Combined check: return violation only if forbidden AND not allowed-op
        should-reject (boolean (and has-forbidden-match (not is-allowed-op)))]

    (if should-reject
      [{:check/id :scope-exclusion
        :violation "Proposal touches membership-eligibility, certification, advocacy-policy, dues-waiver, or disciplinary decisions"}]
      [])))

;; ---------------------- decision logic ----------------------

(defn govern
  "Apply all three HARD checks. Any violation is a permanent rejection
  with no override path."
  [store proposal]
  (let [;; Only check member verification if member-id is present
        member-violations (if (:member-id proposal)
                            (member-unverified-violations store (:member-id proposal))
                            [])
        effect-violations (effect-not-propose-violations proposal)
        scope-violations (scope-exclusion-violations proposal)
        all-violations (concat member-violations effect-violations scope-violations)]

    {:proposal proposal
     :violations all-violations
     :passes? (empty? all-violations)
     :decision (if (empty? all-violations)
                 :APPROVE
                 :REJECT)}))
