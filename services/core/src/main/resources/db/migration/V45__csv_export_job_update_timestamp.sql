ALTER TABLE export_jobs ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
