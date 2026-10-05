CREATE TABLE debts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    name varchar(120) NOT NULL CHECK (length(trim(name)) > 0),
    opening_balance numeric(20,2) NOT NULL CHECK (opening_balance > 0),
    current_balance numeric(20,2) NOT NULL CHECK (current_balance >= 0),
    interest_rate numeric(7,4) CHECK (interest_rate IS NULL OR interest_rate >= 0),
    minimum_payment numeric(20,2) NOT NULL DEFAULT 0 CHECK (minimum_payment >= 0),
    status varchar(16) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    CHECK (status <> 'closed' OR current_balance = 0)
);

CREATE INDEX debts_tenant_status_idx ON debts (tenant_id, status, created_at);
ALTER TABLE debts ENABLE ROW LEVEL SECURITY;
ALTER TABLE debts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON debts
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE transactions ADD COLUMN debt_id uuid;
ALTER TABLE transactions ADD COLUMN debt_balance_effect numeric(20,2) NOT NULL DEFAULT 0 CHECK (debt_balance_effect >= 0);
ALTER TABLE transactions ADD CONSTRAINT transactions_tenant_debt_fk
    FOREIGN KEY (tenant_id, debt_id) REFERENCES debts(tenant_id, id);
CREATE INDEX transactions_tenant_debt_idx ON transactions (tenant_id, debt_id) WHERE debt_id IS NOT NULL;
