import asyncio
import base64
import os
import shutil
from io import BytesIO
from pathlib import Path

import pytest
from PIL import Image, ImageDraw, ImageFont

from services.python.intelligence.gateway import UnsupportedCapability
from services.python.intelligence.tesseract import TesseractProvider
from services.python.intelligence.gateway import ProviderRouter
from services.python.intelligence import receipt_ocr
from services.python.intelligence.receipt_ocr import OcrVariant


def image_context():
    buffer = BytesIO()
    Image.new("RGB", (40, 20), "white").save(buffer, format="PNG")
    return {"imageBase64": base64.b64encode(buffer.getvalue()).decode("ascii")}


def test_tesseract_returns_bounded_text_and_word_coordinates_without_llm_capabilities():
    observed = []

    def recognize(image, language, timeout):
        observed.append((language, timeout, image.size))
        return {
            "text": ["Такси", "2 000"],
            "conf": ["92", "87"],
            "left": [2, 20], "top": [3, 5], "width": [10, 15], "height": [6, 7],
        }

    provider = TesseractProvider(ocr_engine=recognize, version="5.3.0")

    result = asyncio.run(provider.execute("receipt-ocr", image_context(), timeout=5))

    assert provider.name == "tesseract"
    assert provider.capabilities == frozenset({"text"})
    assert ProviderRouter([provider]).select({"text"}, "local-only") is provider
    with pytest.raises(UnsupportedCapability):
        ProviderRouter([provider]).select({"structured_output"}, "local-only")
    assert observed
    assert {language for language, _, _ in observed} == {"rus+eng"}
    assert {timeout for _, timeout, _ in observed} == {5.0}
    assert {(40, 20), (100, 50)} <= {size for _, _, size in observed}
    assert result == {
        "text": "Такси 2 000",
        "words": [
            {"text": "Такси", "confidence": 92.0, "box": {"x": 2, "y": 3, "width": 10, "height": 6}},
            {"text": "2 000", "confidence": 87.0, "box": {"x": 20, "y": 5, "width": 15, "height": 7}},
        ],
        "modelVersion": "tesseract-5.3.0",
        "promptVersion": "tesseract-ocr.v2",
        "total": None,
        "items": [],
    }


def test_tesseract_rejects_non_ocr_tasks_oversized_images_and_bad_base64():
    provider = TesseractProvider(ocr_engine=lambda *_args, **_kwargs: {})

    with pytest.raises(UnsupportedCapability):
        asyncio.run(provider.execute("transaction-draft", image_context(), timeout=1))
    with pytest.raises(ValueError):
        asyncio.run(provider.execute("receipt-ocr", {"imageBase64": "not-base64"}, timeout=1))
    with pytest.raises(ValueError):
        asyncio.run(provider.execute("receipt-ocr", {"imageBase64": "A" * 17_000_000}, timeout=1))


def test_real_tesseract_reads_synthetic_text_when_binary_is_available(monkeypatch):
    command = os.getenv("TESSERACT_CMD") or shutil.which("tesseract")
    if not command or not Path(command).is_file():
        pytest.skip("Tesseract binary is not installed")
    monkeypatch.setenv("TESSERACT_CMD", command)
    provider = TesseractProvider(timeout_seconds=10)
    images = []
    ordinary = Image.new("RGB", (800, 180), "white")
    ImageDraw.Draw(ordinary).text((24, 40), "TOTAL 123.45", fill="black", font=ImageFont.load_default(size=48))
    images.append(ordinary)
    narrow = Image.new("RGB", (220, 700), "white")
    narrow_draw = ImageDraw.Draw(narrow)
    font = ImageFont.load_default(size=28)
    narrow_draw.text((12, 70), "MILK 5.99", fill="black", font=font)
    narrow_draw.text((12, 600), "TOTAL 123.45", fill="black", font=font)
    images.append(narrow.rotate(4, resample=Image.Resampling.BICUBIC, expand=True, fillcolor="white"))

    results = []
    for image in images:
        image_buffer = BytesIO()
        image.save(image_buffer, format="PNG")
        results.append(asyncio.run(provider.execute("receipt-ocr", {
            "imageBase64": base64.b64encode(image_buffer.getvalue()).decode("ascii"),
        }, timeout=10)))

    assert all("123.45" in result["text"] for result in results)
    assert "MILK" in results[1]["text"]
    assert all(result["modelVersion"].startswith("tesseract-") for result in results)
    assert all(result["promptVersion"] == "tesseract-ocr.v2" for result in results)


def test_provider_selects_best_preprocessing_variant_and_repairs_split_amount(monkeypatch):
    variants = [
        OcrVariant("weak", Image.new("L", (360, 90), 10)),
        OcrVariant("table", Image.new("L", (360, 90), 200)),
    ]
    monkeypatch.setattr(receipt_ocr, "preprocess_variants", lambda _image: variants)

    def raw(words, left, top, width):
        return {"text": words, "conf": ["91"] * len(words), "left": left,
                "top": top, "width": width, "height": [12] * len(words)}

    def recognize(image, _language, _timeout):
        if image.getpixel((0, 0)) == 10:
            return raw(["blurred"], [12], [12], [40])
        return raw(["Milk", "2.00", "5", ".99", "Bread", "1.50", "3.00", "TOTAL", "8.99"],
                   [15, 205, 285, 296, 15, 205, 285, 15, 285],
                   [10, 10, 10, 10, 32, 32, 32, 54, 54],
                   [34, 30, 8, 22, 40, 30, 30, 40, 30])

    provider = TesseractProvider(ocr_engine=recognize, version="5.3.0")

    result = asyncio.run(provider.execute("receipt-ocr", image_context(), timeout=5))

    assert result["text"] == "Milk 2.00 5.99 Bread 1.50 3.00 TOTAL 8.99"
    assert result["promptVersion"] == "tesseract-ocr.v2"
    assert result["total"] == "8.99"
    assert result["items"] == [
        {"name": "Milk", "quantity": None, "unitPrice": "2.00", "lineSum": "5.99"},
        {"name": "Bread", "quantity": None, "unitPrice": "1.50", "lineSum": "3.00"},
    ]


def test_provider_rereads_missing_sum_only_inside_inferred_total_column(monkeypatch):
    variant = OcrVariant("table", Image.new("L", (360, 90), 200))
    monkeypatch.setattr(receipt_ocr, "preprocess_variants", lambda _image: [variant])
    rereads = []

    def raw(words, left, top, width):
        return {"text": words, "conf": ["91"] * len(words), "left": left,
                "top": top, "width": width, "height": [12] * len(words)}

    def recognize(_image, _language, _timeout):
        return raw(["Milk", "5.99", "Bread", "Eggs", "3.00", "TOTAL", "8.99"],
                   [15, 285, 15, 15, 285, 15, 285], [10, 10, 32, 54, 54, 76, 76],
                   [34, 30, 40, 28, 30, 40, 30])

    def reread_cell(image, language, timeout, config):
        rereads.append((image.size, language, timeout, config))
        return raw(["2.99"], [5], [3], [25])

    provider = TesseractProvider(ocr_engine=recognize, cell_ocr_engine=reread_cell, version="5.3.0")

    result = asyncio.run(provider.execute("receipt-ocr", image_context(), timeout=5))

    assert "2.99" in result["text"]
    assert len(rereads) == 1
    assert rereads[0][1] == "eng"
    assert "tessedit_char_whitelist=0123456789.," in rereads[0][3]
