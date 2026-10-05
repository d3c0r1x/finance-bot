"""Bounded receipt image variants and geometry-aware OCR helpers."""

from __future__ import annotations

import re
import hashlib
import math
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Callable, TypeVar

import cv2
import numpy as np
from PIL import Image, ImageOps


MAX_OCR_SIDE = 3200
_MONEY = re.compile(r"^(?:\d{1,3}(?:[ \u00a0]\d{3})+|\d+)[,.]\d{1,2}$")
_INTEGER = re.compile(r"^\d{1,5}$")
_DECIMAL_TAIL = re.compile(r"^[.,‚·\u201a\u00b7']?(\d{2})\D*$")
_THOUSANDS_TAIL = re.compile(r"^(\d{3})[.,](\d{1,2})\D*$")
_ITEM_SERVICE_PREFIXES = (
    "итог", "итого", "всего", "сумма", "к оплате", "total", "subtotal", "payment",
    "card", "cash", "change", "скидк", "сдач", "налог", "ндс", "vat", "оплат",
)
_TOTAL_PREFIXES = ("итог", "итого", "всего", "сумма", "к оплате", "total", "subtotal")


@dataclass(frozen=True)
class OcrVariant:
    name: str
    image: Image.Image
    matrix_to_source: np.ndarray | None = None
    source_size: tuple[int, int] | None = None


T = TypeVar("T")


def preprocess_variants(image: Image.Image) -> list[OcrVariant]:
    """Return a small, fixed set of bounded grayscale receipt readings."""
    if not isinstance(image, Image.Image):
        raise ValueError("Receipt OCR image is invalid")
    source = ImageOps.exif_transpose(image).convert("RGB")
    if not source.width or not source.height:
        raise ValueError("Receipt OCR image is empty")
    source_size = source.size
    source.thumbnail((MAX_OCR_SIDE, MAX_OCR_SIDE), Image.Resampling.LANCZOS)
    original_to_bounded = np.array([
        [source.width / source_size[0], 0.0, 0.0],
        [0.0, source.height / source_size[1], 0.0],
        [0.0, 0.0, 1.0],
    ])
    source_gray = ImageOps.autocontrast(source.convert("L"))
    variants = [OcrVariant("gray", source_gray, np.linalg.inv(original_to_bounded), source_size)]
    cropped, crop_left, crop_top = _crop_ink(source)
    crop_to_original = np.linalg.inv(original_to_bounded) @ np.array([
        [1.0, 0.0, crop_left], [0.0, 1.0, crop_top], [0.0, 0.0, 1.0],
    ])
    gray = ImageOps.autocontrast(cropped.convert("L"))
    if cropped.size != source.size:
        variants.append(OcrVariant("crop", gray, crop_to_original, source_size))
    gray_to_original = crop_to_original

    scale = min(2.5, MAX_OCR_SIDE / max(gray.size))
    if scale > 1.05:
        enlarged = gray.resize((round(gray.width * scale), round(gray.height * scale)),
                               Image.Resampling.LANCZOS)
    else:
        enlarged = gray.copy()
    scale_matrix = np.array([[gray.width / enlarged.width, 0.0, 0.0],
                             [0.0, gray.height / enlarged.height, 0.0], [0.0, 0.0, 1.0]])
    variants.append(OcrVariant("upscaled", enlarged, gray_to_original @ scale_matrix, source_size))

    pixels = np.asarray(gray)
    thresholded = cv2.threshold(pixels, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]
    variants.append(OcrVariant("threshold", Image.fromarray(thresholded), gray_to_original, source_size))
    deskewed, rotation = _deskew(gray)
    variants.append(OcrVariant("deskew", deskewed,
                               gray_to_original @ np.linalg.inv(rotation), source_size))
    denoised = cv2.fastNlMeansDenoising(pixels, None, 10, 7, 21)
    variants.append(OcrVariant("denoised", Image.fromarray(denoised), gray_to_original, source_size))

    # A checksum-like pixel key removes duplicate variants without changing their order.
    unique: list[OcrVariant] = []
    seen: set[tuple[tuple[int, int], bytes]] = set()
    for variant in variants:
        key = (variant.image.size, hashlib.blake2s(variant.image.tobytes()).digest())
        if key not in seen:
            seen.add(key)
            unique.append(variant)
    return unique


def _crop_ink(image: Image.Image) -> tuple[Image.Image, int, int]:
    gray = image.convert("L")
    mask = gray.point(lambda value: 255 if value < 245 else 0)
    bounds = mask.getbbox()
    if not bounds:
        return image, 0, 0
    left, top, right, bottom = bounds
    area = (right - left) * (bottom - top)
    if area < image.width * image.height * 0.12 or area > image.width * image.height * 0.96:
        return image, 0, 0
    margin = max(4, round(max(image.size) * 0.025))
    left, top = max(0, left - margin), max(0, top - margin)
    right, bottom = min(image.width, right + margin), min(image.height, bottom + margin)
    return image.crop((left, top, right, bottom)), left, top


def _deskew(image: Image.Image) -> tuple[Image.Image, np.ndarray]:
    """Correct modest receipt rotation using the ink baseline; ignore unstable angles."""
    pixels = np.asarray(image.convert("L"))
    ink = cv2.threshold(pixels, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)[1]
    points = cv2.findNonZero(ink)
    if points is None or len(points) < 50:
        return image.copy(), np.eye(3)
    angle = cv2.minAreaRect(points)[-1]
    if angle < -45:
        angle += 90
    if not 0.4 <= abs(angle) <= 15:
        return image.copy(), np.eye(3)
    height, width = pixels.shape
    matrix = cv2.getRotationMatrix2D((width / 2, height / 2), angle, 1.0)
    rotated = cv2.warpAffine(pixels, matrix, (width, height),
                             flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_CONSTANT,
                             borderValue=255)
    transform = np.vstack((matrix, [0.0, 0.0, 1.0]))
    return Image.fromarray(rotated), transform


def read_variants_in_stable_order(variants: list[OcrVariant], reader: Callable[[OcrVariant], T],
                                  max_workers: int = 2) -> list[T]:
    """Read variants concurrently while retaining their declared result order."""
    if max_workers < 1:
        raise ValueError("OCR worker count must be positive")
    if not variants:
        return []
    results: list[T | None] = [None] * len(variants)
    with ThreadPoolExecutor(max_workers=min(max_workers, len(variants))) as pool:
        futures = {pool.submit(reader, variant): index for index, variant in enumerate(variants)}
        for future in as_completed(futures):
            results[futures[future]] = future.result()
    return results  # type: ignore[return-value]


def map_words_to_source(words: list[dict], variant: OcrVariant) -> list[dict]:
    """Map word boxes from a processed variant back to the supplied image coordinates."""
    if variant.matrix_to_source is None or variant.source_size is None:
        return words
    width, height = variant.source_size
    output = []
    for word in words:
        box = word["box"]
        corners = np.array([
            [box["x"], box["y"], 1.0],
            [box["x"] + box["width"], box["y"], 1.0],
            [box["x"], box["y"] + box["height"], 1.0],
            [box["x"] + box["width"], box["y"] + box["height"], 1.0],
        ])
        mapped = corners @ variant.matrix_to_source.T
        left, top = np.min(mapped[:, :2], axis=0)
        right, bottom = np.max(mapped[:, :2], axis=0)
        x, y = max(0, math.floor(left)), max(0, math.floor(top))
        right, bottom = min(width, math.ceil(right)), min(height, math.ceil(bottom))
        if right <= x or bottom <= y:
            continue
        output.append({**word, "box": {"x": x, "y": y, "width": right - x, "height": bottom - y}})
    return output


def group_rows(words: list[dict]) -> list[list[dict]]:
    """Group OCR words by vertical center and return rows/words in stable reading order."""
    if not words:
        return []
    valid = [word for word in words if _valid_word(word)]
    if not valid:
        return []
    heights = sorted(max(1, word["box"]["height"]) for word in valid)
    tolerance = max(3.0, heights[len(heights) // 2] * 0.6)
    rows: list[dict] = []
    for word in sorted(valid, key=lambda item: (item["box"]["y"] + item["box"]["height"] / 2,
                                                item["box"]["x"])):
        box = word["box"]
        center = box["y"] + box["height"] / 2
        row = next((candidate for candidate in reversed(rows[-3:])
                    if abs(candidate["center"] - center) <= tolerance), None)
        if row is None:
            rows.append({"center": center, "words": [word]})
        else:
            row["words"].append(word)
            row["center"] = sum(item["box"]["y"] + item["box"]["height"] / 2
                                for item in row["words"]) / len(row["words"])
    return [sorted(row["words"], key=lambda item: item["box"]["x"]) for row in rows]


def _valid_word(word: object) -> bool:
    return isinstance(word, dict) and isinstance(word.get("text"), str) and bool(word["text"].strip()) \
        and isinstance(word.get("box"), dict) \
        and all(type(word["box"].get(key)) is int and word["box"][key] >= 0
                for key in ("x", "y", "width", "height"))


def _money_word(text: str) -> bool:
    return bool(_MONEY.fullmatch(text.strip().replace(" ", "")))


def is_money_token(text: str) -> bool:
    return _money_word(text)


def repair_split_amounts(row: list[dict]) -> list[dict]:
    """Join decimal fragments only when geometry makes the split unambiguous."""
    result: list[dict] = []
    index = 0
    while index < len(row):
        current = row[index]
        token = current["text"].strip()
        if index + 1 < len(row) and _INTEGER.fullmatch(token):
            following = row[index + 1]
            next_text = following["text"].strip()
            first_box, next_box = current["box"], following["box"]
            gap = next_box["x"] - (first_box["x"] + first_box["width"])
            close = gap <= max(6, first_box["height"] * 3)
            decimal = _DECIMAL_TAIL.fullmatch(next_text)
            thousands = _THOUSANDS_TAIL.fullmatch(next_text)
            joined = None
            if decimal and close:
                joined = f"{token}.{decimal.group(1)}"
            elif thousands and close:
                joined = f"{token}{thousands.group(1)}.{thousands.group(2)}"
            if joined:
                merged = {**current, "text": joined,
                          "confidence": min(current["confidence"], following["confidence"]),
                          "box": {
                              "x": first_box["x"], "y": min(first_box["y"], next_box["y"]),
                              "width": max(first_box["x"] + first_box["width"],
                                           next_box["x"] + next_box["width"]) - first_box["x"],
                              "height": max(first_box["y"] + first_box["height"],
                                            next_box["y"] + next_box["height"])
                                       - min(first_box["y"], next_box["y"]),
                          }}
                result.append(merged)
                index += 2
                continue
        result.append(current)
        index += 1
    return result


def infer_numeric_columns(rows: list[list[dict]], image_width: int) -> dict:
    """Cluster repeated money right edges into total and unit-price bands."""
    tokens = [word for row in rows for word in row if _money_word(word["text"])]
    if not tokens or image_width <= 0:
        return {"sum": None, "price": None}
    tolerance = max(16.0, image_width * 0.045)
    clusters: list[dict] = []
    for word in sorted(tokens, key=lambda item: item["box"]["x"] + item["box"]["width"]):
        box = word["box"]
        right = box["x"] + box["width"]
        if clusters and right - clusters[-1]["right"] <= tolerance:
            cluster = clusters[-1]
            cluster["left"] = min(cluster["left"], box["x"])
            cluster["right"] = max(cluster["right"], right)
            cluster["count"] += 1
        else:
            clusters.append({"left": box["x"], "right": right, "count": 1})
    repeated = [cluster for cluster in clusters if cluster["count"] >= 2
                and cluster["right"] >= image_width * 0.45]
    repeated.sort(key=lambda item: item["right"])
    if not repeated:
        rightmost = max(clusters, key=lambda item: item["right"])
        return {"sum": (rightmost["left"], rightmost["right"]), "price": None}
    return {"sum": (repeated[-1]["left"], repeated[-1]["right"]),
            "price": (repeated[-2]["left"], repeated[-2]["right"]) if len(repeated) > 1 else None}


def extract_structured_reading(rows: list[list[dict]], columns: dict) -> dict:
    """Extract conservative item candidates and a printed, labeled receipt total."""
    total = None
    items = []
    sum_band = columns.get("sum") if isinstance(columns, dict) else None
    price_band = columns.get("price") if isinstance(columns, dict) else None

    for row in rows:
        valid = [word for word in row if _valid_word(word)]
        if not valid:
            continue
        valid.sort(key=lambda item: item["box"]["x"])
        label = " ".join(word["text"].strip().lower() for word in valid
                         if not _money_word(word["text"]))
        money = [(index, word, _money_decimal(word["text"]))
                 for index, word in enumerate(valid) if _money_word(word["text"])]
        money = [(index, word, amount) for index, word, amount in money if amount is not None]
        if not money:
            continue

        if any(label.startswith(prefix) for prefix in _TOTAL_PREFIXES):
            total = _format_money(money[-1][2])
            continue
        if any(label.startswith(prefix) for prefix in _ITEM_SERVICE_PREFIXES):
            continue

        line_sum = _amount_in_band(money, sum_band)
        if line_sum is None:
            # A lone rightmost amount is the only supported sum when no stable
            # repeated numeric column was found.
            line_sum = money[-1] if not sum_band else None
        if line_sum is None:
            continue
        sum_index, sum_word, sum_value = line_sum
        name_parts = []
        for word in valid[:sum_index]:
            token = word["text"].strip()
            if _money_word(token) or re.fullmatch(r"[xх×]\s*\d+(?:[.,]\d+)?", token, re.IGNORECASE):
                continue
            if any(char.isalpha() for char in token):
                name_parts.append(token)
        name = " ".join(name_parts).strip(" .,:;|-")
        if sum(char.isalpha() for char in name) < 3:
            continue

        unit_price = _amount_in_band(money, price_band)
        if unit_price is not None and unit_price[0] >= sum_index:
            unit_price = None
        items.append({
            "name": name[:200],
            "quantity": None,
            "unitPrice": _format_money(unit_price[2]) if unit_price is not None else None,
            "lineSum": _format_money(sum_value),
        })
        if len(items) == 80:
            break
    return {"total": total, "items": items}


def _amount_in_band(money: list[tuple[int, dict, Decimal]], band):
    if not band or len(band) != 2:
        return None
    left, right = band
    tolerance = max(12, (right - left) * 0.25)
    matching = [entry for entry in money
                if left - tolerance <= entry[1]["box"]["x"] + entry[1]["box"]["width"] <= right + tolerance]
    return matching[-1] if matching else None


def _money_decimal(token: str) -> Decimal | None:
    normalized = token.strip().replace(" ", "").replace("\u00a0", "").replace(",", ".")
    try:
        amount = Decimal(normalized)
    except InvalidOperation:
        return None
    if not amount.is_finite() or amount <= 0 or amount.as_tuple().exponent < -2:
        return None
    return amount


def _format_money(amount: Decimal) -> str:
    return format(amount.quantize(Decimal("0.01")), ".2f")


def missing_amount_cells(rows: list[list[dict]], columns: dict, image_size: tuple[int, int]) \
        -> list[tuple[int, int, int, int]]:
    """Locate unread amount cells only for named rows adjacent to confirmed money rows."""
    band = columns.get("sum") if isinstance(columns, dict) else None
    if not band or len(band) != 2:
        return []
    width, height = image_size
    money_rows = [index for index, row in enumerate(rows)
                  if any(_money_word(word["text"]) for word in row)]
    if len(money_rows) < 2:
        return []
    output = []
    left_band, right_band = map(int, band)
    cell_left = max(0, left_band - max(12, (right_band - left_band) // 2))
    cell_right = min(width, right_band + 12)
    for index, row in enumerate(rows):
        if any(_money_word(word["text"]) for word in row):
            continue
        if not any(any(char.isalpha() for char in word["text"]) for word in row):
            continue
        nearby = [known for known in money_rows if abs(known - index) <= 2]
        if not nearby:
            continue
        top = min(word["box"]["y"] for word in row)
        bottom = max(word["box"]["y"] + word["box"]["height"] for word in row)
        padding = max(3, (bottom - top) // 3)
        cell = (cell_left, max(0, top - padding), cell_right, min(height, bottom + padding))
        if cell[2] - cell[0] >= 6 and cell[3] - cell[1] >= 5:
            output.append(cell)
    return output[:20]
