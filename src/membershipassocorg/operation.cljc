(ns membershipassocorg.operation
  "OperationActor -- one membership-association administrative
  coordination operation = one supervised actor run, expressed as a
  langgraph-clj StateGraph. The advisor (MembershipAssocAdvisor) is
  sealed into a single node (:advise); its proposal is ALWAYS routed
  through the Governor (:govern) and the rollout phase gate (:decide)
  before anything commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore | DatomicStore, see `membershipassocorg.store`)
    - the Advisor  (mock today; real LLM is the next seam --
                     `membershipassocorg.advisor/Advisor` is already the
                     injection point, see its docstring)
    - the Phase    (0->3 rollout, see `membershipassocorg.phase`)

  One graph run = one coordination operation. No unbounded inner loop --
  each operation is auditable and checkpointed. A member's coordination
  history is advanced by MANY operations (schedule-member-event /
  coordinate-dues-processing-logistics / coordinate-supply-request /
  schedule-staff-shift-proposal / flag-safety-concern), each its own
  independent graph run, and every commit/hold/approval-rejected
  decision fact lands in `membershipassocorg.store`'s append-only
  ledger (`store/append-ledger!`), so a member's full coordination
  history is always a query over an immutable log.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor at the
  `:request-approval` node until a human operator resumes it with a
  decision. `:flag-safety-concern` ALWAYS reaches this node -- see
  `membershipassocorg.phase/phase-config`'s `:always-escalate`. Mirrors
  `cerealops.operation` (cloud-itonami-isic-0111) node/edge structure
  exactly, wired to this repo's own advisor/governor/phase/store."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [membershipassocorg.advisor :as advisor]
            [membershipassocorg.governor :as governor]
            [membershipassocorg.phase :as phase]
            [membershipassocorg.store :as store]))

(defn- hold-fact
  "The audit fact written when a proposal is rejected (HOLD) by the
  Governor's HARD checks."
  [request context verdict]
  {:t :governor-hold
   :op (:operation request)
   :actor (:actor-id context)
   :subject (:member-id request)
   :disposition :hold
   :basis (mapv :check/id (:violations verdict))
   :violations (:violations verdict)})

(defn- commit-fact
  "The audit fact written when a proposal commits. `:record` carries the
  operational payload the advisor proposed (event scheduling, dues
  logistics, supply request, staff-shift proposal)."
  [request context proposal]
  {:t :committed
   :op (:operation request)
   :actor (:actor-id context)
   :subject (:member-id request)
   :disposition :commit
   :advisor-reasoning (:advisor-reasoning proposal)
   :confidence (:confidence proposal)
   :record proposal})

(defn- commit-record
  "The SSoT-facing record `store/commit-record!` appends to the
  coordination log."
  [request _context proposal]
  {:op (:operation request)
   :member-id (:member-id request)
   :effect (:effect proposal)
   :value (dissoc proposal :advisor-reasoning :confidence)})

(defn build
  "Compiles an OperationActor graph bound to `store`. opts:
    :advisor      -- a `membershipassocorg.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace p)]})))

      (g/add-node :govern
        (fn [{:keys [proposal]}]
          {:verdict (governor/govern store proposal)}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [ph (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph (:operation request) (:passes? verdict))]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(hold-fact request context verdict)]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:operation request) :subject (:member-id request)
                        :reason reason
                        :phase ph
                        :confidence (:confidence proposal)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :approved-by (:by approval))
             :audit [{:t :approval-granted :op (:operation request)
                      :subject (:member-id request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (hold-fact request context
                                       (assoc verdict :violations
                                              [{:check/id :approver-rejected
                                                :violation "Human approver rejected the proposal"}]))
                            {:t :approval-rejected})]})))

      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
