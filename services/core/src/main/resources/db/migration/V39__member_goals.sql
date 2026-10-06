ALTER TABLE member_profiles
    ADD COLUMN goal_measure_unit varchar(8) NOT NULL DEFAULT 'count'
        CHECK (goal_measure_unit IN ('count', 'sum'));

CREATE TABLE goals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    goal_key varchar(300) NOT NULL,
    goal_scope varchar(16) NOT NULL CHECK (goal_scope IN ('product', 'group')),
    display_name varchar(200) NOT NULL CHECK (length(trim(display_name)) > 0),
    unit varchar(8) NOT NULL CHECK (unit IN ('count', 'sum')),
    baseline_rate numeric(12,2) NOT NULL CHECK (baseline_rate >= 2),
    count_target integer NOT NULL DEFAULT 0 CHECK (count_target >= 0),
    baseline_monthly_spend numeric(20,2) CHECK (baseline_monthly_spend IS NULL OR baseline_monthly_spend >= 0),
    monthly_limit numeric(20,2) CHECK (monthly_limit IS NULL OR monthly_limit >= 0),
    evidence_count integer NOT NULL CHECK (evidence_count >= 1),
    input_watermark varchar(20) NOT NULL CHECK (input_watermark ~ '^[1-9][0-9]{0,19}$'),
    accepted_at timestamptz NOT NULL DEFAULT now(),
    ends_at timestamptz NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'cancelled', 'completed')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, owner_user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE,
    CHECK ((goal_scope = 'product' AND goal_key !~ '^cat:') OR (goal_scope = 'group' AND goal_key ~ '^cat:')),
    CHECK ((unit = 'count' AND count_target > 0 AND monthly_limit IS NULL)
        OR (unit = 'sum' AND goal_scope = 'product' AND count_target = 0
            AND baseline_monthly_spend IS NOT NULL AND monthly_limit IS NOT NULL)),
    CHECK (ends_at > accepted_at)
);

CREATE UNIQUE INDEX goals_one_active_per_member_idx ON goals (tenant_id, owner_user_id)
    WHERE status = 'active';
CREATE INDEX goals_member_history_idx ON goals (tenant_id, owner_user_id, accepted_at DESC, id DESC);

CREATE FUNCTION reject_goal_term_changes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.tenant_id, NEW.owner_user_id, NEW.goal_key, NEW.goal_scope, NEW.display_name,
        NEW.unit, NEW.baseline_rate, NEW.count_target, NEW.baseline_monthly_spend,
        NEW.monthly_limit, NEW.evidence_count, NEW.input_watermark, NEW.accepted_at, NEW.ends_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.owner_user_id, OLD.goal_key, OLD.goal_scope, OLD.display_name,
        OLD.unit, OLD.baseline_rate, OLD.count_target, OLD.baseline_monthly_spend,
        OLD.monthly_limit, OLD.evidence_count, OLD.input_watermark, OLD.accepted_at, OLD.ends_at) THEN
        RAISE EXCEPTION 'accepted goal terms are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER goals_terms_immutable BEFORE UPDATE ON goals
    FOR EACH ROW EXECUTE FUNCTION reject_goal_term_changes();

ALTER TABLE goals ENABLE ROW LEVEL SECURITY;
ALTER TABLE goals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON goals
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
