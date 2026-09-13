"""Бюджет на месяц: лимиты по категориям и общий лимит.

Стартовые значения берутся из config, дальше живут в таблице settings БД
и правятся прямо в боте: ⚙️ Настройки → 🎯 Бюджет.
"""
from datetime import datetime

from config import BUDGET_MIN_DAYS, MONTHLY_LIMITS, TOTAL_MONTHLY_LIMIT
from database.db import get_all_settings, get_debts, get_transactions, set_setting
from database.models import CATEGORIES
from utils.formatting import month_name_ru

TOTAL_KEY = "limit:total"
DEFAULT_LIMITS = dict(MONTHLY_LIMITS)
DEFAULT_TOTAL = float(TOTAL_MONTHLY_LIMIT)


def _key(category: str) -> str:
    return f"limit:{category}"


def _to_number(value) -> float | None:
    try:
        number = float(str(value).replace(" ", "").replace(",", "."))
    except (TypeError, ValueError):
        return None
    return number if 0 <= number < 100_000_000 else None


async def get_limits() -> dict[str, float]:
    """Лимиты по категориям: значения из БД, остальное — из config."""
    stored = await get_all_settings()
    limits = dict(DEFAULT_LIMITS)
    for category in CATEGORIES:
        value = _to_number(stored.get(_key(category)))
        if value is not None:
            limits[category] = value
    return limits


async def get_total_limit() -> float:
    stored = await get_all_settings()
    value = _to_number(stored.get(TOTAL_KEY))
    return DEFAULT_TOTAL if value is None else value


async def set_limit(category: str, value: float) -> None:
    if category not in CATEGORIES:
        raise ValueError(f"Неизвестная категория: {category}")
    await set_setting(_key(category), str(round(float(value), 2)))


async def set_total_limit(value: float) -> None:
    await set_setting(TOTAL_KEY, str(round(float(value), 2)))


async def apply_limits(limits: dict[str, float], total: float | None = None) -> None:
    """Применяет набор лимитов (например, предложенных ИИ)."""
    for category, value in limits.items():
        if category in CATEGORIES:
            await set_limit(category, value)
    if total:
        await set_total_limit(total)


async def reset_limits() -> None:
    """Возвращает стартовые значения из config."""
    for category in CATEGORIES:
        await set_setting(_key(category), str(DEFAULT_LIMITS.get(category, 0)))
    await set_setting(TOTAL_KEY, str(DEFAULT_TOTAL))


def proposal_for_income(income: float, current: dict[str, float] | None = None,
                        share: float = 0.7) -> tuple[dict[str, float], float]:
    """Бюджет по доходу: на траты идёт `share` дохода, остальное — накопления.

    Категории делятся пропорционально текущим лимитам, чтобы привычная картина сохранилась.
    Считается без ИИ — это прикидка для приветственной настройки, когда истории ещё нет.
    """
    total = round(max(income, 0) * share / 1000) * 1000
    weights = {category: (current or {}).get(category, 0) for category in CATEGORIES
               if category != "долги"}
    weight_sum = sum(weights.values()) or 1
    limits = {category: round(total * weight / weight_sum / 100) * 100
              for category, weight in weights.items()}
    limits["долги"] = 0
    return limits, float(total)


async def history_summary(months: int = 2) -> tuple[str, int]:
    """(текст со статистикой для ИИ, сколько дней истории есть в базе)."""
    days = months * 30
    transactions = await get_transactions(days=days)
    if not transactions:
        return "", 0

    try:
        oldest = min(datetime.fromisoformat(t["created_at"]) for t in transactions)
        days_of_history = max(1, (datetime.now() - oldest).days)
    except (TypeError, ValueError):
        days_of_history = 0

    by_month: dict[str, dict[str, float]] = {}
    income_by_month: dict[str, float] = {}
    for row in transactions:
        month = (row["created_at"] or "")[:7]
        if not month:
            continue
        if row["tx_type"] == "expense":
            bucket = by_month.setdefault(month, {})
            bucket[row["category"]] = bucket.get(row["category"], 0) + row["amount"]
        elif row["tx_type"] == "income":
            income_by_month[month] = income_by_month.get(month, 0) + row["amount"]

    if not by_month:
        return "", days_of_history

    lines = [f"Статистика трат за последние {months} мес (ключ ГГГГ-ММ):"]
    for month in sorted(by_month):
        categories = ", ".join(f"{category}: {amount:.0f}" for category, amount
                               in sorted(by_month[month].items(), key=lambda item: -item[1]))
        income = income_by_month.get(month, 0)
        lines.append(f"- {month}: {categories}" + (f"; доход {income:.0f}" if income else ""))

    limits = await get_limits()
    total_limit = await get_total_limit()
    lines.append("")
    lines.append("Текущие лимиты: " + ", ".join(f"{category}: {value:.0f}" for category, value
                                                     in limits.items() if value) + f"; всего {total_limit:.0f}")
    debts = await get_debts()
    if debts:
        lines.append("Кредиты: " + ", ".join(f"{debt['name']} — {debt['current_amount']:.0f} "
                                              f"(платёж {debt['min_payment']:.0f})" for debt in debts))
    lines.append(f"Сегодня: {datetime.now().strftime('%d.%m.%Y')} ({month_name_ru()})")
    return "\n".join(lines), days_of_history


def history_enough(days_of_history: int) -> bool:
    return days_of_history >= BUDGET_MIN_DAYS


def empty_budget_hint(days_of_history: int) -> str:
    """Что сказать, если данных для ИИ ещё мало."""
    left = max(1, BUDGET_MIN_DAYS - days_of_history)
    return ("🤖 Для предложения бюджета нужно хотя бы "
            f"{BUDGET_MIN_DAYS} дней истории трат.\n"
            f"Уже накоплено: {days_of_history} дн. Записывай траты ещё ~{left} дн. — "
            "и ИИ посчитает бюджет сам.")
