CREATE TABLE telegram_actor_contexts (
    context_hash char(64) PRIMARY KEY CHECK (context_hash ~ '^[0-9a-f]{64}$'),
    telegram_user_id bigint NOT NULL CHECK (telegram_user_id > 0),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE
);

CREATE INDEX telegram_actor_contexts_subject_idx
    ON telegram_actor_contexts (telegram_user_id, expires_at DESC)
    WHERE revoked_at IS NULL;

ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY telegram_actor_membership_lookup ON memberships
    FOR SELECT USING (current_setting('app.telegram_actor_service', true) = 'true');

ALTER TABLE telegram_actor_contexts ENABLE ROW LEVEL SECURITY;
ALTER TABLE telegram_actor_contexts FORCE ROW LEVEL SECURITY;
CREATE POLICY telegram_actor_context_service_only ON telegram_actor_contexts
    USING (current_setting('app.telegram_actor_service', true) = 'true')
    WITH CHECK (current_setting('app.telegram_actor_service', true) = 'true');
