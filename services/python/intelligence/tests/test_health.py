import asyncio
import json
from contextlib import asynccontextmanager

from services.python.intelligence import health


class FakeResponse:
    def __init__(self, names):
        self.names = names

    def raise_for_status(self):
        return None

    def json(self):
        return {"models": [{"name": name} for name in self.names]}

    async def aiter_bytes(self):
        yield json.dumps(self.json()).encode()


class FakeClient:
    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    @asynccontextmanager
    async def stream(self, _method, _url, headers=None):
        yield FakeResponse(["qwen-text", "qwen-vl:latest"])


def test_collect_health_checks_configured_capabilities_without_exposing_configuration(monkeypatch):
    monkeypatch.setenv("OLLAMA_HOST", "http://localhost:11434")
    monkeypatch.setenv("FINANCE_AI_POLICY", "cloud-opt-in")
    monkeypatch.setenv("OLLAMA_MODEL", "qwen-text")
    monkeypatch.setenv("OLLAMA_VISION_MODELS", "qwen-vl:latest")
    monkeypatch.setenv("TESSERACT_CMD", "C:/private/tools/tesseract.exe")
    monkeypatch.setattr(health.httpx, "AsyncClient", FakeClient)
    monkeypatch.setattr(health.shutil, "which", lambda command: "C:/tools/tesseract.exe" if command == "tesseract" else None)

    result = asyncio.run(health.collect_health())

    assert result == {
        "capabilities": {
            "localAi": {"status": "available", "diagnosticCode": None},
            "receiptVision": {"status": "available", "diagnosticCode": None},
            "receiptOcr": {"status": "available", "diagnosticCode": None},
        }
    }
    assert "private-host" not in str(result)
    assert "qwen" not in str(result)
    assert "C:/" not in str(result)


def test_collect_health_reports_disabled_and_missing_dependencies_safely(monkeypatch):
    monkeypatch.setenv("OLLAMA_VISION_MODELS", "")
    monkeypatch.setenv("TESSERACT_CMD", "C:/private/tesseract.exe")
    monkeypatch.setattr(health, "_installed_ollama_models", unavailable_models)
    monkeypatch.setattr(health.shutil, "which", lambda _command: None)

    result = asyncio.run(health.collect_health())

    assert result["capabilities"] == {
        "localAi": {"status": "unavailable", "diagnosticCode": "OLLAMA_UNAVAILABLE"},
        "receiptVision": {"status": "disabled", "diagnosticCode": "VISION_DISABLED"},
        "receiptOcr": {"status": "unavailable", "diagnosticCode": "TESSERACT_MISSING"},
    }
    assert "C:/private" not in str(result)
    assert "secret" not in str(result)


def test_collect_health_accepts_installed_preference_fallback(monkeypatch):
    monkeypatch.setenv("OLLAMA_HOST", "http://localhost:11434")
    monkeypatch.setenv("FINANCE_AI_POLICY", "local-only")
    monkeypatch.setenv("OLLAMA_MODEL", "configured:missing")
    monkeypatch.setenv("OLLAMA_MODEL_PREFERENCE", "preferred:8b,preferred:4b")
    monkeypatch.setenv("OLLAMA_VISION_MODELS", "")

    async def installed(_timeout):
        return {"preferred:4b", "preferred:8b", "embed-small"}

    monkeypatch.setattr(health, "_installed_ollama_models", installed)
    result = asyncio.run(health.collect_health())

    assert result["capabilities"]["localAi"] == {"status": "available", "diagnosticCode": None}


def test_collect_health_reports_invalid_model_preferences_safely(monkeypatch):
    monkeypatch.setenv("OLLAMA_HOST", "http://localhost:11434")
    monkeypatch.setenv("FINANCE_AI_POLICY", "local-only")
    monkeypatch.setenv("OLLAMA_MODEL", "configured:latest")
    monkeypatch.setenv("OLLAMA_MODEL_PREFERENCE", "bad model name")
    monkeypatch.setattr(health, "_installed_ollama_models", lambda _timeout: installed_names())

    result = asyncio.run(health.collect_health())

    assert result["capabilities"]["localAi"] == {
        "status": "unavailable", "diagnosticCode": "MODEL_CONFIG_INVALID"}


async def installed_names():
    return {"configured:latest"}


async def unavailable_models(_timeout):
    raise RuntimeError("secret token and private-host:11434 must never escape")


def test_oversized_model_inventory_is_treated_as_unavailable(monkeypatch):
    class OversizedResponse(FakeResponse):
        async def aiter_bytes(self):
            yield b"x" * (128 * 1024 + 1)

    class OversizedClient(FakeClient):
        @asynccontextmanager
        async def stream(self, _method, _url, headers=None):
            yield OversizedResponse(["qwen-text", "qwen-vl:latest"])

    monkeypatch.setenv("OLLAMA_MODEL", "qwen-text")
    monkeypatch.setenv("OLLAMA_VISION_MODELS", "qwen-vl:latest")
    monkeypatch.setattr(health.httpx, "AsyncClient", OversizedClient)

    result = asyncio.run(health.collect_health())

    assert result["capabilities"]["localAi"] == {"status": "unavailable", "diagnosticCode": "OLLAMA_UNAVAILABLE"}
    assert result["capabilities"]["receiptVision"] == {"status": "unavailable", "diagnosticCode": "OLLAMA_UNAVAILABLE"}


def test_cloud_health_does_not_enumerate_remote_models(monkeypatch):
    class ForbiddenClient:
        def __init__(self, *_args, **_kwargs):
            raise AssertionError("Remote model inventory must not be enumerated")

    monkeypatch.setenv("OLLAMA_HOST", "https://ollama.example")
    monkeypatch.setenv("FINANCE_AI_POLICY", "cloud-opt-in")
    monkeypatch.setenv("OLLAMA_MODEL", "cloud-model:latest")
    monkeypatch.setenv("OLLAMA_VISION_MODELS", "cloud-vision:latest")
    monkeypatch.setattr(health.httpx, "AsyncClient", ForbiddenClient)
    monkeypatch.setattr(health.shutil, "which", lambda _command: None)

    result = asyncio.run(health.collect_health())

    assert result["capabilities"] == {
        "localAi": {"status": "disabled", "diagnosticCode": "REMOTE_MODEL_STATUS_UNCHECKED"},
        "receiptVision": {"status": "disabled", "diagnosticCode": "REMOTE_MODEL_STATUS_UNCHECKED"},
        "receiptOcr": {"status": "unavailable", "diagnosticCode": "TESSERACT_MISSING"},
    }
