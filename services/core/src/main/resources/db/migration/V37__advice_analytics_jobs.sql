CREATE TABLE advice_analytics_versions (
    tenant_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    input_hash char(64) NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    input_watermark bigint NOT NULL CHECK (input_watermark > 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, owner_user_id),
    FOREIGN KEY (tenant_id, owner_user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE
);

CREATE TABLE advice_analytics_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    input_hash char(64) NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    input_watermark bigint NOT NULL CHECK (input_watermark > 0),
    algorithm_version varchar(64) NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'processing', 'ready', 'failed', 'stale')),
    input_payload jsonb NOT NULL CHECK (jsonb_typeof(input_payload) = 'object'),
    result jsonb CHECK (result IS NULL OR jsonb_typeof(result) = 'object'),
    attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_expires_at timestamptz,
    error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, owner_user_id, input_watermark, algorithm_version),
    FOREIGN KEY (tenant_id, owner_user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE,
    CHECK ((state = 'processing' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (state <> 'processing' AND lease_token IS NULL AND lease_expires_at IS NULL))
);

CREATE INDEX advice_analytics_jobs_claim_idx
    ON advice_analytics_jobs (next_attempt_at, created_at, id)
    WHERE state IN ('pending', 'processing');
CREATE INDEX advice_analytics_jobs_owner_idx
    ON advice_analytics_jobs (tenant_id, owner_user_id, input_watermark DESC);

ALTER TABLE advice_analytics_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE advice_analytics_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY advice_analytics_member ON advice_analytics_versions
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY advice_analytics_worker ON advice_analytics_versions
    USING (current_setting('app.analytics_worker', true) = 'true')
    WITH CHECK (current_setting('app.analytics_worker', true) = 'true');

ALTER TABLE advice_analytics_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE advice_analytics_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY advice_analytics_member ON advice_analytics_jobs
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY advice_analytics_worker ON advice_analytics_jobs
    USING (current_setting('app.analytics_worker', true) = 'true')
    WITH CHECK (current_setting('app.analytics_worker', true) = 'true');
