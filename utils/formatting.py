"""Форматирование: суммы, полоски прогресса, эмодзи категорий, безопасный Markdown."""
import re

from config import OLLAMA_MODEL


def md_safe(text) -> str:
    """Убирает символы, которые ломают Markdown-разметку Telegram в пользовательском тексте."""
    if text is None:
        return "—"
    cleaned = re.sub(r"[*_`\[\]]", "", str(text)).strip()
    return cleaned or "—"


def md_code(text) -> str:
    """Моноширинный фрагмент: экранирует имена моделей и пути (там бывают символы подчёркивания)."""
    return "`" + str(text or "—").replace("`", "") + "`"


def format_amount(value) -> str:
    try:
        value = float(value)
    except (TypeError, ValueError):
        return str(value)
    if value == int(value):
        return f"{int(value):,}".replace(",", " ") + " ₽"
    return f"{value:,.2f}".replace(",", " ").replace(".", ",") + " ₽"


MONTHS_RU = ("январь", "февраль", "март", "апрель", "май", "июнь",
             "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")


def plural_ru(count: int, one: str, few: str, many: str) -> str:
    """«1 трата», «2 траты», «5 трат» — правильные окончания в отчётах."""
    count = abs(int(count))
    if count % 10 == 1 and count % 100 != 11:
        return one
    if 2 <= count % 10 <= 4 and not 12 <= count % 100 <= 14:
        return few
    return many


def month_name_ru(date=None) -> str:
    """Название месяца по-русски («сентябрь») — для заголовков отчётов."""
    from datetime import datetime
    return MONTHS_RU[(date or datetime.now()).month - 1]


def progress_bar(percent: float, width: int = 10) -> str:
    filled = max(0, min(width, int(percent // (100 / width))))
    return "█" * filled + "░" * (width - filled)


def get_category_emoji(category: str) -> str:
    return CATEGORY_EMOJI.get(category, "📦")


CATEGORY_EMOJI = {
    "еда": "🍕",
    "транспорт": "🚗",
    "жилье": "🏠",
    "досуг": "🎮",
    "одежда": "👕",
    "здоровье": "💊",
    "работа": "💼",
    "техника": "💻",
    "долги": "💳",
    "прочее": "📦",
}

DEBT_NAMES = {
    "sber": "Сбербанк кредитка",
    "tbank": "Т-Банк кредитка",
    "yandex": "Яндекс кредит",
    "main": "Основной кредит",
}

MODEL_HINT = OLLAMA_MODEL  # подставляется в сообщения о статусе ИИ
