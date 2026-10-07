# F46 Goal Outcome Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver one pending completed goal outcome inside a weekly Telegram digest and mark it announced only after Telegram confirms success.

**Architecture:** Core associates at most one oldest unannounced completed outcome with a leased weekly `notification_intent`. Retries keep the same association; successful acknowledgement marks it announced, while terminal non-success releases it for a later weekly intent. Telegram gateway validates and renders the immutable outcome snapshot. A lost acknowledgement can cause a duplicate external message.

**Tech Stack:** Spring Boot, PostgreSQL/Flyway, Jackson, Python 3, aiogram, OpenAPI, pytest, JUnit/PostgreSQL integration tests.

**Spec:** `docs/superpowers/specs/2026-10-07-f46-goal-outcome-delivery-design.md`

## Global Constraints

- Only `origin='completed'` rows are eligible; imported `origin='legacy'` rows are never sent again.
- Daily notification claims have no goal outcome. Weekly claims attach at most one oldest eligible outcome.
- An outcome stays unannounced until Core records `outcome='delivered'` with a valid active lease.
- Retryable and expired leases keep the same outcome attached to the same intent.
- A terminal non-success releases the association; it never sets `announced_at`.
- Empty financial reports still produce an outcome message; ordinary empty daily/weekly reports remain suppressed.
- If Telegram accepted a message but Core did not record the acknowledgement, retry may send a duplicate. Do not claim exactly-once external delivery.
- Weekly preferences stop future weekly intents; already queued intents keep existing delivery semantics. Members without Telegram identity keep outcomes pending until they link an identity.
- Add no dependencies or notification preference changes.

## Review Focus

- Multiple pending, cancelled, or imported outcomes: attach only the oldest eligible completed row. Test oldest-first and legacy/cancelled exclusion in `TransactionApiPostgresTest`.
- Concurrent/overdue weekly claims: one outcome cannot attach to two intents. Test claim association and the unique constraint against PostgreSQL.
- Retry, lease expiry, and terminal failure: retries retain association; terminal non-success releases it without announcement. Test retry, delivery, and release transitions in `TransactionApiPostgresTest`.
- No financial rows in report: the weekly outcome still sends; no-outcome empty digest still does not. Test in `test_digest.py` and `test_digest_worker.py`.
- Unknown sum outcome: render as unknown, never zero; reject malformed outcome payloads. Test renderer and Core client validation.

---

### Task 1: Core weekly claim association

**Files:**
- Create: `services/core/src/main/resources/db/migration/V41__goal_outcome_delivery_link.sql`
- Modify: `services/core/src/main/java/com/decorix/finance/core/api/NotificationDeliveryApi.java`
- Modify: `services/core/src/main/java/com/decorix/finance/core/api/NotificationDeliveryService.java`
- Test: `services/core/src/test/java/com/decorix/finance/core/api/TransactionApiPostgresTest.java`
- Test: `tools/contracts/test_core_migration.py`

**Interfaces:**
- Produce `GoalOutcomeMessage(UUID id, String name, String unit, int bought, int countTarget, String spent, String monthlyLimit, Boolean met, Instant completedAt)` in `NotificationDeliveryApi`.
- Extend `DeliveryClaim` with nullable `GoalOutcomeMessage goalOutcome`.
- Extend `ClaimRow` with nullable `GoalOutcomeMessage goalOutcome` after Core leases each notification intent.
- Add nullable `goal_outcomes.announcement_intent_id` FK to `notification_intents`, with `ON DELETE SET NULL`; partial unique index prevents two outcomes from attaching to one intent; add an index for oldest unannounced completed outcomes.

- [x] **Step 1: Write PostgreSQL claim test** `notificationDeliveryClaimAttachesOnlyOldestCompletedOutcomeToWeeklyIntent`
  - Insert two unannounced completed outcomes, one cancelled goal without outcome, and one legacy outcome for the test member.
  - Claim one due weekly intent and assert response has the oldest completed outcome snapshot.
  - Assert database links that outcome to the intent; other completed and legacy rows remain unannounced and unlinked.
  - Claim a daily intent and assert `goalOutcome` is null.
- [x] **Step 1b: Write concurrent-claim test** `concurrentWeeklyClaimsAttachDistinctGoalOutcomes`
  - Create two due weekly intents and two pending completed outcomes for one member.
  - Issue two concurrent claims with limit `1`; assert distinct intent IDs and distinct outcome IDs.
  - Assert neither outcome is associated with more than one intent.
- [x] **Step 2: Run the focused test and observe RED**
  - Run both focused PostgreSQL claim tests with `.\gradlew.bat :services:core:test --tests '*TransactionApiPostgresTest.notificationDeliveryClaim*' --no-daemon`.
  - Use the isolated PostgreSQL test database from `.agent/PROGRESS.md`.
  - Expected: fail because V41 and the weekly claim payload/link do not exist.
- [x] **Step 3: Implement V41 and claim association**
  - Select oldest eligible row by `(completed_at, id)` with `FOR UPDATE SKIP LOCKED`.
  - Associate it with the newly leased weekly intent in the claim transaction.
  - On retry, return the row already associated with the same intent; do not attach a second row.
  - Return null for daily claims and when no completed outcome is pending.
- [x] **Step 4: Run focused Core test and migration checks**
  - Run the focused Gradle test; expected PASS.
  - Run: `.venv\Scripts\python.exe -m pytest tools/contracts/test_core_migration.py -q -p no:cacheprovider`; expected PASS.
- [x] **Step 5: Commit Task 1**
  - Commit migration, Core DTO/service, acceptance test, migration check, and plan state as `feat(F46.1): attach outcomes to weekly claims`.

### Task 2: Core delivery acknowledgement lifecycle

**Files:**
- Modify: `services/core/src/main/java/com/decorix/finance/core/api/NotificationDeliveryService.java`
- Test: `services/core/src/test/java/com/decorix/finance/core/api/TransactionApiPostgresTest.java`

**Interfaces:**
- Reuse `acknowledge(UUID intentId, DeliveryRequest request)` and its existing state transitions.
- On `delivered`, set `goal_outcomes.announced_at=now()` and clear `announcement_intent_id` for the acknowledged intent in the same transaction.
- On retryable failure below attempt limit, preserve the association.
- On `no_data`, permanent failure, or exhausted retries, clear the association and leave `announced_at` null.

- [x] **Step 1: Write acknowledgement lifecycle tests**
  - `successfulWeeklyDeliveryMarksAttachedOutcomeAnnouncedOnce`: a valid lease and `delivered` set `announced_at`; duplicate acknowledgement is rejected and does not rewrite it.
  - `retryableDeliveryKeepsAttachedOutcomeForSameIntent`: retry leaves it unannounced and attached; next claim returns the same outcome.
  - `terminalDeliveryFailureReleasesOutcomeForNextWeeklyIntent`: terminal `permanent_failure` and `no_data` leave it unannounced and unlinked; a later weekly intent can claim it.
  - `exhaustedExpiredLeaseReleasesAttachedOutcome`: exhausting lease attempts leaves outcome available for later delivery.
- [x] **Step 2: Run focused tests and observe RED**
  - Run the four test methods in `TransactionApiPostgresTest` with the isolated PostgreSQL test database.
  - Expected: announcement/link columns do not transition with acknowledgement.
- [x] **Step 3: Implement acknowledgement transitions**
  - Apply outcome updates inside the same transaction as the existing delivery attempt and intent updates.
  - Release attached outcomes whenever the intent reaches `skipped_no_data` or `failed`, including lease exhaustion.
  - Preserve association for `pending` retry state.
- [x] **Step 4: Run focused lifecycle tests**
  - Expected: all four tests PASS; regular notification retry tests remain PASS.
- [ ] **Step 5: Commit Task 2**
  - Commit service and PostgreSQL tests as `feat(F46.2): mark goal outcome delivery`.

### Task 3: Telegram validation and localized rendering

**Files:**
- Modify: `services/python/telegram_gateway/core_client.py`
- Modify: `services/python/telegram_gateway/digest.py`
- Modify: `services/python/telegram_gateway/digest_worker.py`
- Test: `services/python/telegram_gateway/tests/test_core_client.py`
- Test: `services/python/telegram_gateway/tests/test_digest.py`
- Test: `services/python/telegram_gateway/tests/test_digest_worker.py`

**Interfaces:**
- Change renderer to `render_digest(report, kind, language, goal_outcome=None) -> str | None`.
- `TelegramCoreClient.claim_notification_deliveries()` accepts `goalOutcome: null | GoalOutcomeMessage`; reject it for `daily` claims and reject malformed fields/money.
- Worker passes the validated outcome to `render_digest`; it reports `no_data` only when both financial report and outcome produce no text.

- [x] **Step 1: Write renderer tests**
  - Add RU and EN tests for count outcomes and sum outcomes.
  - Add unknown sum test that renders an explicit unknown label and contains no zero amount.
  - Add an empty-report plus outcome test; expect outcome text. Keep existing empty-report/no-outcome suppression test unchanged.
  - Add daily-with-outcome rejection test.
- [x] **Step 2: Run renderer tests and observe RED**
  - Run from repository root: `.venv\Scripts\python.exe -m pytest services/python/telegram_gateway/tests/test_digest.py -q -p no:cacheprovider`
  - Expected: current renderer has no `goal_outcome` argument.
- [x] **Step 3: Write Core client and worker tests**
  - Validate a well-formed weekly `goalOutcome`, null outcome, and malformed UUID/unit/money/Boolean payloads.
  - Verify an empty report with an outcome sends one Telegram message and acknowledges `delivered`.
  - Verify a lost acknowledgement leaves the same claim payload available for retry; worker does not mark success locally.
- [x] **Step 4: Run gateway tests and observe RED**
  - Run from repository root: `.venv\Scripts\python.exe -m pytest services/python/telegram_gateway/tests/test_core_client.py services/python/telegram_gateway/tests/test_digest_worker.py -q -p no:cacheprovider`
  - Expected: claim validation rejects new payload and worker omits outcome text.
- [x] **Step 5: Implement validation, rendering, and worker integration**
  - Keep the outcome snapshot bounded and render RU/EN without changing ordinary daily digest text.
  - Include an outcome heading when the Core report has no transactions.
- [x] **Step 6: Run gateway tests**
  - Expected: all focused tests PASS; existing gateway tests remain PASS.
- [x] **Step 7: Commit Task 3**
  - Commit client, renderer, worker, and tests as `feat(F46.3): render outcomes in weekly digest`.

### Task 4: Contract, regression, and F46 completion

**Files:**
- Modify: `contracts/openapi/finance-api-v1.yaml`
- Test: `tools/contracts/test_contracts.py`
- Modify: `.agent/PROGRESS.md`
- Modify: `docs/superpowers/plans/2026-10-07-f46-goal-outcome-delivery.md`

- [x] **Step 1: Add contract assertions first**
  - Require nullable `goalOutcome` on `NotificationDeliveryClaim`.
  - Validate required goal fields, enum units, nullable exact money, nullable `met`, and forbid a non-null goal outcome on daily claims.
- [x] **Step 2: Run contract tests and observe RED**
  - Run: `.venv\Scripts\python.exe -m pytest tools/contracts/test_contracts.py -q -p no:cacheprovider`
  - Expected: missing `goalOutcome` schema/constraints fail.
- [x] **Step 3: Update OpenAPI schema**
  - Document the claim field, weekly-only rule, successful acknowledgement marker, and duplicate risk after lost acknowledgement.
- [x] **Step 4: Run all regression gates**
  - Core: `:services:core:check` with isolated PostgreSQL.
  - Telegram gateway: full pytest suite.
  - Contracts/migrations: `test_contracts.py` and `test_core_migration.py`.
  - Go: `go test ./... -count=1` and `go vet ./...`.
  - Web: full test suite and production build.
  - `git diff --check`.
- [x] **Step 5: Review diff and commit contract/progress**
  - Stage only F46 files; preserve unrelated untracked user data.
  - Commit as `feat(F46): deliver goal outcome in weekly digest`.
  - Record actual test counts, commit SHA, and remote CI status in `.agent/PROGRESS.md`.
- [x] **Step 6: Mark F46 complete only after all gates pass**
  - Confirm retry/terminal state semantics, localized rendering, contract, and external-delivery limitation match the approved spec.

## Execution Method

Implement natively in this session. User explicitly requires strict test-first RED/GREEN and repository instructions require plan-driven TDD. Do not delegate or begin implementation until the user approves this plan.
