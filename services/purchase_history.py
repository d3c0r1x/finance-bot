"""Личная история цен: один товар — одна цена, подорожание, экономия и каталог покупок.

Один модуль отвечает за ответ на три вопроса: «этот товар подорожал у меня?», «дешевле ли
я купил, чем обычно?» и «сколько я обычно плачу за этот товар, где брал дешевле всего».
Сравнение идёт только по позициям чеков одного пользователя — база отдаёт выборку
(`get_receipt_price_history`), а решение «это тот же товар или другой бренд» принимается здесь.
"""
import re
import statistics
from datetime import datetime
from difflib import SequenceMatcher

from utils.formatting import format_amount, md_safe

# Служебные слова и бренды не должны превращать два разных продукта в «один и тот же».
STOP_WORDS = {
    "пятерочка", "пятёрочка", "смaк", "тема", "папа", "мож", "катти", "pur", "felix",
    "корм", "пакет", "майка", "покупка", "товар", "шт", "руб", "р",
}

# Порог, ниже которого изменение цены не считается сигналом: мелочь и округление OCR.
MIN_CHANGE = 10.0
MIN_RELATIVE = 0.12


def _tokens(name: str) -> set[str]:
    text = (name or "").lower().replace("ё", "е")
    words = re.findall(r"[a-zа-я0-9]{3,}", text)
    return {word for word in words if word not in STOP_WORDS and not word.isdigit()}


def _signature(name: str) -> str:
    return "".join(sorted(_tokens(name)))


def product_key(name: str) -> str:
    """Стабильный ключ товара: по нему хранятся отключённые напоминания и адресуются кнопки.

    «Молоко 3,2% 930мл» и «МОЛОКО 3,2% 930МЛ» дают один ключ, поэтому подсказка про товар не
    теряется из-за регистра и знаков препинания в чеке.
    """
    return _signature(name or "")


def same_product(current: str, previous: str) -> bool:
    """Консервативно определяет один ли это товар, а не просто похожая категория."""
    current_tokens, previous_tokens = _tokens(current), _tokens(previous)
    if not current_tokens or not previous_tokens:
        return False
    overlap = len(current_tokens & previous_tokens) / max(len(current_tokens), len(previous_tokens))
    ratio = SequenceMatcher(None, _signature(current), _signature(previous)).ratio()
    # Один сильный идентификатор (например, 200г или название марки) плюс близкая строка.
    return overlap >= 0.75 or (overlap >= 0.5 and ratio >= 0.82)


def _get(row, key: str, default=None):
    """Читает поле и строки sqlite3.Row, и обычного словаря."""
    try:
        value = row[key]
    except (KeyError, IndexError, TypeError):
        return default
    return default if value is None else value


def _unit_price(row) -> float | None:
    """Цена за единицу: то, что сравнимо между чеками с разным количеством."""
    try:
        qty = float(_get(row, "qty", 1) or 1)
        price = float(_get(row, "price", 0) or 0)
        total = float(_get(row, "sum", 0) or 0)
    except (TypeError, ValueError):
        return None
    if price <= 0 and total > 0:
        price = total / max(qty, 1)
    return round(price, 2) if price > 0 else None


def _entry(row) -> dict | None:
    """Приводит позицию чека к виду, с которым работает сравнение цен."""
    name = str(_get(row, "name", "") or "").strip()
    price = _unit_price(row)
    if not name or not price:
        return None
    try:
        total = float(_get(row, "sum", 0) or 0)
    except (TypeError, ValueError):
        total = 0.0
    return {
        "name": name,
        "price": price,
        "sum": total or price,
        "date": str(_get(row, "created_at", "") or ""),
        "store": str(_get(row, "description", "") or "").strip(),
    }


def parse_date(value: str):
    """Дата позиции чека: база хранит её строкой, разбираем её в одном месте."""
    try:
        return datetime.fromisoformat(str(value or ""))
    except (TypeError, ValueError):
        return None


def _day(value: str) -> str:
    moment = parse_date(value)
    return moment.strftime("%d.%m") if moment else ""


def grouped_entries(history) -> list[dict]:
    """Группирует позиции чеков по товару — общая база для каталога и прогноза закупки.

    Группы строятся тем же сопоставлением названий, что и сравнение цен, поэтому каталог,
    предупреждение о подорожании и список покупок не могут разойтись в трактовке «тот же
    товар». Порядок позиций внутри группы — по дате чека.
    """
    groups: list[dict] = []
    for row in history or []:
        entry = _entry(row)
        if not entry:
            continue
        group = next((item for item in groups
                      if same_product(entry["name"], item["first"])
                      or same_product(entry["name"], item["last"])), None)
        if group is None:
            groups.append({"first": entry["name"], "last": entry["name"], "entries": [entry]})
            continue
        group["last"] = entry["name"]
        group["entries"].append(entry)
    for group in groups:
        group["entries"].sort(key=lambda entry: entry["date"] or "")
    return groups


def usual_price(entries: list[dict]) -> float | None:
    """Обычная цена — медиана прошлых покупок.

    Медиана, а не последняя цена и не среднее: одна акция или одно крупное отклонение не
    должны объявлять обычную цену подорожавшей. При одной покупке она и есть обычная.
    """
    prices = [entry["price"] for entry in entries if entry.get("price")]
    if not prices:
        return None
    return round(statistics.median(prices), 2)


def compare_items(items: list[dict], history) -> list[dict]:
    """Сравнивает позиции нового чека с обычной ценой того же товара в прошлых чеках.

    Возвращает и подорожание, и покупку дешевле обычного: обе стороны полезны. Товар ищется
    по личной истории пользователя, разные SKU одной категории не сравниваются.
    """
    past = [entry for entry in (_entry(row) for row in (history or [])) if entry]
    results = []
    for item in items or []:
        current_price = _unit_price(item)
        if not current_price:
            continue
        matches = [entry for entry in past if same_product(str(item.get("name", "")), entry["name"])]
        if not matches:
            continue
        baseline = usual_price(matches)
        if not baseline:
            continue
        change = round(current_price - baseline, 2)
        relative = change / baseline if baseline else 0
        if abs(change) < MIN_CHANGE or abs(relative) < MIN_RELATIVE:
            continue
        last = max(matches, key=lambda entry: entry["date"])
        cheapest = min(matches, key=lambda entry: entry["price"])
        name = str(item.get("name", "Товар"))
        percent = round(relative * 100)
        results.append({
            "name": name,
            "current": current_price,
            "previous": baseline,
            "change": change,
            "relative": relative,
            "kind": "up" if change > 0 else "down",
            "purchases": len(matches),
            "date": _day(last["date"]),
            "usual": format_amount(baseline),
            "cheapest": cheapest["price"],
            "cheapest_store": cheapest["store"],
            "message": (f"{md_safe(name)}: {format_amount(baseline)} → "
                        f"{format_amount(current_price)} ({percent:+d}%)"),
        })
    return results


def history_text(changes: list[dict]) -> str:
    """Короткий блок для корзины: что подорожало, а что взято дешевле обычного."""
    if not changes:
        return ""
    rising = [change for change in changes if change.get("kind") == "up"]
    cheaper = [change for change in changes if change.get("kind") != "up"]
    lines = []
    if rising:
        lines += ["", "📈 **Дороже, чем ты обычно берёшь:**"]
        for change in rising[:5]:
            lines.append(f"   • {change['message']}")
    if cheaper:
        if lines:
            lines.append("")
        lines.append("📉 **Дешевле обычного — так и дальше:**")
        for change in cheaper[:5]:
            saved = abs(change["change"])
            lines.append(f"   • {change['message']} · экономия {format_amount(saved)}")
    lines.append("Сравниваю с обычной ценой твоих прошлых покупок того же товара" + _note(changes))
    return "\n".join(lines)


def _note(changes: list[dict]) -> str:
    """Поясняет, на чём построено сравнение: одна покупка или медиана."""
    counts = {change.get("purchases", 0) for change in changes}
    if counts and max(counts) >= 2:
        return " (медиана нескольких покупок), а не с другим брендом или упаковкой."
    return ", а не с другим брендом или упаковкой."


def _aggregate(group: dict) -> dict:
    """Считает по группе товара то, что нужно всем экранам: цена, рост, лучший магазин.

    Две разные цифры, и обе нужны: «обычная цена» — сколько человек платит обычно (медиана всех
    покупок), «прежняя цена» — медиана покупок до последней, с ней сравнивается рост. Если
    считать рост от обычной цены, в одной строке сойдутся проценты от одной базы и сумма от
    другой — именно так каталог и список закупки однажды показывали разные «обычные» цены.
    """
    entries = group["entries"]
    last = entries[-1]
    cheapest = min(entries, key=lambda entry: entry["price"])
    baseline = usual_price(entries[:-1]) or last["price"]
    return {
        "name": last["name"],
        "key": product_key(last["name"]),
        "usual": usual_price(entries) or last["price"],
        "baseline": baseline,
        "last": last["price"],
        "last_date": last["date"],
        "last_store": last["store"],
        "cheapest": cheapest["price"],
        "cheapest_store": cheapest["store"],
        "count": len(entries),
        "spent": round(sum(entry["sum"] for entry in entries), 2),
        "trend": round((last["price"] - baseline) / baseline, 3) if baseline else 0,
        "entries": entries,
    }


def product_groups(history, min_purchases: int = 3) -> list[dict]:
    """Собирает товары из прошлых чеков в группы с обычной и лучшей ценой."""
    groups = [_aggregate(group) for group in grouped_entries(history)
              if len(group["entries"]) >= min_purchases]
    return sorted(groups, key=lambda group: -group["spent"])


def search_products(history, query: str, min_purchases: int = 1,
                    limit: int = 5) -> list[dict]:
    """Ищет товар в своих чеках по названию.

    Сначала находятся товары, в название которых вошли все слова запроса: «молоко» находит
    «Молоко Простоквашино 3,2%», но не «Молочный коктейль». Если так ничего не нашлось,
    включается нечёткое сопоставление — лучше показать похожее, чем ничего.
    """
    wanted = _tokens(query)
    scored = []
    for group in grouped_entries(history):
        if len(group["entries"]) < min_purchases:
            continue
        name = group["last"]
        exact = bool(wanted) and wanted <= _tokens(name)
        overlap = len(wanted & _tokens(name))
        if not exact and not overlap and not same_product(query, name):
            continue
        scored.append((exact, overlap, group))
    if any(exact for exact, _, _ in scored):
        scored = [item for item in scored if item[0]]
    scored.sort(key=lambda item: (-item[0], -item[1]))
    return [_aggregate(item[2]) for item in scored[:limit]]


def card_text(group: dict, due: dict | None = None, history_limit: int = 6) -> str:
    """Карточка одного товара: обычная цена, история покупок и, если пора, срок закупки."""
    lines = [f"🔍 **{md_safe(group['name'])}**", ""]
    lines.append(f"Обычная цена: **{format_amount(group['usual'])}** · покупок: "
                 f"{group['count']} · всего {format_amount(group['spent'])}")
    last = f"Последняя покупка: {format_amount(group['last'])}"
    if group["last_date"]:
        last += f" · {_day(group['last_date'])}"
    if group["last_store"]:
        last += f" · {md_safe(group['last_store'])}"
    percent = round(group["trend"] * 100)
    if abs(percent) >= round(MIN_RELATIVE * 100):
        last += f" · {percent:+d}% к прежней {format_amount(group['baseline'])}"
    lines.append(last)
    if group["cheapest"] < group["usual"] - 0.01:
        cheapest = f"Дешевле всего: {format_amount(group['cheapest'])}"
        if group["cheapest_store"]:
            cheapest += f" · {md_safe(group['cheapest_store'])}"
        lines.append(cheapest)

    entries = group["entries"][-history_limit:]
    lines += ["", "**История покупок:**"]
    for entry in entries:
        line = f"   • {_day(entry['date'])} — {format_amount(entry['price'])}"
        if entry["store"]:
            line += f" · {md_safe(entry['store'])}"
        lines.append(line)
    if group["count"] > len(entries):
        lines.append(f"   … показаны последние {len(entries)} из {group['count']} покупок")
    if due:
        lines += ["", f"🛒 Пора брать: ожидал {due['due_date']:%d.%m}, берёшь "
                      f"раз в {due['interval']} дн."]
    return "\n".join(lines)


def catalog_text(history, limit: int = 10) -> str:
    """Экран «Мои цены»: обычная цена товаров, динамика и где было дешевле."""
    groups = product_groups(history)
    if not groups:
        return ("🏷 **Мои цены**\n\n"
                "Пока нечего сравнивать: нужно минимум три покупки одного товара в чеках.\n"
                "Пришли пару чеков — здесь появится обычная цена каждого товара, её рост и "
                "место, где было дешевле.")
    rising = [group for group in groups if group["trend"] >= MIN_RELATIVE]
    falling = [group for group in groups if group["trend"] <= -MIN_RELATIVE]
    lines = [f"🏷 **Мои цены** — товаров с историей: {len(groups)}", ""]
    for group in groups[:limit]:
        percent = round(group["trend"] * 100)
        arrow = "🔺" if percent >= 12 else "🔻" if percent <= -12 else "▪️"
        last = f"последний раз {format_amount(group['last'])}"
        if group["last_date"]:
            last += f" ({_day(group['last_date'])})"
        line = (f"{arrow} **{md_safe(group['name'])}**\n"
                f"   обычно {format_amount(group['usual'])} · {last} · покупок: {group['count']}")
        if abs(percent) >= 12:
            line += f"\n   {percent:+d}% к прежней цене {format_amount(group['baseline'])}"
        if group["cheapest"] < group["usual"] - 0.01:
            place = f", {md_safe(group['cheapest_store'])}" if group["cheapest_store"] else ""
            line += f"\n   дешевле всего было {format_amount(group['cheapest'])}{place}"
        lines.append(line)
    if len(groups) > limit:
        lines.append(f"… и ещё {len(groups) - limit} товаров — показываю самые крупные "
                     "по тратам.")
    summary = []
    if rising:
        summary.append("подорожали: " + ", ".join(md_safe(group["name"]) for group in rising[:3]))
    if falling:
        summary.append("подешевели: " + ", ".join(md_safe(group["name"]) for group in falling[:3]))
    if summary:
        lines += ["", "📌 " + "; ".join(summary)]
    lines.append("\nСравнение идёт с обычной ценой (медианой твоих покупок) того же товара.")
    lines.append("Карточка одного товара с историей покупок: `/price молоко`.")
    return "\n".join(lines)
