# F48 Android import selection and confirmation plan

Goal: implement the approved Android F48 selection and explicit import confirmation on top of F47, with Core-owned state and totals.

1. **Contract audit.** Follow `.agent/specs/F48-android-import-confirmation.md`; use OpenAPI, Core and Web as authorities. Keep F49 dedupe/undo and F50/F51 classification out of scope. Resolve Core/OpenAPI `transfer_in` drift.
2. **Acceptance and observed RED.** Add separate API/Compose suites and a contract regression. Run each before production edits; record failures tied to absent Android F48 behavior and the stale contract enum.
3. **Models/API.** Extend strict DTOs with server revision/state/aggregate fields and stable row UUIDs. Add PATCH selection and non-replayed explicit confirm calls with `If-Match`, deterministic per-import idempotency key, and captured auth generation.
4. **UI/state.** Render Core aggregates and included/excluded reasons; expose row type selection and reset only while editable; serialize mutations and replace state only from Core responses. Add explicit confirmation and committed status, viewer/state/pending guards, 412 refresh, and ambiguous-outcome GET reconciliation. No undo or local calculation.
5. **RED/GREEN and regression.** Run focused JVM/API/UI tests, existing F47 + relevant API 27 Android regressions on isolated `emulator-5554`, contract tests, `:app:assembleDebug :app:assembleDebugAndroidTest`, install/launch, APK hash, and diff checks.
6. **Parity/delivery.** Mark F48 Android partial with local evidence and live OIDC gap, update `.agent/PROGRESS.md`, get independent review, and commit/push only scoped F48 files after GREEN. Preserve unrelated untracked user files.

DoD: F48 selection and confirmation are explicit, revision-guarded, idempotent, Core-authoritative, locally verified, and pushed; Android still has no F49 undo/dedupe behavior.

## Execution result — 2026-10-11

- Implemented Android row selection/reset-to-Core-default and explicit confirmation. The label describes reset semantics; Core may restore an included default.
- Preserved nullable transaction and duplicate references in the strict Android DTO. Updated OpenAPI and JSON Schema for Core's `transfer_in` row kind.
- Independent reviews found and corrected test-fixture defects: invalid negative-purchase-to-income selection, stale aggregate values, reset source, and created/duplicate response states.
- GREEN: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; focused F47/F48 instrumentation 29/29; combined Android API 27 regression 208/208; contract suite 62/62; APK installed/launched on isolated `emulator-5554`; `git diff --check`.
- Debug APK SHA-256: `62463BD6D4933A2AD08E448E971E9D5EB6B28A893AF02B71A9412596D7A948B3`.
- Remaining delivery gates: live authenticated Android-to-Core OIDC mutation and tenant-isolation E2E. F49 deduplication/undo remains a separate goal.
- Commit/push: `e2b6852b16b1b59877b445ac7ce27446681a4e92` (`feat(F48): add Android bank import confirmation`) pushed to `origin/feat/saas-rewrite`; remote ref matches. No GitHub workflow run was found for the feature commit.
