CREATE TABLE notification_preferences (
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    language varchar(2) NOT NULL DEFAULT 'ru' CHECK (language IN ('ru', 'en')),
    daily_enabled boolean NOT NULL DEFAULT true,
    daily_local_time time NOT NULL DEFAULT TIME '21:00',
    weekly_enabled boolean NOT NULL DEFAULT true,
    weekly_day_of_week smallint NOT NULL DEFAULT 7 CHECK (weekly_day_of_week BETWEEN 1 AND 7),
    weekly_local_time time NOT NULL DEFAULT TIME '19:00',
    quiet_hours_start time,
    quiet_hours_end time,
    next_daily_at timestamptz,
    next_weekly_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES member_profiles (tenant_id, user_id) ON DELETE CASCADE,
    CHECK ((quiet_hours_start IS NULL) = (quiet_hours_end IS NULL)),
    CHECK (quiet_hours_start IS NULL OR quiet_hours_start <> quiet_hours_end)
);

CREATE INDEX notification_preferences_daily_due_idx
    ON notification_preferences (next_daily_at, tenant_id, user_id)
    WHERE daily_enabled;
CREATE INDEX notification_preferences_weekly_due_idx
    ON notification_preferences (next_weekly_at, tenant_id, user_id)
    WHERE weekly_enabled;

ALTER TABLE notification_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_preferences FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_preferences_member ON notification_preferences
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
           AND user_id = (SELECT user_id FROM memberships
                          WHERE tenant_id = notification_preferences.tenant_id
                            AND subject = nullif(current_setting('app.subject', true), '')
                            AND status = 'active'))
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
                AND user_id = (SELECT user_id FROM memberships
                               WHERE tenant_id = notification_preferences.tenant_id
                                 AND subject = nullif(current_setting('app.subject', true), '')
                                 AND status = 'active'));
CREATE POLICY notification_preferences_worker ON notification_preferences
    USING (current_setting('app.notification_service', true) = 'true')
    WITH CHECK (current_setting('app.notification_service', true) = 'true');

CREATE TABLE notification_intents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    digest_kind varchar(16) NOT NULL CHECK (digest_kind IN ('daily', 'weekly')),
    scheduled_local_date date NOT NULL,
    scheduled_at timestamptz NOT NULL,
    timezone varchar(64) NOT NULL,
    language varchar(2) NOT NULL CHECK (language IN ('ru', 'en')),
    report_from_date date NOT NULL,
    report_to_date date NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'leased', 'delivered', 'skipped_no_data', 'failed')),
    attempt_count smallint NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 8),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    last_error_code varchar(64),
    provider_message_id varchar(128),
    delivered_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, user_id, digest_kind, scheduled_local_date),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE CASCADE,
    CHECK (report_to_date >= report_from_date),
    CHECK ((state = 'leased' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
           OR (state <> 'leased' AND lease_token IS NULL AND lease_until IS NULL)),
    CHECK ((state = 'delivered' AND delivered_at IS NOT NULL)
           OR state <> 'delivered')
);

CREATE INDEX notification_intents_ready_idx
    ON notification_intents (available_at, created_at, id)
    WHERE state IN ('pending', 'leased');
CREATE INDEX notification_intents_member_idx
    ON notification_intents (tenant_id, user_id, created_at DESC);

ALTER TABLE notification_intents ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_intents FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_intents_service_only ON notification_intents
    USING (current_setting('app.notification_service', true) = 'true')
    WITH CHECK (current_setting('app.notification_service', true) = 'true');

CREATE TABLE notification_delivery_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id uuid NOT NULL REFERENCES notification_intents (id) ON DELETE CASCADE,
    attempt_number smallint NOT NULL CHECK (attempt_number BETWEEN 1 AND 8),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    outcome varchar(24) NOT NULL DEFAULT 'started'
        CHECK (outcome IN ('started', 'delivered', 'retryable_failure', 'permanent_failure', 'no_data')),
    error_code varchar(64),
    provider_message_id varchar(128),
    UNIQUE (intent_id, attempt_number),
    CHECK ((outcome = 'started' AND completed_at IS NULL)
           OR (outcome <> 'started' AND completed_at IS NOT NULL))
);

ALTER TABLE notification_delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_delivery_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_delivery_attempts_service_only ON notification_delivery_attempts
    USING (current_setting('app.notification_service', true) = 'true')
    WITH CHECK (current_setting('app.notification_service', true) = 'true');
