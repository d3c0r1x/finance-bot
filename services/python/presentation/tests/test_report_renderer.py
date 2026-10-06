import io

import pytest
from PIL import Image

from services.python.presentation.report_renderer import (
    RendererUnavailable,
    render_personal_inflation,
    render_recurring_projection,
    render_report,
    render_report_png,
    report_text,
)


REPORT = {
    "period": "custom",
    "scope": "personal",
    "fromDate": "2026-10-01",
    "toDate": "2026-10-03",
    "timezone": "Europe/Moscow",
    "currency": "RUB",
    "incomeTotal": "100.00",
    "expenseTotal": "60.00",
    "debtPaymentTotal": "0.00",
    "refundTotal": "0.00",
    "transactionCount": 2,
    "expenseByCategory": {"food": "10.00", "other": "50.00"},
    "expenseByDay": {"2026-10-01": "10.00", "2026-10-02": "0.00", "2026-10-03": "50.00"},
}


def test_renderer_outputs_readable_png_from_server_report_dto():
    waste = {"available": True, "reasonCode": "available", "completeness": "complete",
             "reviewedSpend": "120.00", "optionalSpend": "20.00", "optionalShare": "0.166667",
             "reviewedItemCount": 5, "optionalItemCount": 2, "missingAmountCount": 0,
             "bySource": {"model": "13.00", "rule": "7.00"},
             "topItems": [{"name": "Сок", "amount": "13.00", "verdict": "optional", "source": "model"}],
             "corrected": [{"productName": "Молоко", "count": 1, "amount": "30.00"}]}
    data = render_report_png({**REPORT, "monthlyBudgetLimit": "100.00", "monthlyBudgetRemaining": "40.00",
                             "waste": waste})
    assert data.startswith(b"\x89PNG\r\n\x1a\n")
    with Image.open(io.BytesIO(data)) as image:
        assert image.format == "PNG"
        assert image.size[0] == 960
        assert image.size[1] > 640
        assert len(image.getcolors(maxcolors=1_000_000) or []) > 3
        assert image.getpixel((300, 195)) == (120, 174, 230)


def test_text_report_includes_available_optional_spend_evidence():
    report = {**REPORT, "waste": {
        "available": True, "reasonCode": "available", "completeness": "complete",
        "reviewedSpend": "120.00", "optionalSpend": "20.00", "optionalShare": "0.166667",
        "reviewedItemCount": 5, "optionalItemCount": 2, "missingAmountCount": 0,
        "bySource": {"model": "13.00", "rule": "7.00"},
        "topItems": [{"name": "Сок", "amount": "13.00", "verdict": "optional", "source": "model"}],
        "corrected": [{"productName": "Молоко", "count": 1, "amount": "30.00"}],
    }}

    text = report_text(report)

    assert "Optional purchases: 20.00 RUB (16.7% of reviewed 120.00 RUB)" in text
    assert "Source model: 13.00 RUB" in text
    assert "Сок: 13.00 RUB (model)" in text
    assert "Corrected: Молоко · 30.00 RUB" in text


def test_text_report_explains_partial_waste_without_zero_placeholders():
    report = {**REPORT, "waste": {
        "available": False, "reasonCode": "missing_amounts", "completeness": "partial",
        "reviewedSpend": None, "optionalSpend": None, "optionalShare": None,
        "reviewedItemCount": 2, "optionalItemCount": 0, "missingAmountCount": 1,
        "bySource": {}, "topItems": [], "corrected": [],
    }}

    text = report_text(report)

    assert "Optional purchases unavailable: 1 receipt item has no amount; totals not calculated" in text
    assert "Optional purchases: 0.00" not in text

    content_type, data = render_report(report, png_renderer=lambda _report: unavailable_png())
    assert content_type == "text/plain; charset=utf-8"
    assert b"Optional purchases unavailable: 1 receipt item has no amount" in data


def test_renderer_falls_back_to_text_when_image_support_is_unavailable():
    def unavailable(_report):
        raise RendererUnavailable("Pillow unavailable")

    content_type, data = render_report(REPORT, png_renderer=unavailable)
    assert content_type == "text/plain; charset=utf-8"
    assert b"Expenses: 60.00 RUB" in data
    assert b"food: 10.00 RUB" in data
    assert b"2026-10-03: 50.00 RUB" in data


def test_png_caps_overrun_bar_and_keeps_exact_negative_remaining_visible():
    report = {**REPORT, "monthlyBudgetLimit": "100.00", "monthlyBudgetRemaining": "-5.00"}
    data = render_report_png(report)
    with Image.open(io.BytesIO(data)) as image:
        assert image.getpixel((515, 195)) == (120, 174, 230)
        assert image.getpixel((550, 195)) != (120, 174, 230)


def test_text_fallback_includes_exact_monthly_budget_remaining():
    report = {**REPORT, "monthlyBudgetLimit": "100.00", "monthlyBudgetRemaining": "-5.00"}
    _content_type, data = render_report(report, png_renderer=lambda _report: unavailable_png())
    assert b"Monthly budget: 100.00 RUB; remaining: -5.00 RUB" in data


def test_text_report_uses_core_rolling_food_amounts_without_inventing_history():
    report = {
        **REPORT,
        "rolling7FoodStatus": {
            "fromDate": "2026-09-28",
            "toDate": "2026-10-04",
            "limit": "1000.00",
            "spent": "430.00",
            "remaining": "570.00",
            "limitStatus": "normal",
            "usualWeeklySpend": None,
            "historyWeeks": 0,
            "paceStatus": "insufficient_history",
            "paceShare": None,
        },
    }

    text = report_text(report)

    assert "Rolling food, last 7 days: 430.00 / 1000.00 RUB" in text
    assert "Food limit remaining: 570.00 RUB" in text
    assert "Historical pace: insufficient history" in text
    assert "Usual weekly spend" not in text


def test_text_report_includes_core_historical_weekly_food_pace():
    report = {
        **REPORT,
        "rolling7FoodStatus": {
            "fromDate": "2026-09-28",
            "toDate": "2026-10-04",
            "limit": "2000.00",
            "spent": "1830.00",
            "remaining": "170.00",
            "limitStatus": "near",
            "usualWeeklySpend": "1500.00",
            "historyWeeks": 4,
            "paceStatus": "over",
            "paceShare": "122.00",
        },
    }

    text = report_text(report)

    assert "Rolling food, last 7 days: 1830.00 / 2000.00 RUB" in text
    assert "Usual weekly spend: 1500.00 RUB (4 weeks)" in text


def test_renderer_rejects_disabled_food_limit_with_nonzero_limit_amount():
    report = {
        **REPORT,
        "rolling7FoodStatus": {
            "fromDate": "2026-09-28",
            "toDate": "2026-10-04",
            "limit": "1000.00",
            "spent": "430.00",
            "remaining": "570.00",
            "limitStatus": "disabled",
            "usualWeeklySpend": None,
            "historyWeeks": 0,
            "paceStatus": "insufficient_history",
            "paceShare": None,
        },
    }

    with pytest.raises(ValueError, match="limit status must match limit amount"):
        report_text(report)


def unavailable_png():
    raise RendererUnavailable("Pillow unavailable")


def test_renderer_rejects_mismatched_monthly_budget_fields():
    report = {**REPORT, "monthlyBudgetLimit": "100.00", "monthlyBudgetRemaining": None}
    with pytest.raises(ValueError, match="must both be provided"):
        render_report_png(report)


def test_shopping_renderer_explains_bought_muted_and_blocked_candidates():
    from services.python.presentation.report_renderer import render_shopping_candidates

    candidate = {"productKey": "freshmilk", "productName": "Молоко 1 л", "purchaseCount": 3,
                 "medianIntervalDays": 10, "usualUnitPrice": "100.000000", "estimatedCost": "100.00",
                 "lastPurchasedAt": "2026-10-04T00:00:00Z", "dueAt": "2026-10-05T00:00:00Z",
                 "daysUntilDue": 0}
    text = render_shopping_candidates({"candidates": [], "estimatedListCost": "0.00", "inventoryTracked": False,
        "boughtCandidates": [{**candidate, "productKey": "boughtmilk"}],
        "mutedCandidates": [{**candidate, "productKey": "mutedmilk"}],
        "blockedCandidates": [{"productKey": "blockedmilk", "productName": "Молоко 1 л",
                               "reasonCode": "confirmed_not_to_buy"}]})

    assert "Уже куплено" in text
    assert "Активных подсказок нет." in text
    assert "Вы скрыли" in text
    assert "не брать" in text.lower()


@pytest.mark.parametrize("field,value", [
    ("expenseTotal", "60.001"),
    ("expenseByDay", {"2026-10-02": "-1.00"}),
    ("expenseByCategory", {"food": 10.0}),
])
def test_renderer_rejects_invalid_financial_dto_values(field, value):
    report = {**REPORT, field: value}
    with pytest.raises(ValueError):
        render_report_png(report)


def _inflation_item(name, old, new, weight, change, older=2, window=1):
    return {
        "productName": name,
        "oldUnitPrice": old,
        "newUnitPrice": new,
        "oldSpendWeight": weight,
        "changePercent": change,
        "olderPurchaseCount": older,
        "windowPurchaseCount": window,
    }


def test_personal_inflation_renderer_shows_explicit_insufficient_history_without_totals():
    dto = {
        "available": False,
        "reasonCode": "insufficient_history",
        "asOf": "2026-10-06T12:00:00Z",
        "windowDays": 90,
        "productCount": 0,
        "basketBefore": None,
        "basketNow": None,
        "indexPercent": None,
        "rising": [],
        "falling": [],
    }

    text = render_personal_inflation(dto)

    assert "90 дней" in text
    assert "минимум 3 товара" in text
    assert "100.00" not in text
    assert "не официальная статистика" in text.lower()


def test_personal_inflation_renderer_shows_core_weighted_totals_and_top_products():
    dto = {
        "available": True,
        "reasonCode": "available",
        "asOf": "2026-10-06T12:00:00Z",
        "windowDays": 90,
        "productCount": 4,
        "basketBefore": "2510.00",
        "basketNow": "2334.00",
        "indexPercent": "-7.01",
        "rising": [_inflation_item("Coffee", "100.00", "110.00", "500.00", "10.00")],
        "falling": [_inflation_item("Milk", "200.00", "180.00", "700.00", "-10.00")],
    }

    text = render_personal_inflation(dto)

    assert "2 510,00" in text
    assert "2 334,00" in text
    assert "-7,01%" in text
    assert "Coffee" in text and "+10,00%" in text
    assert "Milk" in text and "-10,00%" in text
    assert "только цены из ваших чеков" in text.lower()
    assert "не официальная статистика" in text.lower()


@pytest.mark.parametrize("changes", [
    {"basketBefore": "0.00"},
    {"productCount": 2},
    {"rising": [_inflation_item("Coffee", "100.00", "90.00", "500.00", "-10.00")]},
    {"falling": [_inflation_item("Milk", "200.00", "220.00", "700.00", "10.00")]},
])
def test_personal_inflation_renderer_rejects_inconsistent_available_dto(changes):
    dto = {
        "available": True,
        "reasonCode": "available",
        "asOf": "2026-10-06T12:00:00Z",
        "windowDays": 90,
        "productCount": 4,
        "basketBefore": "2510.00",
        "basketNow": "2334.00",
        "indexPercent": "-7.01",
        "rising": [_inflation_item("Coffee", "100.00", "110.00", "500.00", "10.00")],
        "falling": [_inflation_item("Milk", "200.00", "180.00", "700.00", "-10.00")],
    }
    dto.update(changes)

    with pytest.raises(ValueError):
        render_personal_inflation(dto)


def test_recurring_renderer_separates_three_day_warnings_and_overdue_series():
    phone = _recurring_series("1", "Phone plan", "2026-08-15", "2026-08-22", 2)
    old = _recurring_series("2", "Old payment", "2026-08-06", "2026-08-13", -7)
    text = render_recurring_projection({
        "algorithmVersion": "recurring.v1", "completeness": "complete", "timeZone": "Europe/Moscow",
        "asOf": "2026-08-20T00:00:00+03:00", "expenseSeries": [phone, old], "incomeSeries": [],
        "dueSoon": [phone], "overdue": [old], "nextIncome": None, "monthlyExpenseEstimate": "857.14",
        "monthlyExpenseEstimates": {"RUB": "857.14"}, "mutedSeries": [],
    })
    soon = text.split("Регулярные расходы:")[0]
    overdue = text.split("Просрочено (не входит в предупреждения):")[-1]
    assert "Phone plan" in soon
    assert "интервал 7–7 дн." in soon
    assert "Old payment" not in soon
    assert "Old payment" in overdue and "просрочено на 7 дн." in overdue
    assert "8 57" not in text and "857,14" in text


def test_recurring_renderer_shows_no_fake_totals_without_history():
    text = render_recurring_projection({
        "algorithmVersion": "recurring.v1", "completeness": "complete", "timeZone": "UTC",
        "asOf": "2026-08-20T00:00:00Z", "expenseSeries": [], "incomeSeries": [], "dueSoon": [],
        "overdue": [], "nextIncome": None, "monthlyExpenseEstimate": None, "monthlyExpenseEstimates": {},
        "mutedSeries": [],
    })
    assert "минимум 3" in text
    assert "0,00" not in text


def test_recurring_renderer_keeps_muted_series_visible_without_counting_them_active():
    muted = _recurring_series("4", "Cloud backup", "2026-08-15", "2026-08-22", 2)
    text = render_recurring_projection({
        "algorithmVersion": "recurring.v1", "completeness": "complete", "timeZone": "UTC",
        "asOf": "2026-08-20T00:00:00Z", "expenseSeries": [], "incomeSeries": [], "dueSoon": [],
        "overdue": [], "nextIncome": None, "monthlyExpenseEstimate": None, "monthlyExpenseEstimates": {},
        "mutedSeries": [muted],
    })
    assert "Отключённые напоминания" in text and "Cloud backup" in text
    assert "Пока не нашёл регулярных операций" not in text
    assert "В месяц на расходы" not in text


def _recurring_series(identifier, name, last, next_date, days):
    return {"id": identifier * 32, "key": name.lower(), "name": name, "category": "services", "type": "expense",
            "currency": "RUB", "amount": "100.00", "minAmount": "100.00", "maxAmount": "100.00",
            "periodCode": "week", "periodDays": 7, "minIntervalDays": 7, "maxIntervalDays": 7,
            "occurrences": 3, "lastDate": last, "nextDate": next_date, "daysUntil": days}
