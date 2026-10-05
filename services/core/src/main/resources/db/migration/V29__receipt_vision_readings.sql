ALTER TABLE receipt_processing_jobs DROP CONSTRAINT receipt_processing_jobs_stage_check;
ALTER TABLE receipt_processing_jobs ADD CONSTRAINT receipt_processing_jobs_stage_check
    CHECK (stage IN ('queued', 'scanning', 'vision', 'ocr', 'draft', 'complete'));

DROP INDEX receipt_readings_source_job_once_idx;
CREATE UNIQUE INDEX receipt_readings_source_job_reader_once_idx
    ON receipt_readings (tenant_id, source_job_id, reader)
    WHERE source_job_id IS NOT NULL;
