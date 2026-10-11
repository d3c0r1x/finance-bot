# F45 Goal Lifecycle — execution plan

**Global source:** `PLAN.md` F45. **Behavior contract:** `.agent/specs/F45-goal-lifecycle.md`. **Legacy reference:** `services/goals.py`. Keep F46 delivery separate.

## F45.1 Go progress calculator and contract

- [x] Add calculation tests first for product/group membership, exact 30-day bounds, as-of cutoff, count/sum, unknown amounts, completion boundary and invalid/oversized inputs; observe compile RED.
- [x] Add authenticated strict internal API tests first; observe RED for missing F45 handler.
- [x] Add nullable exact-money progress calculator and token-protected endpoint; register route.
- [x] Extend F44 group proposals with a deterministic eligible member-key snapshot. Test confirmed decisions are included and user-allowed products are excluded.
- [x] Add OpenAPI schemas and strict contract acceptance tests for F45 progress and group membership.
- [x] Run Go tests/vet, all contract/migration tests, and `git diff --check`.
- [x] Commit F45.1 as `37f84ba`; record evidence in `.agent/PROGRESS.md` (`37f84ba`), pushed to `origin/feat/saas-rewrite`. GitHub created no workflow run for this SHA.

## F45.2 Core durable progress and lifecycle

- [x] Add PostgreSQL API acceptance tests first for product/group progress, accepted member-key snapshot, null amounts, cancellation exclusion, exactly-once close, history retention and owner/viewer/tenant boundaries.
- [x] Observe RED on the isolated finance test database before migration or Core changes.
- [x] Add progress client validation, member-only purchase snapshot and accepted group membership persistence.
- [x] Add durable goal outcome/history, atomic completed status + audit/outbox, idempotent retries, cancellation semantics, and current candidates after a finished/cancelled goal.
- [x] Run focused PostgreSQL/API, full Core checks, migrations/contracts and `git diff --check`.
- [ ] Add/import legacy history through the J migration pipeline. V40 and history reads preserve all `origin='legacy'` rows, but the repository has no migration ingestion route yet; F45 stays open until the source mapping and import acceptance exist.
- [x] Commit F45.2 as `afcde2d`; record evidence. Legacy history ingestion remains an explicit unchecked dependency on J, so F45 is not yet complete.

## F45.3 Web progress, note and history

- [x] Add component tests first for progress changes after purchase refresh, unknown money, completion/history, cancellation, next candidate, RU/EN and viewer permissions.
- [x] Implement progress, read-only purchase note, retained history and proposal access.
- [x] Refresh goal progress cache after a receipt is confirmed.
- [x] Run focused/all Web tests, production build, relevant Core/contract regression and `git diff --check`.
- [x] Commit F45.3 as `85c30d1` and record evidence. F45 remains open only for legacy-history ingestion through J.

## F45.5 Android progress, purchase note and history

- [x] Add Compose and tenant-freshness tests first; observe RED for missing Android progress/history rendering and confirmed-receipt cache invalidation.
- [x] Render Core `activeProgress` and every `history` row in the returned order; preserve nullable money/verdicts and show current candidates after completion without auto-accept.
- [x] Invalidate only the matching tenant's cached goal overview after a successful, current-session receipt confirmation. Re-entering Goals fetches fresh Core progress; failed confirmation leaves cached state unchanged.
- [x] Run F45 tests, F43/F42/report/model regression, contracts, unit policy tests, APK build/API 27 launch and `git diff --check`.
- [x] Record evidence and commit/push the Android slice as `dee2325`. Keep live OIDC parity partial and E8 legacy import separate.
