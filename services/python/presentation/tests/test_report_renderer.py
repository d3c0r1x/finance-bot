import io

import pytest
from PIL import Image

from services.python.presentation.report_renderer import (
    RendererUnavailable,
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
    data = render_report_png({**REPORT, "monthlyBudgetLimit": "100.00", "monthlyBudgetRemaining": "40.00"})
    assert data.startswith(b"\x89PNG\r\n\x1a\n")
    with Image.open(io.BytesIO(data)) as image:
        assert image.format == "PNG"
        assert image.size == (960, 640)
        assert len(image.getcolors(maxcolors=1_000_000) or []) > 3
        assert image.getpixel((300, 195)) == (120, 174, 230)


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
