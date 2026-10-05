"""Batched, schema-checked merchant category hypotheses."""

from __future__ import annotations

import json
import unicodedata
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any

PROMPT_VERSION = "merchant-category.v1"
MAX_BATCH_SIZE = 50
CATEGORIES = frozenset({"еда", "транспорт", "жилье", "досуг", "одежда", "здоровье", "работа", "техника", "долги", "прочее"})


def normalize_merchant(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("Merchant name must be a string")
    normalized = " ".join(unicodedata.normalize("NFKC", value).split()).casefold()
    if not normalized or len(normalized) > 200 or any(unicodedata.category(char) == "Cc" for char in normalized):
        raise ValueError("Merchant name is invalid")
    return normalized


def validate_context(value: Any) -> dict[str, list[str]]:
    if not isinstance(value, dict) or set(value) != {"merchants"}:
        raise ValueError("Merchant classification context is invalid")
    merchants = value["merchants"]
    if not isinstance(merchants, list) or not 1 <= len(merchants) <= MAX_BATCH_SIZE:
        raise ValueError("Merchant classification batch size is invalid")
    normalized = [normalize_merchant(merchant) for merchant in merchants]
    if len(set(normalized)) != len(normalized):
        raise ValueError("Merchant classification batch must be unique")
    return {"merchants": normalized}


def normalize_classifications(value: Any, merchants: list[str]) -> list[dict[str, str]]:
    if not isinstance(value, dict) or set(value) != {"classifications"}:
        raise ValueError("AI classifications have an invalid shape")
    rows = value["classifications"]
    if not isinstance(rows, list) or len(rows) != len(merchants):
        raise ValueError("AI classifications do not cover the requested merchants")
    requested = set(merchants)
    normalized: dict[str, dict[str, str]] = {}
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"merchant", "categoryCode", "confidence"}:
            raise ValueError("AI classification row has an invalid shape")
        merchant = normalize_merchant(row["merchant"])
        category = row["categoryCode"]
        if (merchant not in requested or merchant in normalized or not isinstance(category, str)
                or category not in CATEGORIES):
            raise ValueError("AI classification contains an unknown merchant or category")
        try:
            confidence = Decimal(str(row["confidence"]))
        except (InvalidOperation, ValueError, TypeError) as exc:
            raise ValueError("AI classification confidence is invalid") from exc
        if not confidence.is_finite() or confidence < 0 or confidence > 1:
            raise ValueError("AI classification confidence is outside the allowed range")
        normalized[merchant] = {
            "merchant": merchant,
            "categoryCode": category,
            "confidence": format(confidence.quantize(Decimal("0.001"), rounding=ROUND_HALF_UP), ".3f"),
        }
    if set(normalized) != requested:
        raise ValueError("AI classifications omit a requested merchant")
    return [normalized[merchant] for merchant in merchants]


def build_prompt(context: dict[str, list[str]]) -> str:
    context = validate_context(context)
    return (
        "Для каждого магазина предположи одну категорию личных расходов. "
        "Если по названию нельзя уверенно определить категорию, выбери прочее и низкую уверенность. "
        "Не добавляй магазины и не придумывай сведения о покупках. "
        f"Разрешённые категории: {json.dumps(sorted(CATEGORIES), ensure_ascii=False)}. "
        'Верни только JSON вида {"classifications":[{"merchant":"...","categoryCode":"еда",'
        '"confidence":0.75}]}. Магазины: '
        f"{json.dumps(context['merchants'], ensure_ascii=False)}"
    )


async def request_classifications(context: dict[str, list[str]], *, http_client, ollama_url: str,
                                  model: str, api_key: str = "") -> dict[str, Any]:
    context = validate_context(context)
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    response = await http_client.post(
        f"{ollama_url.rstrip('/')}/api/generate",
        json={"model": model, "prompt": build_prompt(context), "format": "json", "stream": False},
        headers=headers,
    )
    response.raise_for_status()
    try:
        generated = json.loads(response.json().get("response"))
    except (TypeError, json.JSONDecodeError) as exc:
        raise ValueError("Ollama did not return classification JSON") from exc
    classifications = normalize_classifications(generated, context["merchants"])
    return {"classifications": classifications, "modelVersion": model, "promptVersion": PROMPT_VERSION}
