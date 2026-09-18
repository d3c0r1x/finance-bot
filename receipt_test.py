"""Тест разбора чеков: OCR → структура → позиции → разбор корзины.

Реальные чеки — локальные образцы: фото и эталонные суммы берутся из
`receipt_samples.json` (см. `samples.py` и `receipt_samples.example.json`), поэтому
в репозитории нет ничьих чеков. Синтетический продуктовый чек рисуется через PIL.

Запуск:  venv\\Scripts\\python.exe receipt_test.py [путь_к_фото_чека]
"""
import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ["FINANCE_DB"] = "test_receipts.db"
os.environ["USER_ID_1"] = "1111"

TEST_DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "test_receipts.db")

from samples import sample, sample_photos  # noqa: E402
GROCERY_LINES = [
    "ООО ПЯТЁРОЧКА",
    "г. Москва, ул. Тестовая, 4",
    "КАССОВЫЙ ЧЕК",
    "========================",
    "МОЛОКО 3.2% 1Л      89.90",
    "ХЛЕБ БОРОДИНСКИЙ     45.50",
    "СЫР РОССИЙСКИЙ 200Г 350.00",
    "ЧИПСЫ LAYS 120Г      149.90",
    "ПИВО БАЛТИКА 0.5Л   89.00",
    "ШОКОЛАД МИЛКА       120.00",
    "========================",
    "ИТОГ                844.30",
    "09.09.26 19:14",
]


def make_grocery_receipt(path: str) -> bool:
    """Рисует простой продуктовый чек в PNG (моноширинный шрифт с кириллицей)."""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return False

    from utils.fonts import mono_font
    font = mono_font(30, fallback=False)
    if font is None:
        return False  # без моноширинного шрифта чек рисовать нечем — тест пропускается

    width, line_height = 640, 42
    height = line_height * (len(GROCERY_LINES) + 4)
    image = Image.new("L", (width, height), color=255)
    draw = ImageDraw.Draw(image)
    for index, line in enumerate(GROCERY_LINES):
        draw.text((24, 40 + index * line_height), line, fill=0, font=font)
    image.save(path)
    return True


async def main():
    from ai.llm import analyze_basket, resolve_model
    from ai.receipts import apply_review_rules, basket_text, items_list_text, parse_receipt
    from ai.vision import resolve_vision_model
    from database.db import add_receipt_items, add_transaction, get_receipt_items, init_db

    await init_db()
    failures = []

    def check(label, condition, detail=""):
        if condition:
            print(f"✅ {label}")
        else:
            failures.append(f"{label}: {detail}")
            print(f"❌ {label}: {detail}")

    print(f"🧠 модель разбора: {await resolve_model()}")
    vision_model = await resolve_vision_model()
    print(f"👁 модель зрения: {vision_model or 'недоступна — чек читает Tesseract'}")

    def check_reader(receipt: dict, label: str):
        """Проверяет, что чек прочитан осознанно: либо моделью зрения, либо Tesseract.

        Кто точнее — решает арифметика, а не наличие модели: если Tesseract сошёлся с итогом,
        а модель зрения — нет, побеждает Tesseract, и карточка должна честно сказать, что
        читал он.
        """
        if not vision_model:
            print(f"⏭  {label}: модель зрения недоступна, проверка чтения пропущена")
            return
        if receipt.get("reader") != "vision":
            check(f"{label}: чтение выбрано по арифметике, позиции сходятся",
                  bool(receipt.get("reader")) and not receipt.get("items_mismatch"),
                  f"reader={receipt.get('reader')}, mismatch={receipt.get('items_mismatch')}")
            return
        check(f"{label} прочитан моделью зрения",
              "vision" in (receipt.get("parsed_by") or ""), f"parsed_by={receipt.get('parsed_by')}")

    def check_sample(label, entry, key, matches, detail=""):
        """Сверяет результат с эталоном из локального образца чека.

        Образца нет — печатаем пропуск, а не выдумываем ожидание: в репозитории
        личных чеков нет, они лежат только у владельца.
        """
        expected = (entry or {}).get(key)
        if expected is None:
            print(f"⏭  {label}: эталон «{key}» не задан — проверка пропущена")
            return
        check(label, matches(expected), detail)

    # ── Чек электроники ──────────────────────────────────────────────────
    photo_arg = sys.argv[1] if len(sys.argv) > 1 else None
    dns = sample("dns")
    photo = photo_arg or (sample_photos(dns)[0] if dns else "")
    if not photo or not os.path.isfile(photo):
        print("⏭  образец чека электроники не задан (receipt_samples.json) — проверка пропущена")
    else:
        entry = dns if dns and sample_photos(dns)[0] == photo else {}
        print("\n— Чек электроники —")
        receipt = await parse_receipt(photo)
        print(items_list_text(receipt["items"], receipt.get("store") or "", receipt))
        check_sample("сумма чека распознана", entry, "total",
                     lambda expected: bool(receipt["total"] and abs(receipt["total"] - expected) < 1),
                     f"total={receipt['total']}")
        check_sample("категория как в образце", entry, "category",
                     lambda expected: receipt["category"] == expected, f"category={receipt['category']}")
        check("магазин распознан", bool(receipt.get("store")), f"store={receipt.get('store')}")
        check_sample("позиций не меньше, чем в образце", entry, "min_items",
                     lambda expected: len(receipt["items"]) >= expected,
                     f"items={len(receipt['items'])}")
        check_sample("продуктовость чека как в образце", entry, "is_grocery",
                     lambda expected: receipt["is_grocery"] == expected,
                     f"is_grocery={receipt['is_grocery']}")
        check_reader(receipt, "чек электроники")
        print(f"ℹ️  чтение: {receipt.get('reader')} ({receipt.get('vision_model')}), "
              f"проходов {receipt.get('vision_passes')}, позиции "
              f"{receipt.get('items_total')} против итога {receipt.get('total')}")
        check("итог совпал с суммой позиций", not receipt["items_mismatch"],
              f"позиции {receipt.get('items_total')} против {receipt.get('total')}")
        if receipt["items"]:
            names = " ".join(item["name"].lower() for item in receipt["items"])
            check_sample("в позициях есть товары из образца", entry, "name_tokens",
                         lambda expected: all(token in names for token in expected), names[:120])

        # позиции сохраняются в БД и читаются обратно
        transaction_id = await add_transaction(1111, receipt["total"], receipt["category"],
                                               "чек", receipt.get("store") or "Чек", source="photo")
        await add_receipt_items(transaction_id, receipt["items"])
        stored = await get_receipt_items(transaction_id)
        check("позиции сохранились в базе", len(stored) == len(receipt["items"]),
              f"в базе {len(stored)}, в чеке {len(receipt['items'])}")

    # ── Реальный продуктовый чек (таблица с колонками) ───────────────────
    grocery = sample("grocery")
    if not grocery:
        print("\n⏭  образец продуктового чека с таблицей не задан — проверка пропущена")
    for photo in (sample_photos(grocery) if grocery else []):
        print("\n— Реальный продуктовый чек (колоночный разбор) —")
        receipt = await parse_receipt(photo)
        print(items_list_text(receipt["items"], receipt.get("store") or "", receipt))
        check_sample("итог чека прочитан точно", grocery, "total",
                     lambda expected: bool(receipt["total"] and abs(receipt["total"] - expected) < 1),
                     f"total={receipt['total']}")
        check_sample("позиций не меньше, чем в образце", grocery, "min_items",
                     lambda expected: len(receipt["items"]) >= expected,
                     f"items={len(receipt['items'])}")
        check_sample("сумма позиций близка к итогу", grocery, "sum_tolerance",
                     lambda tolerance: abs(sum(i["sum"] for i in receipt["items"])
                                          - receipt["total"]) < tolerance,
                     f"позиции {sum(i['sum'] for i in receipt['items']):.2f} против {receipt['total']}")
        check_sample("чек распознан как продуктовый", grocery, "is_grocery",
                     lambda expected: receipt["is_grocery"] == expected,
                     f"is_grocery={receipt['is_grocery']} category={receipt['category']}")
        check_reader(receipt, "продуктовый чек")
        # второе чтение включается только при расхождении, поэтому проходов 1 или 2
        check("видно, кто читал чек и сколько было проходов",
              bool(receipt.get("reader")) and receipt.get("vision_passes") in (1, 2),
              f"reader={receipt.get('reader')}, passes={receipt.get('vision_passes')}, "
              f"error={receipt.get('vision_error')!r}")
        print(f"ℹ️  чтение: {receipt.get('reader')} ({receipt.get('vision_model')}), "
              f"проходов {receipt.get('vision_passes')}, позиции "
              f"{receipt.get('items_total')} против итога {receipt.get('total')}, "
              f"расхождение={receipt.get('items_mismatch')}")
        # Имена от модели зрения читаемые: обрывков OCR вместо названий быть не должно
        names = " ".join(item["name"] for item in receipt["items"]).lower()
        check_sample("названия позиций читаемые, без обрывков OCR", grocery, "name_tokens",
                     lambda expected: all(token in names for token in expected), names[:160])
        check_sample("алкоголь отмечен как досуг", grocery, "leisure",
                     lambda expected: bool(receipt["leisure"]) == expected,
                     f"leisure={receipt['leisure']}")
        from ai.receipts import leisure_hint
        # либо ИИ сразу записал чек в досуг, либо в карточке есть кнопка/подсказка про досуг
        check("пиво не уехало в «еду»", receipt["category"] == "досуг" or bool(leisure_hint(receipt)),
              f"category={receipt['category']}, hint={leisure_hint(receipt)!r}")
        # пиво и снеки — досуг, а не «полезная покупка»; разбор идёт через те же страховки, что в боте
        advice = apply_review_rules(await analyze_basket(receipt["items"], receipt.get("store") or ""),
                                    receipt["items"])
        text = basket_text(advice, receipt["items"], receipt.get("store") or "", receipt.get("total"))
        print(text)
        verdicts = (advice or {}).get("items", {})
        junk = [entry["verdict"] for index, entry in verdicts.items()
                if any(token in receipt["items"][index - 1]["name"].lower()
                       for token in (grocery.get("junk_tokens") or ()))]
        check_sample("необязательное не названо полезным", grocery, "junk_tokens",
                     lambda expected: bool(junk) and all(value in ("вредно", "лишнее")
                                                         for value in junk),
                     f"вердикты: {junk}")
        # подробный разбор вместо советов-затычек
        from ai.llm import is_advice_filler
        notes = [(entry.get("reason") or "") + (entry.get("note") or "") for entry in verdicts.values()]
        check("нет советов-затычек («заменить», «полезно»)",
              all(not value or not is_advice_filler(value) for value in notes), f"{notes}")
        # большую часть покупок должны объяснить, а не промолчать
        described = sum(1 for value in notes if len(value) > 6)
        check("есть причина и совет у большинства позиций",
              described >= len(receipt["items"]) * 0.5,
              f"расписано {described} из {len(receipt['items'])}")
        check("в разборе есть план на следующую закупку",
              bool((advice or {}).get("plan")), f"plan={(advice or {}).get('plan')}")
        junk_leisure = [receipt["items"][index - 1]["name"] for index, entry in verdicts.items()
                        if entry["verdict"] == "лишнее"
                        and any(word in receipt["items"][index - 1]["name"].lower()
                                for word in ("мак", "круп", "рис", "консерв", "специ"))]
        check("еда не попала в «лишнее»", not junk_leisure, f"{junk_leisure}")
        check("в разборе есть суммы по группам и итог по чеку",
              "% чека" in text and "Необязательные траты" in text, text[:200])

    # ── Продуктовый чек ──────────────────────────────────────────────────
    print("\n— Продуктовый чек —")
    grocery_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "test_grocery.png")
    if not make_grocery_receipt(grocery_path):
        print("⏭  не удалось нарисовать тестовый чек (нет шрифта)")
    else:
        receipt = await parse_receipt(grocery_path)
        print(items_list_text(receipt["items"], receipt.get("store") or "", receipt))
        check("магазин — продуктовый", receipt["is_grocery"], f"is_grocery={receipt['is_grocery']}")
        # пиво, чипсы и шоколад — это досуг: если их заметная часть чека, категория уходит в «досуг»
        check("категория «еда» или «досуг» (в чеке пиво и чипсы)",
              receipt["category"] in ("еда", "досуг"), f"category={receipt['category']}")
        check("сумма близка к 844", receipt["total"] and abs(receipt["total"] - 844.3) < 60,
              f"total={receipt['total']}")
        check("позиции продуктов есть", len(receipt["items"]) >= 4, f"items={len(receipt['items'])}")

        if receipt["items"]:
            advice = apply_review_rules(await analyze_basket(receipt["items"], receipt.get("store") or ""),
                                        receipt["items"])
            # Без текстовой модели разбор корзины проверить нечем — проверки переходят
            # на разбор отзывов в handlers_test.py, а здесь остаётся только чтение чека
            if advice is None:
                print("⏭  текстовая модель недоступна: разбор корзины пропущен")
            else:
                from database.db import get_receipt_price_history
                from services.purchase_history import compare_items, history_text
                advice["history_changes"] = compare_items(receipt["items"],
                                                          await get_receipt_price_history(1111))
                text = basket_text(advice, receipt["items"], receipt.get("store") or "",
                                   receipt.get("total"))
                text += history_text(advice["history_changes"])
                print("\n" + text)
                check("ИИ разобрал корзину", len(text) > 60, f"text={text[:80]!r}")
                verdicts = advice.get("items", {})
                beer = [value["verdict"] for index, value in verdicts.items()
                        if "пив" in receipt["items"][index - 1]["name"].lower()]
                check("пиво отмечено как вредное",
                      bool(beer) and all(v in ("вредно", "лишнее") for v in beer),
                      f"вердикты по пиву: {beer}")
                check("оценка дана каждой позиции",
                      len(verdicts) == len(receipt["items"]),
                      f"вердиктов {len(verdicts)} на {len(receipt['items'])} позиций")
                checks = [len(entry.get("reason") or "") + len(entry.get("note") or "")
                          for entry in verdicts.values()]
                check("у большинства позиций есть причина и совет",
                      sum(1 for size in checks if size > 6) >= len(checks) * 0.6,
                      f"расписанных {sum(1 for size in checks if size > 6)} из {len(checks)}")
        os.remove(grocery_path)

    # ── Узкая таблица «кол-во × цена = итого» ────────────────────────────
    # На таком чеке колонка «итого» уезжала в соседнюю строку, в позиции попадали
    # шапка и реквизиты, а позиция с непрочитанной ценой исчезала совсем.
    narrow = sample("narrow")
    if not narrow:
        print("\n⏭  образец узкого чека «кол-во × цена = итого» не задан — проверка пропущена")
    for photo in (sample_photos(narrow) if narrow else []):
        print("\n— Узкая таблица «кол-во × цена = итого» —")
        receipt = await parse_receipt(photo)
        print(items_list_text(receipt["items"], receipt.get("store") or "", receipt))
        names = " ".join(item["name"].lower() for item in receipt["items"])
        sums = sorted(round(item["sum"], 2) for item in receipt["items"])
        check_sample("итог чека прочитан", narrow, "total",
                     lambda expected: bool(receipt["total"] and abs(receipt["total"] - expected) < 0.01),
                     f"total={receipt['total']}")
        check_sample("позиций столько же, сколько в образце", narrow, "item_count",
                     lambda expected: len(receipt["items"]) == expected,
                     f"items={[item['name'] for item in receipt['items']]}")
        check_sample("суммы позиций как в чеке", narrow, "item_sums",
                     lambda expected: sums == expected, f"sums={sums}")
        check("позиции сходятся с итогом", not receipt["items_mismatch"],
              f"items_total={receipt.get('items_total')} total={receipt['total']}")
        check("шапка и реквизиты не попали в позиции",
              not any(marker in names for marker in ("ценник", "зачислится", "кассир",
                                                     "итог", "наличн", "скидка")), names)
        check_sample("товары узнаются по названию", narrow, "name_tokens",
                     lambda expected: all(marker in names for marker in expected), names)
        check_sample("количество посчитано как «кол-во × цена»", narrow, "qty_check",
                     lambda expected: any(abs(item["sum"] - expected["sum"]) < 0.01
                                          and item.get("qty") == expected["qty"]
                                          for item in receipt["items"]),
                     f"{[(item['name'], item.get('qty'), item['sum']) for item in receipt['items']]}")
        check_sample("магазин распознан", narrow, "store",
                     lambda expected: receipt.get("store") == expected,
                     f"store={receipt.get('store')!r}")

    if os.path.exists(TEST_DB):
        os.remove(TEST_DB)
    if failures:
        print("\n❌ Проблемы:")
        for failure in failures:
            print("   -", failure)
        raise SystemExit(1)
    print("\n🎉 Разбор чеков работает")


if __name__ == "__main__":
    asyncio.run(main())
