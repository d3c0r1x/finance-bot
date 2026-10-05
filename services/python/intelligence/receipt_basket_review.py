"""Bounded, amount-free basket suggestions for Java's deterministic receipt policy."""

from __future__ import annotations

import json
from typing import Any


PROMPT_VERSION = "receipt-basket.v1"
VERDICTS = {"useful", "neutral", "harmful", "unnecessary"}


def validate_context(context: Any) -> dict[str, Any]:
    if not isinstance(context, dict) or set(context) != {"items"}:
        raise ValueError("Receipt basket context is invalid")
    items = context["items"]
    if not isinstance(items, list) or not 1 <= len(items) <= 80:
        raise ValueError("Receipt basket item count is invalid")
    normalized = []
    for expected_ordinal, item in enumerate(items, start=1):
        if not isinstance(item, dict) or set(item) != {"ordinal", "name"}:
            raise ValueError("Receipt basket item context is invalid")
        ordinal, name = item["ordinal"], item["name"]
        if (not isinstance(ordinal, int) or isinstance(ordinal, bool) or ordinal != expected_ordinal
                or not isinstance(name, str) or not name.strip() or len(name.strip()) > 200):
            raise ValueError("Receipt basket item identity is invalid")
        normalized.append({"ordinal": ordinal, "name": name.strip()})
    return {"items": normalized}


def build_prompt(context: dict[str, Any]) -> str:
    checked = validate_context(context)
    return (
        "Оцени каждую позицию корзины отдельно. Верни строго JSON без текста вне JSON. "
        "Сохрани каждый ordinal ровно один раз и не добавляй строки. "
        "Допустимый verdict: useful, neutral, harmful, unnecessary. Reason и action должны быть короткими, "
        "конкретными и относиться к названию позиции. Не выдумывай суммы, цены, количество или товары. "
        "Если данных недостаточно, ставь neutral и пустые reason/action. "
        "Формат: {\"items\":[{\"ordinal\":1,\"verdict\":\"neutral\",\"reason\":\"\",\"action\":\"\"}]}. "
        f"Позиции: {json.dumps(checked['items'], ensure_ascii=False, sort_keys=True)}"
    )


def validate_result(result: Any, context: dict[str, Any]) -> dict[str, Any]:
    checked_context = validate_context(context)
    if not isinstance(result, dict) or set(result) != {"items"}:
        raise ValueError("Receipt basket model output schema is invalid")
    items = result["items"]
    if not isinstance(items, list) or len(items) != len(checked_context["items"]):
        raise ValueError("Receipt basket model output is incomplete")
    normalized = []
    seen = set()
    for item in items:
        if not isinstance(item, dict) or set(item) != {"ordinal", "verdict", "reason", "action"}:
            raise ValueError("Receipt basket model item schema is invalid")
        ordinal, verdict, reason, action = item["ordinal"], item["verdict"], item["reason"], item["action"]
        if (not isinstance(ordinal, int) or isinstance(ordinal, bool) or not 1 <= ordinal <= len(items)
                or ordinal in seen or verdict not in VERDICTS
                or not isinstance(reason, str) or len(reason.strip()) > 500
                or not isinstance(action, str) or len(action.strip()) > 500):
            raise ValueError("Receipt basket model item is invalid")
        seen.add(ordinal)
        normalized.append({"ordinal": ordinal, "verdict": verdict,
                           "reason": reason.strip(), "action": action.strip()})
    if seen != set(range(1, len(items) + 1)):
        raise ValueError("Receipt basket model output ordinals do not match context")
    return {"items": sorted(normalized, key=lambda item: item["ordinal"])}


async def request_basket_review(
    context: dict[str, Any],
    *,
    http_client: Any,
    ollama_url: str,
    model: str,
    api_key: str = "",
) -> dict[str, Any]:
    checked = validate_context(context)
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    response = await http_client.post(
        f"{ollama_url.rstrip('/')}/api/generate",
        json={"model": model, "prompt": build_prompt(checked), "format": "json", "stream": False},
        headers=headers,
    )
    response.raise_for_status()
    try:
        envelope = response.json()
        parsed = json.loads(envelope["response"])
    except (KeyError, TypeError, json.JSONDecodeError, ValueError) as error:
        raise ValueError("Ollama receipt basket output is invalid JSON") from error
    return {**validate_result(parsed, checked),
            "modelVersion": str(envelope.get("model") or model)[:128], "promptVersion": PROMPT_VERSION}
