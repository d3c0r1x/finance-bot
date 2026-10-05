"""Scheduled digest text built only from the canonical Core report DTO."""

from __future__ import annotations

from collections.abc import Mapping

from services.python.presentation.report_renderer import _validated_report, rolling_food_lines


def render_digest(report: Mapping[str, object], kind: str, language: str) -> str | None:
    """Format a daily summary or weekly digest; return None when Core has no records."""
    if kind not in {"daily", "weekly"} or language not in {"ru", "en"}:
        raise ValueError("Unsupported digest kind or language")
    from_date, to_date, _categories, _days, _budget, food = _validated_report(report)
    count = report["transactionCount"]
    if type(count) is not int or count < 0:
        raise ValueError("transactionCount must be a non-negative integer")
    if count == 0:
        return None

    currency = report["currency"]
    if language == "ru":
        heading = "Ежедневная сводка" if kind == "daily" else "Недельный дайджест"
        lines = [f"{heading} · {from_date:%d.%m.%Y}" if kind == "daily"
                 else f"{heading} · {from_date:%d.%m}–{to_date:%d.%m.%Y}",
                 f"Доходы: {report['incomeTotal']} {currency}",
                 f"Расходы: {report['expenseTotal']} {currency}",
                 f"Платежи по долгам: {report['debtPaymentTotal']} {currency}",
                 f"Возвраты: {report['refundTotal']} {currency}"]
        lines.extend(rolling_food_lines(food, currency, language))
        return "\n".join(lines)

    heading = "Daily summary" if kind == "daily" else "Weekly digest"
    lines = [f"{heading} · {from_date:%Y-%m-%d}" if kind == "daily"
             else f"{heading} · {from_date:%Y-%m-%d}–{to_date:%Y-%m-%d}",
             f"Income: {report['incomeTotal']} {currency}",
             f"Expenses: {report['expenseTotal']} {currency}",
             f"Debt payments: {report['debtPaymentTotal']} {currency}",
             f"Refunds: {report['refundTotal']} {currency}"]
    lines.extend(rolling_food_lines(food, currency, language))
    return "\n".join(lines)
