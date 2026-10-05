"""Constrained Ollama adapter for history-based budget proposals."""

from __future__ import annotations

import json
import re
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any

PROMPT_VERSION = "budget-proposal.v1"
_CONTEXT_FIELDS = {
    "monthlyIncome", "historyDays", "categoryCodes", "monthlyExpenseByMonth",
    "monthlyIncomeByMonth", "currentFamilyLimits",
}


def validate_context(context: Any) -> dict[str, Any]:
    """Reject transaction-level or unbounded inputs before constructing an AI prompt."""
    if not isinstance(context, dict) or set(context) != _CONTEXT_FIELDS:
        raise ValueError("Budget AI accepts only the approved aggregate fields")
    income = _decimal(context["monthlyIncome"])
    if not income.is_finite() or income <= 0 or income > Decimal("999999999999999999.99"):
        raise ValueError("Monthly income is outside the supported range")
    days = context["historyDays"]
    if not isinstance(days, int) or isinstance(days, bool) or not 30 <= days <= 3660:
        raise ValueError("At least 30 days of bounded history are required")
    categories = context["categoryCodes"]
    if (not isinstance(categories, list) or not 1 <= len(categories) <= 32
            or any(not isinstance(code, str) or not code.strip() or len(code) > 64 or code == "долги"
                   for code in categories)
            or len(categories) != len(set(categories))):
        raise ValueError("Invalid allowlisted budget categories")
    if not _valid_monthly_totals(context["monthlyExpenseByMonth"], categories):
        raise ValueError("Invalid monthly expense aggregates")
    if not _valid_monthly_income(context["monthlyIncomeByMonth"]):
        raise ValueError("Invalid monthly income aggregates")
    if not isinstance(context["currentFamilyLimits"], dict) or set(context["currentFamilyLimits"]) != set(categories) | {"долги"}:
        raise ValueError("Current limits do not match allowed categories")
    for amount in context["currentFamilyLimits"].values():
        _validate_amount(amount)
    return context


def _valid_monthly_totals(value: Any, categories: list[str]) -> bool:
    if not isinstance(value, dict) or len(value) > 4:
        return False
    try:
        for month, by_category in value.items():
            if not isinstance(month, str) or not re.fullmatch(r"\d{4}-\d{2}", month):
                return False
            if not isinstance(by_category, dict) or not set(by_category).issubset(categories):
                return False
            for amount in by_category.values():
                _validate_amount(amount)
    except (InvalidOperation, ValueError):
        return False
    return True


def _valid_monthly_income(value: Any) -> bool:
    if not isinstance(value, dict) or len(value) > 4:
        return False
    try:
        for month, amount in value.items():
            if not isinstance(month, str) or not re.fullmatch(r"\d{4}-\d{2}", month):
                return False
            _validate_amount(amount)
    except (InvalidOperation, ValueError):
        return False
    return True


def _validate_amount(value: Any) -> None:
    amount = _decimal(value)
    if not amount.is_finite() or amount < 0 or amount > Decimal("999999999999999999.99"):
        raise ValueError("Aggregate amount is outside the supported range")


def _decimal(value: Any) -> Decimal:
    try:
        amount = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise ValueError("Amount must be a decimal string") from exc
    if not amount.is_finite():
        raise ValueError("Amount must be finite")
    return amount


def build_prompt(context: dict[str, Any]) -> str:
    """Build a prompt from approved aggregates only; no transaction-level facts are accepted."""
    allowed = sorted(context["categoryCodes"])
    payload = {
        "monthlyIncome": context["monthlyIncome"],
        "historyDays": context["historyDays"],
        "monthlyExpenseByMonth": context["monthlyExpenseByMonth"],
        "monthlyIncomeByMonth": context["monthlyIncomeByMonth"],
        "currentFamilyLimits": context["currentFamilyLimits"],
    }
    return (
        "Разложи семейный месячный лимит по разрешённым категориям на основе агрегатов за историю. "
        "Общий лимит уже ограничен 70% дохода; верни только относительные доли категорий в процентах. "
        "Не включай долги: они считаются отдельно. Не добавляй категории, не выдумывай суммы. "
        "Доли должны быть неотрицательными и в сумме равняться 100.00. "
        f"Разрешённые категории: {json.dumps(allowed, ensure_ascii=False)}. "
        "Ответ строго JSON: {\"shares\": {\"категория\": 25.00}}. "
        f"Агрегированные данные: {json.dumps(payload, ensure_ascii=False, sort_keys=True)}"
    )


def normalize_shares(raw: Any, category_codes: list[str]) -> dict[str, str]:
    """Validate the model's exact category set and normalize small rounding drift to 100%."""
    if not isinstance(raw, dict) or set(raw) != set(category_codes) or not category_codes:
        raise ValueError("AI shares must contain exactly the allowed categories")
    shares: dict[str, Decimal] = {}
    try:
        for category in category_codes:
            value = Decimal(str(raw[category]))
            if not value.is_finite() or value < 0 or value > 100:
                raise ValueError("AI share is outside the allowed range")
            shares[category] = value
    except (InvalidOperation, TypeError) as exc:
        raise ValueError("AI share must be a finite decimal") from exc

    total = sum(shares.values(), Decimal("0"))
    if total <= 0 or abs(total - Decimal("100")) > Decimal("1"):
        raise ValueError("AI shares must sum to 100 percent")

    normalized = {
        key: (value * Decimal("100") / total).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        for key, value in shares.items()
    }
    drift = Decimal("100.00") - sum(normalized.values(), Decimal("0"))
    largest = max(category_codes, key=lambda key: (shares[key], key))
    normalized[largest] += drift
    if normalized[largest] < 0 or sum(normalized.values(), Decimal("0")) != Decimal("100.00"):
        raise ValueError("AI shares could not be normalized")
    return {key: format(value, ".2f") for key, value in normalized.items()}


async def request_shares(
    context: dict[str, Any],
    *,
    http_client: Any,
    ollama_url: str,
    model: str,
    api_key: str = "",
) -> dict[str, Any]:
    """Request a constrained share distribution from Ollama and validate it before returning."""
    context = validate_context(context)
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    response = await http_client.post(
        f"{ollama_url.rstrip('/')}/api/generate",
        json={"model": model, "prompt": build_prompt(context), "format": "json", "stream": False},
        headers=headers,
    )
    response.raise_for_status()
    content = response.json().get("response")
    try:
        generated = json.loads(content)
    except (TypeError, json.JSONDecodeError) as exc:
        raise ValueError("Ollama did not return JSON") from exc
    shares = normalize_shares(generated.get("shares") if isinstance(generated, dict) else None,
                              context["categoryCodes"])
    return {"shares": shares, "modelVersion": model, "promptVersion": PROMPT_VERSION}
