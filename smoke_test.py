"""Смоук-тест: импорты всех модулей + логика БД/аналитики/экспорта (без Telegram и Ollama).
Запуск:  venv\Scripts\activate && set PYTHONIOENCODING=utf-8 && python smoke_test.py
"""
import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["FINANCE_DB"] = "test_finance.db"  # отдельная чистая БД для теста

_TEST_DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "test_finance.db")
if os.path.exists(_TEST_DB):
    os.remove(_TEST_DB)


async def main():
    # 1. Импорты всех модулей
    from database import db as database
    from ai import llm, ocr
    from keyboards import main_menu_kb, expense_kb, debt_kb, report_kb
    from handlers import main_menu, expenses, reports, debts, settings, onboarding
    from services import analytics, alerts, budget, charts, export, profile, scheduler
    print("✅ Все модули импортируются")

    # 2. Инициализация БД и начальные долги
    await database.init_db()
    debts = await database.get_debts()
    assert len(debts) == 4, f"ожидалось 4 долга, получено {len(debts)}"
    print("✅ БД создана, 4 начальных долга")

    # 3. Транзакции: расход, доход, платёж по долгу
    await database.add_transaction(111, 2000, "транспорт", "бензин", "Заправка", "expense", source="text")
    await database.add_transaction(222, 150000, "прочее", None, "Зарплата", "income", source="text")
    debt_payment = 400
    await database.add_transaction(111, debt_payment, "долги", None, "Платёж Сбербанк", "debt_payment", debt_target="sber", source="manual")
    sber = await database.get_debt("sber")
    assert sber["current_amount"] == sber["initial_amount"] - debt_payment, \
        f"остаток сбера неверный: {sber['current_amount']}"
    spending = await database.get_monthly_spending(user_id=111)
    assert spending.get("транспорт") == 2000, spending
    assert "долги" not in spending  # платёж по долгу — не расход, учитывается отдельно
    assert spending.get("прочее") is None  # доход не считается тратой
    total = await database.get_total_spent_this_month(user_id=111)
    assert total == 2000, total
    income = await database.get_month_income(user_id=222)
    assert income == 150000, income
    recent = await database.get_recent_transactions(111, limit=3)
    assert recent and recent[0]["user_id"] == 111
    removable = next(row for row in recent if row["tx_type"] == "expense")
    assert await database.get_transaction(removable["id"], 111)
    assert await database.get_transaction(removable["id"], 222) is None
    assert await database.delete_transaction(removable["id"], 111)
    assert await database.get_transaction(removable["id"], 111) is None
    print("✅ Транзакции, платежи по долгу, лимиты считаются верно")

    # 4. Полное погашение закрывает долг
    await database.add_transaction(111, sber["current_amount"], "долги", None, "Доплата", "debt_payment", debt_target="sber")
    sber = await database.get_debt("sber")
    assert sber["current_amount"] == 0 and sber["status"] == "closed"
    print("✅ Полное погашение закрывает долг")

    # 5. Фолбэк-парсер (Ollama не запущен — должно вернуться без исключения)
    parsed = await llm.parse_transaction("Заправка 2000")
    assert parsed["amount"] == 2000 and parsed["category"] == "транспорт", parsed
    parsed2 = await llm.parse_transaction("Зарплата 150000")
    assert parsed2["tx_type"] == "income", parsed2
    parsed3 = await llm.parse_transaction("Платёж Т-Банк 3000")
    assert parsed3["tx_type"] == "debt_payment" and parsed3["debt_target"] == "tbank", parsed3
    assert llm.fallback_parse("Доставка суши 1,5к")["amount"] == 1500
    assert llm.fallback_parse("Такси 2 тыс")["amount"] == 2000
    assert llm.fallback_parse("Аптека 640")["category"] == "здоровье"
    assert await llm.guess_category("нет") is None  # без Ollama — None, не исключение
    print("✅ Фолбэк-парсер работает (категории, доход, долги, «1,5к»)")

    # 6. Санитизация ответа LLM
    bad = llm._sanitize({"amount": "abc", "category": "неизвестное", "tx_type": "штора", "confidence": 5}, "тест")
    assert bad["category"] == "прочее" and bad["tx_type"] == "expense" and bad["confidence"] == 1.0, bad
    ok = llm._sanitize({"amount": "2 500,50", "category": "еда", "tx_type": "expense"}, "тест")
    assert ok["amount"] == 2500.50, ok
    debt = llm._sanitize({"amount": 5000, "category": "долги", "tx_type": "debt_payment"}, "Платёж сбербанк 5000")
    assert debt["debt_target"] == "sber", debt
    print("✅ Санитизация ответов LLM")

    # 6б. Разбор корзины: страховки по ключевым словам, повторы и рендер
    from ai.receipts import apply_review_rules, basket_text
    from ai.llm import _sanitize_basket
    from services.purchase_history import compare_items
    beer = "Пиво Жигулёвское 1.35л"
    basket = [
        {"name": beer, "sum": 110.0},
        {"name": beer, "sum": 110.0},                        # тот же товар ещё раз — это норма
        {"name": "К.Ц.Изд.мак.ПЕРЬЯ В/С ГР.Б 400г", "sum": 10.93},   # макароны — не «лишнее»
        {"name": "Пакет ПЯТЕРОЧКА 65х40см", "sum": 4.43},
        {"name": "Масло сливочное 82%", "sum": 94.22},
        {"name": "Сметана 20%", "sum": 75.28},
        {"name": "Творог 9%", "sum": 84.35},
    ]
    # ответ модели «как бы он выглядел»: пиво полезное, макароны лишние, один и тот же совет на всех
    noisy = {"items": [
        {"index": 1, "verdict": "полезно", "reason": "удачный выбор", "note": "всегда полезно"},
        {"index": 2, "verdict": "полезно", "reason": "удачный выбор", "note": "всегда полезно"},
        {"index": 3, "verdict": "лишнее", "reason": "ненужные перья", "note": "можно без"},
        {"index": 4, "verdict": "нейтрально", "reason": "пакет нужен", "note": "взять меньше"},
        {"index": 5, "verdict": "нейтрально", "reason": "размер упаковки", "note": "взять меньше"},
        {"index": 6, "verdict": "нейтрально", "reason": "размер упаковки", "note": "взять меньше"},
        {"index": 7, "verdict": "полезно", "reason": "", "note": "заменить"},
    ], "summary": "сводка", "plan": ["взять пакет-сумку", " "], "save": 100}
    # как в бою: сначала санитайзер ответа модели, потом страховки по ключевым словам
    fixed = apply_review_rules(_sanitize_basket(noisy, len(basket)), basket)
    assert fixed["items"][1]["verdict"] == "вредно", fixed["items"][1]        # пиво не «полезно»
    assert "бутыл" in fixed["items"][1]["note"], fixed["items"][1]           # совет именно про пиво
    assert fixed["items"][3]["verdict"] == "нейтрально", fixed["items"][3]   # макароны — еда
    assert fixed["items"][3]["note"] == "" and fixed["items"][3]["reason"] == ""
    assert fixed["items"][4]["verdict"] == "лишнее" and "сумка" in fixed["items"][4]["note"]
    assert fixed["items"][5]["note"] == "" and fixed["items"][6]["note"] == ""  # повтор выброшен
    assert fixed["items"][7]["note"] == "" and fixed["items"][7]["reason"] == ""  # «заменить» — затычка
    assert fixed["plan"] == ["взять пакет-сумку"], fixed["plan"]            # пустой шаг выкинут
    # одинаковый совет допустим только для одинакового SKU; два разных снека должны
    # получить разные осмысленные варианты, а два одинаковых пива — не считаться дефектом.
    duplicate_skus = [
        {"name": "Чипсы Lays сыр 140г", "sum": 120.0},
        {"name": "Чипсы Lays сыр 140г", "sum": 120.0},
        {"name": "Чипсы Lays краб 81г", "sum": 80.0},
    ]
    duplicate_answer = {"items": {
        1: {"verdict": "вредно", "reason": "", "note": ""},
        2: {"verdict": "вредно", "reason": "", "note": ""},
        3: {"verdict": "вредно", "reason": "", "note": ""},
    }}
    distinct = apply_review_rules(duplicate_answer, duplicate_skus)
    advice_by_name = [(entry.get("reason", "") + entry.get("note", ""), duplicate_skus[index - 1]["name"])
                      for index, entry in distinct["items"].items() if entry.get("reason") or entry.get("note")]
    assert len({advice for advice, _ in advice_by_name}) >= 2, advice_by_name
    assert all(any(name == other_name for _, other_name in advice_by_name) for _, name in advice_by_name)
    text = basket_text(fixed, basket, "Пятёрочка", 489.21)
    assert "Разбор корзины" in text and "Пятёрочка" in text
    assert "Что делать в следующий раз" in text and "1. взять пакет-сумку" in text
    assert "Пакет" in text and "пакет-сумка из дома" in text
    assert "взять меньше" not in text and "заменить" not in text
    assert "Необязательные траты" in text and "% чека" in text
    assert "Реально сократить" in text   # оценка модели ниже суммы по вердиктам
    # Смешанный чек не должен остаться категорией «еда», если алкоголь — заметная доля.
    from ai.receipts import _apply_receipt_category_rules
    mixed = _apply_receipt_category_rules({
        "total": 1000, "category": "еда", "leisure": False,
        "items": [{"name": "Пиво 0.5л", "sum": 150}, {"name": "Молоко", "sum": 850}],
    })
    assert mixed["category"] == "досуг" and mixed["leisure"]
    # Регрессия пользовательского бага: «колб.»/«серв.» не являются пакетом.
    sausage = [{"name": "ПАПА МОЖ.Колб. Серв.Кар. в/к", "sum": 119.89}]
    sausage_answer = {"items": {1: {"verdict": "лишнее", "reason": "одноразовая посуда", "note": ""}}}
    sausage_fixed = apply_review_rules(sausage_answer, sausage)
    assert sausage_fixed["items"][1]["verdict"] == "нейтрально", sausage_fixed
    assert "однораз" not in str(sausage_fixed["items"][1]).lower(), sausage_fixed

    # Цена того же товара выросла: другая марка/вес не должны давать ложный сигнал.
    changes = compare_items(
        [{"name": "Сметана Простоквашино 20% 200г", "qty": 1, "price": 120, "sum": 120}],
        [{"name": "Сметана Простоквашино 20% 200г", "qty": 1, "price": 80, "sum": 80,
          "created_at": "2026-09-10 10:00:00"}],
    )
    assert changes and changes[0]["previous"] == 80 and changes[0]["current"] == 120, changes
    assert not compare_items(
        [{"name": "Сметана Домик в деревне 20% 200г", "price": 120, "sum": 120}],
        [{"name": "Сметана Простоквашино 20% 200г", "price": 80, "sum": 80,
          "created_at": "2026-09-10 10:00:00"}],
    )
    print("✅ Разбор корзины: страховки, отсутствие повторов, подробный разбор")

    # 6.1 Добор потерянных позиций по арифметике чека
    from ai.receipts import _gap_fill, _still_off
    items = [{"name": "Пиво Жигулёвское", "qty": 1, "price": 109.99, "sum": 109.99}]
    ocr_items = [{"name": "Пиво ЖИГУЛ.ФИРМ.", "sum": 109.99},
                 {"name": "Напиток TORNADO 0.45л", "sum": 54.99},
                 {"name": "Пакет ПЯТЕРОЧКА", "sum": 6.99}]
    filled = _gap_fill(items, ocr_items, 164.98)      # недостача ровно в TORNADO
    assert len(filled) == 2 and filled[-1]["recovered"], filled
    assert sum(item["sum"] for item in filled) == 164.98, filled
    assert not _still_off({"total": 164.98, "items": filled})
    assert len(_gap_fill(items, ocr_items, 109.99)) == 1     # всё сошлось — не трогаем
    # расхождение в пару процентов от суммы чека — уже повод перечитать фото
    assert _still_off({"total": 1234.56, "items": items + [{"sum": 1201.35}]})
    print("✅ Добор потерянных позиций по арифметике чека")

    # 7. Аналитика и алерты
    month_text = await analytics.build_month_report(spending, 2000)
    assert "Отчёт" in month_text and "2 000" in month_text
    pace = analytics.budget_pace(95_000, 100_000)
    assert any(marker in pace for marker in ("Темп", "запас")), pace
    assert analytics.forecast_end_of_month(0) is None
    from datetime import datetime as _dt
    week_text = await analytics.build_week_report([
        {"tx_type": "expense", "category": "транспорт", "amount": 2000,
         "created_at": _dt.now().isoformat(sep=" ")},
    ])
    assert "за неделю" in week_text and "2 000" in week_text
    # лимит задаём сами: тест не должен зависеть от демо-значений config.py
    alert = alerts.check_limits_alert("транспорт", {"транспорт": 9500}, {"транспорт": 10_000})
    assert "почти исчерпана" in alert, alert
    assert alerts.check_limits_alert("транспорт", {"транспорт": 100}, {"транспорт": 10_000}) == ""
    months = analytics.forecast_debt_payoff(50000, 3000, 60.0)
    assert months and months > 0
    print("✅ Аналитика, прогнозы и алерты")

    # 8. Экспорт CSV — теперь его делает панель управления (panel.py), а не бот
    import pandas as pd
    txs = await database.get_transactions(days=30)
    df = pd.DataFrame([dict(r) for r in txs])
    filename, content = await export.export_csv(df)
    assert filename.startswith("finance_export_") and "Сумма".encode("utf-8") in content
    print("✅ Экспорт CSV (используется панелью управления)")

    # 8.1 Картинки-диаграммы для отчётов
    assert charts.CHARTS_AVAILABLE, "matplotlib не установлен — диаграммы не рисуются"
    card = await charts.month_card({"еда": 12000, "транспорт": 4000}, 150000, 16000, 100000,
                                   40000, limits={"еда": 20000, "транспорт": 5000})
    assert card and card[:8] == b"\x89PNG\r\n\x1a\n", "месячная диаграмма не отрисовалась"
    period = await charts.period_card({"еда": 12000}, 12000, 7,
                                      charts.daily_series(txs, 7))
    assert period and period[:8] == b"\x89PNG\r\n\x1a\n", "диаграмма периода не отрисовалась"
    assert await charts.period_card({}, 0, 7) is None   # нет данных — молчим, а не падаем
    print("✅ Диаграммы отчётов рисуются (PNG)")

    # 8.2 Профиль и приветственная настройка
    assert not await profile.is_onboarded(111)
    await profile.set_name(111, "  Никита  ")
    assert await profile.display_name(111) == "Никита"
    await profile.set_planned_income(111, 150_000)
    assert await profile.planned_income(111) == 150_000
    await profile.mark_onboarded(111)
    assert await profile.is_onboarded(111)
    safe = await profile.safe_to_spend(111, 2000)
    assert "Безопасно тратить" in safe, safe
    limits, total = budget.proposal_for_income(100_000, {"еда": 20_000, "транспорт": 5_000})
    assert total == 70_000 and sum(limits.values()) > 0, (limits, total)
    welcome = await onboarding._step_text(0, 111)
    assert "Привет" in welcome[0] and welcome[1] is True
    proposal_text = (await onboarding._step_text(4, 111))[0]
    assert "Лимит на месяц" in proposal_text and "70%" in proposal_text, proposal_text
    print("✅ Профиль, «безопасно тратить» и шаги настройки")

    # 8.3 Выбор модели строго по тегу: иначе молча работает слабейшая модель.
    # Раньше «qwen3-vl:8b-instruct» подменялась на «qwen3-vl:4b», а для текста —
    # «qwen2.5:7b-instruct» на «qwen2.5:7b» — качество падало без единой ошибки.
    installed = ["qwen3-vl:4b", "qwen3-vl:8b-instruct", "qwen2.5:7b"]
    assert llm.find_model("qwen3-vl:8b-instruct", installed) == "qwen3-vl:8b-instruct"
    assert llm.find_model("qwen2.5:7b-instruct", installed) is None
    assert llm.find_model("qwen3-vl:4b", installed) == "qwen3-vl:4b"
    print("✅ Подбор модели строго по тегу")

    # 9. Планировщик регистрируется
    class FakeBot:
        pass
    sched = scheduler.register_scheduler(FakeBot())
    assert sched.get_job("daily_report") is not None
    if sched.running:
        sched.shutdown(wait=False)
    print("✅ Планировщик ежедневной сводки")

    # 9.1 Сводка долгов и карточка траты
    from handlers import debts as debts_module, expenses as expenses_module
    overview = await debts_module.build_debts_overview()
    assert "Т-Банк" in overview and "Итого" in overview, overview
    card = expenses_module._card_text(
        {"amount": 2000, "category": "транспорт", "subcategory": "бензин",
         "description": "Заправка_*тест", "tx_type": "expense", "debt_target": None},
        {"транспорт": 9500}, {"транспорт": 10_000}, "text")
    assert "2 000" in card and "почти исчерпана" not in card and "*тест" not in card, card
    assert "95%" in card, card
    debt_card = expenses_module._card_text(
        {"amount": 3000, "category": "долги", "description": "Платёж",
         "tx_type": "debt_payment", "debt_target": "tbank"}, {}, {}, "text")
    assert "Т-Банк" in debt_card, debt_card
    print("✅ Сводка долгов и карточки записей")

    # 10. Клавиатуры строятся
    for kb in (main_menu_kb.get_main_menu_kb(), main_menu_kb.get_main_menu_inline_kb(),
               main_menu_kb.get_confirm_kb(),
               main_menu_kb.get_categories_kb(), main_menu_kb.get_debts_kb(),
               main_menu_kb.get_report_kb(), main_menu_kb.get_settings_kb(),
               main_menu_kb.get_onboarding_kb(0, "Приветствие"),
               expense_kb.get_categories_kb(), expense_kb.get_subcategory_kb("еда"),
               expense_kb.get_edit_kb(), expense_kb.get_edit_kb(True), expense_kb.get_amount_kb(),
               expense_kb.get_receipt_kb(), expense_kb.get_receipt_kb(True),
               debt_kb.get_debt_payment_kb(), debt_kb.get_debt_detail_kb("sber"),
               report_kb.get_report_kb(), report_kb.get_period_kb()):
        assert kb is not None

    # Кнопки, ведущие в никуда, недопустимы: у каждой callback_data есть обработчик
    import re as _re
    sources = "".join(open(f, encoding="utf-8").read() for f in (
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "handlers", name)
        for name in ("main_menu.py", "expenses.py", "reports.py", "debts.py", "settings.py",
                     "onboarding.py")
    ))
    for kb in (main_menu_kb.get_main_menu_inline_kb(), main_menu_kb.get_confirm_kb(),
               main_menu_kb.get_debts_kb(), main_menu_kb.get_settings_kb(),
               expense_kb.get_edit_kb(True), expense_kb.get_amount_kb(),
               expense_kb.get_receipt_kb(), expense_kb.get_receipt_kb(True),
               debt_kb.get_debt_detail_kb("sber"), report_kb.get_report_kb(), report_kb.get_period_kb()):
        for row in kb.inline_keyboard:
            for button in row:
                data = button.callback_data
                dynamic_prefix = data.rsplit("_", 1)[0] + "_"
                known = (data in sources
                         or f'{data.split(":")[0]}:' in sources
                         or f'startswith("{dynamic_prefix}")' in sources)
                assert known, f"нет обработчика для {data}"
    assert any(button.text == "🕘 История" for row in main_menu_kb.get_main_menu_kb().keyboard
               for button in row)
    assert any(button.callback_data == "menu_history"
               for row in main_menu_kb.get_main_menu_inline_kb().inline_keyboard
               for button in row)
    print("✅ Все клавиатуры строятся и каждая кнопка имеет обработчик")

    print("\n🎉 Смоук-тест пройден полностью")
    if os.path.exists(_TEST_DB):
        os.remove(_TEST_DB)


if __name__ == "__main__":
    asyncio.run(main())
