import asyncio
import json

import httpx
import pytest

from services.python.intelligence.merchant_classification import (
    CATEGORIES,
    PROMPT_VERSION,
    build_prompt,
    normalize_classifications,
    request_classifications,
    validate_context,
)


def context():
    return validate_context({"merchants": ["  ПЯТЁРОЧКА\u00a024 ", "City Taxi"]})


def test_context_normalizes_and_batches_only_unique_merchant_names():
    assert context() == {"merchants": ["пятёрочка 24", "city taxi"]}
    with pytest.raises(ValueError):
        validate_context({"merchants": ["Shop", " shop "]})
    with pytest.raises(ValueError):
        validate_context({"merchants": ["Shop"], "tenantId": "must-not-leak"})
    with pytest.raises(ValueError):
        validate_context({"merchants": ["Shop"] * 51})


def test_model_output_must_cover_exact_input_with_valid_categories_and_confidence():
    result = normalize_classifications({"classifications": [
        {"merchant": "city taxi", "categoryCode": "транспорт", "confidence": 0.94},
        {"merchant": "пятёрочка 24", "categoryCode": "еда", "confidence": "0.88"},
    ]}, context()["merchants"])

    assert result == [
        {"merchant": "пятёрочка 24", "categoryCode": "еда", "confidence": "0.880"},
        {"merchant": "city taxi", "categoryCode": "транспорт", "confidence": "0.940"},
    ]
    with pytest.raises(ValueError):
        normalize_classifications({"classifications": [
            {"merchant": "пятёрочка 24", "categoryCode": "private", "confidence": 0.9},
            {"merchant": "city taxi", "categoryCode": "транспорт", "confidence": 0.9},
        ]}, context()["merchants"])
    with pytest.raises(ValueError):
        normalize_classifications({"classifications": [
            {"merchant": "пятёрочка 24", "categoryCode": "еда", "confidence": 1.1},
            {"merchant": "city taxi", "categoryCode": "транспорт", "confidence": 0.9},
        ]}, context()["merchants"])
    for invalid_category in (None, [], {"category": "еда"}):
        with pytest.raises(ValueError):
            normalize_classifications({"classifications": [
                {"merchant": "пятёрочка 24", "categoryCode": invalid_category, "confidence": 0.9},
                {"merchant": "city taxi", "categoryCode": "транспорт", "confidence": 0.9},
            ]}, context()["merchants"])


def test_prompt_and_request_keep_only_names_and_versioned_hypotheses():
    submitted = context()
    prompt = build_prompt(submitted)
    assert all(category in prompt for category in CATEGORIES)
    assert "tenantId" not in prompt and "amount" not in prompt and "description" not in prompt

    seen = {}

    def respond(request):
        seen["request"] = request
        return httpx.Response(200, json={"response": json.dumps({"classifications": [
            {"merchant": "city taxi", "categoryCode": "транспорт", "confidence": 0.94},
            {"merchant": "пятёрочка 24", "categoryCode": "еда", "confidence": 0.88},
        ]}, ensure_ascii=False)})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            return await request_classifications(submitted, http_client=client,
                                                 ollama_url="http://ollama:11434/", model="qwen-test")

    result = asyncio.run(run())
    assert result["classifications"][0]["categoryCode"] == "еда"
    assert result["modelVersion"] == "qwen-test"
    assert result["promptVersion"] == PROMPT_VERSION
    assert json.loads(seen["request"].content)["format"] == "json"
