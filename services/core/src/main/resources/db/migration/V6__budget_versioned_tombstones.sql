-- Keep a revision after reset so the next optimistic update has a stable version.
ALTER TABLE tenant_budgets ADD COLUMN active boolean NOT NULL DEFAULT true;
