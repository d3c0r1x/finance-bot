"""Run common, synthetic golden cases against AI providers without echoing user data."""

from __future__ import annotations

import asyncio
import base64
import json
import re
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, InvalidOperation
from io import BytesIO
from pathlib import Path
from time import perf_counter
from typing import Any

import httpx
from PIL import Image, ImageDraw, ImageFont

from services.python.intelligence.gateway import (
    TASK_SCHEMAS,
    PolicyDenied,
    ProviderRouter,
    UnsupportedCapability,
)


DEFAULT_DATASET = Path(__file__).with_name("evaluation_data") / "golden-v1.json"


@dataclass(frozen=True)
class GoldenCase:
    case_id: str
    task_kind: str
    context: dict[str, Any]
    expected: dict[str, Any]
    tolerance_percentage_points: Decimal = Decimal("0")


@dataclass(frozen=True)
class GoldenDataset:
    version: str
    cases: tuple[GoldenCase, ...]


def load_golden_dataset(path: str | Path | None = None) -> GoldenDataset:
    """Load a versioned synthetic-only dataset and generate its receipt image in memory."""
    dataset_path = Path(path) if path is not None else DEFAULT_DATASET
    try:
        raw = json.loads(dataset_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("AI evaluation dataset is unavailable or invalid") from error
    if not isinstance(raw, dict) or not isinstance(raw.get("version"), str) or not raw["version"].strip():
        raise ValueError("AI evaluation dataset version is invalid")
    rows = raw.get("cases")
    if not isinstance(rows, list) or not rows:
        raise ValueError("AI evaluation dataset has no cases")

    cases = []
    seen_ids = set()
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("AI evaluation case is invalid")
        case_id, task_kind = row.get("id"), row.get("taskKind")
        if (not isinstance(case_id, str) or not case_id or case_id in seen_ids
                or task_kind not in TASK_SCHEMAS):
            raise ValueError("AI evaluation case identity or task is invalid")
        seen_ids.add(case_id)
        context = row.get("context")
        expected = row.get("expected")
        if not isinstance(context, dict) or not isinstance(expected, dict):
            raise ValueError("AI evaluation case context or expected output is invalid")
        context = dict(context)
        synthetic_receipt = row.get("syntheticReceipt")
        if synthetic_receipt is not None:
            if (task_kind not in {"receipt-ocr", "receipt-vision"}
                    or not isinstance(synthetic_receipt, dict)
                    or not isinstance(synthetic_receipt.get("lines"), list)
                    or not 1 <= len(synthetic_receipt["lines"]) <= 12
                    or any(not isinstance(line, str) or not line or len(line) > 100
                           for line in synthetic_receipt["lines"])):
                raise ValueError("AI evaluation synthetic receipt is invalid")
            context["imageBase64"] = _synthetic_receipt_image(synthetic_receipt["lines"])
            context["syntheticReceipt"] = True
        elif task_kind in {"receipt-ocr", "receipt-vision"}:
            raise ValueError("Receipt evaluation cases must use a synthetic receipt")
        tolerance = _decimal(row.get("tolerancePercentagePoints", "0"))
        if tolerance < 0 or tolerance > 100:
            raise ValueError("AI evaluation tolerance is outside limits")
        cases.append(GoldenCase(case_id, task_kind, context, expected, tolerance))
    return GoldenDataset(raw["version"], tuple(cases))


def evaluate_providers(
    providers: list[Any] | tuple[Any, ...],
    cases: list[GoldenCase] | tuple[GoldenCase, ...],
    *,
    execution_policy: str,
    minimum_pass_rate: float = 1.0,
    timeout_seconds: float = 30.0,
    provider_api_keys: dict[int, str] | None = None,
) -> dict[str, Any]:
    """Run one common dataset; reports contain only case IDs and failure classes."""
    if not 0 < minimum_pass_rate <= 1 or not 0 < timeout_seconds <= 600:
        raise ValueError("AI evaluation gate settings are invalid")
    results = []
    provider_api_keys = provider_api_keys or {}
    for provider in providers:
        provider_cases = []
        latency_samples = []
        model_versions = set()
        prompt_versions = set()
        for case in cases:
            if case.task_kind not in getattr(provider, "supported_tasks", frozenset()):
                continue
            required = TASK_SCHEMAS[case.task_kind][2]
            try:
                ProviderRouter([provider]).select(required, execution_policy, case.task_kind)
            except PolicyDenied:
                provider_cases.append(_case_result(case, "blocked", "policy_denied"))
                continue
            except UnsupportedCapability:
                provider_cases.append(_case_result(case, "failed", "unsupported_capability"))
                continue
            started = perf_counter()
            try:
                actual = asyncio.run(provider.execute(case.task_kind, case.context, timeout_seconds,
                                                      provider_api_keys.get(id(provider), "")))
            except (TimeoutError, httpx.TimeoutException):
                provider_cases.append(_case_result(case, "failed", "timeout"))
                continue
            except Exception:
                provider_cases.append(_case_result(case, "failed", "provider_error"))
                continue
            latency_ms = max(0, round((perf_counter() - started) * 1000))
            latency_samples.append(latency_ms)
            if isinstance(actual, dict):
                if isinstance(actual.get("modelVersion"), str):
                    model_versions.add(actual["modelVersion"][:128])
                if isinstance(actual.get("promptVersion"), str):
                    prompt_versions.add(actual["promptVersion"][:128])
            try:
                passed = _matches_golden(case, actual)
            except (InvalidOperation, TypeError, ValueError, KeyError):
                provider_cases.append(_case_result(case, "failed", "invalid_output", latency_ms))
                continue
            provider_cases.append(_case_result(case, "passed" if passed else "failed",
                                               None if passed else "golden_mismatch", latency_ms))

        supported = len(provider_cases)
        passed = sum(item["status"] == "passed" for item in provider_cases)
        pass_rate = passed / supported if supported else 0.0
        results.append({
            "provider": str(getattr(provider, "name", "unknown"))[:64],
            "supportedCases": supported,
            "passedCases": passed,
            "passRate": round(pass_rate, 4),
            "eligible": supported > 0 and pass_rate >= minimum_pass_rate,
            "meanLatencyMs": round(sum(latency_samples) / len(latency_samples)) if latency_samples else None,
            "modelVersions": sorted(model_versions),
            "promptVersions": sorted(prompt_versions),
            "usage": "unknown",
            "cost": "unknown",
            "cases": provider_cases,
        })
    return {
        "minimumPassRate": minimum_pass_rate,
        "providers": results,
    }


def _matches_golden(case: GoldenCase, actual: Any) -> bool:
    if not isinstance(actual, dict):
        raise ValueError("Provider output must be an object")
    if case.task_kind == "budget-proposal":
        from services.python.intelligence.budget_proposals import normalize_shares

        expected = case.expected["shares"]
        observed = normalize_shares(actual["shares"], list(expected))
        return all(abs(_decimal(observed[key]) - _decimal(value)) <= case.tolerance_percentage_points
                   for key, value in expected.items())
    if case.task_kind == "transaction-draft":
        from services.python.intelligence.transaction_drafts import validate_draft_output

        fields = {key: actual[key] for key in (
            "type", "amount", "categoryCode", "subcategoryCode", "description", "occurredAt")}
        normalized = validate_draft_output(fields, now=datetime.fromisoformat(case.context["now"]))
        return _deep_equal(case.expected, normalized)
    if case.task_kind == "receipt-vision":
        from services.python.intelligence.vision import OllamaVisionProvider

        fields = {key: actual[key] for key in ("store", "date", "total", "items")}
        normalized = OllamaVisionProvider._validate(fields)
        return _deep_equal(case.expected, normalized)
    if case.task_kind == "receipt-ocr":
        text = actual.get("text")
        words = actual.get("words")
        if not isinstance(text, str) or not isinstance(words, list):
            raise ValueError("OCR output schema is invalid")
        normalized_text = _receipt_tokens(text)
        return all(_receipt_tokens(token).issubset(normalized_text)
                   for token in case.expected["tokens"])
    if case.task_kind == "merchant-classification":
        from services.python.intelligence.merchant_classification import normalize_classifications, validate_context

        merchants = validate_context(case.context)["merchants"]
        normalized = normalize_classifications({"classifications": actual.get("classifications")}, merchants)
        expected = case.expected.get("classifications")
        return isinstance(expected, list) and [
            {"merchant": item["merchant"], "categoryCode": item["categoryCode"]} for item in normalized
        ] == expected
    raise ValueError("AI evaluation task is unsupported")


def _deep_equal(expected: Any, actual: Any) -> bool:
    if isinstance(expected, dict):
        return (isinstance(actual, dict) and set(expected) == set(actual)
                and all(_deep_equal(value, actual[key]) for key, value in expected.items()))
    if isinstance(expected, list):
        return (isinstance(actual, list) and len(expected) == len(actual)
                and all(_deep_equal(left, right) for left, right in zip(expected, actual)))
    if isinstance(expected, str) and isinstance(actual, str):
        return expected.casefold() == actual.casefold()
    return expected == actual


def _receipt_tokens(value: str) -> set[str]:
    return {token.casefold() for token in re.findall(r"[\w.]+", value, flags=re.UNICODE)}


def _decimal(value: Any) -> Decimal:
    if not isinstance(value, (str, int, Decimal)) or isinstance(value, bool):
        raise ValueError("Evaluation numbers must be decimal strings")
    result = Decimal(str(value))
    if not result.is_finite():
        raise ValueError("Evaluation numbers must be finite")
    return result


def _case_result(case: GoldenCase, status: str, failure_code: str | None = None,
                 latency_ms: int | None = None) -> dict[str, Any]:
    result = {"caseId": case.case_id, "status": status}
    if failure_code:
        result["failureCode"] = failure_code
    if latency_ms is not None:
        result["latencyMs"] = latency_ms
    return result


def _synthetic_receipt_image(lines: list[str]) -> str:
    image = Image.new("RGB", (1000, max(200, 72 * len(lines))), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=42)
    for index, line in enumerate(lines):
        draw.text((24, 18 + index * 68), line, fill="black", font=font)
    output = BytesIO()
    image.save(output, format="PNG")
    return base64.b64encode(output.getvalue()).decode("ascii")
