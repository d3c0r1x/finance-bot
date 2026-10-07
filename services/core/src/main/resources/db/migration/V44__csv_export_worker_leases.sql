ALTER TABLE export_jobs
    ADD COLUMN attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_expires_at timestamptz,
    ADD COLUMN completed_lease_token uuid,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN object_checksum_sha256 char(64),
    ADD COLUMN object_byte_count bigint CHECK (object_byte_count IS NULL OR object_byte_count > 0),
    ADD CONSTRAINT export_jobs_lease_state_check CHECK (
        (status = 'processing' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'processing' AND lease_token IS NULL AND lease_expires_at IS NULL)),
    ADD CONSTRAINT export_jobs_ready_metadata_check CHECK (
        status <> 'ready' OR (object_key IS NOT NULL AND object_checksum_sha256 ~ '^[0-9a-f]{64}$'
                              AND object_byte_count IS NOT NULL AND completed_lease_token IS NOT NULL));

CREATE INDEX export_jobs_worker_claim_idx
    ON export_jobs (next_attempt_at, created_at, id)
    WHERE status IN ('queued', 'processing');

CREATE POLICY export_jobs_worker_service ON export_jobs
    USING (current_setting('app.export_service', true) = 'true')
    WITH CHECK (current_setting('app.export_service', true) = 'true');
CREATE POLICY export_snapshot_rows_worker_service ON export_snapshot_rows
    USING (current_setting('app.export_service', true) = 'true')
    WITH CHECK (current_setting('app.export_service', true) = 'true');
