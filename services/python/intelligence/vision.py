"""Capability-scoped Ollama vision adapter for receipt reading drafts."""

from __future__ import annotations

import asyncio
import json
import re
import base64
from io import BytesIO
from dataclasses import dataclass
from datetime import date

import httpx
from PIL import Image

from services.python.intelligence.gateway import UnsupportedCapability, is_local_endpoint
from services.python.intelligence.tesseract import TesseractProvider


MONEY_RE = re.compile(r"^\d{1,18}(?:\.\d{1,2})?$")
QUANTITY_RE = re.compile(r"^\d{1,12}(?:\.\d{1,6})?$")
VISION_PROMPT = """Read this receipt image. Return only JSON with exactly these fields:
{"store": string|null, "date": "YYYY-MM-DD"|null, "total": decimal-string|null,
 "items": [{"name": string, "quantity": decimal-string|null,
 "unitPrice": decimal-string|null, "lineSum": decimal-string|null}]}
Copy values visible in the image. Do not calculate, round, infer, or invent a missing value.
Use null when a field cannot be read. Exclude headers, tax, discount, payment, and total rows from items.
Keep item order from top to bottom. Return at most 80 items."""
VISION_RETRY_PROMPT = VISION_PROMPT + """

Your previous response did not match the required JSON schema. Read the image again.
Return one valid JSON object with exactly the required fields, no markdown or extra text.
Keep unreadable values null; never infer or invent them."""
MAX_VISION_SIDE = 3000
MAX_VISION_MODELS = 3
VISION_MODEL_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")


def parse_vision_models(value: str | None, maximum: int = MAX_VISION_MODELS) -> tuple[str, ...]:
    """Read an ordered, small model pool from the server allowlist."""
    if value is None or not value.strip():
        return ()
    models: list[str] = []
    for item in value.split(","):
        model = item.strip()
        if not model or not VISION_MODEL_NAME.fullmatch(model):
            raise ValueError("Vision model allowlist is invalid")
        if model not in models:
            models.append(model)
    if len(models) > maximum:
        raise ValueError("Vision model pool exceeds its configured bound")
    return tuple(models)


@dataclass
class OllamaVisionProvider:
    endpoint: str
    model: str
    http_client_factory: object = httpx.AsyncClient

    @property
    def name(self) -> str:
        return "ollama"

    @property
    def capabilities(self) -> frozenset[str]:
        return frozenset({"text", "structured_output", "vision"})

    @property
    def supported_tasks(self) -> frozenset[str]:
        return frozenset({"receipt-vision"})

    async def execute(self, task_kind: str, context: dict, timeout: float, api_key: str = "") -> dict:
        if task_kind != "receipt-vision":
            raise UnsupportedCapability(f"Ollama vision adapter does not implement task: {task_kind}")
        image = self._prepare_image(context)
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else None
        async with self.http_client_factory(timeout=timeout, trust_env=not is_local_endpoint(self.endpoint)) as client:
            for attempt in range(2):
                response = await client.post(
                    f"{self.endpoint.rstrip('/')}/api/chat",
                    headers=headers,
                    json={
                        "model": self.model,
                        "messages": [{"role": "user",
                                      "content": VISION_PROMPT if attempt == 0 else VISION_RETRY_PROMPT,
                                      "images": [image]}],
                        "format": "json",
                        "stream": False,
                        "options": {"temperature": 0},
                    },
                )
                response.raise_for_status()
                try:
                    envelope = response.json()
                    content = envelope["message"]["content"]
                    parsed = json.loads(content)
                    normalized = self._validate(parsed)
                except (KeyError, TypeError, json.JSONDecodeError, ValueError) as error:
                    if attempt == 0:
                        continue
                    raise ValueError("Ollama vision output is invalid JSON or schema") from error
        version = envelope.get("model") or self.model
        return {**normalized, "modelVersion": str(version)[:128],
                "promptVersion": "receipt-vision.v1"}

    @staticmethod
    def _prepare_image(context: dict) -> str:
        image = TesseractProvider._decode_image(context)
        if max(image.size) > MAX_VISION_SIDE:
            image.thumbnail((MAX_VISION_SIDE, MAX_VISION_SIDE), Image.Resampling.LANCZOS)
        output = BytesIO()
        image.save(output, format="PNG", optimize=True)
        return base64.b64encode(output.getvalue()).decode("ascii")

    @staticmethod
    def _validate(result: object) -> dict:
        if not isinstance(result, dict) or set(result) != {"store", "date", "total", "items"}:
            raise ValueError("Ollama vision output schema is invalid")
        store = result["store"]
        if store is not None and (not isinstance(store, str) or len(store.strip()) > 100):
            raise ValueError("Ollama vision store is invalid")
        receipt_date = result["date"]
        if receipt_date is not None:
            try:
                if not isinstance(receipt_date, str) or date.fromisoformat(receipt_date).isoformat() != receipt_date:
                    raise ValueError("Ollama vision date is invalid")
            except ValueError as error:
                raise ValueError("Ollama vision date is invalid") from error
        total = OllamaVisionProvider._money(result["total"], optional=True)
        items = result["items"]
        if not isinstance(items, list) or len(items) > 80:
            raise ValueError("Ollama vision items are invalid")
        normalized_items = []
        for item in items:
            if not isinstance(item, dict) or set(item) != {"name", "quantity", "unitPrice", "lineSum"}:
                raise ValueError("Ollama vision item schema is invalid")
            name = item["name"]
            if not isinstance(name, str) or not 1 <= len(name.strip()) <= 200:
                raise ValueError("Ollama vision item name is invalid")
            quantity = OllamaVisionProvider._quantity(item["quantity"])
            unit_price = OllamaVisionProvider._money(item["unitPrice"], optional=True)
            line_sum = OllamaVisionProvider._money(item["lineSum"], optional=True)
            normalized_items.append({"name": name.strip(), "quantity": quantity,
                                     "unitPrice": unit_price, "lineSum": line_sum})
        return {"store": store.strip() if store else None, "date": receipt_date,
                "total": total, "items": normalized_items}

    @staticmethod
    def _money(value: object, optional: bool) -> str | None:
        if optional and value is None:
            return None
        if not isinstance(value, str) or not MONEY_RE.fullmatch(value) or float(value) <= 0:
            raise ValueError("Ollama vision money value is invalid")
        return value

    @staticmethod
    def _quantity(value: object) -> str | None:
        if value is None:
            return None
        if not isinstance(value, str) or not QUANTITY_RE.fullmatch(value) or float(value) <= 0:
            raise ValueError("Ollama vision quantity is invalid")
        return value


class OllamaVisionPoolProvider:
    """Try explicitly allowlisted models in order; preserve the model that answered."""

    name = "ollama"
    capabilities = frozenset({"text", "structured_output", "vision"})
    supported_tasks = frozenset({"receipt-vision"})

    def __init__(self, providers):
        self.providers = tuple(providers)
        if not 1 <= len(self.providers) <= MAX_VISION_MODELS:
            raise ValueError("Vision model pool size is invalid")

    async def execute(self, task_kind: str, context: dict, timeout: float, api_key: str = "") -> dict:
        if task_kind != "receipt-vision":
            raise UnsupportedCapability(f"Vision pool does not implement task: {task_kind}")
        last_error = None
        for index, provider in enumerate(self.providers):
            try:
                result = await provider.execute(task_kind, context, timeout, api_key)
                if index:
                    return {**result, "fallbackReason": "vision_model_unavailable"}
                return result
            except asyncio.CancelledError:
                raise
            except (httpx.HTTPError, TimeoutError, ValueError) as error:
                last_error = error
        if last_error is not None:
            raise last_error
        raise UnsupportedCapability("No allowlisted Vision model is available")
