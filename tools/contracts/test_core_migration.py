import os
from pathlib import Path
import re
from uuid import uuid4

import pytest


MIGRATION = Path("services/core/src/main/resources/db/migration/V1__core_tenant_transactions.sql")


def test_migration_creates_tenant_scoped_transactional_core():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    normalized = re.sub(r"--[^\n]*", "", sql)

    for table in ("tenants", "memberships", "accounts", "transactions", "idempotency_records", "audit_log", "outbox_events"):
        assert re.search(rf"create table\s+{table}\b", normalized), f"missing {table}"
    assert "numeric(20,2)" in normalized
    assert "amount > 0" in normalized
    assert "unique (tenant_id, id)" in normalized
    assert "foreign key (tenant_id, account_id)" in normalized
    assert "unique (tenant_id, actor_subject, route, idempotency_key)" in normalized
    assert "primary key (consumer_name, event_id)" in normalized
    assert "create policy tenant_isolation" in normalized
    assert "using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)" in normalized
    assert "force row level security" in normalized
    assert "set local app.tenant_id" not in normalized, "tenant context belongs to request transaction, not migration"


def test_transaction_indexes_and_outbox_retry_fields_exist():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
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
            conn.execute(MIGRATION.read_text(encoding="utf-8"), prepare=False)
            conn.execute(sql.SQL("GRANT USAGE ON SCHEMA {} TO {}").format(sql.Identifier(schema), sql.Identifier(role)))
            conn.execute(sql.SQL("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA {} TO {}").format(sql.Identifier(schema), sql.Identifier(role)))
            conn.execute(sql.SQL("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA {} TO {}").format(sql.Identifier(schema), sql.Identifier(role)))
            tenant_ids = conn.execute("INSERT INTO tenants (display_name) VALUES ('tenant A'), ('tenant B') RETURNING id").fetchall()
            tenant_a, tenant_b = [row[0] for row in tenant_ids]
            conn.execute("INSERT INTO transactions (tenant_id, owner_subject, type, amount, category_code, occurred_at) VALUES (%s, 'user-b', 'expense', 20, 'food', now())", (tenant_b,))

            with conn.transaction():
                conn.execute(sql.SQL("SET LOCAL ROLE {}").format(sql.Identifier(role)))
                conn.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant_a),))
                conn.execute("INSERT INTO transactions (tenant_id, owner_subject, type, amount, category_code, occurred_at) VALUES (%s, 'user-a', 'expense', 12.34, 'food', now())", (tenant_a,))
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
                        conn.execute("INSERT INTO transactions (tenant_id, owner_subject, type, amount, category_code, occurred_at) VALUES (%s, 'user-a', 'expense', 2, 'food', now())", (tenant_b,))

            assert conn.execute("SELECT count(*) FROM transactions WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM transactions WHERE tenant_id = %s", (tenant_b,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM idempotency_records WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM audit_log WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM outbox_events WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1

            with pytest.raises(RuntimeError, match="rollback transaction and outbox together"):
                with conn.transaction():
                    conn.execute(sql.SQL("SET LOCAL ROLE {}").format(sql.Identifier(role)))
                    conn.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant_a),))
                    rolled_back = conn.execute("INSERT INTO transactions (tenant_id, owner_subject, type, amount, category_code, occurred_at) VALUES (%s, 'user-a', 'expense', 9, 'food', now()) RETURNING id", (tenant_a,)).fetchone()[0]
                    conn.execute("INSERT INTO outbox_events (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload) VALUES (%s, 'transaction', %s, 1, 'transaction.created', '{}'::jsonb)", (tenant_a, rolled_back))
                    raise RuntimeError("rollback transaction and outbox together")
            assert conn.execute("SELECT count(*) FROM transactions WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM outbox_events WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
    finally:
        with psycopg.connect(dsn, autocommit=True) as cleanup:
            cleanup.execute(sql.SQL("DROP SCHEMA IF EXISTS {} CASCADE").format(sql.Identifier(schema)))
            cleanup.execute(sql.SQL("DROP ROLE IF EXISTS {}").format(sql.Identifier(role)))
