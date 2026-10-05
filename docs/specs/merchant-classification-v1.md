# Merchant classification v1

Merchant labels are normalized with Unicode NFKC, case folding, and whitespace
collapse. A category chosen by the current tenant member is authoritative over
any model suggestion. Personal mappings are keyed by tenant, user, and normalized
merchant and survive service restarts.

The allowed categories are `еда`, `транспорт`, `жилье`, `досуг`, `одежда`,
`здоровье`, `работа`, `техника`, `долги`, and `прочее`. A missing or unusable
suggestion resolves to `прочее`. Model output is limited to batches of 50 unique
merchants and must include an allowed category and finite confidence from 0 to 1.
The AI receives only merchant names, never transaction amounts, descriptions,
tenant IDs, or user IDs. Valid suggestions are cached per tenant member for 90
days under the prompt version. A personal mapping always takes precedence.

Import clarification aggregates included purchase amounts by normalized merchant.
Only unmapped merchants with at least 300 RUB are candidates; candidates are
ordered by descending spend and then normalized name, and at most six are shown.
The web preview presents model output as a hypothesis and lets the member choose
a category and save that choice as a personal mapping. Import confirmation remains
explicit. Reclassification of already posted transactions is specified separately
in F51 and is not performed by F50.

## Historical transaction reclassification (F51)

Reclassification is a separate, explicit preview/apply flow. The current
tenant-member merchant mapping supplies the target category. Preview includes
only that member's committed bank-import expenses created by the importer whose
category is still managed by the importer. It excludes duplicate, reverted,
income/refund, manually categorized import rows, and any transaction whose
version changed outside the previous managed reclassification.

Apply submits the exact transaction IDs, versions, and current categories shown
in preview. Core locks and reloads the mapping and candidate rows, then rejects
the whole operation with `412` if the rule or candidate set changed. A user's
transaction edit therefore cannot be overwritten by a stale preview. Successful
changes go through the normal transaction update path, increment versions, and
write the usual audit and outbox records in the same database transaction. A
retry is accepted only when all reviewed rows already match the managed result.

Mapping updates, reclassification, and import confirmation share a PostgreSQL
transaction-scoped lock keyed by tenant, member, and normalized merchant. This
serializes a rule change or a newly committed matching expense against Apply;
imports acquire multiple merchant locks in sorted order. Browser endpoints
require the BFF session and CSRF token, while Apply also requires an
idempotency key. The Web UI displays the reviewed list and asks for a separate
Apply action; saving a mapping alone never changes prior transactions.
