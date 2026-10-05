"""Private internal HTTP adapter for constrained Ollama tasks."""

from __future__ import annotations

import asyncio
import base64
import binascii
import hmac
import json
import os
import re
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx

from services.python.intelligence.budget_proposals import validate_context
from services.python.intelligence.gateway import (AiRequestEnvelope, OllamaProvider, PolicyDenied, ProviderRouter,
                                                  RequestCancelled, RequestDeadlineExceeded, UnsupportedCapability,
                                                  authorize_provider,
                                                  restrictive_policy, run_with_controls)
from services.python.intelligence.model_scheduler import OLLAMA_MODEL_SCHEDULER
from services.python.intelligence.merchant_classification import validate_context as validate_merchant_context
from services.python.intelligence.tesseract import TesseractBusy, TesseractProvider, TesseractUnavailable
from services.python.intelligence.transaction_drafts import validate_draft_context
from services.python.intelligence.receipt_basket_review import validate_context as validate_basket_context
from services.python.document_import.tbank_pdf import StatementParseError, parse_tbank_pdf_bytes

MAX_BODY_BYTES = 32 * 1024
MAX_OCR_BODY_BYTES = 17 * 1024 * 1024
MAX_IMPORT_BODY_BYTES = 17 * 1024 * 1024
MAX_IMPORT_PDF_BYTES = 12 * 1024 * 1024
AI_SLOTS = threading.BoundedSemaphore(2)
ACTIVE_REQUESTS: dict[str, threading.Event] = {}
ACTIVE_REQUESTS_LOCK = threading.Lock()


class IntelligenceHandler(BaseHTTPRequestHandler):
    server_version = "FinanceIntelligence/1.0"

    def do_GET(self):
        if self.path != "/healthz":
            self._reply(404, {"error": "not_found"})
            return
        self._reply(200, {"status": "ok"})

    def do_POST(self):
        cancel_match = re.fullmatch(r"/internal/v1/jobs/([0-9a-fA-F-]{36})/cancel", self.path)
        if self.path not in {"/internal/v1/budget-proposals", "/internal/v1/transaction-drafts",
                             "/internal/v1/receipts/ocr", "/internal/v1/receipts/vision",
                             "/internal/v1/receipts/basket-review", "/internal/v1/imports/tbank",
                             "/internal/v1/merchant-classifications"} and not cancel_match:
            self._reply(404, {"error": "not_found"})
            return
        if not self._authenticated():
            return
        if cancel_match:
            self._cancel(cancel_match.group(1))
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._reply(400, {"error": "invalid_content_length"})
            return
        if self.path == "/internal/v1/imports/tbank":
            max_body = MAX_IMPORT_BODY_BYTES
        else:
            max_body = MAX_OCR_BODY_BYTES if self.path.startswith("/internal/v1/receipts/") else MAX_BODY_BYTES
        if length <= 0 or length > max_body:
            self._reply(413, {"error": "request_too_large"})
            return
        try:
            submitted = json.loads(self.rfile.read(length))
            if self.path == "/internal/v1/imports/tbank":
                if not isinstance(submitted, dict) or set(submitted) != {"pdfBase64"} \
                        or not isinstance(submitted["pdfBase64"], str):
                    raise ValueError("Statement PDF payload is invalid")
                pdf_contents = base64.b64decode(submitted["pdfBase64"], validate=True)
                if not pdf_contents or len(pdf_contents) > MAX_IMPORT_PDF_BYTES:
                    self._reply(413 if pdf_contents else 400,
                                {"error": "request_too_large" if pdf_contents else "invalid_request"})
                    return
                try:
                    self._reply(200, parse_tbank_pdf_bytes(pdf_contents).to_payload())
                except StatementParseError as error:
                    self._reply(422, {"error": error.code, "message": str(error)})
                return
            if self.path == "/internal/v1/budget-proposals":
                task_kind = "budget-proposal"
                context = validate_context(submitted)
                generate = self._generate
            elif self.path == "/internal/v1/transaction-drafts":
                task_kind = "transaction-draft"
                context = validate_draft_context(submitted)
                generate = self._generate_transaction_draft
            elif self.path == "/internal/v1/receipts/basket-review":
                task_kind = "receipt-basket-review"
                context = validate_basket_context(submitted)
                generate = self._generate_receipt_basket_review
            elif self.path == "/internal/v1/merchant-classifications":
                task_kind = "merchant-classification"
                context = validate_merchant_context(submitted)
                generate = self._generate_merchant_classifications
            else:
                task_kind = "receipt-ocr" if self.path.endswith("/ocr") else "receipt-vision"
                if not isinstance(submitted, dict) or set(submitted) != {"imageBase64"}:
                    raise ValueError("Receipt image context is invalid")
                if task_kind == "receipt-ocr":
                    context = TesseractProvider.prepare_context(submitted)
                    generate = self._generate_receipt_ocr
                else:
                    TesseractProvider._decode_image(submitted)
                    context = submitted
                    generate = self._generate_receipt_vision
            envelope = AiRequestEnvelope.from_headers(self.headers, task_kind)
            execution_policy = restrictive_policy(os.getenv("FINANCE_AI_POLICY", "local-only").strip(),
                                                  envelope.execution_policy)
            provider = self._provider(execution_policy, envelope.required_capabilities, task_kind)
        except PolicyDenied:
            self._reply(403, {"error": "policy_denied"})
            return
        except UnsupportedCapability:
            self._reply(422, {"error": "unsupported_capability"})
            return
        except (ValueError, TypeError, json.JSONDecodeError):
            self._reply(400, {"error": "invalid_request"})
            return
        if not AI_SLOTS.acquire(blocking=False):
            self._reply(429, {"error": "quota"})
            return
        cancellation = threading.Event()
        with ACTIVE_REQUESTS_LOCK:
            if envelope.correlation_id in ACTIVE_REQUESTS:
                AI_SLOTS.release()
                self._reply(409, {"error": "correlation_conflict"})
                return
            ACTIVE_REQUESTS[envelope.correlation_id] = cancellation
        self._execution_policy = execution_policy
        self._active_envelope = envelope
        self._active_provider = provider
        try:
            started = time.perf_counter()
            inference = lambda: generate(context)
            if provider.name == "ollama":
                inference = lambda: OLLAMA_MODEL_SCHEDULER.run(lambda: generate(context))
            result = asyncio.run(run_with_controls(inference, cancellation,
                                                   envelope.deadline_unix_ms))
            fallback_reason = result.pop("fallbackReason", None)
            model_version = result.get("modelVersion")
            prompt_version = result.get("promptVersion")
            self._reply(200, {"provider": provider.name, **result,
                              "taskKind": envelope.task_kind,
                              "inputSchemaVersion": envelope.input_schema_version,
                              "outputSchemaVersion": envelope.output_schema_version,
                              "correlationId": envelope.correlation_id,
                              "executionPolicy": execution_policy,
                              "requiredCapabilities": sorted(envelope.required_capabilities),
                              "capabilities": sorted(provider.capabilities),
                              "latencyMs": max(0, round((time.perf_counter() - started) * 1000)),
                              "usage": {"inputTokens": None, "outputTokens": None, "cost": None},
                              "fallbackReason": fallback_reason,
                              "provenance": {"provider": provider.name, "modelVersion": model_version,
                                             "promptVersion": prompt_version,
                                             "executionPolicy": execution_policy}})
        except RequestCancelled:
            self._reply(409, {"error": "cancelled"})
        except RequestDeadlineExceeded:
            self._reply(504, {"error": "timeout"})
        except TesseractBusy:
            self._reply(429, {"error": "quota"})
        except TimeoutError:
            self._reply(504, {"error": "timeout"})
        except TesseractUnavailable:
            self._reply(503, {"error": "unavailable"})
        except httpx.TimeoutException:
            self._reply(504, {"error": "timeout"})
        except httpx.HTTPStatusError as error:
            self._reply(429 if error.response.status_code == 429 else 503,
                        {"error": "quota" if error.response.status_code == 429 else "unavailable"})
        except httpx.HTTPError:
            self._reply(503, {"error": "unavailable"})
        except ValueError:
            self._reply(502, {"error": "invalid_output"})
        finally:
            with ACTIVE_REQUESTS_LOCK:
                ACTIVE_REQUESTS.pop(envelope.correlation_id, None)
            AI_SLOTS.release()

    def _authenticated(self):
        expected_token = os.getenv("FINANCE_AI_SERVICE_TOKEN", "")
        if not expected_token:
            self._reply(503, {"error": "service_auth_not_configured"})
            return False
        supplied = self.headers.get("Authorization", "")
        if not hmac.compare_digest(supplied, f"Bearer {expected_token}"):
            self._reply(401, {"error": "unauthorized"})
            return False
        return True

    def _cancel(self, correlation_id: str):
        try:
            normalized = str(uuid.UUID(correlation_id))
        except ValueError:
            self._reply(400, {"error": "invalid_correlation_id"})
            return
        with ACTIVE_REQUESTS_LOCK:
            cancellation = ACTIVE_REQUESTS.get(normalized)
            if cancellation:
                cancellation.set()
        if cancellation:
            self._reply(200, {"status": "cancellation_requested"})
        else:
            self._reply(404, {"error": "job_not_found"})

    def _providers(self):
        endpoint = authorize_provider(os.getenv("OLLAMA_HOST", "http://localhost:11434").rstrip("/"),
                                      "cloud-opt-in")
        model = os.getenv("OLLAMA_MODEL", "qwen2.5:7b-instruct")
        from services.python.intelligence.vision import (OllamaVisionPoolProvider, OllamaVisionProvider,
                                                         parse_vision_models)
        vision_models = parse_vision_models(os.getenv("OLLAMA_VISION_MODELS", ""))
        from services.python.intelligence.gateway import VertexAIProvider

        providers = [OllamaProvider(endpoint.endpoint, model)]
        if vision_models:
            vision_pool = OllamaVisionPoolProvider(
                [OllamaVisionProvider(endpoint.endpoint, vision_model) for vision_model in vision_models])
            providers.append(vision_pool)
        providers.extend([TesseractProvider(), VertexAIProvider()])
        return providers

    def _provider(self, requested_policy=None, required_capabilities=frozenset({"text", "structured_output"}),
                  task_kind=None):
        policy = restrictive_policy(os.getenv("FINANCE_AI_POLICY", "local-only").strip(),
                                    requested_policy or os.getenv("FINANCE_AI_POLICY", "local-only").strip())
        return ProviderRouter(self._providers()).select(required_capabilities, policy, task_kind)

    async def _generate(self, context):
        timeout = min(float(os.getenv("FINANCE_AI_TIMEOUT_SECONDS", "90")), self._active_envelope.remaining_seconds)
        return await self._active_provider.execute("budget-proposal", context, timeout,
                                                    os.getenv("OLLAMA_API_KEY", ""))

    async def _generate_transaction_draft(self, context):
        timeout = min(float(os.getenv("FINANCE_AI_TIMEOUT_SECONDS", "90")), self._active_envelope.remaining_seconds)
        return await self._active_provider.execute("transaction-draft", context, timeout,
                                                    os.getenv("OLLAMA_API_KEY", ""))

    async def _generate_receipt_ocr(self, context):
        timeout = min(float(os.getenv("FINANCE_AI_TIMEOUT_SECONDS", "90")), self._active_envelope.remaining_seconds)
        return await self._active_provider.execute("receipt-ocr", context, timeout)

    async def _generate_receipt_vision(self, context):
        timeout = min(float(os.getenv("FINANCE_AI_TIMEOUT_SECONDS", "90")), self._active_envelope.remaining_seconds)
        return await self._active_provider.execute("receipt-vision", context, timeout,
                                                    os.getenv("OLLAMA_API_KEY", ""))

    async def _generate_receipt_basket_review(self, context):
        timeout = min(float(os.getenv("FINANCE_AI_TIMEOUT_SECONDS", "90")), self._active_envelope.remaining_seconds)
        return await self._active_provider.execute("receipt-basket-review", context, timeout,
                                                    os.getenv("OLLAMA_API_KEY", ""))

    async def _generate_merchant_classifications(self, context):
        timeout = min(float(os.getenv("FINANCE_AI_TIMEOUT_SECONDS", "90")), self._active_envelope.remaining_seconds)
        return await self._active_provider.execute("merchant-classification", context, timeout,
                                                    os.getenv("OLLAMA_API_KEY", ""))

    def _reply(self, status: int, body: dict):
        data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        # Never log request bodies, aggregate values, authorization headers, or model prompts.
        super().log_message(format, *args)


if __name__ == "__main__":
    host = os.getenv("FINANCE_AI_BIND", "0.0.0.0")
    port = int(os.getenv("FINANCE_AI_PORT", "8091"))
    ThreadingHTTPServer((host, port), IntelligenceHandler).serve_forever()
