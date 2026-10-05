from concurrent.futures import ThreadPoolExecutor
from time import sleep

import cv2
import numpy as np
from PIL import Image, ImageDraw

from services.python.intelligence.receipt_ocr import (
    group_rows,
    extract_structured_reading,
    infer_numeric_columns,
    missing_amount_cells,
    map_words_to_source,
    preprocess_variants,
    read_variants_in_stable_order,
    repair_split_amounts,
)


def word(text, x, y, width=28, height=12, confidence=90):
    return {"text": text, "confidence": confidence,
            "box": {"x": x, "y": y, "width": width, "height": height}}


def test_receipt_preprocessing_produces_bounded_variants_for_tilted_image():
    image = Image.new("RGB", (220, 520), "white")
    draw = ImageDraw.Draw(image)
    draw.line((30, 80, 190, 50), fill="black", width=3)
    draw.text((30, 95), "MILK  5.99", fill="black")

    variants = preprocess_variants(image)

    assert {variant.name for variant in variants} >= {"gray", "upscaled", "threshold", "deskew"}
    assert all(max(variant.image.size) <= 3200 for variant in variants)
    assert all(variant.image.mode == "L" for variant in variants)


def test_parallel_ocr_results_keep_preprocessing_order():
    variants = preprocess_variants(Image.new("RGB", (80, 120), "white"))

    def read(variant):
        if variant.name == variants[0].name:
            sleep(0.03)
        return variant.name

    readings = read_variants_in_stable_order(variants, read, max_workers=3)

    assert readings == [variant.name for variant in variants]


def test_receipt_words_group_into_rows_and_repair_split_cents_and_columns():
    rows = group_rows([
        word("Milk", 15, 10, width=34), word("2.00", 205, 10, width=30), word("5", 285, 10, width=8),
        word(".99", 296, 10, width=22),
        word("Bread", 15, 32, width=40), word("1.50", 205, 32, width=30), word("3.00", 285, 32),
        word("TOTAL", 15, 54, width=40), word("8.99", 285, 54),
    ])

    repaired = [repair_split_amounts(row) for row in rows]
    columns = infer_numeric_columns(repaired, image_width=360)

    assert len(repaired) == 3
    assert [item["text"] for item in repaired[0]] == ["Milk", "2.00", "5.99"]
    assert columns["sum"] is not None and columns["sum"][0] >= 280
    assert columns["price"] is not None and columns["price"][0] >= 200


def test_structured_ocr_extracts_named_item_rows_and_only_a_labeled_total():
    rows = group_rows([
        word("Milk", 15, 10, width=34), word("2.00", 205, 10, width=30), word("5.99", 285, 10),
        word("Bread", 15, 32, width=40), word("1.00", 205, 32, width=30), word("3.00", 285, 32),
        word("TOTAL", 15, 54, width=40), word("8.99", 285, 54),
        word("CARD", 15, 76, width=34), word("8.99", 285, 76),
    ])
    repaired = [repair_split_amounts(row) for row in rows]
    columns = infer_numeric_columns(repaired, image_width=360)

    result = extract_structured_reading(repaired, columns)

    assert result == {
        "total": "8.99",
        "items": [
            {"name": "Milk", "quantity": None, "unitPrice": "2.00", "lineSum": "5.99"},
            {"name": "Bread", "quantity": None, "unitPrice": "1.00", "lineSum": "3.00"},
        ],
    }


def test_structured_ocr_does_not_invent_total_or_merge_duplicate_item_rows():
    rows = group_rows([
        word("Bread", 15, 10, width=40), word("5.00", 285, 10),
        word("Bread", 15, 32, width=40), word("5.00", 285, 32),
        word("CARD", 15, 54, width=34), word("10.00", 285, 54),
    ])
    repaired = [repair_split_amounts(row) for row in rows]
    columns = infer_numeric_columns(repaired, image_width=360)

    result = extract_structured_reading(repaired, columns)

    assert result["total"] is None
    assert len(result["items"]) == 2
    assert [item["lineSum"] for item in result["items"]] == ["5.00", "5.00"]


def test_missing_item_amount_cell_is_scoped_to_inferred_total_column():
    rows = group_rows([
        word("Milk", 15, 10, width=34), word("5.99", 285, 10),
        word("Bread", 15, 32, width=40),
        word("Eggs", 15, 54, width=28), word("3.00", 285, 54),
    ])
    columns = {"sum": (280, 315), "price": None}

    cells = missing_amount_cells(rows, columns, image_size=(360, 90))

    assert len(cells) == 1
    left, top, right, bottom = cells[0]
    assert left >= 255 and right <= 360
    assert top <= 32 <= bottom
    assert right > left and bottom > top


def test_upscaled_ocr_coordinates_map_back_to_source_image():
    variants = preprocess_variants(Image.new("RGB", (80, 120), "white"))
    upscaled = next(variant for variant in variants if variant.name == "upscaled")

    mapped = map_words_to_source([word("Milk", 100, 50, width=25, height=25)], upscaled)

    assert mapped[0]["box"] == {"x": 40, "y": 20, "width": 10, "height": 10}


def test_deskew_variant_straightens_a_synthetic_narrow_receipt():
    source = Image.new("RGB", (180, 900), "white")
    draw = ImageDraw.Draw(source)
    for y in range(40, 860, 45):
        draw.text((12, y), f"ITEM {y // 45} 5.99", fill="black")
    tilted = source.rotate(5, resample=Image.Resampling.BICUBIC, expand=True, fillcolor="white")
    variants = preprocess_variants(tilted)

    def skew(image):
        pixels = np.asarray(image.convert("L"))
        ink = cv2.threshold(pixels, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)[1]
        points = cv2.findNonZero(ink)
        angle = cv2.minAreaRect(points)[-1]
        return angle + 90 if angle < -45 else angle

    original_angle = abs(skew(next(variant.image for variant in variants if variant.name == "gray")))
    corrected_angle = abs(skew(next(variant.image for variant in variants if variant.name == "deskew")))

    assert original_angle >= 3.5
    assert corrected_angle <= original_angle * 0.25
