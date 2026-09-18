"""Локальная LLM (Ollama): разбор трат, определение категории чека, финансовые советы.

Если модель недоступна или ответила мусором — работает фолбэк по правилам,
бот никогда не падает из-за ИИ.
"""
import asyncio
import json
import re
import time
from datetime import datetime

import httpx

from config import (AI_ADVICE_TIMEOUT, AI_PARSE_TIMEOUT, MODEL_PREFERENCE,
                    OLLAMA_API_KEY, OLLAMA_HOST, OLLAMA_MODEL)

try:
    import ollama
except ImportError:  # библиотека не установлена — работаем на фолбэке
    ollama = None

VALID_CATEGORIES = {"еда", "транспорт", "жилье", "досуг", "одежда", "здоровье", "работа",
                    "техника", "долги", "прочее"}
VALID_TX_TYPES = {"expense", "income", "debt_payment"}
VALID_DEBT_TARGETS = {"sber", "tbank", "yandex", "main"}

# Ключевые слова для определения долга (используются и в ИИ-фолбэке, и в правилах)
DEBT_KEYWORDS = {
    "sber": ("сбер", "sber", "сбербанк"),
    "tbank": ("т-банк", "тбанк", "тинькофф", "tinkoff", "tbank"),
    "yandex": ("яндекс", "yandex", "сплит"),
    "main": ("основн", "большой кредит"),
}

# ─── Промпты ─────────────────────────────────────────────────────────────

TRANSACTION_PROMPT = """Ты — разборщик финансовых сообщений для личного трекера расходов на русском языке.
Отвечай ТОЛЬКО валидным JSON. Никаких пояснений, markdown и ``` — только объект.

Схема ответа:
{"amount": число, "category": "еда|транспорт|жилье|досуг|одежда|здоровье|работа|техника|долги|прочее",
 "subcategory": "уточнение или null", "description": "краткое описание",
 "tx_type": "expense|income|debt_payment", "debt_target": "sber|tbank|yandex|main|null",
 "confidence": 0.0-1.0}

Правила:
- amount — итоговая сумма в рублях, только число. «2к», «2 тыс», «2000 р», «2000₽» → 2000.
- Суммы нет в тексте — amount: 0.
- Продукты в магазине (пятёрочка, магнит, лента, ашан, вкусвилл, дикси) → еда/продукты.
- Фастфуд → еда/фастфуд, доставка еды → еда/доставка, кафе и рестораны → досуг/кафе.
- Бензин, заправка, АЗС → транспорт/бензин; такси → транспорт/такси; метро, автобус → транспорт/общественный.
- Чай, кофе, перекус или обед на работе, автомат → работа/перекус.
- Аренда, коммуналка, ремонт → жилье. Аптека, врач, спорт → здоровье. Одежда, обувь → одежда.
- Электроника, комплектующие, гаджеты, бытовая техника (ДНС, М.Видео, Ситилинк, Эльдорадо) → техника.
- Кредит, платёж по карте, банк → category «долги», tx_type «debt_payment», укажи debt_target.
- Зарплата, аванс, премия, доход, помощь родителей, полученный перевод → tx_type «income».
- confidence: 0.9+ если всё однозначно, 0.5-0.8 если категория под вопросом, ниже 0.5 если непонятно.

Примеры:
«Купил чай в автомате за 130» → {"amount":130,"category":"работа","subcategory":"перекус","description":"Чай в автомате","tx_type":"expense","debt_target":null,"confidence":0.95}
«Заправка 2000» → {"amount":2000,"category":"транспорт","subcategory":"бензин","description":"Заправка","tx_type":"expense","debt_target":null,"confidence":0.95}
«Пятёрочка 3450.50» → {"amount":3450.5,"category":"еда","subcategory":"продукты","description":"Пятёрочка","tx_type":"expense","debt_target":null,"confidence":0.95}
«Платёж Т-Банк 3000» → {"amount":3000,"category":"долги","subcategory":null,"description":"Платёж Т-Банк","tx_type":"debt_payment","debt_target":"tbank","confidence":0.95}
«Зарплата 150000» → {"amount":150000,"category":"прочее","subcategory":null,"description":"Зарплата","tx_type":"income","debt_target":null,"confidence":0.95}
«Доставка суши 1800» → {"amount":1800,"category":"еда","subcategory":"доставка","description":"Доставка суши","tx_type":"expense","debt_target":null,"confidence":0.9}
Сегодня: {today}"""

RECEIPT_PROMPT = """Ты определяешь категорию покупки по сырому тексту чека (может содержать ошибки OCR).
Отвечай ТОЛЬКО JSON: {"category": "..."} — одно из: еда, транспорт, жилье, досуг, одежда, здоровье, работа, долги, прочее.
Продуктовые магазины и аптеки — по смыслу; если непонятно — «прочее»."""

ADVICE_PROMPT = """Ты — финансовый советник. Отвечай по-русски, коротко и по делу.
Пиши 2-3 пункта, каждый с новой строки и с эмодзи, без вступлений, без заголовков и без markdown.
Учитывай, что у пользователя кредитки под высокий процент: досрочное погашение обычно выгоднее любых трат."""

# ─── Доступ к модели ─────────────────────────────────────────────────────

_client = None
_resolved_model: str | None = None
_models_cache: tuple[float, list[str]] | None = None
MODELS_CACHE_TTL = 30      # секунд: не дёргаем Ollama на каждое сообщение
MODELS_CACHE_TTL_DOWN = 15  # короче, если Ollama недоступен — чтобы быстро подхватить запуск


def is_local_host(host: str) -> bool:
    return any(marker in host for marker in ("localhost", "127.0.0.1", "0.0.0.0", "::1"))


def http_options() -> dict:
    """Прокси и авторизация для обращений к Ollama."""
    options = {}
    if is_local_host(OLLAMA_HOST):
        # системный прокси/VPN не должен перехватывать запросы к локальной модели
        options["trust_env"] = False
    if OLLAMA_API_KEY:
        options["headers"] = {"Authorization": f"Bearer {OLLAMA_API_KEY}"}
    return options


def _get_client():
    """Ленивый клиент Ollama (локальная модель, удалённый хост или облако с ключом)."""
    global _client
    if _client is None:
        if ollama is None:
            raise RuntimeError("Библиотека ollama не установлена")
        _client = ollama.Client(host=OLLAMA_HOST, **http_options())
    return _client


async def list_installed_models(force_refresh: bool = False) -> list[str]:
    """Список скачанных моделей (кэш на 30 секунд). Пустой список — Ollama недоступен."""
    global _models_cache
    if _models_cache and not force_refresh:
        ttl = MODELS_CACHE_TTL if _models_cache[1] else MODELS_CACHE_TTL_DOWN
        if time.monotonic() - _models_cache[0] < ttl:
            return _models_cache[1]
    try:
        async with httpx.AsyncClient(timeout=3, **http_options()) as http:
            resp = await http.get(f"{OLLAMA_HOST}/api/tags")
            resp.raise_for_status()
            models = [m.get("name", "") for m in resp.json().get("models", []) if m.get("name")]
    except Exception:
        models = []
    _models_cache = (time.monotonic(), models)
    return models


def split_model(name: str) -> tuple[str, str]:
    """Имя модели → (основа, тег): «qwen3-vl:8b-instruct» → («qwen3-vl», «8b-instruct»)."""
    base, _, tag = (name or "").partition(":")
    return base, (tag or "latest")


def find_model(model: str, installed: list[str]) -> str | None:
    """Ищет модель по точному имени и тегу.

    Совпадения только по основе мало: «qwen3-vl:8b-instruct» превращался в «qwen3-vl:4b»,
    а «qwen2.5:7b-instruct» — в «qwen2.5:7b». Модель молча подменялась на слабейшую, поэтому
    тег обязан совпасть. Исключение — кандидат без тега: подходит единственная версия основы.
    """
    wanted_base, wanted_tag = split_model(model)
    for name in installed:
        base, tag = split_model(name)
        if base == wanted_base and tag == wanted_tag:
            return name
    if wanted_tag == "latest":
        same_base = [name for name in installed if split_model(name)[0] == wanted_base]
        if len(same_base) == 1:
            return same_base[0]
    return None


async def resolve_model(force_refresh: bool = False) -> str:
    """Какая модель реально будет использоваться.

    Сначала OLLAMA_MODEL, затем лучшая из MODEL_PREFERENCE, затем любая не-эмбеддинг модель.
    Если ничего не скачано — возвращаем OLLAMA_MODEL (для понятного сообщения об ошибке).
    """
    global _resolved_model
    if _resolved_model and not force_refresh:
        return _resolved_model

    installed = await list_installed_models(force_refresh=force_refresh)
    if not installed:
        return OLLAMA_MODEL

    for candidate in (OLLAMA_MODEL, *MODEL_PREFERENCE):
        found = find_model(candidate, installed)
        if found:
            _resolved_model = found
            return found

    for name in installed:
        if "embed" not in name.lower():
            _resolved_model = name
            return name
    return OLLAMA_MODEL


def _chat(system: str, user: str, model: str, *, json_mode: bool = True,
          num_predict: int = 250, temperature: float = 0.0) -> str:
    """Синхронный вызов модели (крутится в отдельном потоке через asyncio.to_thread)."""
    client = _get_client()
    kwargs = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "options": {"temperature": temperature, "num_predict": num_predict, "num_ctx": 4096},
        # держим модель в памяти — иначе каждая трата ждёт холодного старта
        "keep_alive": "30m",
    }
    if json_mode:
        # Ollama сам гарантирует синтаксически валидный JSON
        kwargs["format"] = "json"
    response = client.chat(**kwargs)
    return response["message"]["content"].strip()


def loads_lenient(content: str) -> dict:
    """Достаёт JSON из ответа модели, даже если она обернула его в ``` или текстом."""
    content = content.strip()
    content = re.sub(r"^```(?:json)?\s*", "", content)
    content = re.sub(r"\s*```$", "", content)
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", content, re.DOTALL)
        if not match:
            raise
        return json.loads(match.group(0))


# ─── Разбор траты ────────────────────────────────────────────────────────

def _sanitize(parsed: dict, text: str) -> dict:
    """Приводит ответ модели к валидному виду: проверяет категории, суммы, типы."""
    result = {
        "amount": 0.0,
        "category": "прочее",
        "subcategory": None,
        "description": text[:50],
        "tx_type": "expense",
        "debt_target": None,
        "confidence": 0.0,
    }
    try:
        raw_amount = parsed.get("amount", 0)
        amount = float(str(raw_amount).replace(" ", "").replace(",", "."))
        if amount > 0 and amount < 10_000_000:
            result["amount"] = amount
    except (TypeError, ValueError):
        pass

    category = str(parsed.get("category") or "").strip().lower()
    result["category"] = category if category in VALID_CATEGORIES else "прочее"

    subcategory = parsed.get("subcategory")
    result["subcategory"] = str(subcategory).strip()[:50] if subcategory else None

    description = parsed.get("description")
    result["description"] = str(description).strip()[:100] if description else text[:50]

    tx_type = str(parsed.get("tx_type") or "").strip().lower()
    result["tx_type"] = tx_type if tx_type in VALID_TX_TYPES else "expense"

    debt_target = parsed.get("debt_target")
    if isinstance(debt_target, str) and debt_target.strip().lower() in VALID_DEBT_TARGETS:
        result["debt_target"] = debt_target.strip().lower()
    if result["tx_type"] == "debt_payment" and not result["debt_target"]:
        result["debt_target"] = guess_debt_target(text)

    try:
        result["confidence"] = max(0.0, min(1.0, float(parsed.get("confidence", 0.3))))
    except (TypeError, ValueError):
        result["confidence"] = 0.3

    return result


def guess_debt_target(text: str) -> str | None:
    """Ищет упоминание банка в тексте (нужно, когда модель не назвала долг)."""
    lowered = text.lower()
    for key, hints in DEBT_KEYWORDS.items():
        if any(hint in lowered for hint in hints):
            return key
    return None


def _parse_transaction_sync(text: str, model: str, hint: str = "") -> dict:
    user_text = text if not hint else f"{text}\n\n{hint}"
    content = _chat(TRANSACTION_PROMPT.replace("{today}", datetime.now().strftime("%d.%m.%Y")),
                    user_text, model, num_predict=250)
    return _sanitize(loads_lenient(content), text)


async def parse_transaction(text: str) -> dict:
    """Разбирает свободный текст траты. Первая попытка — LLM, вторая — LLM + подсказка,
    затем (или при недоступной модели) — правила. Никогда не бросает исключение."""
    model = await resolve_model()

    for hint in ("", "Ответь ещё раз строго по схеме, только JSON."):
        try:
            parsed = await asyncio.wait_for(
                asyncio.to_thread(_parse_transaction_sync, text, model, hint),
                timeout=AI_PARSE_TIMEOUT,
            )
        except Exception:
            continue

        if parsed.get("amount"):
            return parsed

        # Модель не нашла сумму — подстраховываемся правилами, не теряя её категорию
        rules = fallback_parse(text)
        if rules.get("amount"):
            parsed["amount"] = rules["amount"]
            parsed["confidence"] = min(parsed.get("confidence") or 0.3, 0.5)
            if parsed["tx_type"] == "expense" and rules["tx_type"] != "expense":
                parsed["tx_type"] = rules["tx_type"]
                parsed["debt_target"] = parsed.get("debt_target") or rules.get("debt_target")
            return parsed

        if hint:  # второй раз тоже без суммы — дальше только правила
            return rules
        return parsed

    return fallback_parse(text)


async def guess_category(receipt_text: str) -> str | None:
    """Определяет категорию покупки по сырому тексту чека (учёт ошибок OCR). Возвращает None при неудаче."""
    if not receipt_text or len(receipt_text.strip()) < 5:
        return None
    model = await resolve_model()
    try:
        content = await asyncio.wait_for(
            asyncio.to_thread(_chat, RECEIPT_PROMPT, receipt_text[:800], model,
                              json_mode=True, num_predict=40),
            timeout=AI_PARSE_TIMEOUT,
        )
        category = str(loads_lenient(content).get("category") or "").strip().lower()
        return category if category in VALID_CATEGORIES else None
    except Exception:
        return None


# ─── Фолбэк по правилам ──────────────────────────────────────────────────

CATEGORY_KEYWORDS = {
    "еда": ("пятёрочка", "пятерочка", "магнит", "лента", "ашан", "вкусвилл", "дикси", "продукт",
            "перекрёсток", "перекресток", "фастфуд", "мак", "бургер", "шаурма", "суши", "пицц"),
    "техника": ("днс", "dns", "м.видео", "мвидео", "mvideo", "ситилинк", "citilink", "эльдорадо",
                "технопоинт", "регард", "onlinetrade", "комплектующ", "видеокарт", "материнск",
                "процессор", "монитор", "клавиатур", "наушник", "смартфон", "ноутбук", "ssd",
                "жёсткий диск", "жесткий диск", "корпус", "блок питания", "мыш"),
    "транспорт": ("бензин", "азс", "заправк", "метро", "автобус", "такси", "лукойл", "газпром",
                  "роснефть", "татнефть", "каршеринг", "парковк"),
    "досуг": ("кафе", "ресторан", "кино", "доставка", "кофе", "бар", "игр", "развлеч", "концерт"),
    "работа": ("чай", "автомат", "перекус", "обед", "кофе с собой", "канцеляр"),
    "здоровье": ("аптек", "врач", "стоматолог", "клиник", "анализ", "спортзал", "фитнес"),
    "одежда": ("одежд", "обувь", "кроссовк", "футболк", "куртк", "аксессуар"),
    "жилье": ("аренд", "квартир", "коммунал", "жкх", "ремонт", "мебел"),
    "долги": ("кредит", "платёж", "платеж", "сбер", "т-банк", "тбанк", "яндекс", "карт"),
}

INCOME_KEYWORDS = ("зарплат", "аванс", "премия", "доход", "получил", "перевод от", "помощь",
                   "вернули", "кешбэк", "кэшбэк")


# ─── Категоризация магазинов банковской выписки ──────────────────────────

# Правила по ключевым словам — первая линия, модель достаётся остальным.
MERCHANT_RULES = {
    "еда": ("пят", "магнит", "лента", "ашан", "вкусвилл", "дикси", "перекрёсток", "перекресток",
            "продукт", "гастроном", "монетка", "бристоль", "красное&белое", "винлаб",
            "шаурма", "шаверма", "пицц", "суши", "бургер", "макдоналдс", "вкусно и точка",
            "додо", "dominos", "мегаполюс", "агрокомплекс", "молочн", "мяснов", "абрикос",
            "pyaterochka", "magnit", "perekrestok", "lenta ", "ashan", "dixy", "vkusvill",
            "shaverma", "bristol", "krasnoe&belo", "magnit cosmet"),
    "транспорт": ("азс", "лукойл", "газпромнефть", "роснефть", "татнефть", "такси", "яндекс go",
                  "автобус", "транспортн", "парков", "каршер", "ситидрайв", "делимобиль",
                  "ржд", "суперпоток", "taxi", "lukoil", "rosneft", "gazprom oil", "avtodor", "дорплат"),
    "техника": ("днс", "dns", "мвидео", "м.видео", "ситилинк", "эльдорадо", "технопоинт",
                "юмей", "ozon", "вайлдберриз", "wildberries", "яндекс маркет", "алиэкспресс",
                "hoff", "юлмарт", "регард", "citilink", "mvideo"),
    "здоровье": ("аптек", "аптек", "горздрав", "рсб", "вита", "клиник", "стоматолог", "медиц",
                 "инвитро", "гемотест", "поликлин", "фитнес", "спортзал", "олимп",
                 "aptek", "apteka", "pharmacy", "aptech"),
    "досуг": ("кино", "кинотеатр", "steam", "playstation", "мир игр",
              "кафе", "ресторан", "кофейн", "кофе хауз", "шоколадниц", "ск сити", "синема",
              "боулинг", "billiard", "бильярд", "антикафе", "t-bundle", "sochipark", "парк"),
    "одежда": ("одежда", "обув", "спортмастер", "sportmaster", "зара", "zara", "h&m",
               "глория джинс", "фамилия", "экко", "эконика", "goldapple"),
    "жилье": ("жкх", "гис жкх", "управляющ", "домо", "аренда", "мебель", "леруа", "строй",
              "сбермаркет доставка"),
    "долги": ("кредит", "платёж по", "сбербанк", "перевод по номеру"),
}


def rule_category(merchant: str) -> str | None:
    """Категория магазина по правилам или None — тогда решает модель."""
    low = merchant.lower()
    if not low:
        return None
    # Такси пишется как «YANDEX*4121*GO» — звёздочки и номер внутри ломают подстроки.
    if re.search(r"yandex[^a-z]*go|яндекс\s*go", low):
        return "транспорт"
    for category, words in MERCHANT_RULES.items():
        if any(w in low for w in words):
            return category
    return None


def _extract_amount(text: str) -> float:
    """Достаёт сумму из текста: «2000», «2 000», «2к», «2 тыс», «3450,50»."""
    compact = text.replace("\u00a0", " ")
    thousands = re.search(r"(\d+(?:[.,]\d+)?)\s*(?:к|тыс\w*)\b", compact, re.IGNORECASE)
    if thousands:
        try:
            return float(thousands.group(1).replace(",", ".")) * 1000
        except ValueError:
            pass

    numbers = re.findall(r"(\d+(?:[.,]\d+)?)", compact.replace(" ", ""))
    if not numbers:
        return 0.0
    try:
        return float(numbers[0].replace(",", "."))
    except ValueError:
        return 0.0


def fallback_parse(text: str) -> dict:
    """Простые правила на случай, если LLM недоступна или не ответила."""
    lowered = text.lower()
    amount = _extract_amount(text)

    category = "прочее"
    for cat, words in CATEGORY_KEYWORDS.items():
        if any(w in lowered for w in words):
            category = cat
            break

    tx_type = "expense"
    debt_target = None
    if any(w in lowered for w in INCOME_KEYWORDS):
        tx_type = "income"
        category = "прочее"
    elif category == "долги":
        tx_type = "debt_payment"
        debt_target = guess_debt_target(lowered)

    return {
        "amount": amount,
        "category": category,
        "subcategory": None,
        "description": text[:50],
        "tx_type": tx_type,
        "debt_target": debt_target,
        "confidence": 0.3,
    }


# ─── Чек: распознавание структуры и разбор корзины ───────────────────────

RECEIPT_PROMPT = """Ты разбираешь кассовый чек. Числа в позициях уже распознаны точно — их НЕЛЬЗЯ менять,
твоя работа — человеческие названия, магазин, дата и категория.
Отвечай ТОЛЬКО JSON:
{"store": "название магазина или null", "date": "ДД.MM.ГГГГ или null",
 "category": "еда|транспорт|жилье|досуг|одежда|здоровье|работа|техника|долги|прочее",
 "is_grocery": true или false, "leisure": true или false,
 "items": [{"index": 1, "name": "человеческое название товара"}]}

Позиции чека и сырой текст OCR придут в следующем сообщении:
номера позиций — в таблице «номер. название | цена x кол-во = сумма».

Правила:
- items: только исправленные названия, ровно по одному на каждый номер (index — тот же номер).
  Восстанавливай названия по обрывкам: «ПЭТ Ly 1.350» → «Пиво Жигулёвское 1.35 л»,
  «Пакет ПЯТЕРОЧКА 65%40cM» → «Пакет-майка 65×40 см», «BN Deepcool PF750 7508» → «БП Deepcool PF750 750W».
  Не пиши в названии объёмы отдельными цифрами, не добавляй того, чего нет в чеке.
  Если название совсем не читается — верни для него пустую строку.
- store — название магазина. Строки «кассовый чек», «ИТОГ», адреса и телефоны — НЕ магазин.
  Название компании обычно в первых строках («ООО «X»», «ИП X») — извлекай X даже с ошибками OCR
  и сопоставляй с известной сетью (ДНС: «anc pirenn» → «ДНС Ритейл»; «Пятёрочка», «агроторг»).
  null — если в тексте вообще нет названия компании, не подставляй сеть, которой нет.
- date — только та дата, что есть в тексте. Ничего не придумывай.
- category — по составу чека: электроника и комплектующие → техника; продукты → еда; аптека → здоровье.
  Алкоголь (пиво, вино, водка), чипсы, снеки, энергетики — это досуг/развлечения, а не продукты:
  если на них приходится заметная часть суммы (примерно от трети), ставь category «досуг».
- is_grocery = true только для продуктового магазина.
- leisure = true, если в чеке есть алкоголь или явно «развлекательные» товары (снеки, игры, игрушки).

Примеры исправления кривого OCR:
«BN pewpcool PF750 7508» → «БП Deepcool PF750 750W»
«КОРПУС ZALMAN на Rev. 1 Hidtower Btack» → «Корпус ZALMAN N4 Rev.1 Midtower Black»"""

BASKET_PROMPT = """Ты разбираешь продуктовую корзину по позициям чека. По каждому товару нужен не ярлык,
а понятный вывод: почему так и что делать в следующий раз. Отвечай ТОЛЬКО JSON:
{"items": [{"index": 1, "verdict": "полезно|нейтрально|вредно|лишнее",
            "reason": "почему, до 10 слов", "note": "что делать, до 12 слов"}],
 "plan": ["шаг 1", "шаг 2"], "summary": "2-3 предложения", "save": число}

Список позиций и магазин придут в следующем сообщении строками «номер. название — сумма».

Вердикты:
- «полезно» — база нормального рациона и быта: овощи, фрукты, ягоды, крупы, бобовые, макароны,
  мясо, рыба, яйца, творог, кефир, молоко, вода, чай, специи, хлеб.
- «нейтрально» — нужное, но не про пользу: сыр, масло, сметана, соусы, кофе, соки, выпечка,
  бытовая химия, гигиена и косметика, посуда, корм животным.
- «вредно» — то, что легко урезать без потерь: алкоголь (пиво, вино, водка), энергетики,
  чипсы, снеки, сладкая газировка, фастфуд, кондитерские изделия и сладости.
- «лишнее» — НЕ еда и не нужное к ней: одноразовые пакеты, заколки, игрушки, сувениры,
  вторая упаковка того, что уже есть. Продукты (в том числе макароны, крупы, консервы,
  специи) в «лишнее» НЕ попадают.

Жёсткие правила:
- Пиво, вино, водка, энергетики, чипсы, снеки — НИКОГДА не «полезно» и не «нейтрально».
- Пиши только про позиции из списка, index — тот же номер, названия и суммы не выдумывай.
- reason — факт именно об этом товаре («много сахара», «белка 18 г на порцию», «цена выросла»).
- note — конкретное действие или замена («взять 1 л вместо 2 л», «крыжовник вместо конфет»,
  «пакет-сумка из дома», «только по выходным»).
- reason и note НЕ повторяются у разных товаров: одинаковые советы двум разным продуктам
  запрещены. Запрещены пустые note: «заменить», «полезнее», «досуг», «нужен», «ненужное».
  Если для товара нет осмысленного совета — верни пустую строку в note, но не общие слова.
- plan — 2-3 конкретных шага на следующую закупку, с суммами, если они есть в чеке.
- summary — где именно утечка денег, сколько это в рублях и что менять в первую очередь.
  Опирайся на конкретные позиции из списка, без нравоучений.
- save — сколько реально можно не тратить в следующий раз, одно число в рублях.

Пример одной позиции:
{"index": 3, "verdict": "вредно", "reason": "чипсы, 520 ккал на пачку",
 "note": "взять орехи 100 г"}
Плохо (совет-затычка, так нельзя): {"index": 3, "verdict": "вредно",
 "reason": "вредно", "note": "заменить"}"""

BUDGET_PROMPT = """Ты финансовый советник. По статистике трат предложи месячный бюджет.
Отвечай ТОЛЬКО JSON: {"total": число, "limits": {"категория": число}, "comment": "1-2 предложения"}

Правила:
- limits только для категорий: еда, транспорт, жилье, досуг, одежда, здоровье, работа, техника, прочее.
- Отталкивайся от средних трат за прошлые месяцы: стабильные категории — средняя +5-10%,
  редкие и крупные (техника, одежда) — с запасом, но не больше 1,5 средней.
- total — сумма лимитов + небольшой резерв, обязательно больше суммы лимитов.
- comment — как считал и где проще всего сэкономить."""


VERDICTS = {"полезно", "нейтрально", "вредно", "лишнее"}


def _sanitize_receipt(parsed: dict, expected: int = 0) -> dict:
    """Приводит ответ модели по чеку к безопасному виду.

    Числа берём из разбора OCR, поэтому от модели ждём только названия и категории.
    """
    result = {"store": None, "date": None, "category": None, "is_grocery": False,
              "leisure": False, "names": {}}

    store = str(parsed.get("store") or "").strip()
    result["store"] = store[:60] or None

    date = str(parsed.get("date") or "").strip()
    result["date"] = date[:20] or None

    category = str(parsed.get("category") or "").strip().lower()
    result["category"] = category if category in VALID_CATEGORIES else None
    result["is_grocery"] = bool(parsed.get("is_grocery")) or result["category"] == "еда"
    result["leisure"] = bool(parsed.get("leisure"))

    items = parsed.get("items") or []
    if isinstance(items, list):
        for position, item in enumerate(items[:80], start=1):
            if not isinstance(item, dict):
                continue
            try:
                index = int(item.get("index") or position)
            except (TypeError, ValueError):
                index = position
            name = str(item.get("name") or "").strip()
            if not name:
                continue
            if expected and not 1 <= index <= expected:
                continue
            result["names"][index] = name[:70]
    return result


def _structure_receipt_sync(raw_text: str, table_text: str, model: str) -> dict:
    expected = len([line for line in table_text.split("\n") if line.strip()])
    content = _chat(RECEIPT_PROMPT, f"{table_text}\n\nСырой текст чека:\n{raw_text[:5000]}",
                    model, json_mode=True, num_predict=1500, temperature=0.0)
    return _sanitize_receipt(loads_lenient(content), expected)


async def structure_receipt(raw_text: str, table_text: str = "") -> dict | None:
    """Разбирает чек: человеческие названия позиций, магазин, категория.

    Числа берёт из таблицы OCR (table_text), а не из ответа модели.
    """
    if not raw_text or len(raw_text.strip()) < 10:
        return None
    expected = len([line for line in table_text.split("\n") if line.strip()])
    model = await resolve_model()
    try:
        return await asyncio.wait_for(
            asyncio.to_thread(_structure_receipt_sync, raw_text, table_text, model),
            timeout=AI_PARSE_TIMEOUT,
        )
    except Exception:
        return None


# Советы-затычки: модель уходит в них, когда не разобрала товар — в карточке они бесполезны
def is_advice_filler(text: str) -> bool:
    cleaned = text.strip(" .,:;—-–").lower()
    if len(cleaned) < 5:
        return True
    fillers = ("заменить", "замена", "полезнее", "полезно", "вредно", "лишнее", "ненужное",
               "не нужен", "ненужный", "досуг", "нужен", "нужно", "можно без", "не обязательно",
               "необязательно", "нет", "-", "без комментариев")
    if cleaned in fillers:
        return True
    # «хлеб заменить», «можно заменить» — совет ни о чём
    words = cleaned.split()
    return len(words) <= 2 and any(filler in cleaned for filler in ("замен", "полезн", "нужн"))


def _clean_advice(text, limit: int = 110) -> str:
    cleaned = re.sub(r"\s+", " ", str(text or "")).strip()
    cleaned = cleaned.strip(" .,:;—-–")
    if not cleaned or is_advice_filler(cleaned):
        return ""
    return cleaned[:limit]


def _sanitize_basket(parsed: dict, count: int) -> dict:
    """Оценка корзины: по вердикту, причине и действию на каждую позицию, плюс план."""
    result = {"items": {}, "summary": "", "plan": [], "save": 0.0}
    items = parsed.get("items") or []
    if isinstance(items, list):
        for position, item in enumerate(items[:60], start=1):
            if not isinstance(item, dict):
                continue
            try:
                index = int(item.get("index") or position)
            except (TypeError, ValueError):
                index = position
            verdict = str(item.get("verdict") or "").strip().lower()
            reason = _clean_advice(item.get("reason"), 80)
            note = _clean_advice(item.get("note"))
            if index < 1 or index > count or (not verdict and not note and not reason):
                continue
            result["items"][index] = {"verdict": verdict if verdict in VERDICTS else "нейтрально",
                                      "reason": reason, "note": note}

    plan = parsed.get("plan") or []
    if isinstance(plan, list):
        for step in plan[:4]:
            cleaned = _clean_advice(step, 120)
            if cleaned:
                result["plan"].append(cleaned)

    result["summary"] = str(parsed.get("summary") or "").strip()[:600]
    try:
        save = float(str(parsed.get("save") or 0).replace(" ", "").replace(",", "."))
        result["save"] = round(save) if 0 <= save < 10_000_000 else 0.0
    except (TypeError, ValueError):
        pass
    return result


def _analyze_basket_sync(items: list[dict], store: str, model: str) -> dict:
    limited = items[:40]
    listing = "\n".join(f"{index}. {item['name']} — {item['sum']:.0f} ₽"
                        for index, item in enumerate(limited, start=1))
    content = _chat(BASKET_PROMPT, f"Магазин: {store or 'неизвестен'}\nПозиции:\n{listing}",
                    model, json_mode=True, num_predict=900, temperature=0.0)
    return _sanitize_basket(loads_lenient(content), len(limited))


async def analyze_basket(items: list[dict], store: str = "") -> dict | None:
    """Честный разбор корзины: что полезно, что вредно и от чего отказаться."""
    if not items:
        return None
    model = await resolve_model()
    try:
        return await asyncio.wait_for(
            asyncio.to_thread(_analyze_basket_sync, items, store, model),
            timeout=AI_ADVICE_TIMEOUT,
        )
    except Exception:
        return None


async def suggest_budget(context: str) -> dict | None:
    """Предложение бюджета на месяц по истории трат."""
    model = await resolve_model()
    try:
        content = await asyncio.wait_for(
            asyncio.to_thread(_chat, BUDGET_PROMPT, context, model,
                              json_mode=True, num_predict=600, temperature=0.0),
            timeout=AI_ADVICE_TIMEOUT,
        )
        parsed = loads_lenient(content)
    except Exception:
        return None

    limits = {}
    for category, value in (parsed.get("limits") or {}).items():
        key = str(category).strip().lower()
        if key not in VALID_CATEGORIES:
            continue
        try:
            number = float(str(value).replace(" ", "").replace(",", "."))
        except (TypeError, ValueError):
            continue
        if 0 <= number < 10_000_000:
            limits[key] = round(number)
    if not limits:
        return None

    try:
        total = float(str(parsed.get("total") or 0).replace(" ", "").replace(",", "."))
    except (TypeError, ValueError):
        total = 0
    total = round(max(total, sum(limits.values())))
    return {"limits": limits, "total": total,
            "comment": str(parsed.get("comment") or "").strip()[:400]}


# ─── Советы ──────────────────────────────────────────────────────────────

def _recommendation_sync(context: str, model: str) -> str:
    return _chat(ADVICE_PROMPT, context, model, json_mode=False, num_predict=300, temperature=0.3)


async def get_recommendation(context: str) -> str:
    model = await resolve_model()
    try:
        return await asyncio.wait_for(
            asyncio.to_thread(_recommendation_sync, context, model),
            timeout=AI_ADVICE_TIMEOUT,
        )
    except Exception:
        return ("🤖 Не удалось получить совет: Ollama недоступен.\n"
                f"Запусти модель командой `ollama pull {OLLAMA_MODEL}`.")


# ─── Категоризация магазинов выписки: правила + один батч модели ─────────

BATCH_SYSTEM = """Ты — ассистент личного бюджета. Тебе дают список магазинов из банковской выписки.
Для КАЖДОГО магазина из списка укажи категорию расходов. Только валидный JSON, без пояснений.

Схема ответа: {"категории": {"<магазин>": "<категория>", ...}}
Категория — строго одно из: еда, транспорт, жилье, досуг, одежда, здоровье, работа, техника, долги, прочее.

Важно: названия латиницей — это русская транслитерация. Расшифровывай их:
APTEKA/APTECHNOE = аптека (здоровье), MOROZHENOYE = мороженое (еда), KOFEYNYA = кофейня (досуг),
PRODUKTY = продукты (еда), TABACHNAYA = табачная (прочее), STOLOVAYA = столовая (еда).
Ориентиры: супермаркеты, продукты, доставка еды — «еда»; аптеки и клиники — «здоровье»;
такси, АЗС, платные дороги — «транспорт»; маркетплейсы (Ozon, Wildberries) — «техника»;
кафе и развлечения — «досуг»; одежда — «одежда»; ЖКХ и аренда — «жилье».
Если совсем непонятно (номер точки, аббревиатура) — «прочее». Верни все строки списка."""


def _norm_key(name: str) -> str:
    """APTEKA_SOVETSKAYA 13 -> aptekasovetskaya13: без регистра, пунктуации и ё."""
    text = name.lower().replace("ё", "е")
    return re.sub(r"[^a-zа-я0-9]", "", text)


def _match_requested(response_key: str, chunk: list[str], norm_index: dict[str, str]) -> str | None:
    """Сопоставляет ключ ответа модели с именем из запроса.

    Модель отвечает ключами в нижнем регистре и слегка переписывает названия
    («stolovaya» вместо «STOLOVAYA 1», «moryazhenoye» вместо «MOROZHENOYE 3»).
    Порядок: точное совпадение после нормализации → уникальный префикс →
    нечёткое совпадение → None.
    """
    import difflib

    key = _norm_key(response_key)
    if key in norm_index:
        return norm_index[key]
    prefixes = [name for name in chunk if _norm_key(name).startswith(key) and key]
    if len(prefixes) == 1:
        return prefixes[0]
    close = difflib.get_close_matches(key, list(norm_index), n=1, cutoff=0.8)
    return norm_index[close[0]] if close else None


async def classify_merchants(merchants: list[str]) -> dict[str, str]:
    """Категории для уникальных магазинов выписки: правила, затем батчи модели.

    Модель страхует только то, что не покрыл словарь. Ключи её ответа
    сопоставляются с запросом нечётко — имена она переписывает. Ошибки модели
    и таймауты дают «прочее», не падение.
    """
    result: dict[str, str] = {}
    unknown: list[str] = []
    for name in merchants:
        category = rule_category(name)
        if category:
            result[name] = category
        else:
            unknown.append(name)
    if not unknown:
        return result

    try:
        model = await resolve_model()
        # Порции по 10 магазинов: на них модель отвечает за каждую строку,
        # на длинных порциях отвечает частично или «прочее» на всё подряд.
        for chunk_start in range(0, len(unknown), 10):
            chunk = unknown[chunk_start:chunk_start + 10]
            norm_index = {_norm_key(name): name for name in chunk}
            listing = "\n".join(f"- {name}" for name in chunk)
            content = await asyncio.wait_for(
                asyncio.to_thread(_chat, BATCH_SYSTEM, listing, model,
                                  json_mode=True, num_predict=3072, temperature=0.0),
                timeout=max(AI_ADVICE_TIMEOUT, 180),
            )
            mapping = loads_lenient(content).get("категории") or {}
            if not isinstance(mapping, dict):
                mapping = {}
            for response_key, raw_category in mapping.items():
                name = _match_requested(str(response_key), chunk, norm_index)
                if name is None:
                    continue
                category = str(raw_category or "").strip().lower()
                result[name] = category if category in VALID_CATEGORIES else "прочее"
    except Exception:
        pass
    for name in unknown:
        result.setdefault(name, "прочее")
    return result


# ─── Уточнение загадочных магазинов выписки ответом человека ─────────────

CLARIFY_SYSTEM = """Ты — ассистент личного бюджета. Пользователь объясняет, что это за магазин
из банковской выписки (названия в выписке часто технические: «TERMINAL 14», «LIZONKA»).
Только валидный JSON, без пояснений.

Схема ответа: {"category": "<категория>", "label": "<короткое название для истории>"}
Категория — строго одно из: еда, транспорт, жилье, досуг, одежда, здоровье, работа, техника, долги, прочее.
Label — 1-3 слова, как этот магазин стоит называть в истории расходов: без номеров, городов и аббревиатур.
Пример: «снековый автомат на работе» → {"category": "еда", "label": "Снековый автомат"}
Пример: «доставка воды домой» → {"category": "жилье", "label": "Доставка воды"}
Если объяснение не про магазин и не про трату — верни {"category": "прочее", "label": ""}."""


async def classify_clarification(answer: str) -> dict:
    """Ответ человека («снековый автомат на работе») → категория + метка для истории.

    Ошибки модели и таймауты дают {"category": "прочее", "label": ""} — уточнение
    всё равно сохраняется, просто без ярлыка.
    """
    fallback = {"category": "прочее", "label": ""}
    try:
        model = await resolve_model()
        content = await asyncio.wait_for(
            asyncio.to_thread(_chat, CLARIFY_SYSTEM, answer[:500], model,
                              json_mode=True, num_predict=120, temperature=0.0),
            timeout=AI_PARSE_TIMEOUT,
        )
        parsed = loads_lenient(content)
        category = str(parsed.get("category") or "").strip().lower()
        label = str(parsed.get("label") or "").strip()[:40]
        return {"category": category if category in VALID_CATEGORIES else "прочее",
                "label": label}
    except Exception:
        return fallback


# ─── Догадки-кнопки под вопросом уточнения ───────────────────────────────

GUESS_SYSTEM = """Ты — ассистент личного бюджета. В банковской выписке встретился магазин
с техническим названием. Дай до двух правдоподобных догадок, что это за место,
ТОЛЬКО если название реально подсказывает (латиница — русская транслитерация).
Только валидный JSON: {"догадки": [{"category": "<категория>", "label": "<1-3 слова>"}, ...]}
Категория — одно из: еда, транспорт, жилье, досуг, одежда, здоровье, работа, техника, долги, прочее.
Примеры: APTECHNOE UCHREZHD-IE -> аптека (здоровье); OST. DINAMO -> остановка (транспорт);
KOFEYNYA -> кофейня (досуг). Если название ничего не подсказывает (номер точки, аббревиатура) —
верни {"догадки": []}."""


async def guess_merchant(name: str) -> list[dict]:
    """Догадки, что за магазин: до двух (категория, метка) для кнопок под вопросом.

    Без гипотезы возвращает пустой список — тогда под вопросом остаются только
    свободный ответ и пропуск. Ошибки модели не ломают цикл уточнений.
    """
    try:
        model = await resolve_model()
        content = await asyncio.wait_for(
            asyncio.to_thread(_chat, GUESS_SYSTEM, name[:120], model,
                              json_mode=True, num_predict=150, temperature=0.0),
            timeout=AI_PARSE_TIMEOUT,
        )
        raw = loads_lenient(content).get("догадки") or []
        guesses = []
        for item in (raw[:2] if isinstance(raw, list) else []):
            if not isinstance(item, dict):
                continue
            category = str(item.get("category") or "").strip().lower()
            label = str(item.get("label") or "").strip()[:30]
            if category in VALID_CATEGORIES and label:
                guesses.append({"category": category, "label": label})
        return guesses
    except Exception:
        return []
