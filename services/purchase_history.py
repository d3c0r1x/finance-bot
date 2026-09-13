"""Сравнение позиций нового чека с личной историей покупок."""
import re
from datetime import datetime
from difflib import SequenceMatcher

from utils.formatting import format_amount

# Служебные слова и бренды не должны превращать два разных продукта в «один и тот же».
STOP_WORDS = {
    "пятерочка", "пятёрочка", "смaк", "тема", "папа", "мож", "катти", "pur", "felix",
    "корм", "пакет", "майка", "покупка", "товар", "шт", "руб", "р",
}


def _tokens(name: str) -> set[str]:
    text = (name or "").lower().replace("ё", "е")
    words = re.findall(r"[a-zа-я0-9]{3,}", text)
    return {word for word in words if word not in STOP_WORDS and not word.isdigit()}


def _signature(name: str) -> str:
    return "".join(sorted(_tokens(name)))


def same_product(current: str, previous: str) -> bool:
    """Консервативно определяет один ли это товар, а не просто похожая категория."""
    current_tokens, previous_tokens = _tokens(current), _tokens(previous)
    if not current_tokens or not previous_tokens:
        return False
    overlap = len(current_tokens & previous_tokens) / max(len(current_tokens), len(previous_tokens))
    ratio = SequenceMatcher(None, _signature(current), _signature(previous)).ratio()
    # Один сильный идентификатор (например, 200г или название марки) плюс близкая строка.
    return overlap >= 0.75 or (overlap >= 0.5 and ratio >= 0.82)


def _unit_price(row: dict) -> float | None:
    try:
        qty = float(row.get("qty") or 1)
        price = float(row.get("price") or 0)
        total = float(row.get("sum") or 0)
    except (TypeError, ValueError):
        return None
    if price <= 0 and total > 0:
        price = total / max(qty, 1)
    return round(price, 2) if price > 0 else None


def compare_items(items: list[dict], history) -> list[dict]:
    """Находит подорожание того же товара и возвращает только уверенные сигналы.

    Не сравниваем разные SKU одной категории: колбаса с другой рецептурой или сметана
    другого бренда не выдаются за изменение цены. Порог — минимум 10 рублей и 12%.
    """
    results = []
    for item in items:
        current_price = _unit_price(item)
        if not current_price:
            continue
        matches = [row for row in history if same_product(item.get("name", ""), row["name"])]
        if not matches:
            continue
        previous_price = _unit_price(matches[0])
        if not previous_price:
            continue
        change = round(current_price - previous_price, 2)
        relative = change / previous_price if previous_price else 0
        if change < 10 or relative < 0.12:
            continue
        date = ""
        try:
            date = datetime.fromisoformat(matches[0]["created_at"]).strftime("%d.%m")
        except (TypeError, ValueError):
            pass
        results.append({
            "name": item.get("name", ""),
            "current": current_price,
            "previous": previous_price,
            "change": change,
            "relative": relative,
            "date": date,
            "message": (f"{item.get('name', 'Товар')} подорожал: "
                        f"{format_amount(previous_price)} → {format_amount(current_price)}"
                        + (f" с {date}" if date else "")),
        })
    return results


def history_text(changes: list[dict]) -> str:
    """Короткое сообщение для корзины, без превращения его в ещё один общий совет."""
    if not changes:
        return ""
    lines = ["", "📈 **Изменения относительно твоих прошлых чеков:**"]
    for change in changes[:5]:
        percent = round(change["relative"] * 100)
        lines.append(f"   • {change['message']} (+{percent}%)")
    lines.append("Это сравнение одинакового товара, а не разных брендов или упаковок.")
    return "\n".join(lines)
