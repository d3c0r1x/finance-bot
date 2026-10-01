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
- Original main and separate existing checkout are preserved.

## Goals

| ID | Requirement | State | Dependencies | Commit |
|---|---|---|---|---|
| E0.1 | Restore approved plan, supplement Android/AI and record Keycloak choice | COMPLETE | GitHub clone | d19269f |
| E0.2 | Legacy baseline, F/D registry, mobile delta inventory | COMPLETE | E0.1 | d19269f |
| E0.3 | Version matrix, foundational ADRs and initial contracts | COMPLETE | E0.2 | d19269f |
| E1.1 | Java build foundation and positive decimal money value | COMPLETE | E0 | pending |
| E1.2 | PostgreSQL schema/migration, RLS tenant isolation, idempotency/audit/outbox persistence | COMPLETE | E1.1 + PostgreSQL runtime | pending |
| E1.3 | Idempotent transaction HTTP API, membership authorization, atomic business/outbox writes | PLANNED | E1.2 | none |
| E1 | Complete core vertical slice (E1.1–E1.2) | PLANNED | E0 | none |
| E2–E10 | Remaining stages of global plan, including Android supplement | PLANNED | Prior stage gates | none |

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

## Environment observations

- `java`, `mvn`, `gradle`, `docker`, `psql`, `go`, Android SDK absent from PATH.
- Standard Docker Desktop executable path absent. Search for installed/portable
  runtimes before deciding whether PostgreSQL integration work is blocked.

## Next action

Begin E1.3 with a red test for authenticated tenant transaction create/list,
  idempotent replay/conflict, and atomic audit/outbox behavior. Add Keycloak JWT
  subject membership checks before setting transaction-local tenant context.
