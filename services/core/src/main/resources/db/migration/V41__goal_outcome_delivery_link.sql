ALTER TABLE goal_outcomes
    ADD COLUMN announcement_intent_id uuid
        REFERENCES notification_intents (id) ON DELETE SET NULL;

CREATE UNIQUE INDEX goal_outcomes_announcement_intent_idx
    ON goal_outcomes (announcement_intent_id)
    WHERE announcement_intent_id IS NOT NULL;

CREATE INDEX goal_outcomes_pending_delivery_idx
    ON goal_outcomes (tenant_id, owner_user_id, completed_at, id)
    WHERE origin = 'completed' AND announced_at IS NULL AND announcement_intent_id IS NULL;
