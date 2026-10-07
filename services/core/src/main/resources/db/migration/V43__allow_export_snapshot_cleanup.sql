DROP TRIGGER export_snapshot_rows_immutable ON export_snapshot_rows;
CREATE TRIGGER export_snapshot_rows_immutable
    BEFORE UPDATE ON export_snapshot_rows
    FOR EACH ROW EXECUTE FUNCTION reject_export_snapshot_mutation();
