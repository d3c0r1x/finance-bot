DROP INDEX tenant_budgets_family_unique_idx;
DROP INDEX tenant_budgets_personal_unique_idx;

ALTER TABLE tenant_budgets ADD COLUMN period varchar(16) NOT NULL DEFAULT 'monthly'
    CHECK (period IN ('monthly', 'rolling7'));
ALTER TABLE tenant_budgets ADD CONSTRAINT tenant_budgets_period_key_check
    CHECK (period = 'monthly' OR (period = 'rolling7' AND budget_key = 'еда'));

CREATE UNIQUE INDEX tenant_budgets_family_unique_idx
    ON tenant_budgets (tenant_id, budget_key, period) WHERE owner_user_id IS NULL;
CREATE UNIQUE INDEX tenant_budgets_personal_unique_idx
    ON tenant_budgets (tenant_id, owner_user_id, budget_key, period) WHERE owner_user_id IS NOT NULL;
