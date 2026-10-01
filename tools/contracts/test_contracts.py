"""Validate machine-readable API, event and feature-parity contracts."""

import json
import re
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator, ValidationError
from openapi_spec_validator import validate


ROOT = Path(__file__).resolve().parents[2]


def test_openapi_document_and_operation_contracts_are_valid():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))

    validate(spec)

    assert spec["openapi"] == "3.1.0"
    assert spec["components"]["securitySchemes"]["keycloakOidc"]["type"] == "openIdConnect"
    create = spec["paths"]["/api/v1/tenants/{tenantId}/transactions"]["post"]
    assert any(ref["$ref"].endswith("IdempotencyKey") for ref in create["parameters"])
    amount = spec["components"]["schemas"]["CreateTransaction"]["properties"]["amount"]
    assert amount["type"] == "string"
    assert "pattern" in amount
    money = re.compile(amount["pattern"])
    assert all(money.fullmatch(value) for value in ["0.01", "0.1", "12", "12.50"])
    assert not any(money.fullmatch(value) for value in ["0", "0.00", "-1", "12.345"])


def test_event_schema_accepts_a_valid_transaction_snapshot():
    schema = json.loads((ROOT / "contracts/events/finance.transaction.v1.schema.json").read_text("utf-8"))
    Draft202012Validator.check_schema(schema)
    event = {
        "event_id": "00000000-0000-4000-8000-000000000001",
        "event_type": "transaction.created",
        "schema_version": 1,
        "tenant_id": "00000000-0000-4000-8000-000000000002",
        "aggregate_type": "transaction",
        "aggregate_id": "00000000-0000-4000-8000-000000000003",
        "aggregate_version": 1,
        "occurred_at": "2026-10-01T12:00:00Z",
        "recorded_at": "2026-10-01T12:00:01Z",
        "producer": "core",
        "payload": {
            "owner_user_id": "00000000-0000-4000-8000-000000000004",
            "type": "expense",
            "amount": "12.50",
            "currency": "RUB",
            "category_code": "food",
            "description": "Lunch",
            "status": "posted",
            "financial_occurred_at": "2026-10-01T12:00:00Z",
        },
    }

    Draft202012Validator(schema).validate(event)


@pytest.mark.parametrize("amount", ["0", "-1.00", "01.00", "12.345", 12.5])
def test_event_schema_rejects_invalid_money_representation(amount):
    schema = json.loads((ROOT / "contracts/events/finance.transaction.v1.schema.json").read_text("utf-8"))
    event = {
        "event_id": "00000000-0000-4000-8000-000000000001",
        "event_type": "transaction.created",
        "schema_version": 1,
        "tenant_id": "00000000-0000-4000-8000-000000000002",
        "aggregate_type": "transaction",
        "aggregate_id": "00000000-0000-4000-8000-000000000003",
        "aggregate_version": 1,
        "occurred_at": "2026-10-01T12:00:00Z",
        "recorded_at": "2026-10-01T12:00:01Z",
        "producer": "core",
        "payload": {
            "owner_user_id": "00000000-0000-4000-8000-000000000004",
            "type": "expense",
            "amount": amount,
            "currency": "RUB",
            "category_code": "food",
            "description": "Lunch",
            "status": "posted",
            "financial_occurred_at": "2026-10-01T12:00:00Z",
        },
    }

    with pytest.raises(ValidationError):
        Draft202012Validator(schema).validate(event)


def test_feature_registry_accounts_for_all_legacy_and_new_parity_ids():
    registry = yaml.safe_load((ROOT / "contracts/parity/feature-parity.yaml").read_text("utf-8"))
    features = registry["features"]

    assert [feature["id"] for feature in features] == [f"F{i:02d}" for i in range(1, 61)]
    assert all(feature["status"] == "planned" for feature in features)
    assert all(feature["feature"] and feature["acceptance"] for feature in features)
