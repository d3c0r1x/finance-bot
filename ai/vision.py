"""Чтение чека локальной моделью зрения (VLM) через Ollama.

Tesseract читает посимвольно и не понимает таблицу: у него рвутся строки, теряются колонки
и объёмы товаров уезжают в цены. VLM (qwen3-vl) видит фото целиком — как человек: понимает,
где название, где цена, где итог, и читает даже блёклый и наклонный текст.

VLM не заменяет Tesseract, а работает вместе с ним: модель зрения приносит позиции и
человеческие названия, Tesseract — детерминированные цифры для арифметической проверки.
Сведение обоих ответов — в ai/receipts.py.

Перед чтением фото готовится (`ai/ocr.py::vision_variants`): обрезается фон, выпрямляется
наклон, поднимается контраст и резкость. Если позиции не сошлись с итогом, чек перечитывается
вторым проходом по другому варианту подготовки и с промптом, нацеленным на таблицу позиций.
"""
import base64
import re
from io import BytesIO

import httpx

from ai.llm import find_model, http_options, list_installed_models, loads_lenient
from ai.ocr import vision_variants
from config import (OLLAMA_HOST, OLLAMA_MODEL, VISION_ENABLED, VISION_KEEP_ALIVE, VISION_MODEL,
                    VISION_PREFERENCE, VISION_SCALE, VISION_TIMEOUT, VISION_UNLOAD_CHAT)

# Что просим у модели зрения: всё, что нужно для карточки записи
VISION_PROMPT = """Ты читаешь кассовый чек с фотографии. Верни ТОЛЬКО JSON:
{"store": "название магазина", "date": "ДД.MM.ГГГГ", "total": 0.0,
 "category": "еда|транспорт|жилье|досуг|одежда|здоровье|работа|техника|долги|прочее",
 "is_grocery": true, "leisure": false,
 "items": [{"name": "товар", "qty": 1, "price": 0.0, "sum": 0.0}]}

Правила:
- total — итог к оплате (строки ИТОГ, ИТОГО, ПОДЫТОГ, К ОПЛАТЕ).
- items — каждая товарная строка чека: name, qty (количество), price (цена за единицу),
  sum (сумма по строке, колонка «Итого»). Переписывай название разборчиво и по-русски:
  «Пиво ЖИГУЛ.ФИРМ.св.ПЭТ 1.35л» → «Пиво Жигулёвское 1.35 л», «Пакет ПЯТЕРОЧКА 65х40см» →
  «Пакет-майка 65×40 см», «БП Deepcool PF750 750W».
- Служебные строки (кассовый чек, НДС, скидка, округление, итог, гарантия, реквизиты, кассир,
  адрес, телефон) в items НЕ включай.
- Числа переписывай точно как на чеке: не округляй, не додумывай и не меняй разряды.
  Если у строки несколько цен — бери ИТОГО по строке в sum, цену за единицу в price.
- Если строка совсем нечитаема — пропусти её, не придумывай товар.
- category — по составу чека: электроника и комплектующие → техника, продукты → еда,
  аптека → здоровье. Алкоголь (пиво, вино, водка), чипсы и снеки — это досуг/развлечения,
  а не еда: если на них приходится заметная часть суммы (примерно от трети), ставь «досуг».
- is_grocery — true только для продуктового магазина.
- leisure — true, если в чеке есть алкоголь или развлекательные товары (снеки, игры, игрушки).
- store — название магазина или сети. Если его не видно — null."""

# Второй проход: первый не сошёлся с итогом — ищем потерянные и криво прочитанные позиции
REPAIR_PROMPT = """Ты перечитываешь кассовый чек ВТОРОЙ раз и очень внимательно. На первом проходе
сумма позиций не сошлась с итогом — значит, часть строк потеряна или числа прочитаны неверно.
Верни ТОЛЬКО JSON:
{"total": 0.0, "items": [{"name": "товар", "qty": 1, "price": 0.0, "sum": 0.0}]}

Как читать:
- Иди по чеку строка за строкой сверху вниз и выпиши КАЖДУЮ товарную строку, даже если
  название размыто: назови её по тому, что видно («позиция: К.Ц.Изд.мак.ПЕРЬЯ 400г»).
- Сумма всех sum должна точно совпасть с total из строки ИТОГ. Скидку и округление учитывай:
  если после скидки сумма меньше — в sum ставь конечную сумму по строке.
- Проверь строки с количеством: sum = price × qty (две бутылки пива — sum вдвое больше price).
- Не придумывай товаров, которых в чеке нет, и не повторяй одну строку дважды.
- Числа переписывай точно как напечатано, без округления и без «починки» разрядов.
- total — итог к оплате одной строкой."""

# «Думающие» модели (qwen3 без -instruct) уходят в рассуждения и оставляют content пустым
NO_THINK_SUFFIX = ("\n\nОтвечай сразу, одним JSON, без рассуждений и пояснений. "
                   "Не пиши ничего, кроме JSON.")


async def resolve_vision_model() -> str | None:
    """Какая модель зрения реально доступна (None — нечего использовать)."""
    if not VISION_ENABLED:
        return None
    models = await list_installed_models()
    if not models:
        return None
    for candidate in (VISION_MODEL, *VISION_PREFERENCE):
        found = find_model(candidate, models)
        if found:
            return found
    return None


async def unload(model: str) -> None:
    """Выгружает модель из памяти (освобождает VRAM под другую)."""
    if not model:
        return
    try:
        async with httpx.AsyncClient(timeout=15, **http_options()) as client:
            await client.post(f"{OLLAMA_HOST}/api/generate",
                              json={"model": model, "keep_alive": 0})
    except Exception:
        pass


async def warmup(model: str, keep_alive: str = "30m") -> None:
    """Заранее поднимает модель в памяти, чтобы следующее сообщение не ждало загрузки."""
    if not model:
        return
    try:
        async with httpx.AsyncClient(timeout=120, **http_options()) as client:
            await client.post(f"{OLLAMA_HOST}/api/generate",
                              json={"model": model, "prompt": "", "keep_alive": keep_alive})
    except Exception:
        pass


def _encode(image, scale: float) -> str:
    """Готовое фото чека в base64. Увеличиваем — мелкий шрифт модель читает точнее."""
    from PIL import Image

    if scale and scale != 1:
        image = image.resize((int(image.width * scale), int(image.height * scale)),
                             Image.LANCZOS)
    # ограничение сверху: очень большие картинки модель всё равно ужимает
    if max(image.size) > 2600:
        ratio = 2600 / max(image.size)
        image = image.resize((int(image.width * ratio), int(image.height * ratio)),
                             Image.LANCZOS)
    buffer = BytesIO()
    image.convert("RGB").save(buffer, format="JPEG", quality=92)
    return base64.b64encode(buffer.getvalue()).decode()


def encoded_variants(image_path: str) -> dict[str, str]:
    """Подготовленные варианты фото чека: метка → base64 (см. ai/ocr.py::vision_variants)."""
    encoded = {}
    for label, image in vision_variants(image_path):
        encoded[label] = _encode(image, VISION_SCALE)
    return encoded


def _number(value) -> float:
    """Достаёт деньги из русской и международной записи без потери разрядов.

    «1 234,56», «1.234,56», «1234.56» и «1234» должны дать одно число. Раньше
    точка-разделитель тысяч превращалась в две десятичные точки и поле отбрасывалось.
    """
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value or "").replace("\u00a0", "").replace(" ", "").strip()
    text = re.sub(r"[^\d,.-]", "", text)
    if not text or not re.search(r"\d", text):
        return 0.0
    if "," in text and "." in text:
        # Последний разделитель — дробная часть: 1.866,33 или 1,866.33.
        separator = "," if text.rfind(",") > text.rfind(".") else "."
        thousands = "." if separator == "," else ","
        text = text.replace(thousands, "").replace(separator, ".")
    elif "," in text:
        text = text.replace(",", ".")
    elif text.count(".") > 1:
        parts = text.split(".")
        text = "".join(parts[:-1]) + "." + parts[-1]
    try:
        return float(text)
    except ValueError:
        return 0.0


def _clean_name(name: str) -> str:
    text = re.sub(r"\s+", " ", str(name or "")).strip(" -—–:.,*")
    return text[:70]


def _sanitize(parsed: dict) -> dict:
    """Приводит ответ модели зрения к безопасному виду."""
    result = {"store": None, "date": None, "total": None, "category": None,
              "is_grocery": False, "leisure": False, "items": []}

    store = str(parsed.get("store") or "").strip()
    # модель иногда переписывает саму инструкцию вместо ответа
    if store and not re.search(r"(null|магазин или)", store, re.IGNORECASE):
        result["store"] = store[:60] or None

    date = str(parsed.get("date") or "").strip()
    if re.match(r"^\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4}$", date):
        result["date"] = date

    total = _number(parsed.get("total"))
    if 0 < total < 10_000_000:
        result["total"] = round(total, 2)

    category = str(parsed.get("category") or "").strip().lower()
    result["category"] = category or None
    result["is_grocery"] = bool(parsed.get("is_grocery"))
    result["leisure"] = bool(parsed.get("leisure"))

    items = parsed.get("items") or []
    if isinstance(items, list):
        for entry in items[:80]:
            if not isinstance(entry, dict):
                continue
            name = _clean_name(entry.get("name"))
            if len(name) < 2 or not any(char.isalpha() for char in name):
                continue
            quantity = _number(entry.get("qty")) or 1.0
            price = _number(entry.get("price"))
            position_sum = _number(entry.get("sum"))
            if not position_sum and price:
                position_sum = round(price * (quantity or 1), 2)
            if position_sum <= 0 or position_sum > 1_000_000:
                continue
            if not price:
                price = round(position_sum / (quantity or 1), 2)
            result["items"].append({"name": name, "qty": float(quantity or 1),
                                    "price": round(price, 2), "sum": round(position_sum, 2)})
    return result


async def _ask(client: httpx.AsyncClient, model: str, prompt: str, image_b64: str) -> dict | None:
    """Один запрос к модели зрения. None — модель не ответила или ответ не JSON."""
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt, "images": [image_b64]}],
        "format": "json",
        "stream": False,
        "keep_alive": VISION_KEEP_ALIVE,
        "options": {"temperature": 0, "num_predict": 3000, "num_ctx": 16384},
    }
    response = await client.post(f"{OLLAMA_HOST}/api/chat", json=payload)
    if response.status_code != 200:
        return None
    content = (response.json().get("message", {}).get("content") or "").strip()
    if not content:
        # «думающая» версия модели ушла в рассуждения и не оставила ответа — просим без раздумий
        payload["messages"][0]["content"] = prompt + NO_THINK_SUFFIX
        response = await client.post(f"{OLLAMA_HOST}/api/chat", json=payload)
        if response.status_code != 200:
            return None
        content = (response.json().get("message", {}).get("content") or "").strip()
        if not content:
            return None
    return loads_lenient(content)


async def read_receipt(image_path: str, model: str | None = None, *,
                       variant: str = "crop", purpose: str = "read",
                       prepared: dict[str, str] | None = None) -> dict | None:
    """Читает фото чека моделью зрения. None — модель недоступна или ошибка.

    variant — какой подготовленный вариант фото отправить («crop», «clean», «raw»);
    purpose — «read» (обычное чтение) или «repair» (второй проход по таблице позиций).
    prepared позволяет не готовить фото заново при повторном проходе.
    """
    model = model or await resolve_vision_model()
    if not model:
        return None

    try:
        variants = prepared if prepared is not None else encoded_variants(image_path)
    except Exception:
        return None
    image_b64 = variants.get(variant) or next(iter(variants.values()))
    prompt = VISION_PROMPT if purpose == "read" else REPAIR_PROMPT

    # на 12 ГБ картах текстовая и зрительная модели не влезают вместе
    if VISION_UNLOAD_CHAT:
        await unload(OLLAMA_MODEL)

    try:
        async with httpx.AsyncClient(timeout=VISION_TIMEOUT, **http_options()) as client:
            parsed = await _ask(client, model, prompt, image_b64)
    except Exception:
        # модель могла не влезть в память (503) или отдать не-JSON — путь Tesseract остаётся
        return None
    if not parsed:
        return None

    result = _sanitize(parsed)
    result["model"] = model
    result["variant"] = variant
    result["purpose"] = purpose
    return result


async def vision_status() -> dict:
    """Состояние модели зрения для экрана статусов."""
    if not VISION_ENABLED:
        return {"enabled": False, "model": None, "installed": []}
    models = await list_installed_models()
    return {"enabled": True, "model": await resolve_vision_model(), "installed": models}
