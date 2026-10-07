CREATE TABLE export_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    requester_user_id uuid NOT NULL,
    format_version varchar(24) NOT NULL CHECK (format_version = 'csv-v1'),
    from_date date NOT NULL,
    to_date date NOT NULL,
    requester_timezone varchar(64) NOT NULL,
    scope_member_id uuid,
    include_all_members boolean NOT NULL DEFAULT false,
    snapshot_at timestamptz NOT NULL,
    row_count integer NOT NULL CHECK (row_count BETWEEN 0 AND 100000),
    status varchar(16) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'processing', 'ready', 'failed', 'expired')),
    object_key varchar(512),
    error_code varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT now() + interval '24 hours',
    completed_at timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, requester_user_id) REFERENCES memberships (tenant_id, user_id),
    FOREIGN KEY (tenant_id, scope_member_id) REFERENCES memberships (tenant_id, user_id),
    CHECK (from_date <= to_date),
    CHECK ((include_all_members AND scope_member_id IS NULL) OR
           (NOT include_all_members AND scope_member_id IS NOT NULL)),
    CHECK (status <> 'ready' OR object_key IS NOT NULL)
);

CREATE INDEX export_jobs_requester_created_idx
    ON export_jobs (tenant_id, requester_user_id, created_at DESC, id DESC);
CREATE INDEX export_jobs_queue_idx ON export_jobs (created_at, id) WHERE status = 'queued';

ALTER TABLE export_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE export_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY export_jobs_tenant_isolation ON export_jobs
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE export_snapshot_rows (
    tenant_id uuid NOT NULL,
    export_id uuid NOT NULL,
    row_number bigint NOT NULL CHECK (row_number > 0),
    transaction_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    amount numeric(20,2) NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    category_code varchar(64) NOT NULL,
    subcategory_code varchar(64),
    description varchar(500) NOT NULL,
    transaction_type varchar(24) NOT NULL,
    debt_target varchar(120),
    source varchar(64) NOT NULL,
    telegram_id varchar(32),
    PRIMARY KEY (tenant_id, export_id, row_number),
    UNIQUE (tenant_id, export_id, transaction_id),
    FOREIGN KEY (tenant_id, export_id) REFERENCES export_jobs (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE export_snapshot_rows ENABLE ROW LEVEL SECURITY;
ALTER TABLE export_snapshot_rows FORCE ROW LEVEL SECURITY;
CREATE POLICY export_snapshot_rows_tenant_isolation ON export_snapshot_rows
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION reject_export_snapshot_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'export snapshot rows are immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER export_snapshot_rows_immutable
    BEFORE UPDATE OR DELETE ON export_snapshot_rows
    FOR EACH ROW EXECUTE FUNCTION reject_export_snapshot_mutation();
