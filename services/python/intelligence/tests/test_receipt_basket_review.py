import asyncio
import json

import pytest

from services.python.intelligence.receipt_basket_review import (
    build_prompt,
    request_basket_review,
    validate_context,
    validate_result,
)


def context():
    return {"items": [{"ordinal": 1, "name": "Пиво 0.5 л"}, {"ordinal": 2, "name": "Молоко"}]}


def test_context_is_bounded_ordered_and_contains_only_item_labels():
    checked = validate_context(context())
    assert checked == context()
    prompt = build_prompt(checked)
    assert "Пиво 0.5 л" in prompt
    assert "Молоко" in prompt
    assert "lineSum" not in prompt
    assert "cashTotal" not in prompt


@pytest.mark.parametrize("invalid", [
    {"tenantId": "tenant", "items": []},
    {"items": [{"ordinal": 1, "name": "Хлеб", "lineSum": "100.00"}]},
    {"items": [{"ordinal": 1, "name": "Хлеб"}, {"ordinal": 1, "name": "Кефир"}]},
    {"items": [{"ordinal": 2, "name": "Хлеб"}]},
    {"items": [{"ordinal": 1, "name": "   "}]},
])
def test_context_rejects_extra_fields_duplicate_or_unbounded_identity(invalid):
    with pytest.raises(ValueError):
        validate_context(invalid)


def test_model_result_must_cover_same_lines_and_cannot_return_money():
    valid = {"items": [
        {"ordinal": 1, "verdict": "harmful", "reason": "алкоголь", "action": "ограничить покупку"},
        {"ordinal": 2, "verdict": "neutral", "reason": "", "action": ""},
    ]}
    assert validate_result(valid, context()) == valid
    with pytest.raises(ValueError):
        validate_result({"items": [valid["items"][0]]}, context())
    with pytest.raises(ValueError):
        validate_result({"items": [{**valid["items"][0], "amount": "999.00"}, valid["items"][1]]}, context())


def test_ollama_request_uses_json_and_returns_validated_provenance():
    calls = []

    class Response:
        def raise_for_status(self):
            return None

        def json(self):
            return {"model": "qwen-text:8b", "response": json.dumps({"items": [
                {"ordinal": 1, "verdict": "harmful", "reason": "алкоголь", "action": "ограничить покупку"},
                {"ordinal": 2, "verdict": "neutral", "reason": "", "action": ""},
            ]}, ensure_ascii=False)}

    class Client:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

        async def post(self, url, **kwargs):
            calls.append((url, kwargs))
            return Response()

    result = asyncio.run(request_basket_review(
        context(), http_client=Client(), ollama_url="http://ollama:11434", model="qwen-text:8b"))

    assert result["items"][0]["ordinal"] == 1
    assert result["modelVersion"] == "qwen-text:8b"
    assert result["promptVersion"] == "receipt-basket.v1"
    assert calls[0][0] == "http://ollama:11434/api/generate"
    assert calls[0][1]["json"]["format"] == "json"
