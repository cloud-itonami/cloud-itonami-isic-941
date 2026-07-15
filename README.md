# cloud-itonami-isic-941

Membership association administrative coordination actor — ISIC 941 (Activities of business, employers and professional membership organizations).

## Purpose

This actor provides LLM-driven administrative coordination for membership organizations (trade associations, chambers of commerce, professional bodies). It handles:

- Member enrollment logistics
- Event/meeting scheduling
- Dues-processing logistics
- Supply coordination
- Staff shift scheduling

## Scope

**In scope:**
- Member enrollment logistics
- Event/meeting scheduling
- Dues-processing logistics (reminders, tracking)
- Supply coordination (non-content consumables)
- Staff shift scheduling proposals (administrative only, never binding)
- Safety/conduct concern flagging (escalates to humans)

**Out of scope (hard-coded blocks):**
- Membership-eligibility decisions
- Professional certification/credentialing decisions
- Advocacy-policy/content decisions
- Dues-amount or fee-waiver decisions
- Disciplinary actions
- Safety-authority overrides

## Architecture

### Modules (all `.cljc` — portable across Clojure, ClojureScript, nbb)

- **store.cljc** — MemStore protocol; in-memory demo data (facilities, members, events, accounts, ledger)
- **governor.cljc** — Three HARD, permanent, un-overridable checks:
  1. Member/event-record verified (re-derived from store, never self-report)
  2. Effect is `:propose` (no overrides)
  3. Scope exclusion (EN+JA substring scan, qualified to avoid self-blocking legitimate ops)
- **advisor.cljc** — Deterministic proposal generation (demo; production uses LLM)
- **operation.cljc** — State machine: intake → advise → govern → decide → commit | hold | escalate
- **phase.cljc** — Rollout phases 0–3 (which ops auto-commit, which escalate)
- **sim.cljc** — Demo simulation: 5 scenarios (happy path, hard checks, escalation)
- **test.cljc** — Comprehensive test suite (16 test cases covering all critical paths)

## Running

### Tests (via nbb)

```bash
nbb -m membershipassocorg.test
```

### Simulation (via nbb)

```bash
nbb -m membershipassocorg.sim
```

## Test Coverage

**Store tests** (5 cases):
- Member lookup
- All members
- Event lookup
- Account lookup
- Ledger append

**Governor tests** (7 cases):
- Member unverified check
- Effect not :propose check
- Scope exclusion: membership-eligibility
- Scope exclusion: certification
- Scope exclusion: dues-waiver
- Flag-safety-concern allowed (legitimate use)
- Full governor decision (pass)

**Operation tests** (5 cases):
- Event scheduling (happy path)
- Unverified member rejection
- Safety concern escalation
- Dues logistics (happy path)
- Supply request (happy path)

**Phase tests** (3 cases):
- Phase 0 (read-only)
- Phase 1 (event + dues)
- Phase 3 (full auto-commit)

**Total: 20 test cases, all passing.**

## Demo Data

Three members (one unverified), two events, three dues accounts:

- **member-1** (verified): Alice Chen, Director
- **member-2** (verified): Bob Martinez, Manager
- **member-3** (unverified): Charlie Wong, Engineer

## Governance

See GOVERNANCE.md for decision-making process and escalation policy.

## License

GNU Affero General Public License v3.0. See LICENSE.

## Code of Conduct

See CODE_OF_CONDUCT.md.
