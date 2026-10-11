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

- [x] Add component tests before the proposal/active-goal UI; observe RED because the Goals panel did not yet exist.
- [x] Render Core candidates on `/goals`, support writer-only unit changes and explicit accept/cancel, show immutable terms and the remaining 30-day window, and keep viewer controls read-only.
- [x] Show unknown money as an em dash, explain missing amounts, and refresh after a stale-watermark response; add RU/EN text and mobile layout.
- [x] Run all Web tests (72/72), production TypeScript/Vite build, full Core/PostgreSQL regression and `git diff --check`.
- [x] Commit F44.3 separately and record parity/progress evidence. F44.1–F44.3 gates pass; F44 is complete. F45 lifecycle/history and F46 outcome delivery remain separate.

## F44.4 Android member goal candidates

- [x] Add authenticated API and Compose behavior tests first; observe RED for missing Android goal DTOs, endpoints, and screen.
- [x] Parse the complete Core goals overview, including nullable exact money, skipped candidates, accepted terms, and reserved F45 progress/history fields; reject malformed contract data. Independently reviewed DTO validation and added RED tests for exact two-decimal values, OpenAPI evidence minimums, and 50,000 counter limits.
- [x] Add a separate Android Goals surface for writer/viewer roles. Let writers change future proposal unit, explicitly accept with the displayed candidate key and watermark, and explicitly cancel; keep accepted terms immutable and server-owned.
- [x] Keep candidate calculation and 30-day terms in Core. On stale 409 refresh and explain; never silently accept a changed candidate. Preserve null amounts and skipped reasons.
- [x] Run focused Android API/screen tests, F43/F42/report/model regression, contract checks, APK build and API 27 isolated emulator launch, then `git diff --check`.
- [x] Record local evidence; Android F44 stays PARTIAL until live authenticated Android-to-Core OIDC verification. Commit and push only project-owned F44 files after GREEN.
