# F47 Android statement preview implementation plan

Goal: implement the approved F47 Android counterpart as a tenant-scoped PDF upload and read-only Core preview.

1. **Confirm baseline and boundaries.** Use F45.5 Android `dee23258` and F46 Android scope correction `b708ae6`; inspect current Android API, picker, navigation, roles, and tests. Keep F48/F49 confirmation/dedup/undo out of scope.
2. **Acceptance and test-first.** Follow `.agent/specs/F47-android-statement-preview.md`. Add separate API/model and Compose suites. Run them and record observed failures caused by absent F47 behavior before production edits.
3. **API/model.** Add a strict preview DTO preserving Core strings/nulls/order, separate PDF size policy (12 MiB), multipart upload and authenticated preview GET. Disable automatic 401 replay on the non-idempotent upload. Map only known server error codes to localized safe messages.
4. **UI.** Add a separate Imports entry and PDF picker for writers. Upload and show progress/failure; display Core quality, period, reconciliation amounts, and all ordered read-only rows in RU/EN. Guard responses by auth session, tenant, request, and active screen. Do not retain PDF bytes or URI.
5. **RED/GREEN and regression.** Run targeted RED, then focused API/UI GREEN. Run impacted existing API 27 instrumentation, unit checks, contract suite, APK builds, install and launch on isolated `emulator-5554`; compute APK hash and check diff. Do not use or stop user emulator 5558.
6. **Parity and delivery.** Update F47 Android registry and `.agent/PROGRESS.md`, identify live OIDC/parser/tenant checks still open, review scoped diff, commit only F47 files, push the authorized branch, and verify remote SHA/CI state. Preserve unrelated untracked user files.

DoD: all local F47 tests and relevant regression checks green; F47 is locally complete or explicitly partial with live evidence gap; Android has no F48/F49 side effects; commit and push verified.
