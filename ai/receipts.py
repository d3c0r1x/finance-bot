"""Разбор фото чека: модель зрения (VLM) + Tesseract → сведение → категория от ИИ.

Два независимых чтения одного фото:

* **модель зрения** (`ai/vision.py`, qwen3-vl) видит чек целиком — понимает таблицу, читает
  блёклый и наклонный текст, чинит названия по-человечески. Она основа разбора;
* **Tesseract** (`ai/ocr.py`) даёт цифры с координатами и колонки. Он не «понимает» чек,
  зато его арифметику можно проверить: цена × количество = сумма, сумма позиций = итог.

Сведение (`_merge_readings`): берём чтение, у которого позиции сходятся с итогом, а если
сходятся оба — то, где больше позиций. Tesseract попутно подтверждает или поправляет числа.

Возвращает магазин, итог, дату, категорию, позиции и признак продуктового чека.
"""
import asyncio
import re
from difflib import SequenceMatcher

from ai.llm import is_advice_filler, structure_receipt
from ai.ocr import (extract_receipt_data, guess_category_from_items, guess_category_from_store,
                    looks_like_item_line, table_rows_text)
from ai.vision import encoded_variants, read_receipt, resolve_vision_model, unload
from config import VISION_SECOND_PASS, VISION_UNLOAD_CHAT
from utils.formatting import format_amount, md_safe, plural_ru

LOW_QUALITY_HINT = (
    "Не смог уверенно прочитать чек. Сфотографируй его целиком, ровно и при хорошем свете — "
    "или просто напиши сумму текстом, например: «Магазин 5000»."
)

# Категория чека дополнительно проверяется правилами, а не принимается вслепую от VLM.
# Это важно для смешанной корзины: один ошибочный ответ модели не должен раздуть «еду».
RECEIPT_ALCOHOL_MARKERS = (
    "пив", "жигул", "балтик", "водк", "вин", "шампан", "коньяк", "виск", "ром", "джин",
    "сидр", "ликёр", "ликер", "алкогол",
)
RECEIPT_LEISURE_MARKERS = (
    "чипс", "lays", "лейс", "сухарик", "кириешк", "снек", "попкорн", "энергетик",
    "энергет", "игрушк", "игра", "сувенир",
)


def _receipt_leisure_share(result: dict) -> tuple[float, float]:
    """Возвращает долю алкоголя и всей явно развлекательной части чека."""
    total = float(result.get("total") or 0)
    if not total:
        return 0.0, 0.0
    alcohol = leisure = 0.0
    for item in result.get("items") or []:
        name = (item.get("name") or "").lower()
        amount = float(item.get("sum") or 0)
        if any(marker in name for marker in RECEIPT_ALCOHOL_MARKERS):
            alcohol += amount
            leisure += amount
        elif any(marker in name for marker in RECEIPT_LEISURE_MARKERS):
            leisure += amount
    return alcohol / total, leisure / total


def _apply_receipt_category_rules(result: dict) -> dict:
    """Уточняет досуг по позициям после VLM/OCR.

    Алкоголь от 10% чека или развлечения от 25% — достаточный сигнал, чтобы весь чек
    не маскировался под «еду». При меньшей доле сохраняем «еду», но оставляем leisure=True
    и подсказку/кнопку для ручного выбора.
    """
    alcohol_share, leisure_share = _receipt_leisure_share(result)
    if not alcohol_share and not leisure_share:
        return result
    result["leisure"] = True
    if alcohol_share >= 0.10 or leisure_share >= 0.25:
        result["category"] = "досуг"
    result["leisure_share"] = round(leisure_share, 4)
    return result

# Вердикты разбора корзины: эмодзи и подписи
VERDICT_STYLE = {
    "полезно": ("✅", "Полезно"),
    "нейтрально": ("➖", "Нейтрально"),
    "вредно": ("❌", "Лучше сократить"),
    "лишнее": ("🗑", "Можно было не брать"),
}
VERDICT_ORDER = ("вредно", "лишнее", "нейтрально", "полезно")

# Похожие друг на друга символы: OCR часто путает латиницу и кириллицу
LOOKALIKE = str.maketrans({
    "a": "а", "c": "с", "e": "е", "o": "о", "p": "р", "x": "х", "y": "у", "k": "к",
    "m": "м", "t": "т", "h": "н", "b": "в", "0": "о", "3": "з", "d": "д", "n": "н",
    "s": "с", "r": "р", "u": "и", "g": "г", "l": "л", "v": "в",
})


def _normalize(text: str) -> str:
    """Оставляет только буквы: убирает пробелы, регистр, похожие символы."""
    lowered = (text or "").lower().translate(LOOKALIKE)
    return re.sub(r"[^а-яё]", "", lowered)


def _supported(candidate: str, raw_text: str, threshold: float = 0.6) -> bool:
    """Есть ли название магазина в тексте чека (с учётом кривого OCR).

    Нужно, чтобы модель не подставляла знакомую сеть, которой в чеке нет.
    """
    needle = _normalize(candidate)
    haystack = _normalize(raw_text)
    if not needle:
        return False
    if needle in haystack:
        return True
    window = len(needle)
    for start in range(0, max(1, len(haystack) - window + 1)):
        chunk = haystack[start:start + window]
        if SequenceMatcher(None, needle, chunk).ratio() >= threshold:
            return True
    return False


# Частые сети: кривое OCR-название приводим к каноничному
KNOWN_STORES = ("ДНС", "ДНС Ритейл", "М.Видео", "Ситилинк", "Эльдорадо", "Технопоинт",
                "Пятёрочка", "Магнит", "Лента", "Ашан", "ВкусВилл", "Дикси", "Перекрёсток",
                "Fix Price", "Wildberries", "Ozon", "Спортмастер", "Аптека Апрель")

NOISE_WORDS = ("кассов", "чек", "квитанц", "документ", "итог", "смен", "информац",
               "претенз", "сервис", "гарант", "адрес", "телефон", "сайт", "расчет", "мест") 


def _looks_like_store(name: str) -> bool:
    """Отсеивает служебные строки чека («кассовый чек», «итог», адреса)."""
    normalized = _normalize(name)
    if len(normalized) < 3:
        return False
    lowered = (name or "").lower()
    return not any(word in lowered for word in NOISE_WORDS)


def _clean_store_line(line: str) -> str:
    """Из строки вида «000 "анС Ри Фил" 00008 Por Ы» достаёт название компании."""
    text = re.sub(r"^\s*(ооо|000|оао|зао|пао|ао|ип)\s*", "", line or "", flags=re.IGNORECASE)
    quoted = re.search(r"[«\"“”']([^»\"“”']{3,})[»\"“”']", text)
    if quoted:
        text = quoted.group(1)
    text = re.sub(r"[«»\"“”']", "", text)
    text = re.sub(r"\s+\d{3,}.*$", "", text)          # хвост с длинными числами
    text = re.sub(r"\s+[A-Za-zА-Яа-я]{1,3}\s*$", "", text)  # короткие обрывки вроде «Por Ы»
    return text.strip()[:40]


def canonical_store(name: str) -> str:
    """Если кривое название похоже на известную сеть — возвращаем её каноничное имя."""
    normalized = _normalize(name)
    if not normalized:
        return name
    best_name, best_ratio = name, 0.0
    for store in KNOWN_STORES:
        ratio = SequenceMatcher(None, normalized, _normalize(store)).ratio()
        if ratio > best_ratio:
            best_name, best_ratio = store, ratio
    return best_name if best_ratio >= 0.55 else name


def _date_from_text(date: str, raw_text: str) -> bool:
    """Дата модели должна быть в тексте чека (по цифрам)."""
    digits = re.sub(r"\D", "", date or "")
    if len(digits) < 4:
        return False
    raw_digits = re.sub(r"\D", "", raw_text)
    return digits in raw_digits or digits[:4] in raw_digits


def _accept_store(candidate: str | None, raw_text: str, ocr_store: str = "") -> bool:
    """Верить ли названию магазина от модели.

    Строгая проверка — название должно быть в тексте чека (OCR часто ломает его).
    Послабление — известная сеть при наличии строки юрлица («ООО», «000», «ИП»):
    название в чеке есть, но OCR прочитал его нечитаемо («anc pirenn» вместо «ДНС Ритейл»).
    Если же OCR уже узнал другую известную сеть — модель ошиблась, верим OCR.
    """
    if not candidate or not _looks_like_store(candidate):
        return False
    if _supported(candidate, raw_text):
        return True
    # послабление только для длинных названий: короткие («Магнит», «ДНС») модель легко выдумывает
    if len(candidate.strip()) < 7:
        return False
    if not (canonical_store(candidate) != candidate or candidate in KNOWN_STORES):
        return False
    ocr_known = canonical_store(ocr_store) if ocr_store else ""
    if ocr_known and ocr_known != ocr_store \
            and _normalize(ocr_known) != _normalize(canonical_store(candidate)):
        return False
    return bool(re.search(r"\b(ооо|оао|зао|пао|ип|000|ao)\b", (raw_text or "").lower()))


def _reading_total(reading: dict) -> float:
    return round(sum(item["sum"] for item in reading.get("items") or []), 2)


def _reconciled(reading: dict, tolerance: float = 0.03) -> bool:
    """Позиции чтения сходятся с его итогом (сравнивать есть с чем)."""
    total = reading.get("total")
    items = reading.get("items") or []
    if not total or not items:
        return False
    return abs(_reading_total(reading) - total) <= max(2.0, total * tolerance)


# Порог «позиции всё ещё не сошлись с итогом»: 1% чека, но не меньше 5 ₽.
# Он строже того, о котором предупреждает карточка: расхождение в 50 ₽ на чеке в 1 800 ₽
# карточка ещё принимает, а вот второй проход запустить стоит.
REPAIR_GAP = 0.01
REPAIR_GAP_MIN = 5.0


def _still_off(reading: dict) -> bool:
    """Позиции чтения не сходятся с его итогом настолько, что стоит перечитать чек."""
    total = reading.get("total")
    items = [item for item in reading.get("items") or [] if item.get("sum")]
    if not total or not items:
        return False
    return abs(sum(item["sum"] for item in items) - total) > max(REPAIR_GAP_MIN,
                                                                 total * REPAIR_GAP)


def _reading_gap(reading: dict) -> float:
    """Насколько позиции чтения расходятся с итогом чека (в рублях, по модулю)."""
    total = reading.get("total")
    items = reading.get("items") or []
    if not total or not items:
        return float("inf")
    return abs(sum(item["sum"] for item in items) - total)


def _better_vision(first: dict | None, second: dict | None) -> dict | None:
    """Из двух чтений модели зрения берёт то, что ближе к итогу чека (иначе — где больше позиций)."""
    if not first:
        return second
    if not second:
        return first
    first_gap, second_gap = _reading_gap(first), _reading_gap(second)
    if abs(first_gap - second_gap) > 0.5:
        return first if first_gap < second_gap else second
    first_items, second_items = len(first.get("items") or []), len(second.get("items") or [])
    return first if first_items >= second_items else second


def _merge_readings(vision: dict | None, ocr: dict) -> dict:
    """Сводит два чтения одного чека в одно, самое достоверное.

    Кто точнее, решает арифметика, а не уверенность модели: у верного чтения сумма
    позиций равна итогу чека. Равные — у кого больше позиций (значит, меньше потеряно).
    """
    ocr_reading = {"total": ocr.get("total"), "items": [i for i in ocr.get("items") or [] if i.get("name")]}
    merged = dict(ocr)
    merged["parsed_by"] = "ocr"
    merged["reader"] = "ocr"
    merged["vision_total"] = (vision or {}).get("total")
    merged["ocr_total"] = ocr.get("total")
    if not vision or not vision.get("items"):
        return merged

    vision_reading = {"total": vision.get("total"), "items": vision["items"]}
    vision_ok, ocr_ok = _reconciled(vision_reading), _reconciled(ocr_reading)
    if vision_ok and not ocr_ok:
        take_vision = True
    elif ocr_ok and not vision_ok:
        take_vision = False
    elif vision_ok and ocr_ok:
        # сходятся оба — предпочитаем то, где позиций больше (полнее чек)
        take_vision = len(vision_reading["items"]) >= len(ocr_reading["items"])
    else:
        # не сходится ни у кого: у модели зрения полнее строки, у OCR честнее арифметика
        take_vision = len(vision_reading["items"]) > len(ocr_reading["items"])
        if not take_vision and vision.get("total") and ocr_reading["items"]:
            # позиции OCR не сходятся с итогом, а модель итог прочитала — верим модели
            take_vision = abs(_reading_total(ocr_reading) - vision["total"]) > \
                abs(_reading_total(ocr_reading) - ocr_reading["total"])

    if take_vision:
        merged["items"] = vision_reading["items"]
        merged["parsed_by"] = "vision"
        merged["reader"] = "vision"
        merged["total"] = vision.get("total") or _reading_total(vision_reading)

    # Числа модели зрения проверяем по OCR: если в OCR нашлась та же позиция с той же суммой,
    # помечаем её подтверждённой; если сумма отличается — берём ту, что сходится с итогом.
    if take_vision and merged.get("total") and ocr_reading["items"]:
        merged["items"] = _cross_check(merged["items"], ocr_reading["items"], merged["total"])
    return merged


def _same_item(left: str, right: str) -> bool:
    """Похожие названия одной и той же позиции (OCR и модель пишут по-разному)."""
    first, second = _normalize(left), _normalize(right)
    if not first or not second:
        return False
    if first in second or second in first:
        return True
    return SequenceMatcher(None, first, second).ratio() >= 0.62


def _gap_fill(items: list[dict], ocr_items: list[dict], total: float) -> list[dict]:
    """Дотягивает пропущенные позиции по арифметике чека.

    Сначала ищется точная комбинация из 1–3 OCR-строк, а не только одна ближайшая цена.
    Это защищает от ситуации, когда недостача 120 ₽ состоит из двух товаров по 60 ₽.
    Сопоставление уже использованных строк запрещает повторно добавить один SKU.
    """
    if not total or not items:
        return list(items)
    filled = list(items)
    gap = round(total - sum(item["sum"] for item in filled), 2)
    if gap <= max(3.0, total * 0.01):
        return filled
    # Товаром считается только неслужебная строка: обрывок названия («Пиво ЖИГУЛ.ФИРМ.»)
    # это всё ещё товар, а «СДАЧА», «ИТОГ» и чистые цифры — нет. Иначе недостачу закрывает
    # служебная строка и в чеке появляется позиция, которой покупатель не покупал.
    unmatched = [other for other in ocr_items
                 if other.get("sum") and looks_like_item_line(other.get("name", ""))
                 and not any(_same_item(item["name"], other["name"]) for item in filled)]
    if not unmatched:
        return filled

    # Маленькая bounded subset-sum: достаточно для пропущенных строк, не превращаем OCR
    # в перебор всех комбинаций на длинном чеке.
    candidates: list[tuple[float, list[dict]]] = []
    for first_index, first in enumerate(unmatched):
        candidates.append((first["sum"], [first]))
        for second_index in range(first_index + 1, len(unmatched)):
            second = unmatched[second_index]
            candidates.append((first["sum"] + second["sum"], [first, second]))
            for third_index in range(second_index + 1, len(unmatched)):
                third = unmatched[third_index]
                candidates.append((first["sum"] + second["sum"] + third["sum"],
                                   [first, second, third]))
    best_sum, best = min(candidates, key=lambda pair: abs(round(pair[0] - gap, 2)))
    if abs(round(best_sum - gap, 2)) > max(5.0, gap * 0.18):
        return filled
    for candidate in best:
        filled.append({"name": candidate["name"],
                       "qty": candidate.get("qty") or 1,
                       "price": candidate.get("price") or candidate["sum"],
                       "sum": round(candidate["sum"], 2),
                       "recovered": True,
                       "verified": False})
    return filled


def _cross_check(items: list[dict], ocr_items: list[dict], total: float) -> list[dict]:
    """Сверяет позиции модели зрения с числами Tesseract по принципу one-to-one.

    Одна OCR-строка может подтверждать только один товар. Это важно для двух одинаковых
    товаров: нельзя использовать одну цену дважды и пометить оба товара уверенными.
    """
    used: set[int] = set()
    for item in items:
        matched_index = next((index for index, other in enumerate(ocr_items)
                              if index not in used and _same_item(item["name"], other["name"])), None)
        matched = ocr_items[matched_index] if matched_index is not None else None
        if matched_index is not None:
            used.add(matched_index)
        if matched and abs(matched["sum"] - item["sum"]) <= 0.02:
            item["verified"] = True
        elif matched and abs(matched["sum"] - item["sum"]) > max(1.0, item["sum"] * 0.05):
            current_total = _reading_total({"items": items})
            current_gap = abs(current_total - total)
            # Проверяем именно замену этой строки: не вычитаем цену дважды.
            alternative_total = current_total - item["sum"] + matched["sum"]
            alternative_gap = abs(alternative_total - total)
            if alternative_gap < current_gap:
                item["sum"] = round(matched["sum"], 2)
                item["verified"] = True
        if item.get("qty", 1) and item.get("price"):
            expected = round(item["price"] * item["qty"], 2)
            if abs(expected - item["sum"]) <= max(0.05, expected * 0.01):
                item["verified"] = True
    return _gap_fill(items, ocr_items, total)


async def _release_vision(vision_model: str | None) -> None:
    """Выгружает модель зрения, когда чтение чека закончено.

    Симметрично выгрузке текстовой модели перед чтением: `read_receipt` освобождает VRAM
    под зрение, здесь — обратно под разбор. Держать модель зрения в памяти нельзя: текстовая
    тогда уезжает на CPU, и следующий разбор растягивается на минуты (по умолчанию модель
    висит в памяти две минуты — ровно то окно, в котором обычный разбор успевает начаться).
    """
    if not (vision_model and VISION_UNLOAD_CHAT):
        return
    try:
        await unload(vision_model)
    except Exception:
        pass  # модель выгрузится сама по keep_alive, разбор блокировать не должно


async def _reload_text_model() -> None:
    """Возвращает текстовую модель в память после чтения чека моделью зрения."""
    from ai.llm import resolve_model
    from ai.vision import warmup

    try:
        model = await resolve_model(force_refresh=True)
        if model:
            await warmup(model, "30m")
    except Exception:
        pass  # модель поднимется сама при следующем разборе


async def _swap_models_back(vision_model: str | None, vision_read: bool) -> None:
    """После чтения чека: выгружает зрение и возвращает текстовую модель — по порядку.

    Обе операции в одной фоновой задаче, чтобы warmup не стартовал раньше выгрузки:
    они меняют одну и ту же память, и одновременный запуск ломает обе.
    """
    await _release_vision(vision_model)
    if vision_read:
        await _reload_text_model()


async def _read_with_vision(image_path: str, prepared: dict[str, str] | None) -> tuple[dict | None, int]:
    """Первое чтение чека моделью зрения.

    Если модель вовсе не ответила (например, не хватило видеопамяти на первом варианте),
    пробуем ещё раз другим вариантом подготовки и с промптом про таблицу позиций.
    Второй проход из-за расхождения в суммах делается позже — в `parse_receipt`: сначала
    дешёвая сверка и добор потерянных позиций по Tesseract, и только если этого не хватило.
    """
    first = await read_receipt(image_path, variant="crop", prepared=prepared)
    if first or not VISION_SECOND_PASS:
        return first, 1
    return await read_receipt(image_path, variant="clean", purpose="repair",
                              prepared=prepared), 2


async def parse_receipt(image_path: str) -> dict:
    """Полный разбор чека по фото: модель зрения + Tesseract, затем сведение."""
    vision_model = await resolve_vision_model()
    prepared = None
    if vision_model:
        try:  # фото готовится один раз и используется обоими проходами модели зрения
            prepared = encoded_variants(image_path)
        except Exception:
            prepared = None

    vision_task = asyncio.create_task(_read_with_vision(image_path, prepared))
    raw = await extract_receipt_data(image_path)
    vision, vision_passes = await vision_task
    raw_text = (raw.get("raw_text") or "").strip()

    result = {
        "total": raw.get("total"),
        "store": raw.get("store"),
        "date": raw.get("date"),
        "category": None,
        "items": [item for item in raw.get("items") or [] if item.get("name")],
        "is_grocery": False,
        "leisure": False,
        "raw_text": raw_text,
        "parsed_by": "ocr",
        "readable": len(raw_text) >= 40,
        "columns": raw.get("columns", False),
        "has_total_row": raw.get("has_total_row", False),
    }
    base = dict(result)                     # чтение Tesseract — база для повторного сведения
    if vision:
        result = _merge_readings(vision, result)

    # Позиции всё ещё не сошлись с итогом — пробуем перечитать чек вторым проходом
    if VISION_SECOND_PASS and vision_model and prepared and _still_off(result):
        repair = await read_receipt(image_path, variant="clean", purpose="repair",
                                    prepared=prepared)
        if repair:
            vision_passes += 1
            alternative = _merge_readings(_better_vision(vision, repair), base)
            if _reading_gap(alternative) < _reading_gap(result):
                result = alternative

    result["vision_model"] = (vision or {}).get("model") or vision_model
    result["vision_passes"] = vision_passes
    result["vision_error"] = "" if vision else (
        "модель зрения не ответила (таймаут или не хватило видеопамяти)" if vision_model
        else "модель зрения недоступна")

    # Магазин, дата и категория от модели зрения — самые надёжные (она видит фото целиком)
    if vision:
        if _accept_store(vision.get("store"), raw_text or vision.get("store") or "", raw.get("store") or ""):
            result["store"] = vision["store"]
        elif not result["store"] and vision.get("store"):
            result["store"] = vision["store"] if _looks_like_store(vision["store"]) else None
        if not result["date"] and vision.get("date") \
                and _date_from_text(vision["date"], raw_text or vision["date"]):
            result["date"] = vision["date"]
        result["category"] = vision.get("category")
        result["is_grocery"] = bool(vision.get("is_grocery"))
        result["leisure"] = bool(vision.get("leisure"))

    # Чтение закончено: сначала освобождаем VRAM, и только после выгрузки возвращаем
    # текстовую модель — наоборот warmup убил бы только что выгружаемую модель.
    if VISION_UNLOAD_CHAT:
        asyncio.create_task(_swap_models_back(vision_model, bool(vision)))

    if not raw_text and not vision:
        return result

    # Второй проход текстовой моделью: она не трогает числа, а разворачивает сокращения
    # чека в человеческие названия («Пиво ЖИГУЛ.ФИРМ.св.ПЭТ 1.35л» → «Пиво Жигулёвское 1.35 л»).
    table = table_rows_text(result["items"])
    structured = await structure_receipt(raw_text or table, table)
    if structured:
        if result.get("parsed_by") == "ocr":
            result["parsed_by"] = "ai"
        if not result.get("store") and _accept_store(structured.get("store"), raw_text,
                                                     raw.get("store") or ""):
            result["store"] = structured["store"]
        # Дату берём из текста: модель любит дописывать свой год
        if not result["date"] and structured.get("date") and _date_from_text(structured["date"], raw_text):
            result["date"] = structured["date"]
        if not result["category"]:
            result["category"] = structured.get("category")
        result["is_grocery"] = bool(result["is_grocery"] or structured.get("is_grocery"))
        result["leisure"] = bool(result["leisure"] or structured.get("leisure"))
        # Модель только чинит названия — суммы и количества остаются от чтения
        for index, item in enumerate(result["items"], start=1):
            better = (structured.get("names") or {}).get(index)
            if better and not _looks_like_store_only_name(better):
                item["name"] = better

    if result["store"]:
        result["store"] = canonical_store(_clean_store_line(result["store"]))
    if result["store"] and not _looks_like_store(result["store"]):
        result["store"] = None

    # Итог на чеке прочитать не удалось: показываем сумму позиций, но помечаем её как
    # посчитанную, а не напечатанную кассой — иначе такая сумма выглядит как факт с чека.
    result["total_estimated"] = False
    if result["total"] is None and result["items"]:
        result["total"] = round(sum(item["sum"] for item in result["items"]), 2)
        result["total_estimated"] = True
    if not result["category"]:
        # Магазин может быть не распознан (логотип на фото размыт), а товары видно:
        # без этого фолбэка технический чек уезжал в «прочее».
        store_category = guess_category_from_store(result.get("store") or "")
        item_category = guess_category_from_items(result["items"])
        result["category"] = item_category or store_category
        # Продуктовый чек остаётся продуктовым, даже если покупка в основном алкогольная:
        # «досуг» — это про долю алкоголя в чеке, а не про тип магазина.
        result["is_grocery"] = store_category == "еда" or item_category == "еда"

    # Последняя страховка после всех источников: VLM могла назвать смешанный чек едой,
    # а Tesseract — не дать категории вообще. Решение принимаем по фактическим позициям.
    result = _apply_receipt_category_rules(result)

    # Сумма позиций не сходится с итогом — значит часть цен прочитана неверно
    items_total = sum(item["sum"] for item in result["items"])
    result["items_total"] = round(items_total, 2)
    result["items_mismatch"] = bool(
        result["items"] and result["total"]
        and abs(items_total - result["total"]) > max(3.0, result["total"] * 0.03)
    )
    # Позиции дороже чека — самая опасная ошибка чтения: строка посчитана дважды или
    # пришла из соседней колонки. Итог печатает касса, поэтому верим ему, а не им.
    result["over_total"] = bool(
        result["items"] and result["total"]
        and items_total - result["total"] > max(3.0, result["total"] * 0.03)
    )
    return result


def _looks_like_store_only_name(name: str) -> bool:
    """Модель иногда возвращает в названии товара мусор вида «—», «нет». Отсеиваем."""
    cleaned = (name or "").strip(" -—–:.")
    return len(cleaned) < 2


def items_summary(items: list[dict], limit: int = 6) -> str:
    """Короткий список позиций для карточки чека."""
    lines = []
    for item in items[:limit]:
        quantity = f"{item.get('qty', 1):.0f} × " if item.get("qty", 1) > 1 else ""
        lines.append(f"   • {quantity}{item['name']} — {item['sum']:.0f} ₽")
    if len(items) > limit:
        lines.append(f"   … и ещё {len(items) - limit}")
    return "\n".join(lines)


def item_origin(item: dict) -> str:
    """Откуда взялась цифра позиции — видно в карточке, и это не всегда «прочитано».

    agreed — ту же сумму прочитал другой проход OCR (два независимых чтения),
    recovered — название прочитано с чека, а цену дал остаток чека,
    corrected — цену прочитали, но пересчитали по остатку чека,
    verified — арифметика строки сошлась (цена × количество = сумма),
    read — цифра взята как есть.
    """
    if item.get("recovered"):
        return "recovered"
    if item.get("corrected"):
        return "corrected"
    if item.get("corroborated"):
        return "agreed"
    if item.get("verified"):
        return "verified"
    return "read"


ITEM_MARKS = {"agreed": " ✅", "recovered": " ➕", "corrected": " ✏️"}


def item_mark(item: dict) -> str:
    """Пометка позиции в списке: ✅ — два чтения, ➕ — цена добрана, ✏️ — цена поправлена."""
    origin = item_origin(item)
    if origin in ITEM_MARKS:
        return ITEM_MARKS[origin]
    return "" if item.get("verified", True) else " ⚠️"


def items_list_text(items: list[dict], store: str = "", receipt: dict | None = None) -> str:
    """Полный список позиций чека (в виде карточки, эмодзи, суммы по-человечески).

    `receipt` — весь разбор: из него берутся пометки «итог посчитан по позициям» и
    «позиции дороже итога». Без этих пометок список читается как полностью надёжный,
    а это не всегда так.
    """
    if not items:
        return "В этом чеке позиции не распознаны."
    lines = [f"📋 **Позиции чека{' — ' + md_safe(store) if store else ''}**", ""]
    total = 0.0
    for index, item in enumerate(items, start=1):
        quantity = f"{item.get('qty', 1):.0f} × " if item.get("qty", 1) > 1 else ""
        price = (f" ({format_amount(item['price'])}/шт)"
                 if item.get("price") and item.get("qty", 1) > 1 else "")
        lines.append(f"{index}. {quantity}{md_safe(item['name'])}{price} — "
                     f"**{format_amount(item['sum'])}**{item_mark(item)}")
        total += item["sum"]
    lines += ["", f"**Итого по позициям: {format_amount(total)}**"]
    receipt = receipt or {}
    if receipt.get("total_estimated"):
        lines.append("ℹ️ Итог на чеке прочитать не удалось — это сумма по позициям.")
    if receipt.get("over_total"):
        lines.append("⚠️ Сумма позиций больше итога чека — часть цен прочитана дважды или неверно.")
    if any(item_origin(item) in ("recovered", "corrected") for item in items):
        lines.append("➖ Цены со значками добраны или поправлены по арифметике чека.")
    return "\n".join(lines)


# ─── Страховки поверх ответа модели ──────────────────────────────────────
# Модель ошибается на кривом OCR и на незнакомых товарах: пиво становится «полезным»,
# макароны — «лишними перьями». Эти группы распознаём по названию и правим вердикт.

# Алкоголь, энергетики и снеки: никогда «полезно» и не «нейтрально»
# Маркер с «\b» — регулярка с границей слова: без неё «кола» нашлась бы внутри «шоколад»,
# «сок» — внутри «соковыжималка», «рис» — внутри «рислинг».
def _hits(name: str, markers: tuple) -> bool:
    for marker in markers:
        if marker.startswith("\\b"):
            if re.search(marker, name):
                return True
        elif marker in name:
            return True
    return False


JUNK_MARKERS = (
    "алкоголь", "пиво", "пив", "жигул", "балтик", "водк", "вино", "винн", "шампан",
    "коньяк", "виски", "ром ", "джин", "настойк", "вермут", "сидр", "энергетик", "энергет",
    "red bull", "burn", "adrenaline", "tornado", "чипс", "chips", "lays", "лейс", "сухарик",
    "сух.", "грен", "кириешк", "снек", "попкорн", "шоколад", "батончик", "мармелад",
    "жвачк", "конфет", "морожен",
    "печень", "пряник", "вафл", "кекс", "торт", "пирожн",
    "\\bкола", "\\bcoca", "cola", "pepsi", "пепси", "фанта", "спрайт", "лимонад", "торнад",
    "газиров",
)
# Настоящая еда и бытовое: не бывает «лишним», даже если название нечитаемо
FOOD_MARKERS = (
    "макарон", "мак.", "изд.мак", "перья", "спагет", "вермишел", "лапш", "рожк", "круп",
    "\\bрис", "греч", "овсян", "пшен", "булгур", "кускус", "горох", "фасол", "чечевиц", "мук",
    "сахар", "соль", "специ", "консерв", "тушен", "масло", "сыр", "молок", "кефир", "творог",
    "сметан", "яйц", "мяс", "кури", "рыб", "овощ", "фрукт", "ягод", "картоф", "лук",
    "морков", "капуст", "хлеб", "батон", "чай", "кофе", "вода", "\\bсок", "йогурт", "ряженк",
    "сливк", "томат", "огур", "зелен", "петрушк", "зелень", "гриб", "паштет", "колбас",
)
# Явная упаковка: только если название действительно содержит упаковочный товар.
# Нельзя считать упаковкой строку из-за похожего сокращения: «колб.» — колбаса,
# а «серв.» в «Серв.Кар.» — часть названия продукта.
PACKAGING_MARKERS = ("пакет", "пакет-майка", "пакет майка", "мешок для", "мешок-майка")

# Продуктовые исключения имеют приоритет над любыми слабими совпадениями модели.
PRODUCT_PRIORITY_MARKERS = (
    "колбас", "сервелат", "серв кар", "серв.кар", "ветчин", "сосиск", "бекон", "мясо",
    "слойк", "слоен", "пирог", "булоч", "хлеб", "молок", "сметан", "сыр", "творог",
    "йогурт", "кефир", "пельмен", "макарон", "корм", "проклад", "вода",
)
# Техника: редкая осознанная покупка, а не расходник и уж точно не упаковка. Модель любит
# отправлять её в «лишнее» и называть упаковкой («БП Deepcool — одноразовая упаковка» на чеке
# из ДНС) — это оценка не по данным: такой выбор делают осознанно и не каждый день.
TECH_MARKERS = ("бп ", "блок питан", "корпус", "монитор", "ноутбук", "клавиатур", "мышь", "мышк",
                "наушник", "телевизор", "смартфон", "телефон", "планшет", "принтер", "роутер",
                "ssd", "hdd", "видеокарт", "процессор", "материнск", "флешк", "зарядк", "кабел",
                "переходник", "колонк", "гарнитур", "powerbank", "павербанк")
# Слова про упаковку в совете модели. Совет про упаковку уместен только у настоящей упаковки —
# ровно то правило, из-за которого «колб.» больше не читается как пакет.
PACKAGING_WORDS = ("упаковк", "пакет", "мешок", "одноразов", "тара")
# Мелочи не по еде (заколки, игрушки): тоже «лишнее», но повод другой
ACCESSORY_MARKERS = ("закол", "игрушк", "сувенир", "брелок", "наклейк", "погремушк")
# Гигиена и бытовая химия: нужное, но не «полезное» и не «лишнее»
CARE_MARKERS = ("гель", "шампун", "зубн", "паст", "мыло", "прокладк", "салфетк", "стиральн",
                "порошок", "бумаг", "губк", "бритв", "дезодор", "sensitive", "lac.", "лосьон",
                "крем")

# Готовые советы там, где модель ошибается чаще всего: по типу товара, а не общие слова
JUNK_ADVICE = (
    (("пиво", "пив", "жигул", "балтик", "сидр"),
     ("пиво, много калорий", "хватит одной бутылки")),
    (("вино", "винн", "шампан", "вермут"),
     ("вино, 700 ккал/л", "бокал вместо бутылки")),
    (("водк", "коньяк", "виски", "ром ", "джин", "настойк", "алкоголь"),
     ("крепкий алкоголь", "только по праздникам")),
    (("энергет", "энергетик", "red bull", "burn", "adrenaline"),
     ("энергетик, много кофеина", "заменить кофе")),
    (("чипс", "chips", "lays", "лейс", "сухарик", "сух.", "грен", "кириешк", "снек", "попкорн"),
     ("снек, много калорий", "взять орехи или фрукты")),
    (("шоколад", "батончик", "мармелад", "жвачк", "конфет", "морожен", "печень", "пряник",
      "вафл", "кекс", "торт", "пирожн"),
     ("сладкое, много сахара", "заменить фруктами")),
    (("\\bкола", "\\bcoca", "cola", "pepsi", "пепси", "фанта", "спрайт", "лимонад", "торнад",
      "tornado", "газиров"),
     ("сладкая газировка", "вода вместо неё")),
)

# Готовые советы для категорий, где модель ошибается чаще всего
PACKAGING_ADVICE = ("одноразовая упаковка", "пакет-сумка из дома")
ACCESSORY_ADVICE = ("не еда, покупка по желанию", "брать только осознанно")


def _advice_for(name: str, amount: float = 0.0, index: int = 0) -> tuple[str, str]:
    """Конкретная причина + действие с вариацией для разных SKU одного типа.

    Одинаковые по смыслу советы допустимы только для реально одинаковых товаров. Для
    разных упаковок снеков действие привязывается к размеру/цене и индексу позиции,
    поэтому карточка не выглядит скопированной из одной заготовки.
    """
    lowered = name.lower()
    for markers, advice in JUNK_ADVICE:
        if _hits(lowered, markers):
            reason, note = advice
            if any(marker in lowered for marker in ("чипс", "chips", "lays", "лейс", "сухарик", "сух.", "грен", "снек")):
                gram_match = re.search(r"(\d+)\s*(?:г|гр)", lowered)
                grams = int(gram_match.group(1)) if gram_match else 0
                if grams >= 140:
                    note = "взять маленькую пачку до 80 г"
                elif grams and grams < 100:
                    note = "не докупать вторую пачку к этой"
                else:
                    note = "сравнить цену за 100 г и взять одну пачку"
            elif any(marker in lowered for marker in ("шоколад", "конфет", "печень", "вафл")):
                note = "оставить одну сладость, не несколько"
            elif any(marker in lowered for marker in ("пиво", "пив", "жигул", "балтик")):
                note = "ограничить одной бутылкой и не брать запас"
            return reason, note
    return ("вредная покупка", "брать реже")


def _advice_key(text: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^0-9a-zа-яё ]", "", (text or "").lower())).strip()


def _dedupe_advice(verdicts: dict) -> None:
    """Выбрасывает причины и советы, которые повторяются у разных товаров.

    Модель любит один и тот же текст («хлеб заменить») на половину чека — значит, она не
    смотрела на конкретный товар, и такой совет в карточке — мусор. Убираем его у всех.
    """
    counts: dict[str, int] = {}
    for entry in verdicts.values():
        for field in ("reason", "note"):
            key = _advice_key(entry.get(field) or "")
            if key:
                counts[key] = counts.get(key, 0) + 1
    for entry in verdicts.values():
        for field in ("reason", "note"):
            key = _advice_key(entry.get(field) or "")
            if key and counts[key] > 1:
                entry[field] = ""


def apply_review_rules(analysis: dict | None, items: list[dict]) -> dict | None:
    """Правит ответ модели по ключевым словам — как бы модель ни считала.

    Алкоголь и снеки — никогда «полезно»; настоящая еда — никогда «лишнее» («макароны —
    ненужные перья» — типовая ошибка модели); упаковка — только «лишнее». Где вердикт
    пришлось поправить, объяснение модели тоже не заслуживает доверия — стираем его,
    а для алкоголя и упаковки подставляем проверенный совет.
    """
    if not analysis or not analysis.get("items"):
        return analysis
    verdicts = analysis["items"]
    # Кто поставил вердикт — видно человеку: правило одинаково для одного и того же названия,
    # а вердикт модели — это её оценка, и спорить стоит именно с ней. Заодно видно позиции,
    # по которым модель промолчала: им досталось наше «нейтрально», а не её мнение.
    model_said = set(verdicts)
    by_rule: set[int] = set()
    curated: dict[int, tuple[str, str]] = {}

    for index, item in enumerate(items, start=1):
        name = (item.get("name") or "").lower()
        entry = verdicts.setdefault(index, {"verdict": "нейтрально", "reason": "", "note": ""})
        verdict = entry.get("verdict")
        if is_advice_filler(entry.get("reason") or ""):
            entry["reason"] = ""
        if is_advice_filler(entry.get("note") or ""):
            entry["note"] = ""

        if _hits(name, PRODUCT_PRIORITY_MARKERS):
            # Название явно является продуктом/непродовольственным расходником, но не
            # упаковкой. Модель не может отправить его в «лишнее» по своей фантазии.
            # Правилом вердикт считается только там, где правило его и поставило: если
            # модель сказала «нейтрально» про сыр, это её вердикт, а не наш.
            if _hits(name, ("колбас", "сервелат", "серв кар", "серв.кар", "сосиск", "ветчин",
                            "слойк", "слоен", "пирог", "булоч")):
                by_rule.add(index)
                entry["verdict"] = "нейтрально"
                entry["reason"] = entry["note"] = ""
            elif _hits(name, ("корм", "проклад")):
                by_rule.add(index)
                entry["verdict"] = "нейтрально"
            elif verdict == "лишнее":
                by_rule.add(index)
                entry["verdict"] = "нейтрально"
            if entry["verdict"] == "нейтрально" and _hits(name, ("колбас", "сервелат", "сосиск", "ветчин")):
                entry["reason"] = entry["note"] = ""
        elif _hits(name, JUNK_MARKERS):
            by_rule.add(index)
            if verdict != "лишнее":
                entry["verdict"] = "вредно"
            curated[index] = _advice_for(name, item.get("sum", 0), index)
        elif _hits(name, TECH_MARKERS):
            by_rule.add(index)
            # Техника не бывает «лишней» и не бывает упаковкой: вердикт — нейтрально,
            # а выдуманное обоснование модели стирается вместе с ним.
            entry["verdict"] = "нейтрально"
            entry["reason"] = entry["note"] = ""
        elif _hits(name, PACKAGING_MARKERS):
            by_rule.add(index)
            entry["verdict"] = "лишнее"
            curated[index] = PACKAGING_ADVICE
        elif _hits(name, ACCESSORY_MARKERS):
            by_rule.add(index)
            entry["verdict"] = "лишнее"
            curated[index] = ACCESSORY_ADVICE
        elif _hits(name, CARE_MARKERS) and verdict in ("полезно", "лишнее"):
            by_rule.add(index)
            entry["verdict"] = "нейтрально"
            entry["reason"] = entry["note"] = ""
        elif verdict == "лишнее" and _hits(name, FOOD_MARKERS):
            by_rule.add(index)
            # макароны, крупы, консервы — обычная еда, а не «ненужные перья»
            entry["verdict"] = "нейтрально"
            entry["reason"] = entry["note"] = ""

        # Последняя проверка на упаковку: если совет говорит про упаковку, а товар упаковкой
        # не является — совет не относится к делу и убирается. Вердикт при этом не меняется.
        advice_text = f"{entry.get('reason') or ''} {entry.get('note') or ''}".lower()
        if _hits(advice_text, PACKAGING_WORDS) and not _hits(name, PACKAGING_MARKERS):
            entry["reason"] = entry["note"] = ""

    # Совет модели вычищаем, если он повторяется у разных товаров (— он ни о чём).
    # Готовые советы ставим каждому подходящему товару: они по типу товара, а не выдуманные
    _dedupe_advice(verdicts)
    for index in sorted(curated):
        verdicts[index]["reason"], verdicts[index]["note"] = curated[index]
        verdicts[index]["_name"] = items[index - 1].get("name", "")
    for index, item in enumerate(items, start=1):
        # Нужен для проверки повторов даже у нейтральных/модельных рекомендаций.
        verdicts.setdefault(index, {"verdict": "нейтрально", "reason": "", "note": ""})
        verdicts[index]["_name"] = item.get("name", "")
        if index not in curated and _hits((item.get("name") or "").lower(), JUNK_MARKERS):
            verdicts[index]["reason"], verdicts[index]["note"] = _advice_for(
                item.get("name", ""), item.get("sum", 0), index)
    _ensure_distinct_advice(verdicts)
    for index, entry in verdicts.items():
        entry.pop("_name", None)
        entry["source"] = ("rule" if index in by_rule
                           else "model" if index in model_said else "default")

    # План — тоже часть ответа модели. Показываем только шаги, которые можно связать
    # с этой корзиной; это отсекает галлюцинации вроде «купить сахар», которого нет в чеке.
    known = {_normalize(item.get("name", "")) for item in items}
    for item in items:
        # Проверяем не только целое название (оно склеено нормализатором), но и
        # отдельные слова: «шоколад Милка» должен заземлить шаг «выбрать шоколад». 
        known.update(_normalize(word) for word in re.findall(r"[A-Za-zА-Яа-яЁё]{4,}",
                                                               item.get("name", "")))
    known_stems = {"алкогол", "сладост", "чипс", "снек", "пакет", "закол", "напит", "сахар"}
    grounded_plan = []
    for step in (analysis.get("plan") or []):
        normalized = _normalize(str(step))
        if normalized and (any(token and (token in normalized or normalized in token)
                            for token in known if len(token) >= 4)
                           or any(stem in normalized for stem in known_stems)):
            grounded_plan.append(str(step).strip())
    analysis["plan"] = grounded_plan[:3]
    return analysis


def _advice_line(entry: dict) -> str:
    """«причина → действие» из ответа модели (что есть, то и пишем)."""
    reason = (entry.get("reason") or "").strip()
    note = (entry.get("note") or "").strip()
    if reason and note and reason.lower() != note.lower():
        return f"{md_safe(reason)} → {md_safe(note)}"
    return md_safe(reason or note) if (reason or note) else ""


def _ensure_distinct_advice(verdicts: dict) -> None:
    """Убирает повторы у разных SKU, но сохраняет одинаковый совет для дублей одной позиции."""
    seen: dict[str, list[str]] = {}
    # _name прокидывается apply_review_rules; если функция вызвана отдельно,
    # всё равно не падаем и просто не пытаемся различать неизвестные SKU.
    alternatives = {
        "чипс": (
            ("снек: калории без сытости", "взять одну пачку и не добавлять сухарики"),
            ("снек: много соли", "сравнить цену за 100 г перед покупкой"),
            ("снек: легко съесть незаметно", "оставить одну маленькую пачку"),
        ),
        "lays": (
            ("снек: калории без сытости", "сравнить цену за 100 г перед покупкой"),
            ("снек: много соли", "взять пачку меньшего объёма"),
        ),
        "шоколад": (
            ("сладкое: много добавленного сахара", "выбрать маленькую плитку к чаю"),
            ("сладкое: покупка по импульсу", "не брать вторую сладость в этот день"),
        ),
        "пив": (
            ("алкоголь: калории и импульсная покупка", "покупать только к запланированному вечеру"),
            ("алкоголь: трата без пользы", "заранее ограничить количество бутылок"),
            ("алкоголь: незапланированная покупка", "не брать запас после первой бутылки"),
        ),
    }
    for entry in verdicts.values():
        key = _advice_key(f"{entry.get('reason', '')} {entry.get('note', '')}")
        if not key:
            continue
        name = _normalize(str(entry.get("_name", "")))
        previous_names = seen.setdefault(key, [])
        # Две одинаковые строки в чеке — один SKU, повтор не является дефектом.
        if name and name in previous_names:
            continue
        if not previous_names:
            previous_names.append(name)
            continue

        options = next((values for marker, values in alternatives.items()
                        if marker in str(entry.get("_name", "")).lower()), ())
        replacement = next((value for value in options
                            if not seen.get(_advice_key(f"{value[0]} {value[1]}"))), None)
        if replacement:
            entry["reason"], entry["note"] = replacement
            replacement_key = _advice_key(f"{replacement[0]} {replacement[1]}")
            seen.setdefault(replacement_key, []).append(name)
        else:
            entry["reason"] = entry["note"] = ""
        previous_names.append(name)


def sources_text(verdicts: dict, items: list[dict]) -> str:
    """Кто поставил вердикты: проверка по названию (правила) или оценка модели.

    Правило одинаково для одного и того же товара в любом чеке, а вердикт модели — это её
    чтение названия, и ошибается она именно на сокращённых кассовых строках («колб.» как
    пакет). Человеку важно знать, с чем спорить: с оценкой можно, с правилом обычно не в чем.
    """
    counts = {"rule": 0, "model": 0, "default": 0}
    waste = {"rule": 0, "model": 0, "default": 0}
    for index in range(1, len(items) + 1):
        entry = verdicts.get(index) or {}
        source = entry.get("source") if entry.get("source") in counts else "model"
        counts[source] += 1
        if entry.get("verdict") in ("вредно", "лишнее"):
            waste[source] += 1
    parts = []
    if counts["rule"]:
        parts.append(f"правила — {counts['rule']}")
    if counts["model"]:
        parts.append(f"оценка модели — {counts['model']}")
    if counts["default"]:
        parts.append(f"без вердикта, нейтрально по умолчанию — {counts['default']}")
    if not parts:
        return ""
    lines = ["🧩 **Откуда вердикты:** " + ", ".join(parts) + "."]
    guessed = waste["model"] + waste["default"]
    if guessed and waste["rule"]:
        lines.append(f"Из необязательных — {waste['rule']} по правилу и {guessed} по оценке "
                     "модели: спорить есть с чем именно у вторых.")
    elif guessed:
        if guessed == 1:
            lines.append("Единственный необязательный вердикт — оценка модели, а не проверка "
                         "по названию: это её чтение кассовой строки, и оно ошибается.")
        else:
            words = plural_ru(guessed, "вердикт", "вердикта", "вердиктов")
            lines.append(f"Все {guessed} необязательных {words} — оценка модели, а не проверка "
                         "по названию: это её чтение кассовых строк, и оно ошибается.")
    elif waste["rule"]:
        if waste["rule"] == 1:
            lines.append("Единственный необязательный вердикт — от правила: проверка по "
                         "названию, а не оценка, и одинаково для одного и того же товара "
                         "в любом чеке.")
        else:
            words = plural_ru(waste["rule"], "вердикт", "вердикта", "вердиктов")
            lines.append(f"Все {waste['rule']} необязательных {words} поставлены правилами: "
                         "проверка по названию, а не оценка — одинаково для одного и того же "
                         "товара в любом чеке.")
    return "\n".join(lines)


def basket_text(analysis: dict | None, items: list[dict], store: str = "",
                receipt_total: float | None = None) -> str:
    """Разбор корзины: подробный разбор по позициям, суммы и доли по группам, план."""
    if not analysis or not analysis.get("items"):
        return ""
    verdicts = analysis["items"]

    def group_of(verdict: str) -> list[tuple[int, dict]]:
        return [(index, item) for index, item in enumerate(items, start=1)
                if verdicts.get(index, {}).get("verdict") == verdict]

    def money(amount: float) -> str:
        return format_amount(amount) if amount >= 1 else f"{amount:.2f} ₽"

    header = "🛒 **Разбор корзины**" + (f" — {md_safe(store)}" if store else "")
    spent = round(sum(item["sum"] for item in items), 2)
    total = receipt_total or spent
    lines = [header, f"Чек {format_amount(total)} · позиций: {len(items)}", ""]

    for verdict in VERDICT_ORDER:
        group = group_of(verdict)
        if not group:
            continue
        emoji, title = VERDICT_STYLE[verdict]
        group_sum = round(sum(item["sum"] for _, item in group), 2)
        share = f" · {round(group_sum / total * 100)}% чека" if total else ""
        lines.append(f"{emoji} **{title} — {money(group_sum)}**{share}")
        for index, item in group:
            advice = _advice_line(verdicts[index])
            name = md_safe(item["name"])
            if verdict in ("вредно", "лишнее"):
                lines.append(f"   • {name} — {money(item['sum'])}")
                if advice:
                    lines.append(f"     ↳ {advice}")
            else:
                tail = f" — {advice}" if advice else ""
                lines.append(f"   • {name} — {money(item['sum'])}{tail}")
        lines.append("")

    # Итог считаем по вердиктам, а не по оценке модели — видно, из чего он сложился
    waste = round(sum(item["sum"] for verdict in ("вредно", "лишнее")
                      for _, item in group_of(verdict)), 2)
    if waste:
        share = f" · {round(waste / total * 100)}% чека" if total else ""
        lines.append(f"💰 **Необязательные траты: ~{format_amount(waste)}**{share}")
    # Оценка модели обычно реальнее суммы по вердиктам: необязательное ≠ то, что человек урежет
    save = analysis.get("save") or 0
    if save and save < waste * 0.9:
        lines.append(f"📉 Реально сократить в следующий раз: ~{format_amount(save)}")

    plan = analysis.get("plan") or []
    if plan:
        lines.append("")
        lines.append("📌 **Что делать в следующий раз:**")
        for number, step in enumerate(plan, start=1):
            lines.append(f"   {number}. {md_safe(step)}")

    # Сводку формируем сами: текст модели может назвать другую сумму («сэкономить 635»),
    # хотя сумма по вердиктам уже посчитана выше. Так пользователь видит только проверяемые
    # факты и конкретные позиции, а не красивую, но противоречивую фразу.
    sources = sources_text(verdicts, items)
    if sources:
        lines += ["", sources]

    focus = [(item["name"], item["sum"]) for verdict in ("вредно", "лишнее")
             for _, item in group_of(verdict)]
    if focus:
        focus.sort(key=lambda pair: -pair[1])
        names = ", ".join(md_safe(name) for name, _ in focus[:3])
        lines += ["", f"📍 **Главная точка экономии:** {names}.",
                  f"По текущим вердиктам необязательная часть чека — {format_amount(waste)}."]
    else:
        lines += ["", "📍 Явных необязательных покупок по текущим правилам не нашёл."]
    return "\n".join(lines).strip()


def verdict_rows(analysis: dict | None, items: list[dict]) -> list[tuple]:
    """Вердикт и совет по каждой позиции чека — в том же порядке, что и сами позиции.

    Совет берётся той же функцией, что печатает разбор корзины, поэтому в сохранённой истории
    видно ровно то, что человек прочитал в сообщении, а не пересказ. Вместе с вердиктом
    сохраняется его происхождение: без него отчёт о необязательных покупках не смог бы
    отличить проверку по названию от догадки модели.
    """
    if not analysis or not analysis.get("items"):
        return []
    verdicts = analysis["items"]
    rows = []
    for index, item in enumerate(items, start=1):
        entry = verdicts.get(index)
        if not entry:
            continue
        rows.append((item.get("name") or "Позиция",
                     entry.get("verdict") or "нейтрально", _advice_line(entry),
                     entry.get("source") or ""))
    return rows


def recalc_verdict(name: str, verdict: str, advice: str) -> dict:
    """Что нынешние правила говорят про сохранённую позицию старого чека.

    Пересчёт идёт по одному названию: чека и позиции давно нет под рукой, а правила работают
    именно с названием. Сохранённый вердикт играет роль вердикта модели, и правила его
    поправляют там, где покрывают товар: результат и говорит, что осталось от старого разбора.
    """
    analysis = apply_review_rules(
        {"items": {1: {"verdict": verdict, "reason": advice, "note": ""}}},
        [{"name": name, "sum": 0.0}])
    entry = ((analysis or {}).get("items") or {}).get(1) or {}
    return {"verdict": (entry.get("verdict") or verdict),
            "advice": _advice_line(entry),
            "source": entry.get("source") or ""}


def leisure_hint(result: dict) -> str:
    """Подсказка, что часть чека — это досуг, а не продукты."""
    if not result.get("leisure") or result.get("category") in ("досуг", None):
        return ""
    return ("🍺 В чеке есть алкоголь или закуски к нему — это скорее досуг/развлечения.\n"
            "Можно записать в категорию «досуг», чтобы отчёт по еде не раздувался.")
