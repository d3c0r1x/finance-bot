# F44 Goal Candidates — implementation plan

**Global source:** `PLAN.md` F44. **Approved scope:** `.agent/specs/F44-goal-candidates.md`; legacy behavior: `services/goals.py`. Follow the user's RED → implement → GREEN → regression → commit cycle.

## F44.1 Go candidate calculation

- [x] Add tests first for product eligibility, group eligibility, decisions/overrides, cadence, count target, sum minimum and rounding, skipped sums, stable sorting/limits, strict API auth/decoding and golden JSON.
- [x] Run the new tests and verify RED is missing F44 behavior.
- [x] Implement a versioned pure calculator and authenticated handler in the existing `analytics-api`; do not add a deployable or data store.
- [x] Run focused tests, `go test ./... -count=1`, `go vet ./...`, contract tests, and `git diff --check`.
- [x] Commit F44.1 only after GREEN; record evidence in `.agent/PROGRESS.md`.

## F44.2 Core persistence and member API

1. Write isolated-PostgreSQL acceptance tests for member/viewer boundaries, one-active-goal, a fixed 30-day period from acceptance, immutable target/unit, count/sum preference affecting future proposals only, cancel and idempotent request behavior.
2. Observe behavioral RED, then add migration/RLS, member service, API/BFF contract and tests.
3. Run Core PostgreSQL check and API contract suite; verify no receipt or transaction facts are written.
4. Commit separately and record evidence.

## F44.3 Web surface

1. Add component tests before the proposal/active-goal UI.
2. Render Core candidates, let a writer choose and accept a target, show immutable terms and remaining window, and keep viewer controls read-only.
3. Verify RU/EN, unavailable/skipped candidates, explicit errors and reload behavior; run full Web test/build and API contract tests.
4. Commit separately and update parity. F44 completes only after F44.1–F44.3 gates pass.
