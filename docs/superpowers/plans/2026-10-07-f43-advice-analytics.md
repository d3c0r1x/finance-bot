# F43 Advice Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver member-scoped savings-ceiling, weekly optional-spend trend, and post-advice purchase-cadence analytics with durable recomputation and honest completeness/status reporting.

**Architecture:** Core owns authorization, bounded personal-input snapshots, durable recompute jobs, input watermarks, and stored result/query APIs. The existing Go `analytics-api` computes the deterministic report and polls Core's authenticated internal job API; no separate deployable or ClickHouse source of truth is added. Web reads the same Core report and exposes current status, incomplete reasons, and explicit refresh for stale inputs.

**Tech Stack:** Java 17 / Spring Boot, PostgreSQL + Flyway, Go, React + TypeScript, Vitest, OpenAPI and JSON contract fixtures.

**Spec:** `.agent/specs/F43-advice-analytics.md`; global requirement: `PLAN.md` F43; execution state: `.agent/PROGRESS.md`.

## Global Constraints

- Use only the authenticated active member's confirmed purchases and receipt positions; do not include family aggregates or raw transaction descriptions.
- Savings ceiling uses 90 days, repeated harmful/unnecessary items with at least 2 purchases, excludes member-allowed products, and scales as `sum / 90 * 30`.
- Trend uses four rolling 7-day windows in member timezone; unknown verdicts remain denominator-only; missing amounts are never replaced by zero; fewer than 2 data-bearing windows means unavailable.
- Advice effect requires the first dated harmful/unnecessary item and at least 2 prior purchases of that normalized product; under 21 days is pending; direction threshold is strictly greater than 20%; copy must deny causal proof.
- F42 verdict recalculation does not create purchases or advice effects; report recalculation amount separately and flag affected windows.
- Use decimal money; preserve approved legacy normalization, rounding, top limits and 5 percentage-point trend-noise rule in golden fixtures.
- Job identity is member + monotonic input watermark + algorithm version; retries are bounded and idempotent; an old job must never overwrite a newer result.
- Keep Go endpoints internal and service-authenticated; Core owns access control and result persistence.
- Do not change transactions or receipt amounts; do not claim realized savings or causal advice impact.

## Review Focus

- Partial/missing amounts: test that affected outputs are marked partial/unavailable with `missing_amounts`, never zero-filled. (Tasks 1, 2, 4.)
- Old or repeated jobs: test idempotent retry and that an older result cannot replace the current watermark. (Tasks 2, 3.)
- A verdict-only F42 recalculation: test it changes the recalculation annotation only, not purchase count, spend, or cadence. (Tasks 1, 2.)
- Owner/member boundary and viewers: test foreign tenant/member reads are hidden and unauthorized roles cannot request member analytics. (Task 2.)
- Empty history and pending windows: test these return explicit reasons/statuses rather than zero trend or a premature advice-effect verdict. (Tasks 1, 4.)

---

### Task 1: Deterministic F43 report calculation in Go

**Files:**
- Create: `services/analytics-go/advice/f43.go`
- Create: `services/analytics-go/advice/f43_test.go`
- Create: `services/analytics-go/advice/f43_api.go`
- Create: `services/analytics-go/advice/f43_api_test.go`
- Modify: `services/analytics-go/cmd/analytics-api/main.go`
- Test/fixture: `contracts/analytics/advice-f43.v1.json`
- Modify: `contracts/openapi/finance-intelligence-v1.yaml`
- Test: `tools/contracts/test_contracts.py`

**Interfaces:**
- `BuildF43Report(request F43Request) (F43Report, error)` accepts only the versioned, bounded input contract; returns algorithm version, watermark, completeness, reason codes, savings ceiling, four-window trend, advice effects, and F42 recalculation annotation.
- The internal Go handler accepts `POST /internal/v1/analytics/advice/f43`, requires the existing analytics service token, rejects unknown/trailing JSON, invalid decimal values and oversized bodies, and returns the exact `F43Report` JSON contract.

- [ ] **Step 1: Write failing golden behavior tests** for: one purchase does not create a savings group; two harmful purchases do; allowed group is excluded; weekly denominator includes unknown verdicts but numerator does not; missing amounts make the report partial; fewer than two populated weeks is unavailable; advice requires two pre-slice purchases; under 21 days is pending; zero later purchases is “less often” without causality language; a 20% cadence change is unchanged while greater than 20% changes direction; F42-only verdict edits do not change purchase facts.
- [ ] **Step 2: Run `go test ./advice -run 'TestF43' -count=1` from `services/analytics-go`;** observe failure on missing F43 behavior, not a setup error.
- [ ] **Step 3: Implement decimal-based `BuildF43Report`** by porting the approved legacy boundaries from `services/advice.py`; preserve UTC event instants and apply the input timezone only for seven-day window assignment.
- [ ] **Step 4: Run the focused Go tests;** all F43 golden assertions pass, with explicit partial/unavailable reasons.
- [ ] **Step 5: Add authenticated internal-handler tests** for missing/wrong token, malformed/unknown/trailing fields, oversized request, invalid bounds, and valid report; register the route in `analytics-api`.
- [ ] **Step 6: Run `go test ./...` and `go vet ./...` from `services/analytics-go`;** both pass.
- [ ] **Step 7: Add OpenAPI schema and fixture parity checks** in `contracts/openapi/finance-intelligence-v1.yaml` for F43 request, report and reason enums; run `python -m pytest tools/contracts/test_contracts.py -q` from repository root.
- [ ] **Step 8: Commit** as `feat(F43.1): add advice analytics calculation` after focused and regression gates pass.

### Task 2: Member-scoped snapshot and durable recompute/query API in Core

**Files:**
- Create: `services/core/src/main/resources/db/migration/V37__advice_analytics_jobs.sql`
- Create: `services/core/src/main/java/com/decorix/finance/core/api/AdviceAnalyticsApi.java`
- Create: `services/core/src/main/java/com/decorix/finance/core/api/AdviceAnalyticsService.java`
- Create: `services/core/src/main/java/com/decorix/finance/core/api/AdviceAnalyticsController.java`
- Create: `services/core/src/main/java/com/decorix/finance/core/api/AdviceAnalyticsInternalController.java`
- Modify: `services/core/src/test/java/com/decorix/finance/core/api/TransactionApiPostgresTest.java` (reuse the existing isolated PostgreSQL/JWT integration fixture rather than boot a second Spring context)
- Modify: `contracts/openapi/finance-api-v1.yaml`
- Test: `tools/contracts/test_core_migration.py`, `tools/contracts/test_contracts.py`

**Interfaces:**
- Public member routes: `GET /api/v1/tenants/{tenantId}/analytics/advice` returns the latest job/report for the active member; `POST` on the same route requests current inputs and returns `202` with job state and watermark; `GET /api/v1/tenants/{tenantId}/analytics/advice/jobs/{jobId}` polls one member-owned job. Provide matching BFF routes for Web.
- Internal worker routes: authenticated `POST /internal/v1/analytics/advice-jobs/claim` atomically leases one eligible job and returns its bounded input; authenticated `POST /internal/v1/analytics/advice-jobs/{jobId}/result` records success/failure only for the current job lease.
- Core response state is `pending | processing | ready | failed | stale`; stored result includes `inputWatermark`, `algorithmVersion`, `complete | partial`, and explicit reasons. More than 50,000 source items returns `413` with `too_many_items` and never creates a truncated job.

- [x] **Step 1: Add PostgreSQL acceptance tests first** for active-member scope, viewer denial, same-input idempotence, changed-input watermark advance, 50,000-item cap plus one (return `too_many_items`, no truncation), atomic job claim/lease, retry eligibility, duplicate result delivery, and rejection of stale job completion.
- [x] **Step 2: Run the focused PostgreSQL tests** using the established isolated Core test database; the first feature run observed 404 for missing routes, and the later lease test observed duplicate delivery rejection before idempotence was implemented. Tests ran against the isolated database without skips.
- [x] **Step 3: Add V37 tables and indexes** for per-member input watermark, durable jobs, attempts/lease, and stored report; V38 adds the completed-lease token for idempotent delivery. Both use tenant RLS and a separate analytics-worker policy.
- [x] **Step 4: Implement `AdviceAnalyticsService`** to resolve active membership/role, select only that member's confirmed expenses and receipt positions plus allowed decisions/profile/timezone/F42 changes, cap inputs before enqueue, and generate a canonical input hash. Reuse unchanged watermark/job; advance and stale older jobs when inputs change.
- [x] **Step 5: Implement public, BFF and internal controllers** with member/viewer authorization, idempotent enqueue, service-token auth via `X-Analytics-Service-Token`, atomic leases/retry limits, result validation, and current-watermark write guard.
- [x] **Step 6: Run focused PostgreSQL tests;** member scope, viewer denial, overflow, retry, duplicate delivery, stale completion and Web BFF routes pass. No financial rows are written.
- [x] **Step 7: Run `:services:core:check` with PostgreSQL enabled** from repository root and `python -m pytest tools/contracts/test_core_migration.py tools/contracts/test_contracts.py -q`; Core check and F43 PostgreSQL cases pass; contracts pass 64 with 2 existing optional DB skips.
- [ ] **Step 8: Commit** as `feat(F43.2): add durable member analytics jobs` after focused and regression gates pass.

### Task 3: Existing Go analytics-api worker lifecycle and result delivery

**Files:**
- Create: `services/analytics-go/advice/f43_worker.go`
- Create: `services/analytics-go/advice/f43_worker_test.go`
- Create: `services/analytics-go/advice/f43_core_client.go`
- Create: `services/analytics-go/advice/f43_core_client_test.go`
- Modify: `services/analytics-go/cmd/analytics-api/main.go`
- Modify: `services/analytics-go/cmd/analytics-api/main.go` for worker configuration and lifecycle

**Interfaces:**
- `F43CoreClient` claims one Core job, submits `F43Request`, and reports typed result/error for a leased job using service authentication and bounded HTTP timeouts.
- `F43Worker.Run(ctx)` polls with configured delay, processes one lease at a time, honors shutdown, and relies on Core lease expiry/retry after process failure.

- [x] **Step 1: Write fake-Core worker tests** for no available work, successful claim/calculate/result, transient Core failure, invalid calculation delivery, cancellation during idle poll, and serialized processing in one worker instance.
- [x] **Step 2: Run the focused tests;** observed compile RED on missing `F43LeasedJob`, `F43JobResult`, and `NewF43CoreClient` symbols before implementation.
- [x] **Step 3: Implement the Core HTTP client and worker** with strict response decoding, service-token header, finite HTTP timeout, cancellable poll delay, and no direct database or ClickHouse writes.
- [x] **Step 4: Wire worker into existing `analytics-api` lifecycle** behind opt-in `ADVICE_ANALYTICS_WORKER_ENABLED`; enabled mode requires Core URL/token and validates poll configuration; process cancellation stops active requests.
- [x] **Step 5: Run focused worker tests;** Core lease policy owns retries and duplicate/stale writes are handled by Core.
- [x] **Step 6: Run `go test ./...` and `go vet ./...`;** all Go packages pass and vet reports no issues.
- [ ] **Step 7: Commit** as `feat(F43.3): process durable advice jobs` after focused and regression gates pass.

### Task 4: Web report, state polling, contracts and optional Telegram reuse

**Files:**
- Create: `apps/web/src/AdviceAnalyticsPanel.tsx`
- Create: `apps/web/src/AdviceAnalyticsPanel.test.tsx`
- Modify: `apps/web/src/api.ts`
- Modify: `apps/web/src/App.tsx` and `apps/web/src/styles.css`
- Modify: `contracts/openapi/finance-api-v1.yaml` and `contracts/parity/feature-parity.yaml`
- Modify Telegram gateway files/tests only if an existing report route can consume the same Core DTO without a second calculation.

**Interfaces:**
- Add typed `AdviceAnalyticsReport` and `AdviceAnalyticsJob` to Web API client with `getAdviceAnalytics(tenantId)` and `requestAdviceAnalytics(tenantId)`.
- Panel shows current watermark/status, explicit pending/processing/failed/stale states, completeness and localized unavailable reasons, the theoretical 30-day ceiling, weekly trend, and cadence observations with non-causal copy.

- [ ] **Step 1: Write component tests first** for loading/pending polling, complete result, partial result, unavailable metrics, failed retry, stale result not shown as current, explicit refresh, bilingual wording, and no “saved money”/causality claim.
- [ ] **Step 2: Run `pnpm --dir apps/web test -- AdviceAnalyticsPanel`;** observe missing panel/client behavior.
- [ ] **Step 3: Implement API client and panel**; poll only while job is pending/processing, stop on terminal state, refresh current status after explicit enqueue, and invalidate outdated display when watermark changes.
- [ ] **Step 4: Run focused Vitest tests;** every state is actionable and no old-watermark report appears as fresh.
- [ ] **Step 5: Add or finalize public OpenAPI schemas and analytics fixture parity**; test that public routes are member-authenticated and internal Go/worker routes are service-authenticated.
- [ ] **Step 6: Inspect the existing Telegram report API.** If it can render this same stored Core DTO without another computation, add a focused rendering test and reuse it; otherwise document the verified route limitation in `.agent/PROGRESS.md` and keep Web as the F43 client.
- [ ] **Step 7: Run full `pnpm --dir apps/web test`, `pnpm --dir apps/web build`, Python contract tests, `:services:core:check` with PostgreSQL, `go test ./...`, `go vet ./...`, and `git diff --check`;** all pass.
- [ ] **Step 8: Commit** as `feat(F43.4): expose advice analytics report` after all applicable gates pass; update parity and `.agent/PROGRESS.md` with evidence, skips, remote CI status and next global-plan goal.
