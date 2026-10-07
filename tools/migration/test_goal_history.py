import json
import sqlite3
import urllib.error
from pathlib import Path
from uuid import UUID

import pytest
import re

from tools.migration.goal_history import (
    LegacyUserMapping,
    extract_goal_history,
    import_goal_history,
    _http_sender,
    _RejectRedirects,
)


TENANT = "0199b81a-4a9c-7000-8000-000000000001"
OWNER = "0199b81a-4a9c-7000-8000-000000000002"


def _row(**overrides):
    value = {
        "key": "product:coffee",
        "name": "Coffee",
        "unit": "sum",
        "target": 2,
        "limit": 500.0,
        "bought": 3,
        "spent": 450.25,
        "met": True,
        "saved": 49.75,
        "window": "01.01–31.01",
        "started_at": "2026-01-01 09:00:00",
        "closed_at": "2026-01-31 09:00:00",
    }
    value.update(overrides)
    return value


def _source(tmp_path: Path, values: dict[str, str]) -> Path:
    path = tmp_path / "legacy.sqlite3"
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.executemany("INSERT INTO settings (key, value) VALUES (?, ?)", values.items())
    return path


def _mapping(timezone="Europe/Moscow"):
    return {"123": LegacyUserMapping(TENANT, OWNER, timezone)}


def test_extracts_all_matching_settings_and_preserves_normalized_legacy_values(tmp_path):
    path = _source(tmp_path, {
        "advice:goal_history:123": json.dumps([
            _row(),
            _row(key="cat:sweet", name="Sweet", unit="count", target=1,
                 limit=0, spent=None, saved=0, started_at="2026-01-01 00:00:00",
                 closed_at="2026-01-31 00:00:00"),
        ]),
        "advice:goal_history:456": "[]",
        "advice:goal:123": "not a history row",
        "other:setting": "ignored",
    })
    mappings = {
        **_mapping(),
        "456": LegacyUserMapping("0199b81a-4a9c-7000-8000-000000000003",
                                 "0199b81a-4a9c-7000-8000-000000000004", "UTC"),
    }

    report = extract_goal_history(path, mappings)

    assert report.source_entries == 2
    assert report.quarantined == []
    group = report.imports[(UUID(TENANT), UUID(OWNER))]
    assert len(group) == 2
    sum_goal, count_goal = group
    assert sum_goal["scope"] == "product"
    assert sum_goal["unit"] == "sum"
    assert sum_goal["legacyTarget"] == 2
    assert sum_goal["legacyLimit"] == "500.00"
    assert sum_goal["countTarget"] == 0
    assert sum_goal["monthlyLimit"] == "500.00"
    assert sum_goal["spent"] == "450.25"
    assert sum_goal["saved"] == "49.75"
    assert sum_goal["acceptedAt"] == "2026-01-01T06:00:00Z"
    assert sum_goal["completedAt"] == "2026-01-31T06:00:00Z"
    assert re.fullmatch(r"goal-history:[a-f0-9]{64}:0", sum_goal["legacyKey"])
    assert count_goal["scope"] == "group"
    assert count_goal["unit"] == "count"
    assert count_goal["countTarget"] == 1
    assert count_goal["monthlyLimit"] is None
    assert count_goal["legacyLimit"] == "0.00"


def test_duplicate_rows_get_deterministic_distinct_keys_and_source_stays_read_only(tmp_path):
    encoded = json.dumps([_row(), _row()])
    path = _source(tmp_path, {"advice:goal_history:123": encoded})

    first = extract_goal_history(path, _mapping())
    second = extract_goal_history(path, _mapping())

    rows = first.imports[(UUID(TENANT), UUID(OWNER))]
    assert rows[0]["legacyKey"] != rows[1]["legacyKey"]
    assert [row["legacyKey"] for row in rows] == [
        row["legacyKey"] for row in second.imports[(UUID(TENANT), UUID(OWNER))]
    ]
    with pytest.raises(sqlite3.OperationalError):
        with sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True) as db:
            db.execute("DELETE FROM settings")


def test_quarantines_unmapped_users_invalid_rows_amounts_and_ambiguous_local_time(tmp_path):
    path = _source(tmp_path, {
        "advice:goal_history:123": json.dumps([
            _row(name=""),
            _row(spent=1.001),
            _row(started_at="2026-10-25 02:30:00"),
            "not an object",
        ]),
        "advice:goal_history:456": "{broken",
        "advice:goal_history:789": json.dumps([_row()]),
    })
    mappings = {
        "123": LegacyUserMapping(TENANT, OWNER, "Europe/Berlin"),
        "456": LegacyUserMapping(TENANT, OWNER, "Europe/Moscow"),
    }

    report = extract_goal_history(path, mappings)

    assert report.source_entries == 6
    assert report.imports == {}
    reasons = [item.reason for item in report.quarantined]
    assert "missing_name" in reasons
    assert "invalid_money_precision" in reasons
    assert "ambiguous_local_time" in reasons
    assert "invalid_entry" in reasons
    assert "invalid_json" in reasons
    assert "unmapped_user" in reasons
    assert all("Coffee" not in item.reason for item in report.quarantined)


def test_quarantines_nonexistent_local_time_and_missing_timezone(tmp_path):
    path = _source(tmp_path, {
        "advice:goal_history:123": json.dumps([
            _row(started_at="2026-03-29 02:30:00"),
        ]),
        "advice:goal_history:456": json.dumps([_row()]),
    })
    mappings = {
        "123": LegacyUserMapping(TENANT, OWNER, "Europe/Berlin"),
        "456": LegacyUserMapping(TENANT, OWNER, ""),
    }

    report = extract_goal_history(path, mappings)

    assert {item.reason for item in report.quarantined} == {
        "nonexistent_local_time", "missing_timezone"
    }


def test_counts_and_quarantines_malformed_history_setting_key(tmp_path):
    path = _source(tmp_path, {"advice:goal_history:not-a-user": "[]"})

    report = extract_goal_history(path, {})

    assert report.source_entries == 1
    assert report.quarantined[0].reason == "invalid_user_id"


def test_preserves_subsecond_timestamp_precision_and_quarantines_java_integer_overflow(tmp_path):
    path = _source(tmp_path, {
        "advice:goal_history:123": json.dumps([
            _row(started_at="2026-01-01 09:00:00.123456",
                 closed_at="2026-01-31 09:00:00.654321"),
            _row(bought=2_147_483_648),
        ]),
    })

    report = extract_goal_history(path, _mapping())

    row = report.imports[(UUID(TENANT), UUID(OWNER))][0]
    assert row["acceptedAt"] == "2026-01-01T06:00:00.123456Z"
    assert row["completedAt"] == "2026-01-31T06:00:00.654321Z"
    assert report.quarantined[0].reason == "invalid_bought"


def test_preserves_legacy_text_snapshots_without_trimming(tmp_path):
    path = _source(tmp_path, {
        "advice:goal_history:123": json.dumps([_row(
            key=" cat:sweet ", name=" Sweet ", window=" 01.01–31.01 "
        )]),
    })

    report = extract_goal_history(path, _mapping())

    row = report.imports[(UUID(TENANT), UUID(OWNER))][0]
    assert row["key"] == " cat:sweet "
    assert row["scope"] == "group"
    assert row["name"] == " Sweet "
    assert row["window"] == " 01.01–31.01 "


def test_quarantines_money_larger_than_core_decimal_capacity(tmp_path):
    path = _source(tmp_path, {
        "advice:goal_history:123": json.dumps([_row(spent=10 ** 35)]),
    })

    report = extract_goal_history(path, _mapping())

    assert report.imports == {}
    assert report.quarantined[0].reason == "invalid_money"


@pytest.mark.parametrize(
    ("overrides", "reason"),
    [
        ({"unit": "count", "target": 0}, "invalid_target"),
        ({"unit": "sum", "limit": None}, "missing_monthly_limit"),
    ],
)
def test_quarantines_rows_that_core_would_reject(overrides, reason, tmp_path):
    path = _source(tmp_path, {
        "advice:goal_history:123": json.dumps([_row(**overrides)]),
    })

    report = extract_goal_history(path, _mapping())

    assert report.imports == {}
    assert report.quarantined[0].reason == reason


def test_dry_run_cli_reports_counts_without_goal_content_or_secret(tmp_path, capsys):
    source = _source(tmp_path, {
        "advice:goal_history:123": json.dumps([_row(name="private goal title")]),
    })
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"legacyUsers": {
        "123": {"tenantId": TENANT, "ownerUserId": OWNER, "timezone": "UTC"},
    }}), encoding="utf-8")

    from tools.migration.goal_history import main

    assert main([str(source), str(manifest)]) == 0
    output = capsys.readouterr().out
    assert '"importable": 1' in output
    assert "private goal title" not in output
    assert "FINANCE_MIGRATION_SERVICE_TOKEN" not in output


def test_apply_requires_quarantine_review_before_upload(tmp_path, capsys, monkeypatch):
    source = _source(tmp_path, {
        "advice:goal_history:123": json.dumps([_row(), _row(name="")]),
    })
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"legacyUsers": {
        "123": {"tenantId": TENANT, "ownerUserId": OWNER, "timezone": "UTC"},
    }}), encoding="utf-8")
    monkeypatch.setenv("FINANCE_MIGRATION_SERVICE_TOKEN", "migration-secret")

    from tools.migration.goal_history import main

    result = main([str(source), str(manifest), "--apply", "--core-url", "https://core.example"])

    assert result == 2
    captured = capsys.readouterr()
    assert "--allow-quarantine" in captured.err
    assert "migration-secret" not in captured.err


def test_import_splits_bounded_batches_and_validates_server_accounting():
    rows = [{"legacyKey": f"row-{index}"} for index in range(501)]
    imports = {(UUID(TENANT), UUID(OWNER)): rows}
    calls = []

    def sender(url, headers, body):
        calls.append((url, headers, json.loads(body)))
        count = len(json.loads(body)["outcomes"])
        return 200, {"inserted": count, "alreadyPresent": 0}

    report = import_goal_history(
        "https://core.example", "migration-secret", imports, sender=sender
    )

    assert [len(call[2]["outcomes"]) for call in calls] == [500, 1]
    assert all(call[0] == "https://core.example/internal/v1/migrations/goal-history"
               for call in calls)
    assert all(call[1]["X-Finance-Migration-Token"] == "migration-secret"
               for call in calls)
    assert report.sent == 501
    assert report.inserted == 501
    assert report.already_present == 0
    assert "migration-secret" not in repr(report)


def test_import_stops_when_server_accounting_does_not_match_batch():
    imports = {(UUID(TENANT), UUID(OWNER)): [{"legacyKey": "row"}]}

    def sender(_url, _headers, _body):
        return 200, {"inserted": 0, "alreadyPresent": 0}

    with pytest.raises(ValueError, match="accounting mismatch"):
        import_goal_history("https://core.example", "secret", imports, sender=sender)


def test_migration_token_never_goes_to_plain_http_non_loopback_host():
    imports = {(UUID(TENANT), UUID(OWNER)): [{"legacyKey": "row"}]}
    called = False

    def sender(_url, _headers, _body):
        nonlocal called
        called = True
        return 200, {"inserted": 1, "alreadyPresent": 0}

    with pytest.raises(ValueError, match="HTTPS"):
        import_goal_history("http://core.example", "secret", imports, sender=sender)
    assert not called


def test_http_sender_builds_opener_that_rejects_redirects(monkeypatch):
    class FakeOpener:
        def open(self, request, timeout):
            assert request.get_header("X-finance-migration-token") == "secret"
            assert timeout == 30
            raise urllib.error.HTTPError(request.full_url, 302, "Found", {}, None)

    def build_opener(*handlers):
        assert any(isinstance(handler, _RejectRedirects) for handler in handlers)
        return FakeOpener()

    monkeypatch.setattr("tools.migration.goal_history.urllib.request.build_opener", build_opener)

    status, response = _http_sender(
        "https://core.example/redirect",
        {"X-Finance-Migration-Token": "secret"},
        b"{}",
    )

    assert status == 302
    assert response == {}
