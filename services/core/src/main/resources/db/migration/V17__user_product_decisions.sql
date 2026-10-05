CREATE TABLE user_product_decisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    product_key varchar(256) NOT NULL CHECK (product_key ~ '^[a-zа-я0-9]{1,256}$'),
    decision varchar(16) NOT NULL CHECK (decision IN ('allowed', 'confirmed')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, user_id, product_key),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE
);

CREATE INDEX user_product_decisions_lookup_idx ON user_product_decisions (tenant_id, user_id, decision, product_key);

ALTER TABLE user_product_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_product_decisions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_product_decisions
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE user_product_decision_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    product_key varchar(256) NOT NULL CHECK (product_key ~ '^[a-zа-я0-9]{1,256}$'),
    before_decision varchar(16) CHECK (before_decision IN ('allowed', 'confirmed')),
    after_decision varchar(16) CHECK (after_decision IN ('allowed', 'confirmed')),
    actor_subject varchar(255) NOT NULL,
    action varchar(32) NOT NULL CHECK (action IN ('allowed', 'revoked')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE,
    CHECK (before_decision IS DISTINCT FROM after_decision)
);

CREATE INDEX user_product_decision_events_lookup_idx
    ON user_product_decision_events (tenant_id, user_id, product_key, created_at DESC);

ALTER TABLE user_product_decision_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_product_decision_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_product_decision_events
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
