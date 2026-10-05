CREATE TABLE receipt_processing_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    owner_user_id uuid NOT NULL,
    owner_subject varchar(255) NOT NULL,
    document_id uuid NOT NULL,
    receipt_id uuid,
    state varchar(16) NOT NULL DEFAULT 'queued'
        CHECK (state IN ('queued', 'running', 'retryable', 'completed', 'rejected')),
    stage varchar(16) NOT NULL DEFAULT 'queued'
        CHECK (stage IN ('queued', 'scanning', 'ocr', 'draft', 'complete')),
    progress_percent smallint NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    idempotency_key varchar(128) NOT NULL CHECK (length(trim(idempotency_key)) BETWEEN 16 AND 128),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_expires_at timestamptz,
    error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, owner_user_id, idempotency_key),
    UNIQUE (tenant_id, document_id),
    FOREIGN KEY (tenant_id, owner_user_id) REFERENCES memberships (tenant_id, user_id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, id),
    FOREIGN KEY (tenant_id, receipt_id) REFERENCES receipts (tenant_id, id),
    CHECK ((state = 'completed' AND stage = 'complete' AND progress_percent = 100 AND receipt_id IS NOT NULL)
        OR state <> 'completed'),
    CHECK ((state = 'running' AND lease_expires_at IS NOT NULL) OR state <> 'running')
);

CREATE INDEX receipt_processing_jobs_claim_idx
    ON receipt_processing_jobs (next_attempt_at, created_at, id)
    WHERE state IN ('queued', 'retryable', 'running');
CREATE INDEX receipt_processing_jobs_owner_idx
    ON receipt_processing_jobs (tenant_id, owner_user_id, created_at DESC, id DESC);

ALTER TABLE receipt_processing_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt_processing_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON receipt_processing_jobs
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY receipt_processing_worker ON receipt_processing_jobs
    USING (current_setting('app.receipt_worker', true) = 'true')
    WITH CHECK (current_setting('app.receipt_worker', true) = 'true');

ALTER TABLE receipt_readings ADD COLUMN source_job_id uuid;
ALTER TABLE receipt_readings ADD CONSTRAINT receipt_readings_source_job_fk
    FOREIGN KEY (tenant_id, source_job_id) REFERENCES receipt_processing_jobs (tenant_id, id);
CREATE UNIQUE INDEX receipt_readings_source_job_once_idx
    ON receipt_readings (tenant_id, source_job_id)
    WHERE source_job_id IS NOT NULL;
