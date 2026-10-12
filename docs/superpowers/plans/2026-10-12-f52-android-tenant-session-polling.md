# F52 Android Tenant and Session Polling — Bounded Plan

**Authority:** Approved V1 `PLAN.md` F52, `.agent/specs/F52-android-csv-export.md` Task 4, and the existing F52 Android execution plan.

**Goal:** Stop an Android export status poll when its tenant or authenticated session changes, and reject responses from the old tenant or session.

**Scope:** Android export polling only. Preserve current Core API and authorization. Do not change Web, Core, worker, S3, auth configuration, or CSV behavior.

**Files:** `FinanceExportsScreensTest.kt` proves cancellation of the pending poll; `FinanceExportStatusLifecyclePolicyTest.kt` proves stale tenant/session responses are rejected; the smallest production export Compose/policy files carry identity through the poll lifecycle. Update F52 parity and `.agent/PROGRESS.md` after GREEN.

## Acceptance

1. A pending poll is cancelled after tenant or session identity changes; the old poll makes no status callback after cancellation.
2. A status response is eligible only when request generation, screen lifecycle, tenant ID, and session identity all match current state.
3. A matching active response remains eligible; existing lifecycle and polling behavior remains unchanged.
4. A stale status completion may clear only its still-current status busy indicator; it never publishes its old job, error, or download URL.
5. Focused tests show compile-time RED before implementation, then GREEN on the isolated API-27 emulator and JVM. Relevant Android regressions, parity contracts, build, and `git diff --check` pass.
6. Documentation says this is local lifecycle evidence, not live OIDC/Core proof.

## TDD steps

1. Add policy tests for same identity, old tenant, and old session; run focused JVM test and record expected RED.
2. Add Compose test with a suspended delay and change tenant/session context; assert cancellation and no stale refresh; run focused instrumentation and record expected RED.
3. Implement identity-bound effect keys and policy comparison with minimal production changes.
4. Run focused tests, affected screen/auth regression, Android JVM suite, parity/contract suite, debug and AndroidTest builds, then `git diff --check`.
5. Update parity and progress with exact evidence and limits. Review diff, commit scoped changes, push under the user's existing authorization, and verify remote SHA.

**Stop condition:** All local acceptance above is GREEN. Live Android OIDC/Core, real roles, Core export worker/S3, browser transfer, and bytes remain separate gates.

## Execution status — 2026-10-12

- Test-first RED was observed as compile failure because the baseline lacked tenant/session identity in the status request and Compose poll key; this was compile RED, not an executed failing behavioral assertion.
- Implemented identity-bound polling and stale-response policy. A stale completion clears busy only while it still owns the matching status request; it cannot publish old status data, errors, or URL.
- RED was compile-time only; no pre-implementation behavioral assertion failure was observed.
- Compose tenant/session cancellation test passed 1/1; complete `FinanceExportsScreensTest` passed 18/18 on isolated Android 8.1/API 27 `CodexFinanceBotApi27`.
- Combined F52 model/API/screens/flow instrumentation passed 47/47. Android JVM suite passed 91/91. F59 parity tests passed 7/7; contract suite passed 63/63; debug and AndroidTest APK assembly passed; `git diff --check` passed.
- MainActivity passes tenant/session identity to Compose and rejects stale status completion. No focused test yet delays MainActivity's actual HTTP response across identity replacement; this remains a local evidence gap.
- Android 8 host-GL aborted the existing Compose suite in RenderThread (`OpenGLPipeline.cpp:105`). Restarting only the isolated AVD with temporary SwiftShader made the unchanged screen test pass. No persistent AVD or Windows memory/graphics setting changed.
- The original identity-bound polling slice passed its local acceptance. The later MainActivity completion-policy follow-up passed JVM and APK-build gates, but its repeated API-27 UI run stalled before completion; do not treat that rerun as GREEN. F52 Android remains PARTIAL.
- The delayed-response regression tests the pure decision MainActivity calls. A delayed MainActivity HTTP response integration test remains unverified, as do live Android OIDC/Core, worker/S3, system resolver, browser transfer, downloaded bytes, and elapsed wall-clock cadence.
- Commit/push: `c42eb07654bec625911e0b86f3696a91e847f65f` (`fix(F52): bind Android export polling to tenant session`) pushed to `origin/feat/saas-rewrite`; remote SHA was verified.

## Follow-up — MainActivity stale completion decision

- Added a pure completion decision shared by `MainActivity` success and failure handlers. It gates status/error publication, signed-URL opening, and busy clearing against request generation, tenant, session, screen, authentication, and export identity. A stale ready response cannot publish or open its URL; busy clearing is allowed only while that request still owns the status busy state.
- Added a behavioral unit test for a delayed `ready` response from a prior tenant/session and older request while a newer status request owns busy state. It asserts no publication, no URL opening, and no busy clearing.
- Test-first RED was observed: the new test failed compilation because the completion decision did not yet exist. Focused JVM test passed through a temporary ASCII junction after direct execution from the Cyrillic workspace hit the known `ClassNotFoundException` path issue.
- API-27 `FinanceExportsScreensTest` instrumentation began on `CodexFinanceBotApi27` (Android 8.1) and reported 14/18, 0 skipped, 0 failed, then stopped producing output while Gradle remained active. ADB showed no instrumentation runner, app process, ANR, crash, or recent runner log. Treat this follow-up's device regression as **UNVERIFIED**, not GREEN.
- The same-run APK build then passed for debug and AndroidTest variants. No emulator test result is inferred from the successful build.
- Live delayed HTTP-response integration across tenant/session replacement remains unverified; this follow-up tests the pure decision used directly by MainActivity. Live OIDC/Core, worker/S3, system resolver, browser transfer, downloaded bytes, and elapsed wall-clock cadence remain unverified.
