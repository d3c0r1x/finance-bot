ALTER TABLE budget_proposals
    ADD COLUMN proposal_source varchar(24) NOT NULL DEFAULT 'income'
        CHECK (proposal_source IN ('income', 'history_ai')),
    ADD COLUMN history_days integer NOT NULL DEFAULT 0 CHECK (history_days BETWEEN 0 AND 3660),
    ADD COLUMN provider varchar(64),
    ADD COLUMN model_version varchar(128),
    ADD COLUMN prompt_version varchar(64),
    ADD CONSTRAINT budget_proposal_source_metadata CHECK (
        (proposal_source = 'income' AND history_days = 0 AND provider IS NULL
            AND model_version IS NULL AND prompt_version IS NULL)
        OR
        (proposal_source = 'history_ai' AND history_days BETWEEN 30 AND 3660
            AND provider IS NOT NULL AND model_version IS NOT NULL AND prompt_version IS NOT NULL)
    );
