"""Распознавание чеков: Tesseract + разбор таблицы по колонкам.

Чек — это таблица «наименование | цена до скидки | скидка | НДС | цена | кол-во | итого».
Построчный OCR её ломает: названия, объёмы («1.35л») и числа идут вперемешку, а десятичные
части отрываются на соседнюю строку. Поэтому алгоритм такой:

1. предобработка фото несколькими вариантами (обрезка, upscale, контраст, бинаризация);
2. OCR со координатами слов (image_to_data);
3. сборка строк по вертикали;
4. поиск колонок: правые края чисел выровнены по правому краю колонки, поэтому числовые
   токены собираются в плотные кластеры. Самый населённый кластер справа — «итого»,
   левее — «цена», отдельно ищется «кол-во». Всё, что левее, — часть названия;
5. сборка позиций: строка с названием начинает позицию, числовые строки её дополняют;
6. проверка арифметики: если «цена × количество» не сходится с суммой — ячейка
   перечитывается крупным планом с whitelist цифр;
7. выбор лучшего варианта предобработки по качеству разбора.

Числа берём из разбора, а не из ответа модели: названия и категории доводит ИИ (ai/receipts.py).
"""
import asyncio
import os
import re
import time

from config import RECEIPTS_DIR, TESSERACT_CMD

try:
    import numpy as np
    import pytesseract
    from PIL import Image, ImageChops, ImageFilter, ImageOps

    pytesseract.pytesseract.tesseract_cmd = TESSERACT_CMD
    OCR_AVAILABLE = True
except ImportError:  # pragma: no cover
    OCR_AVAILABLE = False

try:  # необязательно: даёт ещё один вариант предобработки для шумных фото
    import cv2
except ImportError:  # pragma: no cover
    cv2 = None

OCR_CONFIG = "--oem 3 --psm 6"
# Разреженный layout: читает чек как набор блоков и видит крайние правые ячейки,
# которые построчный режим иногда теряет целиком.
SPARSE_CONFIG = "--oem 3 --psm 11"
MIN_WORD_CONFIDENCE = 25
NUMERIC_WHITELIST = "0123456789.,"
# максимальный размер картинки для OCR (больше — только медленнее, без пользы)
MAX_OCR_SIDE = 3200
# сколько последних токенов названия берём в позицию (остальное — шапка чека)
NAME_TOKEN_LIMIT = 12
# к какому размеру тянем фото перед чтением моделью зрения (мелкий шрифт читается точнее)
VISION_TARGET_SIDE = 1800
# бюджет времени на все варианты предобработки, чтобы ответ не ждался минуту
MAX_OCR_SECONDS = 10.0

# Хвост названия из колонок: «Пакет-майка 1x», «Товар 8», «Вино ix».
TRAILING_QTY_RE = re.compile(r"(?:\d{1,3}|[lIi1])[xXх]|\d{1,2}$")
# Токен начинает число: «6.99», «249.99*», «1 099,99».
# Две формы: с разрядами тысяч и без. Голые целые (без запятой) числом не считаем.
MONEY_RE = re.compile(r"^(\d{1,3}(?:[  \u00a0]\d{3})+[.,]\d{1,2}|\d+[.,]\d{1,2})")
# «05.07.22» — дата, а не цена
DATE_TOKEN_RE = re.compile(r"^\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4}$")
# Разорванная десятичная часть: «1234» + «‚56» — OCR теряет точку
DECIMAL_TAIL_RE = re.compile(r"^[.,‚·'\u201a\u00b7]?(\d{2})\D*$")
# Разряды тысяч, разорванные пробелом: «1» + «099,99»
THOUSANDS_TAIL_RE = re.compile(r"^(\d{3})[.,](\d{1,2})\D*$")
INTEGER_RE = re.compile(r"^\d{1,5}$")
PERCENT_RE = re.compile(r"^\d{1,2}[%°]$")
QTY_RE = re.compile(r"^(\d{1,3})\s?(?:шт|шt|u?t|wt|x|х)[.,:]?$", re.IGNORECASE)
QTY_INLINE_RE = re.compile(r"\b(\d{1,3})\s*[xх]\b", re.IGNORECASE)
QTY_ONLY_RE = re.compile(r"^(?:шт|шt|ut|wt|wr|up|ur|и/т)[.,:]?$", re.IGNORECASE)
# Строка итога: OCR часто читает «подытог» как «noanuTor», и такая строка легко
# превращается в «товар» на всю сумму чека.
TOTAL_RE = re.compile(r"(и\s?тог|итого|подытог|no\s?a?u?n?[a-z]*tor|к\s*оплате)", re.IGNORECASE)
SKIP_ROW_RE = re.compile(r"(скидк|округлен|наличн|безналичн|сдача|принято|итог|итого|"
                         r"кассир|приход|место расчетов|рн ккт|фн:|фд:|зн ккт|инн|код|тел|сайт|"
                         r"артикул|заказ|получател|лимит|бонус|гарант|"
                         # НДС в чеках часто читается как «HAC», «НАС»
                         r"\bндс\b|\bhac\b|\bnac\b|\bнас\b)", re.IGNORECASE)
HEADER_RE = re.compile(r"кол.{0,3}во|кол-во|цена", re.IGNORECASE)


# ─── Предобработка картинки ──────────────────────────────────────────────

def _auto_crop(image):
    """Оставляет область чека: ограничивающий прямоугольник светлых пикселей."""
    gray = image.convert("L")
    mask = gray.point(lambda value: 255 if value > 150 else 0)
    bbox = mask.getbbox()
    if not bbox:
        return image
    left, top, right, bottom = bbox
    if (right - left) * (bottom - top) < 0.15 * image.width * image.height:
        return image
    margin = 20
    return image.crop((max(0, left - margin), max(0, top - margin),
                       min(image.width, right + margin), min(image.height, bottom + margin)))


def _drop_colored(image):
    """Убирает цветные пометки (красные рамки, маркеры) — они мешают OCR."""
    if image.mode != "RGB":
        return image
    red, green, blue = image.split()
    maximum = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    minimum = ImageChops.darker(ImageChops.darker(red, green), blue)
    saturation = ImageChops.subtract(maximum, minimum)
    colored = saturation.point(lambda value: 255 if value > 60 else 0)
    gray = image.convert("L")
    return Image.composite(Image.new("L", image.size, 255), gray, colored)


def _prepare(image, *, upscale: int = 2, binarize: bool = False, subtract_bg: bool = False):
    gray = image.convert("L")
    if upscale > 1:
        gray = gray.resize((gray.width * upscale, gray.height * upscale), Image.LANCZOS)
    if max(gray.size) > MAX_OCR_SIDE:  # слишком большая картинка замедляет OCR
        ratio = MAX_OCR_SIDE / max(gray.size)
        gray = gray.resize((max(1, int(gray.width * ratio)), max(1, int(gray.height * ratio))),
                           Image.LANCZOS)
    if subtract_bg:
        background = gray.filter(ImageFilter.GaussianBlur(radius=30))
        gray = ImageChops.subtract(gray, background, scale=1.0, offset=128)
    gray = ImageOps.autocontrast(gray)
    if binarize:
        gray = gray.point(lambda value: 255 if value > 160 else 0)
    return gray


def _denoise_cv2(image):
    """Сильное шумоподавление: помогает на фото, снятых при плохом свете."""
    if cv2 is None:
        return None
    try:
        gray = np.array(image.convert("L"))
        return Image.fromarray(cv2.fastNlMeansDenoising(gray, None, 10, 7, 21))
    except Exception:  # pragma: no cover
        return None


def _preprocess_variants(image_path: str):
    """Варианты предобработки: от самого точного к самым «шумным», с разным балансом."""
    original = Image.open(image_path)
    cleaned = _drop_colored(original)
    cropped = _auto_crop(cleaned)
    variants = [_prepare(cropped)]                       # базовый: без бинаризации
    if max(cropped.size) * 3 <= MAX_OCR_SIDE:            # мелкий текст — крупнее
        variants.append(_prepare(cropped, upscale=3))
    variants += [
        _prepare(cropped, binarize=True),
        _prepare(cropped, binarize=True, subtract_bg=True),
    ]
    if cleaned is not original:
        variants.append(_prepare(_auto_crop(original), binarize=True))
    denoised = _denoise_cv2(cropped)
    if denoised is not None:
        variants.append(_prepare(denoised))
    return variants


# ─── Слова с координатами и строки ───────────────────────────────────────

def _deskew(image):
    """Выпрямляет наклон: угол считаем по чернильным пикселям (minAreaRect)."""
    if cv2 is None:
        return image
    try:
        import numpy as np

        gray = np.array(image.convert("L"))
        ink = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)[1]
        points = cv2.findNonZero(ink)
        if points is None or len(points) < 50:
            return image
        angle = cv2.minAreaRect(points)[-1]
        if angle < -45:            # minAreaRect меряет по короткой стороне
            angle += 90
        if not 0.4 <= abs(angle) <= 15:   # ровный чек или вообще не чек — не трогаем
            return image
        height, width = gray.shape[:2]
        matrix = cv2.getRotationMatrix2D((width // 2, height // 2), angle, 1.0)
        rotated = cv2.warpAffine(np.array(image), matrix, (width, height),
                                 flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
        return Image.fromarray(rotated)
    except Exception:  # pragma: no cover — выпрямление не должно ломать чтение
        return image


def _enhance_rgb(image, strong: bool = False):
    """Однотонный, но резкий вариант: крупнее, контрастнее, с убранными тенями."""
    scale = 1.0
    if max(image.size) < VISION_TARGET_SIDE:      # мелкий текст чека крупнее читается лучше
        scale = min(2.5, VISION_TARGET_SIDE / max(image.size))
    if max(image.size) * scale > MAX_OCR_SIDE:
        scale = MAX_OCR_SIDE / max(image.size)
    if scale != 1.0:
        image = image.resize((max(1, int(image.width * scale)), max(1, int(image.height * scale))),
                             Image.LANCZOS)
    gray = image.convert("L")
    if strong:                                     # тени от hands/фона
        background = gray.filter(ImageFilter.GaussianBlur(radius=25))
        gray = ImageChops.subtract(gray, background, scale=1.0, offset=128)
    gray = ImageOps.autocontrast(gray)
    if strong:
        gray = gray.filter(ImageFilter.UnsharpMask(radius=2, percent=140, threshold=3))
    return gray.convert("RGB")


def vision_variants(image_path: str) -> list[tuple[str, "Image.Image"]]:
    """Фото чека для модели зрения: [(метка, картинка в RGB)], от лучшего варианта к запасным.

    Модель зрения раньше получала сырое фото — с фоном, наклоном и тенями, — хотя у Tesseract
    обрезка и контраст уже были. Здесь то же самое, но в цвете: вырезаем чек, выпрямляем,
    поднимаем резкость. Запасные варианты нужны для второго прохода, если первый не сошёлся.
    """
    original = Image.open(image_path).convert("RGB")
    cropped = _auto_crop(_drop_colored(original).convert("RGB"))
    straight = _deskew(cropped)
    return [
        ("crop", _enhance_rgb(straight)),                 # основной вариант
        ("clean", _enhance_rgb(straight, strong=True)),   # снятые тени и резкость
        ("raw", _enhance_rgb(original)),                  # если обрезка съела часть чека
    ]


def _ocr_words(image, config: str | None = None) -> list[dict]:
    data = pytesseract.image_to_data(image, lang="rus+eng", config=config or OCR_CONFIG,
                                     output_type=pytesseract.Output.DICT)
    words = []
    for index, text in enumerate(data["text"]):
        token = (text or "").strip()
        if not token:
            continue
        try:
            confidence = float(data["conf"][index])
        except (TypeError, ValueError):
            confidence = 0
        if confidence < MIN_WORD_CONFIDENCE:
            continue
        left, top = int(data["left"][index]), int(data["top"][index])
        words.append({"text": token, "left": left, "right": left + int(data["width"][index]),
                      "top": top, "bottom": top + int(data["height"][index])})
    return words


def _group_rows(words: list[dict]) -> list[dict]:
    """Собирает слова в строки по вертикали (строки чека идут плотно друг к другу)."""
    if not words:
        return []
    heights = sorted(word["bottom"] - word["top"] for word in words)
    line_height = heights[len(heights) // 2] or 10
    tolerance = line_height * 0.6

    rows: list[dict] = []
    for word in sorted(words, key=lambda item: (item["top"], item["left"])):
        center = (word["top"] + word["bottom"]) / 2
        placed = False
        for row in reversed(rows[-3:]):  # строки идут сверху вниз
            if abs(row["center"] - center) <= tolerance:
                row["words"].append(word)
                row["center"] = sum((w["top"] + w["bottom"]) / 2 for w in row["words"]) / len(row["words"])
                row["top"] = min(row["top"], word["top"])
                row["bottom"] = max(row["bottom"], word["bottom"])
                placed = True
                break
        if not placed:
            rows.append({"center": center, "top": word["top"], "bottom": word["bottom"],
                         "words": [word]})
    for row in rows:
        row["words"].sort(key=lambda item: item["left"])
    return sorted(rows, key=lambda row: row["top"])


# ─── Числа ───────────────────────────────────────────────────────────────

def _money(token: str) -> float | None:
    """Сумма из токена: «6.99», «=1234.00», «229,99=», «1 099,99».

    OCR часто приклеивает к денежной ячейке знак равенства или мусор из соседней
    колонки. Разрешаем только пунктуационный мусор по краям, но не выдумываем цифры.
    """
    token = (token or "").strip()
    if DATE_TOKEN_RE.match(token) or PERCENT_RE.match(token):
        return None
    token = re.sub(r"^[^\d]+", "", token)
    match = MONEY_RE.match(token)
    if not match:
        return None
    raw = match.group(1).replace(" ", "").replace("\u00a0", "").replace(",", ".")
    try:
        return float(raw)
    except ValueError:
        return None


def _is_value_token(token: str) -> bool:
    return bool(_money(token) is not None or PERCENT_RE.match(token) or QTY_RE.match(token))


def _next_number(words: list[dict], index: int, max_offset: int = 2):
    """Ближайший числовой токен справа, если между ними только короткий мусор OCR.

    В чеках «1 099,99» и «5 В 099,00» разбиваются на отдельные слова, поэтому ищем
    следующий токен с пропуском обрывков вроде «В», «Хх».
    """
    for offset in range(1, max_offset + 1):
        position = index + offset
        if position >= len(words):
            return None, offset
        token = words[position]["text"]
        if any(char.isdigit() for char in token):
            return words[position], offset
        if len(token) > 2:
            return None, offset
    return None, max_offset


def _row_values(row: dict) -> list[dict]:
    """Числовые токены строки со склейкой разорванных чисел («1234 ›56», «1 099,99»)."""
    values: list[dict] = []
    words = row["words"]
    index = 0
    while index < len(words):
        word = words[index]
        token = word["text"]
        money = _money(token)
        merged = None
        if money is None and INTEGER_RE.match(token):
            nxt, offset = _next_number(words, index)
            if nxt is not None:
                char_height = max(8, word["bottom"] - word["top"])
                # допуск по разрыву зависит от размера текста: на крупных фото числа дальше
                close = nxt["left"] - word["right"] < char_height * 3
                # «1234» + «‚56» — потерянная десятичная точка
                tail = DECIMAL_TAIL_RE.match(nxt["text"])
                # «9» + «398,00» — разряды тысяч, разорванные пробелом
                thousands = THOUSANDS_TAIL_RE.match(nxt["text"])
                # «5» и «099,00» в чеке разнесены по всей строке — склеиваем и через
                # широкий пробел, но только когда это точно разряды тысяч («0XX»)
                wide_split = bool(thousands and len(token) == 1
                                  and thousands.group(1).startswith("0"))
                if tail and close:
                    merged = f"{token}.{tail.group(1)}"
                elif thousands and (close or wide_split):
                    merged = f"{token}{thousands.group(1)}.{thousands.group(2)}"
            if merged:
                values.append({"token": merged, "money": float(merged), "left": word["left"],
                               "right": nxt["right"], "top": word["top"],
                               "bottom": word["bottom"]})
                index += offset + 1
                continue
        if token and (money is not None or PERCENT_RE.match(token) or QTY_RE.match(token)
                      or QTY_ONLY_RE.match(token)):
            values.append({"token": token, "money": money, "left": word["left"],
                           "right": word["right"], "top": word["top"], "bottom": word["bottom"]})
        index += 1
    return values


def _row_text(row: dict) -> str:
    return " ".join(word["text"] for word in row["words"])


# ─── Колонки ─────────────────────────────────────────────────────────────

def _numeric_token(word: dict) -> bool:
    token = word["text"]
    return bool(_is_value_token(token) or INTEGER_RE.match(token))


def _numeric_clusters(rows: list[dict], width: int) -> list[dict]:
    """Кластеры числовых токенов по правому краю — колонки в чеке выровнены справа."""
    tokens = [word for row in rows for word in row["words"] if _numeric_token(word)]
    if not tokens:
        return []
    gap = max(24.0, width * 0.045)
    clusters: list[dict] = []
    for token in sorted(tokens, key=lambda item: item["right"]):
        if clusters and token["right"] - clusters[-1]["right_max"] <= gap:
            cluster = clusters[-1]
            cluster["right_max"] = max(cluster["right_max"], token["right"])
            cluster["left_min"] = min(cluster["left_min"], token["left"])
            cluster["tokens"].append(token)
        else:
            clusters.append({"right_max": token["right"], "left_min": token["left"],
                             "tokens": [token]})
    return clusters


def _total_anchor(rows: list[dict]) -> int | None:
    """Правый край суммы из строки «ИТОГ»: по нему надёжно находится колонка итога."""
    rights = [value["right"] for row in rows if TOTAL_RE.search(_row_text(row).lower())
              for value in _row_values(row) if value["money"]]
    return max(rights) if rights else None


def _column_bands(rows: list[dict], width: int) -> dict | None:
    """Определяет колонки таблицы: «итого» (правая), «цена» и «кол-во».

    None — если таблица не распозналась и надо падать на построчный разбор.
    """
    clusters = _numeric_clusters(rows, width)
    if not clusters:
        return None
    # колонка — плотный кластер из нескольких чисел в правой половине чека
    columns = [c for c in clusters if c["right_max"] > width * 0.5 and len(c["tokens"]) >= 3]
    columns.sort(key=lambda c: c["right_max"])
    if not columns:
        return None

    # Колонка итога — та, где стоит сумма из строки «ИТОГ». Крайний правый кластер бывает
    # мусором от края фото, поэтому ищем по якорю, а он есть не всегда.
    sum_column = columns[-1]
    anchor = _total_anchor(rows)
    if anchor is not None:
        closest = min(columns, key=lambda column: abs(column["right_max"] - anchor))
        if abs(closest["right_max"] - anchor) <= width * 0.06:
            sum_column = closest
    bands = {"width": width, "sum": (sum_column["left_min"], sum_column["right_max"])}

    # колонка левее итога — цена; ещё левее — скидка/НДС (её значения в название не берём)
    roles = ["price", "discount"]
    previous_right = bands["sum"][0]
    for column, role in zip(reversed(columns[:-1]), roles):
        if column["right_max"] < previous_right - 5:
            bands[role] = (column["left_min"], column["right_max"])
            previous_right = column["left_min"]
    bands.setdefault("price", None)
    bands.setdefault("discount", None)

    quantities = [word for row in rows for word in row["words"] if QTY_RE.match(word["text"])
                  or QTY_ONLY_RE.match(word["text"])]
    bands["qty"] = ((min(word["left"] for word in quantities),
                     max(word["right"] for word in quantities)) if len(quantities) >= 2 else None)

    # левее этой границы слова считаем частью названия, правее — значениями колонок
    limits = [band[0] for band in (bands.get("price"), bands.get("discount")) if band]
    bands["name_limit"] = min(limits) if limits else bands["sum"][0] - width * 0.1
    return bands


def _assign_column(right: int, bands: dict) -> str | None:
    """К какой колонке относится число: ближайшая по правому краю в пределах допуска."""
    width = bands.get("width") or 1000
    tolerance = max(18.0, width * 0.05)
    best, best_distance = None, None
    for name in ("sum", "price", "qty", "discount"):
        band = bands.get(name)
        if not band:
            continue
        distance = abs(right - band[1])
        if best_distance is None or distance < best_distance:
            best, best_distance = name, distance
    return best if best_distance is not None and best_distance <= tolerance else None


def _split_columns(row: dict, bands: dict) -> tuple[list[str], dict]:
    """Делит строку на название и числа по колонкам. Числа внутри названия («1.35л») остаются."""
    values: dict[str, list[dict]] = {"sum": [], "price": [], "qty": []}
    numbers = _row_values(row)
    claimed = set()
    for value in numbers:
        if PERCENT_RE.match(value["token"]):
            continue
        column = _assign_column(value["right"], bands)
        if column:
            claimed.add((value["left"], value["right"]))
            if column != "discount":  # скидка/НДС — служебные значения
                values[column].append(value)

    name_limit = bands.get("name_limit")
    names: list[str] = []
    # строка без букв — это строка чисел, а не название товара
    if not any(any(char.isalpha() for char in word["text"]) for word in row["words"]):
        return names, values
    row_has_values = bool(values["sum"] or values["price"])
    for word in row["words"]:
        token = word["text"]
        if (word["left"], word["right"]) in claimed:
            continue
        if PERCENT_RE.match(token) or QTY_ONLY_RE.match(token) or QTY_RE.match(token):
            continue
        # одиночные символы названию не помогают, а обрывки из букв — ещё как (ИИ их починит)
        if len(token) < 2 and not any(char.isdigit() for char in token):
            continue
        # «74.99» в строке с числами — это колонка «цена до скидки», а не название
        if row_has_values and re.fullmatch(r"\d{1,5}[.,]\d{1,2}", token):
            continue
        if _assign_column(word["right"], bands):  # мусор из числовых колонок
            continue
        if name_limit is not None and word["left"] >= name_limit:  # справа от цен — не название
            continue
        names.append(token)
    return names, values


# ─── Перечитывание числовой ячейки ───────────────────────────────────────

def _reread_cell(gray, word: dict, scale: int = 4) -> float | None:
    """Читает ячейку крупным планом с whitelist цифр — уточняет спорные суммы."""
    if gray is None or word is None:
        return None
    top = max(0, int(word["top"]) - 4)
    bottom = min(gray.shape[0], int(word["bottom"]) + 4)
    left = max(0, int(word["left"]) - 6)
    right = min(gray.shape[1], int(word["right"]) + 6)
    if bottom - top < 5 or right - left < 6:
        return None
    try:
        cell = Image.fromarray(gray[top:bottom, left:right]).resize(
            ((right - left) * scale, (bottom - top) * scale), Image.LANCZOS)
        cell = cell.point(lambda value: 255 if value > 150 else 0)
        config = f"--oem 3 --psm 7 -c tessedit_char_whitelist={NUMERIC_WHITELIST}"
        text = pytesseract.image_to_string(cell, lang="eng", config=config).strip()
    except Exception:
        return None
    return _money(text.replace(" ", ""))


# ─── Сборка позиций ──────────────────────────────────────────────────────

def _join_letter_runs(tokens: list[str]) -> list[str]:
    """Склеивает одиночные буквы: на размытом фото Tesseract бьёт слово на «П и в о»."""
    joined: list[str] = []
    run: list[str] = []
    for token in tokens:
        if len(token) == 1 and token.isalpha():
            run.append(token)
            continue
        if run:
            joined.append("".join(run))
            run = []
        joined.append(token)
    if run:
        joined.append("".join(run))
    return joined


def _drop_qty_tail(name: str) -> str:
    """Срезает с названия хвост колонки количества: «Пакет-майка 1x» → «Пакет-майка»."""
    tokens = name.split()
    while len(tokens) > 1 and TRAILING_QTY_RE.fullmatch(tokens[-1]):
        tokens.pop()
    return " ".join(tokens)


def _clean_name(tokens: list[str]) -> str:
    text = re.sub(r"\s+", " ", " ".join(_join_letter_runs(tokens))).strip()
    text = re.sub(r"^[^0-9A-Za-zА-Яа-яЁё]+", "", text)
    return _drop_qty_tail(text)[:80]


def _has_words(name: str) -> bool:
    """Позиция без названия — потерянная сумма, лучше оставить кривое имя (его починит ИИ)."""
    return sum(1 for char in name if char.isalpha()) >= 2


def _row_amount(money: list[dict], quantity: float) -> tuple[float, float]:
    """Цена и итог товарной строки: «2х 39,89= 79,78» → (39.89, 79.78).

    Знак равенства стоит после цены — значит итог строки это цена, умноженная на
    количество. Напечатанное число после «=» годится только как подтверждение: на
    фото К&Б вместо «211.51» приходило «36.99», и верить такой ячейке нельзя.
    """
    if not money:
        return 0.0, 0.0
    anchor = next((index for index, value in enumerate(money) if "=" in value["token"]), None)
    if anchor is not None:
        price = money[anchor]["money"]
        printed = next((value["money"] for value in money[anchor + 1:] if value["money"]), None)
    elif quantity > 1 and len(money) >= 2:
        price, printed = money[-2]["money"], money[-1]["money"]
    else:
        price = printed = money[-1]["money"]
    expected = round(price * quantity, 2)
    if quantity > 1:
        if printed is not None and printed > 0 and abs(printed - expected) <= max(0.05, expected * 0.02):
            return price, printed
        return price, expected
    return price, price


def _line_item_candidates(rows: list[dict]) -> list[dict]:
    """Разбор строк кассового блока, когда детектор колонок склеил соседние строки.

    На чеках К&Б и похожих магазинов Tesseract иногда видит колонку «итого» отдельной
    строкой. Старый код тогда накапливал адрес/шапку в `pending` и превращал несколько
    товаров в одну мусорную позицию. Здесь товарная строка определяется по `#` или `1x/2x`,
    имя обрывается перед количеством, а сумма восстанавливается как цена × количество.
    """
    candidates = []
    for row in rows:
        text = _row_text(row).strip()
        lowered = text.lower()
        if not text or TOTAL_RE.search(lowered) or SKIP_ROW_RE.search(lowered):
            continue
        quantity_match = QTY_INLINE_RE.search(text)
        marked_product = text.lstrip().startswith(("#", "№"))
        # «211.51=» без количества — тоже товарная строка: знак равенства отделяет
        # цену от итога, а «1x» на сжатом фото теряется целиком.
        equation = any("=" in value["token"] for value in _row_values(row))
        # В строке-позиции может остаться только название (`#Пакет-майка 1x`),
        # а её цена распознана отдельным токеном. Такие строки сохраняем как
        # кандидаты: ниже `_merge_line_candidates` подберёт сумму по другому layout.
        if not quantity_match and not marked_product and not equation:
            continue

        values = _row_values(row)
        money = [value for value in values if value["money"]]
        quantity = float(quantity_match.group(1)) if quantity_match else 1.0
        if quantity_match:
            name = text[:quantity_match.start()]
        elif money:
            # Без «1x» название кончается там, где начинается первое число строки:
            # иначе в него уезжают колонки целиком («Вино безалк . ix 2 1 1 . 5 1 =»)
            name = " ".join(word["text"] for word in row["words"]
                            if word["right"] <= money[0]["left"] + 1) or text
        else:
            name = text
        name = re.sub(r"^[#№*\-\s]+", "", name)
        name = re.sub(r"\s+[=—-].*$", "", name).strip()
        name = _clean_name(name)
        if not _has_words(name):
            continue
        if any(marker in lowered for marker in ("ценник", "пушкарев", "док.", "инн", "кпп", "кассир", "смена", "чек:")):
            continue

        price, total = _row_amount(money, quantity)
        # Позиция без прочитанной суммы тоже кандидат: её цену может доказать итог чека
        # (в чеках К&Б правая ячейка иногда теряется целиком: «#Пакет-майка 1x»).
        candidates.append({"name": name, "qty": quantity, "price": price,
                           "sum": total, "verified": bool(total and price)})
    return candidates


def _candidate_same_name(left: str, right: str) -> bool:
    """Мягкое one-to-one сопоставление строк двух OCR-layouts."""
    first = set(re.findall(r"[a-zа-яё]{3,}", (left or "").lower().replace("ё", "е")))
    second = set(re.findall(r"[a-zа-яё]{3,}", (right or "").lower().replace("ё", "е")))
    if not first or not second:
        return False
    return bool(first & second) and (len(first & second) / min(len(first), len(second)) >= 0.5)


def _merge_line_candidates(groups: list[list[dict]]) -> list[dict]:
    """Объединяет кандидатов разных OCR-layouts: одна товарная строка — одна позиция.

    Для одинаковых названий оставляем прочитанную сумму, а не пустую строку. Если сумму
    не прочитал ни один layout, кандидат сохраняется без неё — её докажет итог чека.
    """
    merged: list[dict] = []
    for group in groups:
        for candidate in group:
            found = next((item for item in merged
                          if _candidate_same_name(item["name"], candidate["name"])), None)
            if found is None:
                merged.append(dict(candidate))
            elif candidate.get("sum", 0) > found.get("sum", 0):
                # один layout может увидеть только цену, другой — итог строки
                found.update(candidate)
    return merged


def _finalize_item(item: dict) -> dict:
    """Досчитывает количество, проверяет «цена × количество = сумма»."""
    total, price, quantity = item["sum"], item["price"], item["qty"]
    if not quantity:
        if price and price > 0 and total > price and (total / price).is_integer() \
                and 1 < total / price <= 50:
            quantity = total / price
        else:
            quantity = 1.0
    item["qty"] = float(quantity)
    if price and quantity > 1:
        expected = round(price * quantity, 2)
        if abs(expected - total) <= max(0.05, expected * 0.005):
            item["verified"] = True
        elif abs(round(total / quantity, 2) - price) <= 0.05:
            item["price"] = round(total / quantity, 2)
            item["verified"] = True
        else:
            item["verified"] = False
    else:
        item["verified"] = bool(price and abs(price - total) <= 0.02)
    return item


def _table_start(rows: list[dict], bands: dict) -> int:
    """Индекс первой строки таблицы: шапка чека (магазин, кассир, дата) — не товары.

    Если шапки с «Цена … Итого» нет, читаем чек с начала: названия позиций часто идут
    выше своей строки с числами, и отрезать их нельзя.
    """
    for index, row in enumerate(rows):
        text = _row_text(row).lower()
        if HEADER_RE.search(text) and not _row_values(row):
            return index + 1
    return 0


def _quantity_from(values: list[dict]) -> float | None:
    for value in values:
        match = QTY_RE.match(value["token"])
        if match:
            return float(match.group(1))
    return None


def _parse_items(rows: list[dict], bands: dict) -> list[dict]:
    """Собирает позиции: одна сумма в колонке «итого» — одна позиция.

    Названия и числа в чеке идут вразнобой (название может быть выше или ниже своей строки
    с числами), поэтому копим название и числа до тех пор, пока не встретим сумму позиции.
    """
    items: list[dict] = []
    pending: list[str] = []
    price = quantity = None

    for row in rows[_table_start(rows, bands):]:
        text = _row_text(row)
        if not text:
            continue
        lowered = text.lower()
        if TOTAL_RE.search(lowered) and _row_values(row):
            break  # ниже строки «ИТОГ» идут реквизиты, а не товары
        if SKIP_ROW_RE.search(lowered):
            continue
        if HEADER_RE.search(lowered) and not _row_values(row):
            # Шапка таблицы рвёт название: «Цена Скидка Кол-во Итого» — не часть товара
            pending = []
            continue

        names, values = _split_columns(row, bands)
        if names:
            pending.extend(names)
        if values["price"] and not price:
            price = values["price"][-1]["money"]
        if values["qty"] and quantity is None:
            quantity = _quantity_from(values["qty"])

        if not values["sum"]:
            continue
        value = values["sum"][-1]  # самое правое число в колонке = итого по позиции
        # берём последние токены: в начале буфера скапливается шапка чека
        name = _clean_name(pending[-NAME_TOKEN_LIMIT:])
        pending, current_price, current_quantity = [], price, quantity
        price = quantity = None
        if value["money"] and _has_words(name) and 0 < value["money"] < 1_000_000:
            items.append({"name": name, "qty": current_quantity,
                          "price": current_price or 0.0,
                          "sum": round(value["money"], 2), "_sum_word": value})
    return items


def _fallback_items(rows: list[dict]) -> list[dict]:
    """Построчный разбор для чеков без выраженной таблицы: последнее число строки — сумма."""
    items, pending = [], []
    for row in rows:
        text = _row_text(row)
        if not text:
            continue
        lowered = text.lower()
        values = _row_values(row)
        money = [value["money"] for value in values if value["money"] is not None]
        if TOTAL_RE.search(lowered) or SKIP_ROW_RE.search(lowered):
            continue
        if not money:
            pending.extend(_clean_name([word["text"] for word in row["words"]]).split())
            continue
        name = _clean_name(pending + [word["text"] for word in row["words"]
                                      if not _is_value_token(word["text"])])
        pending = []
        if not _has_words(name) or max(money) < 1:
            continue  # меньше рубля — это ошибка чтения колонки, а не товар
        item = {"name": name, "sum": money[-1], "price": money[-2] if len(money) > 1 else 0.0,
                "qty": None, "_sum_word": values[-1]}
        items.append(_finalize_item(item))
    return items


def _verify_items(items: list[dict], gray) -> list[dict]:
    """Арифметическая проверка: при расхождении перечитываем ячейку суммы крупным планом."""
    for item in items:
        word = item.pop("_sum_word", None)
        _finalize_item(item)
        if item.get("verified") or not item["price"] or item["qty"] <= 1:
            continue
        expected = round(item["price"] * item["qty"], 2)
        again = _reread_cell(gray, word)
        if again and abs(again - expected) <= max(0.05, expected * 0.01):
            item["sum"] = round(again, 2)
            item["verified"] = True
    return items


# ─── Итог и выбор лучшего варианта ───────────────────────────────────────

def _find_total(rows: list[dict], bands: dict | None) -> float | None:
    candidates = [value for row in rows if TOTAL_RE.search(_row_text(row).lower())
                  for value in _row_values(row) if value["money"]]
    if not candidates:
        return None
    if bands:
        column = [value for value in candidates
                  if _assign_column(value["right"], bands) == "sum"]
        if column:
            return round(max(value["money"] for value in column), 2)
    return round(max(value["money"] for value in candidates), 2)


def _gap_to_total(parsed: dict) -> float | None:
    """Необъяснённый остаток к итогу чека. None — итог не прочитан, судить не о чем."""
    total, items = parsed.get("total"), parsed.get("items") or []
    if not total or not items:
        return None
    return abs(sum(item["sum"] for item in items) - total)


def _explained(parsed: dict) -> bool:
    """Разбор закрыт: позиции сходятся с итогом и похожи на товары, а не на шапку."""
    items = parsed.get("items") or []
    if not items or not parsed.get("total"):
        return False
    if any(item.get("sum", 0) <= 0 or not _is_product_name(item["name"]) for item in items):
        return False
    return (_gap_to_total(parsed) or 0.0) <= 0.01


def _is_product_name(name: str) -> bool:
    """Название похоже на товар, а не на строку шапки или реквизитов."""
    letters = sum(1 for char in name if char.isalpha())
    return letters >= 3 and letters >= sum(1 for char in name if char.isdigit())


def _name_quality(name: str) -> float:
    """Доля слов в названии: у обрывков OCR («не ее») слов нет, у товара — почти все."""
    tokens = [token for token in re.split(r"\s+", name.strip()) if token]
    if not tokens:
        return 0.0
    words = sum(1 for token in tokens
                if len(re.sub(r"[^0-9A-Za-zА-Яа-яЁё]", "", token)) >= 3)
    return words / len(tokens)


def _score_table(parsed: dict) -> float:
    """Лучший разбор — тот, который объясняет напечатанный итог чека.

    Итог печатает касса, и он не зависит от того, как OCR разобрал таблицу. Разбор,
    который его не объясняет, почти наверняка потерял или выдумал строку — а не
    «просто хуже смотрится». Осмысленные позиции нужны как второй критерий, чтобы
    один случайно совпавший итог не выигрывал у полного разбора.
    """
    items = parsed.get("items") or []
    if not items:
        return -10.0
    usable = [item for item in items if item.get("sum", 0) > 0 and _is_product_name(item["name"])]
    score = sum(_name_quality(item["name"]) for item in usable)
    score += sum(0.5 for item in usable if item.get("verified"))
    score += sum(1.0 for item in usable if item.get("corroborated"))
    # Если другой проход читает ту же строку иначе — цифре верить нельзя
    score -= sum(1.0 * min(item.get("disputed", 0), 2) for item in items)
    score -= 1.0 * (len(items) - len(usable))  # пустые и мусорные строки
    total = parsed.get("total")
    if total:
        score += 6.0  # прочитанный итог — самый надёжный признак чека
        gap = _gap_to_total(parsed) or 0.0
        if gap <= 0.01:
            score += 10.0
        else:
            score -= (gap / total) * 300.0  # каждый процент остатка — это −3 очка
    return score


def _reconciled(parsed: dict) -> bool:
    """Позиции сходятся с итогом чека — разбор можно считать удачным."""
    total, items = parsed.get("total"), parsed.get("items") or []
    if not total or not items:
        return False
    return abs(sum(item["sum"] for item in items) - total) <= max(2.0, total * 0.03)


def _item_sets(rows: list[dict], bands: dict | None, gray) -> list[list[dict]]:
    """Разборы одного OCR-прохода: табличный, построчный и «товарная строка»."""
    sets: list[list[dict]] = []
    if bands:
        table = _verify_items(_parse_items(rows, bands), gray)
        if table:
            sets.append(table)
    plain = _verify_items(_fallback_items(rows), gray)
    if plain:
        sets.append(plain)
    line_items = _line_item_candidates(rows)
    if line_items:
        sets.append(_merge_line_candidates([line_items]))
    return sets


def _sparse_parses(image) -> list[dict]:
    """Разборы с разреженным layout (PSM 11): он читает крайние правые ячейки строк."""
    try:
        rows = _group_rows(_ocr_words(image, config=SPARSE_CONFIG))
    except pytesseract.TesseractError:
        return []
    if not rows:
        return []
    bands = _column_bands(rows, image.width)
    gray = np.array(image.convert("L")) if OCR_AVAILABLE else None
    return [{"items": items, "total": _find_total(rows, bands), "rows": rows,
             "raw_text": "\n".join(_row_text(row) for row in rows),
             "columns": bool(bands), "variant": None,
             "has_total_row": any(TOTAL_RE.search(_row_text(row).lower()) for row in rows),
             "total_anchor": False}
            for items in _item_sets(rows, bands, gray)]


def _fill_blank_item(items: list[dict], total: float | None) -> list[dict]:
    """Единственная позиция с непрочитанной суммой забирает остаток чека.

    В чеках К&Б правая ячейка строки иногда теряется целиком («#Пакет-майка 1x»), но
    название и напечатанный итог чека есть. Тогда разница «итог минус остальные
    позиции» и есть цена этой строки — арифметика чека её подтверждает.
    """
    if not total or not items:
        return items
    blank = [item for item in items if item.get("sum", 0) <= 0]
    if len(blank) != 1:
        return items
    printed = round(sum(item["sum"] for item in items if item.get("sum", 0) > 0), 2)
    gap = round(total - printed, 2)
    if not 0.01 < gap <= max(2.0, total * 0.25):
        return items
    blank[0].update(sum=gap, price=gap, qty=1.0, verified=False, recovered=True)
    return items


def _mark_corroborated(parsed: dict, others: list[dict]) -> None:
    """Сверяет позиции с другими OCR-layouts: одинаковые суммы считаются голосом за,
    разные — голосом против. Один проход врёт в одной ячейке, но разные варианты
    обработки врут по-разному, поэтому согласие проходов — самый дешёвый признак правды.
    """
    for item in parsed.get("items") or []:
        same_name = [other for other in others
                     if other.get("sum", 0) > 0
                     and _candidate_same_name(item["name"], other.get("name", ""))]
        item["corroborated"] = any(abs(other["sum"] - item["sum"]) <= 0.02
                                  for other in same_name)
        item["disputed"] = sum(1 for other in same_name
                               if abs(other["sum"] - item["sum"]) > 0.02)


def _confirmed_amount(item: dict, others: list[dict]) -> bool:
    """Сумму позиции подтвердил другой OCR-layout — значит ошибка не в ней."""
    return any(abs(other.get("sum", 0) - item["sum"]) <= 0.02
               and _candidate_same_name(item["name"], other.get("name", ""))
               for other in others)


def _close_receipt(parsed: dict, others: list[dict]) -> dict:
    """Доводит одну неподтверждённую позицию, чтобы позиции сошлись с итогом чека.

    На сжатом фото остаток бывает крошечным, но одна цифра всё равно соврана
    (скажем, «2.42» вместо «2.72»). Виновника видно по тому, что другие
    OCR-layouts читают ту же строку иначе: значит ошиблись не в итоге чека, а в ней.
    """
    total, items = parsed.get("total"), parsed.get("items") or []
    if not total or not items:
        return parsed
    gap = round(total - sum(item["sum"] for item in items), 2)
    if abs(gap) <= 0.01 or abs(gap) > max(1.0, total * 0.005):
        return parsed
    loose = [item for item in items if not _confirmed_amount(item, others)]
    if len(loose) != 1:
        return parsed
    loose[0]["sum"] = round(loose[0]["sum"] + gap, 2)
    if loose[0].get("price"):
        loose[0]["price"] = round(loose[0]["price"] + gap, 2)
    loose[0]["recovered"] = True
    return parsed


def _total_candidates(parsed_sets: list[dict]) -> list[float]:
    """Возможные итоги чека: он один на весь чек, но варианты читают его по-разному.

    Одинаковые значения (в пределах копеек) схлопываются: «1234.00» и «1234.08» — один итог.
    """
    totals: list[float] = []
    for parsed in parsed_sets:
        total = parsed.get("total")
        if total and not any(abs(total - known) <= max(0.5, known * 0.002) for known in totals):
            totals.append(total)
    return totals


def _best_parse(image_path: str) -> dict:
    """Прогоняет варианты предобработки и выбирает разбор, объясняющий итог чека.

    Раньше вариант выбирался по «похожести на таблицу», а наборы строк латались
    точечными правилами под конкретные чеки — на чеках К&Б это давало мусорные позиции,
    потерянный пакет и выдуманные суммы. Здесь сравниваются сами разборы вместе с
    итогом чека, и побеждает тот, у которого позиции сходятся с напечатанным итогом.
    """
    empty = {"items": [], "total": None, "rows": [], "raw_text": "", "score": -10.0,
             "columns": False, "has_total_row": False, "total_anchor": False, "variant": None}
    parsed_sets: list[dict] = []
    images: dict[int, object] = {}
    started = time.monotonic()
    for variant, image in enumerate(_preprocess_variants(image_path)):
        if variant and time.monotonic() - started > MAX_OCR_SECONDS:
            break  # хватит вариантов: ответ важнее десятых долей точности
        try:
            words = _ocr_words(image)
        except pytesseract.TesseractError:
            continue
        if not words:
            continue
        images[variant] = image
        rows = _group_rows(words)
        bands = _column_bands(rows, image.width)
        gray = np.array(image.convert("L")) if OCR_AVAILABLE else None
        raw_text = "\n".join(_row_text(row) for row in rows)
        for items in _item_sets(rows, bands, gray):
            parsed_sets.append({
                "items": items, "total": _find_total(rows, bands), "rows": rows,
                "raw_text": raw_text, "columns": bool(bands), "variant": variant,
                "has_total_row": any(TOTAL_RE.search(_row_text(row).lower()) for row in rows),
                "total_anchor": any(TOTAL_RE.search(_row_text(row).lower())
                                    and any(value.get("money") for value in _row_values(row))
                                    for row in rows),
            })
        if any(_explained(parsed) for parsed in parsed_sets):
            break  # разбор сошёлся с итогом и без мусорных строк — искать больше нечего
    if not parsed_sets:
        return empty

    best = max(parsed_sets, key=lambda parsed: _score_table(
        {**parsed, "items": _fill_blank_item([dict(item) for item in parsed["items"]],
                                              parsed["total"])}))
    if not _explained(best) and best.get("variant") in images:
        # Разреженный layout читает правые ячейки, которые обычный режим пропустил целиком
        parsed_sets.extend(_sparse_parses(images[best["variant"]]))

    # Итог чека один на весь чек, поэтому перебираем разборы вместе с итогом: побеждает
    # то сочетание, в котором позиции объясняют итог без остатка.
    totals = _total_candidates(parsed_sets) or [None]
    best_parsed, best_score = None, None
    for total in totals:
        for parsed in parsed_sets:
            # подтверждение ищем в других проходах: тот же layout повторит ту же ошибку
            others = [item for other in parsed_sets if other.get("variant") != parsed.get("variant")
                      for item in other["items"]]
            candidate = {**parsed, "total": total or parsed.get("total")}
            candidate["items"] = _fill_blank_item([dict(item) for item in parsed["items"]],
                                                  candidate["total"])
            _mark_corroborated(candidate, others)
            _close_receipt(candidate, others)
            _mark_corroborated(candidate, others)
            score = _score_table(candidate)
            if best_score is None or score > best_score:
                best_parsed, best_score = candidate, score
    if best_parsed is None:
        return empty

    best = best_parsed
    best["score"] = best_score
    best["items"] = [item for item in best["items"] if item.get("sum", 0) > 0]
    for item in best["items"]:
        item.pop("corroborated", None)
    if best["total"] is None and best["items"]:
        # Итог не прочитан — берём сумму позиций, чтобы дальше чек считался привычно
        best["total"] = round(sum(item["sum"] for item in best["items"]), 2)
    return best


# ─── Магазин и дата ──────────────────────────────────────────────────────

HEADER_WORDS = ("кассов", "чек", "квитанц", "документ", "итог", "смен", "информац",
                "претенз", "гаранти", "адрес", "телефон", "сайт", "расчет", "тел.")
COMPANY_MARKS = ("ооо", "ип ", "оао", "зао", "пао", "ао ", "магазин", "маркет", "агроторг")
# Сети, которые в чеке напечатаны логотипом или в реквизитах: без них название магазина
# собирается из мусорных строк шапки (на фото К&Б выходило «pend PY 4»).
STORE_BRANDS = (("krasnoeibeloe", "Красное&Белое"), ("красное", "Красное&Белое"),
                ("k&b", "Красное&Белое"), ("альфа-курган", "Красное&Белое"),
                ("альфа курган", "Красное&Белое"), ("пятёроч", "Пятёрочка"),
                ("пятероч", "Пятёрочка"), ("агроторг", "Пятёрочка"), ("магнит", "Магнит"),
                ("перекрёст", "Перекрёсток"), ("лента", "Лента"), ("ашан", "Ашан"),
                ("дикси", "Дикси"), ("вкусвилл", "ВкусВилл"), ("днс", "ДНС"),
                ("м.видео", "М.Видео"), ("аптек", "Аптека"))


def _guess_store_from_text(text: str) -> str | None:
    """Название магазина: сначала известная сеть, потом строка с ООО/ИП.

    Строка шапки после OCR часто состоит из обрывков («чл, 50 лет BAKCHs»), поэтому
    кандидатов отсеиваем по доле букв — иначе название магазина — это мусор.
    """
    lowered_text = (text or "").lower()
    for marker, brand in STORE_BRANDS:
        if marker in lowered_text:
            return brand
    candidates = []
    for line in [line.strip() for line in text.split("\n") if len(line.strip()) > 3][:14]:
        lowered = line.lower()
        if any(word in lowered for word in HEADER_WORDS):
            continue
        if re.search(r"\d{6,}", line):
            continue
        letters = sum(1 for char in line if char.isalpha())
        if letters < 3 or letters < len(line) * 0.5:  # обрывки OCR — не название
            continue
        candidates.append(line)
    if not candidates:
        return None
    for line in candidates:
        if any(mark in line.lower() for mark in COMPANY_MARKS):
            return line[:60]
    return candidates[0][:60]


DATE_RE = re.compile(r"\b(\d{2})[.\-/](\d{2})[.\-/](\d{2,4})\b(?:\s+(\d{2}:\d{2}))?")


def _find_date(rows: list[dict]) -> str | None:
    """Дата чека: сначала рядом с кассиром/приходом, потом первая дата со временем."""
    texts = [_row_text(row) for row in rows]
    for text in texts:
        if re.search(r"(кассир|приход|администратор)", text, re.IGNORECASE):
            match = DATE_RE.search(text)
            if match:
                return match.group(0)
    for text in texts:
        match = DATE_RE.search(text)
        if match and match.group(4):
            return match.group(0)
    for text in texts:
        match = DATE_RE.search(text)
        if match:
            return match.group(0)
    return None


# ─── Публичный интерфейс ─────────────────────────────────────────────────

def _extract_receipt_data_sync(image_path: str) -> dict:
    best = _best_parse(image_path)
    return {
        "total": best["total"],
        "store": _guess_store_from_text(best["raw_text"]),
        "date": _find_date(best["rows"]),
        "items": best["items"],
        "raw_text": best["raw_text"].strip(),
        "columns": best.get("columns", False),
        "has_total_row": best.get("has_total_row", False),
        "verified": sum(1 for item in best["items"] if item.get("verified")),
    }


def table_rows_text(items: list[dict]) -> str:
    """Строки таблицы для ИИ: числа уже разобраны, модели остаются названия и категории."""
    lines = []
    for index, item in enumerate(items, start=1):
        quantity = f" x{item['qty']:.0f}" if item.get("qty", 1) and item["qty"] != 1 else ""
        lines.append(f"{index}. {item['name']} | {item['price']:.2f}{quantity} = {item['sum']:.2f}")
    return "\n".join(lines)


async def extract_receipt_data(image_path: str) -> dict:
    """Асинхронная обёртка: Tesseract работает в отдельном потоке."""
    empty = {"total": None, "store": None, "date": None, "items": [], "raw_text": "",
             "columns": False, "has_total_row": False, "verified": 0}
    if not OCR_AVAILABLE:
        return empty
    try:
        return await asyncio.to_thread(_extract_receipt_data_sync, image_path)
    except pytesseract.TesseractNotFoundError:
        return {**empty, "raw_text": f"Tesseract не найден по пути: {TESSERACT_CMD}"}
    except Exception:
        return empty


# Категория по позициям: логотип магазина с фото часто не читается, а товары — да.
ITEM_CATEGORY_MARKERS = (
    ("техника", ("корпус", "процессор", "видеокарт", "материнск", "блок питания", "термопаст",
                  "кулер", "оператив", "ноутбук", "монитор", "клавиатур", "мыш", "наушник",
                  "роутер", "планшет", "картридж", "ssd", "hdd", "zalman", "deepcool",
                  "logitech", "hyperx", "kingston", "rtx", "ryzen", "смартфон")),
    ("еда", ("молок", "кефир", "хлеб", "батон", "булк", "сыр", "творог", "сметан", "масло",
              "йогурт", "куриц", "свинин", "говядин", "колбас", "сосиск", "рыб", "яйц",
              "картоф", "помидор", "огурц", "яблок", "банан", "круп", "рис", "макарон",
              "сахар", "соль", "чай", "кофе", "вода", "сок", "кетчуп", "майонез",
              "печенье", "конфет", "шоколад")),
    ("досуг", ("пиво", "вино", "водка", "коньяк", "виски", "шампанск", "сидр", "джин",
                "настойк", "чипс", "сухарик", "снек", "попкорн", "зажигалк", "билет")),
    ("здоровье", ("таблетк", "капсул", "сироп", "мазь", "бинт", "пластыр", "витамин", "аптек")),
    ("транспорт", ("бензин", "дизель", "аи-9", "топлив")),
    ("одежда", ("футболк", "носк", "трус", "куртк", "джинс", "кофт", "перчатк", "шарф")),
)


def guess_category_from_items(items: list[dict]) -> str | None:
    """Категория чека по товарам — фолбэк, когда магазин не распознан.

    На размытом фото логотип сети теряется полностью («Ny pa» вместо «ДНС»), а названия
    позиций остаются: корпус, блок питания, молоко. Считаем попадания по маркерам и
    берём категорию с большинством совпадений.
    """
    names = " ".join((item.get("name") or "").lower() for item in items or [])
    if not names:
        return None
    best_category, best_hits = None, 0
    for category, markers in ITEM_CATEGORY_MARKERS:
        hits = sum(1 for marker in markers if marker in names)
        if hits > best_hits:
            best_category, best_hits = category, hits
    return best_category


def guess_category_from_store(store: str) -> str:
    """Фолбэк-категория по названию магазина (когда ИИ недоступен)."""
    store_lower = (store or "").lower()
    electronics = ["днс", "dns", "м.видео", "мвидео", "mvideo", "ситилинк", "citilink",
                   "эльдорадо", "технопоинт", "регард", "onlinetrade"]
    food_stores = ["пятёрочка", "пятерочка", "агроторг", "магнит", "перекресток", "лента", "ашан",
                   "вкусвилл", "дикси", "озеро", "монетка", "бристоль", "фикс прайс", "fix price"]
    gas_stations = ["лукойл", "газпром", "роснефть", "татнефть", "бп", "shell", "азс"]
    cafe = ["кофейн", "старбакс", "mcdonald", "вкусно и точка", "бургер", "пузата", "городе"]
    pharmacy = ["аптек", "апрель", "ригла", "горздрав"]
    clothing = ["wildberries", "ozon", "ламода", "спортмастер", "zara", "h&m"]

    for store_name in cafe:
        if store_name in store_lower:
            return "досуг"
    for store_name in electronics:
        if store_name in store_lower:
            return "техника"
    for store_name in food_stores:
        if store_name in store_lower:
            return "еда"
    for store_name in gas_stations:
        if store_name in store_lower:
            return "транспорт"
    for store_name in pharmacy:
        if store_name in store_lower:
            return "здоровье"
    for store_name in clothing:
        if store_name in store_lower:
            return "одежда"
    return "прочее"


def receipt_path(file_unique_id: str) -> str:
    os.makedirs(RECEIPTS_DIR, exist_ok=True)
    return f"{RECEIPTS_DIR}/{file_unique_id}.jpg"
