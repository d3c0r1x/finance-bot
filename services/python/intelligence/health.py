"""Sanitized, bounded dependency checks for the private operator diagnostics API."""

from __future__ import annotations

import os
import shutil
import json

import httpx

from services.python.intelligence.gateway import authorize_provider, is_local_endpoint
from services.python.intelligence.vision import parse_vision_models

MAX_MODEL_LIST_BYTES = 128 * 1024
MAX_INSTALLED_MODELS = 512


async def _installed_ollama_models(timeout: float) -> set[str]:
    host = os.getenv("OLLAMA_HOST", "http://localhost:11434").strip()
    policy = os.getenv("FINANCE_AI_POLICY", "local-only").strip()
    endpoint = authorize_provider(host, policy).endpoint
    api_key = os.getenv("OLLAMA_API_KEY", "")
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else None
    async with httpx.AsyncClient(timeout=timeout, trust_env=not is_local_endpoint(endpoint)) as client:
        async with client.stream("GET", f"{endpoint}/api/tags", headers=headers) as response:
            response.raise_for_status()
            raw = bytearray()
            async for chunk in response.aiter_bytes():
                raw.extend(chunk)
                if len(raw) > MAX_MODEL_LIST_BYTES:
                    raise ValueError("model_list_too_large")
        body = json.loads(raw)
    models = body.get("models") if isinstance(body, dict) else None
    if not isinstance(models, list):
        raise ValueError("invalid_model_list")
    if len(models) > MAX_INSTALLED_MODELS:
        raise ValueError("too_many_models")
    names = set()
    for model in models:
        if not isinstance(model, dict) or not isinstance(model.get("name"), str) or len(model["name"]) > 128:
            raise ValueError("invalid_model_entry")
        names.add(model["name"])
    return names


def _installed_tesseract() -> bool:
    configured = os.getenv("TESSERACT_CMD", "").strip()
    return bool(shutil.which("tesseract") or (configured and os.path.isfile(configured) and os.access(configured, os.X_OK)))


def _capability(status: str, code: str | None = None) -> dict[str, str | None]:
    return {"status": status, "diagnosticCode": code}


async def collect_health(timeout: float = 1.5) -> dict:
    """Check configured feature dependencies without returning configuration or raw errors."""
    bounded_timeout = min(max(float(timeout), 0.05), 2.0)
    try:
        models = await _installed_ollama_models(bounded_timeout)
        ollama_error = False
    except Exception:
        models = set()
        ollama_error = True

    text_model = os.getenv("OLLAMA_MODEL", "qwen2.5:7b-instruct").strip()
    if ollama_error:
        local_ai = _capability("unavailable", "OLLAMA_UNAVAILABLE")
    elif text_model in models:
        local_ai = _capability("available")
    else:
        local_ai = _capability("unavailable", "MODEL_MISSING")

    try:
        vision_models = parse_vision_models(os.getenv("OLLAMA_VISION_MODELS", ""))
    except ValueError:
        vision_models = ()
        vision_invalid = True
    else:
        vision_invalid = False
    if vision_invalid:
        vision = _capability("unavailable", "VISION_CONFIG_INVALID")
    elif not vision_models:
        vision = _capability("disabled", "VISION_DISABLED")
    elif ollama_error:
        vision = _capability("unavailable", "OLLAMA_UNAVAILABLE")
    elif any(model in models for model in vision_models):
        vision = _capability("available")
    else:
        vision = _capability("unavailable", "MODEL_MISSING")

    ocr = _capability("available") if _installed_tesseract() else _capability("unavailable", "TESSERACT_MISSING")
    return {"capabilities": {"localAi": local_ai, "receiptVision": vision, "receiptOcr": ocr}}
