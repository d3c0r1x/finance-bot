import re
from datetime import datetime

from fastapi import HTTPException

from database.models import CATEGORIES


AMOUNT = re.compile(r"(?P<amount>\d[\d\s]*[,.]?\d{0,2})\s*(?:₽|руб|р|RUB)", re.IGNORECASE)
INCOME_WORDS = ("пополнение", "зачисление", "перевод вам", "поступление", "cashback")
EXPENSE_WORDS = ("покупка", "оплата", "списание", "перевод", "tinkoff", "т-банк", "тбанк")


def parse_notification(text: str) -> dict:
    normalized = " ".join(text.split())
    if not normalized:
        raise HTTPException(422, "empty_notification")
    match = AMOUNT.search(normalized)
    if not match:
        raise HTTPException(422, "amount_not_found")
    amount = float(match.group("amount").replace(" ", "").replace(",", "."))
    low = normalized.lower()
    tx_type = "income" if any(word in low for word in INCOME_WORDS) else "expense"
    merchant = _merchant(normalized, match.end())
    return {
        "amount": round(amount, 2),
        "category": _category(merchant, tx_type),
        "description": merchant or "T-Bank notification",
        "tx_type": tx_type,
        "created_at": datetime.now(),
        "source": "tbank_notification",
        "raw": normalized,
    }


def _merchant(text: str, start: int) -> str:
    tail = text[start:].strip(" .,:;-")
    if not tail:
        return ""
    stop = re.search(r"(?:Баланс|Карта|Счет|Доступно|Остаток)[:\s]", tail, re.IGNORECASE)
    value = tail[:stop.start()].strip(" .,:;-") if stop else tail
    return value[:100]


def _category(merchant: str, tx_type: str) -> str:
    if tx_type == "income":
        return "прочее"
    low = merchant.lower()
    if any(word in low for word in ("пятер", "перекрест", "магнит", "вкусвилл", "ozon fresh", "самокат")):
        return "еда"
    if any(word in low for word in ("такси", "metro", "метро", "транспорт", "azs", "азс")):
        return "транспорт"
    if any(word in low for word in ("аптека", "pharm")):
        return "здоровье"
    return "прочее" if "прочее" in CATEGORIES else CATEGORIES[-1]
