"""Capability checks and fail-closed execution policy for intelligence providers."""

from __future__ import annotations

import asyncio
import base64
import inspect
import io
import json
import math
import re
import threading
import time
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Callable, Protocol
from urllib.parse import urlsplit

import httpx


class GatewayError(ValueError):
    pass


class PolicyDenied(GatewayError):
    pass


class UnsupportedCapability(GatewayError):
    pass


class ProviderUnavailable(GatewayError):
    """A requested AI task has no usable provider in the approved execution policy."""


class RequestCancelled(Exception):
    pass


class RequestDeadlineExceeded(TimeoutError):
    pass


TASK_SCHEMAS = {
    "budget-proposal": (
        "budget-proposal-context.v1",
        "budget-proposal-advice.v1",
        frozenset({"text", "structured_output"}),
    ),
    "transaction-draft": (
        "transaction-draft-context.v1",
        "transaction-draft-advice.v1",
        frozenset({"text", "structured_output"}),
    ),
    "receipt-ocr": (
        "receipt-ocr-context.v1",
        "receipt-ocr-result.v1",
        frozenset({"text"}),
    ),
    "receipt-vision": (
        "receipt-vision-context.v1",
        "receipt-vision-result.v1",
        frozenset({"vision", "structured_output"}),
    ),
    "receipt-basket-review": (
        "receipt-basket-context.v1",
        "receipt-basket-review.v1",
        frozenset({"text", "structured_output"}),
    ),
    "merchant-classification": (
        "merchant-classification-context.v1",
        "merchant-classification-result.v1",
        frozenset({"text", "structured_output"}),
    ),
}
KNOWN_CAPABILITIES = frozenset({"text", "structured_output", "vision", "embeddings", "tool_calling", "streaming"})
MAX_VERTEX_RESPONSE_BYTES = 16 * 1024
MAX_VERTEX_OUTPUT_TOKENS = 512
MAX_GEMINI_RESPONSE_BYTES = 64 * 1024
MAX_GEMINI_OUTPUT_TOKENS = 2048
MAX_GEMINI_IMAGE_BYTES = 4 * 1024 * 1024
MAX_GEMINI_IMAGE_SIDE = 2200
MAX_GEMINI_MODELS = 12
GEMINI_MODEL_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
GEMINI_COMPLEXITIES = frozenset({"fast", "balanced", "reasoning", "vision"})
GEMINI_TASKS = frozenset({"budget-proposal", "transaction-draft", "receipt-vision",
                          "receipt-basket-review", "merchant-classification"})
TASK_COMPLEXITY = {
    "budget-proposal": "reasoning",
    "transaction-draft": "balanced",
    "receipt-vision": "vision",
    "receipt-basket-review": "fast",
    "merchant-classification": "fast",
}


@dataclass(frozen=True)
class GeminiModelRoute:
    """One operator-selected Gemini model and its server-owned task/quota policy."""

    model_id: str
    tasks: frozenset[str]
    capabilities: frozenset[str]
    complexity: str
    rpm: int
    tpm: int
    rpd: int


def parse_gemini_model_routes(value: str | None) -> tuple[GeminiModelRoute, ...]:
    """Parse bounded, explicit model routes; model IDs and limits are deployment settings."""
    if not isinstance(value, str) or not value.strip() or len(value) > 16_384:
        return ()
    try:
        raw_routes = json.loads(value)
    except json.JSONDecodeError as error:
        raise ValueError("Gemini model route configuration is invalid") from error
    if not isinstance(raw_routes, list) or not 1 <= len(raw_routes) <= MAX_GEMINI_MODELS:
        raise ValueError("Gemini model route configuration is invalid")
    routes: list[GeminiModelRoute] = []
    seen: set[str] = set()
    expected_fields = {"modelId", "tasks", "capabilities", "complexity", "rpm", "tpm", "rpd"}
    for item in raw_routes:
        if not isinstance(item, dict) or set(item) != expected_fields:
            raise ValueError("Gemini model route fields are invalid")
        model_id, complexity = item["modelId"], item["complexity"]
        if (not isinstance(model_id, str) or not GEMINI_MODEL_ID.fullmatch(model_id)
                or model_id in seen or not isinstance(complexity, str)
                or complexity not in GEMINI_COMPLEXITIES):
            raise ValueError("Gemini model route identity is invalid")
        tasks = item["tasks"]
        capabilities = item["capabilities"]
        if (not isinstance(tasks, list) or not tasks
                or any(not isinstance(task, str) or task not in GEMINI_TASKS for task in tasks)
                or len(tasks) != len(set(tasks))):
            raise ValueError("Gemini model route tasks are invalid")
        if (not isinstance(capabilities, list) or not capabilities
                or any(not isinstance(capability, str) or capability not in KNOWN_CAPABILITIES
                       for capability in capabilities)
                or len(capabilities) != len(set(capabilities))):
            raise ValueError("Gemini model route capabilities are invalid")
        task_set, capability_set = frozenset(tasks), frozenset(capabilities)
        if any(not TASK_SCHEMAS[task][2].issubset(capability_set) for task in task_set):
            raise ValueError("Gemini model route omits a required task capability")
        limits = (item["rpm"], item["tpm"], item["rpd"])
        if any(not isinstance(limit, int) or isinstance(limit, bool) or limit <= 0
               for limit in limits):
            raise ValueError("Gemini model route quota limits are invalid")
        if item["rpm"] > 600 or item["tpm"] > 5_000_000 or item["rpd"] > 500_000:
            raise ValueError("Gemini model route quota limits exceed the safety bound")
        routes.append(GeminiModelRoute(model_id, task_set, capability_set, complexity, *limits))
        seen.add(model_id)
    return tuple(routes)


class GeminiQuotaExceeded(Exception):
    """The configured per-model quota or upstream project quota is exhausted."""

    reason = "quota"


class GeminiTemporaryFailure(Exception):
    """A retryable provider outage, timeout, or malformed model response."""

    def __init__(self, reason: str = "provider_unavailable"):
        super().__init__(reason)
        self.reason = reason


class GeminiRateLimiter:
    """Bound per-model requests and estimated input tokens in fixed UTC windows."""

    def __init__(self, clock: Callable[[], float] = time.time):
        self._clock = clock
        self._lock = threading.Lock()
        self._states: dict[str, tuple[int, int, int, int, int]] = {}

    def reserve(self, route: GeminiModelRoute, estimated_tokens: int) -> None:
        now = self._clock()
        minute = int(now // 60)
        day = int(now // 86_400)
        with self._lock:
            old = self._states.get(route.model_id)
            if old is None or old[0] != minute or old[3] != day:
                requests_minute, tokens_minute = (old[1], old[2]) if old and old[0] == minute else (0, 0)
                requests_day = old[4] if old and old[3] == day else 0
            else:
                requests_minute, tokens_minute, requests_day = old[1], old[2], old[4]
            if (requests_minute + 1 > route.rpm or tokens_minute + estimated_tokens > route.tpm
                    or requests_day + 1 > route.rpd):
                raise GeminiQuotaExceeded("configured model rate limit reached")
            self._states[route.model_id] = (minute, requests_minute + 1,
                                            tokens_minute + estimated_tokens, day, requests_day + 1)


_SHARED_GEMINI_RATE_LIMITER = GeminiRateLimiter()


class GeminiProvider:
    """Bounded Gemini Developer API adapter with task-specific prompts and validation."""

    name = "gemini"
    requires_cloud_opt_in = True

    def __init__(self, route: GeminiModelRoute, api_key: str,
                 transport: httpx.AsyncBaseTransport | None = None,
                 rate_limiter: GeminiRateLimiter | None = None,
                 receipt_images_approved: bool = False):
        self.route = route
        self.api_key = api_key
        self.transport = transport
        self.rate_limiter = rate_limiter or _SHARED_GEMINI_RATE_LIMITER
        self.receipt_images_approved = receipt_images_approved

    @property
    def enabled(self) -> bool:
        return True

    @property
    def capabilities(self) -> frozenset[str]:
        return self.route.capabilities

    @property
    def supported_tasks(self) -> frozenset[str]:
        if self.receipt_images_approved:
            return self.route.tasks
        return self.route.tasks - {"receipt-vision"}

    async def execute(self, task_kind: str, context: dict, timeout: float, api_key: str = "") -> dict:
        if task_kind not in self.supported_tasks:
            raise UnsupportedCapability(f"Gemini route does not implement task: {task_kind}")
        if not isinstance(timeout, (int, float)) or timeout <= 0:
            raise TimeoutError("Gemini request deadline has expired")
        key = self.api_key or api_key
        if not isinstance(key, str) or not key.strip() or "\r" in key or "\n" in key:
            raise PolicyDenied("Gemini server API key is not configured")
        if task_kind == "receipt-vision" and not self.receipt_images_approved:
            raise PolicyDenied("Gemini receipt image processing is not approved")
        prompt, parts, normalized_context = self._request_parts(task_kind, context)
        estimated_tokens = self._estimate_tokens(prompt, normalized_context)
        self.rate_limiter.reserve(self.route, estimated_tokens)
        payload = {
            "contents": [{"role": "user", "parts": parts}],
            "generationConfig": {
                "temperature": 0,
                "maxOutputTokens": MAX_GEMINI_OUTPUT_TOKENS,
                "responseMimeType": "application/json",
                "responseSchema": self._response_schema(task_kind, normalized_context),
            },
        }
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.route.model_id}:generateContent"
        response_bytes = bytearray()
        async with httpx.AsyncClient(timeout=float(timeout), transport=self.transport, trust_env=False) as client:
            async with client.stream("POST", url, json=payload,
                                     headers={"x-goog-api-key": key}) as response:
                limit = 4096 if response.status_code != 200 else MAX_GEMINI_RESPONSE_BYTES
                async for chunk in response.aiter_bytes():
                    if len(response_bytes) + len(chunk) > limit:
                        raise GeminiTemporaryFailure("invalid_output" if response.status_code == 200
                                                      else "provider_unavailable")
                    response_bytes.extend(chunk)
                if response.status_code != 200:
                    self._raise_http_failure(response.status_code, bytes(response_bytes))
        try:
            envelope = json.loads(response_bytes)
        except (ValueError, json.JSONDecodeError) as error:
            raise GeminiTemporaryFailure("invalid_output") from error
        if not isinstance(envelope, Mapping):
            raise GeminiTemporaryFailure("invalid_output")
        candidates = envelope.get("candidates")
        if not isinstance(candidates, list) or len(candidates) != 1 or not isinstance(candidates[0], Mapping):
            raise GeminiTemporaryFailure("invalid_output")
        candidate = candidates[0]
        content = candidate.get("content")
        response_parts = content.get("parts") if isinstance(content, Mapping) else None
        if (candidate.get("finishReason") != "STOP" or not isinstance(response_parts, list)
                or len(response_parts) != 1 or not isinstance(response_parts[0], Mapping)
                or not isinstance(response_parts[0].get("text"), str)):
            raise GeminiTemporaryFailure("invalid_output")
        try:
            raw_result = json.loads(response_parts[0]["text"])
            result, prompt_version = self._validate_output(task_kind, raw_result, normalized_context)
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
            raise GeminiTemporaryFailure("invalid_output") from error
        version = envelope.get("modelVersion")
        if not isinstance(version, str) or not version.strip() or len(version) > 128:
            version = self.route.model_id
        usage = envelope.get("usageMetadata")
        return {
            **result,
            "provider": "gemini",
            "modelVersion": version,
            "promptVersion": prompt_version,
            "capabilities": sorted(self.capabilities),
            "usage": self._usage(usage),
        }

    @staticmethod
    def _raise_http_failure(status_code: int, body: bytes) -> None:
        try:
            error = json.loads(body).get("error", {})
        except (ValueError, json.JSONDecodeError, AttributeError):
            error = {}
        status = error.get("status") if isinstance(error, Mapping) else None
        message = error.get("message", "") if isinstance(error, Mapping) else ""
        lowered_message = str(message).casefold()
        quota = status_code == 429 or (status_code == 403 and (
            status == "RESOURCE_EXHAUSTED" or any(term in lowered_message for term in
                                                   ("quota", "billing", "exhaust", "deplet", "insufficient credit"))))
        if quota:
            raise GeminiQuotaExceeded("Gemini quota unavailable")
        if status_code in {408, 425} or status_code >= 500:
            raise GeminiTemporaryFailure("provider_unavailable")
        raise ProviderUnavailable("Gemini is temporarily unavailable")

    @staticmethod
    def _usage(value: Any) -> dict[str, int | float | None]:
        if not isinstance(value, Mapping):
            return {"inputTokens": None, "outputTokens": None, "cost": None}
        prompt = value.get("promptTokenCount")
        output = value.get("candidatesTokenCount")
        return {
            "inputTokens": prompt if isinstance(prompt, int) and not isinstance(prompt, bool) and prompt >= 0 else None,
            "outputTokens": output if isinstance(output, int) and not isinstance(output, bool) and output >= 0 else None,
            "cost": None,
        }

    def _request_parts(self, task_kind: str, context: dict) -> tuple[str, list[dict], dict]:
        if task_kind == "budget-proposal":
            from services.python.intelligence.budget_proposals import build_prompt, validate_context
            safe = validate_context(context)
            prompt = build_prompt(safe)
            return prompt, [{"text": prompt}], safe
        if task_kind == "transaction-draft":
            from services.python.intelligence.transaction_drafts import build_draft_prompt, validate_draft_context
            safe = validate_draft_context(context)
            prompt = build_draft_prompt(safe)
            return prompt, [{"text": prompt}], safe
        if task_kind == "receipt-vision":
            from services.python.intelligence.vision import VISION_PROMPT
            from services.python.intelligence.tesseract import TesseractProvider
            image = TesseractProvider._decode_image(context)
            if max(image.size) > MAX_GEMINI_IMAGE_SIDE:
                image.thumbnail((MAX_GEMINI_IMAGE_SIDE, MAX_GEMINI_IMAGE_SIDE))
            output = io.BytesIO()
            image.save(output, format="JPEG", quality=82, optimize=True)
            image_bytes = output.getvalue()
            if not image_bytes or len(image_bytes) > MAX_GEMINI_IMAGE_BYTES:
                raise ValueError("Receipt image exceeds Gemini's minimized image bound")
            encoded = base64.b64encode(image_bytes).decode("ascii")
            return VISION_PROMPT, [{"text": VISION_PROMPT},
                                   {"inlineData": {"mimeType": "image/jpeg", "data": encoded}}], {
                                       "imageBase64": encoded, "imageSize": image.size}
        if task_kind == "receipt-basket-review":
            from services.python.intelligence.receipt_basket_review import build_prompt, validate_context
            safe = validate_context(context)
            prompt = build_prompt(safe)
            return prompt, [{"text": prompt}], safe
        if task_kind == "merchant-classification":
            from services.python.intelligence.merchant_classification import build_prompt, validate_context
            safe = validate_context(context)
            prompt = build_prompt(safe)
            return prompt, [{"text": prompt}], safe
        raise UnsupportedCapability(f"Gemini adapter does not implement task: {task_kind}")

    @staticmethod
    def _estimate_tokens(prompt: str, context: dict) -> int:
        estimate = max(1, math.ceil(len(prompt.encode("utf-8")) / 4))
        dimensions = context.get("imageSize")
        if isinstance(dimensions, tuple) and len(dimensions) == 2:
            width, height = dimensions
            estimate += max(1, math.ceil(width / 768) * math.ceil(height / 768)) * 258
        return estimate

    @staticmethod
    def _response_schema(task_kind: str, context: dict) -> dict[str, Any]:
        string = {"type": "STRING"}
        if task_kind == "budget-proposal":
            categories = context["categoryCodes"]
            return {"type": "OBJECT", "properties": {"shares": {
                "type": "OBJECT", "properties": {category: {"type": "NUMBER"} for category in categories},
                "required": categories}}, "required": ["shares"]}
        if task_kind == "transaction-draft":
            properties = {
                "type": {"type": "STRING", "enum": ["expense", "income", "debt_payment"]},
                "amount": string,
                "categoryCode": string,
                "subcategoryCode": {"type": "STRING", "nullable": True},
                "description": string,
                "occurredAt": string,
            }
            return {"type": "OBJECT", "properties": properties, "required": list(properties)}
        if task_kind == "receipt-vision":
            item = {"type": "OBJECT", "properties": {
                "name": string,
                "quantity": {"type": "STRING", "nullable": True},
                "unitPrice": {"type": "STRING", "nullable": True},
                "lineSum": {"type": "STRING", "nullable": True},
            }, "required": ["name", "quantity", "unitPrice", "lineSum"]}
            properties = {
                "store": {"type": "STRING", "nullable": True},
                "date": {"type": "STRING", "nullable": True},
                "total": {"type": "STRING", "nullable": True},
                "items": {"type": "ARRAY", "items": item},
            }
            return {"type": "OBJECT", "properties": properties, "required": list(properties)}
        if task_kind == "receipt-basket-review":
            item = {"type": "OBJECT", "properties": {
                "ordinal": {"type": "INTEGER"},
                "verdict": {"type": "STRING", "enum": ["useful", "neutral", "harmful", "unnecessary"]},
                "reason": string,
                "action": string,
            }, "required": ["ordinal", "verdict", "reason", "action"]}
            return {"type": "OBJECT", "properties": {"items": {"type": "ARRAY", "items": item}},
                    "required": ["items"]}
        if task_kind == "merchant-classification":
            from services.python.intelligence.merchant_classification import CATEGORIES
            item = {"type": "OBJECT", "properties": {
                "merchant": string,
                "categoryCode": {"type": "STRING", "enum": sorted(CATEGORIES)},
                "confidence": {"type": "NUMBER"},
            }, "required": ["merchant", "categoryCode", "confidence"]}
            return {"type": "OBJECT", "properties": {"classifications": {"type": "ARRAY", "items": item}},
                    "required": ["classifications"]}
        raise UnsupportedCapability(f"Gemini adapter does not implement task: {task_kind}")

    @staticmethod
    def _validate_output(task_kind: str, raw: Any, context: dict) -> tuple[dict, str]:
        if task_kind == "budget-proposal":
            from services.python.intelligence.budget_proposals import PROMPT_VERSION, normalize_shares
            if not isinstance(raw, Mapping) or set(raw) != {"shares"}:
                raise ValueError("Gemini budget proposal schema is invalid")
            return {"shares": normalize_shares(raw["shares"], context["categoryCodes"])}, PROMPT_VERSION
        if task_kind == "transaction-draft":
            from services.python.intelligence.transaction_drafts import PROMPT_VERSION, validate_draft_output
            return validate_draft_output(raw, now=datetime.fromisoformat(context["now"])), PROMPT_VERSION
        if task_kind == "receipt-vision":
            from services.python.intelligence.vision import OllamaVisionProvider
            return OllamaVisionProvider._validate(raw), "receipt-vision.v1"
        if task_kind == "receipt-basket-review":
            from services.python.intelligence.receipt_basket_review import PROMPT_VERSION, validate_result
            return validate_result(raw, context), PROMPT_VERSION
        if task_kind == "merchant-classification":
            from services.python.intelligence.merchant_classification import (
                PROMPT_VERSION, normalize_classifications,
            )
            if not isinstance(raw, Mapping) or set(raw) != {"classifications"}:
                raise ValueError("Gemini merchant classification schema is invalid")
            return {"classifications": normalize_classifications(raw, context["merchants"])}, PROMPT_VERSION
        raise UnsupportedCapability(f"Gemini adapter does not implement task: {task_kind}")


class GeminiProviderPool:
    """Select by task complexity, then try at most one compatible model fallback."""

    name = "gemini"
    requires_cloud_opt_in = True

    def __init__(self, providers: list[GeminiProvider] | tuple[GeminiProvider, ...]):
        self.providers = tuple(providers)
        if not self.providers or len(self.providers) > MAX_GEMINI_MODELS:
            raise ValueError("Gemini model pool size is invalid")

    @property
    def enabled(self) -> bool:
        return bool(self.providers)

    @property
    def capabilities(self) -> frozenset[str]:
        return frozenset().union(*(provider.capabilities for provider in self.providers))

    @property
    def supported_tasks(self) -> frozenset[str]:
        return frozenset().union(*(provider.supported_tasks for provider in self.providers))

    def supports(self, task_kind: str, required: set[str] | frozenset[str]) -> bool:
        return any(task_kind in provider.supported_tasks and required.issubset(provider.capabilities)
                   for provider in self.providers)

    @staticmethod
    def _complexity_rank(target: str, configured: str) -> int:
        order = {
            "fast": ("fast", "balanced", "reasoning", "vision"),
            "balanced": ("balanced", "fast", "reasoning", "vision"),
            "reasoning": ("reasoning", "balanced", "fast", "vision"),
            "vision": ("vision", "reasoning", "balanced", "fast"),
        }
        return order[target].index(configured)

    async def execute(self, task_kind: str, context: dict, timeout: float, api_key: str = "",
                      required_capabilities: set[str] | frozenset[str] | None = None) -> dict:
        target_complexity = TASK_COMPLEXITY.get(task_kind)
        if target_complexity is None:
            raise UnsupportedCapability(f"Gemini adapter does not implement task: {task_kind}")
        required = frozenset(required_capabilities or TASK_SCHEMAS[task_kind][2])
        candidates = [provider for provider in self.providers
                     if task_kind in provider.supported_tasks
                     and required.issubset(provider.capabilities)]
        candidates.sort(key=lambda provider: self._complexity_rank(target_complexity, provider.route.complexity))
        if not candidates:
            raise UnsupportedCapability("No configured Gemini model supports this task and capability set")
        failures: list[Exception] = []
        deadline = asyncio.get_running_loop().time() + timeout
        for index, provider in enumerate(candidates[:2]):
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                failures.append(GeminiTemporaryFailure("timeout"))
                break
            try:
                result = await asyncio.wait_for(provider.execute(task_kind, context, remaining, api_key), timeout=remaining)
                return {**result, "fallbackReason": failures[0].reason if failures else None}
            except asyncio.CancelledError:
                raise
            except GeminiQuotaExceeded as error:
                failures.append(error)
            except GeminiTemporaryFailure as error:
                failures.append(error)
            except (httpx.TimeoutException, TimeoutError) as error:
                failures.append(GeminiTemporaryFailure("timeout"))
            except httpx.HTTPError as error:
                failures.append(GeminiTemporaryFailure("provider_unavailable"))
            if index + 1 < min(2, len(candidates)):
                continue
        raise ProviderUnavailable("Gemini is temporarily unavailable") from (failures[-1] if failures else None)


class ProviderAdapter(Protocol):
    @property
    def name(self) -> str: ...

    @property
    def requires_cloud_opt_in(self) -> bool: ...

    @property
    def capabilities(self) -> frozenset[str]: ...

    @property
    def supported_tasks(self) -> frozenset[str]: ...

    async def execute(self, task_kind: str, context: dict, timeout: float, api_key: str = "") -> dict: ...


@dataclass(frozen=True)
class ProviderEndpoint:
    endpoint: str
    execution_policy: str


@dataclass(frozen=True)
class AiRequestEnvelope:
    task_kind: str
    input_schema_version: str
    output_schema_version: str
    deadline_unix_ms: int
    execution_policy: str
    correlation_id: str
    required_capabilities: frozenset[str]

    @classmethod
    def from_headers(cls, headers: Mapping[str, str], expected_task: str, now_ms: int | None = None):
        values = {
            "task": headers.get("X-Finance-Task-Kind", "").strip(),
            "input": headers.get("X-Finance-Input-Schema-Version", "").strip(),
            "output": headers.get("X-Finance-Output-Schema-Version", "").strip(),
            "deadline": headers.get("X-Finance-Deadline-Unix-Ms", "").strip(),
            "policy": headers.get("X-Finance-AI-Policy", "").strip(),
            "correlation": headers.get("X-Finance-Correlation-ID", "").strip(),
            "capabilities": headers.get("X-Finance-Required-Capabilities", "").strip(),
        }
        if any(not value for value in values.values()):
            raise GatewayError("AI request envelope is incomplete")
        schemas = TASK_SCHEMAS.get(expected_task)
        if schemas is None or values["task"] != expected_task:
            raise GatewayError("AI task kind does not match endpoint")
        if values["input"] != schemas[0] or values["output"] != schemas[1]:
            raise GatewayError("AI request schema version is unsupported")
        try:
            deadline = int(values["deadline"])
            correlation_id = str(uuid.UUID(values["correlation"]))
        except (ValueError, AttributeError) as error:
            raise GatewayError("AI deadline or correlation ID is invalid") from error
        current_ms = int(time.time() * 1000) if now_ms is None else now_ms
        remaining_ms = deadline - current_ms
        if remaining_ms <= 0 or remaining_ms > 600_000:
            raise GatewayError("AI request deadline is expired or exceeds maximum")
        if values["policy"] not in {"local-only", "cloud-opt-in"}:
            raise GatewayError("AI execution policy is invalid")
        capabilities = frozenset(item.strip() for item in values["capabilities"].split(",") if item.strip())
        if not capabilities or len(capabilities) != len(values["capabilities"].split(",")):
            raise GatewayError("AI required capabilities are invalid")
        unknown = capabilities - KNOWN_CAPABILITIES
        if unknown:
            raise UnsupportedCapability(f"Unknown AI capability: {','.join(sorted(unknown))}")
        if not schemas[2].issubset(capabilities):
            raise UnsupportedCapability("AI request omits a capability required by this task")
        return cls(values["task"], values["input"], values["output"], deadline, values["policy"],
                   correlation_id, capabilities)

    @property
    def remaining_seconds(self) -> float:
        return max(0.001, (self.deadline_unix_ms - int(time.time() * 1000)) / 1000)


def restrictive_policy(service_policy: str, request_policy: str) -> str:
    if service_policy not in {"local-only", "cloud-opt-in"} or request_policy not in {"local-only", "cloud-opt-in"}:
        raise PolicyDenied("Unsupported AI execution policy")
    return "local-only" if "local-only" in {service_policy, request_policy} else "cloud-opt-in"


async def run_with_controls(operation, cancellation, deadline_unix_ms: int):
    task = asyncio.create_task(operation())
    try:
        while not task.done():
            if cancellation.is_set():
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
                raise RequestCancelled("AI request was cancelled")
            if int(time.time() * 1000) >= deadline_unix_ms:
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
                raise RequestDeadlineExceeded("AI request deadline exceeded")
            await asyncio.sleep(0.02)
        return await task
    finally:
        if not task.done():
            task.cancel()


@dataclass(frozen=True)
class OllamaProvider:
    endpoint: str
    model: str

    @property
    def name(self) -> str:
        return "ollama"

    @property
    def requires_cloud_opt_in(self) -> bool:
        return not is_local_endpoint(self.endpoint)

    @property
    def supported_tasks(self) -> frozenset[str]:
        return frozenset({"budget-proposal", "transaction-draft", "receipt-basket-review", "merchant-classification"})

    @property
    def capabilities(self) -> frozenset[str]:
        return frozenset({"text", "structured_output"})

    async def execute(self, task_kind: str, context: dict, timeout: float, api_key: str = "") -> dict:
        from services.python.intelligence.budget_proposals import request_shares
        from services.python.intelligence.transaction_drafts import request_transaction_draft

        if task_kind not in {"budget-proposal", "transaction-draft", "receipt-basket-review", "merchant-classification"}:
            raise UnsupportedCapability(f"Ollama adapter does not implement task: {task_kind}")
        async with httpx.AsyncClient(timeout=timeout, trust_env=not is_local_endpoint(self.endpoint)) as client:
            if task_kind == "budget-proposal":
                return await request_shares(context, http_client=client, ollama_url=self.endpoint,
                                            model=self.model, api_key=api_key)
            if task_kind == "transaction-draft":
                return await request_transaction_draft(context, http_client=client, ollama_url=self.endpoint,
                                                       model=self.model, api_key=api_key)
            if task_kind == "merchant-classification":
                from services.python.intelligence.merchant_classification import request_classifications
                return await request_classifications(context, http_client=client, ollama_url=self.endpoint,
                                                     model=self.model, api_key=api_key)
            from services.python.intelligence.receipt_basket_review import request_basket_review
            return await request_basket_review(context, http_client=client,
                                               ollama_url=self.endpoint, model=self.model, api_key=api_key)


@dataclass(frozen=True)
class VertexAIProvider:
    """Offline-testable Vertex draft adapter; never enabled in the production router."""

    project_id: str = ""
    location: str = ""
    model_id: str = ""
    access_token_supplier: Callable[[], Any] | None = None
    transport: httpx.AsyncBaseTransport | None = None
    declared_capabilities: frozenset[str] = frozenset()

    @property
    def name(self) -> str:
        return "vertex_ai"

    @property
    def requires_cloud_opt_in(self) -> bool:
        return True

    @property
    def enabled(self) -> bool:
        return False

    @property
    def capabilities(self) -> frozenset[str]:
        supported = frozenset({"text", "structured_output"})
        return frozenset(self.declared_capabilities) & supported

    @property
    def supported_tasks(self) -> frozenset[str]:
        required = TASK_SCHEMAS["transaction-draft"][2]
        return (frozenset({"transaction-draft"})
                if required.issubset(self.capabilities) else frozenset())

    @staticmethod
    def _path_component(value: str, name: str, *, location: bool = False) -> str:
        pattern = r"[a-z0-9]+(?:-[a-z0-9]+)*" if location else r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
        if not isinstance(value, str) or not re.fullmatch(pattern, value):
            raise ValueError(f"Vertex {name} is invalid")
        return value

    @classmethod
    def _response_schema(cls) -> dict[str, Any]:
        return {
            "type": "OBJECT",
            "properties": {
                "type": {"type": "STRING", "enum": ["expense", "income", "debt_payment"]},
                "amount": {"type": "STRING"},
                "categoryCode": {"type": "STRING"},
                "subcategoryCode": {"type": "STRING", "nullable": True},
                "description": {"type": "STRING"},
                "occurredAt": {"type": "STRING"},
            },
            "required": ["type", "amount", "categoryCode", "subcategoryCode", "description", "occurredAt"],
        }

    async def execute(self, task_kind: str, context: dict, timeout: float, api_key: str = "") -> dict:
        if task_kind not in self.supported_tasks:
            raise UnsupportedCapability(f"Vertex adapter does not implement task: {task_kind}")
        if not isinstance(timeout, (int, float)) or timeout <= 0:
            raise ValueError("Vertex request timeout must be positive")
        if self.access_token_supplier is None:
            raise PolicyDenied("Vertex access token supplier is not configured")

        project = self._path_component(self.project_id, "project ID")
        location = self._path_component(self.location, "location", location=True)
        model = self._path_component(self.model_id, "model ID")
        from services.python.intelligence.transaction_drafts import (
            PROMPT_VERSION,
            build_draft_prompt,
            validate_draft_context,
            validate_draft_output,
        )

        safe_context = validate_draft_context(context)
        supplied_token = self.access_token_supplier()
        token = await supplied_token if inspect.isawaitable(supplied_token) else supplied_token
        if not isinstance(token, str) or not token.strip() or "\r" in token or "\n" in token:
            raise PolicyDenied("Vertex access token is invalid")

        host = "aiplatform.googleapis.com" if location == "global" else f"{location}-aiplatform.googleapis.com"
        url = (f"https://{host}/v1/projects/{project}/locations/{location}/publishers/google/"
               f"models/{model}:generateContent")
        payload = {
            "contents": [{"role": "user", "parts": [{"text": build_draft_prompt(safe_context)}]}],
            "generationConfig": {
                "maxOutputTokens": MAX_VERTEX_OUTPUT_TOKENS,
                "responseMimeType": "application/json",
                "responseSchema": self._response_schema(),
            },
        }
        response_bytes = bytearray()
        async with httpx.AsyncClient(timeout=float(timeout), transport=self.transport, trust_env=False) as client:
            async with client.stream(
                "POST",
                url,
                json=payload,
                headers={"Authorization": f"Bearer {token}"},
            ) as response:
                response.raise_for_status()
                async for chunk in response.aiter_bytes():
                    if len(response_bytes) + len(chunk) > MAX_VERTEX_RESPONSE_BYTES:
                        raise ValueError("Vertex response exceeds the allowed size")
                    response_bytes.extend(chunk)
        try:
            body = json.loads(response_bytes)
        except (ValueError, json.JSONDecodeError) as exc:
            raise ValueError("Vertex returned an invalid JSON response") from exc
        if not isinstance(body, Mapping):
            raise ValueError("Vertex response must be an object")
        candidates = body.get("candidates")
        if not isinstance(candidates, list) or len(candidates) != 1 or not isinstance(candidates[0], Mapping):
            raise ValueError("Vertex response must contain exactly one candidate")
        candidate = candidates[0]
        if candidate.get("finishReason") != "STOP":
            raise ValueError("Vertex did not finish the transaction draft safely")
        content = candidate.get("content")
        parts = content.get("parts") if isinstance(content, Mapping) else None
        if not isinstance(parts, list) or len(parts) != 1 or not isinstance(parts[0], Mapping):
            raise ValueError("Vertex candidate must contain exactly one text part")
        text = parts[0].get("text")
        if not isinstance(text, str) or not text:
            raise ValueError("Vertex candidate text is missing")
        try:
            draft = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ValueError("Vertex candidate is not valid JSON") from exc
        validated = validate_draft_output(draft, now=datetime.fromisoformat(safe_context["now"]))
        model_version = body.get("modelVersion")
        return {
            **validated,
            "modelVersion": model_version if isinstance(model_version, str) and model_version.strip() else model,
            "promptVersion": PROMPT_VERSION,
        }

    def enable(self, gate_approved: bool, execution_policy: str):
        if not gate_approved:
            raise PolicyDenied("Vertex AI requires evaluation, canary, and rollback approval")
        if execution_policy != "cloud-opt-in":
            raise PolicyDenied("Vertex AI requires explicit cloud opt-in")
        raise PolicyDenied("Vertex AI adapter has no active implementation")


class ProviderRouter:
    def __init__(self, providers: list[ProviderAdapter] | tuple[ProviderAdapter, ...]):
        self.providers = tuple(providers)

    def select(self, required: set[str] | frozenset[str], execution_policy: str,
               task_kind: str | None = None) -> ProviderAdapter:
        if execution_policy not in {"local-only", "cloud-opt-in"}:
            raise PolicyDenied("Unsupported AI execution policy")
        capable = [provider for provider in self.providers
                   if getattr(provider, "enabled", True)
                   and (task_kind is None or task_kind in provider.supported_tasks)
                   and (provider.supports(task_kind, required) if task_kind is not None
                        and callable(getattr(provider, "supports", None))
                        else required.issubset(provider.capabilities))]
        if not capable:
            raise UnsupportedCapability("No enabled provider supports all required capabilities")
        allowed = [provider for provider in capable
                   if execution_policy == "cloud-opt-in"
                   or getattr(provider, "requires_cloud_opt_in", None) is False]
        if not allowed:
            raise PolicyDenied("Execution policy blocks external or unclassified providers")
        return allowed[0]


def authorize_provider(endpoint: str, execution_policy: str) -> ProviderEndpoint:
    """Validate the endpoint before any model request; remote Ollama is external processing."""
    if execution_policy not in {"local-only", "cloud-opt-in"}:
        raise PolicyDenied("Unsupported AI execution policy")
    if not isinstance(endpoint, str) or not endpoint or len(endpoint) > 512:
        raise PolicyDenied("Ollama endpoint is invalid")
    parsed = urlsplit(endpoint)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password \
            or parsed.query or parsed.fragment:
        raise PolicyDenied("Ollama endpoint is invalid")
    if execution_policy == "local-only" and not is_local_endpoint(endpoint):
        raise PolicyDenied("Local-only policy blocks remote inference")
    return ProviderEndpoint(endpoint.rstrip("/"), execution_policy)


def is_local_endpoint(endpoint: str) -> bool:
    parsed = urlsplit(endpoint)
    host = (parsed.hostname or "").casefold().strip("[]")
    return host in {"localhost", "127.0.0.1", "::1", "ollama", "host.docker.internal"}


def require_capabilities(provider: OllamaProvider, required: set[str] | frozenset[str]) -> None:
    if not required.issubset(provider.capabilities):
        missing = sorted(required - provider.capabilities)
        raise UnsupportedCapability(f"Provider does not support required capability: {','.join(missing)}")
