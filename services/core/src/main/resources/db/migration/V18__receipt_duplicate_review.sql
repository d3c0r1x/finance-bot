ALTER TABLE receipts
    ADD COLUMN duplicate_decision varchar(16) NOT NULL DEFAULT 'unknown'
        CONSTRAINT receipts_duplicate_decision_value_check
        CHECK (duplicate_decision IN ('unknown', 'independent', 'duplicate')),
    ADD COLUMN duplicate_of_receipt_id uuid,
    ADD CONSTRAINT receipts_duplicate_parent_fk
        FOREIGN KEY (tenant_id, duplicate_of_receipt_id) REFERENCES receipts (tenant_id, id),
    ADD CONSTRAINT receipts_duplicate_decision_check CHECK (
        (duplicate_decision = 'duplicate' AND duplicate_of_receipt_id IS NOT NULL AND duplicate_of_receipt_id <> id)
        OR (duplicate_decision <> 'duplicate' AND duplicate_of_receipt_id IS NULL)
    );

CREATE INDEX receipts_duplicate_review_idx
    ON receipts (tenant_id, owner_user_id, cash_total, created_at DESC)
    WHERE state <> 'cancelled' AND cash_total IS NOT NULL;
