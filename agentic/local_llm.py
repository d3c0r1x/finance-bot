from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


class LocalLLMError(RuntimeError):
    """Raised when the local OpenAI-compatible LLM endpoint cannot answer."""


@dataclass(frozen=True)
class LocalLLMConfig:
    base_url: str = os.getenv("FINPULSE_LOCAL_LLM_BASE_URL", "http://127.0.0.1:8080/v1")
    model: str = os.getenv("FINPULSE_LOCAL_LLM_MODEL", "bonsai-2")
    api_key: str = os.getenv("FINPULSE_LOCAL_LLM_API_KEY", "")
    timeout_seconds: float = float(os.getenv("FINPULSE_LOCAL_LLM_TIMEOUT", "120"))

    def normalized_base_url(self) -> str:
        return self.base_url.rstrip("/")


def chat(
    messages: list[dict[str, str]],
    *,
    config: LocalLLMConfig | None = None,
    temperature: float = 0.1,
    max_tokens: int = 2048,
) -> str:
    config = config or LocalLLMConfig()
    payload = {
        "model": config.model,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    data = _post_json(f"{config.normalized_base_url()}/chat/completions", payload, config)
    try:
        return str(data["choices"][0]["message"]["content"]).strip()
    except (KeyError, IndexError, TypeError) as exc:
        raise LocalLLMError(f"Unexpected local LLM response shape: {data!r}") from exc


def check_connection(config: LocalLLMConfig | None = None) -> dict[str, Any]:
    config = config or LocalLLMConfig()
    message = chat(
        [
            {"role": "system", "content": "Return exactly: ok"},
            {"role": "user", "content": "health"},
        ],
        config=config,
        temperature=0,
        max_tokens=8,
    )
    return {
        "ok": message.lower().strip().startswith("ok"),
        "base_url": config.normalized_base_url(),
        "model": config.model,
        "reply": message,
    }


def parse_json_or_summary(raw: str) -> dict[str, Any]:
    text = raw.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:].strip()
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return {
            "summary": raw.strip(),
            "findings": [],
            "recommended_actions": [],
            "raw": raw,
        }
    if not isinstance(parsed, dict):
        return {
            "summary": str(parsed),
            "findings": [],
            "recommended_actions": [],
            "raw": raw,
        }
    return parsed


def _post_json(url: str, payload: dict[str, Any], config: LocalLLMConfig) -> dict[str, Any]:
    headers = {"Content-Type": "application/json"}
    if config.api_key:
        headers["Authorization"] = f"Bearer {config.api_key}"
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=config.timeout_seconds) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise LocalLLMError(f"Local LLM HTTP {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise LocalLLMError(f"Local LLM unavailable at {url}: {exc.reason}") from exc
    except TimeoutError as exc:
        raise LocalLLMError(f"Local LLM timeout after {config.timeout_seconds}s") from exc
    try:
        return json.loads(body)
    except json.JSONDecodeError as exc:
        raise LocalLLMError(f"Local LLM returned non-JSON response: {body[:500]}") from exc

