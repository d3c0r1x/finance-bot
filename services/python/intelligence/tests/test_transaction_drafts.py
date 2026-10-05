import asyncio
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from services.python.intelligence.transaction_drafts import (
    normalize_amount,
    request_transaction_draft,
    validate_draft_context,
    validate_draft_output,
)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("2 000", "2000.00"),
        ("2 тыс", "2000.00"),
        ("1,5к", "1500.00"),
        ("1 234,50 ₽", "1234.50"),
    ],
)
def test_russian_transaction_amounts_normalize_without_loss(raw, expected):
    assert normalize_amount(raw) == expected


@pytest.mark.parametrize("raw", ["0", "-10", "2.001", "два", "1,5 млн", "NaN"])
def test_unsafe_or_ambiguous_transaction_amounts_are_rejected(raw):
    with pytest.raises(ValueError):
        normalize_amount(raw)


def test_draft_context_is_bounded_and_uses_explicit_member_timezone():
    context = {
        "text": "Такси 2 тыс",
        "timezone": "Europe/Moscow",
        "now": "2026-10-01T12:00:00+03:00",
    }
    assert validate_draft_context(context) == context

    for invalid in (
        {**context, "tenantId": "must-not-leak"},
        {**context, "text": " "},
        {**context, "text": "x" * 501},
        {**context, "timezone": "Unknown/Zone"},
        {**context, "now": "not-a-time"},
    ):
        with pytest.raises(ValueError):
            validate_draft_context(invalid)


def test_draft_output_accepts_only_valid_confirmable_expense_or_income():
    value = validate_draft_output(
        {
            "type": "expense",
            "amount": "2 тыс",
            "categoryCode": "transport",
            "subcategoryCode": None,
            "description": "Такси",
            "occurredAt": "2026-10-01T09:00:00+03:00",
        },
        now=datetime(2026, 10, 1, 9, 0, tzinfo=ZoneInfo("Europe/Moscow")),
    )
    assert value["amount"] == "2000.00"
    assert value["type"] == "expense"
    assert value["occurredAt"] == "2026-10-01T09:00:00+03:00"

    income = validate_draft_output(
        {
            "type": "income",
            "amount": "125000",
            "categoryCode": "income",
            "subcategoryCode": None,
            "description": "Зарплата за октябрь",
            "occurredAt": "2026-10-01T09:00:00+03:00",
        },
        now=datetime(2026, 10, 1, 9, 0, tzinfo=ZoneInfo("Europe/Moscow")),
    )
    assert income["type"] == "income"
    assert income["description"] == "Зарплата за октябрь"
    assert income["amount"] == "125000.00"

    payment = validate_draft_output(
        {
            "type": "debt_payment",
            "amount": "1,5к",
            "categoryCode": "долги",
            "subcategoryCode": None,
            "description": "Платёж по кредитке",
            "occurredAt": "2026-10-01T09:00:00+03:00",
        },
        now=datetime(2026, 10, 1, 9, 0, tzinfo=ZoneInfo("Europe/Moscow")),
    )
    assert payment["type"] == "debt_payment"
    assert payment["amount"] == "1500.00"

    with pytest.raises(ValueError):
        validate_draft_output({"type": "transfer", "amount": "100"}, now=datetime.now().astimezone())


def test_prompt_keeps_original_text_local_and_requests_json_fields():
    from services.python.intelligence.transaction_drafts import PROMPT_VERSION, build_draft_prompt

    prompt = build_draft_prompt(
        {"text": "Такси 2 тыс", "timezone": "Europe/Moscow", "now": "2026-10-01T12:00:00+03:00"}
    )
    assert "Такси 2 тыс" in prompt
    assert '"type"' in prompt and '"amount"' in prompt and '"occurredAt"' in prompt
    assert "Europe/Moscow" in prompt
    assert "для дохода" in prompt.casefold() and "в поле description" in prompt.casefold()
    assert PROMPT_VERSION == "transaction-draft.v2"


def test_malformed_model_json_is_rejected_before_a_draft_can_be_returned():
    class Response:
        def raise_for_status(self):
            pass

        @staticmethod
        def json():
            return {"response": "{not-json"}

    class HttpClient:
        async def post(self, url, *, json, headers):
            assert url == "http://ollama/api/generate"
            assert json["format"] == "json"
            return Response()

    context = {"text": "Такси 2 тыс", "timezone": "Europe/Moscow",
               "now": "2026-10-01T12:00:00+03:00"}
    with pytest.raises(ValueError, match="Ollama did not return JSON"):
        asyncio.run(request_transaction_draft(context, http_client=HttpClient(),
                                              ollama_url="http://ollama", model="test-model"))
