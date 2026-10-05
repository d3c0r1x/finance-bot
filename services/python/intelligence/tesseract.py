"""Bounded local Tesseract OCR adapter; it does not emulate model capabilities."""

from __future__ import annotations

import asyncio
import base64
import binascii
import io
import math
import os
import threading
from dataclasses import dataclass

from PIL import Image, ImageOps, UnidentifiedImageError

from services.python.intelligence.gateway import UnsupportedCapability
from services.python.intelligence import receipt_ocr


MAX_IMAGE_BYTES = 12 * 1024 * 1024
MAX_IMAGE_PIXELS = 20_000_000
MAX_WORDS = 4_000
MAX_TOKEN_CHARS = 256
TESSERACT_SLOTS = threading.BoundedSemaphore(2)
TESSERACT_VERSION_LOCK = threading.Lock()


class TesseractBusy(RuntimeError):
    pass


class TesseractUnavailable(RuntimeError):
    pass


@dataclass
class TesseractProvider:
    ocr_engine: object | None = None
    cell_ocr_engine: object | None = None
    version: str | None = None
    language: str = "rus+eng"
    timeout_seconds: float = 30

    def __post_init__(self):
        if self.timeout_seconds <= 0:
            raise ValueError("Tesseract timeout must be positive")

    @property
    def name(self) -> str:
        return "tesseract"

    @property
    def supported_tasks(self) -> frozenset[str]:
        return frozenset({"receipt-ocr"})

    @property
    def capabilities(self) -> frozenset[str]:
        return frozenset({"text"})

    async def execute(self, task_kind: str, context: dict, timeout: float, api_key: str = "") -> dict:
        if task_kind != "receipt-ocr":
            raise UnsupportedCapability(f"Tesseract adapter does not implement task: {task_kind}")
        image = context.get("_image") if isinstance(context, dict) else None
        if not isinstance(image, Image.Image):
            image = self._decode_image(context)
        bounded_timeout = min(float(timeout), self.timeout_seconds)
        if bounded_timeout <= 0:
            raise TimeoutError("Tesseract deadline has expired")
        result = await asyncio.to_thread(self._recognize_bounded, image, bounded_timeout)
        return self._validate_result(result)

    @staticmethod
    def _decode_image(context: dict) -> Image.Image:
        if not isinstance(context, dict):
            raise ValueError("Receipt OCR context must be an object")
        encoded = context.get("imageBase64")
        if not isinstance(encoded, str) or not encoded or len(encoded) > ((MAX_IMAGE_BYTES + 2) // 3) * 4:
            raise ValueError("Receipt image is missing or too large")
        try:
            raw = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as error:
            raise ValueError("Receipt image encoding is invalid") from error
        if not raw or len(raw) > MAX_IMAGE_BYTES:
            raise ValueError("Receipt image is missing or too large")
        try:
            image = Image.open(io.BytesIO(raw))
            if image.format not in {"PNG", "JPEG", "WEBP"}:
                raise ValueError("Receipt image format is unsupported")
            width, height = image.size
            if width <= 0 or height <= 0 or width * height > MAX_IMAGE_PIXELS:
                raise ValueError("Receipt image dimensions exceed limit")
            image.load()
            return ImageOps.exif_transpose(image).convert("RGB")
        except (UnidentifiedImageError, Image.DecompressionBombError, OSError) as error:
            raise ValueError("Receipt image is invalid") from error

    @classmethod
    def prepare_context(cls, context: dict) -> dict:
        return {"_image": cls._decode_image(context)}

    def _recognize_bounded(self, image: Image.Image, timeout: float) -> dict:
        if not TESSERACT_SLOTS.acquire(blocking=False):
            raise TesseractBusy("Tesseract worker pool is full")
        try:
            return self._recognize(image, timeout)
        finally:
            TESSERACT_SLOTS.release()

    def _recognize(self, image: Image.Image, timeout: float) -> dict:
        variants = receipt_ocr.preprocess_variants(image)
        errors = []

        def read_variant(variant):
            try:
                return self._recognize_once(variant.image, timeout), None
            except Exception as error:
                return None, error

        readings = receipt_ocr.read_variants_in_stable_order(variants, read_variant, max_workers=2)
        candidates = []
        for index, (variant, (raw, error)) in enumerate(zip(variants, readings)):
            if error is not None:
                errors.append(error)
                continue
            try:
                words = self._words_from_raw(raw)
            except ValueError as invalid_output:
                errors.append(invalid_output)
                continue
            rows = [receipt_ocr.repair_split_amounts(row) for row in receipt_ocr.group_rows(words)]
            repaired_words = [word for row in rows for word in row]
            columns = receipt_ocr.infer_numeric_columns(rows, variant.image.width)
            confidence = sum(word["confidence"] for word in repaired_words) \
                / max(1, len(repaired_words))
            amount_count = sum(1 for word in repaired_words
                               if receipt_ocr.is_money_token(word["text"]))
            score = (len(repaired_words) * 0.15 + confidence * 0.01 + len(rows) * 0.2
                     + amount_count * 0.75 + (0.5 if columns["sum"] else 0.0)
                     + (0.35 if columns["price"] else 0.0))
            candidates.append((score, -index, variant, raw, repaired_words, rows, columns))
        if not candidates:
            self._raise_read_error(errors)
        _, _, selected, source, words, rows, columns = max(candidates, key=lambda item: (item[0], item[1]))
        words.extend(self._reread_missing_amounts(selected.image, rows, columns, timeout))
        words = receipt_ocr.map_words_to_source(words, selected)
        source_rows = [receipt_ocr.repair_split_amounts(row) for row in receipt_ocr.group_rows(words)]
        source_width = selected.source_size[0] if selected.source_size else selected.image.width
        source_columns = receipt_ocr.infer_numeric_columns(source_rows, source_width)
        structured = receipt_ocr.extract_structured_reading(source_rows, source_columns)
        return {**self._raw_from_words(words, self._result_version(source)), **structured}

    def _recognize_once(self, image: Image.Image, timeout: float) -> dict:
        try:
            if self.ocr_engine is not None:
                return self.ocr_engine(image, self.language, timeout)
            import pytesseract

            command = os.getenv("TESSERACT_CMD", "").strip()
            if command:
                pytesseract.pytesseract.tesseract_cmd = command
            output = pytesseract.image_to_data(image, lang=self.language,
                                               output_type=pytesseract.Output.DICT, timeout=timeout)
            return {"_version": self.version, **output} if self.version else output
        except Exception as error:
            self._raise_read_error([error])

    @staticmethod
    def _raise_read_error(errors: list[Exception]):
        if any(isinstance(error, TimeoutError) or error.__class__.__name__ == "TimeoutExpired"
               for error in errors):
            raise TimeoutError("Tesseract OCR timed out") from next(
                error for error in errors if isinstance(error, TimeoutError)
                or error.__class__.__name__ == "TimeoutExpired")
        if any(error.__class__.__name__ in {"TesseractNotFoundError", "TesseractError"}
               or isinstance(error, TesseractUnavailable) for error in errors):
            raise TesseractUnavailable("Tesseract binary or language data is unavailable") from errors[0]
        raise ValueError("Tesseract OCR failed") from (errors[0] if errors else None)

    @staticmethod
    def _words_from_raw(raw: dict) -> list[dict]:
        if not isinstance(raw, dict):
            raise ValueError("Tesseract output is invalid")
        required = ("text", "conf", "left", "top", "width", "height")
        if any(not isinstance(raw.get(key), (list, tuple)) for key in required):
            raise ValueError("Tesseract output is invalid")
        size = len(raw["text"])
        if size > MAX_WORDS or any(len(raw[key]) != size for key in required):
            raise ValueError("Tesseract output exceeds limits")
        words = []
        for index, value in enumerate(raw["text"]):
            token = str(value).strip()
            if not token:
                continue
            if len(token) > MAX_TOKEN_CHARS:
                raise ValueError("Tesseract token exceeds limit")
            try:
                confidence = float(raw["conf"][index])
                box = {name: int(raw[field][index]) for name, field in (
                    ("x", "left"), ("y", "top"), ("width", "width"), ("height", "height"))}
            except (TypeError, ValueError, OverflowError) as error:
                raise ValueError("Tesseract word metadata is invalid") from error
            if confidence < 0:
                continue
            if confidence > 100 or any(value < 0 for value in box.values()):
                raise ValueError("Tesseract word metadata is outside limits")
            words.append({"text": token, "confidence": confidence, "box": box})
        return words

    @staticmethod
    def _raw_from_words(words: list[dict], version: str = "unknown") -> dict:
        return {
            "text": [word["text"] for word in words],
            "conf": [word["confidence"] for word in words],
            "left": [word["box"]["x"] for word in words],
            "top": [word["box"]["y"] for word in words],
            "width": [word["box"]["width"] for word in words],
            "height": [word["box"]["height"] for word in words],
            "_version": version,
        }

    def _reread_missing_amounts(self, image: Image.Image, rows: list[list[dict]], columns: dict,
                                timeout: float) -> list[dict]:
        recovered = []
        config = "--oem 3 --psm 7 -c tessedit_char_whitelist=0123456789.,"
        for left, top, right, bottom in receipt_ocr.missing_amount_cells(rows, columns, image.size)[:8]:
            crop = image.crop((left, top, right, bottom))
            if crop.width < 6 or crop.height < 5:
                continue
            scale = min(4, max(1, 1600 // max(crop.size)))
            crop = crop.resize((crop.width * scale, crop.height * scale), Image.Resampling.LANCZOS)
            crop = ImageOps.autocontrast(crop.convert("L")).point(lambda value: 255 if value > 155 else 0)
            try:
                if self.cell_ocr_engine is not None:
                    raw = self.cell_ocr_engine(crop, "eng", timeout, config)
                else:
                    raw = self._read_cell_with_tesseract(crop, timeout, config)
                cell_words = self._words_from_raw(raw)
            except (TimeoutError, TesseractUnavailable):
                raise
            except (ValueError, OSError):
                continue
            found = None
            for row in receipt_ocr.group_rows(cell_words):
                for word in receipt_ocr.repair_split_amounts(row):
                    if receipt_ocr.is_money_token(word["text"]):
                        box = word["box"]
                        found = {"text": word["text"], "confidence": word["confidence"],
                                 "box": {"x": min(right - 1, left + math.floor(box["x"] / scale)),
                                         "y": min(bottom - 1, top + math.floor(box["y"] / scale)),
                                         "width": max(1, math.ceil(box["width"] / scale)),
                                         "height": max(1, math.ceil(box["height"] / scale))}}
                        break
                if found:
                    break
            if found:
                recovered.append(found)
        return recovered

    def _read_cell_with_tesseract(self, image: Image.Image, timeout: float, config: str) -> dict:
        try:
            import pytesseract

            command = os.getenv("TESSERACT_CMD", "").strip()
            if command:
                pytesseract.pytesseract.tesseract_cmd = command
            output = pytesseract.image_to_data(image, lang="eng", config=config,
                                               output_type=pytesseract.Output.DICT, timeout=timeout)
            return output
        except Exception as error:
            self._raise_read_error([error])

    def _result_version(self, raw: dict) -> str:
        version = raw.get("_version") or self.version
        if version and version != "unknown":
            return str(version)
        if self.ocr_engine is not None:
            return "unknown"
        with TESSERACT_VERSION_LOCK:
            if self.version:
                return self.version
            try:
                import pytesseract

                self.version = str(pytesseract.get_tesseract_version())[:64]
            except Exception:
                self.version = "unknown"
            return self.version

    def _validate_result(self, raw: dict) -> dict:
        if not isinstance(raw, dict):
            raise ValueError("Tesseract output is invalid")
        words = self._words_from_raw(raw)
        text = " ".join(word["text"] for word in words)
        if len(text) > MAX_WORDS * MAX_TOKEN_CHARS:
            raise ValueError("Tesseract text exceeds limits")
        version = self._result_version(raw) if raw.get("_version") else self.version or "unknown"
        return {
            "text": text,
            "words": words,
            "modelVersion": f"tesseract-{str(version)[:64]}",
            "promptVersion": "tesseract-ocr.v2",
            "total": raw.get("total"),
            "items": raw.get("items", []),
        }
