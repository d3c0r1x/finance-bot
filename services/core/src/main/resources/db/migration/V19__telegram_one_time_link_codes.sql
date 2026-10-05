CREATE TABLE telegram_link_codes (
    code_hash char(64) PRIMARY KEY CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    user_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    invalidated_at timestamptz,
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);
CREATE INDEX telegram_link_codes_user_created_idx ON telegram_link_codes (user_id, created_at DESC);

ALTER TABLE telegram_link_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE telegram_link_codes FORCE ROW LEVEL SECURITY;
CREATE POLICY telegram_link_code_owner_or_service ON telegram_link_codes
    USING (
        user_id = (SELECT user_id FROM external_identities
                   WHERE provider = 'keycloak'
                     AND subject = nullif(current_setting('app.subject', true), ''))
        OR current_setting('app.telegram_link_service', true) = 'true'
    )
    WITH CHECK (
        user_id = (SELECT user_id FROM external_identities
                   WHERE provider = 'keycloak'
                     AND subject = nullif(current_setting('app.subject', true), ''))
        OR current_setting('app.telegram_link_service', true) = 'true'
    );

CREATE TABLE telegram_link_attempts (
    telegram_user_id bigint PRIMARY KEY CHECK (telegram_user_id > 0),
    window_started_at timestamptz NOT NULL DEFAULT now(),
    failed_attempts smallint NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    blocked_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE telegram_link_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE telegram_link_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY telegram_link_attempt_service_only ON telegram_link_attempts
    USING (current_setting('app.telegram_link_service', true) = 'true')
    WITH CHECK (current_setting('app.telegram_link_service', true) = 'true');
