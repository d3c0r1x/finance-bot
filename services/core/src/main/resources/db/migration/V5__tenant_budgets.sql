CREATE TABLE tenant_budgets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    owner_user_id uuid,
    budget_key varchar(64) NOT NULL,
    amount numeric(20,2) NOT NULL CHECK (amount >= 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    updated_by varchar(255) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, owner_user_id) REFERENCES memberships(tenant_id, user_id),
    CHECK (budget_key = '__total__' OR length(trim(budget_key)) > 0)
);

CREATE UNIQUE INDEX tenant_budgets_family_unique_idx
    ON tenant_budgets (tenant_id, budget_key) WHERE owner_user_id IS NULL;
CREATE UNIQUE INDEX tenant_budgets_personal_unique_idx
    ON tenant_budgets (tenant_id, owner_user_id, budget_key) WHERE owner_user_id IS NOT NULL;

ALTER TABLE tenant_budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_budgets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_budgets
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
