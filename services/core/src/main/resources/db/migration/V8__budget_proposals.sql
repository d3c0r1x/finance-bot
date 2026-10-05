CREATE TABLE budget_proposals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    created_by varchar(255) NOT NULL,
    monthly_income numeric(20,2) NOT NULL CHECK (monthly_income > 0),
    total_limit numeric(20,2) NOT NULL CHECK (total_limit >= 0),
    proposed_limits jsonb NOT NULL CHECK (jsonb_typeof(proposed_limits) = 'object'),
    base_versions jsonb NOT NULL CHECK (jsonb_typeof(base_versions) = 'object'),
    base_total_version bigint NOT NULL CHECK (base_total_version >= 0),
    status varchar(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'applied', 'expired')),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '30 days'),
    applied_at timestamptz,
    UNIQUE (tenant_id, id)
);

CREATE INDEX budget_proposals_pending_idx ON budget_proposals (tenant_id, created_at DESC)
    WHERE status = 'pending';

ALTER TABLE budget_proposals ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_proposals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON budget_proposals
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
