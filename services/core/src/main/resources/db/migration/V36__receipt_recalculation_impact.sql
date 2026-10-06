ALTER TABLE recalculation_runs
    ADD COLUMN impact_report jsonb;

ALTER TABLE recalculation_runs
    ADD CONSTRAINT recalculation_runs_impact_report_object_check
        CHECK (impact_report IS NULL OR jsonb_typeof(impact_report) = 'object');
