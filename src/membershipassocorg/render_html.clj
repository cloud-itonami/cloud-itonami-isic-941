(ns membershipassocorg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed the repo shipped no operator-console sample and no generator
  at all.

  EVERY id, number, disposition, verdict, violation and approver name on
  the generated page is produced by ACTUALLY EXECUTING this repo's real
  actor stack at build time --

    `membershipassocorg.operation` (the compiled langgraph-clj StateGraph)
      -> `membershipassocorg.advisor`  (contained proposal node)
      -> `membershipassocorg.governor` (independent censor, 3 HARD checks)
      -> `membershipassocorg.phase`    (staged-rollout gate)
      -> `membershipassocorg.store`    (SSoT + append-only audit ledger)

  driven through `langgraph.graph/run*` exactly as
  `membershipassocorg.sim` (`clojure -M:dev:run`) drives it, including
  the real `interrupt-before #{:request-approval}` pause and the real
  human resume. Nothing on the page is hand-typed domain data: the
  member / event / dues-account rows come from
  `membershipassocorg.store`'s own seeded directory, and every
  disposition and violation id comes from the governor verdict carried
  on that run's own `:audit` channel.

  The ONE deliberately hand-written table is `governor-contract-rows`:
  that is prose documentation of this actor's FIXED op contract
  (`membershipassocorg.governor`'s three checks and
  `membershipassocorg.phase`'s `:auto` / `:always-escalate` sets), i.e.
  behaviour that does not vary per run. It is labelled as such on the
  page, and is not dressed up as telemetry.

  ---------------------------------------------------------------
  CLASSIFICATION -- read this before changing anything below.
  ---------------------------------------------------------------
  This repo can end a run in `:hold` for TWO structurally different
  reasons, and they must never be conflated:

    (a) a HARD GOVERNOR HOLD -- `membershipassocorg.governor` refused
        the proposal. Ledger fact `:t :governor-hold`. Never reaches a
        human; no phase and no approver can override it.

    (b) a HUMAN APPROVAL REJECTION -- the phase gate escalated a
        governor-CLEAN proposal, a human operator looked at it and said
        no. Ledger fact `:t :approval-rejected`.

  Case (b) ALSO carries a `:violations` vector -- the graph synthesises
  a `{:check/id :approver-rejected}` entry for it (see
  `membershipassocorg.operation`'s `:request-approval` node). So
  classifying on `:violations` being non-empty COUNTS A HUMAN'S 'no' AS
  A GOVERNOR REFUSAL. Everything here therefore classifies on the FACT
  TYPE `:t` first (`hard-hold-fact?`), and `-main` asserts at build time
  that a present `:approval-rejected` fact is NOT counted as a hard
  hold (`assert-classifier-discriminates!`).

  Separately, an escalation is NOT a hold at all: `:always-escalate`
  (permanent policy, e.g. `:flag-safety-concern`) and
  `:not-yet-auto-commit-at-phase` (rollout gate) are both reported in
  their own section, with the phase gate's own `:reason` keyword.

  DETERMINISTIC: no timestamp, no wall clock, no randomness, no UUID and
  no hash-order dependence enters the page (store directories are sorted
  by id before rendering), so two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [membershipassocorg.governor :as governor]
            [membershipassocorg.operation :as operation]
            [membershipassocorg.phase :as phase]
            [membershipassocorg.store :as store]))

(def ^:private out-default "docs/samples/operator-console.html")

;; The same association-staff coordinator contexts `membershipassocorg.sim`
;; runs under. Phase is part of the CONTEXT, so the same op can be shown
;; auto-committing at phase 3 and escalating at phase 1 / phase 0.
(def ^:private ctx-phase-3 {:actor-id "coordinator-01" :role :association-staff :phase 3})
(def ^:private ctx-phase-1 {:actor-id "coordinator-01" :role :association-staff :phase 1})
(def ^:private ctx-phase-0 {:actor-id "coordinator-01" :role :association-staff :phase 0})

(def ^:private ops-director "ops-director-01")

;; ----------------------------- driving the real actor -----------------------------

(defn- audit-facts [r] (vec (get-in r [:state :audit])))

(defn- capture
  "Projects ONE finished (or interrupted) graph run into the row shape
  the page needs. Every field is read straight out of the run's own
  final state -- the governor's verdict, the phase gate's disposition,
  the audit facts the nodes themselves wrote, and the exact slices of
  the store's append-only ledger / coordination log that THIS run
  appended (captured by index, so no run is ever credited with another
  run's fact)."
  [label st ledger-before coord-before r]
  (let [audit (audit-facts r)
        kinds (set (map :t audit))
        escalation (last (filter #(= :approval-requested (:t %)) audit))]
    {:label        label
     :status       (:status r)
     :op           (get-in r [:state :request :operation])
     :subject      (get-in r [:state :request :member-id])
     :phase        (get-in r [:state :context :phase])
     :effect       (get-in r [:state :request :effect])
     :description  (or (get-in r [:state :request :description])
                       (get-in r [:state :request :event-id]))
     :confidence   (get-in r [:state :proposal :confidence])
     :reasoning    (get-in r [:state :proposal :advisor-reasoning])
     :passes?      (get-in r [:state :verdict :passes?])
     :decision     (get-in r [:state :verdict :decision])
     :violations   (mapv :check/id (get-in r [:state :verdict :violations]))
     :details      (mapv :violation (get-in r [:state :verdict :violations]))
     :disposition  (get-in r [:state :disposition])
     :escalated?   (contains? kinds :approval-requested)
     :escalate-reason (:reason escalation)
     ;; the human decision this run actually resumed with, if any
     :approval     (get-in r [:state :approval])
     :audit-kinds  (mapv :t audit)
     ;; exactly what THIS run appended to each store plane
     :ledger-slice (subvec (vec (store/ledger st)) ledger-before)
     :coord-slice  (subvec (vec (store/coordination-log st)) coord-before)}))

(defn- exec!
  "One coordination request through the real compiled StateGraph."
  [st actor tid label request ctx]
  (let [lb (count (store/ledger st))
        cb (count (store/coordination-log st))]
    (capture label st lb cb
             (g/run* actor {:request request :context ctx} {:thread-id tid}))))

(defn- exec-resume!
  "One request that the phase gate escalates, then the REAL human
  resume through `interrupt-before #{:request-approval}`. The captured
  row is the FINAL state, so it carries the whole
  escalate -> human -> commit|hold path the actor actually walked."
  [st actor tid label request ctx status by]
  (let [lb (count (store/ledger st))
        cb (count (store/coordination-log st))]
    (g/run* actor {:request request :context ctx} {:thread-id tid})
    (capture label st lb cb
             (g/run* actor {:approval {:status status :by by}}
                     {:thread-id tid :resume? true}))))

(defn run-demo!
  "Seeds a fresh store, builds the REAL OperationActor and runs a
  scenario reaching every disposition this actor can produce.

  Auto-commit (governor-clean AND the op is in this phase's `:auto` set):
    1. `:schedule-member-event` member-1 / event-1 at phase 3
    2. `:coordinate-dues-processing-logistics` member-2 at phase 3
    3. `:coordinate-supply-request` member-2 at phase 3
    4. `:schedule-staff-shift-proposal` member-1 at phase 3

  Human-in-the-loop (the graph really pauses at `:request-approval`):
    5. `:flag-safety-concern` member-1 -- `:always-escalate` at EVERY
       phase; the ops director approves and it commits.
    6. `:coordinate-supply-request` member-1 at phase 1 -- governor-clean
       but not yet in phase 1's `:auto` set, so the ROLLOUT GATE
       escalates it; the ops director rejects and it holds. This is NOT
       a governor refusal (see the ns docstring).
    7. `:schedule-member-event` member-2 / event-2 at phase 0 -- phase 0
       auto-commits nothing, so this run is left genuinely PAUSED,
       showing the interrupt is a real pause and not a formality.

  HARD governor holds -- one per check in `membershipassocorg.governor`,
  each refused unconditionally, none of which ever reaches a human:
    8. `:member-unverified`   -- member-3 is registered but NOT verified
       in the store; the governor re-derives that from the member record
       itself, never from the advisor's say-so.
    9. `:effect-not-propose`  -- a request arriving with `:effect
       :execute`. This actor may only ever PROPOSE.
   10. `:scope-exclusion`     -- a dues-WAIVER request. Dues-amount /
       fee-waiver decisions are permanently outside this back-office
       coordination actor's authority.

  Returns {:store <store> :timeline [<captured run> ...]}."
  []
  (let [st (store/make-store)
        actor (operation/build st)
        timeline
        [(exec! st actor "t1" "1. 会員イベント日程 (member-1 / event-1)"
                {:operation :schedule-member-event :member-id "member-1"
                 :event-id "event-1" :effect :propose}
                ctx-phase-3)

         (exec! st actor "t2" "2. 会費処理の事務調整 (member-2)"
                {:operation :coordinate-dues-processing-logistics :member-id "member-2"
                 :description "Reconcile the July dues-processing batch against the bank statement"
                 :effect :propose}
                ctx-phase-3)

         (exec! st actor "t3" "3. 備品発注の調整 (member-2)"
                {:operation :coordinate-supply-request :member-id "member-2"
                 :description "Order name badges and lanyards for the annual gala"
                 :effect :propose}
                ctx-phase-3)

         (exec! st actor "t4" "4. 職員シフト案 (member-1)"
                {:operation :schedule-staff-shift-proposal :member-id "member-1"
                 :description "Draft front-desk cover for the board meeting week"
                 :effect :propose}
                ctx-phase-3)

         (exec-resume! st actor "t5" "5. 安全・行動懸念の報告 (member-1) — 常時エスカレーション"
                       {:operation :flag-safety-concern :member-id "member-1"
                        :concern-type "member-conduct-issue"
                        :description "Member conduct concern raised at the chapter meeting, for human review"
                        :effect :propose}
                       ctx-phase-3 :approved ops-director)

         (exec-resume! st actor "t6" "6. 備品発注 (member-1) — phase 1 の段階ゲート"
                       {:operation :coordinate-supply-request :member-id "member-1"
                        :description "Order printed programmes for the annual gala"
                        :effect :propose}
                       ctx-phase-1 :rejected ops-director)

         (exec! st actor "t7" "7. 会員イベント日程 (member-2 / event-2) — phase 0"
                {:operation :schedule-member-event :member-id "member-2"
                 :event-id "event-2" :effect :propose}
                ctx-phase-0)

         (exec! st actor "t8" "8. 未検証会員へのイベント日程 (member-3)"
                {:operation :schedule-member-event :member-id "member-3"
                 :event-id "event-1" :effect :propose}
                ctx-phase-3)

         (exec! st actor "t9" "9. :execute 効果での備品発注 (member-2)"
                {:operation :coordinate-supply-request :member-id "member-2"
                 :description "Restock lanyards from the storeroom budget"
                 :effect :execute}
                ctx-phase-3)

         (exec! st actor "t10" "10. 会費減免の判断 (member-1)"
                {:operation :coordinate-dues-processing-logistics :member-id "member-1"
                 :description "Apply a dues waiver for this member for the current year"
                 :effect :propose}
                ctx-phase-3)]]
    {:store st :timeline timeline}))

;; ----------------------------- classification -----------------------------

(defn- hard-hold-fact?
  "A HARD governor refusal, classified on the FACT TYPE.

  Deliberately NOT `(seq (:violations f))`: `membershipassocorg.operation`
  synthesises a `{:check/id :approver-rejected}` violation onto the
  `:approval-rejected` fact a human's 'no' produces, so a
  violations-based test would silently count a human decision as a
  governor refusal. `-main` asserts this discrimination at build time."
  [f]
  (= :governor-hold (:t f)))

(defn- hard-holds [{:keys [store]}]
  (filterv hard-hold-fact? (store/ledger store)))

(defn- human-rejections [{:keys [store]}]
  (filterv #(= :approval-rejected (:t %)) (store/ledger store)))

(defn- escalations
  "Runs the PHASE GATE sent to a human, with the gate's own reason
  keyword. Not holds -- these are governor-clean proposals."
  [{:keys [timeline]}]
  (filterv :escalated? timeline))

;; ----------------------------- approver attribution (derived) ---------------------

(def ^:private approver-key?
  "Keys whose VALUE names the human who decided.

  `:actor` is deliberately absent. In this repo's ledger facts `:actor`
  is `(:actor-id context)` -- the EXECUTING coordinator, not the
  approver. On this page's own data the two DIFFER (coordinator-01 vs
  ops-director-01), so reading `:actor` as the approver is provably
  wrong here rather than accidentally right."
  #{:approved-by :approver :granted-by :rejected-by :decided-by
    :authorized-by :signed-by :by})

(defn- approver-attributions
  "Every approver-shaped key/value pair anywhere inside `form`."
  [form]
  (let [acc (atom [])]
    (walk/postwalk
     (fn [x]
       (when (map? x)
         (doseq [[k v] x]
           (when (and (approver-key? k) (some? v))
             (swap! acc conj [k v]))))
       x)
     form)
    @acc))

(defn- retains-value?
  "Does `form` contain `v` anywhere at all (any key, any depth)?"
  [form v]
  (let [hit (atom false)]
    (walk/postwalk (fn [x] (when (= x v) (reset! hit true)) x) form)
    @hit))

(defn- attribution-findings
  "For every run a HUMAN actually decided, measure -- at render time,
  from this run's own store slices -- whether each store plane kept that
  human's identity.

  This is derived, never asserted: if someone later teaches
  `membershipassocorg.operation` to carry the approver onto the ledger
  fact, this section reports `retained` on its own with no edit here."
  [{:keys [timeline]}]
  (for [{:keys [label op subject approval ledger-slice coord-slice]} timeline
        :when (some? approval)
        :let [who (:by approval)]]
    {:label label
     :op op
     :subject subject
     :human who
     :human-status (:status approval)
     :ledger-fact-types (mapv :t ledger-slice)
     :ledger-retains? (retains-value? ledger-slice who)
     :ledger-attrs (approver-attributions ledger-slice)
     :coord-retains? (retains-value? coord-slice who)
     :coord-attrs (approver-attributions coord-slice)
     ;; what the ledger fact says instead, if anything
     :ledger-actor (some :actor ledger-slice)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw-name [v]
  (cond (nil? v) "" (keyword? v) (name v) :else (str v)))

(defn- dash
  "Renders an absent value as an em dash so no `nil` ever reaches the page."
  [v]
  (let [s (kw-name v)] (if (str/blank? s) "—" s)))

(defn- join-kw [vs] (if (seq vs) (str/join ", " (map kw-name vs)) "—"))

(defn- td [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- code [v] (str "<code>" (esc (dash v)) "</code>"))

(defn- rows [xs] (if (seq xs) (str/join "\n" xs) "        <tr><td>—</td></tr>"))

(defn- flag [true? yes no]
  (if true?
    (str "<span class=\"ok\">" yes "</span>")
    (str "<span class=\"critical\">" no "</span>")))

;; ----------------------------- cells -----------------------------

(defn- disposition-cell
  "The disposition this run actually ended on. HARD governor holds and
  human rejections both end in `:hold`, so the cell is disambiguated by
  what the run's own audit facts say happened, not by the keyword alone."
  [{:keys [disposition violations approval escalated? status escalate-reason]}]
  (case disposition
    :commit
    (if-let [by (and (= :approved (:status approval)) (:by approval))]
      (str "<span class=\"ok\">approved &amp; committed</span> "
           "<span class=\"muted\">by " (esc by) "</span>")
      "<span class=\"ok\">auto-committed</span> <span class=\"muted\">(phase :auto)</span>")

    :hold
    (if (= :rejected (:status approval))
      (str "<span class=\"warn\">HUMAN rejection</span> "
           "<span class=\"muted\">by " (esc (:by approval)) " &middot; governor was clean</span>")
      (str "<span class=\"critical\">HARD governor hold &middot; "
           (esc (join-kw violations)) "</span>"))

    :escalate
    (if (= :interrupted status)
      (str "<span class=\"warn\">paused, awaiting a human</span> "
           "<span class=\"muted\">(" (esc (dash escalate-reason)) ")</span>")
      (str "<span class=\"warn\">escalated</span> "
           (if escalated? "" "<span class=\"muted\">(no approval fact)</span>")))

    (str "<span class=\"muted\">" (esc (dash disposition)) "</span>")))

(defn- human-cell [{:keys [escalated? approval]}]
  (cond
    (some? approval)
    (str "<span class=\"warn\">yes &mdash; " (esc (kw-name (:status approval)))
         " by " (esc (:by approval)) "</span>")
    escalated?
    "<span class=\"warn\">yes &mdash; still waiting</span>"
    :else
    "<span class=\"muted\">no &mdash; never reached a human</span>"))

;; ----------------------------- entity <-> run linkage -----------------------------

(defn- runs-for-member [timeline member-id]
  (filter #(= member-id (:subject %)) timeline))

(defn- runs-for-event [timeline event-id]
  (filter #(= event-id (:description %)) timeline))

(defn- last-status [runs]
  (if-let [r (last runs)] (disposition-cell r) "<span class=\"muted\">no activity</span>"))

;; ----------------------------- rows -----------------------------

(defn- member-row [timeline {:keys [member-id name registered? verified? title organization]}]
  (td (code member-id) (esc (dash name)) (esc (dash title)) (esc (dash organization))
      (flag registered? "registered" "NOT registered")
      (flag verified? "verified" "UNVERIFIED")
      (last-status (runs-for-member timeline member-id))))

(defn- event-row [timeline {:keys [event-id name date location organizer-member]}]
  (td (code event-id) (esc (dash name)) (esc (dash date)) (esc (dash location))
      (code organizer-member)
      (last-status (runs-for-event timeline event-id))))

(defn- account-row [{:keys [member-id dues-balance status last-payment-date]}]
  (td (code member-id)
      (str "<span class=\"num\">" (esc (dash dues-balance)) "</span>")
      (if (= "current" status)
        (str "<span class=\"ok\">" (esc (dash status)) "</span>")
        (str "<span class=\"warn\">" (esc (dash status)) "</span>"))
      (esc (dash last-payment-date))))

(defn- timeline-row [{:keys [label op subject phase effect confidence details] :as r}]
  (td (esc label) (code op) (code subject)
      (str "<span class=\"num\">" (esc (dash phase)) "</span>")
      (code effect)
      (str "<span class=\"num\">" (esc (dash confidence)) "</span>")
      (disposition-cell r)
      (human-cell r)
      (esc (if (seq details) (str/join " / " details) "—"))))

(defn- hold-row [{:keys [op subject basis violations]}]
  (td (str "<span class=\"critical\">" (esc (join-kw basis)) "</span>")
      (code op) (code subject)
      (esc (str/join " / " (map :violation violations)))))

(defn- escalation-row [{:keys [label op subject phase escalate-reason approval status]}]
  (td (esc label) (code op) (code subject)
      (str "<span class=\"num\">" (esc (dash phase)) "</span>")
      (if (= :always-escalate escalate-reason)
        (str "<span class=\"warn\">" (esc (kw-name escalate-reason))
             "</span> <span class=\"muted\">permanent policy — every phase</span>")
        (str "<span class=\"warn\">" (esc (dash escalate-reason))
             "</span> <span class=\"muted\">rollout gate — governor was clean</span>"))
      (cond
        (some? approval) (str "<span class=\"muted\">" (esc (kw-name (:status approval)))
                              " by " (esc (:by approval)) "</span>")
        (= :interrupted status) "<span class=\"warn\">still paused</span>"
        :else "—")))

(defn- coord-row [{:keys [op member-id effect value approved-by]}]
  (td (code op) (code member-id) (code effect)
      (esc (or (:description value) (:event-id value) "—"))
      (if approved-by
        (str "<span class=\"ok\">" (esc approved-by) "</span>")
        "<span class=\"muted\">— (auto-committed, no human)</span>")))

(defn- ledger-row [{:keys [t op subject actor disposition basis]}]
  (td (if (= :governor-hold t)
        (str "<span class=\"critical\">" (esc (kw-name t)) "</span>")
        (str "<span class=\"muted\">" (esc (kw-name t)) "</span>"))
      (code op) (code subject) (code actor)
      (esc (dash disposition))
      (esc (join-kw basis))))

(defn- attribution-row [{:keys [label human human-status ledger-fact-types
                                ledger-retains? ledger-attrs ledger-actor
                                coord-retains? coord-attrs]}]
  (td (esc label)
      (str "<span class=\"muted\">" (esc (kw-name human-status)) "</span> "
           (esc human))
      (esc (join-kw ledger-fact-types))
      (if ledger-retains?
        (str "<span class=\"ok\">retained</span> <span class=\"muted\">"
             (esc (join-kw (map first ledger-attrs))) "</span>")
        (str "<span class=\"critical\">DROPPED</span> "
             "<span class=\"muted\">ledger records <code>:actor "
             (esc (dash ledger-actor)) "</code>, the executing coordinator</span>"))
      (if coord-retains?
        (str "<span class=\"ok\">retained</span> <span class=\"muted\">"
             (esc (join-kw (map first coord-attrs))) "</span>")
        "<span class=\"critical\">DROPPED</span>")))

;; ----------------------------- fixed contract (labelled documentation) ------------

(def ^:private governor-contract-rows
  ;; Static description of this actor's own FIXED contract: the three
  ;; HARD checks in `membershipassocorg.governor` and the `:auto` /
  ;; `:always-escalate` sets in `membershipassocorg.phase`. This does
  ;; not vary per run, so it is hand-described documentation and is
  ;; labelled as such on the page. Everything else here is live output.
  [(td "<code>:member-unverified</code>"
       (str "<span class=\"critical\">HARD &middot; permanent</span> &middot; the member record must exist AND be "
            "<code>:registered?</code> AND <code>:verified?</code>, re-derived from the store's own member record "
            "every time — never from the advisor's self-report"))
   (td "<code>:effect-not-propose</code>"
       (str "<span class=\"critical\">HARD &middot; permanent</span> &middot; this actor may only ever "
            "<code>:propose</code>. Any other effect is refused outright — there is no execute path"))
   (td "<code>:scope-exclusion</code>"
       (str "<span class=\"critical\">HARD &middot; permanent</span> &middot; membership eligibility / expulsion, "
            "professional certification / credentialing, advocacy positions, dues amounts and fee waivers, and "
            "disciplinary action are permanently outside this back-office coordination actor's authority"))
   (td "<code>:flag-safety-concern</code>"
       (str "<span class=\"warn\">ALWAYS escalates to a human</span> &middot; present in every phase's "
            "<code>:always-escalate</code> set (phase 0–3), so it can never auto-commit at any rollout stage"))
   (td "<code>:schedule-member-event</code> / <code>:coordinate-dues-processing-logistics</code>"
       (str "<span class=\"ok\">auto-commits from phase 1</span> when the governor is clean; escalates at phase 0"))
   (td "<code>:coordinate-supply-request</code> / <code>:schedule-staff-shift-proposal</code>"
       (str "<span class=\"ok\">auto-commits from phase 2</span> when the governor is clean; escalates at phase 0–1"))])

(defn- phase-row [[p {:keys [name auto always-escalate]}]]
  (td (str "<span class=\"num\">" (esc p) "</span>")
      (esc (dash name))
      (esc (join-kw (sort-by kw-name auto)))
      (esc (join-kw (sort-by kw-name always-escalate)))))

;; ----------------------------- the document -----------------------------

(defn render
  "Renders the whole operator-console document from a `run-demo!`
  result. `store` is the real store the actor just wrote; `timeline` is
  the real per-run governor / phase-gate output."
  [{:keys [store timeline] :as result}]
  (let [members  (sort-by :member-id (store/all-members store))
        events   (sort-by :event-id (store/all-events store))
        accounts (sort-by :member-id (store/all-accounts store))
        ledger   (vec (store/ledger store))
        coord    (vec (store/coordination-log store))
        holds    (hard-holds result)
        rejects  (human-rejections result)
        escs     (escalations result)
        findings (vec (attribution-findings result))
        commits  (filterv #(= :commit (:disposition %)) timeline)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-941 &middot; membership association administrative coordination</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"

     "<header class=\"bar\">\n"
     "  <h1>Business, employers and professional membership organizations (ISIC 941) — Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> <span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">propose-only — no execute path</span> "
     "<span class=\"badge\">safety concerns ALWAYS human-approved</span></p>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>この実行の要約 / What this run produced</h2>\n"
     "    <p class=\"muted\">Generated by <code>clojure -M:dev:render-html</code>, which executes "
     "<code>membershipassocorg.operation</code>'s compiled langgraph-clj StateGraph through "
     "<code>langgraph.graph/run*</code> — the same entry point <code>membershipassocorg.sim</code> uses. "
     "Every number below is counted from the resulting store, not written by hand.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Measure</th><th>Count</th><th>Meaning</th></tr></thead>\n"
     "      <tbody>\n"
     (rows
      [(td "graph runs" (str "<span class=\"num\">" (count timeline) "</span>")
           "one compiled-StateGraph run each, every one driven through <code>run*</code>")
       (td "committed records" (str "<span class=\"num\">" (count coord) "</span>")
           "reached the SSoT coordination log")
       (td "<span class=\"critical\">HARD governor holds</span>"
           (str "<span class=\"num\">" (count holds) "</span>")
           (str "ledger facts with <code>:t :governor-hold</code> — the governor refused; "
                "no human was ever consulted and none could override"))
       (td "human rejections" (str "<span class=\"num\">" (count rejects) "</span>")
           (str "ledger facts with <code>:t :approval-rejected</code> — a human said no to a "
                "governor-CLEAN proposal. <strong>Not</strong> a governor hold, although the fact "
                "does carry a synthesised <code>:approver-rejected</code> violation"))
       (td "escalations to a human" (str "<span class=\"num\">" (count escs) "</span>")
           "phase-gate or always-escalate; the graph really pauses at <code>:request-approval</code>")
       (td "auto-committed with no human" (str "<span class=\"num\">"
                                               (count (remove :escalated? commits)) "</span>")
           "governor-clean AND the op was in that phase's <code>:auto</code> set")])
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD ガバナ拒否 / HARD governor holds</h2>\n"
     "    <p class=\"muted\">Each row is a proposal <code>membershipassocorg.governor</code> refused "
     "outright. A HARD hold is permanent and un-overridable: the run never reaches "
     "<code>:request-approval</code>, so no approver, and no later rollout phase, can turn it into a "
     "commit. Rows below are the ledger's own <code>:governor-hold</code> facts.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Check refused</th><th>Op</th><th>Member</th><th>Governor's own words</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map hold-row holds))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>人への上申 / Escalations — and why they are not holds</h2>\n"
     "    <p class=\"muted\">These proposals were <strong>governor-clean</strong>. They reached a human "
     "because of <code>membershipassocorg.phase</code>, not because of the governor: either the op is in "
     "that phase's <code>:always-escalate</code> set (permanent policy) or the rollout phase has not yet "
     "enabled auto-commit for it. The reason keyword is the phase gate's own return value.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Run</th><th>Op</th><th>Member</th><th>Phase</th><th>Gate reason</th><th>Human decision</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map escalation-row escs))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>承認者の帰属 / Approver attribution — measured, not assumed</h2>\n"
     "    <p class=\"muted\">For every run a human actually decided, this section re-derives at render "
     "time whether each store plane kept that human's identity, by searching this run's own ledger and "
     "coordination-log slices for the approver value. Nothing here is hard-coded: if the actor is later "
     "taught to carry the approver onto the ledger fact, these cells turn green on their own.</p>\n"
     "    <p class=\"muted\">Note that the ledger fact's <code>:actor</code> is <em>not</em> the approver — "
     "it is <code>(:actor-id context)</code>, the coordinator who executed the run. On this page's own "
     "data the two are different people, so reading <code>:actor</code> as the approver is demonstrably "
     "wrong rather than accidentally right.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Run</th><th>Human decision</th><th>Ledger facts written</th>"
     "<th>Audit ledger keeps the human?</th><th>Coordination log keeps the human?</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map attribution-row findings))
     "\n      </tbody>\n"
     "    </table>\n"
     (if (some (complement :ledger-retains?) findings)
       (str "    <p class=\"banner\"><strong>Disclosed gap (observed in this run, not patched here).</strong> "
            "The append-only audit ledger does not record WHICH human approved or rejected. "
            "<code>membershipassocorg.operation</code>'s <code>:request-approval</code> node puts "
            "<code>:approved-by</code> on the <em>record</em> (so the coordination log keeps it), and emits an "
            "<code>:approval-granted</code> audit entry carrying <code>:by</code> — but only the "
            "<code>:commit</code> node writes to the ledger, and it writes <code>commit-fact</code>, which "
            "carries the raw <code>proposal</code> rather than the approved <code>record</code>. The "
            "<code>:approval-granted</code> entry never leaves the in-memory <code>:audit</code> channel. "
            "The rejection path loses the human entirely: no plane records who said no. Answering "
            "&ldquo;who authorised this?&rdquo; from the audit ledger alone is therefore not possible today. "
            "This is disclosed rather than silently patched, because changing the governor or the ledger "
            "shape is not a rendering task.</p>\n")
       "")
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>この実行の全判定 / Every disposition from this run</h2>\n"
     "    <p class=\"muted\">One row per graph run, in execution order. Confidence and the governor detail "
     "text are the advisor's and governor's own output. &ldquo;Reached a human?&rdquo; is read from the "
     "run's own audit facts — a HARD hold answers <em>no</em> structurally, because the graph routes it "
     "straight to <code>:hold</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Run</th><th>Op</th><th>Member</th><th>Phase</th><th>Effect</th>"
     "<th>Confidence</th><th>Disposition</th><th>Reached a human?</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map timeline-row timeline))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>会員名簿 / Member directory</h2>\n"
     "    <p class=\"muted\">Seeded in <code>membershipassocorg.store</code>. The governor re-derives "
     "<code>:registered?</code> and <code>:verified?</code> from these records themselves before any "
     "proposal for that member may commit or even escalate.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Member</th><th>Name</th><th>Title</th><th>Organization</th>"
     "<th>Registered</th><th>Verified</th><th>Last op this run</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map (partial member-row timeline) members))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>行事 / Events on file</h2>\n"
     "    <table>\n"
     "      <thead><tr><th>Event</th><th>Name</th><th>Date</th><th>Location</th>"
     "<th>Organizer</th><th>Last op this run</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map (partial event-row timeline) events))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>会費口座 / Dues accounts</h2>\n"
     "    <p class=\"muted\">Balances are read-only context for logistics coordination. Setting or waiving a "
     "dues <em>amount</em> is a <code>:scope-exclusion</code> HARD hold — see the governor contract below.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Member</th><th>Balance</th><th>Status</th><th>Last payment</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map account-row accounts))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>調整記録 / Coordination log (committed records)</h2>\n"
     "    <p class=\"muted\">The SSoT-facing append-only log written by the graph's <code>:commit</code> "
     "node. Every record here is a <em>proposal</em>: this actor has no execute path.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Member</th><th>Effect</th><th>Subject</th><th>Approved by</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map coord-row coord))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>監査台帳 / Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log. Only the graph's <code>:commit</code> and "
     "<code>:hold</code> nodes may write to it. <code>:actor</code> is the executing coordinator, "
     "<em>not</em> the approver.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Member</th><th>Actor (executing)</th>"
     "<th>Disposition</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map ledger-row ledger))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>段階的ロールアウト / Rollout phases</h2>\n"
     "    <p class=\"muted\">Read directly out of <code>membershipassocorg.phase/phase-config</code> at "
     "build time, so this table cannot drift from the code that gates the runs above.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Phase</th><th>Name</th><th>Auto-commits</th><th>Always escalates</th></tr></thead>\n"
     "      <tbody>\n"
     (rows (map phase-row (sort-by key phase/phase-config)))
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Membership Association Coordination Governor)</h2>\n"
     "    <p class=\"muted\"><strong>Fixed contract — hand-described documentation, not telemetry.</strong> "
     "This table states behaviour that does not vary per run (the governor's three HARD checks and the "
     "phase gate's sets). Every other table on this page is live actor output.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Check / op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (rows governor-contract-rows)
     "\n      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p class=\"muted\">cloud-itonami-isic-941 &middot; generated by "
     "<code>membershipassocorg.render-html</code> from a real "
     "<code>membershipassocorg.operation</code> StateGraph run. Deterministic: no timestamps, no "
     "wall clock, no randomness, no per-run identifiers — two consecutive builds are byte-identical. "
     "The build refuses to write this file at all if the run produced zero HARD governor holds.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- build-time invariants -----------------------------

(defn- assert-hard-holds!
  "The page's whole claim is that an independent governor really refuses
  things. If a run ever produces zero HARD holds, that claim is
  unevidenced, so refuse to write a file at all rather than ship a page
  that quietly asserts a governor nobody saw work.

  This CAN pass: `run-demo!` drives one scenario per HARD check in
  `membershipassocorg.governor`, and the check ids observed are printed
  below so a reader can see WHICH refusals were counted."
  [holds]
  (println (str "HARD-HOLDS\t" (count holds)))
  (doseq [h holds]
    (println (str "  refused\t" (join-kw (:basis h)) "\t" (name (:op h)) "\t" (:subject h))))
  (when (zero? (count holds))
    (throw (ex-info (str "Refusing to write " out-default
                         ": the actor run produced ZERO HARD governor holds, so this page "
                         "would claim a governor that was never observed refusing anything.")
                    {:hard-holds 0})))
  (let [ts (set (map :t holds))]
    (when-not (= #{:governor-hold} ts)
      (throw (ex-info "HARD-hold classifier drifted: expected only :governor-hold facts"
                      {:fact-types ts})))))

(defn- assert-classifier-discriminates!
  "Proves, at build time, that the HARD-hold classifier is not simply
  counting `(seq (:violations f))`.

  `membershipassocorg.operation` synthesises a
  `{:check/id :approver-rejected}` violation onto the
  `:approval-rejected` fact a human's 'no' produces. So if this run
  produced such a fact, it MUST carry a violation (otherwise this test
  would be vacuous and would pass without measuring anything) and it
  MUST NOT be counted as a HARD hold."
  [ledger holds]
  (let [rejects (filterv #(= :approval-rejected (:t %)) ledger)
        violation-count (count (filter #(seq (:violations %)) ledger))]
    (println (str "LEDGER-FACTS\t" (count ledger)
                  "\tviolation-carrying\t" violation-count
                  "\thuman-rejections\t" (count rejects)))
    (when (empty? rejects)
      (throw (ex-info (str "Refusing to claim the HARD-hold classifier discriminates: this run "
                           "produced no :approval-rejected fact, so the discrimination was never "
                           "exercised. Restore the human-rejection scenario in run-demo!.")
                      {:human-rejections 0})))
    (doseq [r rejects]
      (when-not (seq (:violations r))
        (throw (ex-info (str "Discrimination test is vacuous: the :approval-rejected fact carries no "
                             "violations, so classifying on :violations would agree with classifying "
                             "on :t by accident.")
                        {:fact r})))
      (when (contains? (set holds) r)
        (throw (ex-info "Classifier counted a HUMAN rejection as a HARD governor hold"
                        {:fact r}))))
    (when-not (< (count holds) violation-count)
      (throw (ex-info (str "Expected strictly fewer HARD holds than violation-carrying ledger facts; "
                           "if they are equal the classifier is indistinguishable from a "
                           ":violations test on this data.")
                      {:hard-holds (count holds) :violation-carrying violation-count})))
    (println (str "CLASSIFIER-OK\thard-holds\t" (count holds)
                  "\tnot-counted-human-rejections\t" (count rejects)))))

(defn -main
  "clojure -M:dev:render-html [out-file]"
  [& [out]]
  (let [out (or out out-default)
        result (run-demo!)
        ledger (vec (store/ledger (:store result)))
        holds (hard-holds result)]
    (println (str "SCANNED\truns\t" (count (:timeline result))
                  "\tledger\t" (count ledger)
                  "\tcoordination-log\t" (count (store/coordination-log (:store result)))
                  "\tgovernor-checks\t"
                  (count [#'governor/member-unverified-violations
                          #'governor/effect-not-propose-violations
                          #'governor/scope-exclusion-violations])))
    (assert-hard-holds! holds)
    (assert-classifier-discriminates! ledger holds)
    (let [html (render result)
          f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f html)
      (println (str "WROTE\t" out "\t" (count (.getBytes html "UTF-8")) " bytes")))))
