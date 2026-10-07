"""Bounded local Ollama model inventory and deterministic model selection."""

from __future__ import annotations

import json
import re
import threading
import time

import httpx

from services.python.intelligence.gateway import authorize_provider, is_local_endpoint


MAX_MODEL_LIST_BYTES = 128 * 1024
MAX_INSTALLED_MODELS = 512
MAX_MODEL_NAME_LENGTH = 128
MAX_MODEL_PREFERENCES = 16
MODEL_INVENTORY_TTL_SECONDS = 30
MODEL_INVENTORY_DOWN_TTL_SECONDS = 5
DEFAULT_MODEL_PREFERENCE = (
    "qwen3:8b", "qwen2.5:7b-instruct", "qwen2.5:7b", "qwen3:4b", "gemma3:4b",
    "qwen2.5:3b-instruct", "llama3.2:3b", "qwen2.5:1.5b-instruct-q4_K_M",
)
_MODEL_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
_inventory_cache: dict[str, tuple[float, tuple[str, ...]]] = {}
_cache_lock = threading.Lock()


def parse_model_preferences(value: str | None) -> tuple[str, ...]:
    if value is None or not value.strip():
        return DEFAULT_MODEL_PREFERENCE
    names = []
    for part in value.split(","):
        name = part.strip()
        if not name or len(name) > MAX_MODEL_NAME_LENGTH or not _MODEL_NAME.fullmatch(name):
            raise ValueError("Local model preference is invalid")
        if name not in names:
            names.append(name)
    if len(names) > MAX_MODEL_PREFERENCES:
        raise ValueError("Local model preference list is too long")
    return tuple(names)


def _matching_model(candidate: str, installed: list[str]) -> str | None:
    if candidate in installed:
        return candidate
    base, separator, tag = candidate.partition(":")
    if not separator or tag == "latest":
        same_base = [name for name in installed if name.partition(":")[0] == base]
        if len(same_base) == 1:
            return same_base[0]
    return None


def resolve_model(configured: str, preferences: tuple[str, ...] | list[str], installed: list[str]) -> str:
    """Use configured exact model first, then only exact installed preferences, then a non-embedding model."""
    if not isinstance(configured, str) or not configured.strip() or not _MODEL_NAME.fullmatch(configured.strip()):
        raise ValueError("Configured local model is invalid")
    configured = configured.strip()
    names = [name for name in installed if isinstance(name, str) and _MODEL_NAME.fullmatch(name)]
    selected = _matching_model(configured, names)
    if selected and "embed" not in selected.casefold():
        return selected
    for preference in preferences:
        selected = _matching_model(preference, names)
        if selected and "embed" not in selected.casefold():
            return selected
    for name in names:
        if "embed" not in name.casefold():
            return name
    return configured


async def list_installed_models(endpoint: str, *, timeout: float = 1.5, force_refresh: bool = False) -> list[str]:
    """Read only a bounded local Ollama inventory; remote model enumeration is forbidden."""
    validated = authorize_provider(endpoint, "local-only").endpoint
    if not is_local_endpoint(validated):
        raise ValueError("Remote model inventory is not supported")
    now = time.monotonic()
    with _cache_lock:
        cached = _inventory_cache.get(validated)
        cache_ttl = MODEL_INVENTORY_DOWN_TTL_SECONDS if cached and not cached[1] else MODEL_INVENTORY_TTL_SECONDS
        if cached and not force_refresh and now - cached[0] < cache_ttl:
            return list(cached[1])

    bounded_timeout = min(max(float(timeout), 0.05), 2.0)
    try:
        async with httpx.AsyncClient(timeout=bounded_timeout, trust_env=False) as client:
            async with client.stream("GET", f"{validated}/api/tags") as response:
                response.raise_for_status()
                body_bytes = bytearray()
                async for chunk in response.aiter_bytes():
                    body_bytes.extend(chunk)
                    if len(body_bytes) > MAX_MODEL_LIST_BYTES:
                        raise ValueError("model_list_too_large")
    except httpx.HTTPError:
        # Ollama being down must be cheap and repeatable, without hiding malformed metadata.
        with _cache_lock:
            _inventory_cache[validated] = (time.monotonic(), ())
        return []
    body = json.loads(body_bytes)
    models = body.get("models") if isinstance(body, dict) else None
    if not isinstance(models, list) or len(models) > MAX_INSTALLED_MODELS:
        raise ValueError("model_list_invalid")
    names = []
    for item in models:
        name = item.get("name") if isinstance(item, dict) else None
        if not isinstance(name, str) or len(name) > MAX_MODEL_NAME_LENGTH or not _MODEL_NAME.fullmatch(name):
            raise ValueError("model_list_invalid")
        if name not in names:
            names.append(name)

    with _cache_lock:
        _inventory_cache[validated] = (time.monotonic(), tuple(names))
    return names
