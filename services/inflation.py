"""Личная инфляция: как изменились цены в твоей собственной корзине.

Росстат считает инфляцию по средней корзине, а человек покупает свою: молоко одной марки,
хлеб одного веса. Здесь то же самое делается по его чекам — те же товары, его вес, его цены.

Считается как индекс Ласпейреса, только без экономического жаргона: берётся корзина прошлого
периода и пересчитывается по нынешним ценам тех же товаров. Вес товара — сколько на него
потратили раньше, поэтому подорожание дорогого товара влияет сильнее, чем подорожание мелочи.

Никакой магии: сопоставление товаров, медианы цен и обычные цены берутся из
`services/purchase_history.py`, чтобы личная инфляция не разошлась с каталогом цен.
"""
from datetime import datetime, timedelta

from services.purchase_history import grouped_entries, parse_date, usual_price
from utils.formatting import format_amount, md_safe

WINDOW_DAYS = 90
MIN_PRODUCTS = 3    # меньше трёх товаров с историей — это не корзина, а случайные покупки
RISE_LIMIT = 3


def personal_inflation(history, today: datetime | None = None,
                       window_days: int = WINDOW_DAYS) -> dict | None:
    """Сравнивает прежние цены своих товаров с нынешними и считает личный индекс.

    Товар попадает в корзину, если до окна у него было минимум две покупки (иначе «прежняя
    цена» — это одна случайная цифра) и хотя бы одна внутри окна. Возвращает None, если
    истории мало: показать «инфляцию» по двум товарам хуже, чем промолчать.
    """
    now = today or datetime.now()
    cut = now - timedelta(days=window_days)
    items = []
    for group in grouped_entries(history):
        older, recent = [], []
        for entry in group["entries"]:
            moment = parse_date(entry["date"])
            if moment is None:
                continue
            (recent if moment >= cut else older).append(entry)
        if len(older) < 2 or not recent:
            continue
        old_price = usual_price(older)
        new_price = usual_price(recent)
        weight = round(sum(entry["sum"] for entry in older), 2)
        if not old_price or not new_price or weight <= 0:
            continue
        items.append({
            "name": group["last"],
            "old": old_price,
            "new": new_price,
            "ratio": round(new_price / old_price, 4),
            "weight": weight,
            "change": round(new_price - old_price, 2),
            "count": len(older) + len(recent),
        })
    if len(items) < MIN_PRODUCTS:
        return None
    weight_total = sum(item["weight"] for item in items)
    basket_before = round(weight_total, 2)
    basket_now = round(sum(item["weight"] * item["ratio"] for item in items), 2)
    return {
        "index": round(basket_now / basket_before - 1, 4),
        "basket_before": basket_before,
        "basket_now": basket_now,
        "window_days": window_days,
        "products": sorted(items, key=lambda item: -item["weight"] * (item["ratio"] - 1)),
        "count": len(items),
    }


def inflation_text(stats: dict | None) -> str:
    """Текст экрана: индекс, размер корзины, что подорожало и что подешевело."""
    if not stats:
        return ("📈 **Личная инфляция**\n\n"
                "Пока считать нечего: нужны товары, которые покупались минимум дважды до "
                "последних трёх месяцев и хотя бы раз — внутри них.\n"
                "Отправляй чеки, и через пару месяцев здесь появится динамика цен "
                "по твоей собственной корзине.")
    percent = stats["index"] * 100
    if percent >= 3:
        head = f"📈 **Личная инфляция: +{percent:.1f}%** за {stats['window_days']} дн."
    elif percent <= -3:
        head = f"📉 **Личные цены упали: {percent:.1f}%** за {stats['window_days']} дн."
    else:
        head = (f"📊 **Личные цены почти не изменились: {percent:+.1f}%** "
                f"за {stats['window_days']} дн.")

    difference = stats["basket_now"] - stats["basket_before"]
    lines = [head, "",
             f"Корзина: {stats['count']} товаров. По прежним ценам она стоила "
             f"{format_amount(stats['basket_before'])}",
             f"По нынешним — {format_amount(stats['basket_now'])} "
             f"({'+' if difference >= 0 else '−'}{format_amount(abs(difference))})."]

    risen = [item for item in stats["products"] if item["ratio"] > 1.005][:RISE_LIMIT]
    cheaper = [item for item in stats["products"] if item["ratio"] < 0.995]
    cheaper = sorted(cheaper, key=lambda item: item["ratio"])[:RISE_LIMIT]
    if risen:
        lines += ["", "**Подорожало сильнее всего:**"]
        for item in risen:
            percent_item = round((item["ratio"] - 1) * 100)
            lines.append(f"   • {md_safe(item['name'])}: {format_amount(item['old'])} → "
                         f"{format_amount(item['new'])} ({percent_item:+d}%)")
    if cheaper:
        lines += ["", "**Подешевело:**"]
        for item in cheaper:
            percent_item = round((item["ratio"] - 1) * 100)
            lines.append(f"   • {md_safe(item['name'])}: {format_amount(item['old'])} → "
                         f"{format_amount(item['new'])} ({percent_item:+d}%)")

    lines += ["",
              "Это цены из твоих чеков, а не официальная статистика. Вес товара — сколько "
              "ты тратил на него раньше, поэтому дорогие позиции влияют сильнее.",
              "Сравниваю одинаковые товары по названию: разные марки и упаковки не "
              "считаются одним товаром."]
    return "\n".join(lines)
