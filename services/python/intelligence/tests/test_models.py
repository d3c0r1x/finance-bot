import asyncio
import json
from contextlib import asynccontextmanager

import pytest

from services.python.intelligence import models
from services.python.intelligence.gateway import PolicyDenied


def test_resolver_prefers_configured_exact_model_then_ordered_installed_fallback():
    assert models.resolve_model("qwen3:4b", ["qwen3:8b"], ["qwen3:8b", "qwen3:4b"]) == "qwen3:4b"
    assert models.resolve_model("missing:instruct", ["qwen3:8b", "qwen3:4b"],
                                ["qwen3:4b", "qwen3:8b"]) == "qwen3:8b"


def test_resolver_does_not_silently_change_a_specific_tag_or_choose_embeddings():
    assert models.resolve_model("qwen2.5:7b-instruct", ["qwen2.5:7b-instruct", "qwen3:4b"],
                                ["qwen2.5:7b", "qwen3:4b"]) == "qwen3:4b"
    assert models.resolve_model("missing:instruct", ["embed-large", "nomic-embed-text"],
                                ["embed-large", "nomic-embed-text"]) == "missing:instruct"


def test_resolver_never_selects_configured_embedding_model():
    assert models.resolve_model("nomic-embed-text", ["qwen3:4b"],
                                ["nomic-embed-text", "qwen3:4b"]) == "qwen3:4b"


def test_default_preferences_are_valid_and_bounded():
    preferences = models.parse_model_preferences(None)
    assert "qwen2.5:1.5b-instruct-q4_K_M" in preferences
    assert len(preferences) <= models.MAX_MODEL_PREFERENCES


def test_inventory_is_bounded_cached_and_local_only(monkeypatch):
    requests = []

    class Response:
        def raise_for_status(self):
            return None

        async def aiter_bytes(self):
            yield json.dumps({"models": [{"name": "qwen3:8b"}, {"name": "embed-small"}]}).encode()

    class Client:
        def __init__(self, **kwargs):
            requests.append(kwargs)

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

        @asynccontextmanager
        async def stream(self, method, url):
            requests.append((method, url))
            yield Response()

    monkeypatch.setattr(models.httpx, "AsyncClient", Client)
    monkeypatch.setattr(models, "_inventory_cache", {})

    async def read_twice():
        first = await models.list_installed_models("http://127.0.0.1:11434")
        second = await models.list_installed_models("http://127.0.0.1:11434")
        return first, second

    first, second = asyncio.run(read_twice())
    assert first == ["qwen3:8b", "embed-small"]
    assert second == first
    assert len([call for call in requests if isinstance(call, tuple)]) == 1
    assert requests[0]["timeout"] <= 2
    with pytest.raises(PolicyDenied):
        asyncio.run(models.list_installed_models("https://ollama.com"))


def test_inventory_rejects_oversized_response_before_parsing(monkeypatch):
    class Response:
        def raise_for_status(self):
            return None

        async def aiter_bytes(self):
            yield b"x" * (models.MAX_MODEL_LIST_BYTES + 1)

    class Client:
        def __init__(self, **_kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

        @asynccontextmanager
        async def stream(self, _method, _url):
            yield Response()

    monkeypatch.setattr(models.httpx, "AsyncClient", Client)
    monkeypatch.setattr(models, "_inventory_cache", {})
    with pytest.raises(ValueError, match="model_list_too_large"):
        asyncio.run(models.list_installed_models("http://localhost:11434", force_refresh=True))


def test_inventory_short_negative_cache_bounds_repeated_ollama_outages(monkeypatch):
    requests = []

    class Client:
        def __init__(self, **_kwargs):
            requests.append("connect")

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

        @asynccontextmanager
        async def stream(self, *_args, **_kwargs):
            raise models.httpx.ConnectError("offline")
            yield  # pragma: no cover

    monkeypatch.setattr(models.httpx, "AsyncClient", Client)
    monkeypatch.setattr(models, "_inventory_cache", {})

    async def read_twice():
        return (await models.list_installed_models("http://localhost:11434"),
                await models.list_installed_models("http://localhost:11434"))

    assert asyncio.run(read_twice()) == ([], [])
    assert requests == ["connect"]
