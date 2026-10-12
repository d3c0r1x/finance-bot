# Android + Telegram Friends MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (preferred) or superpowers:executing-plans. Steps use checkbox syntax for tracking.

**Goal:** Deliver a free friends MVP in Russia with full Android and Telegram finance flows, shared accounts, empty production database, and Gemini AI that fails clearly when unavailable.

**Architecture:** Keep Java Core and PostgreSQL authoritative for financial data and calculations. Android and Telegram call the same backend; Python owns Telegram transport and the AI provider boundary. Use the current code and parity registry, avoid new infrastructure without a measured need.

**Tech Stack:** Kotlin/Jetpack Compose, Java/Spring Boot, PostgreSQL, Keycloak/OIDC, Python/aiogram/FastAPI, Gemini API, current Go analytics worker only where required by existing product features.

**Spec:** `docs/specs/ANDROID_TELEGRAM_SAAS_ROADMAP.md`

## Global Constraints

- Do not ship a user-facing Web UI. Keep backend APIs and OpenAPI contracts.
- Use an empty production database. Use synthetic financial fixtures only in development and tests.
- Android is self-registration after manual APK distribution. Do not require invite codes.
- Telegram requires server-configured allowed IDs and resolves each allowed ID to one canonical account without user-entered codes.
- The same person sees the same financial profile and data through Android and Telegram.
- Core owns financial calculations, access control, and mutations.
- Gemini credentials stay on server. Never send full account history when a task needs only aggregates.
- Per-model quota may trigger one compatible fallback. If no model is available, return a localized temporary-unavailable state; manual finance stays usable.
- Primary user database, files, and backups target Russian-region hosting. Confirm legal/data-transfer gates before live provider calls.
- Preserve current unrelated modifications and user-owned untracked paths.
- Keep existing APIs compatible unless a task explicitly replaces a Web-only flow.

## Review Focus

- Unlisted Telegram ID, account-map collision, deleted binding, and stale actor context must deny access before reading finance data.
- Two simultaneous requests for one user must not create duplicate transactions, consent records, or AI billing reservations.
- Gemini 429, depleted project balance, malformed JSON, timeout, and 5xx must not create or confirm a financial mutation.
- App reinstall, expired OIDC session, denied consent, and email-verification failure must have usable recovery states.
- Empty database and missing files must produce real empty states, not synthetic balances or demo data.

---

### Task 1: Make the new product direction authoritative

**Files:**
- Modify: `PLAN.md`
- Modify: `docs/specs/CONTINUATION.md`
- Modify: `docs/specs/PERSONAL_MVP_PLAN.md`
- Modify: `.agent/PROGRESS.md`
- Create: `docs/specs/ANDROID_TELEGRAM_SAAS_ROADMAP.md`
- Create: `docs/superpowers/plans/2026-10-12-android-telegram-mvp.md`
- Test: `tests/test_f59_android_parity.py`, `contracts/parity/feature-parity.yaml`

**Acceptance:** All new requirements in the roadmap are explicit; old Web UI, friend access, data location, AI, and empty-database statements are clearly superseded; current status is not marked complete without evidence.

- [x] Record exact user decisions and current repository snapshot.
- [x] Remove Web UI from product acceptance wording while keeping API/OpenAPI requirements.
- [x] Confirm every F01–F58 row maps to Android and Telegram or justified platform-specific N/A.
- [x] Read the document diff and fix contradictory or stale statements.

### Task 2: Add shared profile mapping for Telegram allowlist

**Files:**
- Modify: `services/python/telegram_gateway/app.py`
- Modify: `services/python/telegram_gateway/core_client.py`
- Modify: `services/core/src/main/java/com/decorix/finance/core/api/TelegramActorContextController.java`
- Modify: `services/core/src/main/java/com/decorix/finance/core/api/TelegramActorContextService.java`
- Modify: Core account, tenant, and membership provisioning/identity services as required by Telegram-native profiles
- Modify: Android `FinanceApi.kt`, models, and profile UI to remove user-entered link codes
- Modify: `services/core/src/main/resources/db/migration/V19__telegram_one_time_link_codes.sql` only through a new forward migration; never edit an applied migration
- Modify: `services/core/src/main/resources/application.yml`
- Test: existing Telegram gateway tests and Core PostgreSQL Telegram-link tests

**Acceptance:** Only configured Telegram IDs can reach Core. Each allowed ID resolves to one stable canonical Core user and personal tenant, or an explicitly configured existing Android account. Telegram-only users receive a real Telegram-native principal; later Android registration can attach a verified OIDC subject to the same `users.id` through a trusted operator action. Financial history stays shared after attachment. Unknown IDs, conflicting mappings, inactive users, and revoked bindings are denied. No bot command or Android screen asks the user for an invite/link code. Do not fake a Keycloak subject or auto-merge by email/name. Remove old code issuance/redeem endpoints only after all callers are removed and forward migration behavior is verified.

- [ ] Add failing config tests for allow, deny, duplicate IDs, malformed IDs, and missing canonical account.
- [ ] Add Core tests for one-time Telegram-native provisioning, mapping conflict, disabled user, cross-tenant denial, revocation, shared-account read/write, and verified OIDC attachment to the same user.
- [ ] Implement server-only Telegram allowlist and operator mapping/provisioning. Keep Telegram-native principal explicit and secrets/config outside Git.
- [ ] Update `/start` behavior and remove user-facing code redemption.
- [ ] Verify that allowlist changes and account deletion revoke access without waiting for an old actor context.

### Task 3: Complete Android self-registration and account recovery

**Files:**
- Modify: `apps/android/app/src/main/java/com/decorix/finance/MainActivity.kt`
- Modify: `apps/android/app/src/main/java/com/decorix/finance/FinanceApi.kt`
- Modify: `ops/keycloak/realm-finance.json`
- Modify: `infra/compose/compose.yml`
- Test: Android authentication UI/unit tests and Keycloak/Core integration tests

**Acceptance:** A clean APK user can register, verify email, login, recover password, logout, and login again. A denied or unverified account cannot call finance APIs. An existing Telegram-native profile can receive the verified OIDC identity through the trusted operator attachment flow before Android provisions a second account; Core keeps the same `users.id` and financial history. Release builds use HTTPS and no fixed local credentials.

- [ ] Add failing tests for register entry, verify-pending state, canceled login, expired token, logout, and attachment to an existing Telegram-native user.
- [ ] Configure supported self-registration and email recovery without invite codes.
- [ ] Implement localized Android recovery states without storing access tokens outside the encrypted token vault.
- [ ] Configure a real mail sender through environment-backed secrets; do not log verification links.

### Task 4: Implement Gemini provider and quota-aware routing

**Files:**
- Modify: `services/python/intelligence/gateway.py`
- Modify: `services/python/intelligence/app.py`
- Modify: `services/python/intelligence/tests/test_gateway.py`
- Modify: `services/python/intelligence/tests/test_app.py`
- Modify: `contracts/openapi/finance-intelligence-v1.yaml`
- Modify: `docs/AI_MODELS.md`
- Modify: `contracts/parity/feature-parity.yaml`
- Test: `services/python/intelligence/tests/test_gateway.py`, `services/python/intelligence/tests/test_app.py`, synthetic evaluation contract

**Acceptance:** Gemini provider uses server-side credentials, typed capability routing, schema validation, model provenance, bounded per-model rate limits, one compatible fallback, and a stable temporary-unavailable result. No live credential is required for unit tests. No request is sent to unpaid Gemini with personal/financial data. No AI response can directly mutate finance.

- [ ] Add mock-transport RED cases for text, image, structured output, 429, exhausted project balance, 5xx, timeout, invalid JSON, and no capable model.
- [ ] Add task classes and route simple/vision/complex work only to configured models with required capabilities.
- [ ] Limit fallback count and total deadline; do not retry the same request across every model.
- [ ] Persist only task ID, provider/model, result status, usage when available, latency, and safe error class; never persist raw prompt/image in telemetry.
- [ ] Return the stable unavailable result when all allowed routes fail.
- [ ] Enable real model traffic only after official account/region/terms, paid data policy, and Russian cross-border legal gates are confirmed.

### Task 5: Show Gemini outage state in Android and Telegram

**Files:**
- Modify: `apps/android/app/src/main/java/com/decorix/finance/MainActivity.kt`
- Modify: `apps/android/app/src/main/java/com/decorix/finance/FinanceApi.kt`
- Modify: `services/python/telegram_gateway/app.py`
- Modify: shared presentation/error contracts as needed
- Test: Android AI screen tests, Telegram AI handler tests, OpenAPI contract

**Acceptance:** Provider outage and quota exhaustion show “Временно недоступно” in Russian and equivalent English copy. Manual transaction, receipt correction, and ordinary finance screens remain usable. Vendor messages and internal codes never appear in user output.

- [ ] Add failing client cases for quota exhaustion, all-model outage, retry button, and manual fallback.
- [ ] Implement one shared semantic status and localize only at Android/Telegram presentation boundaries.
- [ ] Ensure retry is explicit and bounded; do not silently duplicate provider calls.

### Task 6: Retire user-facing Web client

**Files:**
- Delete: `apps/web/**` after API/OpenAPI and backend ownership checks
- Modify: `.github/workflows/**` to remove Web UI-only build/test jobs and retain API contract checks
- Modify: root docs and `README.md`
- Modify: Core Web BFF/session routes only after proving no Android/Telegram caller needs them
- Test: backend/API contract suite and build workflow configuration checks

**Acceptance:** No user-facing React/Vite client or Web-only product route remains. Finance API, OIDC for Android, Telegram, OpenAPI, backend health, and security gates remain functional.

- [x] Identify Web-only endpoints, dependencies, workflow steps, and docs; retain shared API behavior.
- [x] Add failing architecture/contract checks that prevent Web UI references from re-entering product acceptance.
- [x] Remove the user-facing Web client and obsolete UI-only backend code with narrow commits.
- [x] Search the repository for stale user-facing Web requirements and update each live document.

### Task 7: Finish Android and Telegram feature parity

**Files:**
- Modify: `apps/android/app/src/main/java/com/decorix/finance/MainActivity.kt` and focused Android modules
- Modify: `services/python/telegram_gateway/app.py` and focused Telegram handlers
- Modify: `contracts/parity/feature-parity.yaml`
- Test: `tests/test_f59_android_parity.py`, Android JVM/instrumentation, Telegram tests, Core API contracts

**Acceptance:** Every F01–F58 financial workflow has complete acceptance on Android and Telegram. Server-authoritative outputs match across clients. Platform-only N/A is documented. All local recorded changes are preserved.

- [ ] Refresh the parity baseline from current source, not historical status rows.
- [ ] Group incomplete rows into vertical slices by user workflow: onboarding/profile, transactions/history, receipts, imports, budgets/debts, reports, shopping/goals, export/settings.
- [ ] For each slice, write behavior tests first, observe RED, implement the smallest flow, then run its regression gates.
- [ ] Keep the parity registry updated with real commands, results, and remaining live-service dependencies.

### Task 8: Deploy the minimum complete Russian beta runtime

**Files:**
- Modify: `infra/compose/compose.yml`
- Modify: `infra/compose/README.md`
- Modify: `services/core/src/main/resources/application.yml`
- Modify: deployment/run scripts under `ops/`
- Modify: `.env.example` without real secrets
- Test: Compose config, service readiness, persistence/restart, HTTPS, and restore checks

**Acceptance:** Core, PostgreSQL, Keycloak, Telegram Gateway, Intelligence Gateway, private object storage, and mail delivery run with production-safe settings. Persistent data and backups use a Russian region. Restart and restore preserve authorized data.

- [ ] Document required services and remove `start-dev`, fixed secrets, and insecure cookie defaults from production profile.
- [ ] Add service health/readiness and persistent volumes; use a private S3-compatible store for receipt media and exports.
- [ ] Add deployment checks for RU data location, HTTPS-only API, backups, restart, and log redaction.
- [ ] Keep Kafka/ClickHouse and extra services out of the MVP deployment unless a required feature cannot run without them; preserve existing behavior and add only proved dependencies.

### Task 9: Prepare and accept legal documents

**Files:**
- Create: `docs/legal/privacy-policy-ru.md`
- Create: `docs/legal/user-agreement-ru.md`
- Create: `docs/legal/gemini-data-processing-ru.md`
- Modify: Keycloak/Android consent capture and audit API
- Test: consent versioning, denied consent, export/delete, and re-consent after document update

**Acceptance:** Documents list operator, purposes, data categories, retention, deletion, providers, data location, and support contacts. Registration records exact policy versions. Real operator details are filled before friend onboarding.

- [ ] Draft plain-language Russian texts using verified product behavior and provider terms.
- [ ] Add versioned acceptance records and block only the processing that requires denied consent.
- [ ] Verify deletion and export across PostgreSQL, receipt storage, backups, and Gemini request logs where applicable.
- [ ] Mark legal review as an explicit production gate; do not present drafts as legal advice.

### Task 10: Pass friends MVP release gate

**Files:**
- Modify: `.agent/PROGRESS.md`
- Modify: release manifest and install guide
- Test: clean-install APK, registration, Android/TG shared data, two-user isolation, backup/restore, AI outage, and account deletion

**Acceptance:** Owner plus two synthetic friend accounts complete full Android and Telegram scenarios. Production database starts empty. No local address, test credential, synthetic transaction, or private sample enters release artifacts. Gemini is marked active only if Task 4's official availability and legal gates pass; otherwise AI visibly reports temporary unavailability.

- [ ] Build and install the release APK on supported Android versions.
- [ ] Verify registered users and configured Telegram IDs against one shared Core account each.
- [ ] Run full two-user isolation and persistence/restart walkthrough.
- [ ] Record artifact hash, deployment revision, checks, provider state, and known limitations.

### Task 11: Complete commercial SaaS after free MVP

**Files:**
- Modify: `services/core` billing, usage, plans, webhook and operator API modules
- Modify: Android account/plan screens only where needed; no Web billing UI
- Modify: deployment and operator documentation
- Test: sandbox/live payment lifecycle, quotas, usage, cancellation, refunds, and reconciliation

**Acceptance:** Plans, pricing, payment, support, and legal operator are selected. Payment webhooks are authenticated and idempotent. Quota usage is server-authoritative. Users retain/export data after downgrade or cancellation.

- [ ] Measure actual hosting, file storage, and Gemini costs from the friends beta.
- [ ] Define subscription and AI/storage quota policy from observed cost.
- [ ] Implement payment-provider sandbox before live billing.
- [ ] Pass restore, refund, cancellation, and reconciliation checks before paid pilot.
