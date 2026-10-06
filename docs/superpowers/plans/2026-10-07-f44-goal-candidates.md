# F44 Goal Candidates — implementation plan

**Global source:** `PLAN.md` F44. **Approved scope:** `.agent/specs/F44-goal-candidates.md`; legacy behavior: `services/goals.py`. Follow the user's RED → implement → GREEN → regression → commit cycle.

## F44.1 Go candidate calculation

- [x] Add tests first for product eligibility, group eligibility, decisions/overrides, cadence, count target, sum minimum and rounding, skipped sums, stable sorting/limits, strict API auth/decoding and golden JSON.
- [x] Run the new tests and verify RED is missing F44 behavior.
- [x] Implement a versioned pure calculator and authenticated handler in the existing `analytics-api`; do not add a deployable or data store.
- [x] Run focused tests, `go test ./... -count=1`, `go vet ./...`, contract tests, and `git diff --check`.
- [x] Commit F44.1 only after GREEN; record evidence in `.agent/PROGRESS.md`.

## F44.2 Core persistence and member API

- [x] Write isolated-PostgreSQL acceptance tests for member/viewer boundaries, one active goal, fixed 30-day terms, immutable target/unit, preference changes, cancel, stale preview rejection, and nullable receipt amounts.
- [x] Observe compile RED for missing F44 Core API types, then a migration RED because PostgreSQL rejects the timezone-sensitive generated `ends_at` expression as non-immutable.
- [x] Add V39 RLS-protected member preference and goal persistence; persist exact 30-day `ends_at`, keep accepted terms immutable, and allow unknown count-goal money without inventing zero.
- [x] Add Core Go-client validation, member API, same-origin BFF routes, audit/outbox events and OpenAPI contract.
- [x] Run focused Core PostgreSQL/API acceptance, full `:services:core:check`, contract/migration suite and `git diff --check`.
- [x] Commit F44.2 after GREEN; update `.agent/PROGRESS.md`.

## F44.3 Web surface

1. Add component tests before the proposal/active-goal UI.
2. Render Core candidates, let a writer choose and accept a target, show immutable terms and remaining window, and keep viewer controls read-only.
3. Verify RU/EN, unavailable/skipped candidates, explicit errors and reload behavior; run full Web test/build and API contract tests.
4. Commit separately and update parity. F44 completes only after F44.1–F44.3 gates pass.
