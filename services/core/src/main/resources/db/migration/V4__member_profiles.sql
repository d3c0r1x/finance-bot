CREATE TABLE member_profiles (
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    user_id uuid NOT NULL REFERENCES users(id),
    display_name varchar(120) NOT NULL DEFAULT '' CHECK (length(display_name) <= 120),
    planned_income numeric(20,2) CHECK (planned_income IS NULL OR planned_income > 0),
    onboarding_state varchar(24) NOT NULL DEFAULT 'started'
        CHECK (onboarding_state IN ('started', 'complete')),
    goal_unit varchar(24) NOT NULL DEFAULT 'RUB',
    timezone varchar(64) NOT NULL DEFAULT 'UTC',
    currency char(3) NOT NULL DEFAULT 'RUB' CHECK (currency = 'RUB'),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships(tenant_id, user_id)
);

ALTER TABLE member_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_profiles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member_profiles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Let a user discover only their own tenant IDs before setting tenant context.
DROP POLICY tenant_isolation ON memberships;
CREATE POLICY tenant_isolation ON memberships
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
           OR subject = nullif(current_setting('app.subject', true), ''))
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
