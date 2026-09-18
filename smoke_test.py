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

    # 5б. Правка позиций чека: разбор строки правки и пометки списка
    from handlers.expenses import _mark_items_edited, _parse_item_input
    assert _parse_item_input("Сыр Российский 320,50") == ("Сыр Российский", 320.5)
    assert _parse_item_input("320,50") == (None, 320.5)
    assert _parse_item_input("Хлеб Бородинский") == ("Хлеб Бородинский", None)
    assert _parse_item_input("Пакет-майка 1x 2,72") == ("Пакет-майка 1x", 2.72)
    assert _parse_item_input("Кофе 1 250 ₽") == ("Кофе", 1250.0)
    assert _parse_item_input("   ") == (None, None)
    edited = {"items": [{"name": "Сыр", "sum": 320.5}, {"name": "Молоко", "sum": 89.9}],
              "total_estimated": True, "amount": 999.0, "items_mismatch": True, "over_total": True}
    _mark_items_edited(edited)
    assert edited["amount"] == 410.40, edited  # итога на чеке не было — сумма следует за позициями
    assert edited["items_edited"] and not edited["items_mismatch"] and not edited["over_total"], edited
    kept = {"items": [{"name": "Сыр", "sum": 320.5}], "amount": 524.0}
    _mark_items_edited(kept)
    assert kept["amount"] == 524.0, kept  # прочитанный кассой итог правка позиций не трогает
    print("✅ Правка позиций чека: разбор ввода и пометки списка")

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

    # Регрессия чека из ДНС: техника — редкая осознанная покупка, а не упаковка и не «лишнее».
    hardware = [{"name": "БП Deepcool PF750 750W", "sum": 5099.0},
                {"name": "Корпус ZALMAN N4 Rev. 1 Midtower Black", "sum": 4299.0},
                {"name": "Пакет-майка", "sum": 7.0}]
    hardware_answer = {"items": {
        1: {"verdict": "лишнее", "reason": "одноразовая упаковка", "note": "пакет-сумка из дома"},
        2: {"verdict": "лишнее", "reason": "упаковка", "note": ""},
        3: {"verdict": "нейтрально", "reason": "", "note": ""}}}
    hardware_fixed = apply_review_rules(hardware_answer, hardware)["items"]
    assert hardware_fixed[1]["verdict"] == "нейтрально", hardware_fixed
    assert hardware_fixed[2]["verdict"] == "нейтрально", hardware_fixed
    assert "упаковк" not in f"{hardware_fixed[1]['reason']}{hardware_fixed[1]['note']}".lower(), \
        hardware_fixed
    # Настоящая упаковка остаётся упаковкой — правило не стало глухим ко всем.
    assert hardware_fixed[3]["verdict"] == "лишнее", hardware_fixed
    assert "упаковка" in hardware_fixed[3]["reason"].lower(), hardware_fixed

    # Происхождение вердиктов: правило — это проверка по названию (одинаково для того же
    # товара в любом чеке), вердикт модели — её оценка. Человеку важно знать, с чем спорить.
    from ai.receipts import sources_text

    # Имена выбраны так, чтобы одно попадало в правила, а другое — нет: иначе проверка
    # говорила бы не про происхождение вердикта, а про содержимое списков слов.
    origin_items = [{"name": "Пакет ПЯТЕРОЧКА 65х40см", "sum": 4.43},
                    {"name": "Абрикосы свежие 1кг", "sum": 120.0},
                    {"name": "Груши Конференция 1кг", "sum": 89.9}]
    origin_answer = {"items": {
        1: {"verdict": "нейтрально", "reason": "пакет нужен", "note": ""},
        2: {"verdict": "вредно", "reason": "сладкие, много сахара", "note": "меньше плодов"},
    }}   # по третьей позиции модель промолчала — ей досталось наше «нейтрально»
    origin_fixed = apply_review_rules(origin_answer, origin_items)["items"]
    assert origin_fixed[1]["source"] == "rule", origin_fixed[1]
    assert origin_fixed[2]["source"] == "model", origin_fixed[2]
    assert origin_fixed[3]["source"] == "default", origin_fixed[3]
    origin_line = sources_text(origin_fixed, origin_items)
    assert "Откуда вердикты" in origin_line, origin_line
    assert "правила — 1" in origin_line and "оценка модели — 1" in origin_line, origin_line
    assert "без вердикта, нейтрально по умолчанию — 1" in origin_line, origin_line
    assert "спорить есть с чем именно у вторых" in origin_line, origin_line
    # Молчание модели — это тоже происхождение, а не оценка: оно отдельно и названо.
    apricots = [{"name": "Абрикосы свежие 1кг", "sum": 120.0}]
    silent = apply_review_rules({"items": {
        1: {"verdict": "вредно", "reason": "сладкие, много сахара", "note": "меньше плодов"}}},
        apricots)["items"]
    silent_line = sources_text(silent, apricots)
    assert "а не проверка по названию" in silent_line, silent_line
    only_rules = apply_review_rules(
        {"items": {1: {"verdict": "нейтрально", "reason": "", "note": ""}}},
        [{"name": "Пакет-майка", "sum": 7.0}])["items"]
    rules_line = sources_text(only_rules, [{"name": "Пакет-майка", "sum": 7.0}])
    assert "Единственный необязательный вердикт — от правила" in rules_line, rules_line
    assert sources_text({}, []) == ""

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

    # 6в. Личная история цен: обычная цена — медиана, покупка дешевле тоже видна,
    # а одна акция в прошлом не превращает нормальную цену в «подорожание».
    from services.purchase_history import (card_text, catalog_text, product_groups,
                                           search_products, usual_price,
                                           history_text as price_history_text)
    milk = "Молоко Простоквашино 3,2% 930мл"

    def _past(price, day, store="Пятёрочка"):
        return {"name": milk, "qty": 1, "price": price, "sum": price,
                "created_at": f"2026-0{day}", "description": store}

    # Одна акционная покупка (60 ₽) не делает обычную цену дешёвой: медиана 95 ₽.
    history = [_past(60, "9-01"), _past(95, "9-05"), _past(95, "9-08")]
    assert usual_price([{"price": 60}, {"price": 95}, {"price": 95}]) == 95
    up = compare_items([{"name": milk, "qty": 1, "price": 130, "sum": 130}], history)
    assert len(up) == 1 and up[0]["kind"] == "up" and up[0]["previous"] == 95, up
    assert up[0]["purchases"] == 3 and "обычная" not in up[0]["message"], up
    assert "Дороже" in price_history_text(up), price_history_text(up)
    # Цена в пределах обычной — молчим, а не выдаём шум OCR за изменение цены.
    assert not compare_items([{"name": milk, "qty": 1, "price": 100, "sum": 100}], history)
    # Дешевле обычного — это тоже полезный сигнал, с суммой экономии.
    down = compare_items([{"name": milk, "qty": 1, "price": 70, "sum": 70}], history)
    assert len(down) == 1 and down[0]["kind"] == "down", down
    assert "экономия" in price_history_text(down), price_history_text(down)
    # Обычная цена без истории: сравнивать не с чем.
    assert compare_items([{"name": milk, "price": 130, "sum": 130}], []) == []
    assert usual_price([]) is None

    # Каталог: товар с тремя покупками попадает, с двумя — нет; лучшая цена и место видны.
    cheese = "Сыр Российский 200г"
    rows = [_past(95, "9-01", "Пятёрочка"), _past(89, "9-05", "К&Б"),
            _past(105, "9-12", "Пятёрочка")] + [
        {"name": cheese, "qty": 1, "price": price, "sum": price,
         "created_at": f"2026-09-{day}", "description": store}
        for price, day, store in ((320, "02", "Пятёрочка"), (330, "06", "К&Б"),
                                  (345, "11", "Пятёрочка"))]
    groups = product_groups(rows)
    assert {group["name"] for group in groups} == {milk, cheese}, groups
    mine = next(group for group in groups if group["name"] == milk)
    assert mine["count"] == 3 and mine["cheapest"] == 89, mine
    assert mine["cheapest_store"] == "К&Б", mine
    assert mine["trend"] > 0.12, mine          # 105 против медианы прошлого (89 и 95)
    assert product_groups([_past(95, "9-01"), _past(95, "9-02")]) == []
    text = catalog_text(rows)
    assert "Мои цены" in text and "К&Б" in text and "дешевле всего" in text, text
    assert "/price" in text, text          # на карточку товара можно перейти прямо из каталога

    # Поиск товара для карточки: слово запроса должно войти в название целиком.
    found = search_products(rows, "молоко")
    assert len(found) == 1 and found[0]["name"] == milk, found
    assert found[0]["count"] == 3 and found[0]["usual"] == 95, found[0]
    assert search_products(rows, "молоко простоквашино")[0]["name"] == milk
    assert search_products(rows, "кола") == []
    # Товар с одной покупкой тоже находится: это история, а не каталог по трём покупкам.
    single = [{"name": "Кола 0,5л", "qty": 1, "price": 70, "sum": 70,
               "created_at": "2026-09-09", "description": "Пятёрочка"}]
    assert len(search_products(rows + single, "кола")) == 1
    assert product_groups(rows + single) and len(product_groups(rows + single)) == 2  # каталог — нет
    # Два разных товара с одним словом — не угадываем: показываем оба варианта.
    second = [{"name": "Молоко Яркино 1л", "qty": 1, "price": 80, "sum": 80,
               "created_at": "2026-09-07", "description": "Магнит"}]
    assert len(search_products(rows + single + second, "молоко")) == 2
    card = card_text(found[0])
    assert "Обычная цена" in card and "История покупок" in card and "К&Б" in card, card
    assert "Дешевле всего" in card and "+14%" in card, card    # 105 против прежней 92
    assert "Пора брать" not in card, card          # без срока карточка молчит о закупке
    from datetime import datetime as _card_now, timedelta as _card_delta
    with_due = card_text(found[0], due={"due_date": _card_now(2026, 9, 21), "interval": 10})
    assert "Пора брать" in with_due and "21.09" in with_due and "раз в 10 дн." in with_due, with_due
    # Длинная история не раздувает карточку: показываются последние покупки и число пропущенных.
    long_history = [{"name": milk, "qty": 1, "price": 90 + index, "sum": 90 + index,
                     "created_at": (_card_now(2026, 6, 1)
                                    + _card_delta(days=index * 9)).isoformat(sep=" "),
                     "description": "Пятёрочка"} for index in range(9)]
    long_card = card_text(product_groups(long_history)[0])
    assert "показаны последние 6 из 9 покупок" in long_card, long_card
    assert "Пока нечего сравнивать" in catalog_text([])
    assert "Пока нечего сравнивать" in catalog_text([_past(95, "9-01")])
    print("✅ Личная история цен: обычная цена, экономия, каталог товаров")

    # 6г. Список покупок по ритму чеков: одна акция не сдвигает срок, редкие товары
    # не превращаются в вечный список, а заброшенный товар из подсказок уходит.
    from services.shopping import due_items, shopping_text
    from datetime import datetime as _now_cls, timedelta as _days
    now = _now_cls.now()

    def _bought(price, days_ago, store="Пятёрочка", name=milk):
        when = (now - _days(days=days_ago)).isoformat(sep=" ")
        return {"name": name, "qty": 1, "price": price, "sum": price,
                "created_at": when, "description": store}

    # Молоко берут каждые 10 дней, последний раз 12 дней назад — срок уже пришёл.
    weekly = [_bought(95, 42, "К&Б"), _bought(95, 32), _bought(99, 22), _bought(95, 12)]
    due = due_items(weekly, today=now)
    assert len(due) == 1 and due[0]["until"] <= 0, due
    assert due[0]["interval"] == 10 and due[0]["usual"] == 95, due[0]
    assert due[0]["cheapest_store"] == "К&Б", due[0]
    assert "пора брать" in shopping_text(due), shopping_text(due)
    assert "примерно" in shopping_text(due), shopping_text(due)

    # Тот же ритм, но куплено вчера — в списке покупок делать нечего.
    assert due_items([_bought(95, 31), _bought(95, 21), _bought(95, 1)], today=now) == []
    # Товар заброшен: срок прошёл давно, значит его больше не берут — не подсказываем.
    stale = [_bought(95, 200), _bought(95, 190), _bought(95, 180)]
    assert due_items(stale, today=now) == []
    # Двух покупок для ритма мало, а две строки в один день — не интервал.
    assert due_items([_bought(95, 12), _bought(95, 2)], today=now) == []
    assert due_items([_bought(95, 42), _bought(95, 42), _bought(95, 12)], today=now) == []
    # Срок ближе чем горизонт — товар уже виден в списке, а с горизонтом 0 — ещё нет.
    soon = [_bought(95, 25), _bought(95, 15), _bought(95, 5)]
    assert len(due_items(soon, today=now)) == 1, due_items(soon, today=now)
    assert due_items(soon, horizon_days=0, today=now) == []
    # Пустой список честно объясняет, чего не хватает, а не просто молчит.
    empty = shopping_text([])
    assert "не пора" in empty and "трёх покупок" in empty, empty

    # Управление списком: «уже купил» скрывает товар на один обычный срок,
    # а «не напоминать» — насовсем, но с возможностью вернуть.
    from services.shopping import bought_marks, hide_bought, mark_bought
    marked_item = due[0]
    assert await bought_marks(777) == {}
    await mark_bought(777, marked_item["key"], today=now)
    marks = await bought_marks(777)
    assert marks[marked_item["key"]] == now.strftime("%Y-%m-%d"), marks
    assert await bought_marks(778) == {}                 # чужая отметка не видна
    assert hide_bought(due, marks, today=now) == ([], due), hide_bought(due, marks, today=now)
    # Отметка действует только один обычный срок: через 11 дней товар снова в списке.
    old = {marked_item["key"]: (now - _days(days=11)).strftime("%Y-%m-%d")}
    assert hide_bought(due, old, today=now)[0] == due, hide_bought(due, old, today=now)
    # А отметка старше последней покупки не действует: после нового чека ритм идёт заново.
    stale = {marked_item["key"]: (now - _days(days=30)).strftime("%Y-%m-%d")}
    assert hide_bought(due, stale, today=now)[0] == due
    assert "Уже отмечено" in shopping_text(due, marked=[marked_item])
    assert "Отключены из напоминаний" in shopping_text([], muted=[marked_item])

    # Кнопки: отметить купленным, отключить и вернуть — и возврат к отчётам на пустом экране.
    from keyboards.report_kb import get_shopping_kb
    keys = [button.callback_data
            for row in get_shopping_kb(due, due).inline_keyboard for button in row]
    assert any(data.startswith("shopping_bought:") for data in keys), keys
    assert any(data.startswith("shopping_mute:") for data in keys), keys
    assert any(data.startswith("shopping_unmute:") for data in keys), keys
    assert get_shopping_kb([], []) is not None

    # 6д. Недельный дайджест: сравнение недель, подорожания, закупка и списания одним текстом.
    from services.digest import build_weekly_digest, compare_weeks, weekly_digest_text
    assert "меньше" in compare_weeks(800, 1000)
    assert "больше" in compare_weeks(1200, 1000)
    assert "примерно столько же" in compare_weeks(1010, 1000)   # 1% — это шум, а не рост
    assert "не с чем" in compare_weeks(500, 0)
    assert compare_weeks(0, 0) == ""
    week_start, week_end = now - _days(days=6), now
    digest = build_weekly_digest(
        start=week_start, end=week_end, spent=6250, previous_spent=7100,
        categories={"еда": 3200, "транспорт": 900, "досуг": 800},
        rising=[{"name": milk, "usual": 95, "baseline": 95, "last": 119, "trend": 0.25}],
        due=[{"name": "Хлеб Бородинский 400г", "usual": 44}],
        subscriptions=[{"name": "Netflix", "amount": 999, "days_left": 1}],
        subscription_month=4900)
    assert "6 250 ₽" in digest and "меньше, чем на прошлой неделе" in digest, digest
    assert "еда — 3 200 ₽ (51%)" in digest, digest
    assert "Заметно подорожало" in digest and "+25%" in digest, digest
    assert "Пора купить (1)" in digest and "44 ₽" in digest, digest
    assert "Netflix" in digest and "завтра" in digest, digest
    assert "4 900 ₽" in digest, digest
    # Эффект советов идёт в дайджест одной строкой и с оговоркой про причинность.
    with_effects = build_weekly_digest(
        start=week_start, end=week_end, spent=6250, previous_spent=7100,
        effects={"effects": [{"name": milk, "change": 0.6, "after": 1, "before": 3,
                               "interval_before": 10.0, "interval_after": 40.0,
                               "days_after": 40}],
                 "pending": [{"name": "Сметана 20% 300г", "days_after": 5, "days_left": 16}]})
    assert "После советов" in with_effects and "реже" in with_effects, with_effects
    assert "не доказательство" in with_effects, with_effects
    # Пустая неделя — это текст, а не нули: разделов без данных быть не должно.
    quiet = build_weekly_digest(start=week_start, end=week_end, spent=0, previous_spent=500)
    assert "трат не записано" in quiet, quiet
    assert not any(word in quiet for word in
                   ("Подорожало", "Пора купить", "Спишется", "После советов")), quiet
    # Без замеров строки нет: нечего сообщать — молчим, а не пишем «изменений нет».
    assert "После советов" not in build_weekly_digest(
        start=week_start, end=week_end, spent=1000, previous_spent=900, effects=None)

    # Дайджест собирается из базы: прошлая неделя и текущая разделяются по дате записи.
    import aiosqlite
    from config import DB_PATH as digest_db
    async with aiosqlite.connect(digest_db) as db:
        for amount, moment, user in ((1000, now - _days(days=9), 555),
                                     (600, now - _days(days=2), 555),
                                     (7777, now - _days(days=2), 888)):
            await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (user, amount, "еда", "Пятёрочка", "expense", "text",
                 moment.isoformat(sep=" ")))
        await db.commit()
    live = await weekly_digest_text(555)
    assert "600 ₽" in live and "на 40% меньше" in live, live
    assert "7 777" not in live, live                    # чужие траты в дайджест не попадают
    print("✅ Список покупок: ритм чеков, сроки, отметка «уже купил»")

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

    # 6д. Варианты предобработки читаются параллельно и в исходном порядке.
    # Если порядок поедет, один и тот же чек начнёт разбираться по-разному.
    from ai.ocr import OCR_WORKERS, _read_variants
    assert OCR_WORKERS >= 2, OCR_WORKERS
    if ocr.OCR_AVAILABLE:
        from PIL import Image as _Image, ImageDraw as _ImageDraw
        from utils.fonts import mono_font
        font = mono_font(28)
        page = _Image.new("L", (460, 140), color=255)
        _ImageDraw.Draw(page).text((20, 40), "ITOG 1234.56", fill=0, font=font)
        sequential = [ocr._ocr_words(page), ocr._ocr_words(page)]
        parallel = _read_variants([page, page])
        assert len(parallel) == 2 and parallel == sequential, (parallel, sequential)
        assert parallel[0], "tesseract не прочитал ни слова — проверка бессмысленна"
    print("✅ Варианты предобработки читаются параллельно и в том же порядке")

    # 6.2 Честность разбора: без выдуманных позиций и с видимым происхождением цен
    from ai.ocr import _close_receipt, _fill_blank_item, _score_table
    from ai.receipts import item_mark, item_origin, items_list_text

    # Остаток чека не достаётся строке, не похожей на товар: раньше на мусорной
    # строке («MH —», «шт .») вырастала выдуманная позиция на всю недостачу.
    junk = [{"name": "MH —", "sum": 0, "price": 0, "qty": 1},
            {"name": "Сыр Российский 200г", "sum": 450, "price": 450, "qty": 1}]
    assert sum(item["sum"] for item in _fill_blank_item(junk, 500)) == 450, junk

    # Прочитанная крупным планом ячейка («sum_hint») важнее остатка чека: остаток —
    # догадка, которой подходит любая непрочитанная строка.
    hinted = [{"name": "Пакет-майка", "sum": 0, "price": 0, "qty": 1, "sum_hint": 2.72},
              {"name": "Вино безалк. Блютул", "sum": 521.28, "price": 521.28, "qty": 1}]
    filled_hint = _fill_blank_item([dict(item) for item in hinted], 524.0)
    assert filled_hint[0]["sum"] == 2.72 and filled_hint[0].get("cell_read"), filled_hint
    assert not filled_hint[0].get("recovered"), filled_hint
    # Без подсказки работает прежний путь — остаток чека
    without_hint = [dict(item) for item in hinted]
    without_hint[0].pop("sum_hint")
    filled_gap = _fill_blank_item(without_hint, 524.0)
    assert filled_gap[0]["sum"] == 2.72 and filled_gap[0].get("recovered"), filled_gap
    # Две непрочитанные строки: подсказки закрывают обе, остаток никому не достаётся
    pair = _fill_blank_item([{"name": "Сыр", "sum": 0, "sum_hint": 100.0},
                             {"name": "Молоко", "sum": 0, "sum_hint": 50.0}], 1000.0)
    assert [item["sum"] for item in pair] == [100.0, 50.0], pair
    assert not any(item.get("recovered") for item in pair), pair
    # Ячейку читаем только при известной колонке и картинке
    from ai.ocr import _read_sum_cell
    assert _read_sum_cell(None, {"top": 0, "bottom": 10}, None) is None
    assert _read_sum_cell(None, {"top": 0, "bottom": 10}, {"sum": (10, 100)}) is None
    # А строке с товарным названием и потерянной правой ячейкой цена достаётся
    package = [{"name": "#Пакет-майка", "sum": 0, "price": 0, "qty": 1},
               {"name": "Пиво Балтика", "sum": 79.78, "price": 39.89, "qty": 2}]
    filled_blank = _fill_blank_item(package, 82.5)
    assert filled_blank[0]["sum"] == 2.72 and filled_blank[0]["recovered"], filled_blank

    # Служебная строка чека не закрывает недостачу: иначе в чеке появится «покупка»,
    # которой не было.
    service = [{"name": "MH —", "sum": 100}, {"name": "Хлеб Бородинский", "sum": 45}]
    assert len(_gap_fill([{"name": "Молоко", "sum": 60}], service, 160)) == 1

    # Разбор, который дороже итога чека, проигрывает тому, что просто не дотянул:
    # позиции — подмножество чека, дороже итога они быть не могут.
    under = {"total": 1000, "items": [{"name": "Молоко", "sum": 900, "verified": True}]}
    over = {"total": 1000, "items": [{"name": "Молоко", "sum": 900, "verified": True},
                                      {"name": "Хлеб", "sum": 300, "verified": True}]}
    assert _score_table(under) > _score_table(over), (_score_table(under), _score_table(over))

    # Цена, поправленная по остатку чека, помечается как поправленная, а не как прочитанная
    fixed = _close_receipt({"total": 100.02,
                            "items": [{"name": "Сыр", "sum": 100.0, "price": 100.0, "qty": 1}]}, [])
    assert fixed["items"][0]["corrected"] and fixed["items"][0]["sum"] == 100.02, fixed

    # Происхождение цены видно в карточке: ✅ — два чтения, ➕ — добрана, ✏️ — поправлена,
    # ⚠️ — прочитана, но ничего её не подтвердило
    assert item_origin({"recovered": True}) == "recovered"
    assert item_origin({"corrected": True}) == "corrected"
    assert item_origin({"corroborated": True}) == "agreed"
    assert item_origin({"verified": True}) == "verified"
    assert item_origin({}) == "read"
    assert item_mark({"recovered": True}) == " ➕"
    assert item_mark({"corroborated": True}) == " ✅"
    assert item_mark({"verified": False}) == " ⚠️"     # прочитано, но ничем не подтверждено
    assert item_mark({}) == ""                        # без пометок позиция считается прочитанной
    estimated = items_list_text([{"name": "Пакет", "sum": 2.72, "recovered": True}], "Магазин",
                                {"total_estimated": True})
    assert "➕" in estimated and "сумма по позициям" in estimated, estimated
    oversized = items_list_text([{"name": "Пакет", "sum": 2.72}], "", {"over_total": True})
    assert "больше итога" in oversized, oversized
    print("✅ Честность разбора: без выдуманных позиций, пометки происхождения")

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
    # 7б. Личные и семейные лимиты: правка бюджета одним человеком не меняет картину
    # у второго — траты считаются по каждому, и лимит должен быть про него же.
    from config import MONTHLY_LIMITS, TOTAL_MONTHLY_LIMIT
    await budget.set_limit("еда", 12_345, 111)
    await budget.set_total_limit(90_000, 111)
    assert (await budget.get_limits(111))["еда"] == 12_345
    assert await budget.get_total_limit(111) == 90_000
    assert (await budget.get_limits(222))["еда"] == MONTHLY_LIMITS["еда"]
    assert await budget.get_total_limit(222) == TOTAL_MONTHLY_LIMIT
    assert (await budget.get_limits())["еда"] == MONTHLY_LIMITS["еда"]  # семейное не тронуто
    assert await budget.own_limits(111) and not await budget.own_limits(222)
    assert not await budget.own_limits(None)   # без пользователя личных лимитов не бывает
    # Личный лимит занимает только свою категорию: остальное берётся из семейных
    assert (await budget.get_limits(111))["транспорт"] == MONTHLY_LIMITS["транспорт"]
    await budget.reset_limits(111)
    assert not await budget.own_limits(111)
    assert (await budget.get_limits(111))["еда"] == MONTHLY_LIMITS["еда"]
    assert await budget.get_total_limit(111) == TOTAL_MONTHLY_LIMIT
    # 7в. Регулярные платежи: серия находится только у настоящих повторов
    from services import recurring
    moment = _dt(2026, 3, 5, 10, 0)

    def expense(amount, description, category, when):
        return {"tx_type": "expense", "amount": amount, "description": description,
                "category": category, "created_at": when.isoformat(sep=" ")}

    # Май–август: последнее списание 5 августа, следующее — начало сентября
    history = [expense(999, "Netflix", "досуг", moment.replace(month=5 + index))
               for index in range(4)]
    history += [expense(550, "Спортзал", "здоровье", moment.replace(month=6, day=1 + index * 7))
                for index in range(3)]
    # Одни и те же магазины с разными суммами — это покупки, а не подписка
    history += [expense(3450, "Пятёрочка", "еда", moment.replace(month=7, day=1)),
                expense(3800, "Пятёрочка", "еда", moment.replace(month=7, day=4)),
                expense(900, "Пятёрочка", "еда", moment.replace(month=7, day=8))]
    found = recurring.find_recurring(history, today=_dt(2026, 9, 1))
    names = [item["name"] for item in found]
    assert names == ["Netflix", "Спортзал"], found
    netflix = found[0]
    assert netflix["period_label"] == "месяц" and netflix["occurrences"] == 4, netflix
    assert netflix["amount"] == 999 and netflix["days_left"] == 4, netflix
    assert found[1]["period_label"] == "неделю", found[1]
    # Просроченная серия не лезет вперёд: по ней нечего предупреждать
    assert found[-1]["days_left"] < 0, found
    assert [item["name"] for item in recurring.due_soon(found, within=3)] == []
    assert [item["name"] for item in recurring.due_soon(found, within=10)] == ["Netflix"]
    assert recurring.monthly_total(found) == round(999 + 550 * 30 / 7, 2)
    assert "Netflix" in recurring.recurring_text(found)
    assert "не нашёл" in recurring.recurring_text([])

    # Слишком мало повторов, скачущие суммы, записи в один день и доходы — не серии
    assert recurring.find_recurring(history[:2], today=_dt(2026, 9, 1)) == []
    assert recurring.find_recurring(
        [expense(amount, "Аренда", "жилье", moment.replace(month=3 + index))
         for index, amount in enumerate((30_000, 42_000, 12_000, 30_000))],
        today=_dt(2026, 9, 1)) == []
    assert recurring.find_recurring(
        [expense(500, "Кофе", "досуг", moment.replace(month=3 + index, day=1))
         for index in range(3)], today=_dt(2026, 9, 1))[0]["period_label"] == "месяц"
    assert recurring.find_recurring(
        [{"tx_type": "income", "amount": 999, "description": "Netflix",
          "category": "досуг", "created_at": moment.replace(month=3 + index).isoformat(sep=" ")}
         for index in range(4)], today=_dt(2026, 9, 1)) == []
    assert recurring.find_recurring(history + [
        expense(999, "Netflix", "досуг", moment.replace(month=5, day=5))], today=_dt(2026, 9, 1))[0]["name"] == "Netflix"

    # 7д. Отключение ложной подписки: серия остаётся в истории, но не напоминает о себе
    from services import mutelist
    section = mutelist.RECURRING
    assert await mutelist.muted_keys(999, section) == set()
    key = netflix["key"]
    assert mutelist.digest(key) and len(mutelist.digest(key)) == 12
    assert mutelist.digest(key) == mutelist.digest(key)      # кнопка та же при каждом показе
    assert mutelist.digest(key) != mutelist.digest("другая серия")
    await mutelist.mute(999, section, key)
    assert await mutelist.muted_keys(999, section) == {key}
    visible, muted = mutelist.split(found, await mutelist.muted_keys(999, section))
    assert [item["name"] for item in visible] == ["Спортзал"] and muted == [found[0]], (visible, muted)
    assert recurring.upcoming_text(visible) == ""            # отключённое не напоминает
    assert "Спортзал" in recurring.recurring_text(visible, muted)
    assert "Netflix" in recurring.recurring_text([], muted)  # пустой экран всё равно честен
    # Разделы не пересекаются: отключённый товар не скрывает подписку и наоборот
    await mutelist.mute(999, mutelist.SHOPPING, "товарный ключ")
    assert await mutelist.muted_keys(999, section) == {key}
    await mutelist.mute(999, section, key)
    assert await mutelist.muted_keys(999, mutelist.SHOPPING) == {"товарный ключ"}
    await mutelist.unmute(999, mutelist.SHOPPING, "товарный ключ")
    await mutelist.unmute(999, section, key)
    assert await mutelist.muted_keys(999, section) == set()
    # Чужие отключения не влияют на пользователя
    await mutelist.mute(999, section, key)
    assert await mutelist.muted_keys(888, section) == set()
    await mutelist.unmute(999, section, key)

    # Панель кнопок строится и на пустом экране, и с найденными сериями
    from keyboards.report_kb import get_recurring_kb
    empty_kb = get_recurring_kb([], [])
    assert any(button.text == "📊 К отчётам" for row in empty_kb.inline_keyboard for button in row)
    mute_kb = get_recurring_kb(found, [found[0]])
    datas = [button.callback_data for row in mute_kb.inline_keyboard for button in row]
    assert any(data.startswith("recurring_mute:") for data in datas)
    assert any(data.startswith("recurring_unmute:") for data in datas)
    print("✅ Аналитика, прогнозы и алерты")

    # 7г. Панель показывает семейные лимиты и знает, у кого из пользователей свои
    from database import panel_data
    await budget.set_limit("еда", 7_000, 111)
    assert panel_data.personal_limit_users() == [111], panel_data.personal_limit_users()
    assert panel_data.limits()["еда"] == MONTHLY_LIMITS["еда"]
    panel_data.set_limit("еда", 8_000)          # правка в панели меняет семейный лимит
    assert (await budget.get_limits(222))["еда"] == 8_000
    assert (await budget.get_limits(111))["еда"] == 7_000      # личный остался личным
    await budget.reset_limits(111)
    panel_data.set_limit("еда", MONTHLY_LIMITS["еда"])

    # 7г2. Панель показывает те же цены и закупку, что и бот: считает их тот же сервис,
    # а не отдельный запрос в базу (иначе два интерфейса разошлись бы).
    panel_user = 606
    async with aiosqlite.connect(digest_db) as db:
        for amount, moment in ((95, now - _days(days=28)), (89, now - _days(days=18)),
                               (119, now - _days(days=8))):
            cursor = await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (panel_user, amount, "еда", "Пятёрочка", "expense", "receipt",
                 moment.isoformat(sep=" ")))
            await db.execute(
                "INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                "VALUES (?, ?, 1, ?, ?)",
                (cursor.lastrowid, "Молоко Простоквашино 3,2% 930мл", amount, amount))
        await db.commit()
    catalog = panel_data.product_catalog(panel_user)
    assert len(catalog) == 1 and catalog[0]["count"] == 3, catalog
    # «Обычная цена» — медиана всех покупок (95), «прежняя» — медиана до последней (92):
    # проценты роста считаются от прежней, но покупателю обещана обычная.
    assert catalog[0]["usual"] == 95 and catalog[0]["baseline"] == 92, catalog[0]
    assert catalog[0]["last"] == 119 and catalog[0]["trend"] > 0.12, catalog[0]
    assert panel_data.product_catalog(panel_user + 1) == []      # чужие чеки не подмешиваются
    assert panel_data.shopping_list(panel_user)[0]["name"].startswith("Молоко"), \
        panel_data.shopping_list(panel_user)
    # Список закупки и каталог не могут обещать разные цены одного товара.
    assert panel_data.shopping_list(panel_user)[0]["usual"] == catalog[0]["usual"]
    history = panel_data.product_receipts(panel_user, "МОЛОКО ПРОСТОКВАШИНО 3,2% 930МЛ")
    assert len(history) == 3, history                            # регистр не мешает
    assert history[0]["price"] == 119 and history[0]["store"] == "Пятёрочка", history[0]
    assert history[0]["date"] > history[-1]["date"], history      # свежие покупки сверху
    assert panel_data.product_receipts(panel_user, "Сыр Российский") == []

    # Недельный лимит на продукты: панель и бот пишут и читают один и тот же ключ.
    from services.forecast import (forecast_note, forecast_text, grocery_forecast, limit_status,
                                   limit_text, set_weekly_food_limit, weekly_food_limit,
                                   weekly_spend)
    # Покупки выше были недельной давности и старше — колонка «За 7 дней» их не видит,
    # поэтому свежая трата на еду добавляется отдельно.
    async with aiosqlite.connect(digest_db) as db:
        await db.execute(
            "INSERT INTO transactions "
            "(user_id, amount, category, description, tx_type, source, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (panel_user, 4000, "еда", "Пятёрочка", "expense", "receipt",
             (now - _days(days=2)).isoformat(sep=" ")))
        await db.commit()
    await set_weekly_food_limit(panel_user, 5000)
    overview = panel_data.food_week_overview()
    mine = next((row for row in overview if row["user_id"] == panel_user), None)
    assert mine and mine["limit"] == 5000 and mine["current"] == 4000, overview
    assert mine["left"] == 1000 and not mine["over"] and not mine["near"], mine
    # Пороги «почти» и «превышен» панель не считает сама: она отдаёт словарь сервиса
    # (`limit_status`), поэтому пометки строки в панели и в боте не могут разойтись.
    assert mine["ratio"] == limit_status(4000, 5000)["ratio"], mine
    panel_data.set_food_week_limit(panel_user, 3000)      # правка из панели видна боту
    assert await weekly_food_limit(panel_user) == 3000
    assert [row for row in panel_data.food_week_overview()
            if row["user_id"] == panel_user][0]["over"] is True
    # Тот, у кого ни лимита, ни трат на еду за неделю, в список не попадает.
    assert panel_data.food_week_overview() == [row for row in panel_data.food_week_overview()
                                               if row["limit"] or row["current"]], "пустых строк нет"
    assert not [row for row in panel_data.food_week_overview() if row["user_id"] == panel_user + 1]
    panel_data.set_food_week_limit(panel_user, 0)
    assert await weekly_food_limit(panel_user) == 0
    assert not [row for row in panel_data.food_week_overview()
                if row["user_id"] == panel_user and row["limit"]], "лимит снят"

    # 7г3. Аналитика бота видна и в панели — теми же сервисами, а не вторым расчётом.
    from services.inflation import personal_inflation
    from services.recurring import find_recurring
    async with aiosqlite.connect(digest_db) as db:
        # Товары для личной инфляции: по две покупки до окна и по одной внутри него.
        for name, old_price, new_price in (("Хлеб Бородинский 400г", 50, 60),
                                           ("Сыр Российский 200г", 300, 330),
                                           ("Чай Greenfield 100г", 200, 220)):
            for amount, moment in ((old_price, now - _days(days=150)),
                                   (old_price, now - _days(days=120)),
                                   (new_price, now - _days(days=12))):
                cursor = await db.execute(
                    "INSERT INTO transactions "
                    "(user_id, amount, category, description, tx_type, source, created_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (panel_user, amount, "еда", "Пятёрочка", "expense", "receipt",
                     moment.isoformat(sep=" ")))
                await db.execute(
                    "INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                    "VALUES (?, ?, 1, ?, ?)",
                    (cursor.lastrowid, name, amount, amount))
        for days_ago in (70, 40, 10):      # подписка: три списания через месяц
            await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (panel_user, 999, "досуг", "Netflix", "expense", "text",
                 (now - _days(days=days_ago)).isoformat(sep=" ")))
        await db.commit()

    analysis = panel_data.analytics(panel_user, today=now)
    # Корзина: 100×1,2 + 600×1,1 + 400×1,1 = 1220 против 1100 прежних — это +10,9%.
    assert analysis["inflation"] and analysis["inflation"]["index"] == 0.1091, analysis["inflation"]
    assert analysis["inflation"]["count"] == 3, analysis["inflation"]
    # Панель берёт историю тем же окном, что бот: иначе инфляция и темп продуктов разошлись бы.
    rows_200 = [dict(row) for row in panel_data.transactions(user_id=panel_user, days=200)]
    assert analysis["grocery"] == grocery_forecast(rows_200, today=now)
    assert analysis["inflation"] == personal_inflation(
        panel_data.receipt_price_history(panel_user), today=now)
    assert [item["name"] for item in analysis["recurring"]] == ["Netflix"], analysis["recurring"]
    assert analysis["recurring"] == find_recurring(rows_200, today=now), analysis["recurring"]
    assert analysis["recurring_month"] == 999 and analysis["muted"] == [], analysis
    # Отключённая в боте серия помечается в панели, но из суммы месяца не исчезает:
    # отключена подсказка, а не трата.
    series = analysis["recurring"][0]
    await mutelist.mute(panel_user, mutelist.RECURRING, series["key"])
    muted_analysis = panel_data.analytics(panel_user, today=now)
    assert muted_analysis["recurring"] == [], muted_analysis["recurring"]
    assert [item["name"] for item in muted_analysis["muted"]] == ["Netflix"], muted_analysis
    assert muted_analysis["recurring_month"] == 999, muted_analysis
    await mutelist.unmute(panel_user, mutelist.RECURRING, series["key"])

    # Подпись строки — из того же имени, что пишет бот при приветственной настройке:
    # в config имена могут быть пустыми, и тогда таблицы бюджета остаются без подписи.
    assert panel_data.user_label(panel_user) == str(panel_user), "имени нет — виден id"
    await profile.set_name(panel_user, "Проверка")
    assert panel_data.user_label(panel_user) == "Проверка", "имя из бота важнее config"
    print("✅ Панель: каталог цен и закупка на тех же данных, что в боте")

    # 7д. Вердикты разбора корзины сохраняются и превращаются в «необязательные покупки».
    from ai.receipts import verdict_rows
    from services.advice import waste_summary, waste_text

    basket_items = [{"name": "Молоко Простоквашино 3,2% 930мл", "sum": 100.0},
                    {"name": "Чипсы Lays 120г", "sum": 150.0},
                    {"name": "Пакет-майка", "sum": 7.0}]
    basket = {"items": {1: {"verdict": "полезно", "reason": "молоко", "note": "основа рациона"},
                        2: {"verdict": "вредно", "reason": "чипсы", "note": "заменить на овощи"},
                        3: {"verdict": "лишнее", "reason": "пакет", "note": "брать свой"}}}
    ready = verdict_rows(basket, basket_items)
    # Четвёртое поле — происхождение вердикта. У разбора без правил его нет, и это честно:
    # пустое значение в базу попадёт как «без пометки», а не как правило.
    assert ready == [("Молоко Простоквашино 3,2% 930мл", "полезно", "молоко → основа рациона", ""),
                     ("Чипсы Lays 120г", "вредно", "чипсы → заменить на овощи", ""),
                     ("Пакет-майка", "лишнее", "пакет → брать свой", "")], ready
    # Позиция без вердикта в базу не пишется: выдумывать «нейтрально» за модель не нужно.
    assert verdict_rows({"items": {2: {"verdict": "вредно"}}}, basket_items) == \
        [("Чипсы Lays 120г", "вредно", "", "")], "пустые вердикты пропускаются"
    # А в бою разбор всегда идёт через правила, и тогда происхождение доезжает до строк.
    rule_marked = apply_review_rules(
        {"items": {1: {"verdict": "вредно", "reason": "", "note": ""}}},
        [{"name": "Чипсы Lays 120г", "sum": 150.0}])
    assert verdict_rows(rule_marked, [{"name": "Чипсы Lays 120г", "sum": 150.0}])[0][3] == "rule"

    verdict_user = 707
    async with aiosqlite.connect(digest_db) as db:
        for days_ago, positions in ((5, (("Молоко Простоквашино 3,2% 930мл", 100.0),
                                         ("СМЕТАНА 20% 300Г", 150.0), ("Пакет-майка", 7.0))),
                                    (30, (("Сметана 20% 300г", 150.0),))):
            cursor = await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, 'еда', 'Пятёрочка', 'expense', 'receipt', ?)",
                (verdict_user, sum(value for _, value in positions),
                 (now - _days(days=days_ago)).isoformat(sep=" ")))
            for name, value in positions:
                await db.execute(
                    "INSERT INTO receipt_items (transaction_id, name, qty, price, sum) "
                    "VALUES (?, ?, 1, ?, ?)", (cursor.lastrowid, name, value, value))
        await db.commit()
    # Первому чеку дописываем полные вердикты, второму — только «вредно»: как это делает бот.
    order = await database.get_transactions(user_id=verdict_user, days=90)
    newest, oldest = order[0]["id"], order[-1]["id"]
    # Вердикты адресуются названием позиции, а не порядком: в чеке сметана, а не чипсы,
    # поэтому чипсовый вердикт из разбора не уезжает к соседней строке — сметане отвечает
    # её собственный вердикт, а лишняя строка просто не пишется.
    assert await database.save_receipt_verdicts(
        newest, ready + [("СМЕТАНА 20% 300Г", "вредно", "десерт → заменить на фрукты", "")]) == 3
    assert await database.save_receipt_verdicts(oldest, [("Сметана 20% 300г", "вредно", "")]) == 1
    stored = [dict(row) for row in await database.get_receipt_verdicts(verdict_user)]
    assert [row["verdict"] for row in stored] == ["полезно", "вредно", "лишнее", "вредно"], stored
    assert stored[1]["name"] == "СМЕТАНА 20% 300Г" and \
        stored[1]["advice"] == "десерт → заменить на фрукты", stored[1]
    assert not any("чипсы" in (row["advice"] or "") for row in stored), \
        "чипсовый вердикт без позиции в чеке не приписывается соседним строкам"

    # 257 ₽ в свежем чеке и 150 ₽ в старом разобраны, необязательное — 157 + 150 = 307.
    waste = waste_summary(stored, days=90, today=now)
    assert waste["total"] == 407.0 and waste["waste"] == 307.0, waste
    assert round(waste["share"], 2) == 0.75, waste
    assert [item["name"] for item in waste["items"]] == ["СМЕТАНА 20% 300Г", "Сметана 20% 300г",
                                                          "Пакет-майка"], waste["items"]
    # Сметана из двух чеков — один и тот же товар, несмотря на регистр в чеке.
    repeats = waste["repeats"]
    assert len(repeats) == 1 and repeats[0]["count"] == 2 and repeats[0]["sum"] == 300.0, repeats
    text = waste_text(waste, 90)
    assert "Необязательные покупки" in text and "307" in text, text
    assert "Повторяется из чека в чек" in text and "Сметана" in text, text
    assert "не оценка экономии" in text, "отчёт честно говорит, чем он не является"
    # Экран, до которого нет кнопки, — мёртвый код: проверяем и обратную сторону.
    from keyboards.report_kb import get_report_kb
    buttons = [button.callback_data for row in get_report_kb().inline_keyboard
               for button in row]
    assert "report_waste" in buttons, buttons
    # Без разобранных чеков — честный отказ, а не нуль: пустая цифра читалась бы как экономия.
    assert waste_summary([], today=now) is None
    assert "считать нечего" in waste_text(None)
    assert waste_summary(stored, days=1, today=now) is None, "давние разборы в период не попадают"
    # Панель показывает ту же цифру: строки и вердикты берутся из той же таблицы.
    panel_waste = panel_data.analytics(verdict_user, today=now)["waste"]
    assert panel_waste["waste"] == 307.0 and panel_waste["total"] == 407.0, panel_waste
    assert [row["verdict"] for row in panel_data.receipt_verdicts(verdict_user)] == \
        ["полезно", "вредно", "лишнее", "вредно"]

    # Догадка модели не становится запретом молча: если про товар говорила только модель,
    # он не прячется из списка покупок, пока человек сам не подтвердил. Импорт — здесь же:
    # ниже в той же функции есть свой импорт этих имён, и без локального был бы UnboundLocal.
    from services.advice import (ban_text as bans_text_screen, banned as banned_groups,
                                 confirmed_keys as confirmed_now, guesses as model_guesses,
                                 positions as advice_positions, set_confirmed as set_confirmed_now,
                                 waste_groups)
    from services.purchase_history import product_key

    def waste_row(name, amount, verdict, source):
        return {"name": name, "sum": amount, "verdict": verdict, "advice": "",
                "verdict_source": source,
                "created_at": (now - _days(days=3)).isoformat(sep=" ")}

    rule_rows = [waste_row("Чипсы Lays 120г", 150.0, "вредно", "rule"),
                 waste_row("ЧИПСЫ LAYS 120Г", 150.0, "вредно", "rule")]
    model_rows = [waste_row("Сметана 20% 300г", 150.0, "лишнее", "model"),
                  waste_row("СМЕТАНА 20% 300Г", 150.0, "лишнее", "model")]
    both_rows = rule_rows + model_rows
    # `waste_groups` работает с позициями, то есть со строками после `positions` — как в бою.
    grouped = {entry["key"]: entry for entry in waste_groups(advice_positions(both_rows))}
    chips_key, smetana_key = product_key("Чипсы Lays 120г"), product_key("Сметана 20% 300г")
    assert grouped[chips_key]["rules"] == 2 and grouped[chips_key]["guess"] is False, grouped
    assert grouped[smetana_key]["models"] == 2 and grouped[smetana_key]["guess"] is True, grouped
    assert {entry["key"] for entry in banned_groups(both_rows)} == {chips_key}, \
        "в список попадает только то, что подтвердило правило"
    guess_list = model_guesses(both_rows)
    assert [entry["name"] for entry in guess_list] == ["СМЕТАНА 20% 300Г"], guess_list
    # Старый разбор без пометки догадкой не считается: неизвестный источник — не то же самое,
    # что «почти наверняка её чтение».
    unmarked_rows = [waste_row("Сметана 20% 300г", 150.0, "лишнее", ""),
                     waste_row("СМЕТАНА 20% 300Г", 150.0, "лишнее", "")]
    assert model_guesses(unmarked_rows) == [] and len(banned_groups(unmarked_rows)) == 1, \
        unmarked_rows
    # Человек может подтвердить догадку — тогда она становится обычным «не брать».
    await set_confirmed_now(verdict_user, smetana_key, True)
    assert await confirmed_now(verdict_user) == {smetana_key}
    assert len(banned_groups(both_rows, confirmed=await confirmed_now(verdict_user))) == 2
    assert model_guesses(both_rows, confirmed=await confirmed_now(verdict_user)) == []
    await set_confirmed_now(verdict_user, smetana_key, False)
    assert await confirmed_now(verdict_user) == set()
    # Экран говорит о догадках прямо: это её чтение, и бот такие товары не прячет.
    guess_text = bans_text_screen(banned_groups(both_rows), [], model_guesses(both_rows))
    assert "говорит только модель" in guess_text, guess_text
    assert "не убираю" in guess_text and "🔕 Это нормально" in guess_text, guess_text
    assert "СМЕТАНА 20% 300Г" in guess_text, guess_text
    only_guess = bans_text_screen([], [], model_guesses(both_rows))
    assert "Не брать" in only_guess and "Пока список пуст" not in only_guess, only_guess
    assert "Пока список пуст" in bans_text_screen([], [], [])

    # Пересчёт старых разборов: правила применяются к уже сохранённым вердиктам по названиям.
    # Ничего не пишется — функция возвращает план, а решает человек.
    from ai.receipts import recalc_verdict
    from services.advice import recalc_text, recalculate_old_verdicts

    # Макароны были «лишними» — это и есть та ошибка чтения, с которой началась ветка про правила.
    pasta = recalc_verdict("К.Ц.Изд.мак.ПЕРЬЯ В/С ГР.Б 400г", "лишнее", "ненужные перья")
    assert pasta["verdict"] == "нейтрально" and pasta["source"] == "rule", pasta
    assert "перья" not in pasta["advice"].lower(), pasta
    # Чипсы правило подтверждает — они остаются необязательными, но уже со своего вердикта.
    chips = recalc_verdict("Чипсы Lays 120г", "вредно", "снек, много калорий")
    assert chips["verdict"] == "вредно" and chips["source"] == "rule", chips
    # Товар, который правил не касается, остаётся вердиктом модели.
    unknown = recalc_verdict("Абрикосы свежие 1кг", "вредно", "сладкие")
    assert unknown["source"] == "model", unknown

    def stored_row(row_id, name, verdict, source, amount=100.0):
        return {"item_id": row_id, "name": name, "sum": amount, "verdict": verdict,
                "advice": "", "verdict_source": source,
                "created_at": (now - _days(days=4)).isoformat(sep=" ")}

    original = [stored_row(1, "К.Ц.Изд.мак.ПЕРЬЯ В/С ГР.Б 400г", "лишнее", ""),
                stored_row(2, "К.Ц.Изд.мак.ПЕРЬЯ В/С ГР.Б 400г", "лишнее", ""),
                stored_row(3, "Чипсы Lays 120г", "вредно", "model", 150.0),
                stored_row(4, "Чипсы Lays 120г", "вредно", "model", 150.0)]
    plan = recalculate_old_verdicts(original)
    assert plan["checked"] == 4, plan
    assert {entry["item_id"] for entry in plan["updated"]} == {1, 2, 3, 4}, plan["updated"]
    # У макарон поменялась группа («лишнее» → «нейтрально»), у чипсов — только совет:
    # он был пустой, а правило даёт конкретный. И то и другое — правка.
    assert [entry["item_id"] for entry in plan["changed"]] == [1, 2, 3, 4], plan["changed"]
    assert (plan["updated"][2]["verdict"], plan["updated"][2]["advice"]) == \
        ("вредно", "снек, много калорий → сравнить цену за 100 г и взять одну пачку"), plan["updated"][2]
    assert [entry["name"] for entry in plan["leave"]] == ["К.Ц.Изд.мак.ПЕРЬЯ В/С ГР.Б 400г"], plan["leave"]
    # Чипсы наоборот входят в список: раньше про них говорила только модель, теперь — правило.
    assert [entry["name"] for entry in plan["enter"]] == ["Чипсы Lays 120г"], plan["enter"]
    # Сдвиг суммы необязательного от пересчёта: 500 ₽ было (200 макарон + 300 чипсов),
    # стало 300 ₽ — макароны ушли из необязательного, чипсы остались.
    assert (plan["waste_before"], plan["waste_after"]) == (500.0, 300.0), plan
    recalc_line = recalc_text(plan)
    assert "Пересчитал" in recalc_line and "Больше не считаю необязательным" in recalc_line, recalc_line
    assert "ПЕРЬЯ" in recalc_line and "Из «не брать» уходит" in recalc_line, recalc_line
    assert "В «не брать» добавляется" in recalc_line, recalc_line
    assert "только совет, а не группа" in recalc_line, recalc_line
    assert "не трогались" in recalc_line, "сказано, что чеки и суммы не менялись"
    # Уже проверенное правилом не пересчитывается: повторный прогон ничего не находит.
    fixes = {entry["item_id"]: entry for entry in plan["updated"]}
    after_rows = [{**row, "verdict": fixes[row["item_id"]]["verdict"],
                   "advice": fixes[row["item_id"]]["advice"], "verdict_source": "rule"}
                  for row in original]
    again = recalculate_old_verdicts(after_rows)
    assert again["updated"] == [] and "нечего добавить" in recalc_text(again), again
    # Позиция, до которой правила не дотягиваются, остаётся догадкой модели — пересчёт её не трогает.
    untouched = recalculate_old_verdicts([stored_row(9, "Абрикосы свежие 1кг", "вредно", "model")])
    assert untouched["updated"] == [] and untouched["checked"] == 1, untouched
    assert recalc_text(None) == ""

    # Пересчёт двигает ту же долю, что и покупки: без оговорки отчёт выдавал бы это за смену
    # привычек. Запись хранится в настройках и читается тем же парсером в панели.
    import json as _json

    from services.advice import (last_recalc, parse_recalc, recalc_note, set_recalc_record,
                                 waste_trend)

    def trend_row(days_ago, verdict, amount):
        return {"name": "Позиция", "sum": amount, "verdict": verdict, "advice": "",
                "created_at": (now - _days(days=days_ago)).isoformat(sep=" ")}

    trend = waste_trend([trend_row(1, "полезно", 100), trend_row(1, "вредно", 20),
                         trend_row(8, "полезно", 100), trend_row(8, "вредно", 100)], today=now)
    await set_recalc_record(verdict_user, {"changed": [1, 2], "waste_before": 100.0,
                                           "waste_after": 80.0})
    record = await last_recalc(verdict_user)
    assert record["changed"] == 2 and record["waste_before"] == 100.0, record
    panel_record = panel_data.analytics(verdict_user, today=now)["recalc"]
    assert panel_record and panel_record["changed"] == 2, panel_record
    note = recalc_note(record, trend, today=now)
    assert "Оговорка к динамике" in note, note
    assert "уменьшилось на 20" in note and "2 поз." in note, note
    assert "а не твои покупки" in note, note
    assert "выросло на" in recalc_note({**record, "waste_after": 130.0}, trend, today=now)
    # Оговорка появляется только там, где есть что объяснять.
    assert recalc_note(record, None, today=now) == "", "без движения объяснять нечего"
    assert recalc_note(None, trend, today=now) == ""
    assert recalc_note({**record, "waste_after": 100.0}, trend, today=now) == "", "нуль — не сдвиг"
    assert recalc_note({**record, "moment": now - _days(days=60)}, trend, today=now) == "", \
        "давний пересчёт к этой динамике не относится"
    assert "Оговорка к динамике" in waste_text(waste, 90, trend=trend, recalc=record), \
        "в отчёте оговорка стоит под динамикой"
    assert parse_recalc("{не json") is None and parse_recalc("") is None
    assert parse_recalc(_json.dumps(["не", "запись"])) is None, "не объект — не запись"
    assert parse_recalc(_json.dumps({"changed": 1})) is None, "запись без даты не читается"

    # Цель на месяц: одна привычка, один измеримый шаг и проверка по чекам, а не по обещаниям.
    from services.advice import (GOAL_COUNT, GOAL_HISTORY_KEY, GOAL_HISTORY_LIMIT, GOAL_KEY,
                                 GOAL_SUM, GOAL_UNIT_KEY, close_goal_if_finished, goal_candidates,
                                 goal_digest_line, goal_followup_text, goal_history,
                                 goal_history_line, goal_history_text, goal_line, goal_money_step,
                                 goal_progress, goal_proposals_text, goal_report_line,
                                 goal_step_phrase, goal_target, goal_text, goal_unit,
                                 mark_goal_outcome_sent, parse_goal, parse_goal_history,
                                 set_goal, set_goal_unit, stored_goal)

    assert (goal_target(1.0), goal_target(2.0), goal_target(4.0), goal_target(6.0)) == (1, 1, 2, 3), \
        "цель — примерно вдвое меньше, но не ноль"
    habit_name = "Чипсы Lays 120г"

    def purchase(days_ago, name=habit_name, amount=150.0):
        return {"name": name, "sum": amount, "qty": 1, "price": amount,
                "created_at": (now - _days(days=days_ago)).isoformat(sep=" "),
                "description": "Пятёрочка"}

    habit_history = [purchase(day) for day in (2, 16, 30, 44)]
    habit_rows = [stored_row(1, habit_name, "вредно", "rule", 150.0),
                  stored_row(2, habit_name, "вредно", "rule", 150.0)]
    proposed, skipped = goal_candidates(habit_rows, habit_history + [purchase(3, "Молоко 1л", 90.0)])
    assert [item["name"] for item in proposed] == [habit_name], proposed
    assert proposed[0]["baseline"] >= 2 and proposed[0]["target"] == goal_target(
        proposed[0]["monthly"]), proposed[0]
    assert proposed[0]["target"] < round(proposed[0]["monthly"]), "цель должна быть ниже привычки"
    assert proposed[0]["saving"] > 0, proposed[0]
    # Цель не строится на догадке модели и на редком товаре.
    cafe = "Кофе в зернах Lavazza 1кг"
    assert goal_candidates([stored_row(3, cafe, "лишнее", "model"),
                            stored_row(4, cafe, "лишнее", "model")],
                           [purchase(day, cafe, 899.0) for day in (2, 17, 32)]) == ([], []), \
        "обещание не строится на догадке"
    assert goal_candidates([stored_row(5, "Пакет-майка", "лишнее", "rule"),
                            stored_row(6, "Пакет-майка", "лишнее", "rule")],
                           [purchase(3, "Пакет-майка", 7.0)]) == ([], []), "по одной покупке частоту не измерить"
    assert "нечего предложить" in goal_proposals_text([])
    proposals_text = goal_proposals_text(proposed)
    assert habit_name in proposals_text and "не чаще" in proposals_text, proposals_text
    assert "в месяц" in proposals_text, proposals_text
    # Товар без денежного шага не пропадает молча при переключении в деньги: он назван
    # со своей причиной, а пустой экран в деньгах говорит про единицу, а не про чеки.
    # (Пакет для пробы не годится: его токены — стоп-слова, и ключа у товара нет вовсе.)
    cheap = "Спички хозяйственные"
    cheap_rows = [stored_row(7, cheap, "лишнее", "rule", 12.0),
                  stored_row(8, cheap, "лишнее", "rule", 12.0)]
    cheap_history = [purchase(day, cheap, 12.0) for day in (2, 16, 30)]
    money_both, money_skipped = goal_candidates(
        habit_rows + cheap_rows, habit_history + cheap_history + [purchase(3, "Молоко 1л", 90.0)],
        unit=GOAL_SUM)
    assert [item["name"] for item in money_both] == [habit_name], money_both
    assert [item["name"] for item in money_skipped] == [cheap], money_skipped
    money_text = goal_proposals_text(money_both, GOAL_SUM, skipped=money_skipped)
    assert cheap in money_text and "Показаны не все" in money_text, money_text
    assert "шага в деньгах нет" in money_text, money_text
    only_skipped, only_skipped_list = goal_candidates(
        cheap_rows, cheap_history, unit=GOAL_SUM)
    assert only_skipped == [] and [item["name"] for item in only_skipped_list] == [cheap], \
        (only_skipped, only_skipped_list)
    empty_money = goal_proposals_text([], GOAL_SUM, skipped=only_skipped_list)
    assert "шага нет" in empty_money and "Отправь новые чеки" not in empty_money, empty_money
    assert "Отправь новые чеки" in goal_proposals_text([])  # в разах прежний честный отказ

    # Ход цели: окно — месяц с дня постановки, а не календарный месяц. Покупки привязаны
    # к дню постановки, а не к «столько-то дней назад»: тест не должен зависеть от того,
    # какого числа месяца его запустили.
    month_start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    goal_started = now - _days(days=6)
    active_goal = parse_goal(_json.dumps(
        {"key": proposed[0]["key"], "name": habit_name, "target": 2, "baseline": 4.0,
         "usual": 150.0, "started_at": goal_started.isoformat(sep=" ")}))
    assert (active_goal["ends"] - active_goal["started"]).days == 30, active_goal["ends"]

    def dated(days_from_start: int) -> dict:
        return {**purchase(0),
                "created_at": (goal_started + _days(days=days_from_start)).isoformat(sep=" ")}

    window_history = [dated(-20), dated(-3), dated(4), dated(25)]
    started = goal_progress(active_goal, window_history, today=now)
    # В окно попадает только покупка на 4-й день после постановки: -20 и -3 были до неё,
    # +25 — в будущем, а чек из будущего цель заранее не съедает.
    assert started["bought"] == 1 and started["target"] == 2 and not started["over"], started
    before_start = goal_progress(active_goal, window_history,
                                 today=goal_started + _days(days=3))
    assert before_start["bought"] == 0, "чек из будущего цель заранее не съедает"
    active_line = goal_line(active_goal, goal_progress(active_goal, [purchase(0)], today=now),
                            today=now)
    assert "🎯 Цель до" in active_line and "не чаще 2 раза" in active_line, active_line
    assert "обычная частота 4" in active_line and "осталось" in active_line, active_line
    tight_goal = parse_goal(_json.dumps(
        {"key": proposed[0]["key"], "name": habit_name, "target": 1, "baseline": 4.0,
         "usual": 150.0, "started_at": month_start.isoformat(sep=" ")}))
    over = goal_progress(tight_goal, [purchase(0), purchase(0)], today=now)
    assert over["over"] and over["bought"] == 2, over
    assert "цель не сдержится" in goal_line(tight_goal, over, today=now), over
    assert "Считаю по чекам" in goal_text(tight_goal, over, today=now), goal_text(tight_goal, over, today=now)
    # Окно названо датами: цель, взятая 29-го, не превращается в цель на два дня.
    assert f"до {tight_goal['ends']:%d.%m}" in goal_text(tight_goal, over, today=now)
    assert "ориентир, а не запрет" in goal_text(tight_goal, over, today=now)
    assert goal_text(None, None) == "" and goal_line(None, None) == ""

    # Прошлый месяц: цель закончилась, и исход называется словами — выполнилась или нет.
    past_start = (month_start - _days(days=1)).replace(day=1)
    past_goal = parse_goal(_json.dumps(
        {"key": proposed[0]["key"], "name": habit_name, "target": 1, "baseline": 4.0,
         "usual": 150.0, "started_at": past_start.isoformat(sep=" ")}))
    inside = [{**purchase(0), "created_at": (past_start + _days(days=delta)).isoformat(sep=" ")}
              for delta in (1, 5)]
    check_day = past_goal["ends"] + _days(days=2)
    finished = goal_progress(past_goal, inside, today=check_day)
    assert finished["finished"] and finished["bought"] == 2 and not finished["met"], finished
    failed_line = goal_line(past_goal, finished, today=check_day)
    assert "не вышло" in failed_line and "не упрёк" in failed_line, failed_line
    forgiving = parse_goal(_json.dumps(
        {"key": proposed[0]["key"], "name": habit_name, "target": 3, "baseline": 4.0,
         "usual": 150.0, "started_at": past_start.isoformat(sep=" ")}))
    met = goal_progress(forgiving, inside, today=check_day)
    met_line = goal_line(forgiving, met, today=check_day)
    assert met["met"] and "выполнена" in met_line and "не ушли" in met_line, met_line
    # Давность больше не решает, говорить ли итог: сводка говорит его ровно один раз, а от
    # повторения защищает отметка после отправки. Экран цели показывает итог всегда.
    assert goal_line(past_goal, finished, today=check_day + _days(days=300)) != "", \
        "экран цели не забывает итог"
    assert goal_digest_line(past_goal, finished, today=check_day) != "", "итог ещё не сказан"
    assert goal_digest_line(past_goal, finished, today=check_day + _days(days=30)) != "", \
        "пропущенная отправка итог не съедает"
    assert goal_digest_line({**past_goal, "announced_at": "2026-01-01 10:00:00"},
                            finished, today=check_day) == "", "сказанное не повторяется"
    assert goal_digest_line(active_goal, goal_progress(active_goal, habit_history, today=now),
                            today=now) != "", "ход цели сводка говорит всегда"
    # Цель живёт месяц и убирается решением человека.
    await set_goal(verdict_user, proposed[0])
    saved_goal = await stored_goal(verdict_user)
    assert saved_goal["key"] == proposed[0]["key"], saved_goal
    assert saved_goal["target"] == proposed[0]["target"], saved_goal
    assert "🎯 Цель до" in panel_data.analytics(verdict_user, today=now)["goal"], "панель видит цель"
    from services.digest import build_weekly_digest

    weekly = build_weekly_digest(start=now - _days(days=7), end=now, spent=0, previous_spent=0,
                                 goal_line=active_line)
    assert active_line in weekly, "цель видна в недельной сводке"
    assert "🎯" not in build_weekly_digest(start=now - _days(days=7), end=now, spent=0,
                                           previous_spent=0), "без цели строки нет"
    await set_goal(verdict_user, None)
    assert await stored_goal(verdict_user) is None

    # Закончившаяся цель не запирает экран: вместе с итогом на нём есть что взять следующим.
    followup = goal_followup_text(proposed)
    assert "Можно взять следующую" in followup and habit_name in followup, followup
    assert "Новой пока нет" in goal_followup_text([]), goal_followup_text([])
    assert "Отправь новые чеки" in goal_followup_text([])

    # Итог отмечается только после отправки и только у закончившейся цели — иначе отметка
    # съела бы либо будущий итог, либо сам итог у человека с сорванной отправкой.
    key = GOAL_KEY.format(user_id=verdict_user)
    await set_goal(verdict_user, proposed[0])
    raw = _json.loads(await database.get_setting(key, ""))
    raw["started_at"] = (now - _days(days=40)).isoformat(sep=" ")
    await database.set_setting(key, _json.dumps(raw))
    ended = await stored_goal(verdict_user)
    assert goal_progress(ended, habit_history, today=now)["finished"], ended
    await mark_goal_outcome_sent(verdict_user)
    marked = await stored_goal(verdict_user)
    assert marked.get("announced_at"), marked
    # В базе лежит только начало окна: конец выводится, и второй копии дат в записи нет.
    stored_raw = _json.loads(await database.get_setting(key, ""))
    assert "ends" not in stored_raw and stored_raw["started_at"], stored_raw
    assert goal_digest_line(marked, goal_progress(marked, habit_history, today=now)) == "", \
        "повторно итог не говорится"
    await mark_goal_outcome_sent(verdict_user)
    assert (await stored_goal(verdict_user))["announced_at"] == marked["announced_at"], \
        "повторная отметка ничего не меняет"
    await set_goal(verdict_user, proposed[0])
    await mark_goal_outcome_sent(verdict_user)
    assert not (await stored_goal(verdict_user)).get("announced_at"), \
        "ход цели итогом не считается"
    await set_goal(verdict_user, None)
    await mark_goal_outcome_sent(verdict_user)   # без цели — ничего, но и не падение
    assert await stored_goal(verdict_user) is None

    # История целей: итог записывается один раз, хранится ограниченным списком и не
    # теряется при закрытии — иначе бот не может сказать «сдержано N из M».
    assert parse_goal_history("{не json") == [] and parse_goal_history("") == []
    assert parse_goal_history(_json.dumps({"key": "одиночка"})) == [], "не список — не история"
    assert parse_goal_history(_json.dumps(["строка", {"key": "без имени"}])) == []
    assert goal_history_line([]) == "" and goal_history_text([]) == ""
    # Строка для обычного отчёта: ход активной цели или счёт по прошлым — и ничего, если
    # целей не было вовсе. Та же строка, что в дайджесте и панели, без своей версии.
    # Денежный шаг: та же цель в других единицах — «не больше 210 ₽» вместо «не чаще 1 раза».
    assert goal_money_step(429.0, 150.0) == 210, goal_money_step(429.0, 150.0)
    assert goal_money_step(2500.0, 300.0) == 1250, "выше тысячи округляется до 50 ₽"
    assert goal_money_step(120.0, 110.0) is None, "шаг ниже одной покупки — это запрет, а не цель"
    assert goal_money_step(150.0, 150.0) is None, "шаг равен тратам — обещания нет"
    assert goal_money_step(0.0, 0.0) is None and goal_money_step(300.0, 0.0) is None

    money_candidates, money_skipped2 = goal_candidates(
        habit_rows, habit_history + [purchase(3, "Молоко 1л", 90.0)], unit=GOAL_SUM)
    assert [item["name"] for item in money_candidates] == [habit_name], money_candidates
    assert money_candidates[0]["unit"] == GOAL_SUM and money_candidates[0]["limit"] == 210, \
        money_candidates[0]
    assert money_candidates[0]["target"] == 0, "в деньгах шаг по разам не считается"
    assert money_candidates[0]["saving"] == 219.0 and money_candidates[0]["spend"] == 429.0, \
        money_candidates[0]
    assert "не больше 210 ₽ в месяц" in goal_step_phrase(money_candidates[0]), \
        goal_step_phrase(money_candidates[0])

    # Единица — предпочтение человека, и незнакомая запись читается как раза, а не как поломка.
    assert await goal_unit(verdict_user) == GOAL_COUNT
    await database.set_setting(GOAL_UNIT_KEY.format(user_id=verdict_user), "чепуха")
    assert await goal_unit(verdict_user) == GOAL_COUNT, "незнакомая единица — это раза"
    await set_goal_unit(verdict_user, GOAL_SUM)
    assert await goal_unit(verdict_user) == GOAL_SUM
    in_money, in_money_skipped = goal_candidates(
        habit_rows, habit_history + [purchase(3, "Молоко 1л", 90.0)],
        unit=await goal_unit(verdict_user))
    assert in_money and in_money[0]["limit"] == 210, in_money

    # Ход цели в деньгах считается по суммам, а не по числу покупок.
    money_goal = parse_goal(_json.dumps(
        {"key": proposed[0]["key"], "name": habit_name, "unit": GOAL_SUM, "limit": 210,
         "target": 1, "baseline": 2.9, "usual": 150.0, "spend": 429.0,
         "started_at": month_start.isoformat(sep=" ")}))
    over_money = goal_progress(money_goal, [{**purchase(0), "sum": 240.0}], today=now)
    assert over_money["over"] and not over_money["met"], over_money
    assert over_money["spent"] == 240.0 and over_money["saved"] == 189.0, over_money
    money_line = goal_line(money_goal, over_money, today=now)
    assert "не больше 210 ₽ в месяц" in money_line, money_line
    assert "Уже 240 ₽ из 210 ₽" in money_line, money_line
    assert "обычно уходит 429 ₽ в месяц" in money_line, money_line
    assert "обычно уходило" not in money_line, "о текущей цели — в настоящем времени"
    # Шаг один, но видно его в обеих единицах: в тексте есть тот же шаг в разах.
    assert "Это примерно не чаще 1 раз в месяц" in goal_text(money_goal, over_money, today=now)
    tight_money = {**money_goal, "limit": 400, "target": 3}
    assert "Это примерно не больше 400 ₽ в месяц" in goal_text(
        {**tight_money, "unit": GOAL_COUNT}, goal_progress(
            {**tight_money, "unit": GOAL_COUNT}, [{**purchase(0), "sum": 240.0}], today=now),
        today=now), "цель в разах показывает ту же цель в деньгах"
    # Исход в деньгах: счёт идёт по рублям, обычно потраченное — по прошлому темпу.
    inside_money = [{**purchase(0), "sum": 100.0,
                     "created_at": (month_start + _days(days=1)).isoformat(sep=" ")}]
    spent_money = goal_progress(money_goal, inside_money,
                                today=money_goal["ends"] + _days(days=1))
    assert spent_money["met"] and spent_money["spent"] == 100.0, spent_money
    met_money_line = goal_line(money_goal, spent_money,
                               today=money_goal["ends"] + _days(days=1))
    assert "100 ₽ из 210 ₽ — выполнена" in met_money_line, met_money_line
    assert "~329 ₽ в месяц, которые не ушли" in met_money_line, met_money_line

    await set_goal(verdict_user, money_candidates[0])
    money_stored = await stored_goal(verdict_user)
    assert money_stored["unit"] == GOAL_SUM and money_stored["limit"] == 210, money_stored
    assert money_stored["target"] == 1, "та же цель в разах хранится рядом для показа"
    assert goal_progress(money_stored, habit_history, today=now)["unit"] == GOAL_SUM
    await set_goal(verdict_user, None)
    await set_goal_unit(verdict_user, GOAL_COUNT)

    assert goal_report_line(None, None, []) == "", "без целей отчёт молчит"
    assert goal_report_line(None, None, [{"name": "Товар", "met": True}]) == \
        "🏁 Сдержано 1 из 1 целей."
    assert goal_report_line(active_goal, goal_progress(active_goal, habit_history, today=now),
                            [], today=now) == goal_line(
        active_goal, goal_progress(active_goal, habit_history, today=now), today=now), \
        "ход цели берётся у того же форматтера"

    await set_goal(verdict_user, proposed[0])
    raw = _json.loads(await database.get_setting(key, ""))
    raw["started_at"] = (now - _days(days=40)).isoformat(sep=" ")
    await database.set_setting(key, _json.dumps(raw))
    await close_goal_if_finished(verdict_user, today=now)
    first_entry = await goal_history(verdict_user)
    assert len(first_entry) == 1 and first_entry[0]["name"] == habit_name, first_entry
    assert first_entry[0]["met"] and first_entry[0]["closed_at"], first_entry[0]
    assert await goal_history(verdict_user) == first_entry, "повторный вызов ничего не добавляет"
    await close_goal_if_finished(verdict_user, today=now)
    assert len(await goal_history(verdict_user)) == 1, "итог не задваивается"
    # Незакончившаяся цель в историю не попадает: у неё ещё есть ход.
    await set_goal(verdict_user, proposed[0])
    await close_goal_if_finished(verdict_user, today=now)
    assert len(await goal_history(verdict_user)) == 1, "живую цель в историю не пишу"
    # Предел хранения: список не растёт без конца, а старое уходит.
    long_history = [{"name": f"Товар {index}", "met": index % 2 == 0} for index in range(40)]
    await database.set_setting(GOAL_HISTORY_KEY.format(user_id=verdict_user),
                              _json.dumps(long_history))
    assert len(await goal_history(verdict_user)) == 40, "парсер список не режет"
    raw = _json.loads(await database.get_setting(key, ""))
    raw["started_at"] = (now - _days(days=40)).isoformat(sep=" ")
    raw.pop("closed_at", None)
    await database.set_setting(key, _json.dumps(raw))
    await close_goal_if_finished(verdict_user, today=now)
    capped = await goal_history(verdict_user)
    assert len(capped) == GOAL_HISTORY_LIMIT and capped[0]["name"] == habit_name, len(capped)
    capped_line = goal_history_line(capped)
    assert "глубже бот не хранит" in capped_line, capped_line
    capped_text = goal_history_text(capped)
    assert "Сдержано" in capped_text and "и ещё" in capped_text, capped_text
    assert "ориентир" in capped_text, capped_text
    await database.set_setting(GOAL_HISTORY_KEY.format(user_id=verdict_user), "")
    await set_goal(verdict_user, None)

    assert parse_goal("{не json") is None and parse_goal("") is None
    assert parse_goal(_json.dumps({"name": "без ключа"})) is None, "цель без ключа товара не читается"

    # Происхождение вердикта доезжает до отчёта: видно, что поставлено правилом, а что —
    # оценкой модели. У разборов без пометки источник неизвестен, и делением это не считается.
    from services.advice import source_split_text

    assert waste["by_source"] == {"unknown": 307.0}, waste["by_source"]
    assert source_split_text(waste) == "", "без пометок делить нечего"
    assert panel_waste["by_source"] == {"unknown": 307.0}, panel_waste["by_source"]
    assert source_split_text(None) == ""

    def sourced(name, amount, verdict, source):
        return {"name": name, "sum": amount, "verdict": verdict, "advice": "",
                "verdict_source": source,
                "created_at": (now - _days(days=2)).isoformat(sep=" ")}

    mixed_summary = waste_summary(
        [sourced("Молоко Простоквашино 3,2% 930мл", 150.0, "вредно", "model"),
         sourced("Пакет-майка", 7.0, "лишнее", "rule")], days=90, today=now)
    assert mixed_summary["by_source"] == {"model": 150.0, "rule": 7.0}, mixed_summary["by_source"]
    split_text = source_split_text(mixed_summary)
    assert "Из 157" in split_text and "проверка по правилам" in split_text, split_text
    assert "оценка модели" in split_text and "🔧 Не согласен" in split_text, split_text
    assert split_text in waste_text(mixed_summary, 90), "строка происхождения есть в отчёте"
    # Весь необязательный от одного источника называется прямо, а не как «разделение».
    only_model = source_split_text(waste_summary(
        [sourced("Чипсы Lays 120г", 150.0, "вредно", "model")], days=90, today=now))
    assert "Весь необязательный" in only_model and "оценка модели" in only_model, only_model
    assert "не проверка по названию" in only_model, only_model
    only_rule = source_split_text(waste_summary(
        [sourced("Пакет-майка", 7.0, "лишнее", "rule")], days=90, today=now))
    assert "проверка по правилам" in only_rule and "Весь необязательный" in only_rule, only_rule
    assert source_split_text(waste_summary(
        [sourced("Пакет-майка", 7.0, "лишнее", "")], days=90, today=now)) == ""
    # Пометка — свойство позиции чека, а не разбора в памяти: она сохраняется и читается.
    assert await database.save_receipt_verdicts(
        oldest, [("Сметана 20% 300г", "вредно", "", "model")]) == 1
    reread = {row["name"]: dict(row) for row in await database.get_receipt_verdicts(verdict_user)}
    assert reread["Сметана 20% 300г"]["verdict_source"] == "model", reread["Сметана 20% 300г"]
    assert reread["Пакет-майка"]["verdict_source"] in (None, ""), reread["Пакет-майка"]
    # Строка без четвёртого поля остаётся без пометки: догадка вместо факта не пишется.
    await database.save_receipt_verdicts(oldest, [("Сметана 20% 300г", "вредно", "")])
    assert {row["name"]: dict(row) for row in await database.get_receipt_verdicts(
        verdict_user)}["Сметана 20% 300г"]["verdict_source"] in (None, "")

    # Товар, который берут снова, узнаётся по прошлым вердиктам — тем же сопоставлением,
    # что и в каталоге цен: «Сметана 20% 300г» из нового чека и «СМЕТАНА 20% 300Г» из старого.
    from services.advice import repeat_text, repeat_warnings

    again = [{"name": "Сметана 20% 300г", "sum": 180.0},
             {"name": "Молоко Простоквашино 3,2% 930мл", "sum": 110.0},
             {"name": "Сыр Российский 200г", "sum": 200.0}]
    reminders = repeat_warnings(again, stored)
    assert len(reminders) == 1 and reminders[0]["name"] == "Сметана 20% 300г", reminders
    assert reminders[0]["count"] == 2 and reminders[0]["verdict"] == "вредно", reminders
    assert reminders[0]["last_sum"] == 150.0, reminders
    reminder = repeat_text(reminders)
    assert "Уже было" in reminder and "Сметана 20% 300г" in reminder, reminder
    assert "2 раза" in reminder and "не запрет" in reminder, reminder
    assert repeat_text([]) == "" and repeat_warnings([], stored) == []
    assert repeat_warnings(again, [{"name": "Сметана", "verdict": "полезно"}]) == [], \
        "полезное и нейтральное напоминаний не даёт"
    # Текущий чек не должен находить сам себя: он исключается по id, и его вердикты
    # в историю напоминаний не попадают.
    without_current = [dict(row) for row in await database.get_receipt_verdicts(
        verdict_user, exclude_transaction_id=newest)]
    assert [row["verdict"] for row in without_current] == ["вредно"], without_current
    assert repeat_warnings([{"name": "СМЕТАНА 20% 300Г"}], without_current)[0]["count"] == 1, \
        "без текущего чека у сметаны один прошлый случай, а не два"

    # Личный список «не брать»: товар попадает в него после двух необязательных разборов,
    # а ключ у него тот же, что в списке покупок и каталоге цен.
    from services.advice import (allowed_keys, ban_text, banned, blocked_keys,
                                 confirmed_keys, guesses, set_allowed, set_confirmed,
                                 waste_groups)
    from services.purchase_history import product_key
    from services.shopping import hide_blocked

    ban_list = banned(stored)
    assert len(ban_list) == 1 and ban_list[0]["key"] == product_key("Сметана 20% 300г"), ban_list
    assert ban_list[0]["count"] == 2 and ban_list[0]["sum"] == 300.0, ban_list
    assert [entry["name"] for entry in ban_list] == ["СМЕТАНА 20% 300Г"], ban_list
    assert "Пакет-майка" not in [entry["name"] for entry in banned(stored, min_bans=1)], \
        "порог в один разбор отключается явно"
    bans_text = ban_text(ban_list)
    assert "Не брать" in bans_text and "2 раза" in bans_text, bans_text
    assert "заменить на фрукты" in bans_text, bans_text
    assert "Пока список пуст" in ban_text([], [])

    # Разрешённый товар уходит из блокировки и возвращается обратно тем же ключом.
    ban_key = ban_list[0]["key"]
    assert banned(stored, allowed={ban_key}) == []
    await set_allowed(verdict_user, ban_key, True)
    assert await allowed_keys(verdict_user) == {ban_key}, await allowed_keys(verdict_user)
    assert await blocked_keys(verdict_user) == set(), "разрешённый товар больше не блокирует список"
    await set_allowed(verdict_user, ban_key, False)
    assert await blocked_keys(verdict_user) == {ban_key}, await blocked_keys(verdict_user)

    # Скрытое отдаётся отдельно: в экране должно быть видно, что товар убран и почему.
    shown_now, hidden_now = hide_blocked(
        [{"key": ban_key, "name": "Сметана"}, {"key": "другой", "name": "Хлеб"}], {ban_key})
    assert [item["name"] for item in shown_now] == ["Хлеб"], shown_now
    assert [item["name"] for item in hidden_now] == ["Сметана"], hidden_now
    assert hide_blocked([{"key": "a"}], set()) == ([{"key": "a"}], [])

    # Правка вердикта человеком: спор с разбором идёт в тот же ключ «разрешено», потому
    # поправленное перестаёт считаться необязательным и больше не напоминается.
    from services.advice import corrected_positions, corrected_text, fixed_text

    corrected = corrected_positions(stored, {ban_key})
    assert len(corrected) == 1 and corrected[0]["key"] == ban_key, corrected
    assert corrected[0]["count"] == 2 and corrected[0]["sum"] == 300.0, corrected
    assert corrected_positions(stored, set()) == [], "без правок считать нечего"
    fixed_line = corrected_text(corrected)
    assert "Ты поправил разбор" in fixed_line and "1 товар" in fixed_line, fixed_line
    assert "разбор ошибся" in fixed_line, "правка называет причину: ошибся разбор, а не человек"
    assert "300" in fixed_line and "🚫 Не брать" in fixed_line, fixed_line
    assert corrected_text([]) == "" and corrected_text(None) == ""
    assert "Сметана" in fixed_text("Сметана 20% 300г") and "📊 Отчёт" in fixed_text("Сметана")

    # В сумме необязательного поправленного больше нет, а в разобранных позициях оно остаётся:
    # человек убрал товар из «лишнего», а не из чека.
    fixed_waste = waste_summary(stored, days=90, today=now, allowed={ban_key})
    assert fixed_waste["waste"] == 7.0 and fixed_waste["total"] == 407.0, fixed_waste
    assert [item["name"] for item in fixed_waste["items"]] == ["Пакет-майка"], fixed_waste["items"]
    assert fixed_waste["repeats"] == [], "поправленный товар больше не «повторяется»"
    assert "Ты поправил разбор" in waste_text(fixed_waste, 90, corrected=corrected), \
        "в отчёте видно, что именно убрано, а не только новая сумма"
    # Напоминание про товар, который человек уже поправил, не возвращается.
    assert repeat_warnings([{"name": "Сметана 20% 300г"}], stored, {ban_key}) == [], \
        "поправленное больше не напоминается"
    assert repeat_warnings([{"name": "Сметана 20% 300г"}], stored)[0]["count"] == 2, \
        "без правки напоминание на месте"

    # Панель считает ту же цифру теми же функциями: поправленное видно и в ней.
    await set_allowed(verdict_user, ban_key, True)
    panel_stats = panel_data.analytics(verdict_user, today=now)
    assert panel_stats["waste"]["waste"] == 7.0, panel_stats["waste"]
    assert [item["name"] for item in panel_stats["waste_corrected"]] == ["СМЕТАНА 20% 300Г"], \
        panel_stats["waste_corrected"]
    await set_allowed(verdict_user, ban_key, False)
    assert panel_data.analytics(verdict_user, today=now)["waste_corrected"] == [], \
        "снятая правка убирает строку из панели"

    # Динамика: по неделям видно, меняется ли доля необязательного — то есть срабатывают ли советы.
    from services.advice import trend_text, waste_trend

    def verdict_row(days_ago, verdict, amount):
        return {"name": "Позиция", "sum": amount, "verdict": verdict, "advice": "",
                "created_at": (now - _days(days=days_ago)).isoformat(sep=" ")}

    trend = waste_trend([verdict_row(1, "полезно", 100), verdict_row(1, "вредно", 20),
                         verdict_row(8, "полезно", 100), verdict_row(8, "вредно", 100)], today=now)
    assert [round(week["share"], 2) for week in trend["weeks"]] == [0.5, 0.17], trend
    assert trend["delta"] < 0, trend
    trend_line = trend_text(trend)
    assert "🟢" in trend_line and "меньше" in trend_line, trend_line
    assert "недели шумят" in trend_line, "в отчёте есть оговорка про шум"
    # Одна неделя — не тренд: «стало лучше» по одной точке было бы выдумкой.
    assert waste_trend([verdict_row(2, "лишнее", 50)], today=now) is None
    assert waste_trend([], today=now) is None and trend_text(None) == ""
    # Разница в пределах шума движением не объявляется, рост называется ростом.
    flat = waste_trend([verdict_row(1, "полезно", 100), verdict_row(1, "вредно", 52),
                        verdict_row(8, "полезно", 100), verdict_row(8, "вредно", 50)], today=now)
    assert "➖" in trend_text(flat) and "одном уровне" in trend_text(flat), trend_text(flat)
    worse = waste_trend([verdict_row(1, "полезно", 40), verdict_row(1, "вредно", 60),
                         verdict_row(8, "полезно", 80), verdict_row(8, "вредно", 20)], today=now)
    assert "🔺" in trend_text(worse) and "стало больше" in trend_text(worse), trend_text(worse)
    # Пятая неделя в четырёхнедельное окно не попадает: давние разборы динамику не искажают.
    assert waste_trend([verdict_row(30, "вредно", 100), verdict_row(3, "вредно", 100)],
                       today=now) is None
    two_weeks = [verdict_row(1, "вредно", 90), verdict_row(8, "вредно", 40)]
    assert "Доля необязательного по неделям" in waste_text(
        waste_summary(two_weeks, today=now), 90, trend=waste_trend(two_weeks, today=now)), \
        "динамика видна и в самом отчёте"

    # 7г. Потолок экономии в месяц: темп окна пересчитывается на месяц, а не «цена × 30»,
    # и в прогноз идут только привычки — случайная покупка в него не попадает.
    from services.advice import saving_forecast, saving_scale_line, saving_text

    def habit(days_ago, name, verdict, amount):
        return {"name": name, "sum": amount, "verdict": verdict, "advice": "",
                "created_at": (now - _days(days=days_ago)).isoformat(sep=" ")}

    habits = [habit(1, "Чипсы Lays 120г", "вредно", 150),
              habit(8, "ЧИПСЫ LAYS 120Г", "вредно", 150),   # тот же товар другим написанием
              habit(3, "Молоко 1л", "полезно", 100),        # полезное не считается
              habit(5, "Пакет-майка", "лишнее", 7)]         # один раз — не привычка
    forecast = saving_forecast(habits, days=90, today=now)
    assert forecast["count"] == 1, forecast
    assert forecast["monthly"] == 100.0, forecast     # 300 ₽ за 90 дней = 100 ₽ в месяц
    # Название берётся из последней покупки — оно и показывается человеку.
    assert forecast["items"][0]["name"] == "Чипсы Lays 120г", forecast
    reserve = saving_text(forecast)
    assert "в месяц" in reserve and "~100 ₽" in reserve, reserve
    assert "потолок" in reserve and "не обещание" in reserve, reserve
    assert "Сколько это в месяц" in waste_text(waste_summary(habits, today=now), 90,
                                                       saving=forecast), "блок есть в отчёте"
    # Разрешённый человеком товар в потолок не идёт: он уже сказал, что возьмёт его.
    assert saving_forecast(habits, {product_key("Чипсы Lays 120г")}, days=90, today=now) is None
    # Давние разборы в окно не попадают, а без привычек прогноза нет вовсе.
    old = [habit(120, "Чипсы Lays 120г", "вредно", 150),
           habit(130, "ЧИПСЫ LAYS 120Г", "вредно", 150)]
    assert saving_forecast(old, days=90, today=now) is None, "окно прогноза — как у отчёта"
    assert saving_forecast([], today=now) is None and saving_text(None) == ""
    assert saving_text(saving_forecast([habit(1, "Пакет", "лишнее", 7)], today=now)) == "", \
        "одной покупки для прогноза мало"
    # Масштаб потолка: доли лимита и дохода считаются там же, где прогноз, — и не нолём,
    # если сумма мала: «0% лимита» читалось бы как «ничего не значит».
    scaled = saving_forecast(habits, days=90, today=now, income=10_000, limit=1_000)
    assert scaled["share_limit"] == "10%" and scaled["share_income"] == "1%", scaled
    assert "10% месячного лимита" in saving_text(scaled), saving_text(scaled)
    assert "Для масштаба" in saving_text(scaled) and "дохода" in saving_text(scaled), scaled
    tiny = saving_forecast(habits, days=90, today=now, income=100_000, limit=100_000)
    assert tiny["share_limit"] == "меньше 1%", tiny
    assert "меньше 1%" in saving_scale_line(tiny), saving_scale_line(tiny)
    # Без лимита и дохода мерить не в чем: строки нет, а не «0%».
    unscaled = saving_forecast(habits, days=90, today=now)
    assert unscaled["share_limit"] is None and unscaled["share_income"] is None, unscaled
    assert saving_scale_line(unscaled) == "" and saving_scale_line(None) == ""
    assert "Для масштаба" not in saving_text(unscaled), saving_text(unscaled)

    # 7з. Эффект советов: частота товара до первого совета и после него — по всей истории
    # чеков, а не по вердиктам, и с честным «рано судить» вместо преждевременных выводов.
    from services.advice import advice_effects, effects_line, effects_text

    def purchase(days_ago, name, price=100.0):
        return {"name": name, "qty": 1, "price": price, "sum": price,
                "created_at": (now - _days(days=days_ago)).isoformat(sep=" "),
                "description": "Пятёрочка"}

    def advised(days_ago, name, verdict="вредно"):
        return {"name": name, "sum": 100.0, "verdict": verdict, "advice": "заменить на овощи",
                "created_at": (now - _days(days=days_ago)).isoformat(sep=" ")}

    chips = "Чипсы Lays 120г"
    effect_rows = [advised(40, chips), advised(10, "Сметана 20% 300г"), advised(50, "Молоко 1л")]
    effect_history = [purchase(100, chips), purchase(80, chips), purchase(60, chips),
                      purchase(5, chips),
                      purchase(90, "Сметана 20% 300г"), purchase(70, "Сметана 20% 300г"),
                      purchase(80, "Молоко 1л")]
    measured = advice_effects(effect_rows, effect_history, today=now)
    assert len(measured["effects"]) == 1, measured
    chips_effect = measured["effects"][0]
    assert chips_effect["name"] == chips, chips_effect
    assert chips_effect["before"] == 3 and chips_effect["after"] == 1, chips_effect
    assert chips_effect["change"] >= 0.5, chips_effect     # три покупки стали одной — реже
    # Сметана: две покупки до совета есть, но совету всего 10 дней — вывод был бы выдумкой.
    # Молоко: одна покупка до совета — базы для сравнения нет, товар не измеряется вовсе.
    assert [item["name"] for item in measured["pending"]] == ["Сметана 20% 300г"], measured
    assert "Молоко" not in effects_text(measured), effects_text(measured)
    measured_text = effects_text(measured)
    assert "реже" in measured_text and "не доказательство" in measured_text, measured_text
    assert "Рано судить" in measured_text, measured_text
    # Товар вообще перестал появляться в чеках — это тоже результат, и он называется прямо.
    stopped = advice_effects([advised(40, "Сухарики Кириешки")],
                             [purchase(120, "Сухарики Кириешки"),
                              purchase(100, "Сухарики Кириешки")], today=now)
    assert "не покупался ни разу" in effects_text(stopped), effects_text(stopped)
    # Обратный случай тоже честно называется: стало чаще — это частота, а не упрёк.
    worse = advice_effects([advised(40, "Пиво Жигули")],
                           [purchase(80, "Пиво Жигули"), purchase(70, "Пиво Жигули"),
                            purchase(5, "Пиво Жигули"), purchase(4, "Пиво Жигули"),
                            purchase(3, "Пиво Жигули"), purchase(2, "Пиво Жигули")], today=now)
    assert worse["effects"][0]["change"] < -0.2 and "чаще" in effects_text(worse), worse
    assert advice_effects([], effect_history, today=now) is None
    assert advice_effects(effect_rows, [], today=now) is None, "без истории чеков мерить нечего"
    assert effects_text(None) == "" and effects_line(None) == ""
    # Короткая строка — второй формат одного замера: дайджест и панель берут её отсюда.
    line = effects_line(measured)
    assert line.startswith("📈 После советов:") and "реже" in line, line
    assert "раз в" in line and "«Сметана 20% 300г»" in line, line
    assert "не покупается" in effects_line(stopped), effects_line(stopped)
    assert "чаще" in effects_line(worse), effects_line(worse)
    assert effects_line({"effects": [], "pending": []}) == ""
    assert "Что было с этими товарами после совета" in waste_text(
        waste_summary(effect_rows, today=now), 90, effects=measured), "блок есть в отчёте"

    # 7д2. Миграция: старой базе колонки вердиктов дописываются, данные остаются на месте.
    legacy = os.path.join(os.path.dirname(digest_db), "test_legacy.db")
    if os.path.exists(legacy):
        os.remove(legacy)
    async with aiosqlite.connect(legacy) as db:
        await db.execute("CREATE TABLE receipt_items (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                         " transaction_id INTEGER, name TEXT, qty REAL, price REAL, sum REAL)")
        await db.execute("INSERT INTO receipt_items (transaction_id, name, qty, price, sum)"
                         " VALUES (1, 'Хлеб', 1, 50, 50)")
        await db.commit()
        await database._migrate(db)
        await db.commit()
        cursor = await db.execute("PRAGMA table_info(receipt_items)")
        columns = {row[1] for row in await cursor.fetchall()}
        assert {"verdict", "advice"} <= columns, columns
        cursor = await db.execute("SELECT name, verdict FROM receipt_items")
        assert await cursor.fetchall() == [("Хлеб", None)], "миграция не пересоздаёт таблицу"
    os.remove(legacy)
    print("✅ Необязательные покупки: вердикты сохраняются и складываются в отчёт")

    # 8. Экспорт CSV — теперь его делает панель управления (panel.py), а не бот
    import pandas as pd
    txs = await database.get_transactions(days=30)
    df = pd.DataFrame([dict(r) for r in txs])
    filename, content = await export.export_csv(df)
    assert filename.startswith("finance_export_") and "Сумма".encode("utf-8") in content
    print("✅ Экспорт CSV (используется панелью управления)")

    # 8.0 Прогноз продуктов: обычный недельный темп и предупреждение, когда он сломался.

    def food(amount, days_ago, category="еда", tx_type="expense", user=777):
        return {"user_id": user, "amount": amount, "category": category, "tx_type": tx_type,
                "created_at": (now - _days(days=days_ago)).isoformat(sep=" ")}

    # Три спокойные недели по ~4 000 ₽ и текущая на 6 500 ₽ — это уже повод предупредить.
    calm = [food(4000, 27), food(3900, 20), food(4100, 13), food(6500, 2)]
    stats = grocery_forecast(calm, today=now)
    assert stats and stats["usual"] == 4000 and stats["current"] == 6500, stats
    assert stats["over"] and not stats["under"] and stats["weeks_counted"] == 3, stats
    assert "больше твоей обычной недели" in forecast_text(stats), forecast_text(stats)
    assert "медиана 3 недели" in forecast_note(stats), forecast_note(stats)
    # Текущая неделя в пределах обычной — молчим, а не пугаем шумом.
    steady = grocery_forecast(calm[:3] + [food(4200, 1)], today=now)
    assert not steady["over"] and not steady["under"], steady
    # Заметно меньше обычного — тоже полезно знать.
    thrifty = grocery_forecast(calm[:3] + [food(1500, 2)], today=now)
    assert thrifty["under"] and "экономнее" in forecast_text(thrifty), thrifty
    # Пустые недели в медиану не идут: отпуск не должен занижать обычный темп.
    with_gap = [food(4000, 27), food(4000, 13), food(8000, 2)]
    gap = grocery_forecast(with_gap, today=now)
    assert gap["usual"] == 4000 and gap["weeks_counted"] == 2, gap
    # Истории меньше двух недель, доходы, чужие категории и даты в будущем — не прогноз.
    assert grocery_forecast([food(4000, 20), food(4000, 2)], today=now) is None
    rest = [food(4000, 20), food(4000, 13), food(4000, 6),
            food(9000, 2, category="транспорт"), food(9000, 2, tx_type="income"),
            food(9000, -3)]
    assert grocery_forecast(rest, today=now)["current"] == 4000, grocery_forecast(rest, today=now)
    assert grocery_forecast([], today=now) is None and forecast_text(None) == ""
    assert forecast_note(None) == ""

    # Недельный лимит на продукты: он абсолютный, поэтому не зависит от того, набралась ли
    # история для «обычного темпа», и живёт на каждого пользователя отдельно.
    assert weekly_spend(calm, today=now) == 6500          # только еда и только текущие 7 дней
    assert weekly_spend(rest, today=now) == 4000          # доходы и чужие категории не в счёт
    assert await weekly_food_limit(999) == 0
    await set_weekly_food_limit(999, 4000)
    assert await weekly_food_limit(999) == 4000
    assert await weekly_food_limit(998) == 0              # лимит личный
    await set_weekly_food_limit(999, 0)
    assert await weekly_food_limit(999) == 0              # 0 — лимит отключён
    assert limit_status(6500, 0) is None                  # без лимита проверять нечего
    assert not limit_status(6500, 10000)["near"], limit_status(6500, 10000)
    assert limit_status(9500, 10000)["near"] and not limit_status(9500, 10000)["over"]
    assert limit_status(10000, 10000)["over"]            # ровно лимит — уже превышение
    assert "почти выбрали" in limit_text(limit_status(9500, 10000))
    assert "превышен" in limit_text(limit_status(12000, 10000))
    assert "в рамках" in limit_text(limit_status(1000, 10000))
    assert limit_text(None) == ""
    # Строка для главного экрана: без лимита — только сумма, без трат и лимита — ничего.
    from services.forecast import food_line
    assert food_line(0, 0) == ""
    assert food_line(3200, 0) == "🍎 Продукты за неделю: 3 200 ₽", food_line(3200, 0)
    assert "ничего из 5 000 ₽" in food_line(0, 5000), food_line(0, 5000)
    assert "🍎" in food_line(2000, 5000) and "40%" in food_line(2000, 5000)
    assert "⚠️" in food_line(4600, 5000) and "92%" in food_line(4600, 5000)
    assert "перерасход 1 000 ₽" in food_line(6000, 5000), food_line(6000, 5000)

    # Лимит в момент траты: состояние читается из базы — теми же ключом и окном, что и везде.
    from services.forecast import food_week_line, food_week_status, weekly_food_spend
    week_user = 640
    async with aiosqlite.connect(digest_db) as db:
        for amount, day in ((900, 2), (500, 3), (7000, 12)):
            await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (week_user, amount, "еда", "Пятёрочка", "expense", "receipt",
                 (now - _days(days=day)).isoformat(sep=" ")))
        await db.commit()
    assert await weekly_food_spend(week_user, today=now) == 1400      # покупка 12-дневной давности не в счёт
    assert await food_week_status(week_user, today=now) is None        # нет лимита — нет и состояния
    assert await food_week_line(week_user, today=now) == "🍎 Продукты за неделю: 1 400 ₽"
    await set_weekly_food_limit(week_user, 2000)
    status = await food_week_status(week_user, today=now)
    assert status["current"] == 1400 and status["left"] == 600 and not status["over"], status
    assert "в рамках лимита" in limit_text(status), limit_text(status)
    await set_weekly_food_limit(week_user, 1200)
    assert limit_text(await food_week_status(week_user, today=now)).startswith("🚨"), \
        limit_text(await food_week_status(week_user, today=now))
    await set_weekly_food_limit(week_user, 0)
    assert await food_week_status(week_user, today=now) is None
    print("✅ Прогноз продуктов: обычный недельный темп и предупреждение")

    # 8.0б Личная инфляция: корзина своих товаров, пересчитанная по нынешним ценам.
    from services.inflation import inflation_text, personal_inflation

    def purchased(name, price, days_ago_list):
        return [{"name": name, "qty": 1, "price": price, "sum": price,
                 "created_at": (now - _days(days=days)).isoformat(sep=" "),
                 "description": "Пятёрочка"} for days in days_ago_list]

    basket = (purchased("Молоко Простоквашино 3,2% 930мл", 100, [150, 120])
              + purchased("Молоко Простоквашино 3,2% 930мл", 120, [10])
              + purchased("Хлеб Бородинский 400г", 50, [150, 120])
              + purchased("Хлеб Бородинский 400г", 50, [10])
              + purchased("Сыр Российский 200г", 300, [150, 120])
              + purchased("Сыр Российский 200г", 270, [10])
              + purchased("Чай Greenfield 100г", 200, [150, 120])
              + purchased("Чай Greenfield 100г", 220, [10]))
    inflation = personal_inflation(basket, today=now)
    # 200*1.2 + 100*1.0 + 600*0.9 + 400*1.1 = 1320 против 1300 прежних — это +1,5%.
    assert inflation["index"] == 0.0154, inflation
    assert inflation["count"] == 4, inflation
    assert inflation["basket_before"] == 1300 and inflation["basket_now"] == 1320, inflation
    assert inflation["products"][0]["name"].startswith("Сыр") is False, inflation["products"]
    text = inflation_text(inflation)
    assert "почти не изменились" in text and "+1.5" in text, text
    assert "Сыр Российский 200г: 300 ₽ → 270 ₽ (-10%)" in text, text
    assert "Молоко Простоквашино 3,2% 930мл: 100 ₽ → 120 ₽ (+20%)" in text, text
    # Вес решает: тот же рост у дорогого товара двигает индекс сильнее.
    heavy = (purchased("Сыр Российский 200г", 100, [150, 120]) + purchased("Сыр Российский 200г", 125, [10])
             + purchased("Хлеб Бородинский 400г", 100, [150, 120]) + purchased("Хлеб Бородинский 400г", 100, [10])
             + purchased("Чай Greenfield 100г", 100, [150, 120]) + purchased("Чай Greenfield 100г", 100, [10]))
    assert personal_inflation(heavy, today=now)["index"] == 0.0833, personal_inflation(heavy, today=now)
    # Падение цен в корзине имеет свою формулировку, а не «+−5%».
    cheaper_basket = (purchased("Сыр Российский 200г", 300, [150, 120]) + purchased("Сыр Российский 200г", 250, [10])
                      + purchased("Хлеб Бородинский 400г", 50, [150, 120]) + purchased("Хлеб Бородинский 400г", 45, [10])
                      + purchased("Чай Greenfield 100г", 200, [150, 120]) + purchased("Чай Greenfield 100г", 180, [10]))
    dropped = personal_inflation(cheaper_basket, today=now)
    assert dropped["index"] < -0.03 and "Личные цены упали" in inflation_text(dropped), \
        inflation_text(dropped)
    # Товар без прежних цен или без нынешней покупки в корзину не попадает.
    thin = (purchased("Хлеб Бородинский 400г", 50, [150]) + purchased("Хлеб Бородинский 400г", 60, [10])
            + purchased("Сыр Российский 200г", 300, [150, 120])
            + purchased("Чай Greenfield 100г", 200, [150, 120]) + purchased("Чай Greenfield 100г", 220, [10]))
    assert personal_inflation(thin, today=now) is None, personal_inflation(thin, today=now)
    assert personal_inflation([], today=now) is None
    assert "считать нечего" in inflation_text(None), inflation_text(None)
    print("✅ Личная инфляция: корзина своих товаров по нынешним ценам")

    # 8.1 Картинки-диаграммы для отчётов
    # Карточка товара рисует историю цены только когда есть что рисовать.
    from services import charts as charts_module
    priced = [(_card_now(2026, 8, 1), 95.0, "Пятёрочка"), (_card_now(2026, 8, 10), 89.0, "К&Б"),
              (_card_now(2026, 8, 20), 119.0, "Пятёрочка")]
    price_image = await charts_module.price_card(milk, priced, 95, 89, "К&Б")
    if charts_module.CHARTS_AVAILABLE:
        assert price_image and price_image.startswith(b"\x89PNG"), price_image
        assert price_image == await charts_module.price_card(milk, priced, 95, 89, "К&Б")
        flat_prices = [(moment, 100.0, "Пятёрочка") for moment, _, _ in priced]
        assert await charts_module.price_card(milk, flat_prices, 100, 100, "Пятёрочка")  # плоская цена
    assert await charts_module.price_card(milk, priced[:1], 95, 89, "К&Б") is None
    assert await charts_module.price_card(milk, [], 95, 89, "К&Б") is None
    assert charts.CHARTS_AVAILABLE, "matplotlib не установлен — диаграммы не рисуются"
    card = await charts.month_card({"еда": 12000, "транспорт": 4000}, 150000, 16000, 100000,
                                   40000, limits={"еда": 20000, "транспорт": 5000})
    assert card and card[:8] == b"\x89PNG\r\n\x1a\n", "месячная диаграмма не отрисовалась"
    period = await charts.period_card({"еда": 12000}, 12000, 7,
                                      charts.daily_series(txs, 7))
    assert period and period[:8] == b"\x89PNG\r\n\x1a\n", "диаграмма периода не отрисовалась"
    assert await charts.period_card({}, 0, 7) is None   # нет данных — молчим, а не падаем
    # Динамика необязательного: картинка строится по тому же тренду, что считает советник,
    # и по одной неделе её нет — по одной точке динамики не бывает.
    trend_image = await charts_module.waste_trend_card(trend)
    assert trend_image and trend_image.startswith(b"\x89PNG"), "картинка динамики не отрисовалась"
    assert trend_image == await charts_module.waste_trend_card(trend)  # отчёт не мигает цифрами
    flat_image = await charts_module.waste_trend_card(flat)            # «на одном уровне» рисуется
    assert flat_image and flat_image.startswith(b"\x89PNG"), "плоская динамика не отрисовалась"
    assert flat_image != trend_image, "движение и шум не должны давать одну и ту же картинку"
    assert await charts_module.waste_trend_card(None) is None
    assert await charts_module.waste_trend_card({"weeks": [], "delta": 0.0}) is None
    assert await charts_module.waste_trend_card({"weeks": trend["weeks"][:1], "delta": 0.0}) \
        is None, "по одной неделе картинку рисовать нельзя"
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

    # 8б. «Безопасно трать» знает про дату зарплаты и уже обещанные списания.
    # Горизонт — до зарплаты, а не «до конца месяца», и подписки со списанием до этого
    # дня из свободных денег вычитаются: они уже обещаны.
    import aiosqlite
    from config import DB_PATH as _db_path
    from datetime import timedelta as _delta
    from services import recurring as _recurring

    def _stamp(days_back: int) -> str:
        return (_dt.now() - _delta(days=days_back)).isoformat(sep=" ")

    async with aiosqlite.connect(_db_path) as db:
        for days_back in (88, 58, 28):      # зарплата раз в 30 дней — следующая через 2 дня
            await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (111, 150_000, "доход", "Зарплата", "income", "text", _stamp(days_back)))
        for days_back in (70, 40, 10):      # подписка 1 500 со списанием через 20 дней
            await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (111, 1_500, "досуг", "Netflix", "expense", "text", _stamp(days_back)))
        await db.commit()

    salary_line = await profile.safe_to_spend(111, 20_000)
    assert "до зарплаты" in salary_line, salary_line
    assert "подписки" not in salary_line, salary_line   # списание через 20 дней — не сейчас
    # Серия со списанием в день зарплаты: эти деньги уже обещаны, их нельзя показать свободными
    async with aiosqlite.connect(_db_path) as db:
        for days_back in (88, 58, 28):
            await db.execute(
                "INSERT INTO transactions "
                "(user_id, amount, category, description, tx_type, source, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (111, 1_500, "здоровье", "Спортзал", "expense", "text", _stamp(days_back)))
        await db.commit()
    promised_line = await profile.safe_to_spend(111, 20_000)
    assert "до зарплаты" in promised_line and "подписки" in promised_line, promised_line
    assert "1 500" in promised_line, promised_line
    # Когда свободные деньги заняты, подсказка честно предупреждает, а не делит минус
    assert "заняты" in await profile.safe_to_spend(111, 149_000), \
        await profile.safe_to_spend(111, 149_000)
    # Просроченное ожидание зарплаты горизонтом не становится: деньги могут не прийти
    salary_items = _recurring.find_recurring(
        [{"tx_type": "income", "amount": 100, "description": "Зарплата", "category": "доход",
          "created_at": _stamp(120)}, {"tx_type": "income", "amount": 100,
                                    "description": "Зарплата", "category": "доход",
                                    "created_at": _stamp(90)}, {"tx_type": "income", "amount": 100,
                                    "description": "Зарплата", "category": "доход",
                                    "created_at": _stamp(60)}], tx_type="income")
    assert salary_items and salary_items[0]["days_left"] < 0
    assert _recurring.next_income(salary_items) is None
    assert _recurring.total_before(salary_items, 5) == 0.0
    limits, total = budget.proposal_for_income(100_000, {"еда": 20_000, "транспорт": 5_000})
    assert total == 70_000 and sum(limits.values()) > 0, (limits, total)
    # История для ИИ-бюджета — только своя: чужие траты в контексте это и неверный
    # бюджет для пользователя, и утечка сумм второго пользователя в ответ.
    await database.add_transaction(111, 4321, "еда", None, "Свои траты", "expense")
    await database.add_transaction(222, 9876, "еда", None, "Чужие траты", "expense")
    own_context, own_days = await budget.history_summary(user_id=111, months=2)
    assert own_days >= 0 and "4321" in own_context, own_context
    assert "9876" not in own_context, own_context
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
    assert sched.get_job("weekly_digest") is not None   # дайджест — своё расписание
    if sched.running:
        sched.shutdown(wait=False)
    print("✅ Планировщик ежедневной сводки и недельного дайджеста")

    # 9.1а Итог закончившейся цели уходит в сводку один раз — и только после удачной отправки.
    from config import USERS as CONFIG_USERS
    from services import advice as advice_module

    class DigestBot:
        """Бот, который умеет отправлять сводку: одному из чатов отправка рвётся."""

        def __init__(self, failing):
            self.failing, self.sent = set(failing), []

        async def send_message(self, chat_id, text, **kwargs):
            if chat_id in self.failing:
                raise RuntimeError("сеть недоступна")
            self.sent.append((chat_id, text))

    goal_user = next(iter(CONFIG_USERS))
    await advice_module.set_goal(goal_user, {"key": "тест-цель", "name": "Тестовый товар",
                                            "target": 1, "baseline": 3, "usual": 100.0})
    goal_key = advice_module.GOAL_KEY.format(user_id=goal_user)
    record = _json.loads(await database.get_setting(goal_key, ""))
    record["started_at"] = (_now_cls.now() - _days(days=40)).isoformat(sep=" ")
    await database.set_setting(goal_key, _json.dumps(record))
    ended_goal = await advice_module.stored_goal(goal_user)
    assert advice_module.goal_progress(ended_goal, [], today=_now_cls.now())["finished"]

    failed_bot = DigestBot({goal_user})
    await scheduler.register_scheduler(
        failed_bot).get_job("weekly_digest").func()
    assert "announced_at" not in _json.loads(await database.get_setting(goal_key, "")), \
        "сорванная отправка не выдаёт итог за сказанный"
    assert failed_bot.sent, "остальным сводка всё равно ушла"

    ok_bot = DigestBot([])
    await scheduler.register_scheduler(ok_bot).get_job("weekly_digest").func()
    assert "announced_at" in _json.loads(await database.get_setting(goal_key, "")), \
        "после удачной отправки итог отмечен"
    body = next(text for chat_id, text in ok_bot.sent if chat_id == goal_user)
    assert "🎯 Цель (" in body, body
    # Итог идёт вместе со счётом по всем целям: за ту же отправку история уже пополнилась.
    assert "🏁 Сдержано" in body, body
    assert len(await advice_module.goal_history(goal_user)) == 1, \
        await advice_module.goal_history(goal_user)
    again_bot = DigestBot([])
    await scheduler.register_scheduler(again_bot).get_job("weekly_digest").func()
    next_body = next(text for chat_id, text in again_bot.sent if chat_id == goal_user)
    assert "🎯 Цель (" not in next_body, "итог не повторяется в следующей сводке"
    assert "🏁 Сдержано" not in next_body, "и счёт по целям тоже не повторяется"
    await advice_module.set_goal(goal_user, None)
    print("✅ Итог цели уходит в сводку один раз и только после отправки")

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
               expense_kb.get_items_edit_kb([{"name": "Сыр Российский 200г", "sum": 320.5}]),
               expense_kb.get_items_edit_kb([]), expense_kb.get_item_edit_kb(),
               expense_kb.get_items_edit_kb(
                   [{"name": f"Товар {i}", "sum": i * 10} for i in range(1, 21)], page=1),
               expense_kb.get_review_fix_kb(1, [{"id": 7, "name": "Пакет-майка", "sum": 7.0}]),
               expense_kb.get_duplicate_kb(),
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
               expense_kb.get_items_edit_kb([{"name": "Сыр Российский 200г", "sum": 320.5}]),
               expense_kb.get_items_edit_kb([]), expense_kb.get_item_edit_kb(),
               expense_kb.get_items_edit_kb(
                   [{"name": f"Товар {i}", "sum": i * 10} for i in range(1, 21)], page=1),
               expense_kb.get_duplicate_kb(),
               debt_kb.get_debt_detail_kb("sber"), report_kb.get_report_kb(), report_kb.get_period_kb(),
               # Кнопки списка «не брать», включая решения по догадкам модели: подтвердить
               # или сказать «это нормально» — у каждой должен быть обработчик.
               report_kb.get_bans_kb([{"key": "k1", "name": "Чипсы"}],
                                     [], []),
               report_kb.get_bans_kb([], [], [{"key": "k2", "name": "Сметана"}]),
               report_kb.get_bans_kb([], [], [], 2),
               report_kb.get_bans_kb([], [{"key": "k3", "name": "Мороженое"}], []),
               # Кнопки цели: взять шаг в разах или в деньгах, переключить единицу, убрать цель.
               report_kb.get_goal_kb(
                   [{"key": "k1", "name": "Чипсы", "unit": "count", "target": 2}],
                   has_goal=True, unit="count", can_switch=True),
               report_kb.get_goal_kb(
                   [{"key": "k2", "name": "Кофе", "unit": "sum", "limit": 300}],
                   unit="sum"),
               report_kb.get_goal_kb([], has_goal=True),
               report_kb.get_goal_kb([], has_goal=False, can_switch=True)):
        seen: set[str] = set()
        for row in kb.inline_keyboard:
            for button in row:
                data = button.callback_data
                # Две кнопки с одним действием — это забытая старая строка меню, а не выбор.
                assert data not in seen, f"кнопка-дубль в одной клавиатуре: {data}"
                seen.add(data)
                dynamic_prefix = data.rsplit("_", 1)[0] + "_"
                known = (data in sources
                         or f'{data.split(":")[0]}:' in sources
                         or f'startswith("{dynamic_prefix}")' in sources)
                assert known, f"нет обработчика для {data}"
    # Экран цели должен быть достижим из меню отчётов, а не только из дайджеста.
    report_buttons = {button.callback_data
                      for row in report_kb.get_report_kb().inline_keyboard for button in row}
    assert "report_goal" in report_buttons, report_buttons
    assert "report_bans" in report_buttons, report_buttons
    assert any(button.text == "🕘 История" for row in main_menu_kb.get_main_menu_kb().keyboard
               for button in row)
    assert any(button.callback_data == "menu_history"
               for row in main_menu_kb.get_main_menu_inline_kb().inline_keyboard
               for button in row)
    # Длинный чек листается: правка дожимает до последней позиции, а не только до восьмой
    paged = expense_kb.get_items_edit_kb(
        [{"name": f"Товар {i}", "sum": i * 10} for i in range(1, 21)], page=1).inline_keyboard
    labels = [button.text for row in paged for button in row]
    assert "✏️ 9. Товар 9" in labels and "✏️ 16. Товар 16" in labels, labels
    assert "✏️ 1. Товар 1" not in labels and "✏️ 17. Товар 17" not in labels, labels
    assert any(button.callback_data.startswith("items_page:") for row in paged for button in row)
    # Нечего поправлять — не появляется пустая клавиатура под разбором чека.
    assert expense_kb.get_review_fix_kb(1, []) is None
    # Длинный список спорных позиций листается: без этого позиции после шестой поправить нельзя.
    many = [{"id": index, "name": f"Товар {index}", "sum": index * 10.0} for index in range(1, 9)]
    first_page = expense_kb.get_review_fix_kb(5, many).inline_keyboard
    first_labels = [button.text for row in first_page for button in row]
    assert any(button.callback_data == "review_ok:5:1:0" for row in first_page for button in row), \
        first_labels
    assert not any(button.callback_data == "review_ok:5:7:0" for row in first_page for button in row)
    assert "1 / 2" in first_labels and "▶️" in first_labels, first_labels
    second_page = expense_kb.get_review_fix_kb(5, many, page=1).inline_keyboard
    second_labels = [button.text for row in second_page for button in row]
    assert any(button.callback_data == "review_ok:5:7:1" for row in second_page for button in row), \
        second_labels
    assert "2 / 2" in second_labels and "◀️" in second_labels, second_labels
    # Страница за пределами списка не роняет клавиатуру, а прижимается к последней.
    assert expense_kb.get_review_fix_kb(5, many, page=99)
    assert len(expense_kb.get_review_fix_kb(5, many, page=99).inline_keyboard) == 3
    print("✅ Все клавиатуры строятся и каждая кнопка имеет обработчик")

    print("\n🎉 Смоук-тест пройден полностью")
    if os.path.exists(_TEST_DB):
        os.remove(_TEST_DB)


if __name__ == "__main__":
    asyncio.run(main())
