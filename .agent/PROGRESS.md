# Execution progress

Updated: 2026-10-11, Europe/Moscow.

## Global plan

- Plan: `PLAN.md`, SHA-256 `D6D5163AB1AB1B2C76501430435663C24E9E853F293371789681410A19D03FA9`; original specification from the planning chat.
- Mandatory supplement: `docs/specs/CONTINUATION.md`, version 1.1.
- Approval: user requested continuation of the approved rewrite, Android and
  future provider abstraction in the referenced chats; current user explicitly
  chose Keycloak + OIDC and requested GitHub clone before further work.
- Repository: https://github.com/d3c0r1x/finance-bot.git.
- Branch: `feat/saas-rewrite`; baseline HEAD: `cfaa013e1c977db24d7ab81944d0113ca79daea9`.
- E0 commit: `d19269f5c4015170a905c784f558e2e04f809849`.
- E1.1 commit: `68b8295f33328871e17f08cb3098bdb1c359c16e`.
- E1.2 commit: `4f0c5e50efbcc5714d2a14809034bb3dc5a2a924`.
- Original main and separate existing checkout are preserved.

## Current goal

- F59 Android parity remains IN PROGRESS. F59.1 registry: 33 partial, 23 missing, 2 approved N/A after verified F15.1-F15.4 progress.
- F59.2 F08 history/repeat/void locally GREEN: API 27 instrumentation 53/53; APK installed and launched; parity-contract tests 3/3. APK SHA-256: `2E7ABEE23A394665471435666333E03DF0F5D2EA5B00C0B2FA961A65342A9301`.
- F59.2 F09 basic search/type filters observed RED then GREEN; API 27 full instrumentation 54/54. Updated debug APK SHA-256: `FB008F9E13114578347D653E602C158C3870886AF379263F8124931CBABCC937`; `MainActivity` is resumed on isolated API 27 emulator. Remaining: server-backed date/member filters, cursor paging, transaction editing, and live Core E2E.
- F59.2 F03.1 first-time onboarding is locally GREEN and pushed in `1395decae6c50d337193fb6ec3810e6710484116`. F03 Android stays PARTIAL because repeat setup from profile is a later slice. Live OIDC/Core E2E, remaining parity, and F60/production gates remain.
- F59.2 F14.1 Android receipt evidence review is locally GREEN and pushed in `69dcb25` (`feat(android): review receipt reading evidence`). Core provides owner-scoped `GET /api/v1/tenants/{tenantId}/receipts/{receiptId}/readings`; Android now has typed exact-value models, authenticated read-only fetch, scrollable OCR/Vision evidence, localized fallback reasons and unknown-category presentation. F14 Android remains PARTIAL until later receipt edit/reconciliation flows and live OIDC/Core E2E.
- F59.2 F15.1 Android read-only receipt-item browsing is locally GREEN and pushed in `482dd81` (`feat(F15.1): browse receipt items on Android`). Authenticated owner-scoped paging uses fixed 8-row pages, preserves exact item values and versions, gates Next by loaded-page `hasMore`, and performs no mutations. `gh run list --commit 482dd81` returned no workflows; remote CI is unconfirmed.
- E4.108 F15.2 Android item editing is locally GREEN and pushed in `1ad9975c5aaa0a82034b4f05772299b78aadd1fa`: exact values/version, unchanged `cashTotal`, owner-only edit, actionable stale refresh and page-2 failure recovery. Full 124-test Android instrumentation and 15 JVM tests pass on isolated API 27; contracts 78 pass/2 skip; APK builds, installs and launches. `gh run list --commit 1ad9975` returned no workflows; remote CI unconfirmed.
- User-owned untracked paths `.android-user/`, `.freebuff/`, `.pnpm-store/`, `CODEX_AUTONOMOUS.md`, `apps/android/current-screen.png`, and `tmp/` are out of scope and untouched. A test agent briefly installed and ran the instrumentation APK on `Planerka_API27` before the explicit no-use instruction; the run was stopped and no app data was manually changed. All final verification used the dedicated `CodexFinanceBotApi27` on `emulator-5554`.
- Latest F03.1 verification: JVM unit suite GREEN; `assembleDebug` and `assembleDebugAndroidTest` GREEN; full API 27 instrumentation 104/104; parity contracts 60/60 via project `.venv`; debug APK installed/launched on isolated `emulator-5558`, PID 10980, SHA-256 `453A36AB1D74D6CC4D88F99DC1EB8F80B081D0FC28169D9086D0A75DE2F11E73`.
- `gh run list --commit 1395dec` returned no workflow runs; local verification is complete, remote CI is not confirmed.
- F14.1 TDD evidence: observed RED for missing API/model/state; later RED exposed inaccessible non-scrollable evidence and untranslated Core fallback codes. GREEN: API 10/10, receipt screens 16/16, JVM 12/12, full isolated API 27 instrumentation 112/112, debug and Android-test APK builds, and parity contracts 60/60. `git diff --check` clean.
- Runtime proof: installed fresh `com.decorix.finance.debug` on isolated Android 8.1 `emulator-5558`; `MainActivity` resumed, PID 16317. APK SHA-256 `A5ED96A8C199CB6283E9B1178A8A464B8FD6DE2E1C6A15E50DC3CB66EBC72693`; screenshot SHA-256 `13BCA9B4EAA6F76AEE011E77D5357A3F5A38E166C6118B53739F26EE01EF9DD8`. No live OIDC/Core E2E was exercised.
- Remote CI: `gh run list --commit 69dcb25 --limit 10` returned no runs; GitHub CI is not confirmed.
- E4.109 F15.3 Android explicit receipt-total synchronization is GREEN and pushed in `7498080269fa176d41b615bd3fe7aa93fd1aadb0`: exact totals, writer-only explicit action, quoted version guard, Core-authoritative positive/complete validation, localized 409/412 recovery, viewer read-only, no transaction creation. Full API 27 instrumentation 130/130, JVM 15/15; debug APK installed and launched on isolated API 27 emulator. No GitHub workflow run was found for this commit.
- F15.5 Android receipt-item delete is queued behind the user's unanswered design question; do not invent delete semantics before that answer.
- F03.2 Android repeat setup from profile is locally GREEN; implementation commit `018084d` is pushed and remote branch is verified at `f390ab4`. F03 Android remains partial pending authenticated Core/OIDC end-to-end proof. F15 and F59 remain partial; production/OIDC E2E and full F01–F60 parity remain.

## Goals

| ID | Requirement | State | Dependencies | Commit |
|---|---|---|---|---|
| E0.1 | Restore approved plan, supplement Android/AI and record Keycloak choice | COMPLETE | GitHub clone | d19269f |
| E0.2 | Legacy baseline, F/D registry, mobile delta inventory | COMPLETE | E0.1 | d19269f |
| E0.3 | Version matrix, foundational ADRs and initial contracts | COMPLETE | E0.2 | d19269f |
| E1.1 | Java build foundation and positive decimal money value | COMPLETE | E0 | 68b8295 |
| E1.2 | PostgreSQL schema/migration, RLS tenant isolation, idempotency/audit/outbox persistence | COMPLETE | E1.1 + PostgreSQL runtime | 4f0c5e5 |
| E1.3 | JWT transaction create/list API, membership authorization, atomic outbox and cursor paging | COMPLETE | E1.2 | f7fcdd9 |
| E1.4 | Get/void, strict Keycloak JWT validation and least-privilege DB role | COMPLETE | E1.3 | f92500d |
| E1 | Complete core vertical slice (E1.1–E1.4) | COMPLETE | E0 | f92500d |
| E2.1 | Personal tenant onboarding, member profile, tenant list and contract | COMPLETE | E1 | pending |
| E2.2 Web profile/dashboard/transactions | Web RU/EN, CRUD/repeat/void, filters | IN PROGRESS — offline F03–F09 acceptance complete; live Keycloak/API E2E remains |
| E2.3 Android OIDC slice | Login + transaction create/list APK E2E | COMPLETE |
| E2 | Web/profile plus Android first API flow | IN PROGRESS | E1 | none |
| E3 Budgets and debts | Core/Web/Android budgets, proposals, rolling food, debts, reports, text drafts and charts | IN PROGRESS — F25/F26/F30/F31/F32 acceptance complete; remaining F-parity, live API/auth and scale gates remain |
| E4 Python and Telegram parity | Telegram gateway, receipt pipeline, local AI, import and Telegram flows | IN PROGRESS — F10 storage/ClamAV, F01/F02/F04/F05/F07/F25/F30/F32 acceptance complete; F11, imports and remaining F-parity/runtime integrations remain |
| F25 | Rolling food consistency | COMPLETE — reports, Telegram captions, and scheduled digest share Core DTO formatting | F32 | pending |
| F26 | Safe-to-spend cash planning | COMPLETE — current recurring history, payday boundary, reserve and no-plan cases verified | F38 | pending |
| F31 | Report charts and PNG | COMPLETE — Web/Android/PNG category, day, limit, real price-history and optional-spend charts; text fallback | F33, F40 | a07f7cb |
| F31.2 | Daily optional-spend chart in Web, Android and Telegram | COMPLETE — local gates and GitHub workflows GREEN | F40.3 | a07f7cb |
| F32 | Durable daily and weekly digest delivery | COMPLETE — PostgreSQL schedules/outbox, Core API, Python worker, Web/Android settings | F25 rendering | pending |
| F33 | Authoritative price projection and replay | COMPLETE — Go projection, Core API, Web/Telegram actual-history charts; ClickHouse/Redpanda CI replay GREEN | F34 | a07f7cb |
| F35 | Receipt-cadence shopping suggestions | COMPLETE — Core, Go, Telegram, Web, Android | F34 | d001969 |
| F36 | Shopping decisions and copy | COMPLETE — bought marks, member-local mute, blocked reason, clipboard | F35 | d758099 |
| F37 | Personal basket inflation, 90-day window, top rise/fall | COMPLETE — Go, authenticated Java API, Telegram, Web, Android | F33 projection contract | 41b14f0 |
| F38 | Recurring expense/income series and warnings | COMPLETE — Go projection, member-scoped Java API, Telegram/Web/Android ranges and warnings | F26 shared recurrence rules | 69a254e |
| F39 | Mute and restore recurring series | COMPLETE — local gates and GitHub rerun pass | F38 | b1e64c4 + 2de08d9 |
| F40 | Optional-spend aggregate, verdict sources and corrected receipt lines | COMPLETE — Go, Core, Web, Android, Telegram and GitHub regression verified | F39 | ec1a373 |
| F40.1 | Versioned Go advice-spend algorithm and internal API | COMPLETE — local gates and GitHub regression pass | F39 | f3da7a0 |
| F40.2 | Core member-scoped receipt facts and report API integration | COMPLETE — local and GitHub checks pass | F40.1 | 68253b7 |
| F40.3 | Web, Android and Telegram optional-spend presentation | COMPLETE — local client gates and GitHub regression GREEN | F40.2 | ec1a373 |
| F41 | Personal “do not buy” list, separate model hypotheses and human decisions | IN PROGRESS — Android commit exists; GitHub run not found | F40 | ccfcf6a |
| F41.1 | Go evidence groups for the personal “do not buy” list | COMPLETE — local and three GitHub workflows GREEN | F40 facts | bd8444f |
| F41.2a | Confirm/allow/revoke member-local product decisions with audit | COMPLETE — local and four GitHub workflows GREEN | F41.1 | d537b08 |
| F41.2b | Core evidence API, personal policy overlay and shopping isolation | COMPLETE — local and four GitHub workflows GREEN | F41.2a | 7307fff |
| F41.3a | RU/EN Web list, guesses and human controls | COMPLETE — local gates and two GitHub workflows GREEN | F41.2b | 8706159 |
| F41.3b | Telegram list and human controls | COMPLETE — local and four GitHub workflows GREEN | F41.2b | 41ace8f |
| F41.3b1 | Actor-scoped Telegram list and decision routes | COMPLETE — local and four GitHub workflows GREEN | F41.2b | 7089a63 |
| F41.3b2 | Telegram command, renderer and callbacks | COMPLETE — local and four GitHub workflows GREEN | F41.3b1 | 41ace8f |
| F41.3c | Android list and human controls | COMMITTED — 50/50 instrumentation and APK launch; GitHub run not found | F41.2b | ccfcf6a |
| F42 | Recalculate saved receipt verdicts only by explicit request; retain audit and report | COMPLETE locally — F42.1–F42.6; owner-scoped history and bounded pages/inputs | F41 | 8b741c0 |
| F42.1 | Deterministic preview policy for eligible receipt lines | COMPLETE — local Core regression passed | F42 | 6f80afc |
| F42.2 | Persist preview, apply safely, audit and report changes through Core API | COMMITTED — local PostgreSQL/Core checks pass; contract pytest unavailable, GitHub run not found | F42.1 | d41888c |
| F42.3 | Web preview and explicit apply flow for receipt verdict recalculation | COMMITTED — Web 57/57 and production build pass; GitHub run not found | F42.2 | 81267de |
| F42.4 | Calculate optional-spend impact in Go; persist exact report in Core preview; display it in Web | COMMITTED — local Go/Core/Web/contracts GREEN; pushed, but GitHub returned no run for the SHA | F42.3 | 659b537 |
| F42.5 | Telegram `/recalculate` preview with actor-scoped explicit apply | COMMITTED — local gates GREEN; pushed, GitHub returned no run for the SHA | F42.4 | 1a484bb |
| F43 | Advice analytics calculation, durable member job, Go worker, Web report | COMPLETE — F43.1–F43.4 pushed; local gates green; GitHub CI did not start | F42 | a911e27 |
| F44 | Product/group goal candidates, count/sum, one active 30-day goal | COMPLETE — F44.1–F44.3 locally green and pushed; GitHub did not start workflows | F43 | 2e5dce4 |
| F44.1 | Deterministic Go product/group candidates and internal service API | COMPLETE — `959c750`; nullable-amount correction `d8591d3`; Go/contract gates pass | F44 | d8591d3 |
| F44.2 | Member-scoped preference, accepted goal, Core API/BFF | COMPLETE — `88f868b`; combined payload cap correction `e4d0d61`; isolated PostgreSQL, Core and contract gates pass | F44.1 | e4d0d61 |
| F44.3 | RU/EN Web candidate and active-goal screen; writer/viewer actions | COMPLETE — `2e5dce4`; 72 Web tests, production build and Core regression pass | F44.2 | 2e5dce4 |
| F45 | Goal progress, purchase note, completion, history, next candidate | COMPLETE — F45.1–F45.4 code acceptance is green; real SQLite rehearsal belongs to E8 and remains unverified | F44 | afcde2d, 85c30d1, 23db236, 462d758 |
| F46 | Deliver a completed goal outcome in the weekly Telegram digest | COMPLETE — `9455567`; all local gates passed and commit pushed; GitHub returned no workflow run | F45 lifecycle | 9455567 |
| F52 | Excel-compatible CSV export, filters, safe text, scoped download | CODE COMPLETE — final local regression passed; SeaweedFS runtime integration NOT_RUN because Docker is unavailable | F51 | 0f50266 (polling-test stabilization); dd0b35f (Task 4); 016a6a0 (Task 3b); 7e2088d (Task 3a); e8ec4af (Task 2b); 62370cd (Task 2a); 66de1a8 (Task 1) |
| F53 | Web equivalents for eight desktop tabs, shared refresh, member transaction navigation, profile editing | COMPLETE — member route, own profile link, manager transaction filter, refresh/F5 and active route; Web 87 tests/build green | F52 | 5adeccf |
| F54 | Sanitized AI/Vision/Tesseract capability status | COMPLETE LOCALLY — private bounded health probe, membership-protected BFF, RU/EN Web status; external provider runtime check remains deployment proof | F53 | 2a6cf21 |
| F55 | Local AI, explicit cloud opt-in, no-Ollama fallback, model management | COMPLETE — local gates and all four GitHub workflows GREEN after contract-test correction | F54 | 5df8b06, 0edc20b |
| F56 | Receipt inventory CLI, synthetic/private sample checks, CI privacy | COMPLETE — 11 focused tests, synthetic receipt regression, CI GREEN | F55 | ab4a38d |
| F57 | Russian formatting, Telegram escaping, fonts, text fallback | COMPLETE — Python/contracts 199; Web 95/build; Android unit 4; Android 8.1 instrumentation 50; APK built/launched; pushed; no workflow run returned | F56 | c7d7995 |
| F58 | Safe configuration, cross-platform legacy launchers, docs, MIT notice | COMPLETE LOCALLY — focused tests and script checks pass; pushed; no workflow run returned. Docker unavailable; Compose remains unimplemented per PLAN.md | F57 | 853d42f |
| F59.1 | Explicit Android parity map for every F01–F58 registry row | COMPLETE — registry maps 58 features: 33 partial, 23 missing, 2 justified N/A; F14/F15 mappings link to Android instrumentation tests | F59 | pending |
| F59 | Android RU/EN parity for F01–F58, Java API E2E, installed emulator APK | IN PROGRESS — inventory complete; screens and 50-test emulator suite exist; missing flows and live Core/OIDC E2E remain | F01–F58 | pending |
| F10 | Private receipt storage and malware scan | COMPLETE — authenticated SeaweedFS S3 + real ClamAV integration passed in CI | F11 | 68253b7 |

## Current goal

- ID and outcome: F59 — Android Kotlin/Compose RU/EN feature parity for F01–F58, shared Java API, installed APK, and E2E.
- Status: IN PROGRESS — F59.1 maps all 58 rows: 33 partial, 23 missing, 2 justified N/A. Existing UI includes onboarding, transactions, budgets, debts, reports, shopping, inflation, recurring items, product decisions, and F15.1-F15.4 receipt item flows.
- Acceptance: map every F01–F58 to Android behavior or justified platform-specific N/A; implement missing user flows; run installed APK on Android 6–8 emulator; prove authenticated live Java/Core/OIDC flow.
- Evidence: `tests/test_f59_android_parity.py` and F59 `android` mappings in `contracts/parity/feature-parity.yaml`. Initial RED: 3 failed because Android mappings were absent/stale. GREEN: 4 parity/registry contract checks passed. Registry marks all unresolved flows explicitly; this inventory does not claim feature completion.
- Evidence: F56 feature commit `ab4a38d` is pushed. GitHub run `37577879007` passed all workflow steps, including private-path guard, legacy suites, and empty JSON module smoke.
- Ownership: `receipt_inventory.py`, `docs/EVALUATION.md`, and `.github/workflows/tests.yml` are tracked starting points. User-local data `.android-user/`, `.freebuff/`, `.pnpm-store/`, `CODEX_AUTONOMOUS.md`, `apps/android/current-screen.png`, and `tmp/` remain untracked and out of scope.
- Repository / branch / HEAD: `d3c0r1x/finance-bot`, `feat/saas-rewrite`, `853d42f` (F58 pushed). User-owned untracked paths remain untouched.
- F57 GREEN: Python/contracts/report/Telegram command suites — 199 passed; Web `pnpm test` — 95 passed; `pnpm build` passed. Android unit 4 passed, isolated Android 8.1/API 27 instrumentation 50 passed; debug APK installed/launched. SHA-256: `5D6D6F12A62697C1A693BFC6F328B98330931002E097307A168A2DA69D285E97`. `gh run list --commit c7d7995` returned no workflows.
- F58 GREEN: `.venv\\Scripts\\python.exe -m pytest -p no:cacheprovider tests/test_f58_runtime_config.py tests/test_presentation_formatting.py -q` — 13 passed; `sh -n scripts/dev.sh`, PowerShell help mode, both launcher help outputs, `git diff --check`, and Git ignore probes passed. Docker CLI is unavailable, so no image/build-context runtime check was possible. Compose profiles do not exist yet, as stated in PLAN.md.
- Privacy note: one accidental local no-argument CLI smoke did scan a photo under ignored `data/receipts`; it changed or copied no files. No receipt manifest was opened or committed. Do not run default local inventory again; use synthetic monkeypatched paths. This is not evidence about owner receipt quality.
- F56 commit/push: `ab4a38d` (`feat(F56): add receipt evaluation module and privacy gate`); final progress update pending.
- F58 commit/push: `853d42f`; `gh run list --commit 853d42f` returned no workflows.
- Next: commit/push F59.1 map, inspect CI; then add a RED Android acceptance test for transaction repeat/undo (F08) and implement the smallest complete mobile flow.
- Updated at: 2026-10-08, Europe/Moscow.

## E4.86 F52 Task 2a Core export request/status — 2026-10-07

- Test-first RED: migration test failed because V42 export tables were absent; Core request/status methods returned 404. Added tests before implementation.
- Task 2a implements `csv-v1` request validation, owner/admin/member scope, requester-timezone inclusive date filters, 366-day and 100,000-row caps, durable job/outbox/audit transaction, immutable transaction snapshot, status access controls, and Core/BFF routes.
- PostgreSQL tests prove create/update/void after request do not change snapshot; finance transaction count is unchanged; denied scope and cross-tenant/member status return 403/404; rejected oversized range/row set creates no job; successful Web BFF status read is audited.
- Migrations V42/V43 add tenant RLS and immutable snapshots; V43 allows expiry cleanup to delete rows while blocking updates.
- Focused Core tests: 4/4 passed on isolated PostgreSQL. Full `:services:core:check` passed. Contracts/OpenAPI/migration: 73 passed, 2 optional PostgreSQL admin/event checks skipped. Go suite/vet, Web 75 tests/build, and `git diff --check` passed. Contract tests attempted with app DSN correctly failed on insufficient `CREATEROLE` and absent public outbox fixture; rerun without the optional DB variable produced the reported skips.
- Plan refinement: split Core request/status (Task 2a) from worker internal API (Task 2b) so each verified slice can commit independently.
- Task 2a commit/push: `62370cd` (`feat(F52.2a): add scoped export snapshots`). `gh run list --commit 62370cd` returned no workflow runs.

## E4.87 F52 Task 2b worker API — 2026-10-07

- Test-first RED: four PostgreSQL API cases failed on absent worker routes; migration contract failed because lease fields/service RLS were absent.
- Task 2b adds service-token protected claim, keyset page, complete, and fail routes. Claim uses `SKIP LOCKED`, renewable two-minute leases, a five-attempt cap, persisted backoff, terminal expiry/failure, and stale-token fencing. Complete validates tenant/export object key, byte count, SHA-256, and duplicate acknowledgement.
- V44 adds lease state, attempts, object metadata, queue index, and service-only RLS. V45 adds status update timestamp. OpenAPI documents each worker route. Core security permits only through to the token-checking controller.
- Focused Core/PostgreSQL worker tests pass 4/4: credential denial, exclusive claim, lease-scoped pages, idempotent completion, retry/backoff, expired-lease reclaim, stale-attempt rejection, and terminal no-reclaim. Focused OpenAPI/migration tests pass 3/3.
- First implementation check exposed missing `updated_at`; V45 fixes it without changing an applied migration. A later test exposed pending rows from earlier database tests; queue fixture now expires pre-existing pending work before asserting global queue order.
- Full `:services:core:check` passed against isolated PostgreSQL. Full contract suite passed 75 tests with 2 optional DB tests skipped because this local DB role lacks `CREATEROLE` and its event fixture; focused worker OpenAPI/migration contracts passed. `git diff --check` passed. Commit/push `e8ec4af` (`feat(F52.2b): add fenced export worker API`); GitHub returned no workflow run. No Go worker, object storage, or download URL is complete.
- Task 3 is next. No Go worker implementation is complete yet.
- Task 2a commit/push: `62370cd` (`feat(F52.2a): add scoped export snapshots`). `gh run list --commit 62370cd` returned no workflow runs.
- Task 2b is the next goal. No worker endpoints or worker-side object storage are claimed complete.

## E4.88 F52.3a Go export worker and private S3 storage — 2026-10-07

- Test-first RED: worker, Core client, private S3 storage, and config symbols were missing. Additional RED caught transient Core page failures mislabeled as corrupt snapshots; they now report retryable `core_unavailable`.
- Go worker polls Core's fenced lease API, validates claim/page metadata and row order, writes `csv-v1` to a permission-restricted temporary file with a 128 MiB bound, computes SHA-256, uploads to private S3, and only then calls Core complete. Upload failure deletes partial object. Ambiguous completion acknowledgement keeps object for Core idempotency.
- Added strict service-token Core HTTP client; MinIO S3-compatible adapter uses path-style configuration and bounded 5 MiB multipart parts with 2 threads. Worker is opt-in and validates Core, token, S3, poll, page, and temp-directory configuration.
- Added S3 adapter HTTP tests and an optional SeaweedFS test for authenticated round-trip plus anonymous-read denial. Receipt-storage CI now runs this Go integration test; it has not run on this workstation.
- GREEN: Go `test ./... -count=1`, `go vet ./...`, and `git diff --check` passed. Current Go module uses `minio-go/v7 v7.3.0`.
- Commit/push: `7e2088d` (`feat(F52.3a): stream exports to private S3`) pushed. GitHub returned no workflow run. Core signed URL and Web remain separate slices.

## E4.89 F52.3b Core-authorized signed downloads — 2026-10-07 07:15 Europe/Moscow

- Test-first RED: new Core route tests could not compile because `ExportDownloadSigner` was absent; contract tests failed because download URL lifetime and authorization were undocumented.
- Core signs only after tenant/requester authorization for a ready job with an object key. URL lifetime is at most five minutes and at most the remaining job lifetime; jobs with under one second remaining receive no URL. Audit stores only ready status, never URL/signature.
- S3 signer validates endpoint origin, bounded export object-key pattern, credentials, and lifetime. Added presigned GET content type/disposition. No extra AWS artifact is needed; presigner comes from the existing SDK S3 dependency.
- Task 3b commit/push: `016a6a0` (`feat(F52.3b): sign authorized export downloads`). GitHub returned no workflow run.
- Next: commit the RU/EN Web flow, then run final regression; SeaweedFS local integration is unavailable because Docker is not installed.

## E4.90 F52 Task 4 RU/EN Web export flow — 2026-10-07 07:17 Europe/Moscow

- Test-first RED: focused Web suite failed because `ExportsPanel` was missing. Tests cover owner all-member scope, member/viewer own scope, admin-selected member, finite date range validation, queued/processing polling, empty header-only export, failed/expired states, and safe download visibility.
- Added typed create/status API, timezone-local 30-day defaults, 366-day client bound, role-aware scope selector, 1-second status polling, RU/EN job states, empty result messaging, and download only for ready jobs with URL. Added `/exports` navigation and responsive layout.
- GREEN: focused export suite 6/6; full Web suite 81 passed; `pnpm --dir apps/web build` passed. Final Core forced check, contracts 76 passed/2 optional skips, and Go tests/vet passed.
- Final regression first exposed the polling test's default one-second query timeout racing the one-second refetch interval under parallel load. Increased only the test wait to four seconds; focused and full Web suites then passed. Product polling stays at one second.
- Task 4 commit/push: `dd0b35f` (`feat(F52.4): add web CSV export flow`). Test timeout stabilization commit/push: `0f50266`. GitHub returned no workflow runs for either SHA.
- SeaweedFS integration is NOT_RUN locally because Docker and endpoint are unavailable. Code acceptance is complete; record live storage runtime separately.
- F52 code acceptance complete. Production S3 runtime proof remains NOT_RUN. Next: continue next incomplete V1 goal.

## E4.91 F52 final regression and code acceptance — 2026-10-07 07:22 Europe/Moscow

- Full parallel regression initially produced one Web failure: the polling assertion used Testing Library's default one-second wait while the product refetch interval is one second. Raised only assertion waits to four seconds; focused export tests passed 6/6 and full Web passed 81/81 afterward.
- Final gates: Core `:services:core:check --rerun-tasks` passed against isolated PostgreSQL; Go `test ./... -count=1` and `go vet ./...` passed; contracts/migrations 76 passed, 2 optional DB tests skipped; Web 81 passed; `pnpm --dir apps/web build` passed; `git diff --check` passed.
- Polling-test fix committed/pushed as `0f50266` (`test(F52.4): stabilize export status polling`). `gh run list --commit 0f50266` returned no workflows.
- F52 code acceptance complete. SeaweedFS private-object round-trip is NOT_RUN because Docker and an S3 endpoint are unavailable; record as a separate deployment proof.
- Next: F53 panel parity, starting with legacy inventory versus current Web routes/components.

## E4.92 F53 Web panel parity — 2026-10-07 07:34 Europe/Moscow

- Test-first RED: member component, route transition, active navigation, and shared refresh controls were missing. Added the authorized members route, selected-member transaction link, self-profile link, refresh button/F5, and active navigation state.
- GREEN: Web 13 files / 87 tests, TypeScript/Vite production build, and `git diff --check` passed. Full suite caught duplicate accessible names for refresh and retry; global action now has a distinct label.
- Plan: `docs/superpowers/plans/2026-10-07-f53-web-panel-parity.md`. Commit/push `5adeccf`; `gh run list --commit 5adeccf` returned no workflow runs.

## E4.93 F54 health and capability status — 2026-10-07 07:49 Europe/Moscow

- Test-first RED: Python private health route returned 404, Core client/controller types were absent, and Web `HealthPanel`/route could not resolve.
- Added bounded private dependency checks for configured Ollama text and allowlisted vision models plus Tesseract; inventory stream is capped at 128 KiB and 512 models, checks time out, and raw provider errors/configuration never leave the probe. `/healthz` remains public and shallow.
- Core calls the private endpoint with a service token, caps its timeout at five seconds, validates capability names/status/diagnostic codes, returns a fixed safe unavailable result on failure, and checks active tenant membership first. Web displays RU/EN states and user-safe explanations with manual refresh. Added both OpenAPI contracts and environment example.
- GREEN: contracts, migration, intelligence, and Telegram suite 282 passed / 1 optional DB skip; Core `:services:core:check --rerun-tasks`; Web 14 files / 91 tests and production build; `git diff --check`.
- Local provider runtime was not probed against a real Ollama/Tesseract installation; integration behavior is covered with controlled HTTP/filesystem fixtures. Detailed operator-only diagnostics still require the platform-operator authorization model from later SaaS work.
- Plan: `docs/superpowers/plans/2026-10-07-f54-health-status.md`. Commit/push pending.

## E4.94 F55 local AI and model management plan — 2026-10-07 07:52 Europe/Moscow

- F54 committed/pushed as `2a6cf21`; GitHub reported no workflow run for the SHA.
- F55 inventory: the Python gateway already rejects remote providers in `local-only`, requires both policy layers for cloud requests, and uses explicit Vision model allowlists. It lacks the legacy installed-model preference resolver for text tasks; deployment docs do not describe safe management in the v2 layout.
- Wrote `docs/superpowers/plans/2026-10-07-f55-local-ai-models.md` with bounded local inventory, exact tag matching, embeddings exclusion, no remote fallback/inventory scan, manual no-Ollama behavior, and model operations through deployment config/Ollama CLI.
- No F55 product code changed. Next: tests first and observed RED.

## E4.81 F45.4 legacy goal history import — 2026-10-07 05:52 MSK

- Observed RED before implementation: extractor module was missing. Added tests for read-only SQLite extraction, explicit owner/timezone mapping, exact legacy snapshots, duplicate keys, DST ambiguity/gaps, exact money, private quarantine, dry-run, bounded uploads, token transport, and response accounting.
- RED exposed incorrect DST overlap detection, timestamp microsecond truncation, Decimal overflow escaping quarantine, count/sum rows Core would reject, malformed-key accounting, and upload continuing before quarantine review. Each failure received a targeted fix and regression assertion.
- A local HTTP redirect test was initially flaky under Windows socket shutdown; replaced with a deterministic opener test that verifies redirect rejection without network I/O. It passes.
- Core migration endpoint and PostgreSQL acceptance tests are green. Full `:services:core:check` passed against isolated PostgreSQL; extractor + contract suites passed 87 tests with 2 optional skips; Telegram gateway 101 passed; Go tests/vet passed using cached Go 1.27.1; Web 75 passed and production build passed.
- The extractor is read-only, groups by explicit tenant/member, batches at 500, requires HTTPS outside loopback, refuses unresolved quarantine unless explicitly reviewed, and relies on Core idempotency after interrupted uploads. `tools/migration/README.md` documents manifest and commands; CI contract job now runs extractor tests.
- Core API/spec/contract slice committed and pushed as `23db236`; GitHub returned no Actions run for this SHA.
- Extractor/docs/CI slice committed and pushed as `462d758`; GitHub returned no Actions run for this SHA.
- No real legacy SQLite database exists in this checkout. Live mapping, quarantine review, and rehearsal are not verified. F45 code acceptance is complete; F45.4 does not claim a completed production migration.
## E4.57 User MVP steering and plan update — 2026-10-06 11:45 MSK

- User restated strict test-first work and set a 1.5-hour personal MVP priority. F01–F60 stay unchanged; post-V1 V2 work is excluded until V1 finishes.
- Updated `PLAN.md` with binding MVP scope and added `docs/specs/PERSONAL_MVP_PLAN.md`: API 23 Android, public HTTPS without router port changes, persistent/autostart/recovering host runtime, open Keycloak/OIDC registration and real email verification, deployment/security gates, and end-to-end acceptance.
- Captured user's Cloudflare screenshot accurately: Worker Build shows `main`, root `/`, `npx wrangler deploy`; this does not establish that `feat/saas-rewrite` or Java/PostgreSQL backend is deployed. Do not change nameservers from this screenshot.
- Read-only environment check: two connected emulator processes report Android API 34, so neither proves API 23–26 compatibility. Unity-bundled SDK/ADB exists. Tailscale Windows service is running/automatic. No local listener was found on 5432, 8080, 8081, 5173, 4173, or 8443. Android build still has `minSdk = 26` and emulator-only API/OIDC values. F41.3c changes remain uncommitted and must be preserved.
- Plan-document structural checks: PASS (F01–F60 preserved, V2 deferred, MVP link/API 23/Keycloak/email/TDD requirements present); `git diff --check` PASS (Git reports only existing LF/CRLF normalization notices).
- Current-diff checks: Core `AdviceEvidencePolicyTest` PASS; Web `DoNotBuyPanel.test.tsx` 5/5 PASS; Python renderer/Core client 66/66 PASS. Android debug APK assemble PASS, but Android local unit suite FAILS in Gradle/JUnit discovery with `ClassNotFoundException: com.decorix.finance.DevelopmentConnectionBuilderTest`; compiled class exists and is listed on `testDebugUnitTest.classpath`, so root cause remains unresolved. Initial Java/Python command failures were missing PATH runtimes; Unity JDK/SDK and repo `.venv` resolve those setup gaps.
- Plan update commit `cdc51c9` (`docs(plan): define personal MVP and defer V2`) was pushed to `origin/feat/saas-rewrite`; push succeeded. F41 implementation changes remain unstaged and untouched.
- Failure analysis `ANDROID-UNIT-CLASSLOAD`: compiled test and classpath were valid; direct JUnit passed. The Gradle test worker failed from the project's normal Unicode/space path, but the exact Gradle task passed through temporary `Z:` drive mapping. This is a Windows path-specific runner issue, not a product/test assertion failure; no repository build config change needed.
- F41 edge-case RED proven safely in temporary worktree at `cdc51c9`: applied only the new `AdviceEvidencePolicyTest`; it failed because mixed unmarked `tea` was classified as blocked. Temporary worktree removed; current checkout remained unchanged.
- F41 GREEN: `:services:core:test --tests ...AdviceEvidencePolicyTest` PASS; Web `DoNotBuyPanel.test.tsx` 5/5; Web TypeScript + Vite production build PASS; Python renderer/Core client tests 66/66; Android unit PASS via `Z:` mapping; connected Android instrumentation 50/50 on test AVD `FinanceBotF38` (API 34). Targeted F41 model/UI and two unrelated suite tests also passed on `Quest_Test`; its full-suite run failed only after emulator `system_server` died (`DeadSystemException`), so it is not used as GREEN evidence.
- Debug APK assembled, installed and launched on `emulator-5556`; process `21967`; SHA-256 `BB7D6BC6EF01CD793F37D7124420058C3B3273F56C3D976106699E7F5B5F6297`.
- Plan docs commit `cdc51c9` is on origin. Next: commit only F41.3c source/tests plus progress, push feature branch, inspect CI result; then begin MVP Android API 23 goal. Android 6–8 images, persistent backend, public HTTPS and real email are still unverified.
- Next: isolate Android test discovery failure without changing product code; finish current F41.3c gates and commit. Then run MVP M0/M1. No claim of MVP runtime, public HTTPS, or email delivery yet.

## E4.58 Personal MVP M1.1 Android API 23 compatibility — 2026-10-06 13:05 MSK

- Observed RED: APK acceptance script rejected the existing `minSdk 26` against required API 23. The first full API 23 instrumentation run then exposed 18 runtime failures because `java.time.Instant` and `YearMonth` were missing on Android 6.
- Implemented `minSdk 23` and official Android core library desugaring (`desugar_jdk_libs:2.0.3`). This removed the runtime date/time failures. The remaining UI failures exposed test assumptions about content already being visible on a 320x480-class screen; corrected tests to scroll the actual lazy lists before asserting their content/actions.
- GREEN: all 50 Android instrumentation tests passed on isolated API 23 / Android 6, API 25 / Android 7.1.1, and API 27 / Android 8.1 AVDs. AVDs were created under the local Android user profile; existing API 34 AVDs were not wiped or modified. Gradle `testDebugUnitTest assembleDebug` succeeded; min-SDK verifier reported `PASS: APK minSdk 23 is compatible with required API 23 floor.`
- Built debug APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `0D54F551950FFD5936BC9A8994ACF92702ABF0B7CE304A5C9ED9EB39CED4419E`. Installed and launched by package on Android 8.1 emulator; process observed. Debug BuildConfig still uses emulator-only API/OIDC endpoints.
- Plan now splits M1.1 (API 23 compatibility, COMPLETE) from M1.2 (configurable endpoints and release HTTPS checks, TODO). M1 as a whole, public access, server autostart and email registration remain incomplete.
- Next: write endpoint-configuration acceptance tests and finish M1.2 before making a phone-installable APK. Continue to M2/M3 only after checking local Docker/PostgreSQL/Keycloak and safe public HTTPS options. Never publish a backend before auth protects financial routes.

## E4.59 Personal MVP M1.2 endpoint configuration — 2026-10-06 13:13 MSK

- Observed RED: `BuildConfigEndpointTest` with injected HTTPS properties failed against the hard-coded emulator URL. Implemented `financeApiBaseUrl` and `financeOidcIssuer` Gradle properties with isolated emulator defaults for debug.
- Release builds depend on `validateReleaseEndpoints`; it requires both values, HTTPS, public hosts, and a root origin for the Core API. Explicit checks: HTTPS endpoints pass; missing values, HTTP and `192.168.1.50` fail. The endpoint unit test passes for both injected and default debug values.
- Regression: full Android instrumentation 50/50 passed on API 23/25/27 before this BuildConfig-only change; OIDC sign-in screen smoke 1/1 passed on API 27 after it. `testDebugUnitTest assembleDebug` succeeds with no properties. Debug APK installed and launched on API 27, SHA-256 `6BF718140D420BCA39A88CEF2DAE8AE9C3F2D2465B4C4AC5D17497364CCE8B93`; APK floor verifier passes (`minSdk 23`).
- M1.2 code is complete; no actual public API/OIDC endpoint exists yet. Release APK for a phone therefore remains blocked on M2/M3. Tailscale service is running, but `tailscale funnel status` and `tailscale serve status` report `No serve config`; no Docker/Podman, PostgreSQL, Keycloak or service listeners are present. No public tunnel was opened.
- Commit `10882fb` (`fix(android): support API 23 with desugaring`) pushed; GitHub tests workflow `37447430201` completed successfully. Endpoint configuration was committed and pushed as `1e99c2e` after local regression.
- Next: inspect CI for `1e99c2e`. M2 must provision a repeatable database/Core/Keycloak runtime, then M3 may configure Funnel only after auth is protected and external HTTPS can be tested.

## E4.60 Personal MVP M2 environment audit — 2026-10-06 13:17 MSK

- Confirmed no Docker, Podman, Keycloak server runtime, or cloudflared command. The Tailscale Windows service is running; `tailscale funnel status` and `tailscale serve status` both return `No serve config`.
- A local PostgreSQL process listens at `127.0.0.1:55438` from the Codex-managed runtime cache. It is temporary test infrastructure; do not use as the personal app's durable store. No app/API listeners on 5432, 8080 or 8081. `ops/keycloak/realm-finance.json` is configuration only, not an active Keycloak.
- M2 is not implemented; server autostart, durable application database, Keycloak, backups/readiness, and restart recovery remain unverified. No public tunnel was opened. User's Cloudflare screenshot still shows a Worker build, not proof of a deployed Java Core API.
- Email sender/domain still absent, so real email verification remains a hard end-to-end dependency. Never fake a successful confirmation.
- M1 work pushed: `10882fb` and `1e99c2e`; endpoint commit CI is running as GitHub workflow `37448579789` at last check. Next: inspect CI, then provision durable local service components without opening public ingress until auth and email verification are protected.

## Verification evidence

| Criterion/gate | Command or procedure | Revision/worktree state | Actual result | Evidence |
|---|---|---|---|---|
| F34 Go catalog/search | `go test ./...`, `go vet ./...`, Linux build | F34 worktree | PASS | Search threshold 1, catalog threshold 3, exact prices and actual history |
| F34 Core PostgreSQL scope | `TransactionApiPostgresTest.productCatalogUsesAuthenticatedMemberScope` | isolated `finance_test_f34_catalog_20261005` on local PostgreSQL 18.6 | PASS | Both members see only their own catalog through authenticated API |
| F34 Web | `pnpm --dir apps/web test`; `pnpm --dir apps/web build` | F34 worktree | PASS, 40/40; build pass | Catalog and one-purchase search; chart only with two real points |
| F34 Python / contracts | presentation + Telegram pytest; `tools/contracts` pytest | F34 worktree | PASS, 84 passed; 48 passed/2 optional skipped | `/price` response validation, PNG behavior, public/private/Telegram schemas |
| F34 Core suite | `:services:core:check --no-daemon` | F34 worktree | PASS | Full Core unit/check gate; focused PostgreSQL acceptance also passed |
| F33 live event store | tagged Kafka/ClickHouse replay | GitHub run `37411323045` on `a07f7cb` | PASS | `go-price-integration`: ClickHouse and Redpanda started; price projection/replay and recurring projection/replay passed |
| F10 private receipt storage | `.github/workflows/receipt-storage.yml` integration | GitHub run `37406496544` on `68253b7` | PASS | Authenticated SeaweedFS S3 operations and real ClamAV malware scan passed |
| F41.1 Go baseline | `go test ./... -count=1` | HEAD `7a0f992` before F41 code; Go 1.27.1 SHA-256-verified portable toolchain | PASS | advice, prices, recurring and projector tests all pass |
| F41.1 observed RED | `go test ./advice -run TestBuildEvidenceGroups -count=1` | new test only, no F41 production code | FAIL expected | Compiler reports missing `EvidenceRequest`, `EvidenceLine`, `BuildEvidenceGroups`, `EvidenceAlgorithmVersion` |
| F41.1 HTTP/contract RED | focused Go HTTP and Python contract tests | F41.1 tests before handler/schema | FAIL expected | Missing `NewEvidenceHandler` and OpenAPI evidence-groups path |
| F41.1 focused | `go test ./advice -run 'TestEvidence|TestBuildEvidenceGroups' -count=1` | F41.1 working tree | PASS | Threshold, source classification, nullable amount, stable hash/order, strict HTTP and golden fixture |
| F41.1 Go regression | `go test ./... -count=1`; `go vet ./...` | F41.1 working tree, portable Go 1.27.1 | PASS | All Go packages and vet pass |
| F41.1 Go build | `go build -o %TEMP%/finance-bot-analytics-f41.exe ./cmd/analytics-api` | F41.1 working tree | PASS | Analytics API binary built; registered evidence-groups route compiles |
| F41.1 contracts | `pytest tools/contracts/test_contracts.py -q -p no:cacheprovider` | F41.1 working tree | PASS, 43 tests | Authenticated internal path, strict request/response schemas and golden fixture validate |
| F41.1 GitHub regression | Actions `37413587958`, `37413587940`, `37413587971` | commit `bd8444f` | PASS | Python/contracts/Go, Core PostgreSQL and bot workflows all completed successfully |
| F41.2a observed RED | Focused `TransactionApiPostgresTest.productDecisionCanConfirmModelGuessAndSwitchPerMemberWithAudit`; contract schema test | isolated `finance_test_f41_20261006` before implementation | FAIL expected | `confirmed` returned HTTP 400; selection schema lacked `confirmed` |
| F41.2a focused PostgreSQL | Same focused Java test with migration V34 | F41.2a worktree | PASS | Confirm, idempotence, version switch, revoke/audit, second-member isolation and BFF CSRF route |
| F41.2a regression | `:services:core:check --rerun-tasks --no-daemon`; `pytest tools/contracts/test_contracts.py -q -p no:cacheprovider` | F41.2a worktree | PASS; 44 contracts | Full Core suite on isolated PostgreSQL 18.6 and strict decision schemas |
| F41.2a GitHub regression | Actions `37414351352`, `37414351376`, `37414351356`, `37414351388` | commit `d537b08` | PASS | Python/contracts/Go, private S3/ClamAV, bot and Core PostgreSQL workflows all completed successfully |
| F41.2b observed RED | `AdviceEvidencePolicyTest`; focused PostgreSQL do-not-buy API/shopping test; OpenAPI contract test | F41.2b tests before implementation | FAIL expected | Missing policy/types, then HTTP 404 list, then shopping still showed rule-backed Chips, then absent OpenAPI path |
| F41.2b focused PostgreSQL | `TransactionApiPostgresTest.doNotBuyListSeparatesModelGuessesAndScopesReceiptEvidenceToMember`; `AdviceEvidencePolicyTest` | isolated `finance_test_f41_20261006` | PASS | Personal input excludes second member; model-only guess, rule block, confirm/allow transitions, BFF route and analytics outage |
| F41.2b regression | `:services:core:check --rerun-tasks --no-daemon`; `pytest tools/contracts/test_contracts.py -q -p no:cacheprovider` | F41.2b worktree | PASS; 45 contracts | 235 Core tests, 2 skipped; public/BFF schemas and shopping reason code validated |
| F41.2b GitHub regression | Actions `37415625964`, `37415625735`, `37415625772`, `37415625724` | commit `7307fff` | PASS | Go/contracts, private S3, Core PostgreSQL and bot workflows all completed successfully |
| F41.3a observed RED | `pnpm --dir apps/web exec vitest run src/DoNotBuyPanel.test.tsx` | test before component | FAIL expected | Missing DoNotBuyPanel import; new Web behavior absent |
| F41.3a Web | `pnpm --dir apps/web exec vitest run`; `pnpm --dir apps/web run build` | F41.3a worktree | PASS, 53 tests; build | Model guess, confirm and allow with CSRF, viewer read-only, outage, distinct shopping reasons, TypeScript and production bundle |
| F41.3a GitHub regression | Actions `37416106918`, `37416106876` | commit `8706159` | PASS | Python/contracts and bot test workflows completed successfully |
| F41.3b1 observed RED | Focused PostgreSQL Telegram action test; OpenAPI contract test | F41.3b1 test before routes | FAIL expected | Internal actor-scoped do-not-buy routes missing |
| F41.3b1 Core and contracts | `:services:core:check --rerun-tasks --no-daemon`; `pytest tools/contracts/test_contracts.py -q -p no:cacheprovider` | isolated PostgreSQL `finance_test_f41_20261006` | PASS; 46 contracts | Actor-scoped read/write permissions and persistent human decisions; full Core suite |
| F41.3b1 GitHub regression | Actions `37416646936`, `37416646946`, `37416646932`, `37416646937` | commit `7089a63` | PASS | Python/contracts, private S3, Core PostgreSQL and bot workflows completed successfully |
| F41.3b2 observed RED | Python client, renderer, command and contract focused tests | F41.3b2 tests before implementation | FAIL expected | Missing `/nobuy`, client actions, renderer and decisions route; shopping rejected rule-backed blocks |
| F41.3b2 regression | `pytest services/python/telegram_gateway/tests services/python/presentation/tests tools/contracts/test_contracts.py -q -p no:cacheprovider`; `:services:core:check --rerun-tasks --no-daemon` | F41.3b2 working tree, isolated PostgreSQL | PASS, 166 Python/contracts; full Core | Actor-scoped decisions route, separate guesses, viewer read-only, safe callback key and shopping reason labels |
| F41.3b2 GitHub regression | Actions `37417405513`, `37417405369`, `37417405537`, `37417405466` | commit `41ace8f` | PASS | Python/contracts, private S3, bot and Core PostgreSQL workflows completed successfully |
| F41.3c observed RED | `:app:compileDebugAndroidTestKotlin` | tests before Android implementation | FAIL expected | Missing `FinanceModels.doNotBuy`, advice UI state and decision callback |
| F41.3c Android instrumentation | `:app:connectedDebugAndroidTest --no-daemon` | emulator-5556, Android SDK/JDK from Unity | PASS, 49/49 | Evidence parser, separate model guesses, localized UI and shopping reason; debug APK installed and launched; SHA-256 `9265BE1CEDBF9648B2CED24BE025A38D73F259441BD0196ABC7CDF63F6FA4267` |
| F38 recurring projection | `go test ./... -count=1`; `go vet ./...`; tagged integration; Core check/PostgreSQL; Python/contracts; Web; Android | `7c18051` | Local gates PASS; GitHub tests and Go/Kafka/ClickHouse/contracts PASS | Actions runs `37393878671` and `37393878760`; recurring event projection/replay step passed; local live endpoints absent |
| F39 Core member/API/BFF/Telegram | Four focused Core tests; `:services:core:check --no-daemon` | isolated PostgreSQL `127.0.0.1:55438`, commit `b1e64c4` | PASS | Owner mute/restore, other-member isolation, stale-ID rejection, CSRF BFF restore, Telegram actor actions, unchanged ledger |
| F39 Web | `pnpm --dir apps/web exec vitest run`; `pnpm --dir apps/web run build` | commit `b1e64c4` | PASS, 49/49; build pass | TypeScript check and Vite production bundle pass |
| F39 Python/contracts | presentation + Telegram pytest; `tools/contracts` pytest | commit `b1e64c4` | PASS, 109 passed; 54 passed/2 optional skips | Client validation, callbacks, renderer, route security and muted-series schema |
| F39 Android | `:app:connectedDebugAndroidTest`; launch debug APK | emulator `emulator-5556`, commit `b1e64c4` | PASS, 42/42 | APK SHA-256 `3FB2169FD48E14321522548CDACA7DD0763DEB2701B2495B2222146DA7D5796E`; installed and launched |
| F39 GitHub PostgreSQL | Actions run `37399400317`; persisted Core event schema check | commit `2de08d9` | PASS | Receipt event validates against `finance.receipt.v1.schema.json`; Core check passed. |
| F39 GitHub regression | Actions runs `37399400129`, `37399400232` | commit `2de08d9` | PASS | Bot test suite and Python/contracts suite passed. |
| F40.1 observed RED | Go `go test ./advice -count=1` | tests + golden fixture, HEAD `a7de34f` | FAIL as expected | Compiler reports missing `WasteRequest`, `WasteLine`, `WasteAlgorithmVersion`, and `BuildWasteReport`; behavior is not implemented. |
| F40.1 Go algorithm/API | `go test ./... -count=1`; `go vet ./...`; build `./cmd/analytics-api` | F40.1 working tree | PASS | Exact money, denominator, corrections, provenance, day series, required-field validation; Go package regression clean. |
| F40.1 contract | `.venv\\Scripts\\python.exe -m pytest tools/contracts/test_contracts.py -q` | F40.1 working tree | PASS, 41 passed | OpenAPI operation and strict golden fixture schemas validate. Pytest cache warning is permission-only. |
| F40.1 GitHub regression | Actions `37403107898`, `37403107896`, `37403107903` | commit `f3da7a0` | PASS | Go/contracts, Core PostgreSQL/contracts, and general bot test workflows all completed successfully. |
| F40.2 Core PostgreSQL/API | isolated `finance_test_f40_20261006`; focused report tests | commit `68253b7` (verified pre-commit) | PASS, 6 tests | Personal/family receipt scope, per-owner allow decision, null verdict/amount, 50,001-line cap, analytics outage, Telegram/shared report and existing local-date/rolling-food/digest regressions. |
| F40.2 Core suite | `:services:core:check --no-daemon` | commit `68253b7` (verified pre-commit) | PASS | Core compile, test and check tasks pass. PostgreSQL acceptance is recorded separately above. |
| F40.2 contracts | `.venv\\Scripts\\python.exe -m pytest tools/contracts/test_contracts.py -q -p no:cacheprovider` | commit `68253b7` (verified pre-commit) | PASS, 42 passed | FinanceReport now requires strict waste result schema; complete Go fixture and unavailable fallback validate. |
| F40.2 diff hygiene | `git diff --check` | commit `68253b7` (verified pre-commit) | PASS | No whitespace errors. |
| F40.2 GitHub regression | Actions `37406496536`, `37406496576`, `37406496591`, `37406496544` | commit `68253b7e8e3fe5444ed0acf83e85f9fa27dbf848` | PASS | Core/PostgreSQL, Python/contracts and bot workflows passed. Private S3/ClamAV workflow first timed out; failed job rerun passed. |

## Failures and attempts

- First PostgreSQL attempt targeted shared `finance_test`, whose non-empty schema had no Flyway history. Retried on newly created, isolated `finance_test_f34_catalog_20261005`; targeted acceptance passed. The shared test database was not changed.
- F39 Web build first caught an inferred fixture type that rejected nullable totals; explicit `RecurringProjection` typing fixed the test-only issue, then 49 tests and build passed.
- F39 Android test first asserted a virtualized `LazyColumn` row before it was composed; `performScrollTo` could not target an off-screen row. A second attempt used an out-of-range item index. Root-cause review found the UI row existed but test navigation assumed visibility/index semantics. A minimal seven-item fixture and valid scroll to index 6 passed; full instrumentation then passed 42/42. One initial manual launch used the wrong activity package; manifest namespace confirmed `com.decorix.finance.MainActivity`, and corrected launch succeeded.
- F40.1 additional RED tests caught omitted/null required API fields returning 200, repeated products losing their product key, and Go byte-length checks rejecting valid Cyrillic names. Strict decoding, key preservation and Unicode character counts fixed all three.
- F40.2 decision overlay RED: the receipt input contained the expected member/product key, and PostgreSQL had the matching allowed decision, but Java's `user_id = ANY (?::uuid[])` lookup returned no overlay. Removing the array lookup and applying tenant-scoped rows against the bounded fact owner/key sets made the PostgreSQL acceptance pass. Facts: both SQL-array variants failed; direct RLS-scoped SQL found the decision; tenant-scoped lookup passed the same acceptance. Likely cause is JDBC UUID-array binding; no claim that the driver internals were independently proven.
- F40.2 CI: the unrelated private receipt S3 integration timed out once in SeaweedFS HTTP; rerunning the failed GitHub job passed. Core/PostgreSQL, contracts and bot workflows passed on commit `68253b7`.
- GitHub PostgreSQL run `37398375404` failed after the new receipt acceptance left a `receipt.confirmed` outbox event for the shared contract validator. `test_core_migration.py` mapped transaction, budget and debt events but omitted receipt. Added the receipt schema mapping. Local contract suite passes 54/2 skips; local integration test connects only after using the migrator role, but its cleaned database has no persisted events, so CI must verify event replay.
- F41.2b full Core check first failed one unit mock because shopping now calls the four-argument decision overlay. Updated the existing mock expectation to the new `Set.of()` evidence input; focused test and full 235-test Core check then passed.
- F41.3a initial Web test found the confirmed status inside a longer paragraph, so it used a dedicated span for the status. TypeScript build also found mixed mutation return types; an async mutation now returns void after awaiting the selected action. Focused tests, all 53 Web tests and production build then passed.
- F41.3c first full Android run passed 48/49; an existing debt-screen test failed because inserting the new tab before debts moved the horizontally scrollable navigation target. The focused failure reproduced. Moving the new tab to the end restored the focused test; the full 49-test suite then passed.

## Next action

- Finish F45.4 read-only SQLite extractor regression, then close F45 only after the importer acceptance gates pass. Live legacy-data rehearsal remains unverified until a real source database and reviewed mapping are available.

## E3.37 F40.2 Core optional-spend report — 2026-10-06 05:53 MSK

- Observed RED: authenticated report did not include `waste`. Added PostgreSQL/API acceptance for personal and family scope, owner-specific allow decisions, null verdict/amount and report usability during analytics outage. Added a 50,001-line fixture to prove overflow stays in Core and never reaches Go.
- Core now selects only confirmed receipt lines joined to posted expense transactions inside the exact local-date window. It computes each item key with the shared product identity policy, overlays allowed decisions by receipt owner, preserves nullable evidence and sends at most 50,000 rows to Go.
- Core ends its receipt/decision read transaction before HTTP. Query/analytics outage and overflow return an explicit partial unavailable DTO while normal finance totals remain available. Empty selections return complete `no_reviewed_items` without HTTP.
- GREEN: six focused PostgreSQL report/API tests; full `:services:core:check`; contracts 42 passed; `git diff --check` clean.
- Root cause analysis: two failed Java array-filter variants omitted a stored allowed decision. Direct application-role SQL under the same tenant RLS context found it; tenant-scoped lookup followed by owner/key filtering passed. Most likely failure was PostgreSQL JDBC UUID-array binding. Further internal-driver debugging was unnecessary after the replacement passed the target test.
- F40.2 commit `68253b7` pushed. Actions `37406496536`, `37406496576`, `37406496591`, and rerun `37406496544` passed.
- Status: COMPLETE. Next: start F40.3 client tests.

## E3.38 F40.2 pushed and CI GREEN — 2026-10-06 06:02 MSK

- F40.2 commit `68253b7e8e3fe5444ed0acf83e85f9fa27dbf848` is on `origin/feat/saas-rewrite`.
- GitHub Core/PostgreSQL (`37406496536`), Python/contracts (`37406496576`), Telegram/bot (`37406496591`) and private receipt storage (`37406496544`) workflows pass. S3/ClamAV passed after rerunning its first HTTP timeout.
- F40.2 is complete. F40.3 begins with client acceptance tests for available data, correction/source disclosure and honest unavailable states.

## E3.40 F40.3 client presentation GREEN — 2026-10-06 06:31 MSK

- Observed RED in Web, Telegram renderer/caption and Android acceptance before implementation. Available state now shows exact optional/reviewed totals, ratio, source totals, top optional goods and allowed corrections. Partial states show reason/count and never invented sums.
- Web report panel is localized RU/EN and uses only the Core waste DTO. Android parses nullable totals and renders the same details and availability states. Telegram PNG grows to include source/top/correction columns; text fallback and caption preserve partial reasons.
- GREEN: Web 49/49 and TypeScript/Vite production build; Python presentation/Telegram 112 passed; Android test compile and debug APK build passed; Android instrumentation 45/45 on isolated `FinanceBotF38` / `emulator-5556`; installed and launched. APK SHA-256 `87D8757BA4FE4D9356476F620109B4797088FCAA2DBA973331FFE7047CF09F89`. `git diff --check` passes.
- Local Android used SHA-256-verified portable Temurin 17 from the per-user cache and Unity Android SDK; no project SDK configuration changed. Existing `Quest_Test` emulator was not touched.
- F40.3 was committed and pushed as `ec1a373119263914f23f5e5f7fcbd021bd2947a7`. Python/contracts `37409599307` and general tests `37409599306` passed. F40 is complete.

## E3.41 F31.2 daily optional-spend chart — 2026-10-06 06:37 MSK

- User-approved F31 design excludes placeholder series. F40.3 now supplies Core's complete `optionalByDay` map, including exact zero days for available reports.
- Goal: render those exact daily values in RU/EN Web, Android, Telegram PNG and Telegram text. Hide the series unless the waste result is available; partial/unavailable states retain their reason and counts without a chart or inferred amounts.
- Observed RED: Web report test cannot find the optional-by-day figure; Python renderer tests do not find the orange PNG series or daily text row (2 failed, 22 passed); Android instrumented-test Kotlin compilation reports missing `optionalByDay` model property.
- First step complete: fixtures cover populated and zero-valued rows plus empty unavailable series. Implement contract parsing and localized client rendering, then run the focused gates.
- Android instrumentation attempt: 45/46 passed; the new test searched for optional days while scrolled to the first waste card. The section is correctly later in the virtualized `LazyColumn`; advance to its verified item indexes before asserting its date rows.
- Focused Android rerun reached all available-state assertions, then found a second test harness error: Compose allows one `setContent` per test. Keep unavailable-state coverage in the existing separate test instead of resetting content within one test.
- GREEN: Web 49/49 and TypeScript/Vite build; Python presentation/Telegram 114 passed; contracts 42 passed; Android debug and instrumentation APKs built, 46/46 tests pass on isolated `FinanceBotF38` / `emulator-5556`. APK installed and launched; SHA-256 `AACE4090332EE28A769688F50E62EFE080818A7B445F824540F0A66BD9BE3C5A`. `git diff --check` passes.
- Web and Android render Core `optionalByDay` dates and exact zero/non-zero amounts only when waste data is available. Telegram PNG adds a distinct optional-spend series to the daily graph; text fallback lists the exact Core dates and amounts. Python rejects incomplete available series and non-empty unavailable series rather than filling values.
- A text-renderer call initially used names discarded during unpacking; stack trace traced all six failures to that single call. Keeping `from_date`/`to_date` fixes it; the focused renderer suite passes 24/24 and the complete presentation/Telegram suite passes 114/114.
- F31.2 implementation is GREEN locally. Remaining action is separate feature commit/push and GitHub check; F31's price series remains F33-dependent.
- Commit `a07f7cb65cfab5dc76698bc9dc84ff28e1ca473d` is on `origin/feat/saas-rewrite`; GitHub workflows `37411323031` and `37411323045` started and are pending.
- Preserve current expense/category/limit chart behavior, report text fallback, and existing user files. Commit goal separately only after local and CI gates.

## E3.30 F39 recurring reminder mute and restore — 2026-10-06 04:09 MSK

- F39 implements tenant/member/`recurring` mute preferences in the existing RLS-protected `muted_suggestions` table. Core validates current active membership and series existence before write; muted series leave active lists and derived warning/income/expense totals, while underlying transaction rows remain unchanged. Owner can restore; another member cannot affect that preference.
- Authenticated Core API, CSRF-protected Web BFF, Telegram actor actions, Python callbacks, Web controls and Android controls share the same projection. Both clients show localized restore lists and handle muted-only history without fake totals.
- GREEN: Core full check plus four focused PostgreSQL/API/BFF/Telegram service tests; Web 49/49 and production build; Python presentation/Telegram 109 passed; contracts 54 passed/2 optional skips; Android emulator instrumentation 42/42. APK SHA-256 `3FB2169FD48E14321522548CDACA7DD0763DEB2701B2495B2222146DA7D5796E` installed and launched on `emulator-5556`.
- Android test attempts exposed off-screen `LazyColumn` virtualization and a wrong synthetic item index; root-cause review corrected the fixture and scroll target. Web build caught and fixed a test fixture's narrow inferred type. Final reruns passed. `git diff --check` remains to verify before commit.
- Status: COMPLETE at local feature gates; GitHub PostgreSQL regression fix pending. Feature commit: `b1e64c4`.

## E3.31 F39 commit verified — 2026-10-06 04:15 MSK

- Verified commit `b1e64c4` contains only the green F39 goal and its progress/parity records. `git show --check` passed; unrelated user artifacts remain unstaged.
- F40 is now the active goal in ANALYSIS. No F40 implementation has started.

## E3.32 F39 GitHub event-schema follow-up — 2026-10-06 04:24 MSK

- GitHub run `37398375404` passed all other jobs but failed the PostgreSQL persisted-event test with `KeyError: 'receipt'`.
- Root cause: F39 PostgreSQL acceptance now emits a real `receipt.confirmed` event, while `test_core_migration.py` validated only transaction, budget and debt aggregate types.
- Added the receipt v1 schema to the persisted-event validator and to F39's parity test inventory. Local `tools/contracts` remains green: 54 passed, 2 optional skips. Local DB cleanup leaves no persisted outbox event for the integration validator; CI rerun is required.
- F39 remains in VERIFYING until the new GitHub PostgreSQL run passes. F40 implementation has not started.

## E3.33 F39 GitHub verification — 2026-10-06 04:34 MSK

- Follow-up commit `2de08d9` is pushed. GitHub Core PostgreSQL `37399400317`, Python/contracts `37399400232`, and bot tests `37399400129` all passed.
- Receipt aggregate schema now validates the persisted `receipt.confirmed` event. F39 is COMPLETE after local gates, emulator delivery proof, and remote regression gates.
- F40 is active in ANALYSIS. No F40 implementation has started. Next: lock acceptance and smallest independently testable end-to-end slice.

## E3.34 F40.1 acceptance RED — 2026-10-06 04:49 MSK

- F39 completion is verified remotely on commit `2de08d9`; progress-only record `a7de34f` is pushed. Parity validation passes locally.
- Defined F40 subgoals: Go algorithm/API, Core tenant/member-scoped report integration, then RU/EN Web/Android/Telegram presentation. F31 consumes F40's real daily series after F40.
- Legacy `waste_summary` denominator uses stored non-null verdict rows; PLAN §8.2 confirms shares use verdict-bearing positions. Null verdict stays outside evidence; allowed decisions apply per receipt owner, including family report scope.
- `ReportService` already supplies report window, timezone, and personal/family scope. Core must join confirmed receipt items to posted expense transactions, apply `user_product_decisions` by owner, and send bounded facts to Go.
- Added golden contract `contracts/analytics/advice-waste.v1.json` and behavior tests for denominator, verdict/source totals, corrections, local-day bucketing, missing amounts, validation and input-version stability.
- Observed RED: Go `go test ./advice -count=1` fails because F40 Go API/model/function are absent. F38 is last green Go production baseline; F39 did not change Go.
- Next: implement only F40.1 algorithm/API until its gates pass, then commit before Core integration.

## E3.35 F40.1 local GREEN — 2026-10-06 05:11 MSK

- Implemented versioned `advice-waste.v1` exact-money summary, strict service-token endpoint, OpenAPI schemas and golden contract. Null verdicts stay outside denominator; owner-allowed corrections remain visible and leave optional totals; unknown provenance stays unknown; missing reviewed amounts suppress totals.
- Added deterministic input hash, local-day zero-filled series, exact large sums, half-even share rounding, top optional items/repeats/corrections, bounded inputs and strict required/unknown/null JSON field checks.
- Extra RED tests found and fixed required-field acceptance, missing repeat product keys, and Unicode character/byte limit mismatch.
- GREEN: full analytics Go suite, `go vet ./...`, API build, and contracts `41 passed`. `git diff --check` passed. Local gates complete; commit/push and remote regression are next.

## E3.36 F40.1 pushed and CI GREEN — 2026-10-06 05:18 MSK

- Commit `f3da7a0` pushed to `origin/feat/saas-rewrite`.
- GitHub run `37403107898` passed contracts and Go tests/vet; `37403107896` passed Core PostgreSQL plus contracts; `37403107903` passed the general Telegram/bot tests.
- F40.1 is complete. F40 remains open; F40.2 begins with Core query scope, owner-level allow overlay, bounded handoff and report availability on analytics failure.

## E6.6 F33 confirmed-item Web price history — 2026-10-05 01:45 MSK

- Observed RED: two Web acceptance tests failed because confirmed receipts had no price-comparison action or chart. GREEN: confirmed receipt items lazily call the existing BFF/Core price-history endpoint; Web displays current unit price, prior median, Core signal and purchase history. SVG chart uses only returned history and appears only with at least two points. No history shows the actual current price and no baseline/chart. Draft receipts never query prices; invalid amount/quantity hides the query action.
- PASS focused price tests 2/2; full Web 38/38; `pnpm build` type check and production bundle; `git diff --check`. One unrelated test-only TypeScript issue (`saved: unknown` spread in `App.test.tsx`) blocked the build; typed it as `Partial<typeof preferences>`, with no runtime change. `PROGRESS.md` and `.agent/PROGRESS.md` mirror hashes match.
- F33 remains in progress: tagged Kafka/ClickHouse replay explicitly skipped because this host has no integration endpoints or Docker/Podman/WSL. Telegram PNG/catalog chart still needs F34 owner-scoped product search. Next: implement F34 using the existing F33 query boundary.

## E6.7 F34 product catalog and `/price` — 2026-10-05 02:39 MSK

- Observed RED in Go/Core/Web/Python: missing catalog API, member-scoped Core endpoint, Web product route, Telegram `/price`, and PNG renderer failed their new tests. Implemented each over confirmed price-history projection; blank-query catalog requires 3 purchases, search accepts 1, and charts require 2 real points. No baseline or series is invented.
- PASS Go suite, vet, Linux build; Web 40/40 and production build; Python presentation/Telegram 84 passed; contract suite 48 passed/2 skipped; Core check; isolated PostgreSQL member-scope acceptance. `git diff --check` clean.
- First Core DB acceptance run used a populated schema without Flyway history and failed before test logic. Created a separate empty `finance_test_f34_catalog_20261005`; the targeted PostgreSQL test passed. Shared test DB stayed unchanged.
- F34 acceptance is complete independently of F33's unavailable live infrastructure gate; parity registry marks F34 complete. Commit `00068b5735d74111c4f1eea91ea6fb4d58f364e5` checkpoints the accumulated migration files through F34. F35 onward uses separate goal commits.

## E6.8 F35 shopping rhythm — 2026-10-05 04:40 MSK

- Legacy baseline: `services/shopping.py` requires 3 purchases, uses the median of intervals at least 3 days, shows due items within a 3-day horizon, expires them after 2 overdue intervals, estimates one usual unit price per product, and states it is not inventory.
- Added Go acceptance tests before implementation for three-purchase threshold, median interval/price, same-receipt duplicate rows, stale boundary, and tenant/member separation.
- Observed RED: `go test ./prices -run TestShoppingList -count=1` first failed at the missing `BuildShoppingList` symbol. First invocation could not start because Go was absent from PATH. Installed pinned Go 1.27.1 under user cache; official archive SHA-256 matched `a3911b5e0e1b1053f25ed0675f4c1c6aad1e2bfcf253df2b9be4caabd2edd95d`. Initial implementation then failed 2 median assertions because the price slice was unsorted; sorting before `median` fixed both.
- PASS F35 Go policy tests: focused `TestShoppingList` and full `go test ./prices`; `gofmt` applied.
- Current F35 gate: implementation and authenticated API/client parity remain. F35 test file is untracked and excluded from the F34 commit.

## Verification evidence

- Direct GitHub clone succeeded into the user-requested workspace.
- Origin URL verified; main SHA equals the original plan's frozen v1 SHA.
- `git ls-files`: 85 tracked files. `origin/feat/android-fastapi` fetched.
- New branch creation succeeded after filesystem escalation for protected `.git`.
- No production code changed. Original main preserved.
- Python 3.12.14 virtual environment created in ignored `.venv`; dependencies from
  `requirements.txt` installed successfully.
- PASS `PYTHONIOENCODING=utf-8`, `TESSERACT_CMD='C:/Program Files/Tesseract-OCR/tesseract.exe'`
  `python smoke_test.py`: exit 0, all smoke assertions passed.
- PASS same environment `python handlers_test.py`: exit 0, all Telegram handler
  scenarios passed; Ollama provider unavailable and existing fallback behavior ran.
- PASS same environment `python receipt_test.py`: exit 0, synthetic receipt passed
  with Tesseract. Two private receipt fixtures were absent and explicitly skipped;
  Ollama Vision/text models were unavailable, so those integrations remain unverified.
- Generated `contracts/parity/feature-parity.yaml`: 58 legacy F01–F58 entries
  parsed from the approved plan plus F59 Android parity and F60 AI Gateway.
- Contract baseline: `.github/workflows/contracts.yml`, OpenAPI 3.1 transaction
  routes, JSON Schema transaction event and pinned Python validator requirements.
- PASS `python -m pytest tools/contracts/test_contracts.py -q`: 8 passed, 0 warnings.
  The first run found that zero amounts were accepted; schema now requires positive
  decimal strings and rejects numeric, negative, zero, over-precision values.
- Matrix records verified June/September 2026 runtime/service releases, installed
  Java/Python/Node observations, and explicit absent runtimes.
- E1.1: official Gradle 9.8.0 wrapper installed with SHA-256 validation, Java 17
  toolchain, dependency locking and generated `services/core/gradle.lockfile`.
- E1.1 TDD: `MoneyAmountTest` first failed to compile because the class was absent;
  after implementation, `:services:core:test` and full `:services:core:check
  --rerun-tasks` passed. Tests cover canonical positive decimal input, cent
  normalization, and invalid/ambiguous forms.
- Legacy regression after E1.1: `smoke_test.py` passed; contracts remain 8 passed.
- E1.2 adds Flyway V1 tables for tenants, memberships, accounts, transactions,
  idempotency, audit, outbox and inbox; composite tenant/account FK; decimal and
  status constraints; tenant/history and outbox retry indexes; forced RLS policies.
- PASS against a real isolated PostgreSQL 18.6 server: migration applied, app-role
  saw only tenant A despite an existing tenant B row, cross-tenant insert denied,
  duplicate idempotency key denied, and rollback removed transaction and outbox
  together. Schema and non-login role are uniquely named and cleaned per test.
- PASS `pytest tools/contracts -q` with `FINANCE_TEST_DATABASE_URL`: 11 passed,
  including all PostgreSQL integration checks. The CI database workflow runs this
  suite against PostgreSQL 18.6.
- E1.3 adds V2 stable `users`/`external_identities` UUID mapping with a safe RLS-
  suspended backfill and RLS restoration; Keycloak `sub` is mapped only after JWT
  validation and active tenant membership is required before business access.
- E1.3 POST/GET list now enforce positive RUB decimal validation, route-scoped
  idempotency replay/conflict, tenant context via `set_config(..., true)`, and one
  SQL transaction for business row, audit, outbox and stored replay response.
- Event body conforms to the versioned JSON Schema; stable cursor paging orders by
  `(occurred_at, id)` and uses a bounded opaque cursor. Error responses use
  `application/problem+json` with code and trace ID.
- PASS `:services:core:check --rerun-tasks` against PostgreSQL 18.6, including
  Spring MVC tests for create/replay/conflict, unknown member denial, list paging,
  bad money, unauthenticated request and durable audit/outbox state.
- PASS `pytest tools/contracts -p no:cacheprovider -q` with the same DB: 12 passed,
  including V1/V2 execution, legacy identity backfill/RLS restoration and validation
  of persisted API event payloads against its public schema.
- PASS `handlers_test.py`: all legacy Telegram UI scenarios passed after core work.
- Core CI now runs Java 17 Gradle checks and Python contract/database checks against
  pinned PostgreSQL 18.6 image digest `sha256:0377e72c5289ed2f98cf61b1a9c2db9eb9d300317fe14244492fbc94343b3d04`.
- E0.2 mobile delta identified on `origin/feat/android-fastapi`: FastAPI auth/API,
  SQLite account context, T-Bank import, Kotlin Compose Android and Flutter client.
  Full requirements remain source docs on that branch; inspect before assigning
  parity or copying code. Approved v2 target remains Java + Kotlin Android.

## Failures and attempts

- Managed worktree creation unavailable in the initially empty workspace: Not a git repository.
- Initial local clone attempt failed on Git safe.directory ownership. No global trust change made.
- Bundled Git default exec-path omitted HTTPS helper; process-local GIT_EXEC_PATH
  pointing to bundled mingw64/bin resolved helper discovery.
- Network sandbox blocked GitHub; authorized escalated clone succeeded.
- Bundled git-submodule shell lacks basename/sed; inspect git tree for gitlinks instead.
- Old checkout's venv could not launch its referenced Python310; created isolated local venv.
- Initial baseline smoke run hit Windows cp1251 output on checkmark characters; rerun
  with `PYTHONIOENCODING=utf-8` passed without source change.
- First contract-test dependency install hit sandbox network error; escalated pip
  install succeeded. No registry-wide trust or permanent network config changed.
- First contract test run exposed zero amounts accepted (1 failing case). A stricter
  positive-only regex initially rejected canonical money due test expectation for
  leading-zero strings; updated valid canonical cases and all 8 checks now pass.
- E1.1 wrapper attempt with Gradle 9.8 URL (missing `.0`) returned 404; corrected to
  official 9.8.0 distribution and verified its published SHA-256. Initial Gradle
  cache path was outside the sandbox; setting `GRADLE_USER_HOME` to workspace
  `.gradle-cache` resolved it.
- PostgreSQL binary cluster initialization emitted a restricted-token warning but
  completed. Sandbox blocked `pg_ctl` process startup; approved elevated local
  launch succeeded. Python bundled runtime mishandled the Unicode workspace CWD;
  a temporary ASCII `R:` drive allowed the test runner to access the same files.
- Spring Boot 4 uses Jackson 3 and split MVC/Flyway test starters; initial compilation
  and startup exposed these dependency/package differences, corrected before green.
- First Java test reached the app but found Flyway auto-configuration missing; added
  the Boot Flyway starter and verified migrations on a clean database.
- First event-contract check found stale test data from the pre-schema payload shape;
  recreated only the isolated task-owned test database and verified clean events.

## Environment observations

- `java`, `mvn`, `gradle`, `docker`, `psql`, `go`, Android SDK absent from PATH.
- Standard Docker Desktop executable path absent. Search for installed/portable
  runtimes before deciding whether PostgreSQL integration work is blocked.

## E1.4 evidence

- TDD red: added API cases for transaction detail and void before implementation;
  the endpoint returned 404 as expected.
- Implemented tenant-scoped GET detail and POST void with required Idempotency-Key,
  optimistic version via If-Match, replay-safe response, version increment, atomic
  audit before/after snapshots and `transaction.voided` outbox event.
- Stale If-Match returns 412 and does not add audit/outbox rows. Duplicate key
  returns the original successful response.
- Core API checks pass with real PostgreSQL, including signed RSA JWT checks for
  valid issuer/audience and rejection of wrong issuer, audience and expired token.
- Database migration and app execute under separate non-superuser, NOBYPASSRLS,
  non-owner roles. Production grants omit DELETE and deny Flyway history access.
- V3 enables and forces tenant-root RLS; Python contract tests verify all
  tenant-scoped tables enforce RLS and another tenant's rows are invisible.
- PASS `:services:core:check --rerun-tasks`; PASS `pytest tools/contracts -p
  no:cacheprovider -q` (12 passed). `git diff --check` passes.
- Root `PROGRESS.md` mirrors this record. User-owned `.freebuff/` and
  `CODEX_AUTONOMOUS.md` remain untracked and untouched.

## E2.1 evidence

- Added Flyway V4 member_profiles with tenant RLS, composite membership FK,
  timezone, onboarding state and planned income fields.
- Added authenticated `POST /api/v1/tenants` to create identity if needed, tenant,
  owner membership and initial profile in one transaction. IANA timezone is
  validated before identity creation; invalid input leaves no identity behind.
- Added `GET /api/v1/me/tenants`; membership RLS allows subject-scoped discovery,
  then tenant RLS limits returned tenant details.
- OpenAPI now describes onboarding, tenant listing, and stale-version HTTP 412.
- TDD red: onboarding initially returned 404; invalid timezone initially passed.
  Both behavior tests pass after implementation.
- PASS `:services:core:check --rerun-tasks` on PostgreSQL 18.6.
- PASS PostgreSQL-backed contracts: 12 passed, including V1–V4 migrations,
  profile RLS, tenant isolation, OpenAPI validation and event schema.
- First E2 test launch used malformed admin JDBC host; corrected to separate host
  and credentials. No code or database state was changed by that failed launch.

## E2.2, E2.3 and E3.1 progress update — 2026-10-01

- E2.2 remains IN PROGRESS pending the complete F03–F09 parity gate, the onboarding budget/reset flow,
  free-text AI draft integration, remaining transaction types, and real browser proof of error/permission states.
- Web now includes Keycloak OIDC/PKCE, separate tenant/member names, optional income, profile read/update,
  dashboard summary, server-side date/type/search/cursor filters, RU/EN, create/history, versioned edit,
  date correction, repeat-as-new, safe void, BFF routes, and recoverable profile/transaction error states.
- Transaction PATCH requires If-Match + Idempotency-Key, locks the row, updates fields atomically, and
  writes before/after audit plus `transaction.updated`; debt-payment edits are rejected until compensation exists.
- Web UI test coverage now includes create/history, edit/date, repeat, void, filters/profile update,
  onboarding skip, and profile retry after a 503. Latest web suite: 7 passed; TypeScript and Vite build pass.
- E2.3 Android is COMPLETE for the approved login + first create/list vertical slice. AppAuth PKCE,
  encrypted Keystore storage, RU/EN, actual Keycloak login, Java API list/create and installed debug APK
  were verified; unit + 3 connected instrumentation tests pass. APK SHA-256:
  `68730F0E5D7C747B7A3ED6C8380D254A0E82EBAE4FD6C9C95EB3A3FD575F514B`.
- E3.1 monthly family/personal budgets is IN PROGRESS. Flyway V5 adds tenant-scoped budget values and
  V6 adds active tombstones so reset retains version history. RLS is enabled/forced. Defaults match v1.
- Budget API returns family, personal override, effective limit, versions, profile-timezone month spending,
  and 90%/100%/disabled status; refunds reduce category spend. Family edits require owner/admin, personal
  edits disallow viewer. PUT is versioned/idempotent; reset is idempotent; mutations create audit/outbox.
- Web has RU/EN budgets screen with category/total limits and personal/family scope. Android budget parity
  remains outstanding for its later stage.
- TDD evidence: API edit test was red on 405 then passed; profile error recovery test was red then passed;
  budget GET was red on missing endpoint, then PostgreSQL tests passed for defaults, 99% near alert,
  family/personal precedence, stale version 412, reset fallback, tombstone version reactivation, and viewer 403.
- PASS Java `:services:core:check --rerun-tasks --no-daemon` against PostgreSQL 18.6; Python contracts
  14 passed; web 7 passed and production TypeScript/Vite build passed; `git diff --check` passes.
- Next: complete basic reports and remaining F24 behavior, then live web/API permission/error checks. E4–E10 remain planned.
## E3.2 debt lifecycle update — 2026-10-01

- Added tenant-scoped debt CRUD/read, atomic debt payment, linked transaction and exact captured balance effect; duplicate payment requests replay safely.
- Voiding the linked transaction restores only its recorded debt effect and reopens the debt. Overpayment is capped at the outstanding balance; payoff forecast is an estimate and never posts interest to the ledger.
- Added owner/admin balance adjustment with If-Match, idempotency, version increment, audit snapshot and `debt.balance_adjusted` outbox event. OpenAPI and event schema include the new event.
- TDD evidence: payment test first failed due a mistaken expected amount; corrected arithmetic (after undo, 1000 balance minus 900 is 100). Adjustment endpoint first returned 404, then API test passed for repeat, one audit entry and stale-version 412.
- PASS `:services:core:check --rerun-tasks --no-daemon`; PASS PostgreSQL contracts 15/15; PASS web tests 7/7, TypeScript build and Vite production build. A first contracts run caught the missing adjustment event enum; schema fixed and rerun green.
- Debt UI, adjustment/reversal browser proof and concurrent payment race coverage remain outstanding. Budget proposal/apply, rolling food limit and basic reports also remain in E3.
## E3.3 budget proposals, rolling food and debt web update — 2026-10-01 13:56 MSK

- Repository revision checked: `fb00ede1b47aecf833a482ea2b8e04e7751dd969`, branch `feat/saas-rewrite`. Work remains uncommitted per user instruction to commit after the complete main plan.
- Flyway V8 adds tenant-RLS budget proposals with a 30-day expiry, proposed limits and the family-budget version snapshot. API creates a deterministic 70%-of-income draft; creation alone never changes budgets. Explicit apply locks the tenant, rejects stale snapshots with 412, applies category and total limits in one DB transaction, and records idempotency/audit.
- Flyway V9 adds independent monthly/rolling7 budget periods. Rolling food spend covers the member-local dates from today minus six days through today; only food accepts rolling7. GET shows limit, effective personal/family scope, spend, alert and separate versions.
- Added same-origin BFF and RU/EN web flows for budget proposal preview/apply and rolling food limit. Proposal and apply operations are explicit; food spend is visible beside its limit.
- Added BFF debt routes and RU/EN web screen for debt creation, payment, balance adjustment and payoff forecast. Debt payment uses If-Match; transaction void restores captured debt effect.
- TDD/evidence: proposal API was red on missing route then passed creation-without-mutation, replay/conflict, explicit atomic apply and stale snapshot checks. Rolling-window PostgreSQL test includes an expense exactly seven days old and excludes it. Debt UI test was red before screen existed then passed create/payment/adjust/forecast requests. Concurrent payment test ran two requests at version 1; exactly one returned 200 and one 412; resulting balance 900.00/version 2.
- PASS full `:services:core:check --rerun-tasks --no-daemon` after concurrency test; PASS PostgreSQL contracts 15/15; PASS web tests 8/8, TypeScript and Vite production build; PASS `git diff --check`.
- E3 remains IN PROGRESS: history-aware AI proposal (F23), complete F24 pace/forecast, basic period/family reports (F30), browser permission/error E2 proof, and Android budget/debt parity remain. E4–E10 remain planned.
- Next: complete basic reports and remaining F24 behavior, then live web/API permission/error checks; keep plan parity registry statuses unverified until acceptance matrix passes.

## E3.4 monthly pace and dashboard update — 2026-10-01 14:10 MSK

- Dashboard summary now returns member-local as-of date, elapsed/remaining days, calendar month length, average daily expense pace and current-month projection. Forecast starts after day one and stays null for zero spend; future and historical months do not get a current-month projection.
- Dashboard shows daily pace, current budget alert state, current-limit remainder and projected reserve/overrun. Zero limit remains disabled; debt payments are excluded from income/expense totals.
- TDD evidence: `MonthlyPacePolicyTest` first failed because the policy class was absent, then passed rounding, first-day/zero-spend and invalid-input cases. API checks cover leap February, December, first-day no-forecast, debt-payment exclusion and date boundaries. Web dashboard test covers 90% alert, current remainder and month-end overrun.
- PASS Java `:services:core:check --rerun-tasks --no-daemon`; PASS PostgreSQL contracts 15/15; PASS web tests 9/9, TypeScript and Vite production build; PASS `git diff --check`.
- E3 remains IN PROGRESS: history-aware AI proposal (F23), basic period/family reports (F30), live web/API permission/error proof, Android budget/debt parity and final E3 acceptance remain. E4–E10 remain planned.
- Next: implement the basic month/week/90-day/custom and family report API, web controls and parity tests.

## E3.5 period and family reports — 2026-10-01 14:32 MSK

- Added core and BFF report endpoints for month, week, 90 days, custom inclusive dates, and family scope. One service owns all totals and date boundaries; requests use the viewer's profile timezone.
- Reports separate income, expense, debt payment, and refunds; include transaction count, expense categories, weekend share, and month budget remainder. Family scope returns aggregates only and uses the family monthly limit, even when the viewing member has a personal override.
- Web now has a RU/EN reports screen with month/week/90-day/custom selectors and personal/family scope. OpenAPI describes both API and BFF operations and the report DTO.
- TDD evidence: report API test first failed 404, then passed inclusive local-day boundaries, personal vs family totals, debt/refund separation, weekend share, custom-range rejection, and different family/personal monthly limits. Same request through BFF returned the same family totals. Web test covers report values, family budget and custom date filters.
- PASS Java `:services:core:check --rerun-tasks --no-daemon`; PASS PostgreSQL contracts 15/15 including regex validation; PASS web tests 10/10, TypeScript and Vite production build; PASS `git diff --check`.
- E3 remains IN PROGRESS: history-aware AI proposal (F23), rolling food limit consistency across dashboard/digest/report (F25), live web/API permission/error proof, Android budget/debt parity and final E3 acceptance remain. E4–E10 remain planned.
- Next: implement the F23 30-day history gate and constrained AI budget proposal while retaining explicit human apply.

## E3.6 history-based AI budget proposal — 2026-10-01 15:17 MSK

- Added owner/admin-only core and BFF history proposal routes. Planned income is read from the member profile; the API requires at least 30 elapsed local calendar days from the earliest posted expense and returns 422 before any AI request when history is short.
- Context contains only monthly income, up to four local-month expense/income aggregates, category allowlist, existing family limits and bounded history days. Queries use the requesting owner’s rows only; descriptions, tenant ID and identity never cross the Java/Python boundary.
- Added a private Python HTTP adapter with service bearer authentication, 32 KiB body cap, bounded inference concurrency, validated JSON-only Ollama output and 503 on unavailable inference. Provider/model/prompt metadata persist in V10; existing deterministic proposals retain source `income` through migration defaults.
- AI shares pass exact-category, range, sum and rounding validation in Java. Proposal stays `pending`; the existing explicit apply action remains the only mutation path. RU/EN web now offers separate income and history-based proposals and displays history/model provenance.
- TDD and regression: Python service tests cover prompt minimization, invalid outputs, auth, body bounds and health. PostgreSQL API test proves 29-day rejection without an AI call, 30-day acceptance, context redaction, pending/no-mutation, then explicit apply. AI boundary uses a local deterministic stub; live Ollama inference/model quality remain unverified because no local Ollama endpoint is configured.
- A first Python test exposed a fixture missing the required zero debt limit; fixed fixture. Java compile exposed proposal metadata constructor wiring and overloaded `JdbcTemplate.query` ambiguity; both corrected. Contract run exposed OpenAPI indentation, lexical V10 migration ordering and one test indentation error; corrected, with a migration test proving legacy proposal metadata defaults.
- PASS `:services:core:check --rerun-tasks --no-daemon`; PASS combined contract, PostgreSQL migration and Python intelligence suite (26 passed); PASS web suite (10 passed), TypeScript build and Vite production build; PASS `git diff --check`.
- No commit created, per user’s instruction to commit the complete project only after the main plan finishes. E3 remains IN PROGRESS: F25 rolling-food consistency, browser permission/error proof, Android budget/debt parity and final E3 acceptance remain. E4–E10 remain.
- Next: close F25 consistency across dashboard, reports and any digest surfaces, then resume the first remaining stage gate.

## E3.7 F25 rolling food policy — 2026-10-01 15:35 MSK

- Added one Java policy for rolling 7-day food spend and usual weekly pace. The fixed limit works with no history; pace uses up to five completed prior week buckets, skips empty weeks, requires two purchased weeks and preserves the legacy upper-median/25% thresholds.
- The same `rolling7FoodStatus` now appears in budget overview, dashboard and personal/family reports. The Telegram digest does not yet consume it; this is explicitly pending E4/F32 digest integration.
- TDD: policy tests first failed because the class did not exist, then passed limit-without-history, date window, upper median, thresholds and invalid input. A PostgreSQL integration test proves matching status across APIs with no history and with two history weeks. One first run caught a misspelled family-limit accessor; fixed.
- PASS full Java `:services:core:check --rerun-tasks --no-daemon`; PASS web 10/10, TypeScript and Vite production build; PASS Python contract/migration/intelligence tests 26/26; PASS `git diff --check` (only Git line-ending and global-ignore access warnings).
- E3 remains IN PROGRESS. E2.2 is the earliest open stage gate: finish F03–F09 acceptance, browser/API permission and error proof, then close E3 Android budget/debt parity and remaining acceptance. E4–E10 remain.
- No commit created; user asked for a complete-project commit only after the main plan. Next: map the remaining F03–F09 cases to existing implementation/tests and close E2.2 gates.

## E2.2.1 F05/F06 text transaction drafts — IN PROGRESS

- Current acceptance: bounded Russian free text; normalize `2 000`, `2 тыс`, `1,5к` and comma decimals without rounding money; reject invalid/unavailable inference safely. Draft fields remain editable; no transaction exists before explicit confirm; stale/repeated confirmation cannot duplicate a transaction.
- AI runs through private Python/Ollama adapter. Java owns tenant permission, money validation, draft state and final transaction. Web shows success, empty, retry and error states. `debt_payment` parsing remains gated on connecting a chosen debt to the existing atomic debt-payment command.
- Baseline: contract/integration and Web gates from E3.7 pass. First new Python tests show RED: `ModuleNotFoundError` for missing `transaction_drafts` module. No working code changed yet.
- Next: implement Python normalizer/provider contract, then test private endpoint before Java draft persistence/API and Web confirmation flow.

### E2.2.1 evidence update — 2026-10-01 15:47 MSK

- Python parser added explicit timezone context, bounded local prompt and strict expense/income response validation. Russian amount normalizer covers spaces, `тыс`, `к`, comma decimals and ruble suffixes; it rejects zero, negative, ambiguous and over-precision values without rounding.
- Private `/internal/v1/transaction-drafts` requires the internal bearer token and shares bounded body/concurrency controls with existing AI requests. OpenAPI internal contract now documents request and response.
- TDD: amount/context/output tests showed RED on missing module, then 13/13 passed. Endpoint test showed RED at 404 before route existed; after route, Python adapter suite is 17/17.
- Java PostgreSQL test now defines end-to-end acceptance for no write before confirmation, edited fields, stale-version denial and replay without duplicates. First run showed RED at 404 as expected. Java implementation remains pending.

### E2.2.1 web acceptance — RED

- Added browser-component acceptance for the Russian text-draft flow: extracted values are editable, model provenance is visible, transaction list stays empty before confirmation, edited values are patched with `If-Match`, and confirmation uses CSRF plus the updated version.
- Observed RED: Vitest cannot find the missing `Операция текстом` control. Next: wire typed BFF draft methods and review/confirm states, then rerun this case and web regressions.

### E2.2.1 implementation evidence — 2026-10-01 16:17 MSK

- Completed expense, income and debt-payment draft flow across private Python extraction, tenant-owned Java draft persistence and confirmation, and RU/EN Web review. Debt payments require an explicit open-debt choice; confirmation calls the existing atomic debt payment command. No transaction is written before confirmation; edits are versioned, stale actions fail, and repeated confirmation is idempotent.
- A PostgreSQL test exposed that V11's original type check still excluded `debt_payment`. Kept V11/V12 immutable and added V13 to widen the draft type constraint. The migration isolation test now inserts both expense and debt-payment drafts under RLS and checks cross-tenant denial.
- PASS Java targeted PostgreSQL draft tests (2/2), including edit/no-write-before-confirm, stale version, replay, debt selection and atomic debt balance update. PASS migration isolation plus Python intelligence suite (27 passed). PASS all contract tests (16 passed), intelligence tests (26 passed), Web tests (12 passed), TypeScript build and Vite production build.
- Live Ollama model quality remains unverified because no endpoint is configured. Adapter tests cover unavailable provider, malformed response and redacted errors. No live browser session against a running authenticated API has been demonstrated yet; component tests are not that gate.
- E2.2 remains IN PROGRESS. Next: close F03/F04 onboarding/profile preservation, then finish F07–F09 history/repeat/undo and real API browser permission/error gates. Do not mark F05/F06 fully accepted until the full E2 web gate evidence is assembled.

## E2.2.2 F07–F09, source, history, and viewer permissions — 2026-10-01 16:46 MSK

- Manual income now carries an optional 64-character source through Java API, PostgreSQL, BFF, web form, history display, edits, repeat and transaction events. Missing source preserves the existing web-origin default; web manual entries use `manual`. Income totals stay separate from expenses.
- Viewer membership is read-only for transaction create/edit/void and text-draft creation. Core rejects before AI invocation; Web disables the entry form and hides row mutations while retaining history access.
- Added PostgreSQL acceptance for income source/date update and filters, stable cursor pages, profile edit preserving prior transactions, versioned edit/stale rejection/void replay, retained history after void, exact spend reversal, and viewer mutation denial. Existing Web acceptance covers filter controls and repeat once.
- PASS full Java `:services:core:check --rerun-tasks --no-daemon`; PASS Web 14/14, TypeScript and production build; PASS contracts/migration 16/16 and Python intelligence 26/26. The earlier draft slice also passes Java no-write-before-confirm/stale/replay and atomic debt-payment tests.
- E2.2 remains IN PROGRESS: onboarding budget-choice/back flow design is awaiting the user's response; actual authenticated browser-to-live-Keycloak/API run and final permission/error gate remain to prove. Current tests use real PostgreSQL and Spring MVC/BFF with deterministic OIDC fixtures. E3+ and E4–E10 remain.
- No commit created; commit remains deferred until the entire main plan and authorized v2 plan are complete. Next independent work: inspect and fill remaining transaction and profile acceptance; keep the onboarding change parked until its design gate is answered.
- Browser check: fresh Web session shows the sign-in page and the login link reaches the real local Keycloak realm with OIDC state/nonce/PKCE. No test account was created; authenticated BFF/API browser acceptance remains open.

## E3.8 Android budgets and debt lifecycle — 2026-10-01 17:30 MSK

- Android now reads and changes monthly family/personal budgets and rolling 7-day food limits using Core's versioned endpoints; category/total versions and `If-Match` are passed through. Budget proposals can come from income or history, and only the separate Apply action changes limits.
- Android debt screen supports create, payment, balance adjustment and payoff forecast. Core remains source of truth for amounts, authorization, effects and forecasts. Viewer role sees read-only forms; transaction creation is also disabled for viewers.
- Android JSON adapters preserve decimal amounts as strings and parse null fields, statuses and versions. Added unit plus Compose instrumentation acceptance for owner budget/debt views and viewer lockout. The debt create form is collapsed by default so the debt list and actions remain reachable on a phone.
- The first connected run caught two UI test issues: a viewer test attempted to type into a disabled input, and the expanded debt form pushed list controls below the viewport. Tests now check disabled controls and scroll the list; the form collapses by default.
- PASS `:app:testDebugUnitTest` (1/1), `:app:assembleDebug`, and connected Android instrumentation (7/7) on the existing API 34 emulator. APK SHA-256: `E01C13C24E82332EED2133450C8FBC1F1240CDAB290FF1165FC4388321A7A9E8`.
- Existing emulator app had a different signing certificate. Debug variant now uses `com.decorix.finance.debug`, allowing side-by-side test installation without removing the existing app or its data. That package installed and instrumentation ran successfully.
- E3 remains IN PROGRESS: Android reports/profile/dashboard and final parity gates remain. E2.2 authenticated browser gate is still open; onboarding budget/back design reply remains pending. E4–E10 remain planned. No commit created.

## E3.9 F26 cash planning and Android dashboard/reports — 2026-10-01 18:08 MSK

- Android now has Overview and Reports screens backed by Core summary/report DTOs. Month, week, 90-day, custom, personal and family controls are wired; monetary values remain server-owned. Android parser and Compose acceptance cover the totals, cash plan, and selected report request.
- Added F26 cash plan to the Java dashboard contract and RU/EN Web and Android views. It uses the current month's actual income when present, otherwise the member's planned income; reserves 10%, subtracts month expenses and history-derived recurring charges through the next expected payday (inclusive), or month end when no future income series exists. Series require three stable weekly/monthly transactions; expired salary is ignored. No profile income plan means no recommendation.
- TDD: initial policy tests failed on missing classes; policy/detector tests now pass (6 cases). Added PostgreSQL API tests for actual-vs-planned income and no-plan behavior, but the local DB gate was unavailable: no PostgreSQL listener on port 5432 and `FINANCE_TEST_DATABASE_URL` is unset. `:services:core:check` passes with those DB-only tests skipped; persistence/API behavior remains unverified until the PostgreSQL gate runs.
- PASS Web tests 14/14, TypeScript project build, Vite production build; PASS contract tests 14 passed and 2 PostgreSQL-only skips; PASS Android unit 1/1, APK build, and connected API 34 instrumentation 12/12. APK SHA-256: `1AB8D61614221901635549C45F649B45408227F2F65307CECB727FA51A369412`.
- `git diff --check` passes (Git reports only line-ending/global-ignore access warnings). No commit created, per user's whole-plan then v2 commit sequence.
- E3 remains IN PROGRESS until the PostgreSQL API gate and other pending acceptance close. E2.2 still awaits onboarding budget/back design reply and an authenticated live browser-to-Keycloak/API run. E4–E10 remain open.

## F60.1 AI Gateway request controls — 2026-10-01 18:38 MSK

- Added a required versioned request envelope for task kind, input/output schema versions, bounded Unix-millisecond deadline, execution policy, correlation ID, and required capabilities. Java budget and transaction-draft advisors send the envelope.
- Gateway intersects request and service policies; either `local-only` setting blocks remote Ollama before inference. Provider capability checks reject unsupported requests before inference.
- Added authenticated cancellation by correlation ID. Java requests cancellation after timeout/interruption; Python cancels the active inference coroutine and returns a bounded status.
- Responses include correlation/task/schema metadata, effective policy, requested and actual capabilities, measured latency, unknown token/cost values, fallback reason, and provider/model/prompt provenance. OpenAPI contract and tests describe the internal protocol.
- TDD: Python first accepted an envelope-less request (200); new test expected 400. Java request-envelope test first failed to compile because the helper did not exist. Both failures are now fixed.
- PASS Python intelligence + contract + migration suites: 57 passed, 2 PostgreSQL-only skipped. PASS Java `:services:core:check`; AI protocol and Java timeout-cancellation tests pass. PostgreSQL-only tests remain unrun without a local DB listener.
- F60 remains IN PROGRESS: Tesseract OCR and Ollama Vision adapters, Vertex extension gate, shared golden evaluations, and routing/fallback acceptance remain. No commit created; complete-project commit remains deferred as requested.

## F60.2 receipt adapters and shared evaluation gate — 2026-10-01 19:21 MSK

- Added a separate Ollama Vision adapter for `receipt-vision`; only models listed in `OLLAMA_VISION_MODELS` are offered to the router. It validates image bounds and strict JSON fields, keeps money as decimal strings, and records model/prompt provenance. Text Ollama does not claim vision capability.
- Added `finance-ai-golden.v1` with synthetic-only fixtures for budget shares, transaction extraction, OCR and receipt vision. The receipt image is generated in memory; no personal receipt or user data is included.
- Added a reusable provider runner with capability/task/policy checks, output validation, pass-rate gate, case IDs, latency and version provenance. Reports redact prompt, context, output, keys and exception text; usage/cost stay explicitly unknown. Added `python -m tools.evaluate_ai` for configured local evaluation and documented the gate/privacy rules.
- TDD: evaluation tests first failed on the missing runner; the first implementation test exposed that Python resolved the fixture directory instead of the module; a timeout-classification test then showed HTTP provider timeouts reported as generic errors. Each was corrected and covered.
- PASS Python intelligence + OpenAPI contract + migration suites: 78 passed, 3 PostgreSQL-only skipped. PASS evaluation entrypoint `--help`, Python `compileall`, and `git diff --check`. The four-case provider path is exercised with deterministic in-memory providers. A configured live Ollama/VLM golden run was not performed; no verified model endpoint is available. Vertex remains disabled pending comparative eval, canary and rollback.
- E2.2 onboarding-choice answer, authenticated live browser/Keycloak acceptance, and E3 PostgreSQL API gate remain open; E4–E10 remain planned. No commit created, as requested.
- Next: continue with independent E4 receipt intake/storage and review path work while external database, identity and live-model gates remain tracked.

## E4.1 F13 receipt reading reconciliation policy — 2026-10-01 19:31 MSK

- Added the versioned Java domain policy `receipt-reconciliation.v1`. It uses exact `BigDecimal` sums and `max(2.00 RUB, 3% of receipt total)`. Only complete positive item amounts count as arithmetic evidence.
- If both readers disagree on known total, merchant or date, the result requires human review. If both agree, the policy selects the reconciled reading with more items (Vision breaks ties to preserve legacy behavior). A lone reading is selected only when it reconciles; missing evidence is marked insufficient. This only chooses draft evidence; it never confirms a receipt or creates a transaction.
- TDD: first test compile failed because the policy was absent. The exact-boundary test then exposed that rounding 3% upward could accept a 3.01 gap against a 3.003 limit; tolerance now remains exact and does not round.
- Added one-to-one item evidence: one OCR row can corroborate at most one Vision row; repeated names choose the closest unused amount and preserve disagreement instead of duplicating proof.
- PASS Java `:services:core:check --rerun-tasks --no-daemon`; eight receipt policy tests pass. PASS Python intelligence/contracts/migration suite: 78 passed, 3 PostgreSQL-only skipped. PASS feature registry contract validation after marking F11/F12/F13/F59/F60 as evidenced `in_progress`.
- F13/E4 remain partial: no receipt upload/storage API, persistent readings/reviews, item editor, duplicate confirmation, or connected browser/Telegram flow exists yet. Receipt persistence still needs the PostgreSQL integration gate; overall plan remains open and no commit is created.
- Next: add the receipt API/draft persistence around this policy, then exercise edit/review/confirm under PostgreSQL when the local database gate is available.

## E4.2 tenant receipt evidence schema — 2026-10-01 19:50 MSK

- Added Flyway V14 tables for private documents, receipt drafts, ordered items, provider readings and review history. Tenant composite keys/FKs, forced RLS, MIME/size bounds, decimal constraints, reader/verdict provenance, idempotency and duplicate-candidate indexes are defined.
- Review rows retain an item snapshot so deleting an edited item does not erase the prior human decision. The migration adds no object-storage provider or public URL; the upload contract remains pending ADR-014's provider choice.
- TDD: the new static migration contract first failed because `documents` was missing; after V14 it passes. The PostgreSQL migration test now covers receipt-table RLS, receipt/item/reading/review writes and cross-tenant insert denial.
- PASS static migration contracts (4 passed); PostgreSQL integration checks remain skipped because `FINANCE_TEST_DATABASE_URL` and a local PostgreSQL listener are absent. Do not treat V14 as applied against a live database.
- E4 still lacks receipt Java API/storage wiring, item editing/review/confirmation, browser/Android/Telegram flows, and live OCR/Vision pipeline. Next: implement owner-scoped receipt draft create/read and item edits over V14; retain explicit manual total synchronization and confirmation.

## E4.3 owner-scoped receipt draft API — 2026-10-01 20:23 MSK

- Added versioned API/BFF request and response contracts plus Java receipt create/read over V14. Creation persists owner-scoped idempotency, ordered manual item evidence and an initial review event in one transaction; a document can be linked only when it is ready and belongs to the same tenant member.
- `cashTotal` and computed `itemsTotal` remain separate. Exact decimal validation marks incomplete or mismatched values `review_required`; aligned values stay `draft`. The API never creates a financial transaction. Viewer members can read their own receipt but cannot create; another member cannot read the owner’s draft. BFF create remains behind session and CSRF controls.
- TDD evidence: contract test was RED on the missing route, then passed. Four Java validation tests cover separate totals, the exact 3% boundary, unknown item totals and invalid precision. PostgreSQL acceptance now covers replay/conflict, read, owner scope, viewer behavior and BFF CSRF.
- PASS Java `:services:core:check --rerun-tasks --no-daemon`: 77 tests, 0 failures, 27 skipped because PostgreSQL is unavailable. PASS Python contracts/migration/intelligence: 80 passed, 3 PostgreSQL-only skipped. Database execution remains unverified; OpenAPI validation is included in the contract suite.
- E4 remains partial: no upload/storage provider, OCR/Vision job-to-draft wiring, provider reading persistence, item editor/delete/add, explicit total sync, duplicate decision or receipt confirmation exists. Continue with review edits while keeping the live PostgreSQL gate open.

## E4.4 versioned receipt item review — 2026-10-01 20:37 MSK

- Added API and BFF item pages of eight, add/edit/delete with receipt `If-Match`, per-item edit versions, and immutable before/after review snapshots. Item corrections clear old verdict/advice provenance to `unknown` and become manual evidence.
- Cash total remains unchanged during item edits. `itemsTotal` is recomputed only when all item sums are known; mismatch keeps `review_required`. The separate `sync-total` action copies a complete positive item sum into the cash total and records a human review event. No operation posts a transaction.
- TDD/verification: item-edit OpenAPI test was RED on missing routes, then passes. Nine reconciliation policy tests plus four receipt service tests pass. PostgreSQL acceptance now defines eight-row paging, add/edit/delete, stale version rejection, explicit total sync, and BFF CSRF behavior.
- PASS Java `:services:core:check --rerun-tasks --no-daemon`: 79 tests, zero failures/errors, 28 skipped because PostgreSQL is unavailable. PASS Python contracts/migration/intelligence: 81 passed, 3 PostgreSQL-only skipped. All API/persistence behavior in the integration class remains unexecuted until a database is available.
- E4 remains partial: storage/upload and scan jobs, OCR/Vision reading persistence and reconciliation wiring, receipt duplicate decision, product verdict/advice, user confirm-to-transaction, and Web/Android/Telegram flows remain open. ADR-014 still needs a concrete S3-compatible development provider.

## E4.5 F17 receipt category safeguards — 2026-10-01 21:02 MSK

- Ported legacy alcohol/leisure markers into a versioned Java policy. Exact 10% alcohol and 25% leisure boundaries promote the suggested category to `досуг`; unknown amounts never invent shares. The policy rejects null item evidence consistently.
- Added additive V15 receipt fields for category, source, policy version, shares and leisure flag. Receipt creation applies deterministic rules. Owner-scoped `PATCH /category` uses `If-Match`, persists manual `human` provenance and review history; item edits recompute rule evidence while preserving manual choice. Same-origin BFF route requires session and CSRF.
- TDD: migration/contract tests first failed on missing V15 and route; policy test first exposed null-item `NullPointerException`. Fixes are in place. PASS contract/migration tests (19 passed, 2 PostgreSQL-only skipped) and targeted Java policy/reconciliation/service tests (BUILD SUCCESSFUL). Integration assertions for category create/edit/stale version/audit and BFF CSRF compile, but cannot execute without PostgreSQL.
- F17 remains in progress: storage/upload, OCR/Vision generated suggestion integration and client controls remain open. ADR-014 development provider choice is still awaiting user response.
- No commit created. User asked to commit whole project only after the main and improved V2 plans are complete.

## E4.6 F18–F19 basket review and provenance — 2026-10-01 21:20 MSK

- Added the amount-free Python basket task and Java adapter integration. Java sends item names with stable ordinals only, splits receipts into batches of 80, rejects amount/totals in model output, maps results back to item UUIDs, then applies deterministic legacy safety rules and duplicate-advice cleanup.
- Added V16 additive item provenance fields: reason, action, composed advice, verdict source, provider/model/prompt/algorithm versions. Existing rows keep `unknown` review algorithm. Review persistence increments receipt/item versions and writes immutable per-item `receipt_reviews` snapshots. Basket review does not change cash or item totals.
- Added owner API and CSRF-protected BFF `POST .../{receiptId}/basket-review` with `If-Match`; empty baskets conflict, stale receipts fail, and external AI runs between two short tenant-scoped transactions.
- TDD: OpenAPI/migration tests first failed on missing route and V16; after implementation, both pass. Existing policy/adapter tests pass. PASS Java targeted `ReceiptBasketPolicyTest`, `ReceiptBasketAdvisorTest`, `ReceiptServiceTest`, and compile of PostgreSQL acceptance class (`BUILD SUCCESSFUL`). PASS Python contracts/migration/intelligence: 95 passed, 3 skipped (PostgreSQL-only).
- A new fractional ordinal test first failed because `Number.intValue()` truncated `1.5`; exact numeric comparison now rejects it. Targeted advisor tests pass.
- PostgreSQL API read/write, CSRF and audit assertions compile but do not execute: `FINANCE_TEST_DATABASE_URL` is unset and no local PostgreSQL listener exists. Live model quality and authenticated browser flow remain unverified. F18/F19 remain `in_progress` until database and connected acceptance gates pass.
- No commit created. User directed one full-project commit after main plan and V2 plan both complete.

## E4.7 F20 product disagreement and paging — 2026-10-01 21:46 MSK

- Ported legacy `product_key`: lowercase, `ё` normalization, token set, stop words, numeric-token removal and stable sorting. Duplicate receipt names therefore share the same decision key.
- Added V17 tenant/user-scoped `user_product_decisions` and immutable change events with forced RLS. `PUT /products/{productKey}/decision` idempotently allows a product; `DELETE` reverses it and preserves event history. Viewer writes remain forbidden.
- Added owner/BFF disputed-item pages of eight. Items sort by line sum descending, then receipt ordinal; all lines for an allowed product disappear. Product keys travel with receipt items for user actions.
- TDD: route and migration contracts were RED on missing paths/tables. Product key test was RED when duplicate tokens were not deduplicated; `TreeSet` now matches legacy behavior. PASS targeted Java identity, basket, receipt-service and PostgreSQL-test compilation. PASS full Java `:services:core:check --rerun-tasks --no-daemon` (`BUILD SUCCESSFUL`). PASS Python contracts/migration/intelligence: 97 passed, 3 PostgreSQL-only skipped. `git diff --check` passes.
- PostgreSQL tests now define paging, normalized duplicate names, idempotent allow, revoke, event history, tenant RLS and BFF CSRF. They remain unexecuted without a local integration DB. F20 remains `in_progress` until live DB gates pass and later advice/waste analytics use the same decision overlay.
- No commit created. User asked to commit all project work after main plan and V2 plan finish.

## E4.8 F21 repeat warnings — 2026-10-01 21:57 MSK

- Ported legacy conservative product matching: exact normalized token overlap or overlap plus matching-block signature ratio. Tests cover Russian product variants and reject unrelated/similar-category names.
- Added Java repeat-warning policy with streaming accumulator. Only prior `confirmed` receipts can warn; current receipt is excluded, allowed products are filtered on both history/current sides, and output sorts by repeat count then numeric last sum.
- Added owner/BFF repeat-warning API. JDBC reads historical waste rows in tenant/member scope with fetch size 500; monetary last sum comes from stored receipt evidence. No model call or inferred amount is involved.
- TDD: unit tests first failed to compile because product matching/warning policy did not exist; OpenAPI test was RED on missing endpoints. PASS Java targeted identity/repeat-policy tests and PostgreSQL acceptance-class compilation (`BUILD SUCCESSFUL`). PASS Python contracts/migration/intelligence: 98 passed, 3 PostgreSQL-only skipped.
- PostgreSQL API query, forced RLS and authenticated BFF read remain unexecuted without the integration DB. F21 stays `in_progress`; F20/F21 remain open until live DB gates run and later waste analytics use shared allowed decisions.
- No commit created per user's whole-project then V2 final-commit order.

## E4.9 F16 duplicate review and receipt confirmation — 2026-10-01 22:17 MSK

- Added additive V18 duplicate decision/link fields with tenant-composite FK and candidate index. Candidates are same-owner, same-currency receipt expenses with the same cash total in the prior ten minutes; policy uses a strict time boundary, never auto-deletes or merges, and records a versioned user choice in immutable review history.
- Added owner API and CSRF-protected BFF to list candidates and select `independent` or `duplicate`. Receipt confirmation now atomically creates the `receipt` expense transaction/outbox, links it to the receipt, records confirmation, and blocks unresolved duplicate candidates or unreconciled totals. Replays with the same key return the same receipt/transaction; another key conflicts. Amount changes through explicit total sync clear the old duplicate decision.
- Receipt confirmation uses the member timezone and local noon for date-only receipt evidence. It creates an ordinary expense, so receipt candidates share one transaction type.
- TDD: duplicate policy, OpenAPI and V18 tests were RED on missing class/routes/migration. They are now GREEN. PASS Python targeted contracts/migration tests: 9 passed, 2 PostgreSQL-only skipped. PASS Java targeted policy, service and PostgreSQL acceptance-class compilation (`BUILD SUCCESSFUL`). The PostgreSQL acceptance class is disabled without `FINANCE_TEST_DATABASE_URL`; no live database listener is available. `git diff --check` passes.
- Added RU/EN Web receipt draft controls to create a manual receipt, list matching candidates, switch between duplicate/independent decisions, and confirm the expense. Confirmation is disabled while candidate lookup is pending/failed or a match remains unresolved. Successful confirmation invalidates transaction, summary and budget queries.
- TDD: duplicate policy, OpenAPI, V18 and Web UI tests were RED on missing implementation, then GREEN. PASS Python full contracts/intelligence suite: 101 passed, 3 PostgreSQL-only skipped. PASS Java `:services:core:check --rerun-tasks --no-daemon` (`BUILD SUCCESSFUL`). PASS Web Vitest: 15 passed; TypeScript build and Vite production build pass. Database acceptance class is disabled without `FINANCE_TEST_DATABASE_URL`; no live database listener is available. `git diff --check` passes.
- F16 remains `in_progress`: database execution and Android/Telegram receipt controls remain. ADR-014's S3-compatible development provider choice remains pending; continue independent tasks.
- No commit created per the user's instruction to commit only after main and improved V2 plans are both complete.

## E4.10 F15 web receipt item editor — 2026-10-01 22:33 MSK

- Added RU/EN receipt item editor over the versioned BFF: edit, add, delete, and pages of eight. Line corrections update item totals while preserving the receipt cash total; only the explicit sync button copies a complete item total to cash total. Amount changes refresh duplicate candidates; owner/viewer capability is respected.
- Creation keeps one idempotency key through retries; confirmation keeps a separate key. Successful create/update/delete/sync uses current server version and invalidates the affected item or duplicate queries.
- TDD: Web test was RED because item editing controls were missing; added tests for unchanged cash total until explicit sync, add/delete, page-of-eight behavior, duplicate decisions, idempotency headers and CSRF header. PASS full Web Vitest: 18 passed; TypeScript project build passes; Vite production build passes. PASS Python contracts/intelligence: 101 passed, 3 PostgreSQL-only skipped. No new backend changes since the Java core gate passed in E4.9.
- F15 remains `in_progress`: PostgreSQL API assertions are not executed locally; receipt upload/OCR and Android/Telegram item editors remain open. Next: add web category and basket decision controls, then proceed with storage-backed F10/F13 once ADR-014 is resolved.
- No commit created per the user's final whole-project commit instruction.

## E4.11 F17–F21 Web receipt review — 2026-10-01 22:48 MSK

- Added RU/EN category selection with versioned `If-Match`, human provenance display, basket review, per-item verdict/reason/action/advice and model provenance, disputed-item list, repeat warnings, and reversible allow/revoke controls for product decisions. Receipt expense confirmation now remains available after basket review when cash/item totals reconcile; duplicate choice still blocks unresolved candidates.
- Added persisted allowed-product-key list to the owner API and BFF, with OpenAPI contract coverage and PostgreSQL acceptance assertions for listing and revoking keys. Removed an accidental duplicate OpenAPI path entry.
- TDD: new Web flow first failed because category controls were absent; then passed with category save, basket review, provenance, repeat warning, CSRF/version headers, and allow/revoke assertions. PASS full Web Vitest: 19 passed; TypeScript project build and Vite production build pass. PASS Python contract/migration tests: 27 passed, 2 PostgreSQL-only skipped. PASS Java `:services:core:check --rerun-tasks --no-daemon` (`BUILD SUCCESSFUL`). PostgreSQL acceptance class compiles but cannot execute locally: `FINANCE_TEST_DATABASE_URL` unset and no listener on port 5432. `git diff --check` passes with expected line-ending/global-ignore warnings.
- F17–F21 remain `in_progress`: live PostgreSQL gate is unavailable; storage-backed OCR/upload, Android and Telegram screens, real local AI service quality, and shared analytics integration remain. ADR-014's S3-compatible development provider choice is still awaiting user response.
- No commit created per user's final full-plan then V2 commit order.

## E3.10 F22–F30 feature registry reconciliation — 2026-10-01 22:53 MSK

- Reconciled the parity registry with existing Java, Web and Android work for monthly budgets/proposals, alerts and pace, rolling food, cash planning, debt lifecycle/adjustment/forecast, and period/family reports. Added concrete unit, Web, Android and PostgreSQL-acceptance test references; all remain `in_progress` because database execution and Telegram/digest parity are still open.
- PASS OpenAPI/feature registry contract tests: 20 passed. Latest related gates: full Java core check passed in E4.11; Web 19/19 and TypeScript/Vite builds passed in E4.11; API 34 Android instrumentation 12/12 and APK build were recorded in E3.9 before receipt changes.
- E3 remains `in_progress`: live PostgreSQL is unavailable; Telegram budget/digest parity, final permission/error proof, and a current Android build/install after receipt work remain open. F31 charts are recorded in F31.1 below.
- No commit created per user's final main-plan then V2 commit order.

## F31.1 report charts and PNG fallback — 2026-10-01 23:01 MSK

- Extended the shared FinanceReport DTO with daily expense amounts keyed by the member's local ISO date, including zero-spend dates. Java computes these from posted expense rows within the same report window/scope as totals and category aggregates.
- Added accessible RU/EN web bars for category/day reports and monthly budget usage. Values remain visible as text beside each bar. Added a Pillow PNG renderer that consumes the ready server DTO, validates dates/money, draws summary/category/day views, and returns the same report as text when image support is unavailable.
- TDD: report-chart UI and DTO tests failed before charts/daily series existed; PNG tests failed before the renderer module existed. PASS Java `:services:core:check --rerun-tasks --no-daemon` (`BUILD SUCCESSFUL`, PostgreSQL-only acceptance disabled). PASS Web Vitest 19/19, TypeScript build and Vite production build. PASS Python intelligence/presentation/contracts/migrations: 107 passed, 3 skipped (integration-only).
- F31 remains `in_progress`: PostgreSQL report endpoint was not exercised with a live DB; Telegram/Android chart delivery is open; price and optional-spend series depend on F33/F40 data surfaces. No commit created per the requested final commit order.

## E3.11 F05/F06 Android text transaction drafts — 2026-10-02 09:37 MSK

- Replaced Android's direct transaction write with a RU/EN free-text draft flow through the Java API and existing Python AI gateway. The app uses an idempotency key stable across a retry, shows provider/model/prompt/date provenance, allows type/amount/category/subcategory/description corrections and quick amounts, requires a save of edits before confirm, offers debt selection for debt payments, and can cancel at the current draft version. Confirmation uses a stable key; all writes carry the server version. No transaction is posted while the draft is only proposed.
- TDD: added Compose instrumentation for free-text submission, review-before-confirm, quick amount/edit/version transition, provenance, and cancel. Initial compile was RED on the missing draft model/UI/API flow. First emulator pass exposed only test setup and outdated viewer expectations; the cancel action also exposed a narrow-screen button overflow, corrected by moving cancel below the primary actions. Final PASS `:app:connectedDebugAndroidTest`: 14/14 on FinanceCodex AVD; debug APK was built and installed as part of that run. APK SHA-256: `2AB9B4074880AD9E64ADB492DF4527CAA1FB67C2DD8ABA1BFE5240E70537E9F4`.
- Existing Java PostgreSQL acceptance covers `Такси 2 тыс`, no transaction before confirmation, one posted transaction after confirmation, idempotent confirmation, stale edit rejection and debt-payment choice. Its DB execution is not repeated here; current `FINANCE_TEST_DATABASE_URL`/local database availability remains to be checked before final project gates. F05/F06 remain `in_progress` because Telegram conversational/keyboard parity and live integration execution remain open.
- Android F59 remains `in_progress`: this improves transaction parity but does not supply the remaining Android features/screens or the full release gate. F31 charts still lack Android daily chart rendering. No commit created per the user’s required main-plan then V2-plan order.

## E3.12 F31 Android category/day and budget charts — 2026-10-02 10:04 MSK

- Android reports now show server-computed category and local-day expenses as proportional bars with visible amounts; monthly budget usage is graphed on the dashboard. The daily chart preserves the server's zero-spend dates and reads the shared `FinanceReport` DTO without recalculating financial totals.
- TDD: report model/UI tests were RED because `FinanceReport.expenseByDay` and the day chart were absent. Added map parsing and RU/EN report labels, then fixed the test to scroll its lazy results list to an uncomposed date row. PASS `:app:connectedDebugAndroidTest`: 15/15 on FinanceCodex AVD. Current debug APK built and installed by the instrumentation run; F31 Android is covered, while Telegram PNG delivery, live PostgreSQL and F33/F40 price/waste series remain open.
- F31 stays `in_progress` and E3 remains open. Latest APK SHA-256: `13DE8FCD1EC0A23E41716C87373801133F142A7974B2E69AC33781FC3512CFA0`. No commit created per the requested main-plan and V2-plan ordering.

## E3.13 F03/F04 Android onboarding and member profile — 2026-10-02 10:26 MSK

- Android onboarding captures workspace name and a separate member name, allows income to be skipped, and exposes later member-name/income edits without changing the workspace name. Profile also shows server-owned timezone and currency.
- TDD acceptance initially failed because the test tapped the profile tab while it was outside the horizontal navigation viewport. Compose diagnostics showed the route remained on the dashboard; scrolling the tab into view before tapping reproduced the real interaction and resolved the test. No production workaround or assertion weakening was needed.
- PASS `:app:connectedDebugAndroidTest`: 18/18 on FinanceCodex AVD. The run rebuilt and installed the debug APK; SHA-256 `B317BF7B2369E2907FF765AB9A81AAAB6889A424AC11085AB9488DE0C9BBFA6A`.
- F03/F04 remain `in_progress`: legacy repeat-onboarding/back/budget setup parity and Telegram-name synchronization are not complete. Web onboarding/profile UI tests and Java PostgreSQL acceptance cases exist; live Keycloak/API/PostgreSQL execution remains unavailable locally. E2 and E3 remain open; next continue feature parity and database/API gates.
- No commit created before completion of both requested plans.

## E4.15 PostgreSQL-backed F02 verification and migration repair — 2026-10-03 22:28 MSK

- Started a fresh loopback-only PostgreSQL 18.6 test cluster. PASS `TransactionApiPostgresTest`: 41/41; PASS full Java `:services:core:check --rerun-tasks --no-daemon`; PASS Python services/contracts with the live test DB: 124 passed, 1 skipped. The migration/tenant-isolation gate and 256-character product-key acceptance pass.
- Added additive V20 to replace an invalid PostgreSQL `{1,256}` regular-expression bound while keeping the Java/API 256-character contract. Fixed receipt duplicate-window timestamp typing and confirmation after a review-required state. Corrected time-dependent report assertions and RLS-scoped audit assertions in acceptance tests; tightened migration-test version matching and legacy receipt fixture columns.
- The connected Android run passed 20/20 and installed the debug APK (SHA-256 `9F80EC0282922B29A87BA7288496D540EB220BB70D75464156D8844B4670C127`). F02 remains `in_progress`: Telegram actor-context authorization and owner/partner behavior are not implemented or accepted end-to-end. F01 still lacks operation handlers and live Redis/TLS/Telegram acceptance.
- Next: implement and verify per-action Telegram actor authorization, then restore transaction/budget/report Telegram flows; proceed through remaining F01–F60 and operational gates. No commit until the main plan and improved V2 plan are fully complete.

## E2.2.1 Web and contract verification — 2026-10-02 10:28 MSK

- PASS web Vitest: 19/19, including RU onboarding with optional income and member profile edits. PASS TypeScript project build and Vite production build. The environment has no `npm` on PATH; verification used the installed workspace Node runtime directly and existing checked-out `node_modules` without installing packages.
- PASS feature-contract suite: 21/21 after F03/F04 parity registry evidence was reconciled. `PROGRESS.md` and `.agent/PROGRESS.md` have identical SHA-256 `F2621842319DF9D54F5CDD9B35EEB195E14D5D6F29D69E0411BA9933D14716B0` at that update; mirrors must be refreshed after this entry.
- F03/F04 remain incomplete for legacy repeated onboarding/back/budget setup and Telegram profile synchronization. E2.2 remains open for live OIDC/Keycloak + PostgreSQL end-to-end acceptance; no local DB listener or `FINANCE_TEST_DATABASE_URL` is available.

## E2.2.2 F07–F09 registry and Java verification — 2026-10-02 10:31 MSK

- Reconciled parity registry F07–F09: manual income/source, server-side history filters, edits, repeat and void are implemented and covered in Web/Core; the registry now records actual tests and evidence while retaining `in_progress` until Telegram parity and the live PostgreSQL gate pass. F59 now records the current 18/18 Android run and installed APK SHA.
- PASS Python feature-contract suite: 21/21. PASS Java `:services:core:check --rerun-tasks --no-daemon` (`BUILD SUCCESSFUL`), including test compilation; PostgreSQL-only acceptance remains disabled because no local listener or `FINANCE_TEST_DATABASE_URL` is available.
- PostgreSQL server processes exist but no listener was found on ports 5432 or 5500–5700. Do not stop or modify those unrelated processes; continue with independent local work while the integration gate remains open.
- F07–F09 remain `in_progress`; E2.2 remains open. Next inspect the remaining independent E4 Telegram/gateway requirements and complete what does not depend on the pending object-storage decision.

## E4.12 F01 Telegram command gateway bootstrap — 2026-10-02 10:57 MSK

- Added a separate aiogram gateway package with `/start`, `/menu`, `/help`, a persistent reply-menu button, polling mode, and TLS webhook mode. Webhook mode requires an HTTPS URL, TLS cert/key, a secret token, and shared Redis update deduplication; the listener starts before the Telegram webhook is registered. Local single-process polling has a bounded in-memory fallback. Added pinned direct dependencies, a non-root Dockerfile, safe environment examples, and Python CI coverage.
- TDD: command tests were RED because the module did not exist. The first request mock intercepted `send_message`, while aiogram emits via `Bot.__call__`; that accidental network attempt failed closed, and the correct request-boundary mock made tests offline. Webhook/Redis tests then went RED on missing routes/deduplication, followed by HTTPS URL validation; all passed after implementation.
- PASS gateway tests: 7/7. PASS complete Python service and contract suite: 114 passed, 3 PostgreSQL-only skipped. PASS `git diff --check` (Git line-ending/global-ignore warnings only). Java core check remains PASS from E2.2.2.
- F01 remains `in_progress`: operation handlers still need to restore the menu; no live Telegram token, Redis, TLS certificate, or Docker daemon is available, so deployment, webhook and Redis integration gates are NOT_RUN. F02 binding/authorization and remaining F01–F21 parity are next.
- No dependency install, successful external request, or commit. Current Redis async integration follows redis-py's official asyncio `SET`/shared-client API; local tests use a fake Redis client because the service is unavailable.

## E4.13 F02 OIDC-to-Telegram one-time link — 2026-10-02 11:06 MSK

- Started the binding flow from the approved OIDC identity: a user-bound web/API action issues a short-lived one-time code; the trusted Telegram service redeems only `{code, telegramUserId}`. The caller cannot choose the target `user_id`.
- TDD primitive: new Java tests were RED because the code helper was absent; added 16 characters from a 32-symbol unambiguous alphabet, grouped for entry, case-normalized, and SHA-256 hashed. PASS targeted `TelegramLinkCodeTest`: 2/2.
- Added PostgreSQL API acceptance specifications for hash-only persistence, redemption to the Keycloak-mapped user, one-time use, rejection of a supplied arbitrary `userId`, and service-token protection. These are NOT_RUN because local PostgreSQL is unavailable; implementation and actual database verification remain.
- F02 stays `in_progress`. Next add the reversible V19 code/attempt tables, authenticated BFF/API issue endpoints, and scoped internal redemption endpoint, then run Java compilation and database acceptance where available.
- No commit created before both main and V2 plans are complete.

## E4.14 F02 one-time Telegram binding — 2026-10-03 21:54 MSK

- Added V19 hash-only Telegram link codes and service-only failed-attempt rows. Authenticated API and CSRF-protected BFF issue one 80-bit code for 10 minutes, invalidate the previous code, and limit issuance to three per minute. Database policies limit code rows to the mapped member or credential-checked Core service; five failed redemptions block the Telegram ID for 15 minutes.
- Added internal redemption guarded by a constant-time scoped service-token check. Core chooses the user from the code hash and never trusts caller-provided `userId`; provider/user uniqueness prevents one Telegram account or Finance member from being rebound. PostgreSQL acceptance covers one-time use, forged `userId`, issuance limits, failed-attempt limits, and BFF CSRF; it still needs a live PostgreSQL run.
- Added Python `TelegramCoreClient` and `/link <code>`; command accepts private chats only and sends only the code plus Telegram sender ID to Core. Web and Android profiles now issue and display the expiring code; instructions require private chat.
- PASS full Python suite: 121 passed, 3 PostgreSQL-only skipped. PASS Telegram gateway tests: 14/14. PASS Web tests: 20/20 and TypeScript build. PASS OpenAPI YAML parse and required Telegram routes. PASS Java credential/code unit tests before the last PostgreSQL acceptance additions; compile and Android connected run are in progress.
- F02 remains `in_progress`: live V19/HTTP integration, Android instrumented execution, Telegram actor-context authorization, and legacy owner/partner end-to-end parity remain. F01 remains open for financial operation handlers, persistent menu restoration, Redis, TLS/webhook and live Telegram acceptance.
- Next: complete current Android run; compile Java acceptance tests; run complete Core checks; find or start a safe isolated PostgreSQL test instance without touching existing PostgreSQL processes; then continue F01/F02 integration and next plan goal. No commit until original and improved V2 plans are complete.

## E4.16 F02 tenant-scoped Telegram actor contexts — 2026-10-03 23:31 MSK

- Added V21 actor contexts with SHA-256-only token storage and a 15-minute expiry. Core derives the member from the verified Telegram binding, accepts a selected active tenant only when membership exists, derives permissions from the current role on every action, and revokes the prior context when the Telegram user switches tenants.
- PostgreSQL API acceptance now proves hashed-token storage, no caller-selected user, read scoping, cross-tenant isolation, previous-token revocation, membership removal denial, and viewer write denial before any AI call. Policy tests cover owner/admin/member/viewer and legacy `partner -> member` permissions.
- `TelegramActorContextService.require` is nested into the caller's Core transaction so authorization and the business action share one transaction. Full Core, Python and Telegram-runtime acceptance is recorded in E4.17. F02 stays `in_progress` until remaining legacy owner/partner scenarios and real Telegram/Keycloak acceptance are covered.

## E4.17 F01/F05/F06 Telegram operation draft slice — 2026-10-03 23:31 MSK

- Added private `/add <text>` through Telegram Core client into a durable Core-owned transaction draft. A deterministic Telegram sender/message idempotency key makes a retried update return the same draft. No transaction is posted before explicit confirmation.
- Added service-token-protected Core endpoints for create, amount-only update, confirm and cancel. Each rechecks `transaction.write.own`, ignores caller `userId`/`tenantId`, scopes draft ownership to the actor's selected tenant, and uses the draft version and idempotency key. Telegram buttons expose confirm/cancel and fixed quick amounts; a quick-amount change updates only the amount and increments version. Completed actions restore the persistent menu button and show model provenance.
- TDD: the new Python client/command tests were RED before the client and handler existed; the PostgreSQL acceptance first returned 404 before Core routes were implemented. Green verification: gateway+contract tests 45/45; full Python services/contracts 138 passed, 1 integration-only skip (a first full run had one unrelated TCP reset; its isolated test and full rerun passed); full Java Core `check` 121 tests, zero failures; targeted latest PostgreSQL test proves pending draft leaves totals unchanged, edited amount posts once, cancel posts nothing, and viewer is denied before AI. `git diff --check` has only Git line-ending/global-ignore warnings.
- F01/F02/F05/F06 remain `in_progress`: remaining legacy Telegram handlers, full field-edit/debt-payment Telegram flows, OCR receipt and import interactions, Redis/webhook/TLS, and live Telegram acceptance remain open. No commit before the original plan and improved V2 plan are complete.

## E4.18 Telegram draft field editing and debt selection — 2026-10-03 23:51 MSK

- Added Core-backed Telegram draft read/edit actions for recovery after Redis FSM expiry. Actor-selected tenant and owner are revalidated in Core; edits change the requested fields only and compare the current draft version. Telegram can correct amount, category, subcategory, description and date. Callback buttons carry draft ID/version, not field values.
- Added an actor-scoped open-debt list and debt-payment choice. The keyboard stores a short-lived option list in FSM and sends only revision plus option index; Core retrieves the real debt ID from its own response and saves it to the draft. A debt-payment draft has no confirm button until an open debt is selected. PostgreSQL acceptance confirms a missing debt returns 422 and the selected payment reduces balance only after confirmation.
- TDD and verification: new debt selection/client tests were RED before handler/API implementation. PASS gateway+contract suite 49/49; PASS full Python services/contracts 142 passed, 1 PostgreSQL-only skip; PASS full Java Core check, 121 tests, zero failures; PASS PostgreSQL-backed Telegram actor/draft/debt action test. `git diff --check` reports only line-ending/global-ignore warnings.
- F01/F02/F05/F06 stay `in_progress`: broader legacy Telegram conversational parity, receipts/imports, and live Telegram/Redis/TLS/Keycloak deployment acceptance remain. No commit before both plans are complete.

## E4.19 F08 Telegram history, repeat and undo — 2026-10-04 00:20 MSK

- Added `/history`. Core returns up to eight of the linked member's latest posted operations by creation time. Read and write actions revalidate current actor context, role and tenant. Telegram callbacks keep transaction IDs server-side and use a short-lived FSM revision plus option index.
- Repeat loads only the actor's own posted expense, copies amount/category/subcategory/description into an AI-free Core draft, sets current date and uses an idempotency key. Confirmation posts one new transaction with `source=repeat`; duplicate confirmation replays the same transaction. No financial write occurs before confirmation.
- Undo only voids the actor's latest posted operation when ID and version still match. Stale selections return 412; retries replay the prior result. Core void path reverses debt-payment effects and retains audit/event history. F08 acceptance is complete.
- TDD: dispatcher test failed before `/history` existed; PostgreSQL acceptance first returned 404 before Core routes existed. Offline Telegram client/command tests pass. Full Python/services/contracts: `144 passed, 1 skipped`; full Java Core `check --rerun-tasks`: 122 tests, zero failures; PostgreSQL acceptance covers history scoping, repeat idempotency, no write before confirmation, stale undo and void replay. OpenAPI/contract tests pass.
- Regression diagnosis: full Java check initially failed an existing summary test at Moscow midnight. Its setup asserted `Europe/Moscow` while fixture profile stayed default `UTC`; reproduced in isolation. Test now explicitly sets the member timezone to `Europe/Moscow`; targeted test and full Java gate pass. No production behavior changed for this correction.
- F01 remains `in_progress` for remaining legacy handlers, receipt/import flows, and live Telegram/Redis/TLS acceptance. F02/F05/F06 and later plan features remain open. No commit before original and improved V2 plans are complete.
- Next: complete F07 Telegram income/source parity, then continue first incomplete `PLAN.md` feature and E4 acceptance gates.

## E4.20 F07 Telegram income and source parity — 2026-10-04 00:33 MSK

- Telegram AI drafts now explicitly preserve an income source in `description`; prompt version is `transaction-draft.v2`. Review cards label that field “Источник”, and confirmation uses the localized transaction type “доход”. Source, `source=text_ai`, and transaction date survive draft confirmation.
- PostgreSQL acceptance proves no financial totals change before confirmation, confirmed income increases `incomeTotal` without increasing `expenseTotal` or monthly budget spend. The contract parity registry now marks F07 complete.
- TDD: the Telegram review-card assertion and prompt source rule each failed before their fixes. PASS full Python services/contracts: 145 passed, 1 skipped. PASS Java Core `check --rerun-tasks`: 123 tests, zero failures; the F07 PostgreSQL scenario is included. No commit until the main plan and V2 plan are complete.
- Next: continue with the first incomplete objective in `PLAN.md`, while keeping external service gates explicitly NOT_RUN and progressing independent local work.

## E4.21 F03 repeatable onboarding and budget choice — 2026-10-04 01:18 MSK

- Web setup now has welcome, workspace/member details, optional income, back navigation and a budget-choice screen. Entered income creates a deterministic proposal only; the user explicitly applies it or keeps current limits.
- Profile offers “Пройти настройку заново”. The flow updates the current member profile and never creates a second tenant or edits transactions. Existing users with tenant membership continue to the app instead of being treated as new.
- TDD: web tests first failed on missing back navigation, automatic budget choice and repeat-setup action. Green: full web suite 22/22; TypeScript build; Vite production build. Existing PostgreSQL acceptance proves profile update preserves posted history and tenant membership; latest full Java Core gate passes 123/123. F03 marked complete. F04 remains next, especially preserving a chosen member name during Telegram identity sync.
- No commit created before the main plan and improved V2 plan are complete.

## E4.22 F04 Telegram display-name provenance and sync — 2026-10-04 02:58 MSK

- Added profile-name provenance in V22. A Telegram full name fills a workspace-default profile name during secure link redemption and refreshes that fallback on `/start` or `/menu`; a profile edit records a user-owned name that later Telegram sync cannot overwrite. Planned income, onboarding state, timezone, and currency remain outside the sync update.
- TDD: PostgreSQL API tests first failed on missing provenance schema and profile sync, then prove fallback fill/refresh, explicit-name protection, and protection after subsequent sync while preserving planned income and onboarding state. Full Java Core check: 126 tests, zero failures; the three F04 PostgreSQL scenarios pass again after the stronger assertions. Full Python/contracts: 143 passed, 3 provider-dependent tests skipped. `F04` marked complete; live Telegram/Keycloak deployment remains under operational gates.
- No commit created before the main plan and improved V2 plan are complete. Next: F05, with deterministic Russian amount parsing, malformed/disabled provider behavior, and confirmation-only writes as its acceptance focus.

## E4.23 F05 free-text transaction drafts — 2026-10-04 03:02 MSK

- F05 acceptance is complete for free-text expense, income, and debt-payment proposals. Russian number forms `2 тыс`, `1,5к`, `2 000`, and decimal comma normalize to exact kopecks; provider JSON is schema-checked, and malformed output or an unavailable model returns a safe error. AI only proposes: PostgreSQL confirms no financial transaction exists before the user confirms the versioned draft.
- TDD/verification: added direct malformed-JSON coverage; intelligence and gateway tests pass 33/33. PostgreSQL confirmation-only/stale-edit scenario passes. Existing full Python suite passed 143 tests with 3 provider-dependent skips; final complete suite will be rerun at the global gate. `F05` marked complete. Receipt-photo paths remain in F10-F18; broader Telegram parity remains under F01/E4.
- No commit created before the main plan and improved V2 plan are complete.

## E4.24 F06 draft review and explicit confirmation parity — 2026-10-04 03:09 MSK

- F06 review controls now have passing cross-client acceptance: amount, category, subcategory, description and date edits; quick amount buttons; explicit confirmation; cancellation; idempotency and stale-version safety. Cancellation and edits do not create a transaction before confirmation.
- Verification: Java Core 126 tests; web App 17/17; Telegram commands 14/14; Android connected instrumentation 20/20. The connected run built and installed `apps/android/app/build/outputs/apk/debug/app-debug.apk`; SHA-256 `FC05DEE3085835FD01438C15A9BC56247CA9DF9795594A436B67122F4558FC6D`. `F06` marked complete. F59 remains open for feature-by-feature Android mapping and real API/OIDC end-to-end proof.
- No commit created before the main plan and improved V2 plan are complete. Next: first incomplete `PLAN.md` feature, F09 desktop transaction CRUD and filtering.

## E4.25 F09 web transaction CRUD and filters — 2026-10-04 04:07 MSK

- F09 offline parity is complete. Web supports create/edit/repeat/void, member assignment and reassignment for owner/admin, member/period/type/search filters, all desktop transaction fields, and member-timezone date edits. Debt-payment edits reverse the old balance effect before applying the revised payment in one transaction; void compensates it.
- PostgreSQL tests cover owner/admin family history and edits, member denial, active-member assignment, reassignment, debt compensation, version conflicts, and filter scoping. Web App tests pass 21/21; TypeScript plus Vite production build passes; OpenAPI contract suite passes 31 tests with 2 skipped; full Java Core `check --rerun-tasks` passes with PostgreSQL enabled. `git diff --check` passes with existing line-ending/global-ignore warnings.
- Live Keycloak browser/API end-to-end remains in E2/F59. No commit before the original plan and V2 plan are complete. Next: F10 receipt upload/job progress and durable draft idempotency.
## E4.26 F10 receipt upload design gate

- Current code has tenant-scoped document metadata and receipt-review tables, but no byte upload, object-storage adapter, receipt-processing job, or web upload/progress flow. F10 remains the first incomplete feature.
- The proposed private S3-compatible quarantine flow and development provider were sent for user design approval. No F10 implementation has started; continue with the approved design, then write and review the F10 specification before implementation.
- Independent preparation confirmed the existing receipt API takes a `documentId`; OCR/Vision gateway contracts already accept durable job IDs. Next: record the selected provider and finalize upload, idempotency, scan, retry, retention, and status contracts in the design specification.
## E4.27 E2.2 local OIDC/API preflight — 2026-10-04 04:16 MSK

- Live local preflight passes: Keycloak finance discovery returns the expected realm, Core readiness is `UP`, web dev server returns 200, and `/oauth2/authorization/keycloak` redirects to the finance realm with `finance-web`. Through the `localhost:5173` web proxy, the callback exactly matches the client allowlist. Unauthenticated `/bff/session` returns `authenticated:false`; protected API returns 401.
- Authenticated browser/API acceptance remains open. The checked-in realm has no seeded test user and no SMTP settings, so a verified test identity still needs to be provisioned through the local identity setup. Do not weaken email verification to make the gate pass.

## E4.28 F30 Telegram report parity and event contract repair — 2026-10-04 04:56 MSK

- F30 acceptance is complete: Telegram `/report` supports month, week, 90 days, custom inclusive dates, personal/family scope, and consumes the same Core `FinanceReport` DTO behind actor-context `report.read`. PostgreSQL acceptance verifies personal vs family totals, date boundaries, forged tenant/subject rejection, service-token enforcement, and invalid periods. Telegram emits PNG through the existing renderer and uses bounded text fallback when rendering is unavailable.
- TDD exposed and fixed a separate event-contract regression: Core emits `debt.payment_adjusted`, missing from the debt event schema. Added a schema acceptance test (observed RED), then allowed the emitted event. Full persisted-event validation now passes.
- Verification: Java Core `:services:core:check --rerun-tasks --no-daemon` PASS; targeted report/render/gateway Python tests 46 passed; full Python + contracts with loopback excluded from the machine HTTP proxy PASS, 158 passed / 1 skipped; Web tests 26/26 and TypeScript/Vite production build PASS. One full Python attempt had a transient localhost socket reset; isolated case and next complete run passed.
- First full-suite attempt had 19 AI test failures because the host HTTP proxy intercepted `127.0.0.1`; setting `NO_PROXY=127.0.0.1,localhost` made those tests pass. The complete run then found only the event-schema mismatch fixed above.
- `git diff --check` passes with existing LF/CRLF warnings. Android SDK is not available at the standard path in this session; connected APK delivery remains under F59/global verification. The recorded earlier Android UI suite remains in E4.24.
- No commit: user explicitly asked for one full-project commit after the original plan. Next: continue the remaining F01/F02 Telegram parity and authorization scenarios while the F10 storage design decision remains pending.

## E4.29 F01/F02 menu, identity and legacy-partner acceptance — 2026-10-04 05:07 MSK

- F01 acceptance is complete: persistent menu and /start, /menu, /help are covered; registered /link, /add, /history and /report paths run in the offline gateway suite. The persistent reply keyboard is not removed by inline operation controls. Live Telegram/Redis/TLS remains a deployment gate.
- F02 acceptance is complete: an unlinked Telegram ID cannot enumerate tenants or obtain an actor context; one-time OIDC codes bind the Telegram ID to the Core-selected user; every action rechecks active link, membership and current role. The legacy partner compatibility policy maps to member-scoped write permissions. A new PostgreSQL scenario links a member through the OIDC code and verifies role, own-write permission, denial of shared-write permission, and no userId disclosure.
- No production correction was needed for F01/F02; the new PostgreSQL acceptance passed on the existing implementation, so no artificial RED was introduced. Full Java Core `:services:core:check --rerun-tasks --no-daemon` passes after the test addition (132 tests); full Python/services/contracts passes 158 with 1 skip; Web tests 26/26 and TypeScript/Vite production build pass.
- Live Telegram/Keycloak acceptance remains in E2/F59; SQLite owner/partner and full settings migration remain in E9. The Android SDK is not available at its standard path in this session; its latest recorded instrumentation result remains 20/20 in E4.24.
- No commit while the main plan remains unfinished. Next independent objective: F31 report charts; F10 remains blocked on the explicit storage design choice.

## E4.30 F31 monthly budget chart in Web and PNG — 2026-10-04 05:20 MSK

- Added RU/EN monthly report usage chart from the server's `monthlyBudgetLimit` and `monthlyBudgetRemaining`. It appears only for month reports with an enabled limit; other periods and disabled limits have no fabricated series. Personal/family scope is preserved. An overrun fills the bar to 100% while the exact negative remaining stays visible.
- Python PNG now includes the same limit, net usage and exact remaining. Text fallback includes the same budget values; malformed or partial budget DTO fields are rejected. Replaced an unsupported em dash in the image date label after visual inspection showed a missing glyph.
- TDD: Web report test was RED before chart implementation; PNG pixel and fallback assertions were RED before renderer implementation. PASS Web suite 26/26 and TypeScript/Vite production build. PASS full Python/services/contracts suite 159 passed, 3 skipped; focused renderer suite 8 passed after final glyph fix. Overrun and fallback cases covered; generated PNG was visually checked.
- F31 remains `in_progress` only for price and optional-purchase series, which will use authoritative F33/F40 data. The approved F31 design explicitly excludes placeholders. F10 storage/provider decision remains pending; no commit before both requested plans finish.

## E4.31 Legacy bot regression suite — 2026-10-04 05:27 MSK

- Preserved v1 behavior: `smoke_test.py`, full `handlers_test.py`, and `receipt_test.py` all pass. Coverage includes legacy transactions/budgets/debts, price history, daily/weekly summaries, menus, receipt edits, goals, and imports. Synthetic Tesseract receipt case passes. Two private sample fixtures were not present; optional Ollama/Vision providers were unavailable and their documented fallbacks ran.
- These are regression checks only; v1 `main` and legacy bot code were not changed by F31. No commit before completion of the original plan and V2 plan.

## E6.1 F33 deterministic Go price policy — 2026-10-04 05:38 MSK

- Added `services/analytics-go/prices` as the pure v1 price projection policy: exact line-sum/quantity unit price to six places (half-even), standard median with averaged middle pair for even counts, legacy change thresholds (RUB 10 and 12%), and direction.
- Price history is filtered to the same tenant/member, earlier purchase time, and a different receipt. No prior history yields no baseline. Matching preserves the legacy token/sequence rule while rejecting known conflicting package sizes and keeping equivalent units comparable. Algorithm output is versioned `price-projection.v1`.
- TDD: tests were RED before the Go implementation. PASS Go 1.27.1 `go test ./...`, `go vet ./...`, and Linux/amd64 cross-build on Windows. Added a pinned-version Go test/vet CI job and documented the policy in `docs/specs/price-projection-v1.md`.
- F33 remains `in_progress`: confirmed-receipt event ingestion, ClickHouse projection, Core owner-scoped history API, and product UI are not implemented. Live ClickHouse verification is not available because Docker/ClickHouse are absent. No commit before the original plan and V2 plan finish.

## E6.2 F33 durable confirmed-receipt event — 2026-10-04 05:58 MSK

- Core now writes `receipt.confirmed` to the durable outbox in the same transaction that posts the receipt expense and marks the receipt confirmed. The snapshot includes every item in ordinal order, item ID/name, quantity and paid line sum, tenant/member, receipt date, currency, merchant and transaction ID. Idempotent retries return the saved result without a second event.
- Added the strict `finance.receipt.v1` JSON Schema and documented event ownership and consumer deduplication in `docs/specs/price-projection-v1.md`. The Go projector consumes only positive, valid quantity/line-sum values and never fabricates a zero-price baseline.
- TDD: Java PostgreSQL acceptance and schema contract first failed on missing outbox event/schema, then passed. PASS `TransactionApiPostgresTest`: 55/55 against an isolated PostgreSQL 18.6 database; PASS focused receipt event JSON Schema test: 1 passed; `git diff --check` passes with existing line-ending warnings.
- F33 remains `in_progress`: Go durable consumer/ClickHouse projection, owner-scoped Core history API and Web/PNG price views remain. Live ClickHouse runtime is still unavailable. No commit before both requested plans finish.

## E6.3 F33 Go event projection and ClickHouse adapter — 2026-10-04 06:13 MSK

- Added strict Go decoding for `receipt.confirmed`, preserving event/aggregate identity and tenant/member scope. Valid paid lines become exact six-decimal unit-price points; absent, non-positive or malformed price lines are skipped without rejecting the shared event.
- Added a retryable projection consumer core and credentialed ClickHouse HTTP storage adapter. Inserts use fixed-table JSONEachRow; history queries are parameterized, tenant/member-scoped and use `FINAL`; out-of-scope rows are rejected. Remote plaintext HTTP is blocked. Added `ReplacingMergeTree(aggregate_version)` DDL partitioned by receipt month.
- TDD: event projection, storage and consumer tests first failed on absent APIs, then passed. PASS Go 1.27.1 `go test ./...`, `go vet ./...`, and Linux/amd64 cross-build; mock HTTP tests cover credentials, stable replay keys, cutoff/scope predicates and wrong-scope result rejection.
- F33 remains `in_progress`: Kafka runner and live ClickHouse replay/reconciliation, Core owner-authorized history API and Web/PNG price views remain. This Windows host has no Docker or WSL Linux distribution, so no live ClickHouse server is available. No commit before both requested plans finish.

## E6.4 F33 Kafka consumer — 2026-10-04 06:48 MSK

- TDD observed RED: new consumer acceptance could not compile because Kafka worker contracts were absent. The first worker tests now pass and verify write-before-offset-commit, retry, no commit after exhausted storage retries, and DLQ-before-commit.
- Verification attempt 1: Go suite stopped at setup because the SCRAM import needed a missing `go.sum` entry for `github.com/xdg-go/scram`; projection unit tests passed. Next: resolve the declared transitive module, rerun Go suite, then complete Kafka adapter and runner.

## E6.4 F33 Kafka projection runner — 2026-10-04 06:59 MSK

- Added Go Kafka consumer group for inance.receipts.v1. It reads committed messages, writes ClickHouse first, then synchronously commits offsets. ClickHouse retries five times with exponential jitter. Invalid events go to a metadata-only DLQ; failed writes or DLQ publishes leave the source offset uncommitted. Kafka SASL/PLAIN or SCRAM requires TLS.
- TDD RED: worker acceptance initially failed to compile because the worker, message, and dead-letter contracts were absent. GREEN: worker tests cover write-before-commit, retry order, no commit after exhausted retries, safe DLQ metadata, and no source commit when DLQ publish fails.
- Verification: Go go test -count=1 ./..., go vet ./..., and Linux/amd64 cross-build pass. Integration-tagged test compiles but skips locally because Kafka/ClickHouse endpoints are absent. Added CI job to start Redpanda and ClickHouse and exercise replay deduplication and DLQ; remote CI has not run. Core TransactionApiPostgresTest passes 56/56 including active-family-member denial; contract tests pass 26/26; git diff --check passes with existing LF/CRLF warnings.
- F33 remains in_progress: Web/PNG price views await the user's F33 UI design reply, and live Kafka/ClickHouse integration remains NOT_RUN. F32 digest design and F10 quarantine storage choices remain unanswered. No commit: user requested one full-project commit only after main and V2 plans finish.

## E4.32 F11 Vision image preparation and bounded retry — 2026-10-04 07:15 MSK

- TDD RED: new acceptance failed because Vision sent the original JPEG unchanged and returned immediately on malformed JSON. GREEN implementation applies EXIF orientation, re-encodes as metadata-free optimized PNG, caps the longest side at 3000px, and retries a strict schema/output failure exactly once with a fixed prompt that does not include untrusted model text. The actually selected model remains in provenance.
- Verification: Vision/Tesseract/AI HTTP tests pass 31 with 1 optional local-Tesseract skip. The first combined run showed 19 AI HTTP tests returning proxy-generated 503; reproducing with loopback proxy bypass (`NO_PROXY=127.0.0.1,localhost`) made the same test pass and the complete focused suite green. No API code change was made for the environment issue.
- F11 stays `in_progress`: configured-model failover/resource scheduling and how the Vision endpoint hands off to the separate OCR schema need a design decision. Asked the user whether to use a bounded model pool with typed OCR fallback, or a new combined Vision+OCR endpoint. No implementation of that boundary until the design is chosen. No commit before both requested plans finish.

## E2.2 Core regression gate — 2026-10-04 07:19 MSK

- PASS `:services:core:check --rerun-tasks --no-daemon` against the isolated PostgreSQL test database: 134 tests, 0 failures/errors/skips. Includes the receipt price history authorization path and existing tenant/membership checks. `git diff --check` passes; `PROGRESS.md` and `.agent/PROGRESS.md` hashes match.
- Keycloak authenticated browser proof remains open; HTTP preflight only is not end-to-end acceptance. No commit before the main plan and V2 plan complete.

## E4.33 Android emulator regression gate — 2026-10-04 07:30 MSK

- Found Android SDK/emulator under the installed Unity toolchain. PASS `:app:connectedDebugAndroidTest --no-daemon` on FinanceCodex(AVD) - 14: 20/20 tests, zero failures/skips. Gradle built and installed package `com.decorix.finance.debug`; `adb shell pm path` confirms the APK is present on device.
- Current debug APK SHA-256: `589AA444F8AB0A4446C211C320CEFFFB0FB0D7C6103296C6DB18017EED13AA98`. This is a valid installed test artifact; F59 remains open because F01-F58 Android feature mapping and a real Java/Core/OIDC E2E flow are incomplete. No commit before both requested plans finish.

## E6.5 F33 public/private price schema consistency — 2026-10-04 07:34 MSK

- TDD RED: new parity acceptance failed because the public price-comparison contract omitted value descriptions present in the service contract. GREEN: public OpenAPI now describes prior median, delta, fractional relative value (`0.125000` = `12.5%`), thresholds, direction and ordered history identically to the internal service schema.
- PASS all contract tests: 27/27. This documents the API already implemented; it does not add price UI or change price calculations. F33 remains in progress for approved UI, live integration and remaining feature parity. No commit before both plans finish.

## E3.14 F27–F29 debt parity — 2026-10-04 07:45 MSK

- Ported the legacy Telegram debt overview to `/debts`: Core supplies tenant-scoped debt cards; the gateway shows balance, repaid share, interest, minimum payment and total. The existing `/add` payment path still requires an active Core-listed debt and explicit draft confirmation; cross-tenant debt data is rejected. CoreClient now validates and preserves the debt card fields.
- TDD: Telegram command and CoreClient tests were RED before the handler/contract implementation; GREEN gateway suite: 46/46. `:services:core:check --rerun-tasks --no-daemon` with PostgreSQL passes 134/134, and existing Web debt-flow 26/26/build plus Android instrumentation 20/20 pass. F27–F29 acceptance now complete; F59 still owns full Android F01–F58 parity and live OIDC E2E. No commit before both main and V2 plans finish.

## E4.34 Python and contract regression — 2026-10-04 07:55 MSK

- First full run: 167 passed, 3 skipped, one Windows loopback `ReadError` (`WinError 10053`) in an internal HTTP test. No product failure reproduced.
- Isolated failing test passed 10/10 consecutive runs. Repeated full `services/python tools/contracts` suite passes 168/168 with 3 optional skips. `git diff --check` passes; only existing LF/CRLF warnings remain. Progress mirrors have identical SHA-256.

## E3.15 Telegram budget and cash guidance — 2026-10-04 08:14 MSK

- Added a service-token-protected Core Telegram budget read endpoint. It resolves only the opaque actor context and requires `budget.read`; request-supplied tenant or subject fields have no authority. Gateway `/budget` shows family and effective personal totals, category limit states, rolling food status, and Core's safe-to-spend guidance. Workspace dashboard also displays server-provided safe-to-spend values.
- TDD: new gateway client/command tests were RED before implementation. PASS targeted gateway suite 48/48; full Python/contracts suite 173 passed, 3 skipped; full Core `check --rerun-tasks --no-daemon` passes against PostgreSQL. Existing tests verify service-token rejection and that forged tenant/subject fields do not change actor scope. `git diff --check` passes after correcting whitespace in the added integration assertion.
- F22–F26 remain `in_progress`: Telegram budget edits/reset and proposal apply, proactive alert delivery and digest integration remain. No commit before both requested plans finish.

## E3.16 Telegram budget edits and reset — 2026-10-04 08:27 MSK

- Added `/budget set <family|personal> <category|total|food_week> <amount>` and `/budget reset`. Writes use Core actor context, `budget.write`, per-scope/category optimistic versions and message-derived idempotency keys; category and amount parsing is strict. Core exposes update/reset routes that call the existing budget service, preserving family values and preventing client-supplied tenant identity.
- TDD: four new gateway/client tests and extended PostgreSQL API assertions were RED before the implementation. PASS gateway client/command tests 52/52; Core Telegram update/reset PostgreSQL integration passes with `--rerun-tasks`; full Python/contracts suite passes 177 with 3 optional skips after bypassing the configured loopback proxy. The unadjusted run's 19 HTTP 503 failures were proxy responses and reproduced the known environment issue, not application failures. `git diff --check` and mirrored progress hashes are checked after this entry.
- F22 acceptance is complete. F23 proposal application, F24 alert delivery, F25 digest consistency, and F26 recurring-worker freshness remain open. No commit before the main and V2 plans finish.

## E4.35 F12 multi-variant OCR pipeline — 2026-10-04 08:50 MSK

- Ported the receipt OCR path into `services/python/intelligence/receipt_ocr.py`: bounded grayscale/crop/upscale/Otsu/deskew/denoise variants, geometry-preserving box mapping, stable result ordering across two parallel Tesseract reads, right-aligned price/total column clustering, conservative split-number repair and targeted missing-total-cell rereads with a numeric whitelist. The selected OCR contract now records `tesseract-ocr.v2`; OpenAPI matches.
- TDD RED: adapter tests showed a single pass did not repair split cents or reread a missing amount, and the cell-reader injection did not exist. GREEN covers ordinary and narrow synthetic receipts, a 4-degree tilt correction, stable parallel completion, numeric columns, coordinate mapping and targeted reread. Real local Tesseract recognized generated ordinary and tilted/narrow receipt images. OpenCV is pinned to `opencv-python-headless==5.0.0.93`.
- PASS full `services/python` and `tools/contracts`: 186 passed, 2 optional PostgreSQL-only skips, with loopback proxy bypass and local Tesseract enabled. `git diff --check` and mirrored progress hashes are checked after this entry. F12 is complete; F10 upload/job awaits a dev object-storage choice; F13/F14/F15–F21 receipt flows remain open.

## E3.17 F23 Telegram budget proposals — 2026-10-04 09:11 MSK

- Added `/budget suggest <income>` for deterministic 70% proposals and `/budget propose` for history-based AI proposals after Core's 30-day gate. Telegram shows source, income, total/category limits, and a clear pending state; the button applies only the exact proposal held in the same user's and tenant's FSM state. Core service-token routes resolve the opaque actor context and ignore forged tenant/subject fields.
- TDD: CoreClient and Telegram command/callback tests plus PostgreSQL API coverage for both proposal types pass. Full Python/contracts: 190 passed, 2 optional database skips. A Windows loopback test once hit `WinError 10053`; its isolated case passed 5/5 and the full suite passed on rerun. `git diff --check` passes; progress mirrors stay synchronized. F23 remains in progress while local-model proposal quality is evaluated. No commit before main and V2 plans finish.

## E4.36 F47 T-Bank PDF parser — 2026-10-04 09:30 MSK

- Added a separate Python parser for the supported T-Bank statement PDF. It uses `Decimal`, preserves signed operations, local date/time, merchant/card data and parsed/expected totals, and distinguishes `valid`, `mismatch` and `unverifiable`. Zero totals are recognized; missing totals do not count as verified. Invalid bank format, image-only PDFs and empty statements return stable explanatory error codes. Parser result has a strict v1 JSON Schema; the original v1 parser and bot remain unchanged.
- TDD RED/GREEN covers multi-page operations, refund/income classification, merchant/card extraction, exact values, missing/zero totals, 1 RUB reconciliation tolerance, invalid/scanned/empty PDFs, impossible dates, Core amount bounds and strict payload validation. Targeted parser/schema suite passes 9/9; full Python/contracts passes 199 with 2 optional database skips; `git diff --check` passes with line-ending warnings. F47 remains in progress pending Core staging, preview/selection policy and real PDF integration.

## E4.37 F47–F49 T-Bank import review and batch lifecycle — 2026-10-04 10:44 MSK

- Connected the in-memory Python parser to a token-protected internal HTTP endpoint and Core parser client. Core stages tenant-scoped rows and preserves `valid`/`mismatch`/`unverifiable`; the Web panel shows exact signed rows, exclusions, reconciliation and RU/EN selection. A generated Cyrillic statement PDF passes through the actual parser endpoint with HTTP 200 and valid totals. The first smoke request used the configured loopback proxy and returned its 503; rerunning with `httpx.Client(trust_env=False)` reached the parser directly and passed.
- Added explicit `confirmed` batch creation with optimistic revision and idempotency key. JDBC batches insert transaction rows, audit records and `transaction.created` outbox events in one database transaction. SHA-256 dedupe uses the complete normalized description and one-based occurrence number, so overlapping files skip repeats while identical rows within one statement remain distinct. Undo voids only untouched rows created by that batch; any edited/voided row returns its ID and blocks the whole undo without partial changes.
- TDD: confirmation was RED before the route existed; PostgreSQL coverage now exercises stage/selection, idempotent confirmation, overlapping expense/refund duplicates, repeated identical purchases, CSRF and atomic undo conflicts. F47–F49 contract/parity entries are complete. PASS `:services:core:check`; PASS Python/contracts `202 passed, 3 optional database skips`; PASS Web `30/30` and production build; PASS `git diff --check` (line-ending warnings only). No regression to the legacy bot was observed in the existing suites.
- Next: F50 merchant classification and durable user corrections. No commit yet; per the user’s sequence, finish the main plan first, then commit, then write and execute improved V2.

## E4.38 F50 merchant classification — 2026-10-04 11:41 MSK

- Added persistent tenant/member merchant mappings and 90-day classification cache, strict AI classification batches, F50 hypothesis and clarification APIs, and RU/EN Web controls. AI receives normalized merchant names only; it does not receive transaction amounts, descriptions, tenant IDs or user IDs. Personal mappings take precedence; user selection persists a durable mapping. F51 past-transaction reclassification remains separate.
- TDD evidence: merchant policy tests and Web behavior tests cover precedence, normalization, the 300 RUB threshold, six-item cap and explicit user choice. Added malformed model-category validation coverage. A new Core unit test first failed because invalid merchant labels escaped as `IllegalArgumentException`; fixed validation to return HTTP 400.
- PASS focused Core tests; PASS `:services:core:check --rerun-tasks --no-daemon`; PASS Python/contracts `208 passed, 3 skipped`; PASS Web `31/31` and production build. PostgreSQL integration class skipped all 62 tests because no local service, Docker, or `FINANCE_TEST_DATABASE_URL` exists. F50 code and static gates are present; durable DB path remains unverified and F50 stays in progress. No commit until main and V2 plans finish, per user instruction.
- Next: F51 explicit preview/apply for eligible prior bank-import transactions. Revalidate tenant/member, exact IDs and versions; skip any transaction manually edited since import.

## E4.39 F50 PostgreSQL acceptance — 2026-10-04 11:52 MSK

- Located the existing project-local PostgreSQL 18.6 cluster on port 55432. Preserved its pre-existing `finance_test` database because it had eight V1 tables without Flyway history. Created a separate empty database `finance_test_codex_20261004` on that cluster; did not alter unrelated PostgreSQL processes or the existing test database.
- PASS F50 PostgreSQL acceptance: `merchantHypothesesAreCachedUntilAUserRuleOverridesThemAndApplyOnImport`. PASS full `:services:core:check --rerun-tasks --no-daemon`: 153 tests, 0 failures, 0 skips. PASS Python/contracts 208 passed, 3 optional skips; Web 31/31 and production build. F50 is complete. Main plan F47–F51 remains open until F51.
- F51 current scope: explicit preview/apply by normalized merchant, current personal mapping as target, only committed own-member `bank_import` expenses with unchanged managed version; revalidate exact candidate IDs and versions at apply; update category atomically with transaction audit/outbox. Any stale candidate or mapping change rejects whole operation.
- Existing worktree contains many earlier uncommitted SaaS-plan files from the previously authorized deferred-commit sequence. Will reconcile goal-level commit boundaries before committing; no files discarded.

## E4.40 F51 historical merchant reclassification — 2026-10-04 12:40 MSK

- Added additive V27 baseline tracking for created, posted bank-import expenses that have no explicit import-row category. Preview/apply now targets only the importing tenant member, verifies exact reviewed IDs/versions/categories and current mapping, and excludes manually corrected rows or transactions edited after import.
- Mapping saves, import confirmation, and apply share transaction-scoped tenant/member/merchant locks; imports lock merchant keys in sorted order. Apply uses the regular transaction update path, versioning, audit/outbox and all-or-nothing retry behavior. RU/EN Web keeps preview and Apply as separate explicit actions; BFF writes require CSRF.
- TDD: OpenAPI tests were RED before route/schema addition; the PostgreSQL lock regression was RED before adding the shared lock. PostgreSQL acceptance covers stale/manual edits, explicit row category, other-member isolation, idempotency, outbox, BFF CSRF and import serialization.
- PASS fresh `:services:core:check --rerun-tasks --no-daemon`: 159 tests, 0 failures/errors/skips. PASS Python/contracts: 209 passed, 3 optional skips. PASS Web: 32/32 and TypeScript/Vite production build. `git diff --check` passes.
- On this Windows host HTTPX routed loopback tests through the system proxy; the Python test clients now explicitly bypass environment proxy settings. No product network behavior changed.
- F51 and grouped F47–F51 are complete. F31 design reconfirmed: monthly limit charts in Web/PNG, with no placeholder price or optional-purchase series; those wait for F33/F40 authoritative data. Legacy `main` remains unchanged.

## E4.42 F10 private receipt photo upload and OCR jobs — 2026-10-04 13:56 MSK

- Implemented JPEG/PNG validation (10 MiB, 25 MP), generated private quarantine keys, filesystem and S3-compatible storage, fail-closed ClamAV scanning, authenticated OCR calls with validated provenance, durable leased/retryable jobs, monotonic progress, idempotent draft creation, owner-scoped OCR readings, and RU/EN upload/progress/review UI. OCR remains evidence; no transaction is posted before explicit confirmation.
- Added the private-storage spec and ADR, runtime examples, contract/parity coverage, an authenticated SeaweedFS S3 config, and a CI integration workflow. Removed misleading `visibility=private` object metadata; access must be denied by the bucket/provider policy.
- Verification: fresh Core `check --rerun-tasks --no-daemon` passes 177 tests, 0 failures/errors, 2 skipped container integration tests. Python/contracts pass 211 with 3 optional skips. Web passes 34/34 and production build. Legacy `smoke_test.py`, `handlers_test.py` and `receipt_test.py` pass; optional sample images/model paths were not configured. `git diff --check` passes.
- One full Python attempt hit the known intermittent Windows loopback `WinError 10053`; the isolated test passed 5/5 and the full suite then passed. The first Web build exposed a TypeScript idempotency-key signature mismatch; it was fixed, and Web tests/build now pass.
- The real SeaweedFS anonymous-read and ClamAV EICAR checks are configured in `.github/workflows/receipt-storage.yml`; they remain unobserved because this host has neither Docker nor Podman. F10 remains `in_progress` until that integration gate runs. Next: continue with the next open plan goal while preserving this gate as a final acceptance item.

## E4.43 F11 bounded Vision pool and typed OCR fallback — 2026-10-04 14:31 MSK

- Applied the selected design: three-tag ordered Ollama pool, actual-model provenance, one process-wide model slot, and separate typed Vision/OCR fallback. Both successful Vision and OCR fallback persist safe reasons; review API and RU/EN screen expose provenance. Vision-only and OCR-only paths create a review draft but never a transaction.
- TDD: PostgreSQL acceptance first failed because Vision/OCR fallback reasons were absent from `/readings`; Web tests first failed because model failover was not displayed. Added response/contract fields and bilingual UI. Full Core/PostgreSQL `check --rerun-tasks` passes 183 tests, 0 failures, 2 container-gated skips; Python/contracts pass 219 tests, 3 optional skips; Web passes 34/34 and production build. Contract spec and ADR-015 record the accepted boundary.
- Live model check found no Ollama endpoint at `127.0.0.1:11434`; actual model quality remains unverified, so F11 stays `in_progress`. F10 SeaweedFS/ClamAV integration remains pending its configured CI gate. No commit before both the main plan and improved V2 plan finish.
- Next: F13, wire the existing deterministic reconciliation policy into OCR/Vision worker/API and review evidence; do not mark F13 complete from the current unit tests alone.

## E4.44 F13 OCR/Vision reconciliation and bounded top-up — 2026-10-04 15:02 MSK

- Tesseract now returns named coordinate-backed item rows and labeled totals. Java compares them with Vision evidence one-to-one, preserves both readings and exposes arithmetic, mismatches, item provenance, and top-up evidence through the owner-scoped API.
- A top-up appears only when at most three unused OCR rows form the unique combination closing a positive Vision gap within two kopecks; ambiguous combinations and already reconciled OCR stay review-required without a suggestion. The review API and RU/EN Web show original OCR ordinals and values; no suggestion selects or posts a receipt.
- TDD: one-to-one worker/API test and unique top-up PostgreSQL worker/API test pass; a new cent-tolerance ambiguity test was RED before search was corrected and now passes. Full Core `check --rerun-tasks`: 188 tests, 0 failures/errors, 2 container-gated skips. Python/contracts: 221 passed, 3 optional skips. Web: 34/34 and production build. `git diff --check` passes with expected Windows line-ending notices.
- F13 is complete. F10 SeaweedFS/ClamAV integration and F11 live Ollama quality remain external acceptance gates. Next: F14, enforce evidence-backed merchant/date extraction and category fallback with unknown-preserving review output.

## E4.45 F14 receipt metadata and category safeguards — 2026-10-04 15:10 MSK

- Locked the evidence boundary in `docs/specs/receipt-evidence-category-v1.md`: Vision metadata stays an unverified source reading, while receipt merchant/date/total remain null until user verification. Missing category evidence remains null with source `unknown`; missing item amounts do not trigger deterministic category promotion.
- The RU/EN review now labels model values unverified and displays the source date. PostgreSQL tests pass non-null Vision claims and prove draft fields/category stay unknown while the owner-scoped reading preserves those claims. Python preserves null store/date/total, and category tests keep shares unknown. No transaction is posted.
- PASS full Core check: 189 tests, 0 failures/errors, 2 container-gated skips. Python/contracts: 222 passed, 3 optional skips. Web: 34/34 and production build. `git diff --check` passes with Windows line-ending notices.
- F14 is complete. F10 SeaweedFS/ClamAV and F11 live Ollama remain external acceptance gates. Next: F15, verify receipt item CRUD, eight-row paging, and explicit total synchronization across Core and Web.

## E4.46 F15 receipt item review lifecycle — 2026-10-04 15:12 MSK

- PostgreSQL acceptance now covers add, edit, delete, page-of-eight, optimistic versions and unchanged cash totals. Only the explicit sync action copies complete item sums into the cash total. The RU Web tests cover add/delete, paging and explicit sync; F14 full Web build/tests remain green.
- PASS focused PostgreSQL test `receiptItemsArePagedVersionedAndOnlyExplicitSyncChangesCashTotal`; it adds the 10th item after paging nine, verifies cash total stays at 101.00 through edits, and syncs to the reviewed 84.00 total. Web receipt tests: 7/7. Prior full gates: Core 189, Python/contracts 222 passed/3 skipped, Web 34/34 and production build.
- F15 is complete. Next: F16, check same-amount duplicate candidates, explicit independent/duplicate decisions and idempotent confirmation.

## E4.47 F16 receipt duplicate review — 2026-10-04 15:13 MSK

- Verified same-owner receipt candidates are limited to the preceding ten minutes and exact totals. The user can mark a candidate duplicate or independent; stale writes fail and decisions are audited. A duplicate blocks confirmation, while two independent receipts with equal totals create separate expenses.
- PASS policy and PostgreSQL tests `ReceiptDuplicatePolicyTest`, `ReceiptConfirmationPolicyTest`, `receiptDuplicateDecisionIsOwnerScopedVersionedAndReversible`, and CSRF rejection. The integration test replays receipt creation and confirmation keys and proves exactly two distinct receipt transactions for two independent purchases. RU/EN Web duplicate-review flow passes.
- F16 is complete. Next: F17, validate receipt category shares, manual override provenance and leisure/alcohol thresholds.

## E4.48 F17 receipt category policy — 2026-10-04 15:14 MSK

- Verified category thresholds at exactly 10% alcohol and 25% leisure goods, plus just-below boundary, missing amounts, manual override and human provenance. PostgreSQL confirms rule-derived shares persist and a user can replace the category with version checking; RU/EN Web exposes the choice and source.
- PASS `ReceiptCategoryPolicyTest`, PostgreSQL rule/manual/stale-version tests and all 7 receipt Web tests. Full Python/contracts and Web build remain green from F14/F15; Core category integration passed against the project-local PostgreSQL database.
- F17 is complete. Next: F18, validate strict basket advice, duplicate cleanup, rule precedence, provenance and no model-generated totals.

## E4.49 F18 receipt basket advice — 2026-10-04 15:15 MSK

- Python request context contains only bounded `{ordinal,name}` rows; model output schema has no money fields, and rejects extra fields or invented values. Java safety rules override model advice where required, remove repeated generic advice only across different products, and use neutral `default` for absent or invalid opinions. Item IDs retain rule/model/default source.
- PostgreSQL test proves request excludes cash/item sums, beer safety rule overrides a model's useful verdict, original receipt totals stay fixed, advice is versioned/audited, and stale review fails. CSRF BFF rejects writes without token. Python 8/8, focused Java/PostgreSQL, and RU Web receipt tests pass.
- F18 is complete. Next: F19, validate advice stays attached to receipt item IDs with duplicate names and preserve `unknown` provenance for historical rows.

## E4.50 F19 item advice provenance — 2026-10-04 15:22 MSK

- Existing UUID mapping, per-item advice fields, provenance and immutable audit snapshots met F19; this goal needed stronger PostgreSQL acceptance, not production changes.
- PostgreSQL test uses identical item names with distinct amounts and ordinal advice. Both response and audit snapshots retain correct item UUID/reason mapping. A legacy `neutral` row with source and algorithm `unknown` remains unknown through owner API.
- PASS focused acceptance test; full Core check: 190 tests, 0 failures, 2 container-gated skips. Python migration/contracts: 44 passed, 2 skipped. RU/EN receipt Web tests: 7 passed. `git diff --check` passes; only expected Windows line-ending notices. PROGRESS mirrors match.
- F19 is complete. Next: F20, validate reversible product decisions, disputed-row pagination and consistent exclusion from optional-spend advice.

## E4.51 F20 user product decisions — 2026-10-04 15:26 MSK

- Existing tenant/user-scoped decisions already drive disputed receipt pages, repeat-waste warnings, and RU/EN allow/revoke controls. No production change was needed; F20 was missing executed PostgreSQL acceptance evidence.
- Focused PostgreSQL tests prove normalized duplicate names share one decision, disputed rows page by eight, allow hides rows and repeat warnings, and revoke restores both. Repeat-warning and product identity policy tests pass; Web receipt flow exercises allow/revoke.
- PASS targeted Core tests: 2 PostgreSQL cases and 5 policy cases, 0 failures. Full Core check: 190 tests, 0 failures, 2 container-gated skips. Python migration/contracts: 44 passed, 2 skipped. Web receipt tests: 7 passed.
- F20 is complete. Next: F21, verify prior-only repeat warnings, fuzzy product matching and allowed-product suppression.

## E4.52 F21 repeat-purchase warnings — 2026-10-04 15:26 MSK

- Existing Java policy ports legacy token-overlap and sequence-similarity matching. Owner API reads confirmed history only, excludes current receipt, filters the same allowed-product decisions, and presents count, latest amount and advice in RU/EN Web.
- PostgreSQL test proves current receipt is not counted as history and that allowing the matching product removes its warning. Policy tests cover fuzzy identity and stable warning data; Web test checks count, amount and advice.
- PASS targeted Core tests: 2 PostgreSQL cases and 5 policy cases, 0 failures. Full Core check: 190 tests, 0 failures, 2 container-gated skips. Python migration/contracts: 44 passed, 2 skipped. Web receipt tests: 7 passed.
- F21 is complete. Next: F22, verify personal/family budget inheritance, reset and family report semantics.

## E4.53 F22 family and personal budgets — 2026-10-04 15:32 MSK

- PostgreSQL proves a personal food override changes only food: transport remains at its family limit and has no personal override. Reset restores the updated family limit; stale family versions fail. Telegram uses the linked actor scope for personal update/reset and family reports.
- Family report keeps the family monthly limit while personal report uses the member-facing limit. RU/EN Web budget tests and Android owner/viewer screens cover budget access and display; latest instrumented suite already passed 15/15 on the APK build with F31 chart changes.
- PASS targeted PostgreSQL budget/report/Telegram tests; added category-isolation assertion passes. Web App tests: 21 passed. Full Core check: 190 tests, 0 failures, 2 container-gated skips. Python migration/contracts: 44 passed, 2 skipped.
- F22 is complete. Next: F23, verify 70% proposals, rounded allocation, privacy-limited AI context and explicit apply.

## E4.54 F23 income-based budget proposals — 2026-10-04 22:03 MSK

- Java policy and PostgreSQL acceptance cover 70% of income, exact allocation rounding, debt exclusion, the 30-day history gate, tenant isolation, pending proposals, explicit apply and stale-base rejection. These targeted Java tests passed earlier in this work session; a rerun here could not start because `JAVA_HOME` and `java` are unavailable in the current shell.
- Python proposal/privacy and Telegram actor/callback tests: 11 passed. A live local Ollama evaluation of the synthetic `budget-steady-history-v1` case passed 1/1 at 100% with `qwen2.5:7b-instruct` (12.7 s); no personal data was used.
- `F23` is complete. `F24` is now complete. Next: `F25`, verify rolling food limits across dashboard, digest, and report.

## E3.18 F24 monthly budget alerts — 2026-10-04 23:10 MSK

- Core now returns one-time server-computed 90%/100% category and total budget crossings after an expense is committed. Tenant/member-scoped advisory locking serializes concurrent expense snapshots; idempotent responses retain the alert. Web, Android, and Telegram show localized alerts. Zero category limit disables that alert; refunds and debt payments stay outside expense thresholds.
- Acceptance adds PostgreSQL cases for category near/exceeded/no-repeat and total near/exceeded while category limit is disabled. Existing Core acceptance covers month projection, elapsed/future periods, December, leap February, and debt accounting. `BudgetAlertPolicyTest` covers exact threshold crossings and disabled limits.
- PASS full `:services:core:check --rerun-tasks --no-daemon`: 194 tests, 0 failures, 2 skips limited to SeaweedFS/ClamAV integration. PostgreSQL API suite: 80 tests, 0 failures/skips. PASS Python/contracts: 223 passed, 3 optional skips; Web: 35/35 and production build; Android connected instrumentation: 23/23. Debug APK installed and launched on emulator; SHA-256 `BED3716A17E748CBFFE2AF2E86D6B117C736C72FC348A7DE507D9A711B3B564F`. `git diff --check` passes; line-ending notices only.
- Contract validation initially failed because the new budget-money pattern had excess escaping. Corrected the OpenAPI pattern; targeted and full contract suites pass (34/34). No product defect remained.
- F24 acceptance is complete. No commit created under the existing user-requested final commit sequence; preserve mixed prior worktree changes. Next: F25, compare rolling-seven-day values across current dashboard, digest, and report implementations.

## E4.55 F25 rolling-food report output and F32 decision — 2026-10-04 23:33 MSK

- F25 Core already returned the same rolling-food DTO for budget, dashboard, and personal/family reports. Added Python text-report rendering and Telegram photo captions from that DTO; amounts are shown verbatim, insufficient history has no invented baseline, and disabled limits omit remaining amount.
- Observed RED: two report-renderer assertions failed because text output dropped rolling-food status. Telegram `/report week family` assertion failed because its photo caption omitted spend and remaining limit. Added DTO validation, text output, and Russian Telegram caption. A malformed disabled-limit DTO is now rejected.
- PASS report-renderer tests: 11/11. PASS Telegram weekly-report command: 2/2. PASS `services/python tools/contracts`: 226 passed, 3 skipped. Earlier Java/PostgreSQL acceptance covers same DTO across budgets, dashboard, and reports; Web/Android display that DTO.
- F25 remains PARTIAL: scheduled digest delivery must call the shared report renderer; F32 owns that delivery path. F32 was moved ahead of F25 completion as a dependency, without changing product scope.
- Ruling from user delegation: PostgreSQL owns member notification preferences, durable logical intents, and delivery attempts. Python worker claims/acks Telegram delivery with retries. Core report DTO remains financial source. Unique occurrence key prevents duplicate logical intents; Telegram transport is at-least-once when send acknowledgement is ambiguous. Decision is in `docs/adr/ADR-016-durable-digest-scheduling.md`.
- Preserve v1 defaults for linked Telegram members: daily 21:00, weekly Sunday 19:00; both enabled. Time uses member timezone. Quiet hours remain unset unless configured. Default language is RU; user may choose EN.
- Current goal is F32. Next: add PostgreSQL acceptance for defaults, local schedule settings, and tenant isolation; then implement durable preference storage and API.
- Repository remains `feat/saas-rewrite` at `fb00ede` with broad pre-existing uncommitted work. No files staged or committed; keep unrelated changes untouched. Per-goal commit is required after each goal passes full verification.

## E4.56 F32 durable digest delivery and F25 parity closure — 2026-10-05 01:04 MSK

- F32 is complete. V30/V31 store member-local notification preferences, logical intents, leases, and attempts. Core provisions defaults, computes due local occurrences, coalesces missed windows, honors quiet hours, and claims durably. Web and Android now edit RU/EN, daily/weekly enablement and local times, weekday, and paired quiet hours with version checks. Python sends the Core report DTO, suppresses no-data digests, and records bounded retry/permanent outcomes; ADR-016 documents delivery ambiguity.
- F25 is complete: shared `rolling_food_lines` formatting now serves text reports and scheduled digest output; Telegram report captions also use the Core DTO unchanged.
- GREEN: Core 200 tests, 0 failures, 2 integration skips; targeted PostgreSQL F32 defaults/version/lease/report tests and `NotificationScheduleTest`; Web 36/36 plus `tsc`; Telegram renderer/worker/client/commands 71/71; contracts/migration 46 passed/2 skipped; Android emulator instrumentation 26/26, APK installed on clean `emulator-5556`; `git diff --check` clean (line-ending warnings only).
- Closed F25 and F32 in the parity registry. The original emulator's app data was preserved; instrumentation ran on a fresh isolated AVD after the original rejected an APK signed by a different debug key.
- Next: verify the existing F26 safe-to-spend implementation against its acceptance before deciding whether to make changes.

## E3.19 F26 safe-to-spend freshness and payday verification — 2026-10-05 01:09 MSK

- F26 is complete. Cash planning recalculates its recurrence projection from current posted member transactions during each summary request; it has no stale worker-backed projection. The PostgreSQL acceptance test now writes three repeated salary and subscription occurrences, requests the dashboard immediately, and verifies the next payday horizon, 10% reserve effects, and a charge due exactly on payday is included.
- Existing unit cases cover missed salary, month-end fallback, no profile income, monthly-expense subtraction and per-day values. Web, Android, and Telegram display the same Core response.
- GREEN after the new acceptance: full Core 201 passed, 0 failed, 2 container-gated integration skips. Prior same-turn Web 36/36, Android 26/26, Telegram renderer/worker/client/commands 71/71, and contracts 46 passed/2 skipped remain valid because no client or schema changed.
- F26 closed in the parity registry. Next verify F31 Web and PNG charts against the user's approved scope.

## E3.20 F31 approved chart slice verification — 2026-10-05 01:10 MSK

- Verified the user's approved F31 slice: report category/day charts and monthly-limit usage use only Core values in Web and Android; Telegram PNG uses the same report DTO, caps overrun visualization but keeps exact negative remaining, and falls back to readable text. No price or optional-purchase placeholders are present.
- Reused current GREEN evidence: Core 201/201 with 2 integration-only skips, Web 36/36 and TypeScript, Android emulator 26/26, Python report/digest/worker/commands 71/71, contracts 46 passed/2 skipped. `report_renderer.py` tests specifically cover PNG size/readability, capped overrun, exact remaining, fallback, and DTO validation.
- F31 stays in progress because its remaining price/waste chart data depends on F33/F40. Next: inspect and finish F33 authoritative price pipeline gates.

## E3.27 F38 recurring series — 2026-10-06 03:11 MSK

- F38 is complete. Go now projects recurring income/expense series from at least three occurrences, bounded amount and interval ranges, local calendar dates, three-day expense warnings, separate overdue rows, and non-fabricated empty-history totals. ClickHouse state projection is idempotent and supports retry/dead-letter handling.
- Core exposes authenticated member-scoped API and Web BFF routes using the active profile timezone. Telegram, Web, and Android render localized ranges, due-soon, overdue, next income, and available monthly estimates. F26 cash-planning behavior remains covered by Core regression checks.
- GREEN: Go `go test ./... -count=1` + `go vet ./...`; Core `:services:core:check --no-daemon` and isolated PostgreSQL API/BFF member-scope acceptance; Python presentation/Telegram 106 passed; contracts 54 passed/2 optional skips; Web 48/48 plus production build; Android emulator instrumentation 41/41. APK installed and launched on isolated `emulator-5556`, SHA-256 `B0C275204C68A1F2FA9F02EB6E64F09BB327EA757B3ED3DD7D455B357E99F1C7`. `git diff --check` clean.
- Tagged Kafka/ClickHouse live integration remains NOT_RUN locally: required external endpoints are not configured. The integration test is part of the project and `.github/workflows/contracts.yml` provisions Redpanda and ClickHouse for CI.
- Next: F39 mute/unmute recurring series, without deleting the underlying financial transactions or changing another member's view.

## E3.28 F39 recurring reminder preferences — 2026-10-06 03:25 MSK

- F39 inherits `services/mutelist.py`: a muted recurrence remains in transaction history, leaves the active reminder list, and can be restored. Preferences stay separate by tenant, member, and section.
- Reuse RLS-protected `muted_suggestions`, whose `section` constraint already includes `recurring`; store the stable 32-character series ID. Core rechecks active membership and that the series still exists. Muted rows move to a restore list; active recurring totals use only unmuted series.
- Acceptance spans authenticated Core API/BFF, Telegram actor actions, Web CSRF, Android OIDC, localized restore controls, stale IDs, no transaction deletion, and two-member isolation.
- CI follow-up after F38 push: regular tests and all contracts/Go/Kafka/ClickHouse jobs passed at `7c18051`; the recurring event replay and projection steps both passed. Fixes in `4808dce`, `d7e446b`, and `7c18051` are pushed.
- Local shell lacks Go; GitHub runner verifies Go gates. Existing isolated PostgreSQL test server at `127.0.0.1:55438` supports Core acceptance.
- Next: implement Core overlay and actions against the observed RED test.

## E3.29 F39 acceptance RED — 2026-10-06 03:37 MSK

- Added PostgreSQL API acceptance for an authenticated member's recurring projection, mute, another member's attempted unmute, owner's restore through CSRF-protected BFF, stale-ID rejection, preference-row isolation, and preserved transaction count.
- Test `:services:core:test --tests com.decorix.finance.core.api.TransactionApiPostgresTest.recurringMuteIsMemberScopedRestorableAndKeepsTransactions` reaches the live Core test context and fails because response lacks `mutedSeries`. This is the intended RED.
- First fixture attempt returned 502 because it used Go snake-case fields while the test HTTP fixture serves Core camel-case JSON. Corrected fixture shape; rerun reached the missing contract field. No product code changed.
- Next: implement member-scoped overlay and Core routes, then rerun this acceptance.

## E3.21 F35 shopping cadence suggestions — 2026-10-05 23:39 MSK

- F35 preserves the legacy three-purchase threshold, median cadence and unit-price estimate, 3-day due horizon, and expiry after two overdue intervals. Go ignores duplicate rows within one receipt and separates tenant/member histories. Core exposes authenticated API/BFF and Telegram actor routes; Telegram `/shopping`, Web, and Android show RU/EN suggestions and estimated list cost with an explicit no-inventory statement.
- GREEN: Go `go test ./...` + `go vet ./...`; Core `check` 216 tests, 0 failures, 88 environment/container-gated skips; isolated PostgreSQL 18.6 `TransactionApiPostgresTest` 86/86, including member-scoped Web/BFF and Telegram acceptance; Python Telegram/presentation 87/87; contracts 49 passed/2 optional skips; Web 42/42 + production build; Android emulator instrumentation 29/29, debug package installed (SHA-256 `01BA6456F816407E7C6A5BF0903B458AD2D774B64BD787C775B1E77620655049`); `git diff --check` clean.
- F35 is complete and marked complete in `contracts/parity/feature-parity.yaml`. Next: F36, validate “already bought”, mute/unmute, blocking, and purchase-list copy behavior.

## E3.22 F36 shopping decisions and copy — 2026-10-05 23:45 MSK

- Goal active. Inherited from the approved `PLAN.md` and legacy: keep bought marks separate from transactions; suppress only while mark is newer than the latest receipt and younger than one median interval; persist member-local mutes; preserve confirmed “не брать” decisions with a visible reason; copy only active suggestions.
- Planned ownership: Core/PostgreSQL validates active tenant membership and owns marks/mutes; existing `user_product_decisions.confirmed` remains the blocking source. Telegram actor actions, Web BFF with CSRF, and Android direct API all use the same Core rules.
- Next: write deterministic policy and PostgreSQL/API tests first, observe RED, then implement API, clients, localized hidden-state explanations and clipboard behavior.

## E3.25 F37 personal basket inflation — 2026-10-06 01:48 MSK

- Preserved the legacy 90-day Laspeyres basket, shared `prices.SameProduct` identity, median unit prices, old-paid-amount weights, top rise/fall ordering, and receipt-only disclosure. Each product requires two distinct older receipts and one in-window receipt; fewer than three eligible products returns explicit insufficient history without totals.
- Observed RED for missing Go algorithm/HTTP handler, Java/Core API, Python client/renderer and `/inflation`, Web panel, Android DTO/screen, and contract shape. Fixed one pre-existing dangling `try` in the Python client tests before collection.
- GREEN: Go `go test ./...` and `go vet ./...`; Core `:services:core:check` and isolated PostgreSQL member-scope API/BFF acceptance; Python Telegram/presentation 101 passed; contracts 52 passed/2 optional skips; Web 46/46 and production build; Android clean-AVD instrumentation 36/36. Built and installed the debug APK on isolated Android 14 AVD, launched `com.decorix.finance.debug/.MainActivity`; SHA-256 `8BA2E392D0F2F7FC3198BC604622DC646CAE2CB9E0BA2B7B5CF5C89600BC5E44`. `git diff --check` passes.
- First Android run exposed the new tab pushing existing budget/debt tabs outside the viewport. Moved the price-trend tab after established navigation; the full instrumented suite then passed. The existing user AVD and its data were not used for installation.
- F37 is complete and pushed as `41b14f02dfe28928ccf411e7017d471010046851`. F33's live Kafka/ClickHouse replay remains NOT_RUN because no configured integration endpoint is available; it is not presented as verified. Next: begin F38 recurring-income/expense detection.

## E3.26 F38 recurring series — 2026-10-06 02:02 MSK

- Scope from `PLAN.md` and v1: weekly/monthly expense and income series; at least 3 occurrences; amount spread at most 25% of average; period median 6–8 or 25–35 days; at least 60% of intervals within 25% of the median; warning only for expenses due in the next 3 days; overdue rows never enter upcoming. Core owns authorization; Go owns the recurring analytics calculation.
- Existing Core `RecurringProjectionPolicy` supports F26 safe-to-spend but does not expose the F38 read model or ranges. F38 will keep F26 regression behavior while adding its shared Go implementation and member-scoped view.
- Observed RED: `go test ./recurring -count=1` fails because `Transaction` and `BuildProjection` are not implemented. The first deterministic fixture covers local timezone, weekly/monthly series, amount/interval ranges, next dates, warning/overdue separation, income and no-fake-zero behavior.
- Next: implement the Go policy and its golden contract fixture, then add event projection and authenticated Core/client acceptance.

## E3.42 F10, F31 and F33 acceptance closure — 2026-10-06 07:05 MSK

- F33 tagged integration gate is GREEN on GitHub run `37411323045`, commit `a07f7cb`: Go tests/vet passed; ClickHouse and Redpanda started; Kafka-to-ClickHouse price projection/replay passed.
- F31 now has authoritative price-history charts via F34 for Web and Telegram PNG (only two or more real receipt points), Core-backed optional-spend charts via F31.2, existing category/day/limit charts, and text fallback. F31.2 local Web/Python/contracts/Android gates and both GitHub workflows `37411323031` and `37411323045` passed.
- F10 storage gate is GREEN on GitHub run `37406496544`, commit `68253b7`: authenticated SeaweedFS S3 operations and real ClamAV malware scanning passed.
- Contracts regression after parity updates: `pytest tools/contracts/test_contracts.py -q -p no:cacheprovider` — 42 passed. No product code changed in this evidence update.
- F10, F31 and F33 are complete. Next: F41, preserve model-only product hypotheses as reviewable suggestions and require explicit human decision before any product block/allow behavior.

## E4.61 F42.1 deterministic recalculation preview policy — 2026-10-06 23:08 MSK

- Legacy `services/advice.py` recalculates only blank/model/default sources, leaves human/rule/unknown sources untouched, applies current name-based rules, and never changes receipt sums. Ported that eligibility and preview-only boundary into `ReceiptRecalculationPolicy`.
- Observed RED: targeted Gradle test first could not start because Java was absent from PATH. Existing cached JDK 17 then produced the intended compile failure because `ReceiptRecalculationPolicy` did not exist. No JDK was installed or system PATH changed.
- GREEN: targeted `:services:core:test --tests com.decorix.finance.core.domain.ReceiptRecalculationPolicyTest` passed. Full `:services:core:check --no-daemon` passed; latest test report shows 238 tests, 0 failures, 100 skipped. Skipped tests remain environmental/integration gates and are not counted as exercised.
- The policy reports old/new verdict, advice and source with exact stored line amount; it skips human/rule/unknown sources, retains uncovered model opinions, and is idempotent after an item becomes rule-backed. Persistence/API does not exist yet, so F42 remains IN PROGRESS.
- M2 environment audit is complete but runtime remains BLOCKED: durable PostgreSQL and Keycloak are absent; current PostgreSQL listener is temporary test infrastructure; Tailscale Funnel is unconfigured. Public ingress remains closed until auth and durable storage work.
- Next: commit this independently tested policy slice, then write PostgreSQL acceptance for explicit preview/apply, audit snapshots, stale-version handling, and unchanged receipt totals before implementation.

## E4.62 F42.2 durable preview and apply API — 2026-10-06 23:52 MSK

- Observed RED: PostgreSQL acceptance returned 404 for the absent preview route. Added acceptance for preview-only behavior, apply/idempotence, unchanged totals, durable audit, and stale item rejection with no partial write.
- Added V35 RLS-protected recalculation runs and snapshots. Core API scopes run to active tenant member, restricts viewers, stores exact before/after line state, rejects stale versions/state with 412, changes only receipt review fields, records `receipt.verdicts_recalculated`, and makes repeated apply idempotent.
- Historical receipt line sum may be null; preview preserves null without inventing a value. OpenAPI declares API and CSRF-protected BFF routes.
- GREEN: targeted PostgreSQL recalculation tests passed; full `:services:core:check --no-daemon` passed, 241 tests, 0 failures, 2 skipped. `git diff --check` passed. Contract pytest NOT_RUN: local Python environment has no pytest/PyYAML/jsonschema; CI contract gate remains required.
- F42.1 `6f80afc` and F42.2 `d41888c` are pushed. GitHub Actions returned no run for `d41888c`; remote gates remain unverified. M2 runtime remains BLOCKED; test PostgreSQL is temporary Codex infrastructure and public ingress remains closed.
- Next: commit and push F42.3; then implement Telegram controls and remaining F42 batch/change-history behavior. Preserve unrelated untracked files.


## E4.63 F42.3 Web preview and explicit apply — 2026-10-06 23:39 MSK

- Observed RED: focused Vitest first failed because `ReceiptRecalculationPanel` did not exist. Added stale-preview failure case, observed an old apply action remain after a failed refresh, then cleared the stored preview before retry.
- Added RU/EN Web panel in receipts. Writers see explicit preview, old/new verdict and reason, item amount, checked/update/changed counts, and separate apply action. Viewers receive no controls. No apply request runs before the user clicks apply.
- Apply success invalidates report and summary queries. Stale/apply errors clear preview so user can request a current one. UI states receipt and transaction totals stay unchanged.
- GREEN: focused Vitest 3/3; full `pnpm --dir apps/web test` 57/57; `pnpm --dir apps/web build` passed (`tsc -b` and Vite production build).
- F42.3 `81267de` and F42.2 `d41888c` are pushed; GitHub has no run for `81267de`. M2 runtime remains BLOCKED; no server restart or deployment is possible because no persistent Core runtime exists.
- Next: implement Telegram controls and remaining F42 batch/history behavior. Preserve unrelated untracked files.

## E4.64 F42.4 Go-calculated impact — 2026-10-07 00:00 MSK

- Observed RED: Go tests failed to compile because the before/after impact builder and authenticated handler were absent. Core client tests failed to compile because `AdviceRecalculationImpactClient` was absent. Web tests failed to find both the available delta and the explicit missing-data state.
- Go now calls the existing exact optional-spend report algorithm for matched before/after windows, rejects changes to financial receipt facts, hashes both inputs, and exposes an authenticated internal route. Tests cover exact delta, incomplete amounts, changed facts, auth and successful route.
- Core sends only the requesting member's recent 90-day receipt facts, calls Go outside its DB transaction, validates the response, attaches profile currency, and saves the report with V36 JSONB. Preview and Apply (including idempotent retry) return the stored report. Existing versions remain nullable for old runs.
- Web shows before/after spend, signed delta and profile currency; when Go cannot calculate it, Web shows the reason and does not fabricate zero. Apply remains a separate explicit action.
- GREEN: full Go `test ./...` and `vet ./...`; `:services:core:check` with PostgreSQL (243 tests, 0 failures, 2 skipped); all Web tests 59/59, `tsc --noEmit`, Vite production build; `tools/contracts/test_contracts.py` 46/46; `git diff --check`.
- No remote CI run is available yet; F42.4 is not committed. M2 runtime stays BLOCKED; no persistent server or public HTTPS deploy was attempted.
- Next: implement accessible recalculation run history and bounded batch behavior.

## E4.65 F42.4 commit and remote status — 2026-10-07 00:02 MSK

- Committed F42.4 as `659b537` (`feat(F42.4): persist recalculation impact`) and pushed `feat/saas-rewrite` to `origin` successfully.
- `gh run list --commit 659b537` returned an empty array. GitHub CI is not verified; do not report remote GREEN.
- Worktree has only existing untracked user data (`.android-user/`, `.freebuff/`, `.pnpm-store/`, `CODEX_AUTONOMOUS.md`, `apps/android/current-screen.png`, `tmp/`); these remain untouched.
- Next: Telegram controls and remaining F42 batch/change-history parity.

## E4.66 F42.5 Telegram preview and explicit apply — 2026-10-07 00:15 MSK

- Observed RED: Python client had no recalculation API methods; `/recalculate` was unregistered; Core internal preview endpoint returned 404. Added tests before implementation for authenticated client calls, valid response handling, preview-before-apply, viewer denial and Core linked-actor scope.
- Added private `/recalculate`: summary includes checked/prepared/changed counts, old/new verdict and reason, amounts and Go impact. It displays a separate Apply button only when there are candidate updates. Callback revision and tenant must match current FSM state; stale callbacks are rejected, and Core independently rejects stale receipt versions. Successful apply removes the button.
- Core routes require the Telegram service credential and `receipt.write.own`; actor identity/tenant comes only from the signed opaque context. ReceiptRecalculationService is called after context resolution transaction ends; existing preview calls Go outside its own read transaction.
- GREEN: focused RED→GREEN client/command tests 4/4; PostgreSQL route test includes missing service token, owner preview/apply and viewer 403; full Core check 244 tests, 0 failures, 2 skipped; Telegram gateway 95/95; contracts 47/47; `git diff --check`.
- Next: implement accessible run history and bounded batch execution from F42's job/batch target.

## E4.67 F42.5 commit and remote status — 2026-10-07 00:16 MSK

- Committed Telegram flow as `1a484bb` (`feat(F42.5): add Telegram recalculation flow`) and pushed `feat/saas-rewrite` successfully.
- `gh run list --commit 1a484bb` returned `[]`; GitHub CI is unverified.
- Worktree still contains only unrelated untracked user data; these files remain untouched.
- Next: inspect the plan's job/batch target and add member-scoped run history with meaningful pagination and bounded batch behavior.

## E4.68 F42.6 member-scoped recalculation history — 2026-10-07 00:25 MSK

- Extended F42 in `PLAN.md` and parity acceptance: retained history is owner-scoped, run pages default to 20, change pages cap at 100, and recalculation input remains capped at 50,000 items. This records concrete bounds for the existing explicit durable preview/apply flow; it does not change architecture or financial behavior.
- Added Core API/BFF endpoints to list newest runs with stable `(created_at, id)` cursor pagination and read stored old/new snapshots with UUID keyset pages. Queries require active membership and scope runs to the actor's `owner_user_id`. Invalid cursors/limits return 400; foreign/missing runs return 404.
- Web shows saved run state/counts/impact and stored before/after decisions. It loads run pages and change pages on demand and invalidates history after preview/apply.
- TDD: PostgreSQL API tests were added before service/controller implementation. First Gradle attempt showed RED at compilation because the cursor record was missing; added the record, then Core compilation/check passed. This was a compile RED, not an observed route-behavior RED.
- Initial run skipped PostgreSQL tests because test connection variables were unset. Reused the pre-existing isolated `finance_test_codex_20261004` database on local PostgreSQL 18.6 at port 55432; did not alter the legacy `finance_test` database. Focused recalculation preview/history tests passed against PostgreSQL. Full `:services:core:check` then passed: 245 tests, 0 failures, 2 skips; PostgreSQL `TransactionApiPostgresTest` passed 102/102 with 0 skips.
- Verification: Web tests 60/60 and production build pass; contract tests 47/47 pass; full Core check and live PostgreSQL acceptance pass; `git diff --check` passes. The first compile attempt caught missing `HistoryCursor`; fixed before the passing API run. Tests were written before endpoint implementation, but no feature-level RED was observed because first executable run hit that compile error. Existing untracked user files remain untouched.
- No commit yet. GitHub CI status not queried for this change. Next: commit/push F42.6 and check whether GitHub starts CI. M2 public HTTPS and alltime runtime remain separate deployment gates.

## E4.69 F42.6 commit and acceptance — 2026-10-07 00:32 MSK

- Commit `8b741c0 feat(F42.6): add recalculation history` pushed to `origin/feat/saas-rewrite`.
- Full verification is GREEN: Core 245 tests, 0 failures, 2 skips; PostgreSQL 18.6 acceptance 102/102; Web 60/60 and production build; contracts 47/47; `git diff --check`.
- F42 is complete at local feature acceptance and parity now records owner-scoped run history, stored changes, and bounded pages. `gh run list --commit 8b741c0` returned `[]`; remote CI did not start. Public HTTPS/alltime runtime are still not deployed.
- Next plan goal is F43. First inspect acceptance and existing advice analytics; write a failing focused test before implementation.

## E4.70 F43 design and Go calculation — 2026-10-07

- User confirmed the F43 design with “Делай”. Saved the approved feature spec at `.agent/specs/F43-advice-analytics.md` and an implementation plan at `docs/superpowers/plans/2026-10-07-f43-advice-analytics.md`.
- F43.1 adds Go calculation for the 90-day theoretical ceiling, four member-local seven-day spend-share windows, purchase cadence after an explicitly shown advice point, and a separate F42 recalculation annotation. Money uses `big.Rat` decimal strings; no financial rows are written.
- Tests cover one-vs-repeat purchases, allowed products, denominator-only unknown verdicts, missing amounts, two-window minimum, exact 5-point trend boundary, 21-day pending, two prior purchases, exact 20% cadence boundary, zero post-advice purchases, member timezone, F42 independence, malformed input, strict internal bearer auth, and a versioned golden fixture.
- The first `go test` could not start because Go was absent from PATH. Downloaded Go 1.27.1 to the temporary directory and used it per-command; no system PATH or installed settings changed. First feature run showed compile RED for missing F43 symbols. A later behavior test exposed missing recalculation marking when only one trend window had data; fixed and covered it.
- GREEN: `go test ./...` and `go vet ./...` from `services/analytics-go`; contract suite via `.venv\Scripts\python.exe -m pytest tools/contracts/test_contracts.py -q -p no:cacheprovider` — 48 passed; `git diff --check` passed.
- F43.1 committed and pushed as `08ea1af` (`feat(F43.1): add advice analytics calculation`). `gh run list --commit 08ea1af` returned `[]`; remote GitHub CI did not start and is unverified. Next: Core durable member snapshot/job/query API with PostgreSQL acceptance tests before implementation.

## E4.71 F43.2 Core durable jobs and member API — 2026-10-07

- Observed PostgreSQL RED first: all new F43 public routes returned 404. Added V37 member watermark/job tables and RLS; V38 adds a completed-lease token so exact duplicate worker deliveries are acknowledged without reopening a job.
- Core snapshots only the active member's confirmed posted expense receipt positions, allowed-product decisions, profile timezone/income, personal total limit and applied F42 annotations. A canonical member input hash reuses unchanged jobs; changed inputs advance the watermark and stale older work. The 50,001-row bound returns `413 too_many_items` before persisting a partial snapshot.
- Added public and same-origin BFF latest/enqueue/poll routes, explicit viewer read-only behavior, and internal claim/result routes protected by `X-Analytics-Service-Token`. Claims use `FOR UPDATE SKIP LOCKED`, five bounded attempts, expiring leases and delayed retry; result writes compare lease, algorithm, watermark and current member watermark.
- PostgreSQL integration covers member isolation, viewer denial, unchanged-input idempotency, changed-input watermark/stale reads, no-truncation overflow, service-token enforcement, successful result and duplicate delivery, retry token rotation/late result rejection, stale completion after new inputs, and BFF session/CSRF.
- GREEN: focused F43 PostgreSQL tests pass; full `:services:core:check` passes with PostgreSQL enabled; `pytest tools/contracts/test_core_migration.py tools/contracts/test_contracts.py -q -p no:cacheprovider` — 64 passed, 2 existing optional DB skips; `git diff --check` passes. GitHub CI status not yet checked for this commit.
- Committed and pushed F43.2 as `07cb779 feat(F43.2): add durable advice analytics jobs`. `gh run list --commit 07cb779` returned `[]`; GitHub CI did not start.
- Next: commit F43.3, then finish the Web report/status panel and parity before F43 acceptance.

## E4.72 F43.3 Go worker lifecycle — 2026-10-07

- Added strict bounded Core HTTP client, service-token claim/result calls, a single-instance cancellable worker, and opt-in integration in the existing `analytics-api`. Worker mode validates its Core URL, service token and poll interval; disabled mode preserves current startup behavior.
- Tests cover no work, calculation/result flow, invalid input delivery for Core retry, transient Core errors, cancellation during idle poll, completion failure, serialized processing, strict claim decoding, HTTP 204/5xx handling and startup configuration.
- GREEN: `go test ./... -count=1` and `go vet ./...` from `services/analytics-go`; focused Go worker/client/config tests pass. Next: commit F43.3 and implement Task 4 Web report/status polling.

## E4.73 F43.4 Web report and F43 acceptance — 2026-10-07 01:44 MSK

- Added RU/EN F43 panel to Web reports with watermark/status, pending/processing polling, explicit enqueue/retry/recalculation, partial/unavailable reasons, theoretical 30-day ceiling, four-week trend, post-advice cadence, and separate F42 annotation. Viewers can read the report but cannot enqueue jobs; no automatic calculation starts.
- RED/GREEN: focused panel suite first failed on missing component, then a new viewer permission test failed because the enqueue button was visible. Both gaps were fixed. Focused suite 7/7; full Web suite 67/67; TypeScript and production Vite build pass.
- Final gates: Go `test ./... -count=1` and `go vet ./...` pass; contract/migration tests 64 passed, 2 existing optional DB skips; Core `:services:core:check` reports BUILD SUCCESSFUL (tasks up to date; PostgreSQL F43 acceptance had passed in E4.71); `git diff --check` passes.
- Telegram `/report` was inspected: it accepts period/scope and returns the legacy finance report DTO, with no route for the stored F43 job/report DTO. F43 stays in Web; no duplicate Telegram calculation was added. Parity now records this intentional difference.
- F43.4 and plan/parity updates committed as `a911e27` (`feat(F43.4): expose advice analytics report`) and pushed to `origin/feat/saas-rewrite`. `gh run list --commit a911e27` returned `[]`; remote CI did not start and is unverified.
- F43 feature work is complete. This does not complete personal MVP operations: no persistent server, public HTTPS, or Android emulator-installed MVP delivery was verified here. Continue with the next V1 feature goal F44 using test-first gates, while keeping MVP deployment gates visible in the global plan.

## E4.74 F44.1 deterministic candidates — 2026-10-07 02:00 MSK

- Created `.agent/specs/F44-goal-candidates.md` and an execution plan splitting F44 into Go candidates (F44.1), Core persistence/API (F44.2), and Web UI (F44.3). F45 lifecycle/history and F46 outcome delivery remain separate.
- Baseline `go test ./advice -count=1` passed before changes. New tests first observed compile RED for missing `F44Request`, `BuildF44Candidates`, and `NewF44Handler`; an early fixture then caught a missing sweets/snacks category mapping and was corrected to the verified legacy behavior.
- F44.1 adds decimal-based cadence and monthly spending candidates, count/sum targets, legacy category stems, allowed/guess handling, group count-only suggestions, meaningful minimum savings, stable sorted limits, combined 50,000-input bound, strict payload decoding and service-bearer endpoint in the existing `analytics-api`. OpenAPI and a golden fixture cover the interface.
- GREEN: Go `test ./... -count=1` and `go vet ./...`; full contract suite 51/51; `git diff --check`. F44.1 committed as `959c750` (`feat(F44.1): calculate goal candidates`). Next: F44.2 Core PostgreSQL acceptance tests before implementation.

## E4.75 F44.1 nullable receipt amount correction — 2026-10-07 02:10 MSK

- Plan correction, reason: confirmed receipt line sums are nullable in the actual Core schema. Keeping F44.1's previous non-null DTO would either drop real purchase cadence or require fabricated zero values. Updated the contract so count candidates retain cadence with null monetary estimates; sum candidates report `missing_amounts` with null spend.
- Tests first observed compile RED after making amount fields nullable, then Go candidate and Core client tests passed. Core rejects null monetary values for sum candidates and accepts paired null values for count candidates only.
- GREEN: Go `test ./... -count=1`, `go vet ./...`, Core `GoalCandidatesClientTest`, contracts 51/51, and `git diff --check`.
- Committed/pushed as `d8591d3` (`fix(F44.1): preserve unknown receipt amounts`). `gh run list --commit d8591d3` returned `[]`; GitHub CI did not start.

## E4.76 F44.2 member goal persistence and APIs — 2026-10-07 02:23 MSK

- Added PostgreSQL acceptance for member-only purchase facts, writer/viewer access, one active goal, fixed 30-day term, immutable accepted target/unit after preference change, cancellation, stale-watermark rejection, BFF session/CSRF, and count-goal acceptance when receipt line sums are unknown.
- Initial test run observed compile RED for missing F44 Core API types. Migration run observed PostgreSQL SQLSTATE `42P17`: `timestamptz + interval` was rejected as a generated-column expression because it is not immutable. Minimal plan correction: persist explicit `ends_at = accepted_at + 30 days` and include it in immutable-term enforcement.
- V39 adds RLS-protected goal storage and member count/sum preference. Core rebuilds the member-only snapshot and watermark before acceptance, stores immutable terms and audit/outbox events, and lets a preference change affect future proposals without rewriting the active goal. BFF writes use normal session CSRF protection.
- F44.2 GREEN: focused PostgreSQL goal/API tests pass on isolated `finance_test_codex_20261004`; full `:services:core:check` passes; contract/migration suite 65 passed, 2 optional database skips; Go suite/vet and `git diff --check` pass.
- Committed/pushed as `88f868b` (`feat(F44.2): add member goal persistence and API`). `gh run list --commit 88f868b` returned `[]`; GitHub CI did not start. Next: F44.3 Web tests first, then RU/EN candidate and active-goal UI.

## E4.77 F44 completion and F45 start — 2026-10-07 02:39 MSK

- F44.2 payload-bound follow-up: test first observed compile RED for missing overflow-safe combined decision/purchase bound; unit coverage verifies exact 50,000, over-limit, negative, and integer-max inputs. Full Core `check` passed; committed/pushed `e4d0d61`.
- F44.3 GREEN and commit `2e5dce4`: RU/EN Web proposal/active-goal surface; 72/72 Web tests, production TypeScript/Vite build, full Core/PostgreSQL regression and `git diff --check` passed. F44 is complete locally and pushed.
- `gh run list --commit e4d0d61` and `--commit 2e5dce4` returned no runs; remote CI did not start and is not reported as green.
- Legacy F45 semantics inventoried in `services/goals.py`: 30-day fixed window; progress is bounded through the current time; category goals use the accepted member list; completion archives one summary, cancelled goals are not completed outcomes, history keeps newest first up to 24 and presents five; finished goals expose next candidates. F45 remains unimplemented in the rewrite.
- Next: define F45.1 contract around per-member confirmed facts, progress/history persistence, exactly-once completion, cancellation exclusion, and candidate availability; write tests and observe RED before code.

## E4.78 F45.1 Go progress calculation — 2026-10-07 02:48 MSK

- Test-first RED: new tests failed to compile because F45 request, progress DTO, calculator and handler were missing. A second RED showed category proposals did not expose member keys.
- Added strict internal Go endpoint `/internal/v1/analytics/goals/progress`; exact decimal count/sum calculation, immutable 30-day validation, member-key group filtering, null unknown money, future-fact/as-of boundaries, service-token auth and malformed/oversized input rejection.
- F44 group correction now snapshots sorted eligible product keys and excludes products marked allowed or unconfirmed model-only. Needed so later category-rule changes cannot rewrite the accepted goal.
- GREEN: Go `test ./... -count=1`, `go vet ./...`; all contract/migration tests 66 passed, 2 optional DB skips; focused F44/F45 contract tests passed; `gofmt -d` and `git diff --check` passed.
- Committed/pushed `37f84ba` (`feat(F45.1): calculate goal progress`). GitHub returned no workflow run for the commit; CI is unverified.
- Next: add Core/PostgreSQL RED tests for group membership persistence, owner/viewer boundaries, progress from member receipts, cancel exclusion, idempotent completion and history retention, then implement Core integration.

## E4.79 F45.2 durable goal progress and lifecycle — 2026-10-07

- Core now sends the active member's confirmed posted expense receipt facts to the strict Go progress client. Product scope matches the accepted key; group scope uses the immutable member-key snapshot captured at acceptance.
- V40 adds RLS-protected group membership and durable outcome rows. Expired goals transition once with audit/outbox in the same transaction; cancelled goals never become outcomes; completed outcomes retain the newest 24 while legacy-origin rows are not pruned. Overview returns active progress and history.
- TDD caught two cases: the initial viewer test fixture targeted the owner's private goal, so it was corrected to use an expired goal owned by the viewer; then the corrected test failed against unconditional completion because viewer reads attempted a write. Current behavior lets viewers read expired progress and leaves the transition to the writer.
- GREEN: full `:services:core:check` against isolated PostgreSQL; Go `test ./... -count=1` and `go vet ./...`; contracts/migration tests 68 passed, 2 optional skips; `git diff --check`.
- Commit `afcde2d` (`feat(F45.2): persist goal progress outcomes`). The new table/read path preserves all legacy-origin rows, but the global J migration importer is not implemented; this remains an open F45 acceptance item and is recorded in the execution plan.
- Next: commit the Web F45.3 slice, then implement the missing J migration ingestion before marking F45 complete.

## E4.80 F45.3 Web progress and history — 2026-10-07

- Web now shows active count/spend progress, clearly marks unknown sums, displays completed outcomes, and exposes next candidates after closure. Viewer controls remain read-only. Confirming a receipt invalidates the goal query so progress refreshes from the new confirmed facts.
- TDD: receipt confirmation/cache test first failed because `goals` was not invalidated; added the invalidation and the test passed. Added component coverage for unknown sums and outcomes, then verified candidates return alongside completed history.
- GREEN: Web tests 75/75; TypeScript/Vite production build; Core `:services:core:check` with isolated PostgreSQL; Go tests/vet; contracts/migrations 68 passed, 2 optional skips; `git diff --check`.
- Commit `85c30d1` (`feat(F45.3): show goal progress and history`). F45.3 presentation slice is complete. Overall F45 remains open for the legacy-history ingestion path assigned to global J; this is not hidden by the completed UI work.
- Next: push verified F45.2/F45.3 commits and move to the J migration ingestion acceptance; after that close F45, then continue F46 and remaining V1 MVP/deployment gates.

- Remote check: commits fcde2d/85c30d1/39a2b8 are pushed; gh run list --commit e39a2b8 returned no runs, so GitHub CI did not start. The migration mapping now explicitly requires idempotent import of every dvice:goal_history row into goal_outcomes(origin='legacy').

## E4.81 F46 approved delivery design and execution plan — 2026-10-07 05:01 MSK

- User approved the F46 design: send outcome inside weekly Telegram digest, mark only after successful Telegram acknowledgement. Design is `docs/superpowers/specs/2026-10-07-f46-goal-outcome-delivery-design.md` (`e236920`). Clarified existing queued weekly-intent behavior; disabled schedules create no future intents.
- Wrote and self-reviewed `docs/superpowers/plans/2026-10-07-f46-goal-outcome-delivery.md`. Plan covers PostgreSQL association/claim, acknowledgement lifecycle, Telegram validation/rendering, OpenAPI, RED/GREEN commands, and final regression.
- Ruling: begin inline implementation without another plan-review prompt — user approved F46 design and said “Продолжай”; user-owned `CODEX_AUTONOMOUS.md` says not to ask before moving to the next goal, and user already prescribed strict TDD. Cost if wrong: task decomposition may need a later correction.
- No F46 product code changed yet. Existing user-owned untracked files remain untouched. Branch `feat/saas-rewrite`; current HEAD before plan commit `e236920`.
- Next: write Task 1 PostgreSQL claim tests, run against isolated DB, and record observed RED.

## E4.82 F46.1 weekly claim association — 2026-10-07

- Test-first RED: two PostgreSQL acceptance tests failed because `goalOutcome` was absent. Migration test failed because V41 was absent.
- Added V41 link with one-outcome-per-intent unique index and pending-completed lookup index. Weekly claims attach oldest unannounced completed outcome transactionally; retries return same attached snapshot; daily claims remain null.
- GREEN: focused Core PostgreSQL tests 2/2 pass against isolated `finance_test_codex_20261004`; migration checks 16 passed, 2 optional skips; `git diff --check` passed.
- Task 1 commit/push: `21576a1` (`feat(F46.1): attach outcomes to weekly claims`). Task 2 PostgreSQL lifecycle tests observed RED, then passed 4/4 focused methods after transactional delivered/retry/terminal/expired-lease transitions. Next: commit Task 2, then add Telegram validation/rendering tests and observe RED.

## E4.83 F46.2 delivery acknowledgement lifecycle — 2026-10-07

- Test-first RED: delivered outcome did not mark announcement, terminal failures retained association, and exhausted lease hid outcome from next intent. Retry retention already passed.
- Core now marks delivered outcome once and clears link; preserves link on retry; releases it on no_data, permanent/exhausted failures. Expired exhausted leases release linked outcomes in the claim transaction.
- GREEN: six focused Core notification PostgreSQL tests passed, covering claim and acknowledgement lifecycle.
- Task 2 commit/push: `747e792` (`feat(F46.2): mark goal outcome delivery`). Task 3 RED covered renderer signature, client payload validation, and empty-report worker behavior. GREEN: full Telegram gateway suite 101 passed. Task 3 commit/push: `f7ef0cd` (`feat(F46.3): render outcomes in weekly digest`).

## E4.84 F46.3 weekly digest rendering — 2026-10-07

- Test-first RED: renderer lacked outcome argument; client accepted malformed/daily payloads; worker suppressed outcome-only digest.
- Core client validates UUID, unit, counts, money, nullable Boolean, completion timestamp, and weekly-only rule. Digest renders RU/EN count and sum outcomes; unknown amounts stay explicit. Worker sends outcome-only digest and does not record local success if Core acknowledgement is lost.
- GREEN: focused three-file suite 53 passed; full Telegram gateway suite 101 passed.
- Task 3 commit/push: `f7ef0cd` (`feat(F46.3): render outcomes in weekly digest`). Task 4 OpenAPI test observed RED on missing schema, then passed. Final GREEN: Core check; gateway 101; contracts/migrations 70 passed, 2 optional skips; Go tests/vet; Web 75 tests/build; diff check. F46 complete pending final delivery commit.

## E4.85 F46 contract and full regression — 2026-10-07

- OpenAPI RED: `NotificationDeliveryClaim` did not exist. Added claim, outcome, acknowledgement schemas and internal routes with weekly-only eligibility, delivered marker semantics, and lost-ack duplicate risk.
- Full GREEN: Core `:services:core:check`; Telegram gateway 101 passed; contracts/migrations 70 passed, 2 optional skips; Go `test ./... -count=1` and `go vet ./...`; Web 75 passed and production build; `git diff --check`.
- Final commit/push: `9455567` (`feat(F46): deliver goal outcome in weekly digest`). `gh run list --commit 9455567` returned no runs; remote CI did not start. F46 complete.

## E4.92 F53 Web panel parity — 2026-10-07

- Test-first RED: new member panel tests failed because the component was missing; App tests failed because member navigation, active route, and shared refresh controls were absent.
- Added the authorized members page, localized role labels, own-profile link, manager-filtered transaction link, and scope-safe member transaction query. Restored a global refresh action and F5 query invalidation, route-aware navigation, and responsive member cards.
- A full regression caught duplicate accessible names between the global refresh and existing error retry buttons; clarified the global action label. Final GREEN: Web 13 files / 87 tests, TypeScript/Vite production build, `git diff --check`.
- F53 plan: `docs/superpowers/plans/2026-10-07-f53-web-panel-parity.md`. Commit/push pending.

## E4.95 F55 local model selection and cloud boundaries — 2026-10-07 08:23 MSK

- TDD RED: local empty inventory still returned 200; configured embedding could be selected; Ollama connection failures were retried each request. New tests captured these failures before implementation. A cross-surface RED then caught remote inventory enumeration in private health, rejected its new diagnostic in Core, and missing RU/EN explanation in Web.
- Implemented bounded 30-second local inventory cache with a 5-second outage cache, configured/exact preference fallback, embedding exclusion, and no local text provider when inventory/configuration is unusable. Text AI now returns safe 503; Tesseract OCR and manual finance paths remain separate. Remote inference remains explicit cloud opt-in; neither provider selection nor health queries remote model inventory.
- Health reports remote model installation as unchecked. Added its sanitized diagnostic to Core's allowlist and RU/EN Web messages. Health resolves local installed preferences consistently and reports invalid local model config without exposing settings.
- Operator guide and `.env.example` now document Ollama CLI model lifecycle, exact-tag fallback, cloud key storage, no remote inventory, and no-Ollama behavior. F54/F55 parity entries include tests and evidence.
- GREEN: Python/contracts/migration/Intelligence/Telegram 296 passed, 1 optional skip; Core complete check passed; Go test and vet passed on checksum-verified portable runtime; Web 92 passed and production build passed; `git diff --check` passed. No billable cloud inference ran.
- F55 feature commit/push: `5df8b06` (`feat(F55): resolve safe local AI models`). Initial Core/PostgreSQL Python contract test scanned internal events; follow-up `0edc20b` scopes validation to public event types. Targeted isolated PostgreSQL test and DB-backed contracts (79/79) pass. All rerun workflows passed: Core/PostgreSQL `37575696661`, repository tests `37575696676`, Python/contracts/Go `37575696734`; receipt storage integration passed on `5df8b06`. F55 is complete.

## E4.96 F56 receipt evaluation analysis — 2026-10-07 08:37 MSK

- F55 progress service commit `020bf11` is pushed; its CI/product acceptance remains recorded above.
- Read `PLAN.md` F56, `.agent/PROGRESS.md`, existing receipt inventory, sample loader, evaluation docs, and workflow. No owner receipt photo or `receipt_samples.json` was opened.
- Current CLI implementation lives at root; F56 assigns it to `tools/evaluation`. Preserve root command as shim. Existing workflow has no artifact upload and `.gitignore` excludes owner samples and `data/`.
- `--json` baseline mixes JSON with human summary; empty inventory emits prose. Plan makes strict JSON-only stdout an explicit acceptance because flag promises machine output.
- Plan: `docs/superpowers/plans/2026-10-07-f56-receipt-evaluation.md`. Next: write synthetic/temp-file tests and observe RED before implementation.

## E4.97 F56 receipt evaluation RED — 2026-10-07

- Added synthetic-only contracts under `tools/evaluation/tests/test_receipt_inventory.py`; no owner photos or sample manifest were opened.
- Observed RED: 4 failed, 4 passed. Failures: module CLI missing; JSON mode appends human summary; empty JSON emits prose; CI lacks a private-sample tracking guard and module smoke.
- Existing behavior checks passed for discovery ordering/deduplication, Vision + strict exit, root CLI availability, and ignored/untracked private sample paths.
- Next: move implementation behind module CLI, keep root compatibility shim, make `--json` stdout strict JSON, document command, add CI privacy guard and empty-inventory smoke.

## E4.98 F56 implementation and local regression — 2026-10-07 08:44 MSK

- Moved the read-only inventory implementation to `tools/evaluation/receipt_inventory.py`; root command is a compatibility wrapper. `--json` now emits only JSON, including `[]`; optional debug diagnostics use stderr. Ordering, per-photo failure isolation, flags, and strict exit policy remain.
- Added synthetic contracts for exception isolation, strict empty inventory, JSON/debug stream separation, CLI help, ordering/deduplication, Vision selection, wrapper, and privacy workflow. Focused result: 11 passed. Legacy synthetic receipt regression passed with sample loading explicitly disabled; no private manifest/photos were touched by that run.
- Updated evaluation docs and Actions: tracked-private-path guard, empty JSON module smoke, no artifact upload.
- `git diff --check` passed. Remote workflow and commit pending.
- During an earlier default CLI smoke, one ignored local receipt photo was accidentally scanned; no files were modified/copied. Future local invocations use synthetic fixtures only.

## E4.99 F56 CI acceptance — 2026-10-07 08:49 MSK

- Pushed `ab4a38d8095c6ed38077bcdeff3c119734155975` to `feat/saas-rewrite`.
- GitHub Actions run `37577879007` is GREEN (4m22s): privacy guard, dependency setup, smoke, handlers, receipt tests, and new empty-inventory JSON CLI step all passed.
- F56 complete. Next plan item: F57 Russian formatting, Telegram escaping, font portability, and text fallback.

## E4.100 F57 presentation parity — 2026-10-07 09:31 MSK

- Read `PLAN.md` F57 and `docs/specs/CONTINUATION.md`; detailed plan and initial RED evidence: `docs/superpowers/plans/2026-10-07-f57-presentation.md`.
- Python: exact decimal formatting, RU/EN report PNG/text and digest, platform sans-font resolution, bounded long labels, text fallback, Telegram language selection and captions. Web: shared exact string money/status formatter, exact `BigInt` subtraction for displayed balances, and panel adoption. Android: exact localized money, semantic-code mapping on dashboard/report/budget/alerts; Core DTO codes unchanged. Contracts pin stable semantic status enums.
- RED: Python 5 failed/2 passed; Web formatter module missing; Web decimal arithmetic helper missing; Android 8.1 UI regression first showed five stale assertions, then one outdated RU top-item expectation. Updated those assertions to the approved locale behavior.
- GREEN: Python/contracts/report/Telegram command suite 199 passed. Web 95 tests passed and production build passed. Android Gradle unit suite 4 passed from a temporary ASCII junction; `assembleDebug assembleDebugAndroidTest` passed. Isolated Android 8.1/API 27 instrumentation: `OK (50 tests)`; debug APK installed and launched without fatal crash. APK SHA-256: `5D6D6F12A62697C1A693BFC6F328B98330931002E097307A168A2DA69D285E97`.
- Portable Temurin 17 and API 27 AVD were created under `%TEMP%`; no system Java/PATH changes or user AVD changes. No private receipts or personal finance data were used.
- Final `git diff --check`, F57 commit/push and GitHub workflow inspection remain. Then begin F58 configuration/secrets/docs/licence gates.

## E4.101 F09 Android server-backed filters — 2026-10-08 10:20 MSK

- Split work across Android UI, Core/API, acceptance tests, and coordinator/review. The Core/API agent also handled the date-validation fix; no concurrent edits were made to the same files.
- Android history now sends search, type, ISO date range, and member filters to Core; uses authenticated member names and role-scoped options; appends results using the opaque cursor; and retains filters on refresh. Core groups expense/debt-payment and income/refund, and includes member display name in parameterized search while preserving tenant and role scope.
- TDD RED/GREEN: Compose tests first failed on absent filters/selection/empty states; Postgres acceptance first failed on grouped-type and member-name search; reversed-date runtime RED showed stale Compose state allowed an invalid callback. Fix validates current date fields synchronously and blocks malformed/reversed filters with localized errors.
- GREEN: API 27 isolated Android instrumentation 64/64, 0 skipped; Core `:services:core:check` 290 tests, 2 environment-gated skips, 0 failures; PostgreSQL `TransactionApiPostgresTest` 136/136. Seaweed S3 and ClamAV integration tests were skipped because their services/configuration are unavailable. Isolated AVD `emulator-5558`; user AVDs untouched.
- F09 Android remains partial: transaction edit, delete/void parity, and live authenticated Android-to-Core E2E are outstanding. F59 remains in progress; next continue with the next independently testable Android parity slice.
- Commit/push: `32baed6` (`feat(F59): add Android server-side transaction filters`); no GitHub Actions run was created for the revision.

## E4.102 F09 Android transaction edit — 2026-10-08 10:44 MSK

- Test-first RED: owner edit action missing; Compose acceptance observed absent edit control. Additional tests observed incorrect UTC date prefill at a Moscow boundary and Save enabled with category length 65 while Core limit is 64.
- Added owner/admin family editing and member-own editing; viewers cannot edit. Editor preserves identity/version/currency/source/account/debt/owner, validates amount and Core text limits, converts existing Instant through tenant timezone for date prefill, and sends PATCH with full update payload, quoted version, and retry-stable idempotency key. Stale/permission errors are localized. Debt payments require an active/retained debt and Core performs balance compensation.
- GREEN: `FinanceScreensTest` 38/38; full isolated API 27 Android instrumentation 69/69, 0 skipped/failures; Android main/test Kotlin compilation and `git diff --check` passed. Core/Postgres unchanged from the preceding slice: Core check 290 with 2 environment-gated skips, `TransactionApiPostgresTest` 136/136.
- F09 Android remains partial only for owner/admin transaction reassignment and real authenticated Android-to-Core E2E. Continue with the next independently testable F59 Android slice; do not mark F09 or F59 complete.
- Commit/push: `0c5204c` (`feat(F59): edit Android transactions`); no GitHub Actions run was created for the revision.

## E4.103 F09 Android transaction reassignment — 2026-10-08 10:45 MSK

- Test-first RED: owner/admin editor lacked `transaction-edit-owner-picker`; the runtime test expected selecting an active member by display name and sending its UUID. Member test also pins that no owner selector appears and own `ownerUserId` is preserved.
- Added owner/admin-only active-member selection to the editor. Core remains the authority: member reassignment is denied, and missing/inactive target membership is rejected. Ordinary members can edit their own transaction only; viewers cannot edit.
- GREEN: owner reassignment 1/1, admin reassignment 1/1, member self-vs-other 1/1; full isolated API 27 instrumentation suite XML reports 71 tests, 0 skipped, 0 failures/errors; Gradle log reports `BUILD SUCCESSFUL`. The invoking batch wrapper emitted `The batch file cannot be found` only after Gradle success; test XML and Gradle output both confirm GREEN. `git diff --check` passed.
- F09 Android locally covers server-side filters/pagination, edit, and owner/admin reassignment. Only authenticated Android-to-Core E2E remains for its parity row; F59 stays in progress. Next follow F59.2 with the next high-priority Android feature slice.
- Commit/push: `92db740` (`feat(F59): reassign Android transactions`); no GitHub Actions run was created for the revision.

## E4.104 F10.1 Android receipt upload design — approved

- User approved the bounded F10.1 design: API-23-compatible JPEG/PNG system picker, authenticated multipart Core photo-job upload with idempotency, bounded OCR progress polling, and opening the resulting receipt draft. No transaction is auto-posted; receipt edits/duplicate decisions/confirmation remain later F15-F18 slices.
- Constraints: RU/EN, writer-only upload with Core authorization, no broad storage permission, synthetic image fixtures, existing Core/Web contract, Android 6+.
- Initial state: TESTING_RED. Test agent first added runtime acceptance for authenticated-user navigation into the receipt upload screen; UI/API implementation followed observed RED.
- Observed RED: `FinanceScreensTest#authenticatedWriterCanNavigateToReceiptPhotoUpload` failed at `FinanceScreensTest.kt:33` because `Чеки` navigation item was absent; then passed 1/1 after the UI navigation slice. API instrumentation confirmed 2 runtime RED failures, 0 skipped: upload and job getter reached deliberate `TODO` declarations. MockWebServer verifies the intended multipart, Bearer/401-refresh replay, idempotency key, and typed DTO contracts.
- API test runtime RED logs: `C:\Users\d3c0r\AppData\Local\Temp\f10-api-runtime-red.log`; emulator `emulator-5558` API 27. API agent now implements only API/DTO behavior and runs those tests. UI state/rendering tests and implementation follow as a separate cycle to avoid shared-file edits.
- First API GREEN compile attempt failed before instrumentation: three API methods passed raw `String` where DTO parsers require `JSONObject`. No tests ran; API agent is fixing those call sites and will rerun the same target.
- API GREEN: `FinanceReceiptApiTest` 2/2, 0 skipped, `BUILD SUCCESSFUL` on API 27 emulator; log `C:\Users\d3c0r\AppData\Local\Temp\f10-api-targeted-green.log`. Covers bearer auth, multipart `file`, idempotency key and byte-identical replay after 401 refresh, plus typed job/receipt parsing.
- UI acceptance RED/GREEN baseline: 4 Compose tests, 2 pass (writer picker callback; viewer read-only/no picker), 2 fail as expected (missing progress/draft tags), 0 skipped. An initial test-only scroll misuse was removed before this final pack. Log `C:\Users\d3c0r\AppData\Local\Temp\f10-compose-runtime-red-final.log`; XML under the temporary ASCII junction's Android test results.
- UI agent now implements picker/upload orchestration, bounded progress, and draft presentation against the verified API. No transaction confirm/create path is in scope.
- UI GREEN: `FinanceReceiptScreensTest` 4/4, 0 skipped, `BUILD SUCCESSFUL` on API 27; log `C:\Users\d3c0r\AppData\Local\Temp\f10-compose-targeted-green.log`. Verifies writer picker, viewer read-only, RU/EN OCR progress, exact localized draft values, and no transaction creation/confirmation.
- Review found poll loop occupies shared executor and retryable jobs stop polling without refresh. Test agent is adding a policy RED for 1s running, 5s retryable, terminal stop, bounded attempts, and refresh availability; fixer waits for this RED.
- Review-confirmed RED: `retryableJobKeepsStatusRefreshAvailable` failed because `receipt-refresh-status` is absent; timeout refresh already passes. Poll policy helper compile RED was also observed before adding its test seam. Logs: `C:\Users\d3c0r\AppData\Local\Temp\f10-poll-compose-red.log` and `C:\Users\d3c0r\AppData\Local\Temp\f10-poll-policy-compile-red.log`.
- Poll policy GREEN: 5/5, 0 skipped; receipt Compose suite 6/6, 0 skipped, on API 27. Helpers verify queued/running 1s, retryable 5s, terminal and attempt limit stop. Compose verifies role, progress, draft, and refresh visibility. Latest review found refresh disabled while global `busy` remains true during automatic polling; test agent is adding a focused RED for that interaction before the final fix.
- Focused post-fix compile attempt failed before tests: `pollReceiptJob` used the return from `invalidateReceiptPoll()` as a generation, while the helper returned `Unit`. API fixer is correcting the helper return; no tests ran on this attempt.
- Polling RED fixed: policy 5/5 and receipt Compose 7/7 pass; focused background refresh 1/1 passes. Manual refresh now works during retryable polling; generation/cancel guard prevents duplicate chains, and receipt scheduler no longer blocks shared API executor.
- First full API 27 regression ran 86 tests: 85 pass and one legacy debt-navigation test failed because `Долги` tab had moved offscreen in the now-longer horizontal row. Isolated rerun reproduced the test assumption; adding `.performScrollTo()` to that test made it pass 1/1 without production changes. Full regression is rerunning.
- Full Android instrumentation GREEN: 86/86, 0 skipped, 0 failed, `BUILD SUCCESSFUL` on isolated API 27 `emulator-5558`; log `C:\Users\d3c0r\AppData\Local\Temp\f10-full-android-api27-final.log`.
- Current state: COMMIT_PENDING. Full isolated API 27 instrumentation: 94/94, 0 skips/failures. Android unit tests: 4/4. `assembleDebug` and `assembleDebugAndroidTest`: GREEN; debug APK installed/launched on isolated API 27 emulator. Parity contracts: 60/60. `git diff --check`: GREEN. APK SHA256: `8D14E5E02EAB3BDB4C39071DB67F06AD836FA7A45C4B13190B6B312C211630FE`. No live authenticated Android-to-Core E2E; keep Android F10 partial and F10.1 locally verified.
- New RED coverage is in place: completed receipt draft GET failure requires visible manual refresh then successful draft; checkpoint persistence covers pending URI/key, returned tenant/job IDs, new store instance, and clear; refresh with blank `access_token` must preserve existing session. Android test compile RED was observed because the checkpoint store seam does not yet exist (`%TEMP%\f10-checkpoint-red-compile.log`).
- Added `ReceiptUploadCheckpointStore`; persistence test passes across independent store instances. Full API 27 RED run: 89/91 pass, 0 skipped, 2 expected failures: blank refresh token overwrites session; completed receipt GET error has no manual refresh button. Log `%TEMP%\f10-checkpoint-review-findings-red.log`. API and UI fixes are delegated separately; no commit yet.
- After API fix, targeted API GREEN: blank-token refresh preservation 1/1. `FinanceReceiptScreensTest` observed 7/9 GREEN and 2 expected REDs: active queued/running job still allows another photo; completed-job draft GET error hides manual refresh. Log `%TEMP%\f10-receipt-screens-review-red.log`.
- UI fix made the two targeted behaviors GREEN. A further recovery test caught missing manual retry when checkpoint job GET fails before job model loads; `FinanceReceiptScreensTest` now 9/10 with only this expected RED. Log `%TEMP%\f10-checkpoint-refresh-ui-red.log`; retry path fix in progress.
- Recovery retry GREEN: `FinanceReceiptScreensTest` 11/11; full Android API 27 instrumentation 94/94, 0 skipped/failures (`%TEMP%\f10-full-android-final-after-uri-cleanup.log`). Final Android unit/build/install gates passed (`%TEMP%\f10-final-android-gates-final.log`); contracts 60/60; APK installed and launched on isolated emulator-5558, SHA256 above.
- Ready for F10.1 commit after exact-path staging and final staged diff review. Preserve unrelated user-owned untracked directories/files.
- Final API review found local `file_read` failure can leave pending URI checkpoint unresolved and block selecting a replacement. New test observed 10/11 screen tests GREEN; `unrecoverablePendingPhotoOffersExplicitDiscardAndChooseNewPhoto` is expected RED until a safe discard/reselect path exists. Log `%TEMP%\f10-pending-photo-discard-red.log`.
- Discard/reselect GREEN: targeted screen tests 11/11. The first rerun found test callback wired to picker instead of discard; corrected the test helper, then all 11 passed (`%TEMP%\f10-receipt-screens-discard-green-rerun.log`). Persistable URI permission now releases after successful upload and logout; latest full instrumentation and Android gates pass after cleanup.
- Next: commit verified F10.1 paths, record SHA, then select next approved plan goal.
- Final API review found P2: the dedicated receipt executor can refresh concurrently with shared API work. If Keycloak rotates refresh tokens, one racing request could receive `invalid_grant` and clear a valid session. A deterministic concurrent-401 RED is now recorded; API fixer is adding serialized refresh and reuse of newly rotated tokens.
- Concurrent-refresh RED is deterministic: two initial API 401s synchronized; MockWebServer holds first refresh and observed two refresh requests with the old token. `FinanceReceiptApiTest#concurrentUnauthorizedRequestsRefreshRotatingTokenOnceAndKeepSession` failed expected 1 refresh, got 2. Log `%TEMP%\f10-concurrent-refresh-red2.log`; API fixer now adds serialized refresh/re-read reuse.
- Concurrent refresh GREEN: targeted `FinanceReceiptApiTest` 3/3 with zero skipped/errors; single-flight refresh and multipart retry pass. Independent review then identified logout overlapping an in-flight refresh as a possible session-resurrection race. Test agent will add a deterministic logout-vs-refresh RED after full regression; API fixer will synchronize logout with refresh if reproduced.
- Post-single-flight full Android instrumentation GREEN: 87/87, 0 skips/failures on API 27 `emulator-5558`; log `%TEMP%\f10-full-android-api27-post-refresh-lock.log`. Logout-race test is now in preparation before final contract/commit gates.
- Logout race RED reproduced: `FinanceReceiptApiTest#logoutDuringRefreshDoesNotRestoreSessionWhenRefreshCompletes` fails because the delayed refresh restores the session after logout. Log `%TEMP%\f10-logout-refresh-red.log`; API fixer is coordinating refresh and logout under the same lock and removing stale-session fallback.
- Initial post-fix invocation compiled latest source/APKs but did not start AndroidJUnitRunner and returned `Failed to receive the UTP test results`; its visible logcat RED came from the prior test run, not the latest build. Diagnosis: prior UTP cleanup left emulator booted but removed test instrumentation package.
- UTP cause found: prior failed cleanup left emulator booted but test instrumentation package uninstalled. Reinstalled via targeted rerun; `logoutDuringRefreshDoesNotRestoreSessionWhenRefreshCompletes` passed 1/1. Fresh runner start/finish and XML confirmed; log `%TEMP%\f10-logout-refresh-rerun.log`. Running the whole API test class next.
- Post-logout-fix API GREEN: `FinanceReceiptApiTest` 4/4, 0 skipped/errors, including concurrent 401 rotation, multipart replay, DTO parsing, and logout-vs-refresh. Full Android API 27 suite rerun at 88 tests; then unit/build/contract validation.
- Full regression attempt at 88 tests stalled after 61 completed; UTP started FinanceScreensTest#transactionTextCreatesDraftThenRequiresReviewBeforeConfirmation but device stopped responding. Stopped only Gradle/UTP. A targeted retry first failed before runner start because ddmlib timed out reading API 27 device properties. Diagnosed isolated AVD CodexF57Api27 (port 5558) and restarted only its verified process tree; targeted method passed 1/1 in 28s (%TEMP%\f10-transaction-draft-recovered.log). Full isolated API 27 instrumentation then passed 88/88, 0 skipped or failures; log %TEMP%\f10-full-android-after-avd-recovery.log.

## E4.104 F10.1 Android receipt upload — final verification

- Final review added two logout/privacy protections: stale receipt operations carry a generation token invalidated synchronously at logout; persisted document URI grants are reconciled at startup and logout, retaining only the URI in a valid pending-photo checkpoint. This also recovers grants orphaned by process death.
- RED/GREEN: generation test first failed compilation because the guard did not exist; then passed. URI cleanup policy tests first failed compilation because the policy did not exist; then passed 3/3. Receipt generation and cleanup policy unit targets passed 4/4.
- Latest verification after all edits: receipt screens 11/11; complete Android unit suite 8/8; `assembleDebug` and `assembleDebugAndroidTest` successful; full isolated API 27 instrumentation 94/94, zero skipped/failures/errors. Logs: `%TEMP%\f10-uri-generation-policy-green.log`, `%TEMP%\f10-receipt-uri-screens-green.log`, `%TEMP%\f10-uri-final-unit-build.log`, `%TEMP%\f10-uri-final-full-connected.log`.
- Contracts: `tools/contracts/test_contracts.py` 60 passed. `git diff --check` clean. Latest debug APK SHA256 `8373F7ADF7FEBA6BB943DDDDA48417F8ECBB35B352E2A0DF3A9DB25CF1179D3A`; installed and launched on isolated `emulator-5558` (`com.decorix.finance.debug`, PID 14533).
- No authenticated Android-to-live-Core E2E was available; F10 remains partial and F10.1 is locally verified. Commit/push: `3b2ac58` (`feat(F10.1): upload receipt photos from Android`); `gh run list --commit 3b2ac58` returned `[]`, so remote CI did not start. Next: select the next approved V1 plan goal.

## E4.105 F03.1 Android first-time onboarding — COMPLETE

- Selected from F59.2 profile-first priority after F10.1. Scope: welcome → workspace/member identity → optional monthly income → explicit budget proposal decision; back preserves entered values; apply or keep is a separate user choice, and existing authenticated users do not create another tenant.
- Reuse current Core tenant/profile/budget APIs; no new backend rules or automatic budget application. Refresh tenant membership before retrying ambiguous tenant creation; reuse stable proposal/apply idempotency keys. Repeat setup from an existing profile remains a later F03 slice; F03 Android stays partial.
- RED evidence: onboarding UI initially lacked welcome and explicit budget choices; 8 expected failures in the 47-test screen baseline. API compile RED exposed missing caller-supplied idempotency keys. Store compile RED exposed missing durable retry-key storage. Error-message tests observed RED for budget `412/409` copy that described transaction edits, then for copy promising a nonexistent refresh action.
- GREEN evidence: corrected onboarding/retry Compose tests; `BudgetIdempotencyKeyStoreTest` 1/1; `BudgetApplyErrorMessagesTest` 4/4; `FinanceReceiptApiTest` 7/7; `FinanceScreensTest` 48/48; full isolated API 27 instrumentation 104/104; full JVM unit suite passed; `assembleDebug` and `assembleDebugAndroidTest` passed; contract suite 60/60 using project `.venv`; `git diff --check` clean.
- Runtime proof: installed and launched `com.decorix.finance.debug` on isolated Android 8.1 emulator `emulator-5558`; resumed `MainActivity`, PID 10980. APK SHA-256: `453A36AB1D74D6CC4D88F99DC1EB8F80B081D0FC28169D9086D0A75DE2F11E73`.
- No live authenticated Android-to-Core OIDC E2E was available. F03 Android remains PARTIAL; repeat setup from profile remains separate. Commit/push: `1395decae6c50d337193fb6ec3810e6710484116` (`feat(F03.1): add Android first-run onboarding`). `gh run list --commit 1395dec` returned no workflow runs, so remote CI is not confirmed. Next: F59.2 receipt-review parity.

## E4.106 F14.1 Android receipt evidence review — COMPLETE LOCALLY (F14 remains PARTIAL)

- Selected from F59.2 receipt-review priority after F03.1. Requirement: show private OCR/Vision reading separately from verified receipt fields; preserve unknown merchant/date/total/category; Vision values remain explicitly unverified. Use existing owner-scoped Core API; no Core/business-rule changes.
- Acceptance: authenticated Android GET uses `/api/v1/tenants/{tenantId}/receipts/{receiptId}/readings`; typed parser preserves raw OCR text/word coordinates, provenance, exact decimal strings, reconciliation, nullable Vision reading. Compose renders scrollable evidence and `categorySource` in RU/EN, translates known fallback reasons without exposing machine codes, unknown values remain visibly unknown, and reading failure does not fail or mutate the receipt draft. No transaction is posted.
- Dependencies and constraints: F10.1 photo upload creates the draft; existing receipt API/model and F14 spec are source of truth. Synthetic fixtures only. No OCR content in logs or persistent local state. F14 stays partial until additional receipt editing/reconciliation flows land.
- RED observed: `:app:compileDebugAndroidTestKotlin` failed on missing receipt-reading API/model/Compose-state seams; screen RED exposed inaccessible below-viewport evidence and raw fallback codes. GREEN: API 10/10, Compose 16/16, JVM 12/12, full API 27 instrumentation 112/112, `assembleDebug`, `assembleDebugAndroidTest`, contracts 60/60, and `git diff --check`. Fresh APK installed/launched on isolated Android 8.1; resumed `MainActivity`, PID 16317; SHA-256 `A5ED96A8C199CB6283E9B1178A8A464B8FD6DE2E1C6A15E50DC3CB66EBC72693`. No live OIDC/Core E2E. F14 Android remains partial until receipt edit/reconciliation flows land. Commit/push: `69dcb25`; remote CI has no run yet.

## E4.107 F15.1 Android receipt item browsing/pagination — COMPLETE LOCALLY

- Source of truth: `PLAN.md` F15 and Core `GET /api/v1/tenants/{tenantId}/receipts/{receiptId}/items?page=N`; authenticated owner-scoped route returns a 1-based page of exactly up to 8 ordered items plus required `page`, `totalItems`, and `hasMore` fields.
- Acceptance: typed Android page parser preserves item IDs, order, version, nullable fields and exact decimal strings. The receipt owner can browse pages with Previous/Next; Next follows only `hasMore`, pages do not duplicate or skip items, and all displayed cash totals remain unchanged. Browse remains read-only, with no item add/edit/delete, total sync, duplicate decision, confirmation, or transaction post. Foreign-owner 404 propagates.
- RED: API compile failed on the missing typed page API/DTO. Compose compile failed on missing page state/callback. Runtime tests then exposed absent quantity/unit-price display, duplicate request after manual Retry, and Next enabled after page-load error without a loaded `hasMore`. Test-only viewport issues were corrected: scroll to off-screen pager controls/content and match the combined item row by substring.
- GREEN: `FinanceReceiptApiTest` 12/12; `FinanceReceiptScreensTest` 19/19; JVM 12/12; full isolated API 27 instrumentation 117/117 with zero skipped/failures; `assembleDebug` and `assembleDebugAndroidTest` successful; parity contracts 60/60; `git diff --check` clean. Synthetic 9-item acceptance verifies 8→1→8 paging, exact quantity/unitPrice/lineSum, item IDs/versions/order, unchanged cash total, and no mutation/post callbacks. Retry is exactly one request; Next remains disabled after page-load failure until a response provides `hasMore`.
- Runtime proof: installed and launched `com.decorix.finance.debug` on isolated Android 8.1 `emulator-5558`; resumed `MainActivity`, PID 7078. APK SHA-256 `11D7875BD40FADC392C79C7B9D51ADE1F6774AD4DCFF812D90C28BB287E0E6F7`. Runtime screen is login; live authenticated Android-to-Core OIDC E2E was not available. No Core changes; existing endpoint is owner-scoped and foreign-receipt 404 is verified by API test.
- Review: independent API reviewer found no blocking or important findings.
- Commit/push: `482dd81` (`feat(F15.1): browse receipt items on Android`). `gh run list --commit 482dd81 --limit 10` returned no workflows; GitHub CI is not confirmed. Next: continue F59.2 with the next approved Android parity slice; F15 remains partial until editing/reconciliation and related downstream flow requirements are complete.
- Constraints: synthetic fixtures only; no Core changes because endpoint already exists. `totalItems` is non-null in the Core contract, so no fabricated unknown-total behavior is added.

## E4.108 F15.2 Android single receipt-item edit — COMPLETE LOCALLY

- Source of truth: `PLAN.md` F15 parity registry and existing owner/write/version-guarded Core `PATCH /api/v1/tenants/{tenantId}/receipts/{receiptId}/items/{itemId}`. This slice edits one existing draft item only; add/delete/total sync/duplicate resolution/confirmation remain out of scope.
- Acceptance: writer opens prefilled name/quantity/unitPrice/lineSum fields, saves exact strings with the current receipt version in quoted `If-Match`, and sees updated item data. `cashTotal` remains unchanged while Core recalculates `itemsTotal`. Viewer has no edit action. Stale `412` preserves entered form data and gives localized recovery copy plus an actionable GET refresh that updates the receipt version and current item page before retry; no transaction or unrelated mutation occurs.
- Dependencies: F15.1 page DTO and item list are locally verified; Core PATCH already exists and owns validation, authorization, version conflict and item-total recalculation. For edits after page 1, refresh the current page because PATCH embeds only first 8 items.
- RED: `:app:compileDebugAndroidTestKotlin` fails on missing `FinanceApi.updateReceiptItem(...)`, `FinanceUiState.receiptItemEditError`, and `FinanceScreen(onReceiptItemUpdate=...)`; log `%TEMP%\f15-2-item-edit-compile-red.log`. Tests now cover PATCH path/auth/exact JSON/quoted receipt `If-Match`/response parsing/412, writer edit preserving exact strings and cash total, viewer read-only, and localized stale-version form preservation.
- Attempt 1: first post-implementation compile failed before tests on positional `ReceiptUploadScreen` argument order and unresolved `Alignment`; log `%TEMP%\f15-2-api-green.log`. UI agent is fixing those source-level errors; no runtime tests ran in that attempt.
- Attempt 2: API contract passed 14/14 on isolated API 27. Compose first run failed two editor tests: `remember(editingItemId)` recreated the fields as empty when opening the editor; XML confirms empty `InputText`. UI agent is fixing state-key lifetime. The API run initially targeted both connected AVDs because `ANDROID_SERIAL` was unset; later Android runs are pinned to `emulator-5558`.
- Attempt 3: stable state key fixed prefill; stale test now sees correct `EditableText`/`InputText`. Compose assertion then failed because `assertTextEquals` included the TextField label as well as entered value. Test helper now checks entered value with substring matching; no production change for this failure.
- Review blocker: the API reviewer found PATCH and page>1 reload were in one `runCatching`. A committed edit followed by reload failure was reported as a failed save, leaving the old receipt version and making retry stale. Added `ReceiptItemUpdateWithPageRefreshTest` for separate mutation and reload outcomes; observed RED due to missing helper in `%TEMP%\f15-2-update-item-page-refresh-red.log`.
- Follow-up RED→GREEN: helper unit test passes 2/2 with synthetic data. Activity now publishes successful PATCH receipt/version and closes the editor even when page reload fails; reload error is reported separately as `receiptItemsError`. Targeted screen instrumentation is running; full regression/build/runtime verification remains.
- Follow-up review blocker: stale `412` recovery text said to refresh, but supplied no GET action; receipt version stayed stale so retry would conflict again. Added RU/EN Compose acceptance for explicit refresh callback, exact receipt ID, and preservation of all unsaved form fields. Observed compile RED due missing `onReceiptItemRefresh` in `%TEMP%\f15-2-stale-edit-refresh-red.log`.
- Second recovery blocker from independent UI review: if receipt GET succeeds but page>1 reload fails, current state clears the stale-error action and drops `receiptItemsPage`, hiding the open editor and preserved values. Keep the old page visible with separate `receiptItemsError`; retain the updated receipt/version. A pure-helper regression for this split outcome is being authored.
- RED→GREEN: `refreshReceiptAfterConflictWithPage` retains the refreshed receipt/version and exact prior page on item-page failure; Activity reports `receiptItemsError` separately, keeping the edited row/form visible. Helper tests pass. Compose acceptance now navigates to page 2 and confirms values remain visible after a failed refresh.
- Environment diagnosis: one suite attempt crashed on API27 x86 with `OpenGLPipeline.cpp:105` / `GL errors` on the RenderThread. The failing unrelated test passed alone; rerunning all 24 receipt-screen tests on the dedicated `CodexFinanceBotApi27` AVD with `-gpu swiftshader` passed 24/24. This indicates the original host GPU renderer caused the crash, not a deterministic app failure. A test agent briefly ran the instrumentation APK on user-owned `Planerka_API27` before the no-use instruction arrived; the test was stopped, with no manual app-data changes. All final Gradle runs pin `ANDROID_SERIAL=emulator-5554`.
- Final verification: JVM 15/15; complete Android 8.1/API 27 instrumentation 124/124; receipt screens 24/24; Python parity contracts 78 passed/2 skipped (pytest cache permission warning only); `assembleDebug` and `assembleDebugAndroidTest` successful; `git diff --check` clean. Installed APK on isolated `CodexFinanceBotApi27` and confirmed `MainActivity` resumed. SHA-256 `99F5C9E9A67805F89A64F7B3FAAE6C606308A935D1076902A7A43FAD27D7B26A`.
- Independent API and UI reviews found no remaining blockers. Synthetic fixtures only; no Core changes. Live authenticated Android-to-Core OIDC E2E remains unverified.
- Commit/push: `1ad9975c5aaa0a82034b4f05772299b78aadd1fa` (`feat(android): edit receipt draft items`); `git ls-remote` confirms the same remote branch SHA. `gh run list --commit 1ad9975 --limit 10` returned no workflows; remote CI is unconfirmed.
- F15.3 commit/push: `7498080269fa176d41b615bd3fe7aa93fd1aadb0` (`feat(android): sync receipt total to item sum`); `git ls-remote` confirms `origin/feat/saas-rewrite` at this SHA. `gh run list --commit 7498080 --limit 10` returned no workflows.
- Next: F15.5 manual receipt-item delete. F15 and F59 remain partial.

## E4.109 F15.3 Android explicit receipt-total synchronization — PLAN

- Source: `PLAN.md` F15 acceptance and existing Core `POST /api/v1/tenants/{tenantId}/receipts/{receiptId}/sync-total`.
- Acceptance: show exact `cashTotal` and `itemsTotal`; expose a clearly labeled writer-only action for differing totals; send the current receipt version in quoted `If-Match` with no endpoint-specific fields (the shared POST client sends `{}`); do not optimistically change either amount. Apply Core's returned DTO/version only on success. Core is the source of truth: incomplete/non-positive item totals return localized 409 guidance; stale 412 retains existing values and offers receipt refresh. Viewer cannot sync; no transaction is created. A failed later-page refresh after commit keeps the committed receipt/version and current page visible with a separate page error.
- Dependencies: F15.1 paging and F15.2 item edit are locally verified; Core endpoint, owner/write guard, positive-complete-items check, audit and totals behavior already exist. No server changes.
- Test plan: MockWebServer asserts direct Core path, bearer, quoted `If-Match`, POST using the established empty-object convention, exact response decimal strings/version, and 409/412. Compose asserts exact total display, no request before explicit action, writer callback values, response-driven total change, localized 409/412, viewer read-only, no transaction/confirmation callbacks, equal-total no-op gating, and Core-owned validation for incomplete totals.
- RED: API + Compose tests first compiled red on missing `FinanceApi.syncReceiptTotal`, `FinanceUiState.receiptTotalSyncError`, and `FinanceScreen` callbacks. After implementation, behavioral reds caught the shared `{}` POST convention, stale UI expectations, and overly specific 409 wording; tests/wording were aligned with the actual Core and Android contracts. Logs: `%TEMP%\f15-3-sync-total-compile-red.log`, `%TEMP%\f15-3-api-final.log`, `%TEMP%\f15-3-screens-final.log`.
- GREEN: `FinanceReceiptApiTest` 16/16 and `FinanceReceiptScreensTest` 28/28; full API 27 instrumentation 130/130, JVM 15/15; `assembleDebug` and `assembleDebugAndroidTest` successful. Debug APK SHA-256 `B1F41A289A2F8D56C32308F3F8213077C9888171CD52AEFDCA10DA70B30953F5`; installed on isolated `CodexFinanceBotApi27` (API 27) and `MainActivity` confirmed resumed. `git diff --check` clean. Independent API and concurrency review found no remaining blocker; the single-thread executor serializes receipt updates and refreshes. Python parity contracts were not rerun (Python 3.10 environment lacks pytest; no Python code changed).
- Current status: GREEN, committed and pushed as `7498080269fa176d41b615bd3fe7aa93fd1aadb0`; remote branch SHA verified. No workflow run was returned by GitHub CLI.

## E4.110 F15.4 Android manual receipt-item add — COMPLETE LOCALLY AND PUSHED

- Source: `PLAN.md` F15 requirement; existing Core `POST /api/v1/tenants/{tenantId}/receipts/{receiptId}/items`.
- Acceptance: an active non-viewer member can explicitly open an add form with required name, quantity defaulting to `1`, and optional unit price/line sum; decimal inputs stay as strings and are sent with the current quoted `If-Match`. Apply only Core's returned `FinanceReceipt`; preserve its exact unchanged `cashTotal` and use returned `itemsTotal`, count and version. Core returns only the first eight rows; update page 1/count and enable Next from `itemCount`, then use paged GET for the appended row (page 2 after item 9) and authoritative `hasMore`. Viewer cannot add; adding never posts a transaction. Keep add form/error state separate from item edit. A stale 412 retains entered fields and offers receipt refresh. A 400 shows localized validation/200-item-limit guidance. Because Core has no idempotency key for this endpoint, never auto-retry an ambiguous network failure: require receipt refresh and reconcile item IDs/count/entered values before another POST; if the refreshed result remains ambiguous, keep retry gated for explicit user resolution. After a successful add, a later-page GET failure must retain the committed receipt/version, keep the prior page visible, and offer retry of the page GET only.
- Dependencies: F15.1 paging, F15.2 editing, F15.3 total sync and Core add endpoint.
- Test plan: MockWebServer asserts direct POST route, bearer, quoted version, JSON content type, exact string JSON (including trailing decimal zeros), exact returned item/totals/version/count, unchanged cash total, no transaction, one request after a dropped response, and preserved 400/409/412 statuses. Compose asserts no add before explicit tap; writer-only Save/Cancel with required name and exact callback values; response-only totals/count/version and no transaction callback; viewer read-only; page 1→2 access to item 9; localized validation/stale/unknown-outcome copy; refresh-required before retry after ambiguous failure; and localized 200-item cap with no add action.
- RED: API + Compose tests first compiled red on missing API/UI seams (`%TEMP%\f154-api-compose-compile-red-2.log`); after implementation Kotlin found `Set<Any>` vs `Set<String>`, fixed by merging item IDs. API-27 Compose exposed stale owner-action and viewport expectations plus a duplicate text selector; fixed in tests. A new 200-item-limit test then failed because add was still exposed. A rerun also exposed a shared-preferences token expiry leak in the pre-existing budget API test; that fixture now supplies its own long-lived synthetic token.
- GREEN: `FinanceReceiptApiTest` 20/20; `FinanceReceiptScreensTest` 35/35; full Android API-27 instrumentation 141/141 on isolated `emulator-5554`; `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` succeeded. APK installed and `MainActivity` confirmed resumed on API 27. SHA-256: `3042BFB42FC790A2D6A6DD2FE0D042F2252443DD7C15ACDE5C12BD2C7A393929`. `git diff --check` clean. Independent UI/API reviewers found and resolved the 200-item-limit gap; no blocker remains.
- Current status: GREEN, committed and pushed as `512240f8aeb57b5d1df3201cc5133290c5aec121`; remote branch SHA verified. No tests were run on the other connected emulators. Preserve user-owned untracked paths listed by `git status`.

## E4.112 F03.2 Android repeat setup from profile — GREEN, committed

- Source: `PLAN.md` F03 acceptance: repeat setup must preserve posted transactions and must not make an existing user a new workspace member.
- Implementation: profile action opens a localized repeat wizard, prefills current display name and planned income, and saves with the existing authenticated `PATCH /api/v1/tenants/{tenantId}/profile/me`. It starts budget proposal only after the profile PATCH plus snapshot refresh succeeds; failure leaves the form retryable. Budget Apply/Keep remains explicit. No tenant create or transaction mutation is issued.
- TDD: Compose RED first showed the profile repeat action was missing. First implementation RED showed the budget proposal was not triggered reliably; the flow now uses an explicit API completion callback. Tests defer completion and prove no proposal before success, prove failure causes no proposal and can be retried, and prove unchanged history and explicit Apply/Keep.
- GREEN: full API 27 instrumentation 145/145; focused F03 UI tests 3/3; `FinanceReceiptApiTest` 21/21; Gradle JVM unit tests pass; `assembleDebug` and `assembleDebugAndroidTest` pass; debug APK installed and instrumentation run on isolated Android 8.1/API 27 emulator `emulator-5554`. `git diff --check` passes. Synthetic data only.
- The parity registry now points to both Compose and API tests and states the remaining live OIDC/Core gate. No live authenticated Android-to-Core E2E was run; F03 Android remains partial. No backend code changed.
- Preserve user-owned untracked `.android-user/`, `.freebuff/`, `.pnpm-store/`, `CODEX_AUTONOMOUS.md`, `apps/android/current-screen.png`, and `tmp/`.
- Commit: `018084d` (`feat(F03): add Android repeat profile setup`), pushed; remote branch verified at `f390ab4`. `gh run list --commit f390ab4` returned no workflow run. Next: continue the next independently executable V1 parity goal. Keep F15.5 design pending until the user's answer arrives.

## E4.113 F16 Android duplicate review and explicit receipt confirmation — GREEN

- Scope: Android loads same-owner/same-total receipt candidates, asks for an explicit duplicate or independent decision, and creates an expense only after explicit confirmation. Confirmation uses a deterministic tenant+receipt idempotency key; retries preserve that key. Owner-only writes, exact reconciled totals, receipt version, candidate freshness, and Core 409/412 recovery are enforced. If a saved duplicate's candidate disappears, the user can explicitly mark the receipt independent before confirming.
- TDD: Compose/API tests first exposed missing Android UI/API seams. API RED was 23/25 because the generic POST helper sent `{}` while confirm's OpenAPI contract has no body; confirm now sends an empty body without changing other POST behavior. A recovery-policy JVM RED proved 409 and 412 must reload the receipt and candidates. Independent review found a stranded duplicate when its candidate disappeared; the new Compose test failed on the missing independent action, then passed after the UI fix.
- GREEN: full isolated Android 8.1/API 27 instrumentation 155/155; `FinanceReceiptApiTest` 25/25; `FinanceReceiptScreensTest` 41/41; JVM tests 17/17; `assembleDebug` and `assembleDebugAndroidTest` successful; parity-contract regression 63/63; `git diff --check` clean. Debug APK installed and `MainActivity` resumed on isolated `emulator-5554`; SHA-256 `6E3A1CDA90A935556A9C7EE378EAA7A7F478F86B3B84D15EDA8113112AC8191C`.
- Independent final review found no remaining blocker. No Core/business-rule changes and synthetic test fixtures only. Live authenticated Android-to-Core OIDC E2E was not run; F16 Android remains PARTIAL in the parity registry until that gate is available. Remote CI was not checked for this uncommitted change.
- Next: F17 Android receipt category/provenance parity. F15.5 deletion semantics remain pending user input.

## E4.114 F17 Android receipt category and provenance — GREEN locally

- Scope: Android reads Core's category, source, algorithm version, exact alcohol/leisure shares and leisure flag; displays localized RU/EN labels; permits explicit manual category selection for owner/admin/member writers only. Save uses Core's quoted `If-Match` category PATCH and replaces the draft with the returned receipt. Null shares remain absent; Android does not calculate category thresholds. Viewer stays read-only. On Core 409/412, Activity refreshes the receipt and requires review before another save; API test preserves 412, and Compose verifies refreshed-version reselection. The Activity HTTP refresh branch is code-reviewed, not live Core E2E tested.
- TDD: API tests first observed missing `FinanceApi.selectReceiptCategory`; Compose tests observed missing callback/category UI. Review caught a member-visible Save whose Activity handler still rejected non-owners; a JVM role-policy test compiled red on missing `canWriteReceiptCategory`, then passed after the same owner/admin/member policy was wired to both UI and handler. Screen test expectation/viewport fixes were limited to Compose assertion semantics; no product behavior was weakened.
- GREEN: `FinanceReceiptApiTest` 27/27; `FinanceReceiptScreensTest` 46/46; full isolated API 27 instrumentation 162/162; JVM unit tests 19/19; `assembleDebug` and `assembleDebugAndroidTest` successful; parity contract tests 63/63. Member Save callback and stale refreshed-version reselection pass. APK installed and `MainActivity` resumed on `emulator-5554`; SHA-256 `3C6C1E03917DBB57ECA381D76C187277585F0A56AFD57AA338E2DF8C06F83ABC`. `git diff --check` clean. Independent Core/API review found no remaining F17 blocker.
- Live authenticated Android-to-Core OIDC E2E was not run; F17 Android remains PARTIAL. Synthetic fixtures only; no Core/backend business-rule changes.
- Next: proceed to the next V1 parity slice; F15.5 deletion semantics remain pending user input.

## E4.115 F18 Android basket review — GREEN locally

- Scope: owner/admin/member can explicitly request Core basket review for a non-empty draft/review-required receipt; viewer, empty basket, and confirmed receipt cannot start it. Android sends the authenticated POST with quoted `If-Match` and an empty body, renders the returned item verdict/reason/action/advice and source/provenance in RU/EN, and replaces the receipt only with Core's response. Exact totals and line sums remain unchanged. Unknown verdict/source values do not leak raw machine codes in Russian UI.
- TDD: API and Compose tests first observed missing `reviewReceiptBasket` and basket callback. A lifecycle RED then showed missing tenant+receipt identity for the pending action. Activity now scopes pending state to both IDs, allows a different receipt to proceed, ignores stale generations, and clears only its own pending state. 409/412 reloads the authoritative receipt; 502/503 and invalid versions remain typed API failures. Independent review found no blocker.
- GREEN: `FinanceReceiptApiTest` 31/31 and `FinanceReceiptScreensTest` 51/51; both instrumented classes pass on isolated Android 8.1/API 27 emulator (`OK (82 tests)`). Android JVM tests 19/19. `assembleDebug` and `assembleDebugAndroidTest` pass. Parity-contract regression 63/63. Debug APK installed and `MainActivity` resumed on `emulator-5554`; SHA-256 `A6E8F91CCFA608F5554036A50CB4694A60F9EF4AC5CA66B3DDCB7B50F35FA420`. `git diff --check` clean.
- No Core/business-rule changes; synthetic fixtures only. Live authenticated Android-to-Core OIDC E2E and Activity-level HTTP 409/412 refresh test remain unverified, so F18 Android stays PARTIAL. Parity registry and F59 plan snapshot updated.
- Next: F19 Android persistence/display of receipt item verdict and advice; F15.5 deletion semantics remain pending user input.

## E4.116 F19 Android item-ID verdict/advice association — GREEN locally

- Scope: F18's Core-owned basket-review response and Compose receipt rows already carry verdict/advice/source on each receipt item ID. Added focused Android evidence for identical product names with distinct IDs and review metadata; legacy `unknown` source remains unknown through parsing and RU display. No name-based Android matching or duplicate persistence layer added.
- TDD: test-only F19 slice because audit confirmed the required production API and UI already landed in F18. New API and Compose tests independently prove ID-specific verdict/advice/source values for same-name rows and preserve unknown provenance. Both passed on the first targeted API-27 run.
- GREEN: focused F19 instrumentation 2/2; prior full F18 API+screen instrumentation 82/82; Android JVM 19/19; debug/test APK builds pass; parity contract 63/63. Existing debug APK remains installed on isolated `emulator-5554`. Synthetic fixtures only.
- Android F19 remains PARTIAL pending live authenticated Android-to-Core OIDC E2E. Core/PostgreSQL owns durable persistence and duplicate-name item-ID/audit association. Parity registry and F59 plan snapshot updated.
- Next: F20 Android disagreement controls and disputed-item pagination; F15.5 deletion semantics remain pending user input.

## E4.117 F20 Android disagreement controls and disputed-item pagination — IN PROGRESS

- Source: approved `PLAN.md` F20 and existing direct Core contracts `GET /api/v1/tenants/{tenantId}/receipts/{receiptId}/disputed-items?page=N`, product decisions GET/PUT/DELETE. Android uses Core's per-user reversible `allowed` decision; this does not overwrite a receipt item's saved verdict.
- Acceptance: render server-backed disputed rows in Core's 8-item pages; previous/next follow Core page and `hasMore`; allow/revoke decisions require a writer role and show only Core-refreshed state; viewer stays read-only. Preserve item identity, verdict/reason/action/provenance/advice and exact receipt totals. RU/EN loading, error, empty and action states; no optimistic hiding.
- Test plan: API tests cover authenticated direct page requests and exact parsed metadata, allow/list/revoke/list sequence, and viewer 403. Compose tests cover server page navigation, callback-driven allow/revoke visibility, role controls, localization, failure/empty states, 8+2 paging boundary, and unchanged receipt totals. Existing Core/PostgreSQL and Web tests are authoritative for product-key matching and server paging/mutation behavior.
- Baseline: repository was clean except preserved user-owned untracked files before F20. New API and Compose tests were added before production code. Observed RED: `:app:compileDebugAndroidTestKotlin` failed because Android lacks `disputedReceiptItems`, its parsed metadata DTO, disputed page `FinanceUiState` fields, and `FinanceScreen` callbacks. This is a relevant missing-feature compile RED, not setup failure.
- Parallel implementation is scoped: API models/client, Activity/Compose production flow, and additional Compose acceptance tests use separate production/test files. No Core business-rule changes planned.
- Live authenticated Android-to-Core OIDC E2E remains a completion gate; until proven, F20 Android parity stays PARTIAL.
- Initial validation: API-27 `FinanceReceiptApiTest` passed 35/35. The first `FinanceReceiptScreensTest` run had 5 failures; evidence separated three test issues (ambiguous shared page label, off-screen assertions, punctuation), one assertion reading the unrelated receipt draft item instead of the disputed page, and two real presentation gaps found by review (hardcoded RUB symbol; rendering up to 50,000 allowed decision rows).
- Test fixes now target the receipt-page tag, scroll error/empty content into view, assert the disputed row's localized verdict, and prove 20-key allowed-list paging (41 keys => 20/20/1) plus RU/EN USD output. Before UI implementation, API27 RED confirmed the allowed-page test lacked its page tag and the verdict test lacked its verdict tag. A concurrent partial UI edit caused a compile error before the standalone currency test could execute; no isolated pre-fix currency RED is claimed.
- Independent review found and fixed ambiguous-write reconciliation: after a possible lost response, Android reloads the disputed page/decision keys, retains the mutation error, and refreshes already loaded DoNotBuy/shopping caches; definite 4xx rejections are not marked as possibly applied. New JVM cases first failed RED for missing reconciliation, then all 7 passed GREEN.
- GREEN locally: full API-27 `FinanceReceiptApiTest` 35/35 and `FinanceReceiptScreensTest` 64/64 (`OK (99 tests)` total); Android JVM 26/26; parity contracts 60/60; debug and androidTest APK assembly passed; `git diff --check` clean. Debug APK installed/launched on isolated Android 8.1/API 27 `emulator-5554`, `MainActivity` PID 14888; SHA-256 `A6B77AC6220FCE26248909877021A9C18611200349D117C1C0DEE286BEE1595B`.
- Independent final code review found no remaining findings. F20 Android is locally GREEN but remains PARTIAL until live authenticated Android-to-Core OIDC E2E; parity registry and F59 plan snapshot reflect this gate. Synthetic fixtures only; no Core business rules changed.
- Next: F21 Android repeat-warning parity; F15.5 deletion semantics remain pending user input.

## E4.118 F21 repeat-warning parity — LOCALLY VERIFIED; PARITY PARTIAL

- Source: approved `PLAN.md` F21 and existing authenticated Core `GET /api/v1/tenants/{tenantId}/receipts/{receiptId}/repeat-warnings`. Core owns prior-confirmed-history matching, self-exclusion, and allowed-product suppression; Android must render response only and must not reimplement matching.
- Acceptance: for a non-confirmed `review_required` receipt, load warnings once per receipt from Core and render name/title/verdict, repeat count, last sum in the receipt currency, and optional advice; handle RU/EN loading/error/retry/empty states. Hide the section before review or after confirmation. After F20 allow/revoke, refresh a displayed warning list when the mutation may have applied. Preserve receipt totals and viewer read-only behavior.
- RED: API and Compose tests first failed on missing DTO, API method, state, and callbacks. Core policy test failed on missing effective-time/creation-time ordering. Independent review added three Android regressions for an in-flight warning request, definite 4xx handling, and same-count item revision changes; JVM RED exposed missing guards and the Compose revision test failed because no Core refresh callback ran.
- Implementation: Android now fetches the direct Core endpoint per reviewed receipt, validates and displays server response in RU/EN, preserves receipt totals, hides stale revisions and refreshes on item revisions. F20 allow/revoke invalidates in-flight responses and reloads warnings after success or a possibly applied mutation; definite 4xx preserves existing warning state. Core excludes future/later-effective receipts, preserves earlier same-day receipts by creation time, and uses receipt UUID as stable final tie-break. Core owns all product matching and allowed-key suppression.
- GREEN: full API-27 instrumentation `OK (196 tests)` on isolated Android 8.1 `emulator-5554`; Android JVM `29/29`; `:services:core:test` `292` tests, `0` failures/errors, `138` skipped; Web `ReceiptsPanel.test.tsx` `9/9`; parity contracts `60/60`; debug/test APK assembly passed; `git diff --check` clean. Fresh debug APK installed and launched, PID `18845`, SHA-256 `1BA41228E80B74D3C22227301B40A50B1D4778D4659357C15932EB4BDC74D6CB`. Independent final review found no remaining findings. The first parity-contract run rejected registry status `partial`; F21 uses the supported `in_progress` state while Android substatus remains `partial`.
- GREEN (hosted PostgreSQL): GitHub Core/PostgreSQL workflow `38047976514` passed on `a0a1a4316c470f67f59ef8ab7449bd650929d385`; it ran `:services:core:check` and `pytest tools/contracts -q` with PostgreSQL service. Contracts workflow `38047976461`, repository tests `38047976459`, and private receipt storage `38047976502` also passed on the same SHA. Local PostgreSQL remains unavailable, but hosted DB-backed acceptance is verified. Live authenticated Android-to-Core OIDC E2E remains unverified, so F21 Android parity remains PARTIAL. Synthetic fixtures only.
- Commit: `fbcd62bda0874918a6d5b364a64d32abf09bd068` (`feat(F21): add Android repeat warnings`).

## F59.2 F22 Android budget parity — LOCALLY VERIFIED; PARITY PARTIAL

- Source: approved `PLAN.md` F22 and F59.2 Android parity map. Core owns family/personal budget values, category/total versions, reset inheritance, and report calculations. Android displays the returned effective values and sends the selected scope/key/period/version; it must not calculate financial limits.
- Acceptance: RU/EN family and personal category/total values remain Core-sourced and exact; a member can edit personal only, owner/admin may edit family, viewer cannot mutate either; category and total PUT use their scope-specific versions; reset DELETE restores family inheritance; report requests preserve the displayed Core scope and show the server's matching monthly limit/remaining; errors/loading and exact amounts stay understandable.
- Tests first: API tests verified personal category PUT protocol/versions, reset DELETE/inheritance, and family report fields. Compose RED proved report reload changed a displayed Core `family` report to `personal`; a separate RED proved the screen showed effective personal 45,000 ₽ after selecting family instead of Core family 50,000 ₽. Scroll assertions exposed a non-scrollable budget page that clipped category rows. A further RED caught personal spending/status being paired with the family rolling limit.
- Implementation: report starts with the displayed report scope. Report responses apply only for the current tenant, authentication session, and request generation. Budget screen now scrolls within available height, displays selected-scope Core limits, labels family values explicitly, omits individual spending/status from family mode, and routes updates with scope-specific versions. Personal reset and viewer denial remain explicit.
- GREEN: Android JVM `:app:testDebugUnitTest` 34/34; API-27 `FinanceReceiptApiTest` 40/40; six focused `FinanceScreensTest` cases pass individually (personal category/total, family display and category/total versions, RU/EN, reset, viewer denial, family report reload, and budget/debt scope rendering); debug and androidTest APK builds pass; parity contracts 60/60; `git diff --check` clean. One broad `FinanceScreensTest` run reached 54/55 with an outdated family rolling-label assertion; the assertion was corrected, then a later broad run crashed in Android 8.1 `RenderThread` with `GL errors! frameworks/base/libs/hwui/renderthread/OpenGLPipeline.cpp:105`. No full-class GREEN is claimed. APK installed and `MainActivity` resumed on isolated Android 8.1/API 27 `emulator-5558`; SHA-256 `402CCDF61AA9F8D218944F93D5A131EB69B203FC60B59F279A231A9792066753`. The post-review focused-test rerun could not start because the host ran out of virtual memory (Windows error 1455); the earlier focused family test run passed.
- Parity stays PARTIAL: local fixtures cover client behavior; live authenticated Android-to-Core OIDC E2E remains unverified. No Core or Web business logic changed. Synthetic test data only.

## F59.3 F23 Android budget proposal verification — IN PROGRESS

- Scope: verify Android's existing Core-backed income/history proposal preview against `PLAN.md` F23; no production behavior or backend changes were indicated by reconnaissance. Core remains the sole owner of the 30-day history gate, allowed AI context, exact 70% allocation and rounding. Android previews Core results and requires a separate explicit Apply action; Keep leaves limits unchanged.
- Tests added first: API instrumentation checks Core-returned exact proposal values, confirms creation does not issue a budget mutation, accepts Core's `historyDays=30`, and propagates Core's below-threshold 409. Compose coverage checks RU/EN preview text and exact values, no implicit apply, then explicit apply. Fixtures are synthetic.
- Verification is BLOCKED before test execution: `git diff --check` passes, but Windows reports exhausted commit memory (`WinError 1455`). Default G1 could not map 534,773,760 bytes. A compact one-worker retry with 384 MiB heap/SerialGC crashed before configuration on a 1,643,856-byte native allocation (`Chunk::new`). A third one-worker attempt moved Java heap above 4 GiB and limited compilation; it configured Android and reached `:app:compileDebugKotlin`, then the JVM failed to commit another 65,536 bytes of metaspace. All three attempts ran zero tests. Gradle settings were restored. Independent read-only review found no assertion or scope problems. No RED or GREEN is claimed for these new tests; no production files changed. Re-run focused API/UI instrumentation when host memory is available; do not advance the plan goal until verified.

## F59.3 F23 Android budget proposal verification — GREEN; COMMIT_PENDING — 2026-10-11 01:23 MSK

- The prior BLOCKED entry records the earlier host-commit failures; this entry supersedes its status. Root cause was a stale dedicated Android emulator with excessive process commit, not insufficient installed physical RAM. Gracefully restarted only the dedicated Finance Bot emulator; left Planerka emulator running.
- GREEN: Android unit tests 34/34; full `FinanceReceiptApiTest` instrumentation 42/42; focused proposal Compose test 1/1; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` passed; debug APK installed and launched on dedicated Android 8.1/API 27 emulator `emulator-5554`; `git diff --check` passed. APK SHA-256 `41F4AEE25B01F5C764D328E0513FDD3A3CF0FA6AA95A0897230D062A2EE96B67`.
- Test-scope incident: Gradle auto-selected all connected emulators despite the injected serial; the synthetic Compose test also ran on user AVD `Planerka_API27`. That test used in-memory fixtures only and made no Core calls or receipt-data changes. Do not use that AVD again; targeted instrumentation thereafter ran directly on `emulator-5554`.
- No production code changed. Live authenticated Android-to-Core OIDC E2E remains unverified, so Android F23 parity remains PARTIAL. Stage only the two Android test files and this progress log; unrelated user-owned untracked paths remain untouched.
- Commit/push: `10e1b86ce13f95b5f6d0e3ae8a74cadc3f6ff467` (`test(F23): verify Android budget proposal preview`); branch and `origin/feat/saas-rewrite` match. `gh run list --commit 10e1b86` returned no workflow runs. Next: F24 Android dashboard parity, test first.

## F59.3 F24 Android dashboard parity — initial RED evidence, superseded — 2026-10-11 01:29 MSK

- Scope: render Core monthly remainder, daily pace and forecast in RU/EN; preserve null as unavailable; show zero monthly limit as disabled without a progress bar. Core retains all financial calculations and alert/period policies.
- Tests first: added three synthetic Compose tests in `FinanceScreensTest.kt`. AndroidTest APK compilation passed. Direct instrumentation on dedicated `emulator-5554`: `dashboardShowsCoreBudgetRemainderPaceAndForecastExactlyInRussianAndEnglish` RED at missing dashboard remainder (1 failure); `dashboardShowsZeroMonthlyLimitAsDisabledWithoutProgressBar` RED at missing disabled label (1 failure); `dashboardDoesNotInventUnavailablePaceOrMonthlyForecastInRussianAndEnglish` GREEN (1 test). No connected Gradle task used.
- Initial implementation displayed the new remainder below the dashboard viewport. `assertExists()` passed while `assertIsDisplayed()` failed, proving clipping rather than missing data. Moved the remainder next to monthly totals; no financial calculation moved to Android.

## F59.3 F24 Android dashboard parity — GREEN; COMMITTED/PUSHED — 2026-10-11 01:37 MSK

- Dashboard shows exact report remainder only for a Core monthly personal report matching summary month, formats server pace/projection via shared RU/EN money formatter, and renders Core `disabled` without a fake zero-limit progress bar. Alert threshold calculations, debt exclusion and calendar projections remain Core-owned.
- GREEN: Android JVM unit tests 34/34; five direct Compose instrumentation methods pass on isolated Android 8.1/API 27 `emulator-5554`: F24 remainder/pace/forecast RU/EN, null omission RU/EN, disabled limit RU/EN, existing dashboard/report totals, and 90%/100% alert RU/EN. Debug and AndroidTest APKs build; APK installs and `MainActivity` resumes. APK SHA-256 `07535A0A57A1257382A0B60457CFEF1AB83FF273C92EC666511CB8D78E2BA588`. `git diff --check` passed.
- `python -m pytest` was unavailable in system Python; `.venv/Scripts/python.exe -m pytest tools/contracts -q -p no:cacheprovider` passed 78, skipped 2. Registry gap now accurately states local synthetic Compose coverage and missing live authenticated Android-to-Core OIDC E2E. Independent review found no blocker; one optional test gap remains: no negative Compose assertion for hiding a family or mismatched-month report.
- Android F24 remains PARTIAL until live authenticated Core/OIDC E2E. Commit/push: `f8027d19942af78e777de803726e7675753fda9c` (`feat(F24): complete Android budget dashboard parity`); `gh run list --commit f8027d1` returned no workflow runs. Next: F25 Android parity, test first.

## F59.3 F25 Android rolling food limit parity — GREEN; REVIEWED — 2026-10-11

- Scope: use the existing Core rolling-food DTO in dashboard, reports, personal budget and family budget. Android formats exact values and statuses only; Core owns the window, totals, baseline and remaining. Family status comes from the Core family report and must not replace the personal monthly report used for F24.
- Tests first: three synthetic Compose tests cover exact date windows, spend/limit/remaining, separate limit and pace statuses, RU/EN, null remaining/baseline, disabled limit, family-specific DTO and family report request. Initial RED: status line missing on dashboard (1), missing for null/disabled data (1), and family budget line missing (1); failures were direct `assertIsDisplayed` at the absent Core-backed status lines. No Core calls or user data are used.
- Implementation: one shared formatter renders Core date and exact money fields, hides null remaining and disabled limit value, and shows limit status separately from historical pace. Family budget fetches a monthly family report into separate tenant/month-scoped state, guarded against stale tenant, month, request-generation and session responses; it also rejects responses that are not family/month. Successful workspace refreshes retrigger the family fetch so new transactions and changed limits do not leave stale values. The load leaves `state.report` intact, preserving F24's personal monthly remainder. Report food status is hidden when the selected scope differs from the returned Core report. Non-custom period requests no longer send stale custom dates.
- GREEN: Android JVM unit tests 35/35; full Android 8.1/API 27 instrumentation suite 212/212 on isolated `emulator-5554`; debug and AndroidTest APK builds pass; contracts pass 78, skipped 2; `git diff --check` clean. APK SHA-256 `4C9483D9F7105A753D29DBF4567A0D76CA83DDC9AF2EBA461997CDE59A2D9FD5`.
- Independent review found one narrow stale-response race in scope-mismatch error publication; response-currentness is now rechecked before publishing that error. Final build and full instrumentation pass after the fix. Android parity remains PARTIAL until live authenticated Android-to-Core OIDC E2E. No backend calculation changed. Contract registry records local evidence and outstanding live gate. Commit/push follows final contract validation.

## F59.3 F26 Android safe-to-spend parity — GREEN; COMMITTED — 2026-10-11

- Source: `PLAN.md` F26 and existing Core `DashboardSummary.safeToSpend`. Core remains sole owner of income basis, reserve, spending, salary horizon, promised charges and safe totals. This Android slice displays those server values; it adds no client financial calculations.
- Tests first: expanded Android model coverage to assert all ten DTO fields exactly. Added synthetic RU/EN Compose coverage for actual and planned income, exact income/expense/reserve/promised/safe values, horizon date and duration, plus absent guidance. Initial RED: actual and planned panels each failed on the missing income-basis text. A follow-up run exposed another real gap: the dashboard had no scroll semantics, clipping the reserve/charges on short screens. Added vertical scrolling to the dashboard; all card values can now be reached.
- GREEN: targeted API 27 instrumentation 4/4; full isolated API 27 instrumentation 215/215 on `emulator-5554`; Android JVM tests 35/35; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` pass; contract suite 78 passed, 2 skipped; `git diff --check` clean. Independent UI review found no blocker. Debug APK installed and `MainActivity` launched on isolated Android 8.1/API 27 (PID 15734). APK SHA-256 `6FE1C8B85008FE0597AA7B2F7E3B0CA685D107CF5D80195B6F551EFDB8B91627`.
- Commit: `69e8ba841bfdadf92f77fbf8b254e2865a447bf0` (`feat(F26): add Android safe-to-spend parity`). Android F26 stays PARTIAL only for live authenticated Core/OIDC and tenant-isolation E2E. No backend policy changed. Next: push commit, then select next approved V1 goal.

## F59.3 F27 Android debt display and lifecycle — GREEN; COMMITTED — 2026-10-11 02:44 MSK

- Scope: close the Android F27 presentation and action-evidence gaps. Core remains the only debt ledger and calculation owner; Android displays Core rate/minimum and forwards exact IDs, entered values and versions.
- Tests first: the initial API-27 Compose run was RED for missing interest-rate/minimum-payment content. Test harness issues (off-screen horizontal tab, form scrolling, and missing synthetic auth token) were corrected without production edits. Added UI checks for RU/EN, null-rate Web parity, payment/adjust/forecast callbacks and viewer/closed-debt gating; MockWebServer checks create/pay/adjust/forecast paths, normalized payloads, quoted If-Match versions, unique UUID idempotency keys and forecast responses.
- Implementation: debt cards now display Core interest rate and minimum payment, preserve rate precision while localizing its decimal separator, format minimum payment through the shared RU/EN money formatter, and match Web's `0.0000` null-rate fallback. No debt math or mutation policy moved to Android.
- GREEN: full direct instrumentation on isolated Android 8.1/API 27 `emulator-5554` 220/220; focused final F27/API instrumentation 8/8; Android `:app:testDebugUnitTest`, `:app:assembleDebug` and `:app:assembleDebugAndroidTest` pass; parity contracts 78 passed/2 skipped; `git diff --check` clean. APK SHA-256 `3D2F13953AA037EBB869DE680BC0283465309A0DEEEE3E7760BCF389CA0116B5`.
- Independent review found no production blocker. Android F27 remains PARTIAL because live authenticated Android-to-Core OIDC and tenant-isolation E2E are not tested; Core atomicity and duplicate-payment behavior remain covered by the existing PostgreSQL acceptance suite. Full F59 parity remains open.
- Commit: `b771eb3` (`feat(F27): show Android debt terms`). F27 implementation, tests and parity evidence are committed; this progress SHA record is a post-commit update. Push after recording it.
- Next: continue F59 on the next approved Android parity gap; leave pre-existing user-owned untracked files untouched.

## F59.3 F28 Android debt-payment reversal evidence — GREEN; COMMITTED — 2026-10-11 02:50 MSK

- Scope: verify Android exposes cancellation for posted debt-payment transactions and sends the exact Core transaction into the existing void handler. Core owns the versioned reversal and audit; no balance math moves to Android.
- Verification: test-only goal because the generic transaction void route and debt balance refresh already exist. Added Compose coverage that scrolls to the debt-payment history row and asserts its ID, debt ID, version, amount and type reach the void callback. Added MockWebServer coverage for tenant/transaction-scoped POST, empty body, quoted If-Match, UUID Idempotency-Key, and Core's `voided` response. First UI run showed the LazyColumn row was not composed until scrolling to history index 3; corrected test navigation, then focused Android API 27 UI/API checks passed 2/2.
- Core PostgreSQL evidence remains the authoritative proof of exact principal restoration (including capped overpayment) and audit behavior; these Android mocks do not claim to prove that server behavior or live OIDC integration.
- Android `:app:assembleDebugAndroidTest` passes. F28 Android remains PARTIAL pending a live authenticated Android/Core reversal and refreshed-balance E2E. F27's full 220-test instrumentation, focused F27 8/8, JVM 35/35, and contracts 78/2 remain the prior verified regression baseline.
- Commit: `32a88b8` (`test(F28): verify Android debt payment reversal`). This post-commit record is separate; push after recording it. Preserve unrelated worktree files.
- Next: continue F59 on the next approved Android parity gap.

## F59.3 F29 Android forecast presentation — GREEN; COMMIT_PENDING — 2026-10-11 03:07 MSK

- Scope: localize Core's debt payoff forecast basis in Android while preserving the exact server month count. A closed debt's `0` remains zero; a null horizon remains unavailable. Android adds no interest or payoff calculations.
- Tests first: synthetic Compose tests were RED on the raw English basis in Russian for both positive and zero-month forecasts. After localization, the zero-month assertion passed and exposed lazy-list behavior: the null-horizon debt was the second item and needed explicit list scrolling. Added a stable debt-list test tag and verified RU/EN horizon/basis display. JVM tests also verify the recognized Core sentence and safe localized fallback for unrecognized server copy.
- Implementation maps only the exact current Core basis sentence; unknown future copy is hidden behind a localized unavailable label. Added localized expected formatter output and visible forecast basis in the debt screen.
- GREEN: full direct API 27 instrumentation on isolated `emulator-5554` 227/227; Android JVM tests 36/36; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` pass; contracts 78 passed/2 skipped; `git diff --check` clean. Debug APK SHA-256 `85305FDD55D54E24841E46EC1EA76C1940ACEB70672769C1587CC03C82D0BF81`.
- F29 Android remains PARTIAL: current Compose tests use synthetic DTOs, and MockWebServer transport fixtures do not prove live Core response-to-screen mapping, actual zero/null boundary behavior, or authenticated OIDC. Core/PostgreSQL tests remain the evidence for interest math and the 600-month boundary. Android only recognizes the currently defined English basis sentence; other basis text displays a neutral unavailable label.
- No user-owned files were staged. Next: continue F59 with F30 Android parity.

## F59.3 F30 Android reports — TEST COVERAGE GREEN; COMMIT_PENDING — 2026-10-11 03:15 MSK

- Scope: close concrete Android report evidence gaps without moving date, period, tenant or financial rules out of Core. F30's Java/PostgreSQL, Telegram and Web implementation remains unchanged.
- Added Compose checks that custom personal reports pass exact inclusive `from`/`to` dates, reversed custom ranges disable submission, and Core weekend-spend share renders exactly in RU/EN while null remains absent. Added MockWebServer route/query coverage for month, week, 90d, custom personal and custom family reports, including exact custom boundaries.
- GREEN: Android 8.1/API 27 full instrumentation 231/231 on isolated `emulator-5554`; Android JVM 36/36; Android debug and AndroidTest APK builds pass; contracts 78 passed/2 skipped; `git diff --check` clean. The updated tests also passed independently: Compose 78/78 and report API 45/45.
- F30 Android remains PARTIAL: tests use synthetic UI state and MockWebServer fixtures; live authenticated Android-to-Core period/scope E2E and parity against real database boundary fixtures remain unverified.
- Test-only change: no production behavior changed. Next: continue F59 with F31 Android chart/export parity.

## F59.3 F31 Android price-change chart — GREEN; COMMITTED — 2026-10-11

- Scope: visualize only Core-owned 90-day personal inflation `changePercent`; retain exact signed percentages and before/after prices. No local financial calculation or fabricated history. The product-level purchase-history chart remains a separate F34 gap.
- Tests first: Compose RED confirmed no price-change chart. Added checks for rising/falling magnitudes, exact RU/EN TalkBack descriptions, unchanged labels, and hidden chart when Core says history is insufficient. JVM tests cover absolute scale, 100% visual cap, and malformed-value fallback.
- Implementation: labeled RU/EN bars use absolute Core percentages; only visual magnitude is capped at 100%. Screen keeps original exact prices and signed percentages.
- GREEN: full direct Android 8.1/API 27 instrumentation 232/232 on isolated `emulator-5554` using `-gpu swiftshader`; Android JVM 37/37; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` successful; fresh debug APK installed/launched; parity contracts 78 passed, 2 skipped; `git diff --check` clean. APK SHA-256 `111E265150758995F2AD94107ECBB9AAC65B95EBEB446C9E1ECBE38D8F46EDA8`.
- Diagnosed environment failures: an initial full run aborted in Android `RenderThread` with `GL errors! ... OpenGLPipeline.cpp:105`; the unrelated test passed alone, and full suite passed after restarting only the dedicated AVD with SwiftShader. Gradle from Cyrillic path produced `ClassNotFoundException`; same build/tests passed through ASCII junction `C:\fbtest`. User AVD `emulator-5558` was not restarted or modified.
- F31 Android remains PARTIAL pending live authenticated Android-to-Core OIDC verification. F34 purchase-history chart remains open.
- Commit/push: `4b3b2e3741e2b9aec9de6c7445f94cee21567141` (`feat(F31): visualize personal price changes`); remote ref confirmed.
- Next: start F32 Android schedule/digest acceptance tests.


## F59.3 F32 Android digest settings — GREEN; COMMIT_PENDING — 2026-10-11

- Scope: strengthen Android proof for existing member-local daily/weekly digest settings; do not duplicate Core scheduling or Python delivery rules. Core owns timezone, durable intent, retries, deduplication, quiet-hours scheduling, and no-data suppression.
- Tests first: four Compose cases and one MockWebServer wire test cover independent daily/weekly switches, valid disable/enable state, clearing both quiet-hours to null, rejecting `25:99`, rejecting one-sided quiet-hours in RU/EN, authenticated tenant GET/PATCH, exact fields, server-owned timezone omission, explicit JSON nulls, and `If-Match` versions.
- Red/fix history: initial AndroidTest compile found unsupported `hasRole`; replaced with direct tag semantics. First toggle test selected unrelated checkbox and did not scroll into view; added stable digest-checkbox tags and scroll-to-node. Locale checks initially changed digest message language instead of app language; now toggle the global `EN` control. A broad test run caught one accidental edit to an existing locale assertion; restored it before clean regression.
- Implementation: added only stable test tags to the two existing digest checkboxes; no user-visible or server behavior changed.
- GREEN: five F32 Android tests pass individually; full direct API 27 instrumentation 237/237 on `emulator-5554` with SwiftShader; Android JVM 37/37; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` pass; contracts 78 passed, 2 skipped; `git diff --check` clean. Updated debug APK installed/launched, SHA-256 `D8295E0CBD5797F6AE1D475A6E7973F05E04F825774FF9B3A875B250A60528AD`. Dedicated test AVD only; Planerka AVD untouched.
- Android F32 remains PARTIAL only for live authenticated Android-to-Core OIDC roundtrip. Backend scheduler/retry/no-data acceptance remains supported by existing Core/PostgreSQL/Python tests recorded under F32 above.
- Next: commit/push this Android evidence slice, then continue to the next incomplete approved V1 goal.
