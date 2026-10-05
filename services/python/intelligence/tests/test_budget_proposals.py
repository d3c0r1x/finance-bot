import asyncio
import json

import httpx
import pytest

from services.python.intelligence.budget_proposals import (
    PROMPT_VERSION,
    build_prompt,
    normalize_shares,
    request_shares,
)


@pytest.fixture
def context():
    return {
        "monthlyIncome": "100000.00",
        "historyDays": 45,
        "categoryCodes": ["еда", "транспорт", "прочее"],
        "monthlyExpenseByMonth": {"2026-09": {"еда": "12000.00", "прочее": "1000.00"}},
        "monthlyIncomeByMonth": {"2026-09": "95000.00"},
        "currentFamilyLimits": {"еда": "20000.00", "транспорт": "5000.00", "прочее": "5000.00", "долги": "0.00"},
    }


def test_prompt_contains_only_allowlisted_financial_aggregates(context):
    prompt = build_prompt(context)

    assert "100000.00" in prompt
    assert "2026-09" in prompt
    assert "tenantId" not in prompt
    assert "ownerUserId" not in prompt
    assert "description" not in prompt
    assert "receipt" not in prompt.lower()


def test_model_shares_are_validated_and_rounding_is_normalized_to_exactly_100(context):
    shares = normalize_shares({"еда": 66.67, "транспорт": 16.665, "прочее": 16.665}, context["categoryCodes"])

    assert shares == {"еда": "66.66", "транспорт": "16.67", "прочее": "16.67"}
    assert sum(map(float, shares.values())) == 100.0


@pytest.mark.parametrize("value", [
    {"еда": 100},
    {"еда": 96, "транспорт": 1, "прочее": 1},
    {"еда": -1, "транспорт": 50, "прочее": 51},
    {"еда": 50, "транспорт": 50, "прочее": 0, "долги": 0},
])
def test_invalid_model_output_is_rejected(value, context):
    with pytest.raises(ValueError):
        normalize_shares(value, context["categoryCodes"])


def test_ollama_adapter_uses_json_mode_and_returns_model_and_prompt_versions(context):
    seen = {}

    def respond(request):
        seen["request"] = request
        return httpx.Response(200, json={"response": json.dumps({
            "shares": {"еда": 60, "транспорт": 20, "прочее": 20},
        }, ensure_ascii=False)})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            return await request_shares(context, http_client=client, ollama_url="http://ollama:11434/",
                                       model="qwen2.5:7b-instruct", api_key="test-key")

    result = asyncio.run(run())

    assert result == {
        "shares": {"еда": "60.00", "транспорт": "20.00", "прочее": "20.00"},
        "modelVersion": "qwen2.5:7b-instruct",
        "promptVersion": PROMPT_VERSION,
    }
    assert seen["request"].url == "http://ollama:11434/api/generate"
    assert seen["request"].headers["Authorization"] == "Bearer test-key"
    assert json.loads(seen["request"].content)["format"] == "json"
