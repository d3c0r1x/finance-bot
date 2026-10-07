import asyncio
import base64
import json
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from http.server import ThreadingHTTPServer
from types import SimpleNamespace

import httpx
import pytest
from PIL import Image

import services.python.intelligence.app as intelligence_app
from services.python.intelligence.app import IntelligenceHandler


VALID_CONTEXT = {
    "monthlyIncome": "100000.00",
    "historyDays": 45,
    "categoryCodes": ["еда", "транспорт"],
    "monthlyExpenseByMonth": {"2026-09": {"еда": "12000.00"}},
    "monthlyIncomeByMonth": {"2026-09": "95000.00"},
    "currentFamilyLimits": {"еда": "20000.00", "транспорт": "5000.00", "долги": "0.00"},
}


@pytest.fixture(autouse=True)
def local_model_inventory(monkeypatch):
    async def installed_models(_endpoint, **_kwargs):
        return ["qwen-text", "qwen2.5:7b-instruct"]

    monkeypatch.setattr(intelligence_app, "list_installed_models", installed_models, raising=False)


def ai_headers(task_kind, **overrides):
    input_schema, output_schema = {
        "budget-proposal": ("budget-proposal-context.v1", "budget-proposal-advice.v1"),
        "transaction-draft": ("transaction-draft-context.v1", "transaction-draft-advice.v1"),
        "receipt-ocr": ("receipt-ocr-context.v1", "receipt-ocr-result.v1"),
        "receipt-vision": ("receipt-vision-context.v1", "receipt-vision-result.v1"),
        "receipt-basket-review": ("receipt-basket-context.v1", "receipt-basket-review.v1"),
        "merchant-classification": ("merchant-classification-context.v1", "merchant-classification-result.v1"),
    }[task_kind]
    headers = {
        "Authorization": "Bearer private-test-token",
        "X-Finance-Task-Kind": task_kind,
        "X-Finance-Input-Schema-Version": input_schema,
        "X-Finance-Output-Schema-Version": output_schema,
        "X-Finance-Deadline-Unix-Ms": str(int(time.time() * 1000) + 30_000),
        "X-Finance-AI-Policy": "local-only",
        "X-Finance-Correlation-ID": str(uuid.uuid4()),
        "X-Finance-Required-Capabilities": "text,structured_output",
    }
    headers.update(overrides)
    return headers


@pytest.fixture
def internal_server(monkeypatch):
    monkeypatch.setenv("FINANCE_AI_SERVICE_TOKEN", "private-test-token")
    monkeypatch.setenv("FINANCE_AI_POLICY", "local-only")
    monkeypatch.setenv("OLLAMA_HOST", "http://ollama:11434")
    monkeypatch.setenv("OLLAMA_MODEL", "qwen-text")
    async def installed_models(_endpoint, **_kwargs):
        return ["qwen-text"]

    monkeypatch.setattr(intelligence_app, "list_installed_models", installed_models, raising=False)
    generated = []

    class StubIntelligenceHandler(IntelligenceHandler):
        async def _generate(self, context):
            generated.append(context)
            return {
                "shares": {"еда": "60.00", "транспорт": "40.00"},
                "modelVersion": "test-model",
                "promptVersion": "budget-proposal.v1",
            }

        async def _generate_transaction_draft(self, context):
            generated.append(context)
            return {
                "type": "expense",
                "amount": "2000.00",
                "categoryCode": "transport",
                "subcategoryCode": None,
                "description": "Такси",
                "occurredAt": "2026-10-01T09:00:00+03:00",
                "modelVersion": "test-model",
                "promptVersion": "transaction-draft.v2",
            }

        async def _generate_merchant_classifications(self, context):
            generated.append(context)
            return {
                "classifications": [{"merchant": merchant, "categoryCode": "прочее", "confidence": "0.500"}
                                    for merchant in context["merchants"]],
                "modelVersion": "test-model",
                "promptVersion": "merchant-category.v1",
            }

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), StubIntelligenceHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", generated
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_internal_adapter_requires_service_token_and_validated_context(internal_server):
    base_url, generated = internal_server

    with httpx.Client(trust_env=False) as client:
        unauthorized = client.post(f"{base_url}/internal/v1/budget-proposals", json=VALID_CONTEXT)
        invalid = client.post(f"{base_url}/internal/v1/budget-proposals",
                              headers=ai_headers("budget-proposal"),
                              json={**VALID_CONTEXT, "tenantId": "must-not-be-accepted"})
        accepted = client.post(f"{base_url}/internal/v1/budget-proposals",
                               headers=ai_headers("budget-proposal"), json=VALID_CONTEXT)

    assert unauthorized.status_code == 401
    assert invalid.status_code == 400
    assert accepted.status_code == 200
    accepted_body = accepted.json()
    assert isinstance(accepted_body.pop("latencyMs"), int)
    assert str(uuid.UUID(accepted_body.pop("correlationId")))
    assert accepted_body == {
        "provider": "ollama",
        "taskKind": "budget-proposal",
        "inputSchemaVersion": "budget-proposal-context.v1",
        "outputSchemaVersion": "budget-proposal-advice.v1",
        "executionPolicy": "local-only",
        "requiredCapabilities": ["structured_output", "text"],
        "shares": {"еда": "60.00", "транспорт": "40.00"},
        "modelVersion": "test-model",
        "promptVersion": "budget-proposal.v1",
        "capabilities": ["structured_output", "text"],
        "usage": {"inputTokens": None, "outputTokens": None, "cost": None},
        "fallbackReason": None,
        "provenance": {"provider": "ollama", "modelVersion": "test-model",
                       "promptVersion": "budget-proposal.v1", "executionPolicy": "local-only"},
    }
    assert generated == [VALID_CONTEXT]


def test_healthz_stays_shallow_and_private_diagnostics_require_service_token(internal_server):
    base_url, _ = internal_server
    with httpx.Client(trust_env=False) as client:
        live = client.get(f"{base_url}/healthz")
        denied = client.get(f"{base_url}/internal/v1/health")

    assert live.status_code == 200
    assert live.json() == {"status": "ok"}
    assert denied.status_code == 401


def test_private_health_reports_only_sanitized_capability_codes(internal_server, monkeypatch):
    base_url, _ = internal_server

    async def fake_health():
        return {
            "capabilities": {
                "localAi": {"status": "available", "diagnosticCode": None},
                "receiptVision": {"status": "unavailable", "diagnosticCode": "MODEL_MISSING"},
                "receiptOcr": {"status": "available", "diagnosticCode": None},
            }
        }

    monkeypatch.setattr(IntelligenceHandler, "_collect_health", lambda self: fake_health(), raising=False)
    with httpx.Client(trust_env=False) as client:
        response = client.get(f"{base_url}/internal/v1/health",
                              headers={"Authorization": "Bearer private-test-token"})

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"capabilities"}
    assert body["capabilities"]["localAi"]["status"] == "available"
    assert body["capabilities"]["receiptVision"]["diagnosticCode"] == "MODEL_MISSING"
    serialized = json.dumps(body)
    for private_value in ("private-test-token", "ollama:11434", "qwen", "TESSERACT_CMD", "C:/"):
        assert private_value not in serialized


def test_local_provider_uses_only_an_installed_preferred_text_model(monkeypatch):
    monkeypatch.setenv("OLLAMA_HOST", "http://localhost:11434")
    monkeypatch.setenv("FINANCE_AI_POLICY", "local-only")
    monkeypatch.setenv("OLLAMA_MODEL", "configured:missing")
    monkeypatch.setenv("OLLAMA_MODEL_PREFERENCE", "preferred:8b,preferred:4b")

    async def installed_models(_endpoint, **_kwargs):
        return ["preferred:4b", "preferred:8b", "embed-small"]

    monkeypatch.setattr(intelligence_app, "list_installed_models", installed_models, raising=False)
    handler = object.__new__(IntelligenceHandler)
    providers = handler._providers()

    assert providers[0].model == "preferred:8b"


def test_missing_local_models_keeps_receipt_ocr_provider(monkeypatch):
    monkeypatch.setenv("OLLAMA_HOST", "http://localhost:11434")
    monkeypatch.setenv("FINANCE_AI_POLICY", "local-only")
    monkeypatch.setenv("OLLAMA_MODEL", "configured:missing")

    async def no_models(_endpoint, **_kwargs):
        return []

    monkeypatch.setattr(intelligence_app, "list_installed_models", no_models, raising=False)
    providers = object.__new__(IntelligenceHandler)._providers()
    assert not any(provider.name == "ollama" for provider in providers)
    assert any(provider.name == "tesseract" for provider in providers)


def test_cloud_opt_in_uses_only_configured_model_without_remote_inventory(monkeypatch):
    monkeypatch.setenv("OLLAMA_HOST", "https://ollama.example")
    monkeypatch.setenv("FINANCE_AI_POLICY", "cloud-opt-in")
    monkeypatch.setenv("OLLAMA_MODEL", "cloud-configured:latest")

    async def forbidden_inventory(*_args, **_kwargs):
        raise AssertionError("Cloud model inventory must not be queried")

    monkeypatch.setattr(intelligence_app, "list_installed_models", forbidden_inventory, raising=False)
    handler = object.__new__(IntelligenceHandler)
    providers = handler._providers()

    assert providers[0].model == "cloud-configured:latest"


def test_missing_local_ollama_returns_safe_unavailable_without_inference(internal_server, monkeypatch):
    base_url, generated = internal_server

    async def no_models(_endpoint, **_kwargs):
        return []

    monkeypatch.setattr(intelligence_app, "list_installed_models", no_models, raising=False)
    with httpx.Client(trust_env=False) as client:
        response = client.post(f"{base_url}/internal/v1/budget-proposals",
                               headers=ai_headers("budget-proposal"), json=VALID_CONTEXT)

    assert response.status_code == 503
    assert response.json() == {"error": "unavailable"}
    assert generated == []


def test_internal_merchant_classification_is_authenticated_and_validates_minimal_context(internal_server):
    base_url, generated = internal_server
    payload = {"merchants": ["  Market  ", "Taxi"]}
    with httpx.Client(trust_env=False) as client:
        denied = client.post(f"{base_url}/internal/v1/merchant-classifications", json=payload)
        invalid = client.post(f"{base_url}/internal/v1/merchant-classifications",
                              headers=ai_headers("merchant-classification"),
                              json={**payload, "tenantId": "must-not-leak"})
        accepted = client.post(f"{base_url}/internal/v1/merchant-classifications",
                               headers=ai_headers("merchant-classification"), json=payload)

    assert denied.status_code == 401
    assert invalid.status_code == 400
    assert accepted.status_code == 200
    body = accepted.json()
    assert body["taskKind"] == "merchant-classification"
    assert body["classifications"] == [
        {"merchant": "market", "categoryCode": "прочее", "confidence": "0.500"},
        {"merchant": "taxi", "categoryCode": "прочее", "confidence": "0.500"},
    ]
    assert generated == [{"merchants": ["market", "taxi"]}]


def test_internal_tbank_parser_requires_service_token_and_returns_strict_result(internal_server, monkeypatch):
    base_url, _ = internal_server
    parsed = {
        "operations": [{"operationDate": "2026-10-01", "operationTime": "12:30", "signedAmount": "-12.00",
                        "currency": "RUB", "kind": "purchase", "merchant": "Market",
                        "description": "Оплата в Market", "cardLast4": "1234"}],
        "parsedExpenseTotal": "12.00", "parsedIncomeTotal": "0.00", "expectedExpenseTotal": "12.00",
        "expectedIncomeTotal": "0.00", "quality": "valid", "periodStart": "2026-10-01",
        "periodEnd": "2026-10-01", "parseVersion": "tbank-pdf.v1",
    }
    received = []
    monkeypatch.setattr(intelligence_app, "parse_tbank_pdf_bytes",
                        lambda content: (received.append(content), SimpleNamespace(to_payload=lambda: parsed))[1])
    encoded = base64.b64encode(b"%PDF-test").decode("ascii")

    with httpx.Client(trust_env=False) as client:
        denied = client.post(f"{base_url}/internal/v1/imports/tbank", json={"pdfBase64": encoded})
        accepted = client.post(f"{base_url}/internal/v1/imports/tbank",
                               headers={"Authorization": "Bearer private-test-token"},
                               json={"pdfBase64": encoded})

    assert denied.status_code == 401
    assert accepted.status_code == 200
    assert accepted.json() == parsed
    assert received == [b"%PDF-test"]


def test_internal_tbank_parser_rejects_malformed_base64(internal_server):
    base_url, _ = internal_server
    with httpx.Client(trust_env=False) as client:
        response = client.post(f"{base_url}/internal/v1/imports/tbank",
                               headers={"Authorization": "Bearer private-test-token"},
                               json={"pdfBase64": "%%%"})
    assert response.status_code == 400
    assert response.json() == {"error": "invalid_request"}


def test_internal_adapter_requires_versioned_ai_request_envelope(internal_server):
    base_url, generated = internal_server

    with httpx.Client(trust_env=False) as client:
        response = client.post(
            f"{base_url}/internal/v1/budget-proposals",
            headers={"Authorization": "Bearer private-test-token"},
            json=VALID_CONTEXT,
        )

    assert response.status_code == 400
    assert response.json() == {"error": "invalid_request"}
    assert generated == []


def test_internal_adapter_reports_health_without_exposing_model_configuration(internal_server):
    base_url, _ = internal_server

    with httpx.Client(trust_env=False) as client:
        response = client.get(f"{base_url}/healthz")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_internal_adapter_rejects_oversized_request(internal_server):
    base_url, generated = internal_server
    body = json.dumps(VALID_CONTEXT).encode() + b" " * (32 * 1024)

    with httpx.Client(trust_env=False) as client:
        response = client.post(f"{base_url}/internal/v1/budget-proposals",
                              headers=ai_headers("budget-proposal") | {
                                       "Content-Type": "application/json"}, content=body)

    assert response.status_code == 413
    assert generated == []


def test_internal_adapter_requires_service_token_for_text_transaction_drafts(internal_server):
    base_url, _ = internal_server
    context = {
        "text": "Такси 2 тыс",
        "timezone": "Europe/Moscow",
        "now": "2026-10-01T12:00:00+03:00",
    }

    with httpx.Client(trust_env=False) as client:
        unauthorized = client.post(f"{base_url}/internal/v1/transaction-drafts", json=context)
        accepted = client.post(
            f"{base_url}/internal/v1/transaction-drafts",
            headers=ai_headers("transaction-draft"),
            json=context,
        )

    assert unauthorized.status_code == 401
    assert accepted.status_code == 200
    accepted_body = accepted.json()
    assert isinstance(accepted_body.pop("latencyMs"), int)
    assert str(uuid.UUID(accepted_body.pop("correlationId")))
    assert accepted_body == {
        "provider": "ollama",
        "taskKind": "transaction-draft",
        "inputSchemaVersion": "transaction-draft-context.v1",
        "outputSchemaVersion": "transaction-draft-advice.v1",
        "executionPolicy": "local-only",
        "requiredCapabilities": ["structured_output", "text"],
        "type": "expense",
        "amount": "2000.00",
        "categoryCode": "transport",
        "subcategoryCode": None,
        "description": "Такси",
        "occurredAt": "2026-10-01T09:00:00+03:00",
        "modelVersion": "test-model",
        "promptVersion": "transaction-draft.v2",
        "capabilities": ["structured_output", "text"],
        "usage": {"inputTokens": None, "outputTokens": None, "cost": None},
        "fallbackReason": None,
        "provenance": {"provider": "ollama", "modelVersion": "test-model",
                       "promptVersion": "transaction-draft.v2", "executionPolicy": "local-only"},
    }


def test_local_only_blocks_remote_ollama_before_inference(internal_server, monkeypatch):
    base_url, generated = internal_server
    monkeypatch.setenv("OLLAMA_HOST", "https://remote.example")
    context = {
        "text": "Такси 2 тыс",
        "timezone": "Europe/Moscow",
        "now": "2026-10-01T12:00:00+03:00",
    }

    with httpx.Client(trust_env=False) as client:
        response = client.post(f"{base_url}/internal/v1/transaction-drafts",
                               headers=ai_headers("transaction-draft"), json=context)

    assert response.status_code == 403
    assert response.json() == {"error": "policy_denied"}
    assert generated == []


def test_remote_ollama_runs_only_after_explicit_cloud_opt_in(internal_server, monkeypatch):
    base_url, generated = internal_server
    monkeypatch.setenv("OLLAMA_HOST", "https://remote.example")
    monkeypatch.setenv("FINANCE_AI_POLICY", "cloud-opt-in")
    context = {
        "text": "Такси 2 тыс",
        "timezone": "Europe/Moscow",
        "now": "2026-10-01T12:00:00+03:00",
    }

    with httpx.Client(trust_env=False) as client:
        response = client.post(f"{base_url}/internal/v1/transaction-drafts",
                               headers=ai_headers("transaction-draft", **{
                                   "X-Finance-AI-Policy": "cloud-opt-in"}), json=context)

    assert response.status_code == 200
    assert response.json()["provider"] == "ollama"
    assert generated == [context]


def test_request_cloud_opt_in_cannot_override_local_only_gateway(internal_server, monkeypatch):
    base_url, generated = internal_server
    monkeypatch.setenv("OLLAMA_HOST", "https://remote.example")
    context = {
        "text": "Такси 2 тыс",
        "timezone": "Europe/Moscow",
        "now": "2026-10-01T12:00:00+03:00",
    }

    with httpx.Client(trust_env=False) as client:
        response = client.post(
            f"{base_url}/internal/v1/transaction-drafts",
            headers=ai_headers("transaction-draft", **{"X-Finance-AI-Policy": "cloud-opt-in"}),
            json=context,
        )

    assert response.status_code == 403
    assert response.json() == {"error": "policy_denied"}
    assert generated == []


def test_gateway_rejects_unsupported_requested_capability_before_inference(internal_server):
    base_url, generated = internal_server

    with httpx.Client(trust_env=False) as client:
        response = client.post(
            f"{base_url}/internal/v1/budget-proposals",
            headers=ai_headers("budget-proposal", **{
                "X-Finance-Required-Capabilities": "text,structured_output,vision",
            }),
            json=VALID_CONTEXT,
        )

    assert response.status_code == 422
    assert response.json() == {"error": "unsupported_capability"}
    assert generated == []


def test_gateway_rejects_wrong_task_schema_and_expired_deadline(internal_server):
    base_url, generated = internal_server

    with httpx.Client(trust_env=False) as client:
        wrong_task = client.post(
            f"{base_url}/internal/v1/budget-proposals",
            headers=ai_headers("transaction-draft"),
            json=VALID_CONTEXT,
        )
        expired = client.post(
            f"{base_url}/internal/v1/budget-proposals",
            headers=ai_headers("budget-proposal", **{
                "X-Finance-Deadline-Unix-Ms": str(int(time.time() * 1000) - 1),
            }),
            json=VALID_CONTEXT,
        )

    assert wrong_task.status_code == 400
    assert expired.status_code == 400
    assert generated == []


def test_receipt_ocr_routes_to_local_tesseract_and_preserves_provenance(monkeypatch):
    monkeypatch.setenv("FINANCE_AI_SERVICE_TOKEN", "private-test-token")
    image_buffer = BytesIO()
    Image.new("RGB", (40, 20), "white").save(image_buffer, format="PNG")
    context = {"imageBase64": base64.b64encode(image_buffer.getvalue()).decode("ascii")}

    class ReceiptOcrHandler(IntelligenceHandler):
        def _providers(self):
            from services.python.intelligence.tesseract import TesseractProvider

            return [TesseractProvider(
                ocr_engine=lambda *_args: {
                    "text": ["TOTAL", "123.45"], "conf": ["91", "88"],
                    "left": [1, 20], "top": [2, 2], "width": [12, 30], "height": [8, 8],
                },
                version="5.3.0",
            )]

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), ReceiptOcrHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        headers = ai_headers("receipt-ocr", **{"X-Finance-Required-Capabilities": "text"})
        with httpx.Client(trust_env=False) as client:
            response = client.post(f"http://127.0.0.1:{server.server_port}/internal/v1/receipts/ocr",
                                   headers=headers, json=context)

        assert response.status_code == 200
        body = response.json()
        assert body["provider"] == "tesseract"
        assert body["text"] == "TOTAL 123.45"
        assert body["total"] == "123.45"
        assert body["items"] == []
        assert body["modelVersion"] == "tesseract-5.3.0"
        assert body["provenance"]["provider"] == "tesseract"
        assert body["capabilities"] == ["text"]
        assert body["requiredCapabilities"] == ["text"]
        assert body["executionPolicy"] == "local-only"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_receipt_basket_review_uses_private_local_ai_route_and_preserves_ordinals(monkeypatch):
    monkeypatch.setenv("FINANCE_AI_SERVICE_TOKEN", "private-test-token")
    monkeypatch.setenv("FINANCE_AI_POLICY", "local-only")
    monkeypatch.setenv("OLLAMA_HOST", "http://ollama:11434")
    generated = []
    context = {"items": [{"ordinal": 1, "name": "Пиво"}, {"ordinal": 2, "name": "Молоко"}]}

    class BasketHandler(IntelligenceHandler):
        def _providers(self):
            from services.python.intelligence.gateway import OllamaProvider
            return [OllamaProvider("http://ollama:11434", "qwen-text")]

        async def _generate_receipt_basket_review(self, value):
            generated.append(value)
            return {"items": [
                {"ordinal": 1, "verdict": "harmful", "reason": "алкоголь", "action": "ограничить покупку"},
                {"ordinal": 2, "verdict": "neutral", "reason": "", "action": ""},
            ], "modelVersion": "test-model", "promptVersion": "receipt-basket.v1"}

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), BasketHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        headers = ai_headers("receipt-basket-review")
        with httpx.Client(trust_env=False) as client:
            response = client.post(f"http://127.0.0.1:{server.server_port}/internal/v1/receipts/basket-review",
                                   headers=headers, json=context)

        assert response.status_code == 200
        body = response.json()
        assert body["provider"] == "ollama"
        assert body["items"][0]["ordinal"] == 1
        assert body["items"][1]["ordinal"] == 2
        assert body["provenance"]["promptVersion"] == "receipt-basket.v1"
        assert body["executionPolicy"] == "local-only"
        assert generated == [context]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_receipt_vision_requires_allowlisted_vision_model_before_network(internal_server, monkeypatch):
    base_url, _ = internal_server
    image_buffer = BytesIO()
    Image.new("RGB", (40, 20), "white").save(image_buffer, format="PNG")
    context = {"imageBase64": base64.b64encode(image_buffer.getvalue()).decode("ascii")}
    monkeypatch.delenv("OLLAMA_VISION_MODELS", raising=False)

    with httpx.Client(trust_env=False) as client:
        response = client.post(
            f"{base_url}/internal/v1/receipts/vision",
            headers=ai_headers("receipt-vision", **{
                "X-Finance-Required-Capabilities": "vision,structured_output",
            }),
            json=context,
        )

    assert response.status_code == 422
    assert response.json() == {"error": "unsupported_capability"}


def test_receipt_vision_routes_to_allowlisted_model_and_preserves_provenance(monkeypatch):
    monkeypatch.setenv("FINANCE_AI_SERVICE_TOKEN", "private-test-token")
    image_buffer = BytesIO()
    Image.new("RGB", (40, 20), "white").save(image_buffer, format="PNG")
    context = {"imageBase64": base64.b64encode(image_buffer.getvalue()).decode("ascii")}
    reading = {
        "store": "Магазин у дома",
        "date": "2026-10-01",
        "total": "100.00",
        "items": [{"name": "Хлеб", "quantity": "1", "unitPrice": "100.00", "lineSum": "100.00"}],
    }
    calls = []

    class Response:
        status_code = 200

        def raise_for_status(self):
            return None

        def json(self):
            return {"model": "qwen3-vl:8b-instruct", "message": {"content": json.dumps(reading)}}

    class Client:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

        async def post(self, url, json=None, headers=None):
            calls.append((url, json, headers))
            return Response()

    class AllowlistedVisionHandler(IntelligenceHandler):
        def _providers(self):
            from services.python.intelligence.vision import OllamaVisionProvider

            return [OllamaVisionProvider("http://ollama:11434", "qwen3-vl:8b-instruct",
                                         http_client_factory=lambda **_kwargs: Client())]

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), AllowlistedVisionHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        headers = ai_headers("receipt-vision", **{
            "X-Finance-Required-Capabilities": "vision,structured_output",
        })
        with httpx.Client(trust_env=False) as client:
            response = client.post(f"http://127.0.0.1:{server.server_port}/internal/v1/receipts/vision",
                                   headers=headers, json=context)

        assert response.status_code == 200
        body = response.json()
        assert body["provider"] == "ollama"
        assert body["taskKind"] == "receipt-vision"
        assert body["modelVersion"] == "qwen3-vl:8b-instruct"
        assert body["promptVersion"] == "receipt-vision.v1"
        assert body["capabilities"] == ["structured_output", "text", "vision"]
        assert body["provenance"]["promptVersion"] == "receipt-vision.v1"
        assert calls[0][0] == "http://ollama:11434/api/chat"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_receipt_vision_model_failover_is_typed_and_returns_actual_model(monkeypatch):
    monkeypatch.setenv("FINANCE_AI_SERVICE_TOKEN", "private-test-token")
    image_buffer = BytesIO()
    Image.new("RGB", (40, 20), "white").save(image_buffer, format="PNG")
    context = {"imageBase64": base64.b64encode(image_buffer.getvalue()).decode("ascii")}

    class VisionModel:
        name = "ollama"
        capabilities = frozenset({"text", "structured_output", "vision"})
        supported_tasks = frozenset({"receipt-vision"})

        def __init__(self, model, available):
            self.model = model
            self.available = available

        async def execute(self, *_args, **_kwargs):
            if not self.available:
                raise TimeoutError("model unavailable")
            return {"store": None, "date": None, "total": None, "items": [],
                    "modelVersion": self.model, "promptVersion": "receipt-vision.v1"}

    class FallbackVisionHandler(IntelligenceHandler):
        def _providers(self):
            from services.python.intelligence.vision import OllamaVisionPoolProvider

            return [OllamaVisionPoolProvider([VisionModel("preferred-vlm", False),
                                              VisionModel("fallback-vlm", True)])]

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), FallbackVisionHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        headers = ai_headers("receipt-vision", **{
            "X-Finance-Required-Capabilities": "vision,structured_output",
        })
        with httpx.Client(trust_env=False) as client:
            response = client.post(f"http://127.0.0.1:{server.server_port}/internal/v1/receipts/vision",
                                   headers=headers, json=context)

        assert response.status_code == 200
        assert response.json()["modelVersion"] == "fallback-vlm"
        assert response.json()["fallbackReason"] == "vision_model_unavailable"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_gateway_cancels_inflight_inference_by_correlation_id(monkeypatch):
    monkeypatch.setenv("FINANCE_AI_SERVICE_TOKEN", "private-test-token")
    started = threading.Event()

    class SlowInferenceHandler(IntelligenceHandler):
        async def _generate_transaction_draft(self, context):
            started.set()
            await asyncio.Event().wait()

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), SlowInferenceHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base_url = f"http://127.0.0.1:{server.server_port}"
    request_headers = ai_headers("transaction-draft")
    correlation_id = request_headers["X-Finance-Correlation-ID"]
    context = {
        "text": "Такси 2 тыс",
        "timezone": "Europe/Moscow",
        "now": "2026-10-01T12:00:00+03:00",
    }
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            def send_slow_request():
                with httpx.Client(trust_env=False) as client:
                    return client.post(f"{base_url}/internal/v1/transaction-drafts",
                                       headers=request_headers, json=context, timeout=5)

            pending = pool.submit(send_slow_request)
            assert started.wait(timeout=2)
            with httpx.Client(trust_env=False) as client:
                cancelled = client.post(
                    f"{base_url}/internal/v1/jobs/{correlation_id}/cancel",
                    headers={"Authorization": "Bearer private-test-token"},
                    timeout=2,
                )
            response = pending.result(timeout=2)

        assert cancelled.status_code == 200
        assert cancelled.json() == {"status": "cancellation_requested"}
        assert response.status_code == 409
        assert response.json() == {"error": "cancelled"}
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


@pytest.mark.parametrize(
    ("failure", "expected_status", "expected_error"),
    [("unavailable", 503, "unavailable"), ("timeout", 504, "timeout"),
     ("quota", 429, "quota"), ("invalid", 502, "invalid_output")],
)
def test_internal_draft_inference_failure_is_safe(failure, expected_status, expected_error, monkeypatch):
    monkeypatch.setenv("FINANCE_AI_SERVICE_TOKEN", "private-test-token")

    class FailedInferenceHandler(IntelligenceHandler):
        async def _generate_transaction_draft(self, context):
            if failure == "unavailable":
                raise httpx.ConnectError("local model disabled")
            if failure == "timeout":
                raise httpx.ReadTimeout("local model timed out")
            if failure == "quota":
                request = httpx.Request("POST", "http://ollama/api/generate")
                response = httpx.Response(429, request=request)
                raise httpx.HTTPStatusError("provider quota", request=request, response=response)
            raise ValueError("invalid model JSON")

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), FailedInferenceHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        context = {
            "text": "Такси 2 тыс",
            "timezone": "Europe/Moscow",
            "now": "2026-10-01T12:00:00+03:00",
        }
        with httpx.Client(trust_env=False) as client:
            response = client.post(
                f"http://127.0.0.1:{server.server_port}/internal/v1/transaction-drafts",
                headers=ai_headers("transaction-draft"),
                json=context,
            )
        assert response.status_code == expected_status
        assert response.json() == {"error": expected_error}
        assert "Такси" not in response.text
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
