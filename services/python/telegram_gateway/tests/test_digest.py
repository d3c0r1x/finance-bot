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


def test_goal_outcome_renders_count_in_russian_and_sum_in_english():
    count_text = render_digest(report(), "weekly", "ru", {
        "id": "0199b81a-4a9c-7000-8000-000000000003", "name": "Кофе", "unit": "count",
        "bought": 3, "countTarget": 4, "spent": None, "monthlyLimit": None,
        "met": False, "completedAt": "2026-10-01T12:00:00Z",
    })
    sum_text = render_digest(report(), "weekly", "en", {
        "id": "0199b81a-4a9c-7000-8000-000000000004", "name": "Groceries", "unit": "sum",
        "bought": 2, "countTarget": 0, "spent": "450.00", "monthlyLimit": "5000.00",
        "met": True, "completedAt": "2026-10-01T12:00:00Z",
    })

    assert "Цель недели завершена: Кофе" in count_text
    assert "Куплено: 3 из 4" in count_text
    assert "Weekly goal completed: Groceries" in sum_text
    assert "Spent: 450.00 / monthly limit 5000.00 RUB" in sum_text


def test_unknown_goal_sum_is_explicit_and_empty_report_still_sends_outcome():
    outcome = {
        "id": "0199b81a-4a9c-7000-8000-000000000005", "name": "Unknown sum", "unit": "sum",
        "bought": 0, "countTarget": 0, "spent": None, "monthlyLimit": None,
        "met": None, "completedAt": "2026-10-01T12:00:00Z",
    }
    empty = report(transactionCount=0, incomeTotal="0.00", expenseTotal="0.00", debtPaymentTotal="0.00",
                   refundTotal="0.00", expenseByCategory={},
                   expenseByDay={date: "0.00" for date in report()["expenseByDay"]})

    text = render_digest(empty, "weekly", "en", outcome)

    assert "Weekly goal completed: Unknown sum" in text
    assert "Spent: unknown" in text
    assert "0.00" not in text
    assert render_digest(empty, "weekly", "en") is None


def test_daily_digest_rejects_goal_outcome():
    try:
        render_digest(report(), "daily", "en", {"id": "outcome"})
    except ValueError as error:
        assert "weekly" in str(error)
    else:
        raise AssertionError("daily digest accepted a weekly goal outcome")


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
