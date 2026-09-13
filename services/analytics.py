"""Аналитика: отчёты, прогнозы, средние траты и темп бюджета."""
from datetime import datetime, timedelta

from config import MONTHLY_LIMITS, TOTAL_MONTHLY_LIMIT
from utils.formatting import (format_amount, get_category_emoji, month_name_ru, plural_ru,
                              progress_bar)

PERIOD_TITLES = {7: "за неделю", 14: "за 2 недели", 30: "за месяц", 90: "за 90 дней"}


async def build_month_report(spending: dict, total_spent: float, limits: dict | None = None,
                            total_limit: float | None = None) -> str:
    """Текст месячного отчёта с прогресс-барами по лимитам (из бюджета)."""
    limits = limits if limits is not None else MONTHLY_LIMITS
    total_limit = TOTAL_MONTHLY_LIMIT if total_limit is None else total_limit
    text = "📊 **Отчёт за текущий месяц:**\n\n"
    text += f"💸 Всего потрачено: **{format_amount(total_spent)}**\n"
    text += f"🎯 Лимит: {format_amount(total_limit)}\n"
    remaining = total_limit - total_spent
    if remaining >= 0:
        text += f"✅ Остаток: {format_amount(remaining)}\n\n"
    else:
        text += f"🚨 Перерасход: {format_amount(abs(remaining))}\n\n"

    text += f"**По категориям ({month_name_ru()}):**\n"
    for category, amount in sorted(spending.items(), key=lambda x: -x[1]):
        emoji = get_category_emoji(category)
        limit = limits.get(category)
        if limit:
            pct = min(100, int((amount / limit) * 100))
            bar = progress_bar(pct)
            text += f"{emoji} {category}: {bar} {format_amount(amount)} ({pct}%)\n"
        else:
            text += f"{emoji} {category}: {format_amount(amount)}\n"

    forecast = forecast_end_of_month(total_spent)
    if forecast:
        text += f"\n📈 Прогноз к концу месяца: **{format_amount(forecast)}**"
        pace = budget_pace(total_spent, total_limit)
        if pace:
            text += f"\n{pace}"
    return text


def by_category(transactions) -> dict[str, float]:
    """Суммы трат по категориям за период (доходы и платежи по долгам не считаем)."""
    totals: dict[str, float] = {}
    for row in transactions:
        if row["tx_type"] != "expense":
            continue
        totals[row["category"]] = totals.get(row["category"], 0) + row["amount"]
    return totals


def budget_pace(spent: float, limit: float, now: datetime | None = None) -> str:
    """Показывает темп: можно ли тратить с текущей скоростью до конца месяца."""
    if not limit:
        return ""
    now = now or datetime.now()
    days_in_month = (now.replace(month=now.month % 12 + 1, day=1) - now.replace(day=1)).days \
        if now.month != 12 else 31
    day = max(1, now.day)
    forecast = spent / day * days_in_month
    if forecast > limit * 1.05:
        return (f"🔴 Темп выше бюджета примерно на {format_amount(forecast - limit)} — "
                "сейчас лучше сократить необязательные покупки.")
    if forecast > limit:
        return f"🟡 Темп немного выше лимита: прогноз {format_amount(forecast)}."
    reserve = limit - forecast
    return f"🟢 Темп в норме: к концу месяца останется запас около {format_amount(reserve)}."


async def build_period_report(transactions, days: int = 7, limits: dict | None = None) -> str:
    """Отчёт за произвольный период: суммы по категориям и последние траты."""
    title = PERIOD_TITLES.get(days, f"за {days} дней")
    expenses_list = [t for t in transactions if t["tx_type"] == "expense"]
    if not expenses_list:
        return f"📭 Трат {title} не было."
    by_category_totals: dict = by_category(transactions)
    total = sum(by_category_totals.values())
    text = f"📆 **Отчёт {title}:**\n\n"
    text += (f"💸 Всего: **{format_amount(total)}** "
             f"({len(expenses_list)} {plural_ru(len(expenses_list), 'трата', 'траты', 'трат')})\n")
    text += f"📈 В среднем за день: {format_amount(round(total / max(days, 1)))}\n\n"
    text += "**По категориям:**\n"
    for category, amount in sorted(by_category_totals.items(), key=lambda x: -x[1]):
        share = int(amount / total * 100) if total else 0
        text += f"• {get_category_emoji(category)} {category}: {format_amount(amount)} ({share}%)\n"
    text += "\n**Последние траты:**\n"
    for t in sorted(expenses_list, key=lambda x: x["created_at"], reverse=True)[:5]:
        try:
            stamp = datetime.fromisoformat(t["created_at"]).strftime("%d.%m %H:%M")
        except (TypeError, ValueError):
            stamp = ""
        text += f"• {stamp} — {format_amount(t['amount'])} ({t['category']})\n"
    return text


async def build_week_report(transactions) -> str:
    """Текст недельного отчёта."""
    return await build_period_report(transactions, days=7)


def forecast_end_of_month(total_spent: float, now: datetime | None = None) -> float | None:
    """Прогноз к концу месяца с корректным числом дней и безопасным первым числом."""
    now = now or datetime.now()
    if now.day < 2 or total_spent <= 0:
        return None
    days_in_month = (now.replace(month=now.month % 12 + 1, day=1) - timedelta(days=1)).day \
        if now.month < 12 else 31
    return round(total_spent / now.day * days_in_month, 2)


def forecast_debt_payoff(balance: float, min_payment: float, interest_rate: float | None,
                         max_months: int = 600) -> int | None:
    """Сколько месяцев до полного погашения при фиксированном платеже."""
    if balance <= 0:
        return 0
    if not min_payment or min_payment <= 0:
        return None
    monthly_rate = (interest_rate or 0) / 100 / 12
    months = 0
    remaining = balance
    while remaining > 0 and months < max_months:
        interest = remaining * monthly_rate
        principal = min_payment - interest
        if principal <= 0:
            return None
        remaining -= principal
        months += 1
    return months if remaining <= 0 else None
