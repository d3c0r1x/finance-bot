CREATE TABLE documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    uploaded_by_user_id uuid NOT NULL REFERENCES users(id),
    storage_key varchar(512) NOT NULL CHECK (length(trim(storage_key)) > 0),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    mime_type varchar(100) NOT NULL CHECK (mime_type IN ('image/png', 'image/jpeg', 'image/webp', 'application/pdf')),
    byte_size bigint NOT NULL CHECK (byte_size > 0 AND byte_size <= 26214400),
    scan_state varchar(16) NOT NULL DEFAULT 'pending' CHECK (scan_state IN ('pending', 'ready', 'rejected', 'unavailable')),
    retention_until timestamptz,
    original_name varchar(255) NOT NULL CHECK (length(trim(original_name)) > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, storage_key)
);

CREATE INDEX documents_tenant_created_idx ON documents (tenant_id, created_at DESC, id DESC);

ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON documents
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    owner_user_id uuid NOT NULL REFERENCES users(id),
    owner_subject varchar(255) NOT NULL,
    document_id uuid,
    transaction_id uuid,
    state varchar(16) NOT NULL DEFAULT 'draft' CHECK (state IN ('draft', 'review_required', 'confirmed', 'cancelled')),
    currency char(3) NOT NULL DEFAULT 'RUB' CHECK (currency = 'RUB'),
    cash_total numeric(20,2) CHECK (cash_total > 0),
    items_total numeric(20,2) CHECK (items_total >= 0),
    merchant varchar(255),
    receipt_date date,
    selected_reader varchar(16) CHECK (selected_reader IN ('ocr', 'vision', 'manual')),
    algorithm_version varchar(64) NOT NULL DEFAULT 'receipt-reconciliation.v1',
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    create_idempotency_key varchar(128) NOT NULL,
    create_request_hash char(64) NOT NULL CHECK (create_request_hash ~ '^[0-9a-f]{64}$'),
    confirm_idempotency_key varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, owner_user_id, create_idempotency_key),
    UNIQUE (tenant_id, transaction_id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, id),
    FOREIGN KEY (tenant_id, transaction_id) REFERENCES transactions (tenant_id, id),
    CHECK ((state = 'confirmed' AND transaction_id IS NOT NULL AND confirm_idempotency_key IS NOT NULL
            AND confirmed_at IS NOT NULL)
        OR (state <> 'confirmed' AND transaction_id IS NULL AND confirm_idempotency_key IS NULL
            AND confirmed_at IS NULL))
);

CREATE INDEX receipts_owner_created_idx ON receipts (tenant_id, owner_user_id, created_at DESC, id DESC);
CREATE INDEX receipts_duplicate_candidate_idx ON receipts (tenant_id, cash_total, created_at DESC)
    WHERE state IN ('draft', 'review_required', 'confirmed');

ALTER TABLE receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON receipts
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE receipt_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    receipt_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal > 0),
    name varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    quantity numeric(18,6) CHECK (quantity > 0),
    unit_price numeric(20,2) CHECK (unit_price >= 0),
    line_sum numeric(20,2) CHECK (line_sum >= 0),
    category_code varchar(64),
    verdict varchar(16) CHECK (verdict IN ('useful', 'neutral', 'harmful', 'unnecessary')),
    advice varchar(500),
    verdict_source varchar(16) NOT NULL DEFAULT 'unknown'
        CHECK (verdict_source IN ('rule', 'model', 'default', 'unknown', 'human')),
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(evidence) = 'object'),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, receipt_id, ordinal),
    FOREIGN KEY (tenant_id, receipt_id) REFERENCES receipts (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX receipt_items_parent_idx ON receipt_items (tenant_id, receipt_id, ordinal);

ALTER TABLE receipt_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON receipt_items
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE receipt_readings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    receipt_id uuid NOT NULL,
    reader varchar(16) NOT NULL CHECK (reader IN ('ocr', 'vision')),
    provider varchar(64) NOT NULL,
    model_version varchar(128) NOT NULL,
    prompt_version varchar(64) NOT NULL,
    algorithm_version varchar(64) NOT NULL,
    result_fields jsonb NOT NULL CHECK (jsonb_typeof(result_fields) = 'object'),
    raw_result_reference varchar(512),
    confidence numeric(5,4) CHECK (confidence >= 0 AND confidence <= 1),
    field_evidence jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(field_evidence) = 'object'),
    mismatch_fields jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(mismatch_fields) = 'array'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, receipt_id) REFERENCES receipts (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX receipt_readings_parent_idx ON receipt_readings (tenant_id, receipt_id, created_at, id);

ALTER TABLE receipt_readings ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt_readings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON receipt_readings
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE receipt_reviews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    receipt_id uuid NOT NULL,
    receipt_item_id uuid,
    item_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(item_snapshot) = 'object'),
    actor_subject varchar(255) NOT NULL,
    action varchar(64) NOT NULL,
    before_state jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(before_state) = 'object'),
    after_state jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(after_state) = 'object'),
    verdict_source varchar(16) CHECK (verdict_source IN ('rule', 'model', 'default', 'unknown', 'human')),
    algorithm_version varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, receipt_id) REFERENCES receipts (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX receipt_reviews_parent_idx ON receipt_reviews (tenant_id, receipt_id, created_at, id);

ALTER TABLE receipt_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt_reviews FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON receipt_reviews
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
