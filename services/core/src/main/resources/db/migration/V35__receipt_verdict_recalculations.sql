CREATE TABLE recalculation_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    owner_user_id uuid NOT NULL,
    initiated_by_subject varchar(255) NOT NULL CHECK (length(trim(initiated_by_subject)) > 0),
    algorithm_version varchar(64) NOT NULL CHECK (length(trim(algorithm_version)) > 0),
    state varchar(16) NOT NULL DEFAULT 'previewed' CHECK (state IN ('previewed', 'applied')),
    checked_count integer NOT NULL CHECK (checked_count >= 0),
    update_count integer NOT NULL CHECK (update_count >= 0),
    changed_count integer NOT NULL CHECK (changed_count >= 0 AND changed_count <= update_count),
    created_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_user_id) REFERENCES memberships (tenant_id, user_id),
    CHECK ((state = 'previewed' AND applied_at IS NULL) OR (state = 'applied' AND applied_at IS NOT NULL))
);

CREATE INDEX recalculation_runs_owner_idx
    ON recalculation_runs (tenant_id, owner_user_id, created_at DESC, id DESC);

ALTER TABLE recalculation_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE recalculation_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON recalculation_runs
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE recalculation_changes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    receipt_id uuid NOT NULL,
    receipt_item_id uuid NOT NULL,
    expected_item_version bigint NOT NULL CHECK (expected_item_version > 0),
    line_sum numeric(20,2),
    before_state jsonb NOT NULL CHECK (jsonb_typeof(before_state) = 'object'),
    after_state jsonb NOT NULL CHECK (jsonb_typeof(after_state) = 'object'),
    changed boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, run_id, receipt_item_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES recalculation_runs (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, receipt_id) REFERENCES receipts (tenant_id, id),
    FOREIGN KEY (tenant_id, receipt_item_id) REFERENCES receipt_items (tenant_id, id)
);

CREATE INDEX recalculation_changes_run_idx ON recalculation_changes (tenant_id, run_id, receipt_item_id);

ALTER TABLE recalculation_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE recalculation_changes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON recalculation_changes
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
