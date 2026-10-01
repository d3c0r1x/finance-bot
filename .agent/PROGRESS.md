# Execution progress

Updated: 2026-10-01, Europe/Moscow.

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
| E2.2 | React OIDC login, dashboard, transaction form/history and profile | PLANNED | E2.1 | none |
| E2.3 | Android OIDC login and create/list against the Java API | PLANNED | E2.1 | none |
| E2 | Web/profile plus Android first API flow | IN PROGRESS | E1 | none |
| E3–E10 | Budgets/debts through production delivery, migration, parity and release | PLANNED | Prior stage gates | none |

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

## Next action

Continue E2.2: build React OIDC/session path, dashboard and transaction
create/history using the Java API; then E2.3 Android login and create/list.
