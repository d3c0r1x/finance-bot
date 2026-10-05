ALTER TABLE bank_import_rows
    ADD COLUMN category_code varchar(64)
        CHECK (category_code IS NULL OR category_code IN
            ('еда', 'транспорт', 'жилье', 'досуг', 'одежда', 'здоровье', 'работа', 'техника', 'долги', 'прочее')),
    ADD COLUMN suggested_category_code varchar(64)
        CHECK (suggested_category_code IS NULL OR suggested_category_code IN
            ('еда', 'транспорт', 'жилье', 'досуг', 'одежда', 'здоровье', 'работа', 'техника', 'долги', 'прочее')),
    ADD COLUMN category_source varchar(16) NOT NULL DEFAULT 'default'
        CHECK (category_source IN ('default', 'human', 'model')),
    ADD COLUMN category_confidence numeric(4,3)
        CHECK (category_confidence IS NULL OR (category_confidence >= 0 AND category_confidence <= 1)),
    ADD COLUMN category_model_version varchar(128),
    ADD COLUMN category_prompt_version varchar(128);

CREATE TABLE merchant_mappings (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    normalized_merchant varchar(200) NOT NULL CHECK (length(trim(normalized_merchant)) > 0),
    label varchar(200) NOT NULL CHECK (length(trim(label)) > 0),
    category_code varchar(64) NOT NULL CHECK (category_code IN
        ('еда', 'транспорт', 'жилье', 'досуг', 'одежда', 'здоровье', 'работа', 'техника', 'долги', 'прочее')),
    decision_source varchar(16) NOT NULL DEFAULT 'human' CHECK (decision_source = 'human'),
    decision_version varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id, normalized_merchant),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE
);

CREATE INDEX merchant_mappings_lookup_idx ON merchant_mappings (tenant_id, user_id, normalized_merchant);

CREATE TABLE merchant_classification_cache (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    normalized_merchant varchar(200) NOT NULL CHECK (length(trim(normalized_merchant)) > 0),
    prompt_version varchar(128) NOT NULL,
    category_code varchar(64) NOT NULL CHECK (category_code IN
        ('еда', 'транспорт', 'жилье', 'досуг', 'одежда', 'здоровье', 'работа', 'техника', 'долги', 'прочее')),
    confidence numeric(4,3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    provider varchar(64) NOT NULL,
    model_version varchar(128) NOT NULL,
    cached_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, user_id, normalized_merchant, prompt_version),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE,
    CHECK (expires_at > cached_at)
);

CREATE INDEX merchant_classification_cache_expiry_idx ON merchant_classification_cache (expires_at);

ALTER TABLE merchant_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE merchant_mappings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON merchant_mappings
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE merchant_classification_cache ENABLE ROW LEVEL SECURITY;
ALTER TABLE merchant_classification_cache FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON merchant_classification_cache
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
