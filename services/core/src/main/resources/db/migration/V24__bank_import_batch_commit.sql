ALTER TABLE bank_imports
    ADD COLUMN commit_idempotency_key varchar(128),
    ADD COLUMN undo_idempotency_key varchar(128),
    ADD COLUMN created_count integer NOT NULL DEFAULT 0 CHECK (created_count >= 0),
    ADD COLUMN duplicate_count integer NOT NULL DEFAULT 0 CHECK (duplicate_count >= 0),
    ADD COLUMN reverted_count integer NOT NULL DEFAULT 0 CHECK (reverted_count >= 0),
    ADD COLUMN committed_at timestamptz,
    ADD COLUMN reverted_at timestamptz;

ALTER TABLE bank_import_rows
    ADD COLUMN outcome varchar(16) NOT NULL DEFAULT 'pending'
        CHECK (outcome IN ('pending', 'created', 'duplicate', 'excluded', 'reverted')),
    ADD COLUMN transaction_id uuid,
    ADD COLUMN duplicate_of_transaction_id uuid,
    ADD CONSTRAINT bank_import_row_transaction_fk
        FOREIGN KEY (tenant_id, transaction_id) REFERENCES transactions(tenant_id, id),
    ADD CONSTRAINT bank_import_row_duplicate_transaction_fk
        FOREIGN KEY (tenant_id, duplicate_of_transaction_id) REFERENCES transactions(tenant_id, id),
    ADD CONSTRAINT bank_import_row_outcome_links CHECK (
        (outcome = 'created' AND transaction_id IS NOT NULL AND duplicate_of_transaction_id IS NULL)
        OR (outcome = 'duplicate' AND transaction_id IS NULL AND duplicate_of_transaction_id IS NOT NULL)
        OR (outcome IN ('pending', 'excluded') AND transaction_id IS NULL
            AND duplicate_of_transaction_id IS NULL)
        OR (outcome = 'reverted' AND duplicate_of_transaction_id IS NULL)
    );

CREATE TABLE bank_import_dedupe_keys (
    tenant_id uuid NOT NULL,
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    occurrence_no integer NOT NULL CHECK (occurrence_no > 0),
    import_id uuid NOT NULL,
    row_id uuid NOT NULL,
    transaction_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, fingerprint, occurrence_no),
    UNIQUE (tenant_id, import_id, row_id),
    FOREIGN KEY (tenant_id, import_id) REFERENCES bank_imports(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, row_id) REFERENCES bank_import_rows(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, transaction_id) REFERENCES transactions(tenant_id, id)
);

CREATE INDEX bank_import_dedupe_transaction_idx
    ON bank_import_dedupe_keys (tenant_id, transaction_id);

ALTER TABLE bank_import_dedupe_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_import_dedupe_keys FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_import_dedupe_keys
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
