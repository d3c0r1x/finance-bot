ALTER TABLE goals
    ADD COLUMN member_product_keys jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(member_product_keys) = 'array');

ALTER TABLE goals
    ADD CONSTRAINT goals_membership_matches_scope CHECK (
        (goal_scope = 'product' AND jsonb_array_length(member_product_keys) = 0)
        OR (goal_scope = 'group' AND jsonb_array_length(member_product_keys) BETWEEN 1 AND 50000)
    );

CREATE FUNCTION reject_goal_membership_changes() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.member_product_keys IS DISTINCT FROM OLD.member_product_keys THEN
        RAISE EXCEPTION 'accepted goal members are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER goals_members_immutable BEFORE UPDATE ON goals
    FOR EACH ROW EXECUTE FUNCTION reject_goal_membership_changes();

CREATE TABLE goal_outcomes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    goal_id uuid,
    legacy_key varchar(200),
    origin varchar(16) NOT NULL CHECK (origin IN ('completed', 'legacy')),
    goal_snapshot jsonb NOT NULL CHECK (jsonb_typeof(goal_snapshot) = 'object'),
    progress_snapshot jsonb NOT NULL CHECK (jsonb_typeof(progress_snapshot) = 'object'),
    accepted_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    announced_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, owner_user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE,
    CHECK ((origin = 'completed' AND goal_id IS NOT NULL AND legacy_key IS NULL)
        OR (origin = 'legacy' AND legacy_key IS NOT NULL))
);

CREATE UNIQUE INDEX goal_outcomes_one_per_goal_idx ON goal_outcomes (tenant_id, goal_id)
    WHERE goal_id IS NOT NULL;
CREATE UNIQUE INDEX goal_outcomes_legacy_key_idx ON goal_outcomes (tenant_id, owner_user_id, legacy_key)
    WHERE origin = 'legacy';
CREATE INDEX goal_outcomes_member_history_idx ON goal_outcomes (tenant_id, owner_user_id, completed_at DESC, id DESC);

ALTER TABLE goal_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE goal_outcomes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON goal_outcomes
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
