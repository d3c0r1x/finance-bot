# F51 Android merchant reclassification

## Authority and scope

- Parent requirement: approved `PLAN.md` F51, “Уточнение магазина меняет ранее импортированные подходящие записи”.
- Core and `docs/specs/merchant-classification-v1.md` own eligibility, tenant/member scope, category mapping, candidate versions, locking, and mutation results.
- Android adds a separate preview and explicit apply flow for previously imported transactions. Saving an F50 merchant mapping never changes prior transactions by itself.
- Do not widen the candidate list, infer candidates from the import screen, or reclassify transactions locally. Do not add F51 behavior to the F50 category-selection callback.

## Core contract

- Preview uses authenticated `POST /api/v1/tenants/{tenantId}/merchant-reclassifications/preview` with `{ "merchant": "..." }`.
- Core returns `merchant`, `normalizedMerchant`, the current mapped `categoryCode`, and ordered `candidates`. Each candidate has `transactionId`, positive `version`, `currentCategoryCode`, exact decimal `amount` string, `occurredAt`, and `description`.
- Preview includes only this tenant member's committed bank-import expenses whose category remains managed by the importer. Core excludes duplicates, reverted rows, income/refunds, rows with a manual category choice, and transactions changed outside an earlier managed reclassification.
- Apply uses authenticated `POST /api/v1/tenants/{tenantId}/merchant-reclassifications/apply`, an `Idempotency-Key`, and the reviewed `merchant`, target `categoryCode`, and exact reviewed candidate set. Each submitted candidate contains only its reviewed `transactionId`, `version`, and `currentCategoryCode`.
- Core reloads and locks the mapping and candidate rows. A changed rule or candidate set rejects the whole apply with `412`; it never partially overwrites a transaction. A retry succeeds only when every reviewed row already has the managed result.
- Successful apply follows Core's normal transaction update path, increments versions, and writes audit/outbox records atomically.

## Acceptance

1. Android models strictly parse the Core preview and result. Preserve candidate order, UUIDs, integer versions, current category, exact money strings, timestamps, and descriptions. Reject malformed or incomplete required data rather than fabricating a candidate or default category.
2. Only a role allowed to write can request review or apply. Viewers see no review/apply controls. Tenant and auth-session identity are captured at request start; a response from a replaced session, tenant, import screen, or request generation cannot change current UI state.
3. Requesting review is a read-only preview operation. Show the Core-returned merchant, target category, and every returned candidate with description, date, exact amount, and old-to-new category. An empty preview shows an empty state and has no enabled Apply action. Never add or remove candidates using client-side rules.
4. Apply requires a separate user tap after a non-empty preview. Submit exactly the reviewed candidate IDs, versions, and current categories; do not silently refresh and apply a replacement list. Saving an F50 mapping alone must not call Apply.
5. Disable review and apply controls while work is pending. Do not replay Apply automatically after `401`, session replacement, timeout, or ambiguous transport failure. Keep any retry bound to the same reviewed payload and idempotency key; otherwise fetch a fresh preview and ask the user to review it again.
6. A Core `412` marks the preview stale, shows localized recovery guidance, and disables Apply until a fresh preview is explicitly requested and reviewed. Never display success for a stale response. Manual transaction edits remain unchanged; Core is the final protection against a stale Android screen.
7. Handle `403`, empty candidates, malformed response, and unknown future error codes with safe localized messages. Never show raw response bodies, access tokens, tenant identifiers, or machine stack traces to the user.
8. Android displays no reclassification results until Core returns success. On success, use Core's `changedCount` and transaction IDs; do not calculate changed totals or claim that saving a mapping caused the change.
9. Keep this feature separate from F50 import hypotheses/category selection and F48/F49 import confirmation/undo. Offer review only from committed importer-mapped rows (`categorySource=mapping`); do not offer it for manually categorized (`human`), duplicate, reverted, non-expense, or otherwise omitted rows. Core remains authoritative for the actual historic candidate set.

## Test-first evidence

- Add DTO tests first: exact candidate preservation and rejection of invalid UUID, non-positive/fractional version, unsupported category, malformed amount/date, missing required fields, and duplicate IDs.
- Add API tests next: exact preview route/body/auth; exact apply route, idempotency header, and reviewed candidate payload; `412` and `401` are returned without Apply replay or an implicit replacement preview.
- Add Compose tests next: explicit preview; Core-only exact candidate list; empty-preview Apply disabled; separate Apply tap sends the reviewed object; viewer/busy gates; stale preview disables Apply and requires fresh explicit review; no synthesized manually edited candidate.
- Observe failures tied to absent F51 Android behavior before production edits. Keep synthetic fixtures free of real transactions or personal data.

## Verification limits

Local Android tests prove request construction, parsing, and UI behavior only. They do not replace the existing Core/PostgreSQL tests for locking, atomicity, tenant/member isolation, idempotency, or preservation of a real manual edit. Keep live authenticated Android-to-Core/OIDC and tenant-isolation delivery evidence separate.
