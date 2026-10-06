ALTER TABLE user_product_decision_events
    DROP CONSTRAINT user_product_decision_events_action_check;

ALTER TABLE user_product_decision_events
    ADD CONSTRAINT user_product_decision_events_action_check
    CHECK (action IN ('allowed', 'confirmed', 'revoked'));
