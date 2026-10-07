# F52 CSV export design

## Goal

Replace the legacy desktop export with an authenticated Web export. Preserve its Excel-facing CSV format while adding the approved tenant/member filters, durable job status, stable data boundary, and short-lived download access.

## Legacy format contract

- Output is UTF-8 with a BOM and `;` delimiter.
- Columns use this legacy order and Russian labels: `Дата`, `Сумма`, `Категория`, `Подкатегория`, `Описание`, `Тип`, `Долг`, `Источник`, `Telegram ID`.
- Date text uses `yyyy-MM-dd HH:mm` in the requester's configured timezone. One export uses one timezone, including tenant-wide scope.
- Amount is exact positive decimal text with two fraction digits. It remains a numeric spreadsheet cell.
- Text cells that can begin an Excel formula are prefixed with an apostrophe. Numeric amount is never changed by text escaping.
- CSV quoting follows the standard delimiter/quote/newline rules. Export uses one format version, `csv-v1`.

## Scope and access

- Authenticated member can export own transactions. Tenant owner/admin can select one active member or all members. Other members cannot widen scope.
- Filters use explicit local `fromDate`/`toDate`, optional transaction type, and member scope. Defaults are finite and visible in the request/response; no unbounded browser download.
- Export includes only posted transactions visible to the requester at the captured snapshot. Voided rows are excluded. Amounts and row identity come from Core; worker does not become a finance-data writer.
- `POST /api/v1/tenants/{tenantId}/exports` creates a durable job and `jobs.export.v1` outbox event. `GET /api/v1/tenants/{tenantId}/exports/{exportId}` returns scoped status and, only while ready, a short-lived download URL.
- Worker uses a narrow Core service credential and authorized job ID. Object keys are random and tenant-scoped. Core verifies ownership before issuing a signed URL. URL lifetime is bounded; no public bucket or permanent object URL.
- Job is idempotent by export ID and format version. Retry must not expose partial objects as ready. Expired or failed jobs expose no download URL.

## Snapshot and resource bounds

- Job records normalized filters, requester, selected member scope, format version, row watermark, and lifecycle timestamps.
- Worker streams rows in stable keyset order, validates each page against the authorized job, and never loads complete history into RAM.
- The implementation must demonstrate how its watermark prevents rows created after the request from entering the export. If edits/voids can change rows inside a running export, preserve the request-time state through an immutable snapshot or equivalent verified mechanism; do not call a creation-time filter a stable snapshot without proof.
- Request and worker enforce a documented maximum date range/row count and terminal failure on excess. Never silently truncate CSV.

## Side effects and privacy

- Export creates no transaction, receipt, debt, goal, notification, or billing mutation.
- Job status and download access are tenant-scoped and audited. Logs contain job ID, counts, format, and error code only; never row data, URL signature, or credentials.
- There is no Telegram export entry point.

## Acceptance

1. Golden CSV fixture matches BOM, delimiter, exact Russian headers/order, timezone date, quotes/newlines, and format version.
2. Formula-like text is escaped; exact amount remains unchanged and numeric.
3. Invalid filters, excessive date ranges, and unauthorized member scopes are rejected before job creation.
4. Job creation and outbox publish are atomic; job status is isolated by tenant and member role.
5. Worker streams a stable request-time set, is idempotent on retries, and never marks a partial object ready.
6. Only authorized callers can obtain a short-lived URL; expired, failed, and cross-tenant requests return no URL.
7. Web supports create, progress, empty result, download, retryable failure, RU/EN labels, and permission states.
8. No finance rows change. F52 is code-complete only after local Core/PostgreSQL, Go, Web, contract, and export-worker tests pass. Live object-storage delivery remains a separate runtime proof if no real storage service is available.
