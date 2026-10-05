ALTER TABLE bank_import_rows
    ADD COLUMN reclassification_version bigint
        CHECK (reclassification_version IS NULL OR reclassification_version > 0);

UPDATE bank_import_rows r
SET reclassification_version = t.version
FROM transactions t
WHERE r.tenant_id = t.tenant_id
  AND r.transaction_id = t.id
  AND r.outcome = 'created'
  AND r.category_code IS NULL
  AND t.source = 'bank_import'
  AND t.status = 'posted'
  AND t.version = 1;

CREATE INDEX bank_import_rows_reclassification_idx
    ON bank_import_rows (tenant_id, transaction_id)
    WHERE outcome = 'created' AND reclassification_version IS NOT NULL;
