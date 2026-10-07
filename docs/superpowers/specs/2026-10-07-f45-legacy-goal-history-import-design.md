# F45.4 Legacy Goal History Import

## Goal

Import every saved legacy `advice:goal_history:{id}` row into `goal_outcomes` as `origin='legacy'`. Preserve rows independently from the 24-row retention limit for newly completed goals. Re-importing the same source entry must not create a duplicate.

## Approved source behavior

The old history is a JSON list. Each row contains `key`, `name`, `unit`, `target`, `limit`, `bought`, `spent`, `met`, `saved`, `window`, `started_at`, and `closed_at`. Invalid JSON and non-object/name-less entries were ignored by the old reader; the migration extractor must report and quarantine invalid source entries rather than silently discard them. Date strings are naive local timestamps, so the extractor must resolve them with the member's known timezone and send offset-aware timestamps. Missing/invalid timezone is quarantine, not guessed UTC.

## API and persistence

- Add a service-token-only internal endpoint dedicated to migration imports. It is inaccessible through member or BFF routes.
- Request identifies mapped `tenantId` and `ownerUserId`, and carries a bounded array of normalized history entries. Core verifies membership exists in that tenant.
- Each entry contains a deterministic source `legacyKey`, normalized fields, preserved `legacyTarget`/`legacyLimit`, and offset-aware `acceptedAt` / `completedAt`.
- Core validates the full request before writing. Any invalid row rejects the whole batch; no partial imports.
- Insert into `goal_outcomes` with `origin='legacy'`, `goal_id=NULL`, immutable goal/progress JSON snapshots, and the legacy timestamps. Unique `(tenant_id, owner_user_id, legacy_key)` makes repeats idempotent.
- Return inserted and already-present counts. The migration route emits no Telegram notification, finance transaction, or normal user audit/outbox event.
- Imported legacy rows remain readable even when there are more than 24; normal completed outcomes retain their existing 24-row limit.

## Boundaries

- The read-only extractor reads SQLite `settings` keys `advice:goal_history:{legacyUserId}`, maps each legacy user through an explicit manifest, and calls this narrow API with its migration credential.
- The extractor quarantines malformed JSON/rows, unknown users, and missing/ambiguous timezone mappings. It never guesses ownership or UTC for naive local dates.
- Duplicate-identical source rows receive deterministic occurrence suffixes so each saved row survives while repeat runs remain idempotent.
- The extractor supports dry-run reports and batches up to 500 entries per mapped owner; resume is safe because Core import is idempotent.
- Do not add a Redis reader or production bulk-loader credential to Core. The J migration extractor calls this narrow API with its migration credential.
- Do not infer user ownership, timezone, money precision, or missing timestamps. Quarantine uncertain source data upstream.
- Do not rewrite existing legacy rows or announce them through F46.

## Acceptance

1. Service credential is required; invalid credentials return 401 and missing configuration returns 503.
2. A valid batch imports all supplied rows, including more than 24, and maps legacy snapshots correctly.
3. Replaying a batch reports existing rows and inserts zero duplicates.
4. One malformed row rejects the full batch without partial inserts.
5. A second tenant/member cannot read or overwrite the imported rows.
6. Import creates no notification intents and does not alter transactions.
7. OpenAPI documents the internal request/response and exact date/amount constraints.
