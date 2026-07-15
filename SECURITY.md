# Security

## Scope Boundary Enforcement

This actor implements hard-coded scope boundaries to prevent scope creep and unauthorized decision-making:

### Hard-Coded Blocks

The following decision types are permanently blocked by the governor and cannot be overridden:

1. **Membership-eligibility decisions** — Who can join, who remains a member, expulsion
2. **Professional certification/credentialing** — Member qualifications, license status, title
3. **Advocacy-policy content** — Positions, political statements, policy endorsements
4. **Dues-amount/fee-waiver decisions** — Fee structures, payment amounts, financial accommodations
5. **Disciplinary action** — Sanctions, censure, suspension for member conduct

These blocks are implemented as regex patterns in `governor.cljc:scope-exclusion-violations` and apply to all operations except `:flag-safety-concern` (which is allowed and escalates).

### Re-verification

Member/event records are re-verified on every proposal. The actor does not trust proposal self-report; it always re-derives :registered?/:verified? from the store.

### Escalation

Safety and conduct concerns always escalate to humans, even if governance passes all checks. There is no automated override or auto-commit path for escalated proposals.

## Testing

Run the comprehensive test suite to verify all hard checks:

```bash
nbb -m membershipassocorg.test
```

All 20 tests must pass before deployment.
