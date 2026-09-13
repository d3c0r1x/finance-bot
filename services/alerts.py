"""Алерты при приближении к лимитам и превышении. Лимиты берутся из бюджета (services/budget.py)."""
from config import MONTHLY_LIMITS
from utils.formatting import format_amount, get_category_emoji, progress_bar


def _limit_of(category: str, limits: dict | None) -> float:
    return (limits or MONTHLY_LIMITS).get(category, 0) or 0


def check_limits_alert(category: str, spending: dict, limits: dict | None = None) -> str:
    limit = _limit_of(category, limits)
    if not limit:  # лимит 0 — алерты отключены для категории
        return ""
    spent = spending.get(category, 0)
    percentage = (spent / limit) * 100

    if percentage >= 100:
        return (f"🚨 **Лимит по категории «{category}» превышен!**\n"
                f"Потрачено {format_amount(spent)} из {format_amount(limit)}")
    if percentage >= 90:
        return (f"⚠️ **Внимание!** Категория «{category}» почти исчерпана: "
                f"{percentage:.0f}% ({format_amount(spent)} из {format_amount(limit)})")
    return ""


def category_status(category: str, spending: dict, limits: dict | None = None) -> str:
    """Строка о состоянии лимита: «🍕 еда: ███░░░░░░░ 32% — 14 400 ₽ из 45 000 ₽»."""
    limit = _limit_of(category, limits)
    if not limit:
        return ""
    spent = spending.get(category, 0)
    percent = min(100, int((spent / limit) * 100))
    marker = "🚨" if percent >= 100 else "⚠️" if percent >= 90 else get_category_emoji(category)
    return (f"{marker} {category}: {progress_bar(percent)} {percent}% — "
            f"{format_amount(spent)} из {format_amount(limit)}")


def get_daily_alerts(spending: dict, limits: dict | None = None) -> list[str]:
    """Все категории, где лимит исчерпан или почти исчерпан."""
    alerts = []
    current = limits or MONTHLY_LIMITS
    for category, limit in current.items():
        if not limit:
            continue
        spent = spending.get(category, 0)
        percentage = (spent / limit) * 100
        if percentage >= 100:
            alerts.append(f"🚨 «{category}»: {format_amount(spent)} из {format_amount(limit)} — лимит превышен!")
        elif percentage >= 90:
            alerts.append(f"⚠️ «{category}»: {percentage:.0f}% лимита ({format_amount(spent)} из {format_amount(limit)})")
    return alerts
