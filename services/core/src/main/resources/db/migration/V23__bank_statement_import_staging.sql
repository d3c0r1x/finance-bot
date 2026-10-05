CREATE TABLE bank_imports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    owner_user_id uuid NOT NULL REFERENCES users(id),
    owner_subject varchar(255) NOT NULL,
    parser_version varchar(64) NOT NULL CHECK (parser_version = 'tbank-pdf.v1'),
    quality varchar(16) NOT NULL CHECK (quality IN ('valid', 'mismatch', 'unverifiable')),
    period_start date NOT NULL,
    period_end date NOT NULL,
    parsed_expense_total numeric(20,2) NOT NULL CHECK (parsed_expense_total >= 0),
    parsed_income_total numeric(20,2) NOT NULL CHECK (parsed_income_total >= 0),
    expected_expense_total numeric(20,2) CHECK (expected_expense_total >= 0),
    expected_income_total numeric(20,2) CHECK (expected_income_total >= 0),
    state varchar(24) NOT NULL DEFAULT 'needs_review'
        CHECK (state IN ('needs_review', 'ready', 'committed', 'reverted', 'failed')),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    CHECK (period_end >= period_start)
);

CREATE INDEX bank_imports_owner_idx ON bank_imports (tenant_id, owner_user_id, created_at DESC);

CREATE TABLE bank_import_rows (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    import_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    operation_date date NOT NULL,
    operation_time time NOT NULL,
    signed_amount numeric(20,2) NOT NULL CHECK (signed_amount <> 0),
    currency char(3) NOT NULL DEFAULT 'RUB' CHECK (currency = 'RUB'),
    kind varchar(32) NOT NULL CHECK (kind IN ('purchase', 'refund', 'income', 'transfer_out', 'internal', 'withdrawal', 'fee')),
    merchant varchar(200),
    description varchar(2048) NOT NULL,
    card_last4 char(4),
    selected_type varchar(16) CHECK (selected_type IN ('expense', 'income', 'refund', 'transfer')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, import_id, ordinal),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, import_id) REFERENCES bank_imports(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX bank_import_rows_order_idx ON bank_import_rows (tenant_id, import_id, ordinal);

ALTER TABLE bank_imports ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_imports FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_imports
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE bank_import_rows ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_import_rows FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bank_import_rows
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
