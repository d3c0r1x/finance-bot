# F59 Android parity and live API delivery

## Goal

Deliver the continuation-plan F59 requirement: Kotlin/Compose Android client with functional RU/EN parity for F01–F58, shared Java/Core API, installed emulator APK, and authenticated end-to-end proof.

## Acceptance

- Every F01–F58 registry item has a reviewable Android mapping: implemented or partial flow with real Android test references, missing work with a concrete gap, or a justified platform-specific N/A allowed by the continuation plan.
- Every user-facing feature has Android behavior. Operator-only/protocol-only work may stay outside Android only when documented and supported by `PLAN.md`/`CONTINUATION.md`.
- Android uses the same Java/Core API and server-owned financial rules as Web; no Android-side duplicate business calculations.
- Missing flows gain meaningful Compose/API tests before implementation. Tests cover RU/EN, loading/error/empty states, authorization, and exact money where relevant.
- Release APK builds, installs, and launches on an isolated Android 6–8 emulator. User-owned AVDs remain untouched.
- At least one real OIDC login and Java/Core financial operation succeeds end to end, with PostgreSQL-backed tenant isolation verified.

## Baseline

- Existing Compose screens and 50-test instrumentation suite cover several flows, but the registry currently references Android test files for only 21/58 F01–F58 rows.
- Prior registry evidence records only 20/20 instrumentation and explicitly says feature-by-feature mapping and live Core/OIDC E2E remain incomplete.
- First subgoal: add a registry contract test for explicit Android status/scenario/test mapping on every F01–F58 item. Observe RED before editing the registry.
- F59.1 completed locally: 58 rows mapped as 28 partial, 28 missing, and 2 justified developer/infrastructure N/A. A registry contract test validates each mapping and all referenced Android test paths. The missing list remains active acceptance work.
- F59.2 F08 transaction history slice: added a typed exact-value Android transaction model; history displays date, amount, member, category, description, and status; owners can repeat eligible posted transactions or void using the current version; viewers cannot mutate. Repeat posts a fresh transaction with a new idempotency key; debt payments cannot be repeated. F08 is now partial because filters/pagination and live Core E2E remain.
- F08 RED: new Compose acceptance tests initially failed to compile because the transaction model and action callbacks were absent. After implementation, API 27 instrumentation passed `OK (53 tests)`; APK installed and launched on isolated emulator `emulator-5558`, with `MainActivity` resumed (PID 4848). Debug APK SHA-256: `2E7ABEE23A394665471435666333E03DF0F5D2EA5B00C0B2FA961A65342A9301`. Python parity-contract tests: 3 passed. Android JVM unit test execution remains blocked by Gradle's `ClassNotFoundException` worker issue despite compiled test classes; it is not reported green.
- F59.2 F09 server-backed filter slice: Android now requests search, type, date range, and member filters from Core; member choices come from the authenticated members endpoint and use names/IDs with role-scoped options. Results append through the opaque cursor (50 per page), and refresh retains the active filters. Core groups expense with debt payments and income with refunds, and searches member display names without weakening tenant or role scope. Localized date validation rejects malformed ISO dates and reversed ranges before dispatch.
- F09 TDD evidence: Compose runtime tests first observed missing query controls, local-only behavior, missing empty/selection states, and then a reversed range reaching the callback. The last RED exposed stale Compose state captured by the click callback; validation now runs synchronously against current field values. Targeted reversed-range Android 8.1/API 27 test passed; full instrumentation regression passed 64/64. Core/PostgreSQL acceptance for grouped types and member-name search each observed RED then passed. Final gates: Core `:services:core:check` 290 tests (2 external-service skips); `TransactionApiPostgresTest` 136/136; feature registry contract test 1 passed.
- F59.2 F09 transaction edit slice: owner/admin can edit posted family operations; members can edit their own only; viewers cannot edit. The editor preserves ID, version, currency, source, account, debt, and owner fields; changes type, amount, category/subcategory, description, and tenant-local date. PATCH sends the full Core DTO, quoted `If-Match`, and a retry-stable idempotency key. Stale-version and permission errors are localized; dates are validated against tenant time zone and Core text limits before Save.
- F09 edit TDD: missing owner edit action was observed as runtime RED; callback test verifies exact field values and version. Further REDs caught member-local date prefill at a UTC boundary and category length 65 still enabling Save. After fixes, `FinanceScreensTest` passed 38/38 and full isolated API 27 instrumentation passed 69/69. Existing Core/PostgreSQL acceptance covers owner family edit, exact values, stale 412, history, and viewer denial. Remaining Android F09 gaps: owner/admin reassignment and authenticated Android-to-Core E2E.
- F09 owner/admin reassignment slice: an owner/admin selects an active member by display name; ordinary members have no owner selector and retain their own owner ID. RED: owner picker tag absent; GREEN: owner reassignment, admin reassignment, member self-vs-other permission tests each passed, followed by full API 27 instrumentation 71/71. Android F09 local flows are implemented; keep parity partial until authenticated Android-to-Core E2E is proven.
- F10.1 design approved by the user: a dedicated Android receipt entry uses the API-23-compatible system JPEG/PNG picker, existing authenticated Core multipart photo-job endpoint and idempotency key, bounded OCR progress polling, and opens the resulting receipt draft. This slice must not post a transaction; receipt item editing, duplicate decisions, and confirmation follow F15-F18. Tests use synthetic image fixtures only; viewer remains read-only; RU/EN strings and statuses are required.

## Execution slices

1. **F59.1 Parity inventory:** test and fill the mapping for all 58 feature IDs; distinguish implemented, partial, missing, and justified N/A with concrete evidence.
2. **F59.2 Core user flows:** continue with profile, receipt review/upload, and import paths in priority order, keeping all money and authorization rules in Core. F08 history/repeat/void and F09 server-backed filters/pagination plus role-scoped edit/reassignment are partial and locally verified; live Android-to-Core E2E remains. F10.1 now has a locally verified receipt photo upload, OCR job progress, and draft display without auto-posting; Android F10 remains partial pending live Core E2E, while receipt item editing, duplicate decisions, and confirmation remain in F15-F18.
3. **F59.3 Analytics and lifecycle:** close budgets, debts, reports, price/shopping, recurring, waste, advice, goals, digest, export, and settings gaps with RU/EN UI and API tests.
4. **F59.4 OIDC/Core E2E:** validate PKCE/session lifecycle and a real authorized financial read/write against Java/PostgreSQL.
5. **F59.5 Release proof:** build APK, install and launch on isolated Android 6–8 emulator, run complete instrumentation, record artifact hash and runtime evidence.

Do not mark F59 complete while any required mapping is missing/partial, live OIDC/Core E2E is unverified, or APK installation evidence is absent.
