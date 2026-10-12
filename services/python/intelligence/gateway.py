"""Capability checks and fail-closed execution policy for intelligence providers."""

from __future__ import annotations

import asyncio
import inspect
import json
import re
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
                   and required.issubset(provider.capabilities)]
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
