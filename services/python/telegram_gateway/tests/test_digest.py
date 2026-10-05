from services.python.telegram_gateway import digest
from services.python.telegram_gateway.digest import render_digest


def report(**overrides):
    value = {
        "period": "week",
        "scope": "personal",
        "fromDate": "2026-09-29",
        "toDate": "2026-10-05",
        "asOfDate": "2026-10-05",
        "timezone": "Europe/Moscow",
        "currency": "RUB",
        "incomeTotal": "1000.00",
        "expenseTotal": "400.00",
        "debtPaymentTotal": "0.00",
        "refundTotal": "0.00",
        "transactionCount": 2,
        "expenseByCategory": {"food": "400.00"},
        "expenseByDay": {
            "2026-09-29": "0.00", "2026-09-30": "0.00", "2026-10-01": "0.00",
            "2026-10-02": "0.00", "2026-10-03": "0.00", "2026-10-04": "0.00",
            "2026-10-05": "400.00",
        },
        "weekendSharePercent": None,
        "monthlyBudgetLimit": None,
        "monthlyBudgetRemaining": None,
        "rolling7FoodStatus": {
            "fromDate": "2026-09-29", "toDate": "2026-10-05", "limit": "500.00",
            "spent": "400.00", "remaining": "100.00", "limitStatus": "normal",
            "usualWeeklySpend": None, "historyWeeks": 0, "paceStatus": "insufficient_history",
            "paceShare": None,
        },
    }
    value.update(overrides)
    return value


def test_daily_digest_uses_core_values_and_renders_russian():
    text = render_digest(report(period="custom", fromDate="2026-10-05", toDate="2026-10-05",
                               expenseByDay={"2026-10-05": "400.00"}), "daily", "ru")

    assert "Ежедневная сводка" in text
    assert "Расходы: 400.00 RUB" in text
    assert "Еда за 7 дней: 400.00 / 500.00 RUB" in text
    assert "Обычный недельный расход" not in text


def test_weekly_digest_uses_core_values_and_renders_english():
    text = render_digest(report(), "weekly", "en")

    assert "Weekly digest" in text
    assert "Expenses: 400.00 RUB" in text
    assert "Rolling food, last 7 days: 400.00 / 500.00 RUB" in text
    assert "Usual weekly spend" not in text


def test_digest_with_no_transactions_is_suppressed():
    assert render_digest(report(transactionCount=0, incomeTotal="0.00", expenseTotal="0.00",
                                debtPaymentTotal="0.00", refundTotal="0.00",
                                expenseByCategory={},
                                expenseByDay={"2026-09-29": "0.00", "2026-09-30": "0.00",
                                              "2026-10-01": "0.00", "2026-10-02": "0.00",
                                              "2026-10-03": "0.00", "2026-10-04": "0.00",
                                              "2026-10-05": "0.00"}), "weekly", "ru") is None


def test_scheduled_digest_uses_shared_report_renderer_for_rolling_food(monkeypatch):
    calls = []

    def shared_renderer(status, currency, language):
        calls.append((status, currency, language))
        return ["Shared rolling-food output"]

    monkeypatch.setattr(digest, "rolling_food_lines", shared_renderer)
    text = render_digest(report(), "weekly", "ru")

    assert "Shared rolling-food output" in text
    assert calls == [(report()["rolling7FoodStatus"], "RUB", "ru")]
