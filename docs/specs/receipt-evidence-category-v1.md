# Receipt metadata and category safeguards v1 (F14)

## Evidence boundary

- Vision output is a reading candidate, not a verified receipt field. Its
  `store`, `date`, `total`, and item values remain in the private,
  owner-scoped reading record with provider/model/prompt provenance.
- A photo-processing job must not copy Vision claims into the receipt's
  merchant, date, or cash-total fields. Those draft fields stay `null` until
  the user supplies or verifies them. OCR raw text and coordinate evidence
  remain available for review.
- The Vision prompt requires visible values and `null` for unreadable values;
  schema validation rejects malformed dates and unsafe amounts. This does not
  promote model output to verified business data.

## Category fallback

- With no verified category evidence, category is `null` and source is
  `unknown`. No generic category or model guess is written as a fallback.
- Existing deterministic category rules may classify a reviewed receipt only
  when the cash total and all relevant item amounts are known. Missing amounts
  leave category shares unknown and do not trigger a rule promotion.
- Explicit user selection is marked `human` and wins over prior suggestions.

## Review UI and acceptance

- RU/EN review shows Vision store/date/total as an unverified reading, keeps
  the Vision source and original text visible, and labels a missing category
  source as unknown.
- PostgreSQL worker/API tests supply non-null model metadata and prove receipt
  merchant/date/total/category remain unknown while the source reading stays
  available to its owner. No financial transaction is created.
- Python Vision tests preserve unreadable store/date/total as `null`; category
  policy tests prove missing evidence does not invent a category or shares.
