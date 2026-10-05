ALTER TABLE bank_import_dedupe_keys
    DROP CONSTRAINT bank_import_dedupe_keys_tenant_id_transaction_id_fkey;

ALTER TABLE bank_import_dedupe_keys
    ADD CONSTRAINT bank_import_dedupe_transaction_fk
        FOREIGN KEY (tenant_id, transaction_id) REFERENCES transactions(tenant_id, id)
        DEFERRABLE INITIALLY DEFERRED;
