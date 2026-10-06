# F45 Goal Lifecycle — execution plan

**Global source:** `PLAN.md` F45. **Behavior contract:** `.agent/specs/F45-goal-lifecycle.md`. **Legacy reference:** `services/goals.py`. Keep F46 delivery separate.

## F45.1 Go progress calculator and contract

- [x] Add calculation tests first for product/group membership, exact 30-day bounds, as-of cutoff, count/sum, unknown amounts, completion boundary and invalid/oversized inputs; observe compile RED.
- [x] Add authenticated strict internal API tests first; observe RED for missing F45 handler.
- [x] Add nullable exact-money progress calculator and token-protected endpoint; register route.
- [x] Extend F44 group proposals with a deterministic eligible member-key snapshot. Test confirmed decisions are included and user-allowed products are excluded.
- [x] Add OpenAPI schemas and strict contract acceptance tests for F45 progress and group membership.
- [x] Run Go tests/vet, all contract/migration tests, and `git diff --check`.
- [ ] Commit F45.1 and record evidence in `.agent/PROGRESS.md`.

## F45.2 Core durable progress and lifecycle

- [ ] Add PostgreSQL API acceptance tests first for product/group progress, accepted member-key snapshot, null amounts, cancellation exclusion, exactly-once close, history retention and owner/viewer/tenant boundaries.
- [ ] Observe RED on the isolated finance test database before migration or Core changes.
- [ ] Add progress client validation, member-only purchase snapshot and accepted group membership persistence.
- [ ] Add durable goal outcome/history, atomic completed status + audit/outbox, idempotent retries, cancellation semantics, and current candidates after a finished/cancelled goal.
- [ ] Run focused PostgreSQL/API, full Core checks, migrations/contracts and `git diff --check`.
- [ ] Commit F45.2 and record evidence.

## F45.3 Web progress, note and history

- [ ] Add component tests first for progress changes after purchase refresh, unknown money, completion/history, cancellation, next candidate, RU/EN and viewer permissions.
- [ ] Implement progress, read-only purchase note, retained history and proposal access.
- [ ] Run focused/all Web tests, production build, relevant Core/contract regression and `git diff --check`.
- [ ] Commit F45.3 and mark F45 complete only when all slices pass.
