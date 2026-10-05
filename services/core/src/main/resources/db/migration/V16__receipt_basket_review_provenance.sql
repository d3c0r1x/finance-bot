ALTER TABLE receipt_items
    ADD COLUMN review_reason varchar(500),
    ADD COLUMN review_action varchar(500),
    ADD COLUMN review_provider varchar(128),
    ADD COLUMN review_model_version varchar(128),
    ADD COLUMN review_prompt_version varchar(128),
    ADD COLUMN review_algorithm_version varchar(64) NOT NULL DEFAULT 'unknown'
        CHECK (length(trim(review_algorithm_version)) > 0);
