# F45.4 Legacy Goal History Import — execution plan

**Global source:** `PLAN.md` sections 19.2–19.5 and F45. **Behavior contract:** `docs/superpowers/specs/2026-10-07-f45-legacy-goal-history-import-design.md`. **Existing lifecycle:** `docs/superpowers/plans/2026-10-07-f45-goal-lifecycle.md`.

## Task 1: Contract and service-authenticated API

1. Add OpenAPI assertions first for internal service auth, bounded batch, normalized legacy entry, nullable exact money/Boolean, and idempotent result counts.
2. Run focused contract test and observe RED.
3. Add Core/PostgreSQL API tests first for auth, tenant/member mapping, >24 rows, re-import idempotence, whole-batch validation, and no notification creation.
4. Run focused PostgreSQL tests and observe RED against isolated DB.
5. Implement dedicated migration-token endpoint and transactional importer. Validate every row before insert; use `ON CONFLICT ... DO NOTHING` on deterministic `legacy_key`.
6. Verify legacy outcome visibility and unrelated normal goal retention; run focused Core and contract gates.
7. Commit as `feat(F45.4): import legacy goal history` and push.

## Task 2: Read-only SQLite extractor and importer

1. Add extractor tests first for matching keys, exact legacy snapshots, duplicate rows, explicit manifest mapping, timezone conversion, DST gaps/overlaps, malformed entries, money precision, quarantine privacy, and source read-only behavior; observe RED.
2. Implement the SQLite reader with `mode=ro`; map each legacy user explicitly to tenant, member, and timezone. Never alter the source DB or infer missing values.
3. Add uploader tests with an injected HTTP sender; verify 500-row batches, token header, strict response accounting, HTTPS outside loopback, no redirect token leak, and safe full-batch replay.
4. Implement dry-run by default and explicit `--apply`. Require `--allow-quarantine` after review when uncertain rows remain. Never print token or raw goal data.
5. Add the migration test module to the Python CI contract job and document the manifest and invocation.

## Task 3: Regression and F45 completion

1. Add contract/migration checks proving no normal finance writes or notification paths are exposed.
2. Run full Core check with isolated PostgreSQL, all contracts/migrations, Telegram gateway, Go tests/vet, Web tests/build, and `git diff --check`.
3. Record evidence in `.agent/PROGRESS.md`; mark F45 complete only if the extractor preserves all valid rows and import is repeat-safe. No real legacy DB is present, so live data rehearsal remains unverified.

## Execution constraints

- Use strict test-first RED/GREEN and existing approved global migration design.
- Keep extractor-side parsing/timezone quarantine separate; this slice accepts only normalized, offset-aware entries.
- Stage only F45.4 files. Preserve all user-owned untracked files.
