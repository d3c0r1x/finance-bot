import asyncio
import base64
from io import BytesIO
import json

import httpx
import pytest
from PIL import Image
import services.python.intelligence.gateway as intelligence_gateway

from services.python.intelligence.gateway import (
    OllamaProvider,
    PolicyDenied,
    UnsupportedCapability,
    ProviderRouter,
    VertexAIProvider,
    authorize_provider,
    require_capabilities,
)
from services.python.intelligence.vision import OllamaVisionPoolProvider, OllamaVisionProvider
from services.python.intelligence.tesseract import TesseractProvider
from services.python.intelligence.transaction_drafts import (
    PROMPT_VERSION as TRANSACTION_DRAFT_PROMPT_VERSION,
    build_draft_prompt,
    validate_draft_context,
)


@pytest.mark.parametrize("host", ["http://localhost:11434", "http://127.0.0.1:11434", "http://ollama:11434",
                                  "http://host.docker.internal:11434"])
def test_local_only_allows_only_local_ollama_endpoints(host):
    endpoint = authorize_provider(host, "local-only")
    assert endpoint.endpoint == host
    assert endpoint.execution_policy == "local-only"


@pytest.mark.parametrize("host", ["https://ollama.example", "http://10.0.0.8:11434"])
def test_local_only_rejects_remote_ollama_before_network_call(host):
    with pytest.raises(PolicyDenied):
        authorize_provider(host, "local-only")


def test_remote_ollama_requires_explicit_cloud_opt_in():
    endpoint = authorize_provider("https://ollama.example", "cloud-opt-in")
    assert endpoint.endpoint == "https://ollama.example"
    assert endpoint.execution_policy == "cloud-opt-in"


def test_provider_must_declare_each_requested_capability():
    provider = OllamaProvider("http://ollama:11434", "qwen-text")
    require_capabilities(provider, {"text", "structured_output"})
    with pytest.raises(UnsupportedCapability):
        require_capabilities(provider, {"vision"})
    with pytest.raises(UnsupportedCapability):
        require_capabilities(provider, {"embeddings"})


def test_capability_router_selects_only_provider_that_satisfies_task():
    text_provider = OllamaProvider("http://ollama:11434", "qwen-text")
    vision_provider = OllamaVisionProvider("http://ollama:11434", "qwen-vl")

    selected = ProviderRouter([text_provider, vision_provider]).select(
        {"vision", "structured_output"}, "local-only", "receipt-vision")

    assert selected is vision_provider
    with pytest.raises(UnsupportedCapability):
        ProviderRouter([text_provider]).select({"vision"}, "local-only", "receipt-vision")


def test_text_provider_supports_amount_free_basket_review_task():
    provider = OllamaProvider("http://ollama:11434", "qwen-text")

    selected = ProviderRouter([provider]).select(
        {"text", "structured_output"}, "local-only", "receipt-basket-review")

    assert selected is provider


def test_capability_router_rejects_remote_match_under_local_only():
    remote = OllamaVisionProvider("https://remote.example", "qwen-vl")

    with pytest.raises(PolicyDenied):
        ProviderRouter([remote]).select({"vision", "structured_output"}, "local-only", "receipt-vision")


def test_builtin_provider_adapters_declare_cloud_locality_explicitly():
    local_text = OllamaProvider("http://ollama:11434", "qwen-text")
    cloud_text = OllamaProvider("https://ollama.example", "qwen-text")
    local_ocr = TesseractProvider()
    vertex = VertexAIProvider()

    assert local_text.requires_cloud_opt_in is False
    assert cloud_text.requires_cloud_opt_in is True
    assert local_ocr.requires_cloud_opt_in is False
    assert vertex.requires_cloud_opt_in is True


def test_vision_pool_requires_cloud_opt_in_if_any_child_is_remote():
    local = OllamaVisionProvider("http://ollama:11434", "local-vlm")
    remote = OllamaVisionProvider("https://ollama.example", "remote-vlm")

    assert OllamaVisionPoolProvider([local]).requires_cloud_opt_in is False
    assert OllamaVisionPoolProvider([remote]).requires_cloud_opt_in is True
    assert OllamaVisionPoolProvider([local, remote]).requires_cloud_opt_in is True


def test_local_only_fails_closed_when_provider_has_no_locality_classification():
    class UnclassifiedEndpointlessProvider:
        name = "unclassified"
        enabled = True
        capabilities = frozenset({"text", "structured_output"})
        supported_tasks = frozenset({"transaction-draft"})

        async def execute(self, *_args, **_kwargs):
            pytest.fail("Unclassified provider must never execute")

    with pytest.raises(PolicyDenied, match="external or unclassified"):
        ProviderRouter([UnclassifiedEndpointlessProvider()]).select(
            {"text", "structured_output"}, "local-only", "transaction-draft",
        )


def test_local_only_rejects_endpointless_cloud_provider_before_execute():
    class EndpointlessCloudProvider:
        name = "cloud"
        enabled = True
        requires_cloud_opt_in = True
        capabilities = frozenset({"text", "structured_output"})
        supported_tasks = frozenset({"transaction-draft"})

        async def execute(self, *_args, **_kwargs):
            pytest.fail("Cloud provider must not execute under local-only")

    provider = EndpointlessCloudProvider()
    with pytest.raises(PolicyDenied, match="external or unclassified"):
        ProviderRouter([provider]).select(
            {"text", "structured_output"}, "local-only", "transaction-draft",
        )


def test_cloud_opt_in_allows_endpointless_cloud_provider_selection():
    class EndpointlessCloudProvider:
        name = "cloud"
        enabled = True
        requires_cloud_opt_in = True
        capabilities = frozenset({"text", "structured_output"})
        supported_tasks = frozenset({"transaction-draft"})

        async def execute(self, *_args, **_kwargs):
            return {"modelVersion": "cloud-test"}

    provider = EndpointlessCloudProvider()
    selected = ProviderRouter([provider]).select(
        {"text", "structured_output"}, "cloud-opt-in", "transaction-draft",
    )

    assert selected is provider


def test_local_provider_failure_does_not_trigger_cloud_fallback():
    calls = []

    class LocalProvider:
        name = "local"
        enabled = True
        requires_cloud_opt_in = False
        capabilities = frozenset({"text", "structured_output"})
        supported_tasks = frozenset({"transaction-draft"})

        async def execute(self, *_args, **_kwargs):
            calls.append("local")
            raise RuntimeError("local provider failed")

    class CloudProvider:
        name = "cloud"
        enabled = True
        requires_cloud_opt_in = True
        capabilities = frozenset({"text", "structured_output"})
        supported_tasks = frozenset({"transaction-draft"})

        async def execute(self, *_args, **_kwargs):
            calls.append("cloud")
            return {"modelVersion": "cloud-test"}

    selected = ProviderRouter([LocalProvider(), CloudProvider()]).select(
        {"text", "structured_output"}, "local-only", "transaction-draft",
    )
    assert selected.name == "local"

    with pytest.raises(RuntimeError, match="local provider failed"):
        asyncio.run(selected.execute("transaction-draft", {}, timeout=1))

    assert calls == ["local"]


@pytest.mark.parametrize("policy", ["local-only", "cloud-opt-in"])
def test_vertex_provider_is_disabled_by_default_and_router_fails_closed(policy):
    provider = VertexAIProvider()

    assert provider.name == "vertex_ai"
    assert provider.enabled is False
    assert provider.capabilities == frozenset()
    assert provider.supported_tasks == frozenset()
    with pytest.raises(UnsupportedCapability):
        ProviderRouter([provider]).select({"text", "structured_output"}, policy, "transaction-draft")
    assert provider.enabled is False


@pytest.mark.parametrize("policy", ["local-only", "cloud-opt-in"])
def test_configured_vertex_declares_only_transaction_draft_text_capabilities_and_stays_disabled(policy):
    provider = VertexAIProvider(
        project_id="test-project",
        location="us-central1",
        model_id="gemini-test",
        access_token_supplier=lambda: "injected-token",
        transport=httpx.MockTransport(lambda _request: httpx.Response(500)),
        declared_capabilities=frozenset({"text", "structured_output"}),
    )

    assert provider.capabilities == frozenset({"text", "structured_output"})
    assert provider.supported_tasks == frozenset({"transaction-draft"})
    assert provider.enabled is False
    with pytest.raises(UnsupportedCapability):
        ProviderRouter([provider]).select({"text", "structured_output"}, policy, "transaction-draft")


def test_vertex_provider_cannot_be_enabled_without_explicit_gate():
    provider = VertexAIProvider()

    with pytest.raises(PolicyDenied):
        provider.enable(gate_approved=False, execution_policy="cloud-opt-in")


def transaction_draft_context():
    return {
        "text": "Обед 250 рублей",
        "timezone": "Europe/Moscow",
        "now": "2026-10-12T12:30:00+03:00",
    }


def valid_transaction_draft():
    return {
        "type": "expense",
        "amount": "250",
        "categoryCode": "еда",
        "subcategoryCode": None,
        "description": "Обед",
        "occurredAt": "2026-10-12T12:00:00+03:00",
    }


def vertex_success_response(draft):
    return {
        "candidates": [{
            "content": {"role": "model", "parts": [{"text": json.dumps(draft, ensure_ascii=False)}]},
            "finishReason": "STOP",
        }],
        "modelVersion": "gemini-test-version",
    }


def test_vertex_transaction_draft_uses_shared_prompt_and_validated_output_offline():
    requests = []
    access_tokens = []

    def respond(request):
        requests.append(request)
        return httpx.Response(200, json=vertex_success_response(valid_transaction_draft()))

    def access_token_supplier():
        access_tokens.append("requested")
        return "injected-vertex-access-token"

    provider = VertexAIProvider(
        project_id="finance-test-project",
        location="europe-west4",
        model_id="gemini-test-model",
        access_token_supplier=access_token_supplier,
        transport=httpx.MockTransport(respond),
        declared_capabilities=frozenset({"text", "structured_output"}),
    )

    result = asyncio.run(provider.execute(
        "transaction-draft", transaction_draft_context(), timeout=3.5, api_key="generic-ollama-secret",
    ))

    assert len(requests) == 1
    request = requests[0]
    assert request.method == "POST"
    assert request.url == (
        "https://europe-west4-aiplatform.googleapis.com/v1/projects/finance-test-project/locations/"
        "europe-west4/publishers/google/models/gemini-test-model:generateContent"
    )
    assert request.headers["Authorization"] == "Bearer injected-vertex-access-token"
    assert b"generic-ollama-secret" not in request.content
    assert request.extensions["timeout"] == {
        "connect": 3.5, "read": 3.5, "write": 3.5, "pool": 3.5,
    }
    payload = json.loads(request.content)
    assert set(payload) == {"contents", "generationConfig"}
    assert payload["contents"][0]["parts"][0]["text"] == build_draft_prompt(
        validate_draft_context(transaction_draft_context())
    )
    assert payload["generationConfig"]["responseMimeType"] == "application/json"
    assert payload["generationConfig"]["maxOutputTokens"] == 512
    schema = payload["generationConfig"]["responseSchema"]
    assert schema["type"] == "OBJECT"
    assert set(schema["properties"]) == {
        "type", "amount", "categoryCode", "subcategoryCode", "description", "occurredAt",
    }
    assert set(schema["required"]) == set(schema["properties"])
    assert schema["properties"]["type"]["enum"] == ["expense", "income", "debt_payment"]
    assert schema["properties"]["subcategoryCode"]["type"] == "STRING"
    assert schema["properties"]["subcategoryCode"]["nullable"] is True
    assert access_tokens == ["requested"]
    assert result == {
        "type": "expense",
        "amount": "250.00",
        "categoryCode": "еда",
        "subcategoryCode": None,
        "description": "Обед",
        "occurredAt": "2026-10-12T12:00:00+03:00",
        "modelVersion": "gemini-test-version",
        "promptVersion": TRANSACTION_DRAFT_PROMPT_VERSION,
    }


def test_vertex_rejects_unsupported_task_before_token_or_transport_call():
    calls = []
    provider = VertexAIProvider(
        project_id="p",
        location="us-central1",
        model_id="gemini-test-model",
        access_token_supplier=lambda: calls.append("token"),
        transport=httpx.MockTransport(lambda request: calls.append(request)),
        declared_capabilities=frozenset({"text", "structured_output"}),
    )

    with pytest.raises(UnsupportedCapability):
        asyncio.run(provider.execute("budget-proposal", {}, timeout=2))

    assert calls == []


def test_vertex_missing_access_token_fails_before_network_call():
    calls = []
    provider = VertexAIProvider(
        project_id="p",
        location="us-central1",
        model_id="gemini-test-model",
        access_token_supplier=None,
        transport=httpx.MockTransport(lambda request: calls.append(request)),
        declared_capabilities=frozenset({"text", "structured_output"}),
    )

    with pytest.raises(PolicyDenied):
        asyncio.run(provider.execute("transaction-draft", transaction_draft_context(), timeout=2))

    assert calls == []


def test_vertex_rejects_unsafe_project_path_before_token_or_network_call():
    calls = []
    provider = VertexAIProvider(
        project_id="bad/project",
        location="us-central1",
        model_id="gemini-test-model",
        access_token_supplier=lambda: calls.append("token"),
        transport=httpx.MockTransport(lambda request: calls.append(request)),
        declared_capabilities=frozenset({"text", "structured_output"}),
    )

    with pytest.raises(ValueError):
        asyncio.run(provider.execute("transaction-draft", transaction_draft_context(), timeout=2))

    assert calls == []


@pytest.mark.parametrize("body", [
    {"candidates": []},
    {"candidates": [{"content": {"parts": []}}]},
    {"candidates": [{"content": {"parts": [{"text": "not JSON"}]}}]},
    {"candidates": [{"content": {"parts": [{"text": "{}"}]}, "finishReason": "MAX_TOKENS"}]},
])
def test_vertex_malformed_response_fails_closed_without_retry(body):
    calls = []

    def respond(request):
        calls.append(request)
        return httpx.Response(200, json=body)

    provider = VertexAIProvider(
        project_id="p", location="us-central1", model_id="gemini-test-model",
        access_token_supplier=lambda: "injected-token", transport=httpx.MockTransport(respond),
        declared_capabilities=frozenset({"text", "structured_output"}),
    )

    with pytest.raises(ValueError):
        asyncio.run(provider.execute("transaction-draft", transaction_draft_context(), timeout=2))

    assert len(calls) == 1


def test_vertex_non_json_http_body_fails_closed_without_retry():
    calls = []

    def respond(request):
        calls.append(request)
        return httpx.Response(200, content=b"not-json")

    provider = VertexAIProvider(
        project_id="p", location="us-central1", model_id="gemini-test-model",
        access_token_supplier=lambda: "injected-token", transport=httpx.MockTransport(respond),
        declared_capabilities=frozenset({"text", "structured_output"}),
    )

    with pytest.raises(ValueError):
        asyncio.run(provider.execute("transaction-draft", transaction_draft_context(), timeout=2))

    assert len(calls) == 1


def test_vertex_oversized_response_is_rejected_without_retry():
    calls = []
    response_body = vertex_success_response(valid_transaction_draft())
    response_body["padding"] = "x" * (16 * 1024)
    oversized_body = json.dumps(response_body, ensure_ascii=False).encode()
    assert len(oversized_body) > 16 * 1024

    def respond(request):
        calls.append(request)
        return httpx.Response(200, content=oversized_body)

    provider = VertexAIProvider(
        project_id="p", location="us-central1", model_id="gemini-test-model",
        access_token_supplier=lambda: "injected-token", transport=httpx.MockTransport(respond),
        declared_capabilities=frozenset({"text", "structured_output"}),
    )

    with pytest.raises(ValueError):
        asyncio.run(provider.execute("transaction-draft", transaction_draft_context(), timeout=2))

    assert len(calls) == 1


@pytest.mark.parametrize("draft", [
    {**valid_transaction_draft(), "unexpected": "field"},
    {**valid_transaction_draft(), "amount": "0"},
    {**valid_transaction_draft(), "type": "transfer"},
])
def test_vertex_invalid_transaction_draft_fails_closed_without_retry(draft):
    calls = []

    def respond(request):
        calls.append(request)
        return httpx.Response(200, json=vertex_success_response(draft))

    provider = VertexAIProvider(
        project_id="p", location="us-central1", model_id="gemini-test-model",
        access_token_supplier=lambda: "injected-token", transport=httpx.MockTransport(respond),
        declared_capabilities=frozenset({"text", "structured_output"}),
    )

    with pytest.raises(ValueError):
        asyncio.run(provider.execute("transaction-draft", transaction_draft_context(), timeout=2))

    assert len(calls) == 1


def _gemini_api():
    assert hasattr(intelligence_gateway, "GeminiModelRoute"), "Gemini model routing is not implemented"
    assert hasattr(intelligence_gateway, "GeminiProvider"), "Gemini Developer API adapter is not implemented"
    assert hasattr(intelligence_gateway, "GeminiProviderPool"), "Gemini fallback pool is not implemented"
    assert hasattr(intelligence_gateway, "GeminiRateLimiter"), "Gemini quota guard is not implemented"
    return intelligence_gateway


def _gemini_route(api, model_id, *, tasks=("transaction-draft",), complexity="balanced",
                  capabilities=("text", "structured_output"), rpm=10, tpm=10_000, rpd=100):
    return api.GeminiModelRoute(
        model_id=model_id,
        tasks=frozenset(tasks),
        capabilities=frozenset(capabilities),
        complexity=complexity,
        rpm=rpm,
        tpm=tpm,
        rpd=rpd,
    )


def _gemini_response(value, *, model_version="actual-model-version", usage=None):
    return {
        "candidates": [{
            "content": {"role": "model", "parts": [{"text": json.dumps(value, ensure_ascii=False)}]},
            "finishReason": "STOP",
        }],
        "modelVersion": model_version,
        "usageMetadata": usage or {"promptTokenCount": 17, "candidatesTokenCount": 8},
    }


def test_gemini_route_config_is_validated_without_bundled_model_ids():
    api = _gemini_api()

    routes = api.parse_gemini_model_routes(json.dumps([{
        "modelId": "operator-chosen-model-v1",
        "tasks": ["transaction-draft"],
        "capabilities": ["text", "structured_output"],
        "complexity": "balanced",
        "rpm": 5,
        "tpm": 250_000,
        "rpd": 20,
    }]))

    assert len(routes) == 1
    assert routes[0].model_id == "operator-chosen-model-v1"
    assert routes[0].rpd == 20
    invalid_model = [{"modelId": "bad/name", "tasks": ["transaction-draft"],
                      "capabilities": ["text", "structured_output"], "complexity": "balanced",
                      "rpm": 1, "tpm": 1000, "rpd": 1}]
    with pytest.raises(ValueError, match="identity"):
        api.parse_gemini_model_routes(json.dumps(invalid_model))


def test_gemini_transaction_draft_uses_server_key_and_validated_structured_output():
    api = _gemini_api()
    requests = []

    def respond(request):
        requests.append(request)
        return httpx.Response(200, json=_gemini_response(valid_transaction_draft()))

    provider = api.GeminiProvider(
        _gemini_route(api, "operator-model"),
        api_key="server-only-secret",
        transport=httpx.MockTransport(respond),
        rate_limiter=api.GeminiRateLimiter(),
    )
    result = asyncio.run(provider.execute("transaction-draft", transaction_draft_context(), timeout=3))

    assert len(requests) == 1
    request = requests[0]
    assert request.url.path.endswith("/models/operator-model:generateContent")
    assert request.headers["x-goog-api-key"] == "server-only-secret"
    assert b"server-only-secret" not in request.content
    payload = json.loads(request.content)
    assert payload["generationConfig"]["responseMimeType"] == "application/json"
    assert payload["generationConfig"]["responseSchema"]["type"] == "OBJECT"
    assert result["amount"] == "250.00"
    assert result["provider"] == "gemini"
    assert result["modelVersion"] == "actual-model-version"
    assert result["usage"] == {"inputTokens": 17, "outputTokens": 8, "cost": None}
    assert result["capabilities"] == ["structured_output", "text"]


def test_gemini_receipt_vision_sends_only_reencoded_bounded_image():
    api = _gemini_api()
    image_buffer = BytesIO()
    Image.new("RGB", (40, 20), "white").save(image_buffer, format="PNG")
    original_image = base64.b64encode(image_buffer.getvalue()).decode("ascii")
    image_context = {"imageBase64": original_image}
    requests = []
    vision_result = {"store": None, "date": None, "total": None, "items": []}

    def respond(request):
        requests.append(request)
        return httpx.Response(200, json=_gemini_response(vision_result))

    provider = api.GeminiProvider(
        _gemini_route(api, "operator-vision-model", tasks=("receipt-vision",), complexity="vision",
                      capabilities=("text", "structured_output", "vision")),
        api_key="server-only-secret",
        transport=httpx.MockTransport(respond),
        rate_limiter=api.GeminiRateLimiter(),
        receipt_images_approved=True,
    )
    result = asyncio.run(provider.execute("receipt-vision", image_context, timeout=3))

    payload = json.loads(requests[0].content)
    inline_data = payload["contents"][0]["parts"][1]["inlineData"]
    assert inline_data["mimeType"] == "image/jpeg"
    assert inline_data["data"] != original_image
    assert result["items"] == []


@pytest.mark.parametrize("failure", [
    httpx.Response(429, json={"error": {"status": "RESOURCE_EXHAUSTED"}}),
    httpx.Response(403, json={"error": {"status": "PERMISSION_DENIED",
                                         "message": "Prepay credits depleted for billing account"}}),
    httpx.Response(503, json={"error": {"message": "temporary"}}),
    httpx.ReadTimeout("provider timed out"),
])
def test_gemini_runtime_fallback_uses_one_compatible_model(failure):
    api = _gemini_api()
    calls = []

    def respond(request):
        calls.append(request.url.path)
        if len(calls) == 1:
            if isinstance(failure, Exception):
                raise failure
            return failure
        return httpx.Response(200, json=_gemini_response(valid_transaction_draft(), model_version="fallback-version"))

    pool = api.GeminiProviderPool([
        api.GeminiProvider(_gemini_route(api, "preferred-model"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
        api.GeminiProvider(_gemini_route(api, "fallback-model"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
        api.GeminiProvider(_gemini_route(api, "unused-model"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
    ])

    result = asyncio.run(pool.execute("transaction-draft", transaction_draft_context(), timeout=3))

    assert len(calls) == 2
    assert "fallback-model" in calls[1]
    assert result["modelVersion"] == "fallback-version"
    assert result["fallbackReason"] in {"quota", "timeout", "provider_unavailable"}


def test_gemini_all_models_unavailable_returns_stable_error_after_one_fallback():
    api = _gemini_api()
    calls = []

    def respond(request):
        calls.append(request)
        return httpx.Response(429, json={"error": {"status": "RESOURCE_EXHAUSTED"}})

    pool = api.GeminiProviderPool([
        api.GeminiProvider(_gemini_route(api, "first-model"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
        api.GeminiProvider(_gemini_route(api, "second-model"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
        api.GeminiProvider(_gemini_route(api, "third-model"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
    ])

    with pytest.raises(api.ProviderUnavailable, match="temporarily unavailable"):
        asyncio.run(pool.execute("transaction-draft", transaction_draft_context(), timeout=3))

    assert len(calls) == 2


def test_gemini_pool_routes_by_task_complexity_and_refuses_unsupported_image_task():
    api = _gemini_api()
    calls = []

    def respond(request):
        calls.append(request.url.path)
        return httpx.Response(200, json=_gemini_response(valid_transaction_draft()))

    pool = api.GeminiProviderPool([
        api.GeminiProvider(_gemini_route(api, "fast-model", complexity="fast"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
        api.GeminiProvider(_gemini_route(api, "quality-model", complexity="reasoning"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
    ])

    with pytest.raises(api.UnsupportedCapability):
        asyncio.run(pool.execute("receipt-ocr", {}, timeout=1))
    assert calls == []


def test_gemini_pool_prefers_model_class_for_task_complexity():
    api = _gemini_api()
    calls = []

    def respond(request):
        calls.append(request.url.path)
        return httpx.Response(200, json=_gemini_response(valid_transaction_draft()))

    pool = api.GeminiProviderPool([
        api.GeminiProvider(_gemini_route(api, "fast-model", complexity="fast"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
        api.GeminiProvider(_gemini_route(api, "balanced-model", complexity="balanced"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
    ])

    asyncio.run(pool.execute("transaction-draft", transaction_draft_context(), timeout=3))

    assert len(calls) == 1
    assert "balanced-model" in calls[0]


def test_gemini_capability_router_checks_one_models_actual_task_capabilities():
    api = _gemini_api()
    provider = api.GeminiProvider(
        _gemini_route(api, "text-only-for-this-task"), "key",
        transport=httpx.MockTransport(lambda _request: pytest.fail("Must not call unsupported model")),
        rate_limiter=api.GeminiRateLimiter(),
    )
    pool = api.GeminiProviderPool([provider])

    with pytest.raises(api.UnsupportedCapability):
        api.ProviderRouter([pool]).select(
            {"text", "structured_output", "vision"}, "cloud-opt-in", "transaction-draft",
        )
    with pytest.raises(api.UnsupportedCapability):
        asyncio.run(pool.execute("transaction-draft", transaction_draft_context(), timeout=1,
                                 required_capabilities={"text", "structured_output", "vision"}))


def test_gemini_invalid_json_falls_back_once_and_reports_safe_reason():
    api = _gemini_api()
    calls = []

    def respond(request):
        calls.append(request.url.path)
        output = {"type": "expense", "amount": "0", "categoryCode": "еда", "subcategoryCode": None,
                  "description": "Обед", "occurredAt": "2026-10-12T12:00:00+03:00"}
        if len(calls) == 1:
            return httpx.Response(200, json=_gemini_response(output))
        return httpx.Response(200, json=_gemini_response(valid_transaction_draft()))

    pool = api.GeminiProviderPool([
        api.GeminiProvider(_gemini_route(api, "invalid-output-model"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
        api.GeminiProvider(_gemini_route(api, "valid-output-model"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
    ])

    result = asyncio.run(pool.execute("transaction-draft", transaction_draft_context(), timeout=3))

    assert len(calls) == 2
    assert result["modelVersion"] == "actual-model-version"
    assert result["fallbackReason"] == "invalid_output"


def test_gemini_malformed_json_falls_back_once():
    api = _gemini_api()
    calls = []
    valid_body = _gemini_response(valid_transaction_draft())

    def respond(request):
        calls.append(request.url.path)
        if len(calls) == 1:
            return httpx.Response(200, json={"candidates": [{
                "content": {"parts": [{"text": "not JSON"}]}, "finishReason": "STOP",
            }]})
        return httpx.Response(200, json=valid_body)

    pool = api.GeminiProviderPool([
        api.GeminiProvider(_gemini_route(api, "malformed-model"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
        api.GeminiProvider(_gemini_route(api, "recovery-model"), "key",
                           transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter()),
    ])

    result = asyncio.run(pool.execute("transaction-draft", transaction_draft_context(), timeout=3))

    assert len(calls) == 2
    assert result["fallbackReason"] == "invalid_output"


@pytest.mark.parametrize(("task_kind", "context", "output"), [
    ("budget-proposal", {
        "monthlyIncome": "100000.00", "historyDays": 45, "categoryCodes": ["еда", "транспорт"],
        "monthlyExpenseByMonth": {"2026-09": {"еда": "12000.00"}},
        "monthlyIncomeByMonth": {"2026-09": "95000.00"},
        "currentFamilyLimits": {"еда": "20000.00", "транспорт": "5000.00", "долги": "0.00"},
    }, {"shares": {"еда": 60, "транспорт": 40}}),
    ("receipt-basket-review", {"items": [{"ordinal": 1, "name": "сыр"}]},
     {"items": [{"ordinal": 1, "verdict": "neutral", "reason": "", "action": ""}]}),
    ("merchant-classification", {"merchants": ["Market"]},
     {"classifications": [{"merchant": "market", "categoryCode": "прочее", "confidence": 0.7}]}),
])
def test_gemini_structured_tasks_reuse_existing_validators(task_kind, context, output):
    api = _gemini_api()
    route = _gemini_route(api, f"configured-{task_kind}", tasks=(task_kind,), complexity="reasoning")
    provider = api.GeminiProvider(
        route,
        "key",
        transport=httpx.MockTransport(lambda _request: httpx.Response(200, json=_gemini_response(output))),
        rate_limiter=api.GeminiRateLimiter(),
    )

    result = asyncio.run(provider.execute(task_kind, context, timeout=3))

    assert result["provider"] == "gemini"
    assert result["modelVersion"] == "actual-model-version"
    assert "promptVersion" in result


def test_gemini_per_model_request_limit_is_enforced_before_second_network_call():
    api = _gemini_api()
    calls = []

    def respond(request):
        calls.append(request)
        return httpx.Response(200, json=_gemini_response(valid_transaction_draft()))

    provider = api.GeminiProvider(
        _gemini_route(api, "limited-model", rpm=1), "key",
        transport=httpx.MockTransport(respond), rate_limiter=api.GeminiRateLimiter(),
    )
    pool = api.GeminiProviderPool([provider])
    asyncio.run(pool.execute("transaction-draft", transaction_draft_context(), timeout=3))

    with pytest.raises(api.ProviderUnavailable, match="temporarily unavailable"):
        asyncio.run(pool.execute("transaction-draft", transaction_draft_context(), timeout=3))
    assert len(calls) == 1


def test_gemini_token_and_daily_quota_limits_are_independently_enforced():
    api = _gemini_api()
    now = [60.0]
    limiter = api.GeminiRateLimiter(clock=lambda: now[0])
    route = _gemini_route(api, "bounded-model", rpm=10, tpm=20, rpd=1)

    with pytest.raises(api.GeminiQuotaExceeded):
        limiter.reserve(route, 21)
    limiter.reserve(route, 20)
    with pytest.raises(api.GeminiQuotaExceeded):
        limiter.reserve(route, 1)
    now[0] = 86_400.0
    limiter.reserve(route, 20)
