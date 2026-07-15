# Governance

## Decision-Making

This actor operates under the cloud-itonami fleet governance model:

- All proposals are subject to three HARD, un-overridable checks (see governor.cljc)
- Decisions that fail any check are automatically held for human review
- Safety/conduct concerns always escalate, regardless of governance pass/fail
- No automated override path exists; all hard-check violations require explicit human intervention

## Hard Checks

1. **Member/event-record verified** — Member must be registered AND verified in store. This is re-derived from the member's own :registered?/:verified? fields, never from proposal self-report.

2. **Effect is :propose** — All proposals must have effect :propose. Other effects are rejected outright.

3. **Scope exclusion** — Proposals touching membership-eligibility decisions, professional certification/credentialing, advocacy-policy content, dues-amount/fee-waiver decisions, or disciplinary action are blocked. Uses EN+JA substring scan, qualified to avoid self-blocking legitimate :flag-safety-concern ops.

## Escalation

The following operations always escalate to humans for review:
- `:flag-safety-concern` — Any safety or conduct concern is escalated, never auto-committed.

## Phases

- **Phase 0** (read-only) — No auto-commit
- **Phase 1** (event + dues) — Event scheduling and dues-processing auto-commit
- **Phase 2** (+ supply + staff) — Add supply and staff-shift proposals
- **Phase 3** (full auto) — All operations auto-commit except :flag-safety-concern (always escalates)

## Escalation Process

All escalated proposals are held in the coordination-log for human review. The store/coordination-log provides the append-only decision history.
