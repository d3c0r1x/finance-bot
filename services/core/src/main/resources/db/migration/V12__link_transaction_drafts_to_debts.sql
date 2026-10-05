ALTER TABLE transaction_drafts ADD COLUMN debt_id uuid;
ALTER TABLE transaction_drafts
    ADD CONSTRAINT transaction_drafts_tenant_debt_fk
    FOREIGN KEY (tenant_id, debt_id) REFERENCES debts(tenant_id, id);
ALTER TABLE transaction_drafts
    ADD CONSTRAINT transaction_drafts_debt_type_check
    CHECK (type = 'debt_payment' OR debt_id IS NULL);
