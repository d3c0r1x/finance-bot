# F52 Android CSV export — execution plan

**Authority:** `PLAN.md` F52 and `docs/superpowers/specs/2026-10-07-f52-csv-export-design.md`. **Android extension:** `.agent/specs/F52-android-csv-export.md`. This adds the missing Android counterpart; it does not reopen or replace the accepted Core/Go/Web F52 work.

**Goal:** let an authenticated Android user request a Core-owned `csv-v1` export, follow its durable status with audit-aware bounded polling, and explicitly open a fresh HTTPS signed download link only after Core reports ready.

## Task 1 — Contract and baseline

1. Inspect the global F52 contract, Android networking/session/navigation conventions, Core `ExportController`/`ExportService`, Web export flow, F52 parity row, and current Git state. Confirm exact field names, status values, role names, member endpoint, date/timezone behavior, audit writes per status GET, and signed-URL bounds.
2. Record the pre-change result for focused Android JVM/instrumentation tests, relevant auth/navigation regressions, debug build, and API-27 instrumentation emulator. Keep existing user-owned files untouched.
3. Define a single Android goal for F52; do not alter Core, worker, Web, formatter, database, global plan, parity registry, or progress record in this slice.

## Task 2 — Model and request tests (RED first)

1. Add strict `ExportJob` parser tests for all lifecycle states, nullable `memberId`/`downloadUrl`, exact dates/count/timestamps, invalid UUID/date/time/status/version, and malformed/missing required fields. Observe a RED tied to the absent Android export model.
2. Add API request tests for authenticated create/status routes, exact `csv-v1` JSON, omitted own `memberId`, member UUID, `all`, and Core errors including 401/403/5xx. Verify create is never automatically replayed after an ambiguous failure. Observe RED before adding production methods.
3. Implement only typed, locale-neutral export models and Core methods through the existing authenticated `FinanceApi.execute` path. Do not implement an Android CSV serializer or download through the bearer-authenticated API helper.

## Task 3 — Range, role, and screen tests (RED first)

1. Add date policy tests for tenant-timezone defaults and inclusive 1/366 boundaries; reversed, invalid, 0-day, and 367-day intervals must fail before a network call.
2. Add Compose coverage for navigation, own-by-default scope, owner/admin member/all selection, safe member-list failure, one-tap create, loading/duplicate tap, all five job states, zero rows, localized errors, and tenant/session/request-generation isolation. Observe RED before production UI/state edits.
3. Implement a separate export screen using existing tenant membership/timezone and member APIs. Keep finance state unchanged. Clearly show that create retry is a new explicit job after the user chooses it.

## Task 4 — Audit-aware polling tests (RED first)

1. Add fake-clock/scheduler tests proving: no automatic GET before 5 seconds, no more than 12 GETs per window, terminal jobs stop, and screen exit/app background/tenant switch/session replacement cancel polling. Test the bounded-window manual status check path and that an active job is not mislabeled failed after the automatic window ends.
2. Assert no polling is performed for ready/failed/expired jobs and no repeated ready GET is used to refresh a link. Assert the only ready refresh is caused by the user's explicit file-action tap.
3. Implement a lifecycle-bound polling policy. Core audits every status GET and audits a URL issuance for each ready response; stop automatically at terminal state and bound all active polling. Do not hide polling behind a global process worker.

**Follow-up evidence (partial):** `FinanceExportsSection` accepts an injectable suspend delay for deterministic Compose coverage. Tests prove 12 automatic refresh callbacks request a 5,000 ms delay, manual refresh remains available afterward, and disposing the export screen cancels a suspended poll without making a request (2/2 focused; all F52 model/API/screen tests 29/29 on API 27). This does not model elapsed wall time or prove cancellation on app background/session/tenant changes; keep those acceptance items open.

**Download-safety follow-up (partial):** Extracted the attempt/context freshness policy and external `ACTION_VIEW` intent builder. Tests prove only the current ready response with a safe HTTPS URL can be handed off; URL absence, unsafe URL, non-ready state, tenant/export/session/request change, logout, screen exit, or pause yields no URL/intent. Status parsing rejects a valid job DTO whose ID differs from the requested export path, while UUID hex casing remains equivalent. The HTTP mock test verifies a status GET uses Core Bearer only on the Core request; the intent contains only the HTTPS URI and no extras. GREEN: F52 model/policy/intent 16/16, API 6/6, Compose 12/12; full `FinanceScreensTest` 88/88; JVM 71/71; contracts 63/63; debug and AndroidTest APKs assemble on isolated API-27 `emulator-5554`. Activity chooser invocation, live Core/OIDC roles, S3 readiness, real browser transfer, downloaded-byte integrity, app-background polling cancellation, elapsed poll timing, and full RU/EN state coverage remain unverified.

**Pause-race follow-up (partial):** Added a generation guard to export status responses and invalidate in-flight status generations on `onPause`; pause also clears a status-only busy state so the screen is not left stuck. The export Compose poller now begins only at `RESUMED`, not `STARTED`. RED was observed for the absent status-generation policy, and an initial lifecycle test run caught invalid test-thread event dispatch; the test harness was corrected before GREEN. The focused lifecycle policy test and complete Android JVM suite pass (73/73); `FinanceExportsScreensTest` passes 13/13 on Android 8.1/API 27 `emulator-5554`, including CREATED→STARTED no-poll, RESUMED poll, and PAUSE cancellation. Debug and AndroidTest APKs assemble. This checks generation rejection through the production guard and lifecycle behavior in Compose; it does not simulate a live delayed Core HTTP response across Activity pause/resume. Live Android/OIDC/Core, chooser/browser transfer, object-storage bytes, elapsed wall-clock polling, and full RU/EN status coverage remain open.

**Activity chooser handoff follow-up (local only):** Added tests through `MainActivity.openExportDownload` and the shared handoff helper. The Activity test captures the `ACTION_CHOOSER` passed to the launcher callback and verifies its nested `ACTION_VIEW` contains only the exact validated HTTPS URI; an unsafe URL produces no launch. Model/helper chooser contract tests also verify the localized title and no intent extras. RED first exposed the private handoff entry point; a separate initial run exposed asynchronous test-thread dispatch, corrected with `runOnMainSync`. GREEN: F52 model/API/screen tests 37/37 and `FinanceFlowTest` 5/5 on Android 8.1/API 27 `emulator-5554`; Android JVM and contract suites pass; APKs build. This proves the production Activity hands the validated chooser intent to its launch callback. Android's platform resolver, browser/object-store transfer, downloaded bytes, live Core/OIDC roles and other lifecycle/runtime gaps remain unverified.

## Task 5 — Safe signed download tests (RED first)

1. Add tests for absent/non-ready/non-HTTPS/userinfo URLs, fresh URL refresh on explicit tap, URL invalidated by session/tenant change, and no launch after malformed response.
2. Verify the signed link is held transiently only, never persisted or logged, and Core Bearer credentials are not attached to the external URL request/intent. Test URL query redaction in diagnostics if any URL diagnostics exist.
3. Implement explicit external HTTPS view/download handoff after a single authenticated ready-status refresh and strict URI checks. Discard the link on screen exit/session change. Do not auto-open or fetch through the authenticated Core client.

## Task 6 — GREEN, regression, and verification

1. Run focused model/API/policy/Compose tests and confirm each criterion has direct evidence. Repeat them after any fix.
2. Run relevant existing Android auth/session, tenant-switching, navigation, report, and F51 tests; build debug and AndroidTest APKs; run the API-27 AndroidTest suite on the isolated emulator; install/launch the fresh debug APK if the emulator is available.
3. Run existing parity/contract tests and `git diff --check`. The Core/PostgreSQL, Go, Web, and export storage test results from original F52 remain separate evidence; rerun only if this extension changes their inputs or integration contract.
4. Inspect the final diff for private URLs/tokens, user files, accidental CSV generation, and broad changes. Do not stage unrelated or user-owned files.
5. Mark live Android-OIDC/Core role checks, real S3 link transfer, and downloaded-byte verification `NOT_RUN` unless actually exercised. A mocked intent or a signed-looking fixture does not satisfy runtime proof.

## Task 7 — Parity record and delivery

1. After all local gates are green, update the F52 Android parity evidence and `.agent/PROGRESS.md` with exact commands, counts, emulator/API level, commit, and outstanding live-delivery limits.
2. Obtain an independent review of the complete slice and resolve actionable findings with targeted tests.
3. Commit this goal separately with its F52 identifier; push only if the user's existing GitHub-push authorization still applies. Verify the commit contents and remote head after delivery.

## Definition of done

The Android user can create an authorized bounded export, track Core's job without excessive audited reads, and explicitly open only a fresh HTTPS signed link after ready. Tests cover request contract, date and role constraints, lifecycle races, polling bounds, URL handling, and RU/EN states. Android never owns CSV content or sends its Core bearer token to object storage. Live delivery gaps remain explicitly reported rather than inferred from local tests.
