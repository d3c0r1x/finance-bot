CREATE TABLE transaction_drafts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    owner_user_id uuid NOT NULL REFERENCES users(id),
    owner_subject varchar(255) NOT NULL,
    type varchar(16) NOT NULL CHECK (type IN ('expense', 'income')),
    amount numeric(20,2) NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL DEFAULT 'RUB' CHECK (currency = 'RUB'),
    category_code varchar(64) NOT NULL CHECK (length(trim(category_code)) > 0),
    subcategory_code varchar(64),
    description varchar(500) NOT NULL DEFAULT '',
    occurred_at timestamptz NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'confirmed', 'cancelled')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    provider varchar(64) NOT NULL,
    model_version varchar(128) NOT NULL,
    prompt_version varchar(64) NOT NULL,
    create_idempotency_key varchar(128) NOT NULL,
    create_request_hash char(64) NOT NULL,
    confirm_idempotency_key varchar(128),
    transaction_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, owner_user_id, create_idempotency_key),
    FOREIGN KEY (tenant_id, transaction_id) REFERENCES transactions(tenant_id, id),
    CHECK ((state = 'confirmed' AND transaction_id IS NOT NULL AND confirm_idempotency_key IS NOT NULL)
        OR (state <> 'confirmed' AND transaction_id IS NULL AND confirm_idempotency_key IS NULL))
);

CREATE INDEX transaction_drafts_owner_idx
    ON transaction_drafts (tenant_id, owner_user_id, created_at DESC);

ALTER TABLE transaction_drafts ENABLE ROW LEVEL SECURITY;
ALTER TABLE transaction_drafts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transaction_drafts
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
