import json
import os
from pathlib import Path
import re
from uuid import uuid4

import pytest


MIGRATIONS = sorted(Path("services/core/src/main/resources/db/migration").glob("V*.sql"))


def test_migration_creates_tenant_scoped_transactional_core():
    sql = "\n".join(path.read_text(encoding="utf-8") for path in MIGRATIONS).lower()
    normalized = re.sub(r"--[^\n]*", "", sql)

    for table in ("tenants", "users", "external_identities", "memberships", "accounts", "transactions", "idempotency_records", "audit_log", "outbox_events"):
        assert re.search(rf"create table\s+{table}\b", normalized), f"missing {table}"
    assert "numeric(20,2)" in normalized
    assert "amount > 0" in normalized
    assert "unique (tenant_id, id)" in normalized
    assert "foreign key (tenant_id, account_id)" in normalized
    assert "unique (tenant_id, actor_subject, route, idempotency_key)" in normalized
    assert "primary key (consumer_name, event_id)" in normalized
    assert "create policy tenant_isolation" in normalized
    assert "using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)" in normalized
    assert "using (id = nullif(current_setting('app.tenant_id', true), '')::uuid)" in normalized
    assert "force row level security" in normalized
    assert "set local app.tenant_id" not in normalized, "tenant context belongs to request transaction, not migration"


def test_transaction_indexes_and_outbox_retry_fields_exist():
    sql = "\n".join(path.read_text(encoding="utf-8") for path in MIGRATIONS).lower()
    assert "occurred_at desc, id desc" in sql
    assert "published_at" in sql
    assert "attempt_count" in sql
    assert "next_attempt_at" in sql


def test_postgres_migration_and_tenant_isolation():
    dsn = os.environ.get("FINANCE_TEST_DATABASE_URL")
    if not dsn:
        pytest.skip("FINANCE_TEST_DATABASE_URL is only set by the PostgreSQL integration job")
    psycopg = pytest.importorskip("psycopg")
    from psycopg import sql

    suffix = uuid4().hex[:12]
    schema = f"finance_test_{suffix}"
    role = f"finance_app_{suffix}"

    try:
        with psycopg.connect(dsn, autocommit=True) as conn:
            conn.execute(sql.SQL("CREATE ROLE {} NOLOGIN").format(sql.Identifier(role)))
            conn.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
            conn.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
            for migration in MIGRATIONS:
                if migration.name.startswith("V2"):
                    legacy_tenant = conn.execute("INSERT INTO tenants (display_name) VALUES ('legacy') RETURNING id").fetchone()[0]
                    with conn.transaction():
                        conn.execute("SELECT set_config('app.tenant_id', %s, true)", (str(legacy_tenant),))
                        conn.execute("INSERT INTO memberships (tenant_id, subject, role) VALUES (%s, 'legacy-subject', 'owner')", (legacy_tenant,))
                        conn.execute("INSERT INTO transactions (tenant_id, owner_subject, type, amount, category_code, occurred_at) VALUES (%s, 'legacy-subject', 'expense', 3, 'food', now())", (legacy_tenant,))
                conn.execute(migration.read_text(encoding="utf-8"), prepare=False)
            backfilled = conn.execute("""
                SELECT count(*) FROM memberships m
                JOIN external_identities i ON i.user_id = m.user_id AND i.subject = m.subject
                JOIN transactions t ON t.owner_user_id = m.user_id AND t.tenant_id = m.tenant_id
                WHERE m.subject = 'legacy-subject'
                """).fetchone()[0]
            assert backfilled == 1
            for tenant_table in ("tenants", "memberships", "accounts", "transactions", "idempotency_records", "audit_log", "outbox_events"):
                assert conn.execute(
                    "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = to_regclass(%s)",
                    (tenant_table,),
                ).fetchone()[0], f"RLS must be enabled and forced on {tenant_table}"
            conn.execute(sql.SQL("GRANT USAGE ON SCHEMA {} TO {}").format(sql.Identifier(schema), sql.Identifier(role)))
            conn.execute(sql.SQL("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA {} TO {}").format(sql.Identifier(schema), sql.Identifier(role)))
            conn.execute(sql.SQL("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA {} TO {}").format(sql.Identifier(schema), sql.Identifier(role)))
            tenant_ids = conn.execute("INSERT INTO tenants (display_name) VALUES ('tenant A'), ('tenant B') RETURNING id").fetchall()
            tenant_a, tenant_b = [row[0] for row in tenant_ids]
            user_a = conn.execute("INSERT INTO users DEFAULT VALUES RETURNING id").fetchone()[0]
            user_b = conn.execute("INSERT INTO users DEFAULT VALUES RETURNING id").fetchone()[0]
            conn.execute("INSERT INTO external_identities (user_id, provider, subject) VALUES (%s, 'keycloak', 'user-a'), (%s, 'keycloak', 'user-b')", (user_a, user_b))
            conn.execute("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (%s, 'user-a', 'owner', %s), (%s, 'user-b', 'owner', %s)", (tenant_a, user_a, tenant_b, user_b))
            conn.execute("INSERT INTO transactions (tenant_id, owner_subject, owner_user_id, type, amount, category_code, occurred_at) VALUES (%s, 'user-b', %s, 'expense', 20, 'food', now())", (tenant_b, user_b))

            with conn.transaction():
                conn.execute(sql.SQL("SET LOCAL ROLE {}").format(sql.Identifier(role)))
                conn.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant_a),))
                assert conn.execute("SELECT count(*) FROM tenants WHERE id = %s", (tenant_b,)).fetchone()[0] == 0
                conn.execute("INSERT INTO transactions (tenant_id, owner_subject, owner_user_id, type, amount, category_code, occurred_at) VALUES (%s, 'user-a', %s, 'expense', 12.34, 'food', now())", (tenant_a, user_a))
                conn.execute("INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) VALUES (%s, 'user-a', '/transactions', 'request-key-0001', repeat('a', 64))", (tenant_a,))
                with pytest.raises(psycopg.errors.UniqueViolation):
                    with conn.transaction():
                        conn.execute("INSERT INTO idempotency_records (tenant_id, actor_subject, route, idempotency_key, request_hash) VALUES (%s, 'user-a', '/transactions', 'request-key-0001', repeat('b', 64))", (tenant_a,))
                tx_id = conn.execute("SELECT id FROM transactions WHERE tenant_id = %s", (tenant_a,)).fetchone()[0]
                conn.execute("INSERT INTO audit_log (tenant_id, actor_subject, action, entity_type, entity_id, after_state, trace_id) VALUES (%s, 'user-a', 'transaction.created', 'transaction', %s, '{}'::jsonb, 'trace-1')", (tenant_a, tx_id))
                conn.execute("INSERT INTO outbox_events (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload) VALUES (%s, 'transaction', %s, 1, 'transaction.created', '{}'::jsonb)", (tenant_a, tx_id))
                assert conn.execute("SELECT count(*) FROM transactions WHERE tenant_id = %s", (tenant_b,)).fetchone()[0] == 0
                with pytest.raises(psycopg.errors.InsufficientPrivilege):
                    with conn.transaction():
                        conn.execute("INSERT INTO transactions (tenant_id, owner_subject, owner_user_id, type, amount, category_code, occurred_at) VALUES (%s, 'user-a', %s, 'expense', 2, 'food', now())", (tenant_b, user_a))

            assert conn.execute("SELECT count(*) FROM transactions WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM transactions WHERE tenant_id = %s", (tenant_b,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM idempotency_records WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM audit_log WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM outbox_events WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1

            with pytest.raises(RuntimeError, match="rollback transaction and outbox together"):
                with conn.transaction():
                    conn.execute(sql.SQL("SET LOCAL ROLE {}").format(sql.Identifier(role)))
                    conn.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant_a),))
                    rolled_back = conn.execute("INSERT INTO transactions (tenant_id, owner_subject, owner_user_id, type, amount, category_code, occurred_at) VALUES (%s, 'user-a', %s, 'expense', 9, 'food', now()) RETURNING id", (tenant_a, user_a)).fetchone()[0]
                    conn.execute("INSERT INTO outbox_events (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload) VALUES (%s, 'transaction', %s, 1, 'transaction.created', '{}'::jsonb)", (tenant_a, rolled_back))
                    raise RuntimeError("rollback transaction and outbox together")
            assert conn.execute("SELECT count(*) FROM transactions WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM outbox_events WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
    finally:
        with psycopg.connect(dsn, autocommit=True) as cleanup:
            cleanup.execute(sql.SQL("DROP SCHEMA IF EXISTS {} CASCADE").format(sql.Identifier(schema)))
            cleanup.execute(sql.SQL("DROP ROLE IF EXISTS {}").format(sql.Identifier(role)))


def test_persisted_core_events_match_public_json_schema():
    dsn = os.environ.get("FINANCE_TEST_DATABASE_URL")
    if not dsn:
        pytest.skip("FINANCE_TEST_DATABASE_URL is only set by the PostgreSQL integration job")
    psycopg = pytest.importorskip("psycopg")
    import jsonschema

    schema = json.loads(Path("contracts/events/finance.transaction.v1.schema.json").read_text(encoding="utf-8"))
    with psycopg.connect(dsn) as conn:
        if conn.execute("SELECT to_regclass('public.outbox_events')").fetchone()[0] is None:
            pytest.skip("Java core integration tests have not installed the public schema")
        events = conn.execute("SELECT payload FROM public.outbox_events ORDER BY created_at DESC").fetchall()
    assert events, "the Java API integration test should have emitted a transaction event"
    for (event,) in events:
        jsonschema.validate(event, schema)
