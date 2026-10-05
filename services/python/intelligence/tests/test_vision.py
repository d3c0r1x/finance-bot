import asyncio
import base64
import json
from io import BytesIO

import pytest
from PIL import Image

from services.python.intelligence.gateway import UnsupportedCapability
from services.python.intelligence.vision import OllamaVisionProvider, OllamaVisionPoolProvider, parse_vision_models


def image_base64():
    buffer = BytesIO()
    Image.new("RGB", (20, 10), "white").save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def rotated_jpeg_base64():
    buffer = BytesIO()
    image = Image.new("RGB", (30, 20), "white")
    exif = Image.Exif()
    exif[274] = 6
    image.save(buffer, format="JPEG", exif=exif)
    return base64.b64encode(buffer.getvalue()).decode("ascii")


class StubResponse:
    status_code = 200

    def __init__(self, value):
        self.value = value

    def raise_for_status(self):
        return None

    def json(self):
        return self.value


class StubClient:
    def __init__(self, response, calls):
        self.response = response
        self.calls = calls

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def post(self, url, json=None, headers=None):
        self.calls.append({"url": url, "payload": json, "headers": headers})
        return self.response


class SequenceClient(StubClient):
    def __init__(self, responses, calls):
        self.responses = list(responses)
        self.calls = calls

    async def post(self, url, json=None, headers=None):
        self.calls.append({"url": url, "payload": json, "headers": headers})
        return self.responses.pop(0)


def vision_response(result, model="qwen3-vl:8b-instruct"):
    return StubResponse({"model": model, "message": {"content": json.dumps(result, ensure_ascii=False)}})


def good_reading():
    return {
        "store": "Магазин у дома",
        "date": "2026-10-01",
        "total": "100.00",
        "items": [{"name": "Хлеб", "quantity": "1", "unitPrice": "100.00", "lineSum": "100.00"}],
    }


def test_vision_adapter_sends_image_and_returns_validated_provenance():
    calls = []
    provider = OllamaVisionProvider(
        "http://ollama:11434", "qwen3-vl:8b-instruct", http_client_factory=lambda **_kwargs: StubClient(
            vision_response(good_reading()), calls))

    result = asyncio.run(provider.execute("receipt-vision", {"imageBase64": image_base64()}, timeout=5))

    assert provider.name == "ollama"
    assert provider.capabilities == frozenset({"text", "structured_output", "vision"})
    assert provider.supported_tasks == frozenset({"receipt-vision"})
    assert calls[0]["url"] == "http://ollama:11434/api/chat"
    assert calls[0]["payload"]["model"] == "qwen3-vl:8b-instruct"
    assert calls[0]["payload"]["format"] == "json"
    prepared = Image.open(BytesIO(base64.b64decode(calls[0]["payload"]["messages"][0]["images"][0])))
    assert prepared.size == (20, 10)
    assert prepared.getpixel((0, 0)) == (255, 255, 255)
    assert result == {
        **good_reading(),
        "modelVersion": "qwen3-vl:8b-instruct",
        "promptVersion": "receipt-vision.v1",
    }


def test_vision_keeps_unreadable_store_date_and_total_unknown():
    calls = []
    provider = OllamaVisionProvider(
        "http://ollama:11434", "vision-model", http_client_factory=lambda **_kwargs: StubClient(
            vision_response({"store": None, "date": None, "total": None, "items": []}), calls))

    result = asyncio.run(provider.execute("receipt-vision", {"imageBase64": image_base64()}, timeout=5))

    assert result["store"] is None
    assert result["date"] is None
    assert result["total"] is None
    assert result["items"] == []


def test_vision_prepares_oriented_image_and_strips_source_metadata():
    calls = []
    provider = OllamaVisionProvider(
        "http://ollama:11434", "qwen3-vl:8b-instruct", http_client_factory=lambda **_kwargs: StubClient(
            vision_response(good_reading()), calls))

    asyncio.run(provider.execute("receipt-vision", {"imageBase64": rotated_jpeg_base64()}, timeout=5))

    encoded = calls[0]["payload"]["messages"][0]["images"][0]
    prepared = Image.open(BytesIO(base64.b64decode(encoded)))
    assert prepared.format == "PNG"
    assert prepared.size == (20, 30)
    assert not prepared.getexif()


def test_vision_retries_one_invalid_structured_response_without_repeating_bad_output():
    calls = []
    client_factory = lambda **_kwargs: SequenceClient(
        [StubResponse({"model": "qwen3-vl:8b-instruct", "message": {"content": "not json"}}),
         vision_response(good_reading())], calls)
    provider = OllamaVisionProvider("http://ollama:11434", "qwen3-vl:8b-instruct",
                                    http_client_factory=client_factory)

    result = asyncio.run(provider.execute("receipt-vision", {"imageBase64": image_base64()}, timeout=5))

    assert len(calls) == 2
    assert calls[1]["payload"]["messages"][0]["content"] != calls[0]["payload"]["messages"][0]["content"]
    assert "schema" in calls[1]["payload"]["messages"][0]["content"].lower()
    assert "not json" not in calls[1]["payload"]["messages"][0]["content"]
    assert result["items"] == good_reading()["items"]


def test_vision_does_not_retry_invalid_output_more_than_once():
    calls = []
    client_factory = lambda **_kwargs: SequenceClient(
        [StubResponse({"model": "vision-model", "message": {"content": "not json"}})] * 2, calls)
    provider = OllamaVisionProvider("http://ollama:11434", "vision-model", http_client_factory=client_factory)

    with pytest.raises(ValueError):
        asyncio.run(provider.execute("receipt-vision", {"imageBase64": image_base64()}, timeout=5))

    assert len(calls) == 2


def test_vision_adapter_rejects_bad_json_schema_and_non_vision_tasks():
    calls = []
    provider = OllamaVisionProvider(
        "http://ollama:11434", "vision-model", http_client_factory=lambda **_kwargs: StubClient(
            vision_response({**good_reading(), "unexpected": "field"}), calls))

    with pytest.raises(ValueError):
        asyncio.run(provider.execute("receipt-vision", {"imageBase64": image_base64()}, timeout=5))
    with pytest.raises(UnsupportedCapability):
        asyncio.run(provider.execute("transaction-draft", {"text": "Lunch 100"}, timeout=5))


@pytest.mark.parametrize("bad_value", [0, -1, "1,25", "NaN", 9999999999999999999999999999])
def test_vision_adapter_rejects_unsafe_money_values(bad_value):
    calls = []
    result = good_reading()
    result["items"] = [{"name": "Хлеб", "quantity": "1", "unitPrice": "100.00", "lineSum": bad_value}]
    provider = OllamaVisionProvider(
        "http://ollama:11434", "vision-model", http_client_factory=lambda **_kwargs: StubClient(
            vision_response(result), calls))

    with pytest.raises(ValueError):
        asyncio.run(provider.execute("receipt-vision", {"imageBase64": image_base64()}, timeout=5))


def test_vision_model_pool_preserves_configured_order_fails_over_and_reports_actual_model():
    order = []

    class Model:
        name = "ollama"
        capabilities = frozenset({"text", "structured_output", "vision"})
        supported_tasks = frozenset({"receipt-vision"})

        def __init__(self, model, failure=None):
            self.model = model
            self.failure = failure

        async def execute(self, task_kind, context, timeout, api_key=""):
            order.append(self.model)
            if self.failure:
                raise self.failure
            return {**good_reading(), "modelVersion": self.model, "promptVersion": "receipt-vision.v1"}

    pool = OllamaVisionPoolProvider([Model("preferred-vlm", TimeoutError("out of memory")),
                                     Model("fallback-vlm")])
    result = asyncio.run(pool.execute("receipt-vision", {"imageBase64": image_base64()}, timeout=5))

    assert order == ["preferred-vlm", "fallback-vlm"]
    assert result["modelVersion"] == "fallback-vlm"
    assert result["fallbackReason"] == "vision_model_unavailable"


def test_vision_model_pool_is_bounded_and_preserves_configured_order():
    assert parse_vision_models("qwen3-vl:8b, qwen3-vl:4b, qwen3-vl:8b") == (
        "qwen3-vl:8b", "qwen3-vl:4b")
    with pytest.raises(ValueError):
        parse_vision_models("a,b,c,d")
    with pytest.raises(ValueError):
        parse_vision_models("qwen vl:8b")


def test_vision_model_pool_does_not_swallow_cancellation():
    class CancelledModel:
        name = "ollama"
        capabilities = frozenset({"text", "structured_output", "vision"})
        supported_tasks = frozenset({"receipt-vision"})

        async def execute(self, *_args, **_kwargs):
            raise asyncio.CancelledError()

    pool = OllamaVisionPoolProvider([CancelledModel(), CancelledModel()])
    with pytest.raises(asyncio.CancelledError):
        asyncio.run(pool.execute("receipt-vision", {"imageBase64": image_base64()}, timeout=5))
