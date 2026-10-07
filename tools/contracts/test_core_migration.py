import json
import os
from pathlib import Path
import re
from uuid import uuid4

import pytest


MIGRATIONS = sorted(
    Path("services/core/src/main/resources/db/migration").glob("V*.sql"),
    key=lambda path: int(re.match(r"V(\d+)", path.name).group(1)),
)


def test_migration_creates_tenant_scoped_transactional_core():
    sql = "\n".join(path.read_text(encoding="utf-8") for path in MIGRATIONS).lower()
    normalized = re.sub(r"--[^\n]*", "", sql)

    for table in ("tenants", "users", "external_identities", "memberships", "member_profiles", "accounts", "transactions", "transaction_drafts", "tenant_budgets", "budget_proposals", "debts", "notification_preferences", "notification_intents", "notification_delivery_attempts", "idempotency_records", "audit_log", "outbox_events"):
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


def test_goal_progress_and_outcomes_migration_preserves_group_terms_and_tenant_isolation():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V40__goal_progress_and_outcomes")), None)
    assert migration is not None, "goal progress and outcomes need an additive V40 migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    assert "add column member_product_keys jsonb not null" in normalized
    assert "goals_membership_matches_scope" in normalized
    assert "create trigger goals_members_immutable" in normalized
    assert "create table goal_outcomes" in normalized
    assert "origin in ('completed', 'legacy')" in normalized
    assert "goal_outcomes_one_per_goal_idx" in normalized
    assert "goal_outcomes_legacy_key_idx" in normalized
    assert "alter table goal_outcomes enable row level security" in normalized
    assert "alter table goal_outcomes force row level security" in normalized


def test_goal_outcome_delivery_migration_links_one_pending_outcome_to_one_intent():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V41__goal_outcome_delivery_link")), None)
    assert migration is not None, "weekly outcome delivery needs an additive V41 migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    assert "add column announcement_intent_id uuid" in normalized
    assert "references notification_intents (id) on delete set null" in normalized
    assert "goal_outcomes_announcement_intent_idx" in normalized
    assert "where announcement_intent_id is not null" in normalized
    assert "goal_outcomes_pending_delivery_idx" in normalized
    assert "origin = 'completed'" in normalized
    assert "announced_at is null" in normalized


def test_receipt_migration_is_tenant_scoped_and_keeps_reader_evidence():
    sql = "\n".join(path.read_text(encoding="utf-8") for path in MIGRATIONS).lower()
    normalized = re.sub(r"--[^\n]*", "", sql)

    for table in ("documents", "receipts", "receipt_items", "receipt_readings", "receipt_reviews"):
        assert re.search(rf"create table\s+{table}\b", normalized), f"missing {table}"
        assert re.search(rf"alter table\s+{table}\s+enable row level security", normalized)
        assert re.search(rf"alter table\s+{table}\s+force row level security", normalized)
    assert "foreign key (tenant_id, document_id) references documents (tenant_id, id)" in normalized
    assert "foreign key (tenant_id, receipt_id) references receipts (tenant_id, id)" in normalized
    assert "reader in ('ocr', 'vision')" in normalized
    assert "verdict_source in ('rule', 'model', 'default', 'unknown', 'human')" in normalized
    assert "cash_total numeric(20,2)" in normalized
    assert "items_total numeric(20,2)" in normalized
    assert "create_idempotency_key" in normalized


def test_receipt_category_override_is_versioned_and_audited():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V15")), None)
    assert migration is not None, "receipt category policy needs an additive V15 migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    for column in ("category_code", "category_source", "category_algorithm_version", "alcohol_share", "leisure_share", "leisure"):
        assert re.search(rf"add column(?: if not exists)? {column}\b", normalized), f"missing receipt.{column}"
    assert "receipt-category.v1" in normalized
    assert "force row level security" not in normalized, "alter receipt without weakening existing RLS"


def test_receipt_basket_provenance_is_additive_and_bounded():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V16")), None)
    assert migration is not None, "receipt basket review needs an additive V16 migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    for column in ("review_provider", "review_model_version", "review_prompt_version", "review_algorithm_version"):
        assert re.search(rf"add column(?: if not exists)? {column}\b", normalized), f"missing receipt_items.{column}"
    assert "varchar(128)" in normalized
    assert "default 'unknown'" in normalized
    assert "force row level security" not in normalized, "alter receipt_items without weakening existing RLS"


def test_user_product_decisions_have_tenant_rls_and_immutable_change_history():
    sql = "\n".join(path.read_text(encoding="utf-8") for path in MIGRATIONS).lower()
    normalized = re.sub(r"--[^\n]*", "", sql)
    for table in ("user_product_decisions", "user_product_decision_events"):
        assert re.search(rf"create table\s+{table}\b", normalized), f"missing {table}"
        assert re.search(rf"alter table {table} enable row level security", normalized)
        assert re.search(rf"alter table {table} force row level security", normalized)
    assert "unique (tenant_id, user_id, product_key)" in normalized
    assert "decision in ('allowed', 'confirmed')" in normalized


def test_receipt_duplicate_decision_migration_is_additive_and_linked():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V18")), None)
    assert migration is not None, "receipt duplicate review needs an additive V18 migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    assert "add column duplicate_decision" in normalized
    assert "add column duplicate_of_receipt_id" in normalized
    assert "references receipts (tenant_id, id)" in normalized
    assert "duplicate_decision in ('unknown', 'independent', 'duplicate')" in normalized
    assert "constraint receipts_duplicate_decision_value_check" in normalized
    assert "add constraint receipts_duplicate_decision_check" in normalized


def test_product_decision_keys_keep_the_full_256_character_domain():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V20")), None)
    assert migration is not None, "product-key constraint repair needs an additive V20 migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    for table in ("user_product_decisions", "user_product_decision_events"):
        assert f"drop constraint if exists {table}_product_key_check" in normalized
        assert f"add constraint {table}_product_key_check" in normalized
    assert "product_key ~ '^[a-zа-я0-9]+$'" in normalized
    assert "{1,256}" not in normalized, "PostgreSQL ARE repetition counts stop at 255"


def test_telegram_actor_contexts_are_scoped_and_revocable():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V21")), None)
    assert migration is not None, "Telegram actor contexts need an additive V21 migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    assert "create table telegram_actor_contexts" in normalized
    assert "context_hash char(64)" in normalized
    assert "foreign key (tenant_id, user_id) references memberships (tenant_id, user_id)" in normalized
    assert "alter table telegram_actor_contexts force row level security" in normalized
    assert "telegram_actor_context_service_only" in normalized
    assert "create policy telegram_actor_membership_lookup" in normalized
    assert "for select using" in normalized


def test_notification_schedules_and_delivery_attempts_are_durable_and_service_scoped():
    preferences = next((path for path in MIGRATIONS if path.name.startswith("V30")), None)
    worker_access = next((path for path in MIGRATIONS if path.name.startswith("V31")), None)
    assert preferences is not None, "notification preferences and outbox need additive V30 migration"
    assert worker_access is not None, "notification worker member lookup needs additive V31 migration"
    schedule_sql = re.sub(r"--[^\n]*", "", preferences.read_text(encoding="utf-8").lower())
    access_sql = re.sub(r"--[^\n]*", "", worker_access.read_text(encoding="utf-8").lower())
    for table in ("notification_preferences", "notification_intents", "notification_delivery_attempts"):
        assert f"create table {table}" in schedule_sql
        assert f"alter table {table} enable row level security" in schedule_sql
        assert f"alter table {table} force row level security" in schedule_sql
    assert "unique (tenant_id, user_id, digest_kind, scheduled_local_date)" in schedule_sql
    assert "unique (intent_id, attempt_number)" in schedule_sql
    assert "attempt_count between 0 and 8" in schedule_sql
    assert "notification_preferences_member" in schedule_sql
    assert "notification_preferences_worker" in schedule_sql
    assert "notification_intents_service_only" in schedule_sql
    assert "notification_delivery_attempts_service_only" in schedule_sql
    assert "notification_service_membership_lookup" in access_sql
    assert "notification_service_profile_lookup" in access_sql


def test_shopping_marks_and_mutes_are_member_scoped_and_rls_protected():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V32")), None)
    assert migration is not None, "shopping marks and mutes need an additive V32 migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    for table in ("shopping_marks", "muted_suggestions"):
        assert f"create table {table}" in normalized
        assert f"alter table {table} enable row level security" in normalized
        assert f"alter table {table} force row level security" in normalized
        assert f"foreign key (tenant_id, user_id) references memberships (tenant_id, user_id)" in normalized
    assert "primary key (tenant_id, user_id, product_key)" in normalized
    assert "primary key (tenant_id, user_id, section, suggestion_key)" in normalized
    assert "current_setting('app.tenant_id', true)" in normalized


def test_shopping_mark_key_constraint_supports_full_product_key_length_in_postgres():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V33")), None)
    assert migration is not None, "V33 must repair the PostgreSQL shopping-key repetition limit"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    assert "drop constraint shopping_marks_product_key_check" in normalized
    assert "product_key ~ '^[a-zа-я0-9]+$'" in normalized
    assert "varchar(256)" in "\n".join(path.read_text(encoding="utf-8").lower() for path in MIGRATIONS)


def test_merchant_mappings_and_classification_cache_are_personal_and_tenant_scoped():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V26")), None)
    assert migration is not None, "merchant categories need an additive V26 migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    for table in ("merchant_mappings", "merchant_classification_cache"):
        assert f"create table {table}" in normalized
        assert f"alter table {table} enable row level security" in normalized
        assert f"alter table {table} force row level security" in normalized
        assert f"foreign key (tenant_id, user_id) references memberships (tenant_id, user_id)" in normalized
    assert "primary key (tenant_id, user_id, normalized_merchant)" in normalized
    assert "primary key (tenant_id, user_id, normalized_merchant, prompt_version)" in normalized
    assert "decision_source = 'human'" in normalized
    assert "expires_at > cached_at" in normalized
    assert "suggested_category_code" in normalized


def test_merchant_reclassification_tracks_only_unedited_imported_expenses():
    migration = next((path for path in MIGRATIONS if path.name.startswith("V27")), None)
    assert migration is not None, "managed merchant reclassification needs an additive V27 migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    assert "add column reclassification_version bigint" in normalized
    assert "set reclassification_version = t.version" in normalized
    assert "t.source = 'bank_import'" in normalized
    assert "t.status = 'posted'" in normalized
    assert "r.category_code is null" in normalized
    assert "r.outcome = 'created'" in normalized
    assert "create index bank_import_rows_reclassification_idx" in normalized


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
            legacy_proposal = None
            for migration in MIGRATIONS:
                if re.match(r"V2__", migration.name):
                    legacy_tenant = conn.execute("INSERT INTO tenants (display_name) VALUES ('legacy') RETURNING id").fetchone()[0]
                    with conn.transaction():
                        conn.execute("SELECT set_config('app.tenant_id', %s, true)", (str(legacy_tenant),))
                        conn.execute("INSERT INTO memberships (tenant_id, subject, role) VALUES (%s, 'legacy-subject', 'owner')", (legacy_tenant,))
                        conn.execute("INSERT INTO transactions (tenant_id, owner_subject, type, amount, category_code, occurred_at) VALUES (%s, 'legacy-subject', 'expense', 3, 'food', now())", (legacy_tenant,))
                if migration.name.startswith("V10"):
                    legacy_proposal_tenant = conn.execute(
                        "INSERT INTO tenants (display_name) VALUES ('legacy proposal') RETURNING id"
                    ).fetchone()[0]
                    legacy_proposal = conn.execute("""
                        INSERT INTO budget_proposals (tenant_id, created_by, monthly_income, total_limit,
                          proposed_limits, base_versions, base_total_version)
                        VALUES (%s, 'legacy-subject', 1000, 700, '{}'::jsonb, '{}'::jsonb, 0)
                        RETURNING id
                        """, (legacy_proposal_tenant,)).fetchone()[0]
                conn.execute(migration.read_text(encoding="utf-8"), prepare=False)

            assert legacy_proposal is not None
            metadata = conn.execute("""
                SELECT proposal_source, history_days, provider, model_version, prompt_version
                FROM budget_proposals WHERE id = %s
                """, (legacy_proposal,)).fetchone()
            assert metadata == ("income", 0, None, None, None)
            backfilled = conn.execute("""
                SELECT count(*) FROM memberships m
                JOIN external_identities i ON i.user_id = m.user_id AND i.subject = m.subject
                JOIN transactions t ON t.owner_user_id = m.user_id AND t.tenant_id = m.tenant_id
                WHERE m.subject = 'legacy-subject'
                """).fetchone()[0]
            assert backfilled == 1
            for tenant_table in ("tenants", "memberships", "member_profiles", "accounts", "transactions", "transaction_drafts", "tenant_budgets", "budget_proposals", "debts", "notification_preferences", "notification_intents", "notification_delivery_attempts", "documents", "receipts", "receipt_items", "receipt_readings", "receipt_reviews", "user_product_decisions", "user_product_decision_events", "telegram_actor_contexts", "merchant_mappings", "merchant_classification_cache", "idempotency_records", "audit_log", "outbox_events"):
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
            conn.execute("INSERT INTO user_product_decisions (tenant_id, user_id, product_key, decision) VALUES (%s, %s, 'milk', 'allowed')", (tenant_b, user_b))
            conn.execute("INSERT INTO user_product_decision_events (tenant_id, user_id, product_key, after_decision, actor_subject, action) VALUES (%s, %s, 'milk', 'allowed', 'user-b', 'allowed')", (tenant_b, user_b))
            conn.execute("INSERT INTO merchant_mappings (tenant_id, user_id, normalized_merchant, label, category_code, decision_version) VALUES (%s, %s, 'market', 'Market', 'еда', 'merchant-category.v1')", (tenant_b, user_b))
            conn.execute("INSERT INTO merchant_classification_cache (tenant_id, user_id, normalized_merchant, prompt_version, category_code, confidence, provider, model_version, expires_at) VALUES (%s, %s, 'market', 'merchant-category.v1', 'еда', 0.900, 'test', 'model-1', now() + interval '90 days')", (tenant_b, user_b))
            max_product_key = "m" * 256
            conn.execute("INSERT INTO user_product_decisions (tenant_id, user_id, product_key, decision) VALUES (%s, %s, %s, 'allowed')", (tenant_b, user_b, max_product_key))
            conn.execute("INSERT INTO user_product_decision_events (tenant_id, user_id, product_key, after_decision, actor_subject, action) VALUES (%s, %s, %s, 'allowed', 'user-b', 'allowed')", (tenant_b, user_b, max_product_key))
            conn.execute("""
                INSERT INTO telegram_actor_contexts
                  (context_hash, telegram_user_id, tenant_id, user_id, created_at, expires_at)
                VALUES (repeat('a', 64), 424242, %s, %s, now(), now() + interval '15 minutes')
                """, (tenant_b, user_b))

            with conn.transaction():
                conn.execute(sql.SQL("SET LOCAL ROLE {}").format(sql.Identifier(role)))
                conn.execute("SELECT set_config('app.tenant_id', %s, true)", (str(tenant_a),))
                conn.execute("SELECT set_config('app.subject', %s, true)", ("user-a",))
                assert conn.execute("SELECT count(*) FROM tenants WHERE id = %s", (tenant_b,)).fetchone()[0] == 0
                assert conn.execute("SELECT count(*) FROM memberships WHERE subject = 'user-b'").fetchone()[0] == 0
                assert conn.execute("SELECT count(*) FROM user_product_decisions WHERE tenant_id = %s", (tenant_b,)).fetchone()[0] == 0
                assert conn.execute("SELECT count(*) FROM merchant_mappings WHERE tenant_id = %s", (tenant_b,)).fetchone()[0] == 0
                assert conn.execute("SELECT count(*) FROM merchant_classification_cache WHERE tenant_id = %s", (tenant_b,)).fetchone()[0] == 0
                assert conn.execute("SELECT count(*) FROM user_product_decision_events WHERE tenant_id = %s", (tenant_b,)).fetchone()[0] == 0
                assert conn.execute("SELECT count(*) FROM telegram_actor_contexts WHERE tenant_id = %s", (tenant_b,)).fetchone()[0] == 0
                conn.execute("INSERT INTO user_product_decisions (tenant_id, user_id, product_key, decision) VALUES (%s, %s, 'bread', 'allowed')", (tenant_a, user_a))
                conn.execute("INSERT INTO user_product_decision_events (tenant_id, user_id, product_key, after_decision, actor_subject, action) VALUES (%s, %s, 'bread', 'allowed', 'user-a', 'allowed')", (tenant_a, user_a))
                conn.execute("INSERT INTO transactions (tenant_id, owner_subject, owner_user_id, type, amount, category_code, occurred_at) VALUES (%s, 'user-a', %s, 'expense', 12.34, 'food', now())", (tenant_a, user_a))
                conn.execute("""INSERT INTO transaction_drafts
                    (tenant_id, owner_user_id, owner_subject, type, amount, category_code, occurred_at,
                     provider, model_version, prompt_version, create_idempotency_key, create_request_hash)
                    VALUES (%s, %s, 'user-a', 'expense', 3.00, 'food', now(), 'test', 'model-1', 'prompt-1',
                      'draft-create-0001', repeat('a', 64))""", (tenant_a, user_a))
                conn.execute("""INSERT INTO transaction_drafts
                    (tenant_id, owner_user_id, owner_subject, type, amount, category_code, occurred_at,
                     provider, model_version, prompt_version, create_idempotency_key, create_request_hash)
                    VALUES (%s, %s, 'user-a', 'debt_payment', 3.00, 'долги', now(), 'test', 'model-1', 'prompt-1',
                      'draft-debt-create-01', repeat('c', 64))""", (tenant_a, user_a))
                document_id = conn.execute("""
                    INSERT INTO documents
                      (tenant_id, uploaded_by_user_id, storage_key, content_sha256, mime_type, byte_size, original_name)
                    VALUES (%s, %s, 'tenant-a/test-receipt.png', repeat('d', 64), 'image/png', 1024, 'receipt.png')
                    RETURNING id
                    """, (tenant_a, user_a)).fetchone()[0]
                receipt_id = conn.execute("""
                    INSERT INTO receipts (tenant_id, owner_user_id, owner_subject, document_id, cash_total, items_total,
                                          merchant, create_idempotency_key, create_request_hash)
                    VALUES (%s, %s, 'user-a', %s, 30.00, 30.00, 'SAMPLE', 'receipt-migration-0001', repeat('f', 64))
                    RETURNING id
                    """, (tenant_a, user_a, document_id)).fetchone()[0]
                receipt_item_id = conn.execute("""
                    INSERT INTO receipt_items (tenant_id, receipt_id, ordinal, name, line_sum)
                    VALUES (%s, %s, 1, 'Хлеб', 30.00) RETURNING id
                    """, (tenant_a, receipt_id)).fetchone()[0]
                conn.execute("""
                    INSERT INTO receipt_readings
                      (tenant_id, receipt_id, reader, provider, model_version, prompt_version,
                       algorithm_version, result_fields)
                    VALUES (%s, %s, 'ocr', 'tesseract', 'tesseract-5.3', 'tesseract-ocr.v1',
                            'receipt-reconciliation.v1', '{"total":"30.00"}'::jsonb)
                    """, (tenant_a, receipt_id))
                conn.execute("""
                    INSERT INTO receipt_reviews
                      (tenant_id, receipt_id, receipt_item_id, actor_subject, action, after_state,
                       verdict_source, algorithm_version)
                    VALUES (%s, %s, %s, 'user-a', 'verdict.changed', '{"verdict":"useful"}'::jsonb,
                            'human', 'receipt-reconciliation.v1')
                    """, (tenant_a, receipt_id, receipt_item_id))
                assert conn.execute("SELECT count(*) FROM transaction_drafts WHERE tenant_id = %s", (tenant_b,)).fetchone()[0] == 0
                assert conn.execute("SELECT count(*) FROM receipts WHERE tenant_id = %s", (tenant_b,)).fetchone()[0] == 0
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
                with pytest.raises(psycopg.errors.InsufficientPrivilege):
                    with conn.transaction():
                        conn.execute("""
                            INSERT INTO telegram_actor_contexts
                              (context_hash, telegram_user_id, tenant_id, user_id, created_at, expires_at)
                            VALUES (repeat('c', 64), 424243, %s, %s, now(), now() + interval '15 minutes')
                            """, (tenant_a, user_a))
                with pytest.raises(psycopg.errors.InsufficientPrivilege):
                    with conn.transaction():
                        conn.execute("""INSERT INTO transaction_drafts
                            (tenant_id, owner_user_id, owner_subject, type, amount, category_code, occurred_at,
                             provider, model_version, prompt_version, create_idempotency_key, create_request_hash)
                            VALUES (%s, %s, 'user-a', 'expense', 3.00, 'food', now(), 'test', 'model-1', 'prompt-1',
                              'draft-cross-tenant-01', repeat('b', 64))""", (tenant_b, user_a))
                with pytest.raises(psycopg.errors.InsufficientPrivilege):
                    with conn.transaction():
                        conn.execute("""
                            INSERT INTO receipts
                              (tenant_id, owner_user_id, owner_subject, cash_total,
                               create_idempotency_key, create_request_hash)
                            VALUES (%s, %s, 'user-a', 5.00, 'receipt-cross-tenant-01', repeat('e', 64))
                            """, (tenant_b, user_a))

            assert conn.execute("SELECT count(*) FROM transactions WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM transaction_drafts WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 2
            assert conn.execute("SELECT count(*) FROM documents WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM receipts WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM receipt_items WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM receipt_readings WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
            assert conn.execute("SELECT count(*) FROM receipt_reviews WHERE tenant_id = %s", (tenant_a,)).fetchone()[0] == 1
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

    schemas = {
        "transaction": json.loads(Path("contracts/events/finance.transaction.v1.schema.json").read_text(encoding="utf-8")),
        "budget": json.loads(Path("contracts/events/finance.budget.v1.schema.json").read_text(encoding="utf-8")),
        "debt": json.loads(Path("contracts/events/finance.debt.v1.schema.json").read_text(encoding="utf-8")),
        "receipt": json.loads(Path("contracts/events/finance.receipt.v1.schema.json").read_text(encoding="utf-8")),
    }
    with psycopg.connect(dsn) as conn:
        if conn.execute("SELECT to_regclass('public.outbox_events')").fetchone()[0] is None:
            pytest.skip("Java core integration tests have not installed the public schema")
        events = conn.execute("SELECT payload FROM public.outbox_events ORDER BY created_at DESC").fetchall()
    assert events, "the Java API integration test should have emitted a transaction event"
    for (event,) in events:
        jsonschema.validate(event, schemas[event["aggregate_type"]])


def test_csv_export_snapshot_migration_is_tenant_isolated_and_immutable():
    migration = Path("services/core/src/main/resources/db/migration/V42__csv_exports.sql")
    assert migration.exists(), "F52 requires durable export jobs and immutable request-time rows"
    sql = migration.read_text(encoding="utf-8").lower()
    cleanup_migration = Path("services/core/src/main/resources/db/migration/V43__allow_export_snapshot_cleanup.sql")
    assert cleanup_migration.exists(), "expired snapshots must be removable after immutable processing"
    assert "before update on export_snapshot_rows" in cleanup_migration.read_text(encoding="utf-8").lower()
    for table in ("export_jobs", "export_snapshot_rows"):
        assert f"create table {table}" in sql
        assert f"alter table {table} enable row level security" in sql
        assert f"alter table {table} force row level security" in sql
        assert f"create policy {table}_tenant_isolation" in sql
        assert "current_setting('app.tenant_id'" in sql
    for required in (
        "format_version varchar",
        "requester_user_id uuid",
        "requester_timezone varchar",
        "snapshot_at timestamptz",
        "row_count integer",
        "from_date date",
        "to_date date",
        "transaction_id uuid",
        "row_number bigint",
        "foreign key (tenant_id, export_id)",
        "check (format_version = 'csv-v1')",
    ):
        assert required in sql
