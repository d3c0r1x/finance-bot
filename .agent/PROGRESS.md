# Execution progress

Updated: 2026-10-06 05:53, Europe/Moscow.

## Global plan

- Plan: `PLAN.md`, original specification from the planning chat.
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
| E3 Budgets and debts | Core/Web/Android budgets, proposals, rolling food, debts, reports, text drafts and category/day charts | IN PROGRESS — F25/F26/F30/F32 acceptance complete; approved F31 charts verified; price/waste series, remaining F-parity, live API/auth and scale gates remain |
| E4 Python and Telegram parity | Telegram gateway, receipt pipeline, local AI, import and Telegram flows | IN PROGRESS — F01/F02/F04/F05/F07/F25/F30/F32 acceptance complete; F10 external storage/ClamAV gate, F11, receipts/imports and remaining F-parity/runtime integrations remain |
| F25 | Rolling food consistency | COMPLETE — reports, Telegram captions, and scheduled digest share Core DTO formatting | F32 | pending |
| F26 | Safe-to-spend cash planning | COMPLETE — current recurring history, payday boundary, reserve and no-plan cases verified | F38 | pending |
| F31 | Report charts and PNG | IN PROGRESS — Web/Android/PNG category, day and limit charts verified; price/waste awaits F33/F40 | F33, F40 | pending |
| F32 | Durable daily and weekly digest delivery | COMPLETE — PostgreSQL schedules/outbox, Core API, Python worker, Web/Android settings | F25 rendering | pending |
| F35 | Receipt-cadence shopping suggestions | COMPLETE — Core, Go, Telegram, Web, Android | F34 | d001969 |
| F36 | Shopping decisions and copy | COMPLETE — bought marks, member-local mute, blocked reason, clipboard | F35 | d758099 |
| F37 | Personal basket inflation, 90-day window, top rise/fall | COMPLETE — Go, authenticated Java API, Telegram, Web, Android | F33 projection contract | 41b14f0 |
| F38 | Recurring expense/income series and warnings | COMPLETE — Go projection, member-scoped Java API, Telegram/Web/Android ranges and warnings | F26 shared recurrence rules | 69a254e |
| F39 | Mute and restore recurring series | COMPLETE — local gates and GitHub rerun pass | F38 | b1e64c4 + 2de08d9 |
| F40 | Optional-spend aggregate, verdict sources and corrected receipt lines | IN PROGRESS — F40.1 and F40.2 locally green; client presentation remains | F39 | pending |
| F40.1 | Versioned Go advice-spend algorithm and internal API | COMPLETE — local gates and GitHub regression pass | F39 | f3da7a0 |
| F40.2 | Core member-scoped receipt facts and report API integration | COMMIT_PENDING — local gates green; push and CI verification remain | F40.1 | pending |
| F40.3 | Web, Android and Telegram optional-spend presentation | PLANNED | F40.2 | pending |

## Current goal

- ID and outcome: F40.2 — supply correctly scoped confirmed receipt facts through the Core report DTO.
- Status: COMMIT_PENDING — Core report integration is green locally; focused PostgreSQL/API tests and full Core/contracts gates pass.
- Acceptance: use the report's exact date window and timezone; include only confirmed receipts joined to posted expense transactions; personal scope selects the requester, family scope applies each receipt owner's allow decision; retain null verdicts and nullable amounts honestly; cap handoff at Go's 50,000 rows; analytics failure or overflow leaves the finance report usable with an explicit unavailable result; no external call holds a database transaction open.
- F40 boundary: Core resolves report window, tenant/member scope, confirmed receipt lines and per-owner allowed decisions. Go aggregates; Web, Android and Telegram render shared Core DTO. Missing line amounts produce unavailable partial data, never fabricated totals. F31 later renders the real daily optional-spend series.
- Design: reuse report window/timezone and per-member `user_product_decisions`; send bounded authorized facts to stateless Go advice analytics; keep approved financial data in PostgreSQL.
- User choices: maintain full F01–F60 scope, Android RU/EN, separate goal commits, and F33 replay as a distinct integration gate.
- Repository / branch / HEAD: `d3c0r1x/finance-bot`, `feat/saas-rewrite`, `f3da7a0`.
- Updated at: 2026-10-06 05:53 Europe/Moscow.

## Verification evidence

| Criterion/gate | Command or procedure | Revision/worktree state | Actual result | Evidence |
|---|---|---|---|---|
| F34 Go catalog/search | `go test ./...`, `go vet ./...`, Linux build | F34 worktree | PASS | Search threshold 1, catalog threshold 3, exact prices and actual history |
| F34 Core PostgreSQL scope | `TransactionApiPostgresTest.productCatalogUsesAuthenticatedMemberScope` | isolated `finance_test_f34_catalog_20261005` on local PostgreSQL 18.6 | PASS | Both members see only their own catalog through authenticated API |
| F34 Web | `pnpm --dir apps/web test`; `pnpm --dir apps/web build` | F34 worktree | PASS, 40/40; build pass | Catalog and one-purchase search; chart only with two real points |
| F34 Python / contracts | presentation + Telegram pytest; `tools/contracts` pytest | F34 worktree | PASS, 84 passed; 48 passed/2 optional skipped | `/price` response validation, PNG behavior, public/private/Telegram schemas |
| F34 Core suite | `:services:core:check --no-daemon` | F34 worktree | PASS | Full Core unit/check gate; focused PostgreSQL acceptance also passed |
| F33 live event store | tagged Kafka/ClickHouse replay | no endpoint configured | NOT_RUN (explicit test SKIP) | Separate F33 runtime gate; F34 acceptance itself is complete |
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
| F40.2 Core PostgreSQL/API | isolated `finance_test_f40_20261006`; focused report tests | working tree based on `f3da7a0` | PASS, 6 tests | Personal/family receipt scope, per-owner allow decision, null verdict/amount, 50,001-line cap, analytics outage, Telegram/shared report and existing local-date/rolling-food/digest regressions. |
| F40.2 Core suite | `:services:core:check --no-daemon` | working tree based on `f3da7a0` | PASS | Core compile, test and check tasks pass. PostgreSQL acceptance is recorded separately above. |
| F40.2 contracts | `.venv\\Scripts\\python.exe -m pytest tools/contracts/test_contracts.py -q -p no:cacheprovider` | working tree based on `f3da7a0` | PASS, 42 passed | FinanceReport now requires strict waste result schema; complete Go fixture and unavailable fallback validate. |
| F40.2 diff hygiene | `git diff --check` | working tree based on `f3da7a0` | PASS | No whitespace errors. |

## Failures and attempts

- First PostgreSQL attempt targeted shared `finance_test`, whose non-empty schema had no Flyway history. Retried on newly created, isolated `finance_test_f34_catalog_20261005`; targeted acceptance passed. The shared test database was not changed.
- F39 Web build first caught an inferred fixture type that rejected nullable totals; explicit `RecurringProjection` typing fixed the test-only issue, then 49 tests and build passed.
- F39 Android test first asserted a virtualized `LazyColumn` row before it was composed; `performScrollTo` could not target an off-screen row. A second attempt used an out-of-range item index. Root-cause review found the UI row existed but test navigation assumed visibility/index semantics. A minimal seven-item fixture and valid scroll to index 6 passed; full instrumentation then passed 42/42. One initial manual launch used the wrong activity package; manifest namespace confirmed `com.decorix.finance.MainActivity`, and corrected launch succeeded.
- F40.1 additional RED tests caught omitted/null required API fields returning 200, repeated products losing their product key, and Go byte-length checks rejecting valid Cyrillic names. Strict decoding, key preservation and Unicode character counts fixed all three.
- F40.2 decision overlay RED: the receipt input contained the expected member/product key, and PostgreSQL had the matching allowed decision, but Java's `user_id = ANY (?::uuid[])` lookup returned no overlay. Removing the array lookup and applying tenant-scoped rows against the bounded fact owner/key sets made the PostgreSQL acceptance pass. Facts: both SQL-array variants failed; direct RLS-scoped SQL found the decision; tenant-scoped lookup passed the same acceptance. Likely cause is JDBC UUID-array binding; no claim that the driver internals were independently proven.
- GitHub PostgreSQL run `37398375404` failed after the new receipt acceptance left a `receipt.confirmed` outbox event for the shared contract validator. `test_core_migration.py` mapped transaction, budget and debt events but omitted receipt. Added the receipt schema mapping. Local contract suite passes 54/2 skips; local integration test connects only after using the migrator role, but its cleaned database has no persisted events, so CI must verify event replay.

## Next action

- Commit and push F40.2; verify GitHub Core/PostgreSQL, bot and contract regression workflows, then start F40.3 presentation.

## E3.37 F40.2 Core optional-spend report — 2026-10-06 05:53 MSK

- Observed RED: authenticated report did not include `waste`. Added PostgreSQL/API acceptance for personal and family scope, owner-specific allow decisions, null verdict/amount and report usability during analytics outage. Added a 50,001-line fixture to prove overflow stays in Core and never reaches Go.
- Core now selects only confirmed receipt lines joined to posted expense transactions inside the exact local-date window. It computes each item key with the shared product identity policy, overlays allowed decisions by receipt owner, preserves nullable evidence and sends at most 50,000 rows to Go.
- Core ends its receipt/decision read transaction before HTTP. Query/analytics outage and overflow return an explicit partial unavailable DTO while normal finance totals remain available. Empty selections return complete `no_reviewed_items` without HTTP.
- GREEN: six focused PostgreSQL report/API tests; full `:services:core:check`; contracts 42 passed; `git diff --check` clean. CI remains pending.
- Root cause analysis: two failed Java array-filter variants omitted a stored allowed decision. Direct application-role SQL under the same tenant RLS context found it; tenant-scoped lookup followed by owner/key filtering passed. Most likely failure was PostgreSQL JDBC UUID-array binding. Further internal-driver debugging was unnecessary after the replacement passed the target test.
- Status: COMMIT_PENDING. Next: commit/push this goal and verify GitHub workflows before F40.3.

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
