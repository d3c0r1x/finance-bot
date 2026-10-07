"""Scheduled digest text built only from the canonical Core report DTO."""

from __future__ import annotations

from collections.abc import Mapping

from services.python.presentation.report_renderer import (
    _display_money,
    _validated_report,
    rolling_food_lines,
)


def render_digest(report: Mapping[str, object], kind: str, language: str,
                  goal_outcome: Mapping[str, object] | None = None) -> str | None:
    """Format a daily summary or weekly digest; return None when Core has no records."""
    if kind not in {"daily", "weekly"} or language not in {"ru", "en"}:
        raise ValueError("Unsupported digest kind or language")
    if goal_outcome is not None and kind != "weekly":
        raise ValueError("Goal outcomes can only be included in a weekly digest")
    from_date, to_date, _categories, _days, _budget, food = _validated_report(report)
    count = report["transactionCount"]
    if type(count) is not int or count < 0:
        raise ValueError("transactionCount must be a non-negative integer")
    if count == 0:
        return "\n".join(_goal_outcome_lines(goal_outcome, report["currency"], language)) \
            if goal_outcome is not None else None

    currency = report["currency"]
    if language == "ru":
        heading = "Ежедневная сводка" if kind == "daily" else "Недельный дайджест"
        lines = [f"{heading} · {from_date:%d.%m.%Y}" if kind == "daily"
                 else f"{heading} · {from_date:%d.%m}–{to_date:%d.%m.%Y}",
                 f"Доходы: {_display_money(report['incomeTotal'], currency, language)}",
                 f"Расходы: {_display_money(report['expenseTotal'], currency, language)}",
                 f"Платежи по долгам: {_display_money(report['debtPaymentTotal'], currency, language)}",
                 f"Возвраты: {_display_money(report['refundTotal'], currency, language)}"]
        lines.extend(rolling_food_lines(food, currency, language))
        lines.extend(_goal_outcome_lines(goal_outcome, currency, language))
        return "\n".join(lines)

    heading = "Daily summary" if kind == "daily" else "Weekly digest"
    lines = [f"{heading} · {from_date:%Y-%m-%d}" if kind == "daily"
             else f"{heading} · {from_date:%Y-%m-%d}–{to_date:%Y-%m-%d}",
             f"Income: {_display_money(report['incomeTotal'], currency, language)}",
             f"Expenses: {_display_money(report['expenseTotal'], currency, language)}",
             f"Debt payments: {_display_money(report['debtPaymentTotal'], currency, language)}",
             f"Refunds: {_display_money(report['refundTotal'], currency, language)}"]
    lines.extend(rolling_food_lines(food, currency, language))
    lines.extend(_goal_outcome_lines(goal_outcome, currency, language))
    return "\n".join(lines)


def _goal_outcome_lines(outcome: Mapping[str, object] | None, currency: object,
                        language: str) -> list[str]:
    if outcome is None:
        return []
    name = outcome["name"]
    if outcome["unit"] == "count":
        bought, target = outcome["bought"], outcome["countTarget"]
        return ([f"Цель недели завершена: {name}", f"Куплено: {bought} из {target}"]
                if language == "ru" else
                [f"Weekly goal completed: {name}", f"Bought: {bought} of {target}"])

    spent = outcome["spent"]
    monthly_limit = outcome["monthlyLimit"]
    if language == "ru":
        spent_text = (_display_money(spent, currency, language) if spent is not None else "неизвестно")
        limit_text = (_display_money(monthly_limit, currency, language)
                      if monthly_limit is not None else "не задан")
        return [f"Цель недели завершена: {name}",
                f"Потрачено: {spent_text}", f"Лимит: {limit_text}"]
    spent_text = (_display_money(spent, currency, language) if spent is not None else "unknown")
    limit_text = (_display_money(monthly_limit, currency, language)
                  if monthly_limit is not None else "not set")
    return [f"Weekly goal completed: {name}",
            f"Spent: {spent_text} / monthly limit {limit_text}"]
