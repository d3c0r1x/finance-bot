-- Run after Flyway migrations while connected to the target `finance` database.
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO finance_app;
GRANT UPDATE ON TABLE transactions, idempotency_records, outbox_events, users TO finance_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO finance_app;
REVOKE ALL ON TABLE flyway_schema_history FROM finance_app;
