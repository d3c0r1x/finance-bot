"""Необязательные покупки: что советовали не брать и сколько на это ушло.

Цифры строятся по сохранённым вердиктам разбора корзины, а не пересчитываются заново: совет —
это то, что человек видел в сообщении, и отчёт должен с ним сходиться. Поэтому здесь нет ни
модели, ни повторного разбора — только позиции чеков с уже проставленным вердиктом
(`receipt_items.verdict`).

Считать «сэкономленное» здесь нельзя: бот не знает, купил человек этот товар снова или нет.
Что он знает точно — сколько потрачено на то, что разбор назвал необязательным. Поэтому и
`saving_forecast` — это потолок прошлого темпа (сколько уходило бы в месяц), а не достигнутая
экономия.
"""
import json
from datetime import datetime, timedelta

from database.db import get_setting, set_setting
from services import mutelist
from services.purchase_history import grouped_entries, parse_date, product_key, same_product
from utils.formatting import format_amount, md_safe, plural_ru

# Вердикты, которые разбор считает необязательными, и как их называть в отчёте.
WASTE_VERDICTS = (("вредно", "❌ Лучше сократить"), ("лишнее", "🗑 Можно было не брать"))
DEFAULT_DAYS = 90
TOP_ITEMS = 5
TOP_REPEATS = 3
TOP_EFFECTS = 4
# Сколько дней должно пройти после первого совета, чтобы говорить о частоте: сразу после
# совета любое «стало реже» — это ещё шум, а не эффект.
MIN_EFFECT_DAYS = 21
# Сколько покупок товара нужно до совета: по одной покупке базы для сравнения нет.
MIN_BEFORE_PURCHASES = 2
# С какой разницы темпов называть движение: меньше — это «так же часто», а не тренд.
EFFECT_MOVED = 0.2
# Сколько недель показывать в динамике и с какой разницы долей начинать говорить о движении.
TREND_WEEKS = 4
TREND_NOISE = 0.05
# Сколько раз товар должен попасть в необязательное, чтобы попасть в личный список «не брать»:
# один разбор — это случайность (прочиталось или съелось один раз), два — уже привычка.
MIN_BANS = 2
# Окно прогноза пересчитывается на месяц по 30 дням: так «в месяц» читается одинаково
# и не зависит от длины календарного месяца.
DAYS_IN_MONTH = 30
# Кто поставил вердикт: правило — проверка по названию, одинаковая для одного и того же
# товара; модель — её чтение кассовой строки; «unknown» — старый разбор без пометки.
SOURCE_TITLES = {"rule": "проверка по правилам", "model": "оценка модели",
                 "unknown": "разборы без пометки"}
# Какие вердикты имеет смысл пересчитывать нынешними правилами: поставленные моделью или
# оставшиеся без пометки. Подтверждённое правилом пересчитывать нечего — оно уже проверено.
RECALC_SOURCES = ("", "model", "default")
# Ключ настроек: товары, которые человек разрешил, несмотря на прошлые вердикты.
ALLOWED_KEY = "advice:allowed:{user_id}"
# Ключ настроек: товары, которые человек сам подтвердил как «не брать». Нужен там, где
# единственный источник вердикта — догадка модели: без подтверждения бот её не прячет.
CONFIRMED_KEY = "advice:confirmed:{user_id}"
# Ключ настроек: когда и на сколько человек последний раз пересчитывал сохранённые разборы.
# Нужен, чтобы потом отличить движение доли от правки правил от движения от покупок.
RECALC_KEY = "advice:recalc:{user_id}"
# Цели на месяц живут в собственном модуле (`services/goals.py`): константы, расчёт,
# тексты и история цели — один владелец. Ниже — реэкспорт, чтобы прежние импорты
# `from services.advice import …` продолжали работать без правки каждого вызова.


def positions(rows, cut: datetime | None = None) -> list[dict]:
    """Позиции с вердиктом, попадающие в окно: одно чтение строк на все отчёты об необязательном."""
    items = []
    for row in rows or []:
        moment = parse_date(row.get("created_at"))
        if moment is None or (cut is not None and moment < cut):
            continue
        items.append({
            "name": row.get("name") or "Позиция",
            "sum": round(float(row.get("sum") or 0), 2),
            "verdict": (row.get("verdict") or "").strip().lower(),
            "advice": row.get("advice") or "",
            # Кто поставил вердикт. У старых разборов пометки нет, и она остаётся пустой:
            # назвать их правилом задним числом значило бы приписать боту то, чего он не делал.
            "source": (row.get("verdict_source") or "").strip().lower(),
            "moment": moment,
        })
    return items


def waste_summary(rows, days: int = DEFAULT_DAYS, today: datetime | None = None,
                  allowed: set[str] | None = None) -> dict | None:
    """Сколько за период ушло на «вредное» и «лишнее» по сохранённым вердиктам.

    Возвращает None, если разобранных чеков за период нет: показать «ничего не нашёл» там,
    где бот просто ещё не разбирал покупки, значило бы соврать про экономию.

    Товары, которые человек поправил (`allowed`), из необязательного убраны: это его решение,
    а не вывод разбора, и упрекать ими в отчёте нельзя. Сколько именно убрано, показывает
    `corrected_text` — правка видна, а не выглядит пропажей.
    """
    now = today or datetime.now()
    allowed = allowed or set()
    items = positions(rows, cut=now - timedelta(days=days))
    if not items:
        return None
    total = round(sum(item["sum"] for item in items), 2)
    waste = [item for item in items if item["verdict"] in dict(WASTE_VERDICTS)
             and product_key(item["name"]) not in allowed]
    waste_sum = round(sum(item["sum"] for item in waste), 2)
    # Из чего сложился необязательный: проверка по названию или оценка модели.
    by_source: dict[str, float] = {}
    for item in waste:
        source = item["source"] if item["source"] in SOURCE_TITLES else "unknown"
        by_source[source] = round(by_source.get(source, 0) + item["sum"], 2)
    by_verdict = [{"verdict": verdict, "title": title,
                   "sum": round(sum(item["sum"] for item in waste if item["verdict"] == verdict), 2),
                   "count": len([item for item in waste if item["verdict"] == verdict])}
                  for verdict, title in WASTE_VERDICTS]
    return {
        "days": days,
        "total": total,
        "waste": waste_sum,
        "share": round(waste_sum / total, 3) if total else 0,
        "count": len(items),
        "waste_count": len(waste),
        "by_verdict": [item for item in by_verdict if item["count"]],
        "items": sorted(waste, key=lambda item: -item["sum"])[:TOP_ITEMS],
        "repeats": repeated_waste(waste),
        "by_source": by_source,
    }


def repeated_waste(waste: list[dict]) -> list[dict]:
    """Что попадало в «необязательное» больше одного раза: это привычка, а не случай.

    Товары сопоставляются той же функцией, что и в каталоге цен (`same_product`), поэтому
    «Сметана» из одного чека и «СМЕТАНА 20% 300Г» из другого считаются одним товаром.
    """
    groups: list[dict] = []
    for item in waste:
        for group in groups:
            if same_product(group["name"], item["name"]):
                group["count"] += 1
                group["sum"] = round(group["sum"] + item["sum"], 2)
                break
        else:
            groups.append({"name": item["name"], "count": 1, "sum": item["sum"]})
    repeated = [group for group in groups if group["count"] > 1]
    return sorted(repeated, key=lambda group: (-group["count"], -group["sum"]))[:TOP_REPEATS]


def repeat_warnings(items, history, allowed: set[str] | None = None) -> list[dict]:
    """Товары текущего чека, которые раньше уже попадали в «вредно» или «лишнее».

    Сопоставление — то же, что в каталоге цен (`same_product`): «Чипсы Lays 120г» и
    «ЧИПСЫ LAYS 120Г» — один товар. Возвращается по одному предупреждению на позицию, с числом
    прошлых случаев и суммой последнего из них — этого хватает, чтобы напоминание было
    конкретным, а не «ты опять покупаешь вредное".

    Разрешённые человеком товары не напоминаются: он уже сказал, что разбор здесь неправ,
    и возвращать ему этот же совет на каждой следующей покупке было бы спором, а не помощью.
    """
    if not items or not history:
        return []
    allowed = allowed or set()
    waste_history = [row for row in history
                     if (row.get("verdict") or "").strip().lower() in dict(WASTE_VERDICTS)
                     and product_key(row.get("name") or "") not in allowed]
    warnings = []
    for item in items:
        name = item.get("name") or ""
        if product_key(name) in allowed:
            continue
        matched = [row for row in waste_history
                   if same_product(name, row.get("name") or "")]
        if not matched:
            continue
        last = max(matched, key=lambda row: str(row.get("created_at") or ""))
        verdict = (last.get("verdict") or "").strip().lower()
        warnings.append({
            "name": name,
            "verdict": verdict,
            "title": dict(WASTE_VERDICTS).get(verdict, ""),
            "count": len(matched),
            "last_sum": round(float(last.get("sum") or 0), 2),
            "advice": last.get("advice") or "",
        })
    warnings.sort(key=lambda item: (-item["count"], -item["last_sum"]))
    return warnings


def repeat_text(warnings: list[dict]) -> str:
    """Напоминание в момент покупки: по этим товарам уже был совет не брать."""
    if not warnings:
        return ""
    lines = ["⏪ **Уже было:** по этим позициям разбор уже советовал иначе:"]
    for item in warnings:
        times = plural_ru(item["count"], "раз", "раза", "раз")
        line = (f"   • {md_safe(item['name'])} — {item['count']} {times}, "
                f"последний раз {format_amount(item['last_sum'])}")
        if item["advice"]:
            line += f" (совет был: {md_safe(item['advice'])})"
        lines.append(line)
    lines.append("Это не запрет, а напоминание — решение по-прежнему твоё.")
    return "\n".join(lines)


def waste_groups(items: list[dict], allowed: set[str] | None = None,
                 min_bans: int = MIN_BANS) -> list[dict]:
    """Группировка необязательных позиций по товару: сколько раз, на сколько, с каким советом.

    Ключ товара — тот же, что в списке покупок и каталоге цен (`product_key`), поэтому
    «не брать» и «пора купить» всегда говорят про один и тот же товар, а не про два похожих
    названия. Случайность от привычки отделяется числом разборов: одного раза мало.
    Одна точка на список «не брать» и на прогноз экономии: порог считается здесь.
    """
    allowed = allowed or set()
    groups: list[dict] = []
    for item in items or []:
        verdict = (item.get("verdict") or "").strip().lower()
        if verdict not in dict(WASTE_VERDICTS):
            continue
        name = item.get("name") or ""
        key = product_key(name)
        if not key or key in allowed:
            continue
        entry = next((group for group in groups if group["key"] == key), None)
        if entry is None:
            entry = {"key": key, "name": name, "count": 0, "sum": 0.0,
                     "last_sum": 0.0, "verdict": verdict,
                     "title": dict(WASTE_VERDICTS)[verdict], "advice": "",
                     "last_moment": None,
                     # Кто говорил про этот товар: правило, модель или старый разбор без пометки.
                     "rules": 0, "models": 0, "unmarked": 0}
            groups.append(entry)
        entry["count"] += 1
        source = item.get("source") or "unmarked"
        entry[{"rule": "rules", "model": "models"}.get(source, "unmarked")] += 1
        entry["sum"] = round(entry["sum"] + float(item.get("sum") or 0), 2)
        moment = item.get("moment")
        if entry["last_moment"] is None or (moment and moment >= entry["last_moment"]):
            entry["last_moment"] = moment
            entry["last_sum"] = round(float(item.get("sum") or 0), 2)
            entry["name"] = name
            entry["verdict"] = verdict
            entry["title"] = dict(WASTE_VERDICTS)[verdict]
            entry["advice"] = item.get("advice") or ""
    found = [entry for entry in groups if entry["count"] >= min_bans]
    for entry in found:
        # Догадка — это когда про товар говорила только модель: правило такого не говорило,
        # а у старых разборов пометки нет вообще, и догадкой они не считаются: неизвестный
        # источник — не то же самое, что «почти наверняка её чтение».
        entry["guess"] = entry["models"] == entry["count"]
    return sorted(found, key=lambda entry: (-entry["count"], -entry["sum"]))


def banned(rows, allowed: set[str] | None = None, min_bans: int = MIN_BANS,
           confirmed: set[str] | None = None) -> list[dict]:
    """Личный список «не брать» по всей истории разборов — не только за последнее окно.

    Окна здесь нет намеренно: в «не брать» товар попадает по всей истории, иначе старые
    разборы перестали бы защищать от повторных советов. Прогноз экономии работу с окном
    держит у себя (`saving_forecast`), а группировка у этих двух экранов одна.

    Товар, про который говорила только модель, в список не попадает, пока человек не
    подтвердил: `confirmed` — его решение спрятать товар из списка покупок. Иначе догадка
    модели молча решала бы за человека — а именно на оценках модели бот и ошибается.
    """
    stored = confirmed or set()
    return [entry for entry in waste_groups(positions(rows), allowed, min_bans)
            if not entry["guess"] or entry["key"] in stored]


def guesses(rows, allowed: set[str] | None = None, min_bans: int = MIN_BANS,
            confirmed: set[str] | None = None) -> list[dict]:
    """Товары, которые только модель называла необязательными и человек их не подтверждал.

    Отдельный список нужен, чтобы догадка не исчезла молча: она не прячет товар из списка
    покупок, но и не забывается — человек решает, «не брать» это или «всё нормально».
    """
    stored = confirmed or set()
    return [entry for entry in waste_groups(positions(rows), allowed, min_bans)
            if entry["guess"] and entry["key"] not in stored]


def share_phrase(part: float, base: float) -> str | None:
    """Доля в процентах от базы: «12%» или «меньше 1%».

    Ноль процентов не показывается нулём: «0% лимита» читалось бы как «ничего не значит»,
    хотя речь о маленькой, но существующей сумме.
    """
    if not base or base <= 0 or not part or part <= 0:
        return None
    share = part / base * 100
    return "меньше 1%" if share < 1 else f"{round(share)}%"


def saving_forecast(rows, allowed: set[str] | None = None, days: int = DEFAULT_DAYS,
                    today: datetime | None = None, income: float = 0,
                    limit: float = 0) -> dict | None:
    """Потолок экономии в месяц по привычно необязательным товарам.

    Темп считается по окну и пересчитывается на месяц — не «цена × 30», иначе редкая покупка
    превратилась бы в ежедневную. В расчёт идут только товары, которые разбор называл
    необязательными дважды и больше (`MIN_BANS`): по одной случайной покупке прогноз был бы
    выдумкой. Явно разрешённые человеком товары не считаются — он уже сказал, что возьмёт их.

    Доход и месячный лимит приходят от вызывающего: их знают бюджет и профиль, а советник
    чека нет. Здесь они только переводятся в доли — чтобы бот, дайджест и панель говорили
    об одном масштабе одними словами, а не считали проценты каждый по-своему.
    """
    now = today or datetime.now()
    items = positions(rows, cut=now - timedelta(days=days))
    groups = waste_groups(items, allowed)
    if not groups:
        return None
    found = [{**group, "monthly": round(group["sum"] / days * DAYS_IN_MONTH, 2)}
             for group in groups]
    monthly = round(sum(item["monthly"] for item in found), 2)
    if monthly <= 0:
        return None
    return {"monthly": monthly, "days": days, "count": len(found),
            "items": sorted(found, key=lambda item: -item["monthly"])[:TOP_ITEMS],
            "share_limit": share_phrase(monthly, limit),
            "share_income": share_phrase(monthly, income)}


def saving_scale_line(forecast: dict | None) -> str:
    """Масштаб потолка: какая это доля месячного лимита и дохода.

    Одна строка на бота и панель: проценты считает `saving_forecast`, а здесь только текст.
    Если ни лимита, ни дохода нет — строки нет вовсе, а не «0%»: мерить не в чем.
    """
    if not forecast:
        return ""
    parts = []
    if forecast.get("share_limit"):
        parts.append(f"{forecast['share_limit']} месячного лимита")
    if forecast.get("share_income"):
        parts.append(f"{forecast['share_income']} дохода")
    if not parts:
        return ""
    return f"Для масштаба: это {' и '.join(parts)}."


def saving_text(forecast: dict | None) -> str:
    """Строка «сколько освободилось бы в месяц» — с прямым предупреждением, что это потолок."""
    if not forecast:
        return ""
    lines = [f"🧮 **Это в месяц: ~{format_amount(forecast['monthly'])}** — если больше не брать "
             "привычное из списка ниже:"]
    for item in forecast["items"]:
        times = plural_ru(item["count"], "раз", "раза", "раз")
        lines.append(f"   • {md_safe(item['name'])} — ~{format_amount(item['monthly'])} "
                     f"({item['count']} {times} за {forecast['days']} дн.)")
    scale = saving_scale_line(forecast)
    if scale:
        lines.append(scale)
    lines.append("Это потолок по твоим чекам, а не обещание экономии: часть этих денег уже "
                 "ушла, и брать ли их снова — решение твоё.")
    return "\n".join(lines)


async def allowed_keys(user_id: int) -> set[str]:
    """Товары, разрешённые несмотря на вердикты: человек решил брать их всё равно.

    Набор хранится отдельно от блокировки и разбирается тем же кодом, что и отключённые
    напоминания (`services/mutelist.py::parse`) — один формат на все «не трогай мои решения».
    """
    return mutelist.parse(await get_setting(ALLOWED_KEY.format(user_id=user_id), ""))


async def set_allowed(user_id: int, key: str, allowed: bool) -> None:
    """Разрешает товар или возвращает его в список «не брать»."""
    keys = await allowed_keys(user_id)
    keys.add(key) if allowed else keys.discard(key)
    await set_setting(ALLOWED_KEY.format(user_id=user_id),
                      json.dumps(sorted(keys), ensure_ascii=False))


async def confirmed_keys(user_id: int) -> set[str]:
    """Товары, которые человек подтвердил как «не брать» — в дополнение к вердиктам правил.

    Формат хранения тот же, что у остальных «не трогай мои решения» (`services/mutelist.py`).
    """
    return mutelist.parse(await get_setting(CONFIRMED_KEY.format(user_id=user_id), ""))


async def set_confirmed(user_id: int, key: str, confirmed: bool) -> None:
    """Подтверждает догадку модели или снимает подтверждение."""
    keys = await confirmed_keys(user_id)
    keys.add(key) if confirmed else keys.discard(key)
    await set_setting(CONFIRMED_KEY.format(user_id=user_id),
                      json.dumps(sorted(keys), ensure_ascii=False))


async def blocked_keys(user_id: int, rows=None) -> set[str]:
    """Ключи товаров из личного списка «не брать» — так их видит список покупок.

    Вердикты читаются здесь же, чтобы ни один экран не собирал этот список по-своему
    и «не брать» в боте и в панели не расходились.
    """
    if rows is None:
        from database.db import get_receipt_verdicts

        rows = [dict(row) for row in await get_receipt_verdicts(user_id)]
    return {entry["key"] for entry in banned(rows, await allowed_keys(user_id),
                                             confirmed=await confirmed_keys(user_id))}



def recalculate_old_verdicts(rows, allowed: set[str] | None = None,
                            confirmed: set[str] | None = None) -> dict:
    """Что нынешние правила говорят про сохранённые вердикты — без записи в базу.

    Пересчитываются только те позиции, чей вердикт поставила модель или которые остались
    без пометки: у позиций, подтверждённых правилом, ничего не изменится. Возвращается план
    правок и то, что из-за них уходит или приходит в «не брать», — считает это та же `banned`,
    а не второй расчёт порога и источников. Функция ничего не записывает: историю чеков
    меняет человек, нажав кнопку, а не бот при каждом открытии экрана.
    """
    from ai.receipts import recalc_verdict

    updated, changed, after = [], [], []
    for row in rows or []:
        verdict = (row.get("verdict") or "").strip().lower()
        advice = row.get("advice") or ""
        source = (row.get("verdict_source") or "").strip().lower()
        fixed = (recalc_verdict(row.get("name") or "", verdict, advice)
                 if source in RECALC_SOURCES and row.get("item_id") is not None else None)
        if not fixed or fixed["source"] != "rule":
            after.append(row)
            continue
        after.append({**row, "verdict": fixed["verdict"], "advice": fixed["advice"],
                      "verdict_source": "rule"})
        updated.append({"item_id": row["item_id"], "name": row.get("name") or "",
                        "sum": round(float(row.get("sum") or 0), 2),
                        "old_verdict": verdict, "verdict": fixed["verdict"],
                        "advice": fixed["advice"],
                        "title": dict(WASTE_VERDICTS).get(verdict, "нейтрально")})
        if fixed["verdict"] != verdict or fixed["advice"] != advice:
            changed.append(updated[-1])

    with_allowed = allowed or set()
    stored = confirmed or set()
    before_bans = banned(rows, with_allowed, confirmed=stored)
    after_bans = banned(after, with_allowed, confirmed=stored)
    after_keys = {entry["key"] for entry in after_bans}
    before_keys = {entry["key"] for entry in before_bans}
    # Насколько пересчёт сдвинул саму сумму необязательного за окно отчёта: эти числа
    # сохраняются и потом ставятся оговоркой к динамике — движение могло прийти не от покупок.
    before_waste = waste_summary(rows, allowed=with_allowed) or {"waste": 0.0}
    after_waste = waste_summary(after, allowed=with_allowed) or {"waste": 0.0}
    return {"checked": len(rows or []), "updated": updated, "changed": changed,
            "leave": [entry for entry in before_bans if entry["key"] not in after_keys],
            "enter": [entry for entry in after_bans if entry["key"] not in before_keys],
            "waste_before": round(before_waste["waste"], 2),
            "waste_after": round(after_waste["waste"], 2)}


def parse_recalc(raw: str | None) -> dict | None:
    """Разбор сохранённой записи о пересчёте — один парсер на бота и панель."""
    if not raw:
        return None
    try:
        record = json.loads(raw)
    except (TypeError, ValueError):
        return None
    if not isinstance(record, dict):
        return None
    moment = parse_date(record.get("at"))
    if moment is None:
        return None
    return {**record, "moment": moment}


async def last_recalc(user_id: int) -> dict | None:
    """Когда и насколько человек пересчитывал разборы — последняя запись, если она есть."""
    return parse_recalc(await get_setting(RECALC_KEY.format(user_id=user_id), ""))


async def set_recalc_record(user_id: int, result: dict) -> None:
    """Запоминает факт пересчёта: дату и сдвиг суммы необязательного."""
    record = {"at": datetime.now().isoformat(sep=" ", timespec="seconds"),
              "changed": len(result.get("changed") or []),
              "waste_before": float(result.get("waste_before") or 0),
              "waste_after": float(result.get("waste_after") or 0)}
    await set_setting(RECALC_KEY.format(user_id=user_id),
                      json.dumps(record, ensure_ascii=False))


def recalc_note(record: dict | None, trend: dict | None = None,
                total: float | None = None, today: datetime | None = None) -> str:
    """Оговорка к динамике: часть движения могла прийти от пересчёта разборов.

    Без этого отчёта нельзя поверить: доля необязательного меняется не только когда человек
    меняет покупки, но и когда бот поправляет свои же вердикты по нынешним правилам.
    Показывается только тогда, когда есть что объяснять: есть движение и запись о пересчёте
    внутри того же окна.
    """
    if not record or not trend:
        return ""
    now = today or datetime.now()
    window = now - timedelta(days=TREND_WEEKS * 7)
    if record["moment"] < window:
        return ""
    before = float(record.get("waste_before") or 0)
    after = float(record.get("waste_after") or 0)
    delta = round(after - before, 2)
    if not delta:
        return ""
    direction = "уменьшилось на" if delta < 0 else "выросло на"
    lines = [f"✏️ **Оговорка к динамике:** {record['moment']:%d.%m} я пересчитал старые "
             f"разборы по нынешним правилам — необязательное за "
             f"{DEFAULT_DAYS} дн. {direction} {format_amount(abs(delta))}"
             + (f" ({record['changed']} поз.)" if record.get("changed") else "") + "."]
    lines.append("Это правка разбора, а не твои покупки: часть движения ниже — от неё.")
    return "\n".join(lines)


def recalc_text(result: dict | None) -> str:
    """Отчёт о пересчёте: сколько позиций проверено, что изменилось и как это отменяется.

    Текст говорит и о том, чего пересчёт не делает: чеки, суммы и покупки не трогаются,
    поэтому отчёты могут пересчитаться — но уже по правилам, а не по чтению модели.
    """
    if not result:
        return ""
    changed, updated = result["changed"], result["updated"]
    lines = ["🔄 **Пересчитал сохранённые разборы по нынешним правилам**"]
    if not updated:
        lines.append("Нынешним правилам нечего добавить: все позиции, до которых они дотягиваются, "
                     "уже разобраны.")
        return "\n".join(lines)
    checked, changed_n = result["checked"], len(changed)
    confirmed = len(updated) - changed_n
    positions = plural_ru(checked, "позицию", "позиции", "позиций")
    clauses = []
    if changed_n:
        wrong = plural_ru(changed_n, "вердикт", "вердикта", "вердиктов")
        verb = plural_ru(changed_n, "оказался", "оказались", "оказались")
        clauses.append(f"{changed_n} {wrong} {verb} ошибкой чтения")
    if confirmed:
        right = plural_ru(confirmed, "вердикт", "вердикта", "вердиктов")
        verb = plural_ru(confirmed, "подтверждён", "подтверждены", "подтверждены")
        clauses.append(f"{confirmed} {right} {verb} правилом")
    lines.append(f"Проверил {checked} {positions} без пометки правила: " + ", ".join(clauses) + ".")
    lines.append("Позиции, до которых правила не дотягиваются, остались оценкой модели — "
                 "их я не трогал.")
    waste_down = [item for item in changed
                  if item["verdict"] not in dict(WASTE_VERDICTS)
                  and item["old_verdict"] in dict(WASTE_VERDICTS)]
    waste_up = [item for item in changed
                if item["verdict"] in dict(WASTE_VERDICTS)
                and item["old_verdict"] not in dict(WASTE_VERDICTS)]
    if waste_down:
        lines += ["", "**Больше не считаю необязательным:**"]
        for item in waste_down[:TOP_ITEMS]:
            lines.append(f"   • {md_safe(item['name'])} — {format_amount(item['sum'])} "
                         f"(было: {item['title']})")
    if waste_up:
        lines += ["", "**Наоборот, теперь считаю необязательным:**"]
        for item in waste_up[:TOP_ITEMS]:
            lines.append(f"   • {md_safe(item['name'])} — {format_amount(item['sum'])} "
                         f"(было: нейтрально)")
    rest = len(changed) - len(waste_down) - len(waste_up)
    if rest:
        verb = plural_ru(rest, "изменился", "изменились", "изменились")
        lines.append(f"Ещё у {rest} {verb} только совет, а не группа — но и он теперь "
                     "от правила, а не от чтения модели.")
    if result["leave"]:
        lines.append("Из «не брать» уходит: "
                     + ", ".join(md_safe(item["name"]) for item in result["leave"]) + ".")
    if result["enter"]:
        lines.append("В «не брать» добавляется: "
                     + ", ".join(md_safe(item["name"]) for item in result["enter"]) + ".")
    lines += ["", "Правка идёт по названиям: чеки, суммы и покупки не трогались, поэтому "
                  "отчёты могут пересчитаться — но теперь по правилам, а не по оценке модели. "
                  "Спорную позицию по-прежнему можно поправить кнопкой «🔧 Не согласен»."]
    return "\n".join(lines)


def source_split_text(summary: dict | None) -> str:
    """Из чего сложился необязательный: проверка по правилам или оценка модели.

    Разница не косметическая: правило одинаково для одного и того же товара в любом чеке,
    а вердикт модели — её чтение кассовой строки, и ошибается она именно на сокращениях.
    Одна часть вместо двух как «разделение» не показывается: когда весь необязательный
    от одного источника, это говорится прямо, а когда пометок нет вовсе — не говорится ничего:
    у разборов до появления пометок источник просто неизвестен.
    """
    if not summary:
        return ""
    split = summary.get("by_source") or {}
    parts = [(title, split.get(key, 0)) for key, title in SOURCE_TITLES.items() if split.get(key)]
    if not parts:
        return ""
    if len(parts) == 1:
        title, _amount = parts[0]
        if title == SOURCE_TITLES["unknown"]:
            return ""
        lines = [f"🧩 Весь необязательный — {format_amount(summary['waste'])}: {title}."]
        if title == SOURCE_TITLES["model"]:
            lines.append("Это не проверка по названию, а чтение кассовой строки моделью — "
                         "именно там бывают ошибки (сокращения вроде «колб.»), и там же "
                         "она поправляется кнопкой «🔧 Не согласен» в самом разборе чека.")
        return "\n".join(lines)
    lines = [f"🧩 Из {format_amount(summary['waste'])} необязательного: "
             + ", ".join(f"{format_amount(amount)} — {title}" for title, amount in parts) + "."]
    if split.get("model"):
        lines.append("Правило одинаково для одного и того же товара в любом чеке, а оценка "
                     "модели — её чтение кассовой строки: она и ошибается. Неверный вердикт "
                     "поправляется кнопкой «🔧 Не согласен» в самом разборе чека.")
    return "\n".join(lines)


def corrected_positions(rows, allowed: set[str] | None = None) -> list[dict]:
    """Что разбор называл необязательным, а человек с этим не согласился.

    Считается по тем же вердиктам и тем же ключом товара, что и сам список «не брать», —
    отдельной памяти о правках не заводится: правка и есть «этот товар разрешён». Порог здесь
    не применяется: даже одна поправленная позиция должна быть видна в отчёте.
    """
    allowed = allowed or set()
    if not allowed:
        return []
    return [group for group in waste_groups(positions(rows), min_bans=1)
            if group["key"] in allowed]


def corrected_text(items: list[dict]) -> str:
    """Строка о правках человека: из суммы они убраны, но исчезнуть молча не должны."""
    if not items:
        return ""
    total = round(sum(item["sum"] for item in items), 2)
    count = len(items)
    lines = [f"🔧 **Ты поправил разбор: {count} {plural_ru(count, 'товар', 'товара', 'товаров')}"
             f" — {format_amount(total)}**",
             "   Эти позиции я больше не считаю необязательными: разбор ошибся, а не ты."]
    for item in items[:TOP_ITEMS]:
        lines.append(f"   • {md_safe(item['name'])} — {format_amount(item['sum'])}")
    lines.append("Вернуть как было: 📊 Отчёт → 🚫 Не брать.")
    return "\n".join(lines)


def fixed_text(name: str) -> str:
    """Ответ на правку вердикта: что изменилось и как это отменить."""
    return (f"🔧 Учёл: «{md_safe(name)}» — больше не считаю это необязательным.\n"
            "Позиция убрана из отчёта о необязательных покупках и из предупреждений "
            "при следующих покупках.\n"
            "Если передумаешь: 📊 Отчёт → 🚫 Не брать.")


def ban_text(items: list[dict], allowed: list[dict] | None = None,
             guesses: list[dict] | None = None) -> str:
    """Экран личного списка «не брать»: что в нём, почему и где бот сомневается.

    Догадки модели идут отдельным разделом и в список не спрятаны: про такие товары
    говорила только модель, а проверка по названию — нет, и молча убирать их из списка
    покупок значило бы отдать решение её чтению кассовой строки.
    """
    if not items and not allowed and not guesses:
        return ("🚫 **Не брать**\n\n"
                "Пока список пуст. Сюда попадают товары, которые в разборах чеков дважды "
                "и больше назывались «вредно» или «лишнее»: один раз — случайность, "
                "два — привычка.\n"
                "Отправляй чеки, и бот сам соберёт твой список.")
    lines = ["🚫 **Не брать** — по твоим же разборам чеков", ""]
    for entry in items:
        times = plural_ru(entry["count"], "раз", "раза", "раз")
        line = (f"   • {md_safe(entry['name'])} — {entry['count']} {times}, "
                f"всего {format_amount(entry['sum'])}")
        if entry["advice"]:
            line += f" (совет был: {md_safe(entry['advice'])})"
        lines.append(line)
    if items:
        lines += ["",
                  "Из списка покупок эти товары убраны: бот не предлагает брать то, что ты сам "
                  "признал лишним. Кнопка ниже возвращает товар назад."]
    if allowed:
        lines += ["", "🔔 **Разрешено брать всё равно:** "
                      + ", ".join(md_safe(item["name"]) for item in allowed) + "."]
    if guesses:
        lines += ["", "❓ **Похоже на необязательное, но говорит только модель:**"]
        for entry in guesses:
            times = plural_ru(entry["count"], "раз", "раза", "раз")
            lines.append(f"   • {md_safe(entry['name'])} — {entry['count']} {times}, "
                         f"всего {format_amount(entry['sum'])}")
        lines += ["Из списка покупок я их не убираю: правила такого не говорили, а модель "
                  "читает кассовую строку и ошибается на сокращениях.\n"
                  "«🚫 Не брать» — подтвердить, «🔕 Это нормально» — оставить как есть."]
    return "\n".join(lines)


def advice_effects(rows, history, today: datetime | None = None, min_days: int = MIN_EFFECT_DAYS,
                   min_before: int = MIN_BEFORE_PURCHASES) -> dict | None:
    """Подействовал ли совет: как часто товар покупался до первого совета и как — после.

    Сравниваются темпы («раз в N дней»), а не суммы: периоды имеют разную длину, и сумма
    по короткому периоду всегда меньше — сравнивать её с длинным было бы обманом. Товар
    попадает в список, только если до совета он покупался `min_before` раз и больше (иначе
    базы для сравнения нет), и если после совета прошло `min_days` дней или больше — иначе
    он попадает в «рано судить», а не в результаты. Причинность здесь не утверждается:
    «реже» значит реже, а не «благодаря совету» — товар могли просто не купить по случаю.
    """
    now = today or datetime.now()
    first_advice: dict[str, dict] = {}
    for item in positions(rows):
        if item["verdict"] not in dict(WASTE_VERDICTS):
            continue
        key = product_key(item["name"])
        if not key:
            continue
        seen = first_advice.get(key)
        if seen is None or item["moment"] < seen["moment"]:
            first_advice[key] = {"moment": item["moment"], "name": item["name"],
                                 "advice": item["advice"]}
    if not first_advice:
        return None

    effects, pending = [], []
    for group in grouped_entries(history):
        cut = next((item for item in first_advice.values()
                    if same_product(group["first"], item["name"])
                    or same_product(group["last"], item["name"])), None)
        if cut is None:
            continue
        moments = []
        for entry in group["entries"]:
            moment = parse_date(entry["date"])
            if moment:
                moments.append((moment, entry))
        before = [(moment, entry) for moment, entry in moments if moment < cut["moment"]]
        after = [(moment, entry) for moment, entry in moments if moment >= cut["moment"]]
        if len(before) < min_before:
            continue
        days_after = (now - cut["moment"]).days
        if days_after < min_days:
            pending.append({"name": group["last"], "days_after": days_after,
                            "days_left": min_days - days_after})
            continue
        # База сравнения — от первой покупки до совета; если она короче дня, берём день,
        # чтобы деление не превратилось в бесконечность на двух чеках подряд.
        span_before = max((cut["moment"] - min(moment for moment, _ in before)).days, 1)
        rate_before = len(before) / span_before
        rate_after = len(after) / max(days_after, 1)
        effects.append({
            "name": group["last"], "advice": cut["advice"],
            "before": len(before), "after": len(after),
            "days_after": days_after, "days_before": span_before,
            "interval_before": round(1 / rate_before, 1),
            "interval_after": round(1 / rate_after, 1) if rate_after else None,
            "change": round((rate_before - rate_after) / rate_before, 2),
            "sum_after": round(sum(entry["sum"] for _, entry in after), 2),
        })
    if not effects and not pending:
        return None
    effects.sort(key=lambda item: -item["change"])
    return {"effects": effects[:TOP_EFFECTS], "pending": pending[:TOP_EFFECTS]}


def _interval(days: float) -> str:
    """«раз в 9 дн.» — частота понятнее, чем «0,11 покупки в день»."""
    value = max(round(float(days)), 1)
    return f"раз в {value} дн."


def effects_text(effects: dict | None) -> str:
    """Что изменилось после советов — с прямым отказом выдавать это за доказательство."""
    if not effects or not (effects["effects"] or effects["pending"]):
        return ""
    lines = ["📈 **Что было с этими товарами после совета:**"]
    for item in effects["effects"]:
        if not item["after"]:
            tail = f"после совета не покупался ни разу ({item['days_after']} дн.)"
        else:
            was = _interval(item["interval_before"])
            now_ = _interval(item["interval_after"] or item["days_after"])
            word = ("реже" if item["change"] > 0.2
                    else "чаще" if item["change"] < -0.2 else "так же часто")
            tail = (f"раньше {was}, теперь {now_} ({word}; "
                    f"{item['before']} до совета, {item['after']} после)")
        lines.append(f"   • {md_safe(item['name'])} — {tail}")
    if effects["pending"]:
        soon = ", ".join(f"{md_safe(item['name'])} (ещё {item['days_left']} дн.)"
                         for item in effects["pending"])
        lines.append(f"⏳ Рано судить о: {soon} — с первого совета прошло слишком мало времени.")
    lines.append("Это частота покупок по твоим чекам, а не доказательство, что совет сработал: "
                 "товар мог просто не попасться или быть заменён на другой.")
    return "\n".join(lines)


def effects_line(effects: dict | None) -> str:
    """Короткая строка «что стало после советов» — для дайджеста и панели.

    Это второй формат, а не второй расчёт: слова рисуются из тех же полей, что и подробный
    блок (`effects_text`), поэтому сводка и отчёт не могут разойтись в трактовке одного замера.
    """
    if not effects or not (effects["effects"] or effects["pending"]):
        return ""
    parts = []
    for item in effects["effects"]:
        name = md_safe(item["name"])
        if not item["after"]:
            parts.append(f"{name} — больше не покупается")
            continue
        now_days = round(item["interval_after"] or item["days_after"])
        word = ("реже" if item["change"] > EFFECT_MOVED
                else "чаще" if item["change"] < -EFFECT_MOVED else "так же часто")
        parts.append(f"{name} — {word} (раз в {now_days} дн. вместо "
                     f"{round(item['interval_before'])} дн.)")
    if effects["pending"]:
        parts.append("рано судить о " + ", ".join(f"«{md_safe(item['name'])}»"
                                                  for item in effects["pending"]))
    return "📈 После советов: " + "; ".join(parts) + "."


def waste_trend(rows, weeks: int = TREND_WEEKS, today: datetime | None = None) -> dict | None:
    """Доля необязательного по неделям: видно, меняется ли что-то после советов.

    По одной неделе тренда не бывает: «стало лучше» на единственной точке было бы выдумкой,
    поэтому меньше двух недель с разобранными чеками — это None, а не нули.
    """
    now = today or datetime.now()
    buckets: list[list[dict]] = [[] for _ in range(weeks)]
    for item in positions(rows):
        index = (now - item["moment"]).days // 7
        if 0 <= index < weeks:
            buckets[weeks - 1 - index].append(item)     # свежая неделя — последняя
    found = []
    for offset, items in enumerate(buckets):
        if not items:
            continue
        total = round(sum(item["sum"] for item in items), 2)
        waste = round(sum(item["sum"] for item in items
                          if item["verdict"] in dict(WASTE_VERDICTS)), 2)
        end = now - timedelta(days=7 * (weeks - 1 - offset))
        found.append({"label": f"{end - timedelta(days=6):%d.%m}–{end:%d.%m}",
                      "total": total, "waste": waste,
                      "share": round(waste / total, 3) if total else 0, "count": len(items)})
    if len(found) < 2:
        return None
    return {"weeks": found, "first": found[0], "last": found[-1],
            "delta": round(found[-1]["share"] - found[0]["share"], 3)}


def trend_text(trend: dict | None, weeks: int = TREND_WEEKS) -> str:
    """Строка «меняется ли доля необязательного» — с честной оговоркой про шум."""
    if not trend:
        return ""
    lines = ["📊 **Доля необязательного по неделям:**"]
    for week in trend["weeks"]:
        lines.append(f"   • {week['label']} — {round(week['share'] * 100)}% "
                     f"({week['count']} поз., {format_amount(week['waste'])} из "
                     f"{format_amount(week['total'])})")
    first, last, delta = trend["first"], trend["last"], trend["delta"]
    if abs(delta) < TREND_NOISE:
        lines.append(f"➖ Доля держится на одном уровне: "
                     f"{round(first['share'] * 100)}% → {round(last['share'] * 100)}%.")
    elif delta < 0:
        lines.append(f"🟢 Необязательного стало меньше: "
                     f"{round(first['share'] * 100)}% → {round(last['share'] * 100)}%.")
    else:
        lines.append(f"🔺 Необязательного стало больше: "
                     f"{round(first['share'] * 100)}% → {round(last['share'] * 100)}% — "
                     "это не упрёк, а факт из чеков.")
    lines.append(f"Сравниваю последние {weeks} недели по разобранным чекам: "
                 "если разборов мало, недели шумят.")
    return "\n".join(lines)


def waste_text(summary: dict | None, days: int = DEFAULT_DAYS, trend: dict | None = None,
               saving: dict | None = None, effects: dict | None = None,
               corrected: list[dict] | None = None, recalc: dict | None = None) -> str:
    """Текст экрана: сумма по вердиктам, из чего она сложилась и что повторяется."""
    if not summary:
        return ("📉 **Необязательные покупки**\n\n"
                "Пока считать нечего: нужны продуктовые чеки, которые бот разбирал — вердикты "
                "по позициям сохраняются вместе с чеком.\n"
                "Отправь пару чеков с фото, и здесь появится, сколько ушло на то, что разбор "
                "советовал не брать.")
    share = f" · {round(summary['share'] * 100)}% разобранных позиций" if summary["total"] else ""
    lines = ["📉 **Необязательные покупки**",
             f"За {days} дн. · разобрано позиций: {summary['count']}",
             "",
             f"💰 **{format_amount(summary['waste'])}**{share}"]
    for group in summary["by_verdict"]:
        lines.append(f"   {group['title']} — {format_amount(group['sum'])} · {group['count']} поз.")
    split_line = source_split_text(summary)
    if split_line:
        lines += ["", split_line]
    if summary["items"]:
        lines += ["", "**Больше всего ушло на:**"]
        for item in summary["items"]:
            tail = f" — {md_safe(item['advice'])}" if item["advice"] else ""
            lines.append(f"   • {md_safe(item['name'])} — {format_amount(item['sum'])}{tail}")
    if summary["repeats"]:
        lines += ["", "**Повторяется из чека в чек:**", ""]
        for group in summary["repeats"]:
            times = plural_ru(group["count"], "раз", "раза", "раз")
            lines.append(f"   • {md_safe(group['name'])} — {group['count']} {times}, "
                         f"{format_amount(group['sum'])} всего")
    fix_line = corrected_text(corrected)
    if fix_line:
        lines += ["", fix_line]
    if effects:
        effect_line = effects_text(effects)
        if effect_line:
            lines += ["", effect_line]
    reserve = saving_text(saving)
    if reserve:
        lines += ["", "**Сколько это в месяц:**", reserve]
    trend_line = trend_text(trend)
    if trend_line:
        lines += ["", trend_line]
        recalc_line = recalc_note(recalc, trend)
        if recalc_line:
            lines += ["", recalc_line]
    lines += ["",
              "Считается только по чекам, которые бот разбирал, и по вердиктам самого разбора — "
              "это не оценка экономии, а сумма необязательных покупок."]
    return "\n".join(lines)


# ─── Цели на месяц: реэкспорт из services/goals.py ───────────────────────
# Каждое имя ниже раньше жило здесь; теперь владелец — `services/goals.py`.
# Импорт в конце файла разрывает цикл: goals импортирует `banned` отсюда.
from services.goals import (CATEGORY_PREFIX, GOAL_COUNT, GOAL_HISTORY_KEY, # noqa: E402
                            GOAL_HISTORY_LIMIT, GOAL_HISTORY_SHOWN, GOAL_KEY,
                            GOAL_MIN_MONEY_STEP, GOAL_MIN_MONTHLY, GOAL_NOTHING,
                            GOAL_NOTHING_MONEY, GOAL_SUM, GOAL_UNITS, GOAL_UNIT_KEY,
                            category_candidates, category_members, category_purchase_note,
                            close_goal_if_finished, goal_candidates,
                            goal_digest_line, goal_ends, goal_equivalent,
                            goal_followup_text, goal_history, goal_history_entry,
                            goal_history_line, goal_history_text, goal_line,
                            goal_money_step, goal_progress, goal_proposals_text,
                            goal_report_line, goal_step_phrase, goal_target, goal_text,
                            goal_unit, goal_unit_text, mark_goal_outcome_sent, parse_goal,
                            parse_goal_history, set_goal, set_goal_unit, stored_goal)
