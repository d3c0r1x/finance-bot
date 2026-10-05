ALTER TABLE user_product_decisions
    DROP CONSTRAINT IF EXISTS user_product_decisions_product_key_check,
    ADD CONSTRAINT user_product_decisions_product_key_check
        CHECK (product_key ~ '^[a-zа-я0-9]+$');

ALTER TABLE user_product_decision_events
    DROP CONSTRAINT IF EXISTS user_product_decision_events_product_key_check,
    ADD CONSTRAINT user_product_decision_events_product_key_check
        CHECK (product_key ~ '^[a-zа-я0-9]+$');
