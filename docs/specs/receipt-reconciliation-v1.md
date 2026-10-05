# Receipt OCR and Vision reconciliation v1 (F13)

## Input evidence

- Tesseract returns the existing raw text and coordinate-bearing words plus
  deterministic candidate rows. A candidate row needs a non-service item name
  and an amount in the inferred line-total column. Receipt total comes only
  from a labeled total row. Unknown quantity, price, total, merchant and date
  remain null; the extractor does not fill them by arithmetic.
- Vision and OCR stay as separate immutable readings with their own reader,
  model and prompt provenance. Core validates both schemas before storage.
- Money uses exact decimal values. Item totals reconcile within the existing
  tolerance `max(2 RUB, 3% of receipt total)`; line corroboration uses a
  two-kopeck amount tolerance.

## Reconciliation

- Normalize names for comparison, then match each Vision item to at most one
  OCR row. A used OCR row cannot support another item, including repeated item
  names. Evidence identifies both ordinals and whether the amounts corroborate,
  disagree, are unknown, or appear in only one reader.
- Compare receipt totals and any present merchant/date fields. Conflicts or
  unexplained arithmetic require review; missing evidence is not treated as
  zero and never becomes confirmation.
- When Vision's item sum has a positive gap, consider unmatched OCR rows as a
  top-up only when one unique combination of at most three rows (from at most
  30 candidates) closes the gap within two kopecks. Totals must agree and OCR
  must not already reconcile. Every proposal keeps the OCR ordinal and value;
  an ambiguous match yields no proposal. Suggestions never change the reading
  selection or draft by themselves.
- Core keeps both source readings and exposes the deterministic reconciliation
  result. The Web draft shows selected reader, decision, mismatches, one-to-one
  evidence, suggested top-ups and model versions. The user may edit the draft
  through its item editor; explicit receipt confirmation remains the only path
  to a financial transaction.

## Acceptance

- Synthetic OCR layouts extract only named item rows and a labeled total; totals
  and missing fields are never invented.
- Java policy tests prove exact tolerance, one-to-one matching, item-level
  conflicts, and bounded unique top-up. PostgreSQL acceptance exercises both
  readings through the worker and owner-scoped read API.
- RU/EN Web tests show reconciliation decisions and source provenance. No
  receipt-processing code creates a transaction.
