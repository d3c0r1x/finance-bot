ALTER TABLE transaction_drafts
    DROP CONSTRAINT transaction_drafts_type_check;

ALTER TABLE transaction_drafts
    ADD CONSTRAINT transaction_drafts_type_check
    CHECK (type IN ('expense', 'income', 'debt_payment'));
