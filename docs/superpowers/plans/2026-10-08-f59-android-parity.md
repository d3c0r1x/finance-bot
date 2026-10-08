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

## Execution slices

1. **F59.1 Parity inventory:** test and fill the mapping for all 58 feature IDs; distinguish implemented, partial, missing, and justified N/A with concrete evidence.
2. **F59.2 Core user flows:** close missing transaction, profile, receipt review/upload, and import paths in priority order, keeping all money and authorization rules in Core.
3. **F59.3 Analytics and lifecycle:** close budgets, debts, reports, price/shopping, recurring, waste, advice, goals, digest, export, and settings gaps with RU/EN UI and API tests.
4. **F59.4 OIDC/Core E2E:** validate PKCE/session lifecycle and a real authorized financial read/write against Java/PostgreSQL.
5. **F59.5 Release proof:** build APK, install and launch on isolated Android 6–8 emulator, run complete instrumentation, record artifact hash and runtime evidence.

Do not mark F59 complete while any required mapping is missing/partial, live OIDC/Core E2E is unverified, or APK installation evidence is absent.
