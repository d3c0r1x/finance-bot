"""Список покупок по ритму своих же чеков.

Сервис отвечает на один вопрос: «что я обычно беру примерно сейчас?». Ритм берётся из истории
позиций чеков пользователя (тот же группировщик товаров, что и в каталоге цен), поэтому список
покупок, «Мои цены» и предупреждение о подорожании не могут разойтись в трактовке товара.

Это подсказка, а не учёт остатков: бот не знает, что лежит у тебя дома, — он видит только,
как часто товар появляется в чеках.
"""
import json
import statistics
from datetime import datetime, timedelta

from database.db import get_setting, set_setting
from services.purchase_history import grouped_entries, parse_date, product_key
from utils.formatting import format_amount, md_safe

# Где хранятся отметки «уже купил»: товар -> дата отметки, пока она не устареет сама.
BOUGHT_KEY = "shopping:bought:{user_id}"

# Чаще раза в три дня — это не ритм закупки, а две строки одного похода в магазин.
MIN_INTERVAL_DAYS = 3
# «Пора» наступает чуть раньше срока: иначе подсказка приходит, когда всё уже кончилось.
DUE_SHARE = 0.85
# Насколько заранее показывать товар в списке.
HORIZON_DAYS = 3
MIN_PURCHASES = 3
# Просрочка больше двух обычных сроков — товар просто перестали брать: не мозолим глаза.
MAX_OVERDUE_SHARE = 2.0


def due_items(history, horizon_days: int = HORIZON_DAYS, min_purchases: int = MIN_PURCHASES,
              today: datetime | None = None) -> list[dict]:
    """Товары, которые по своему же ритму пора брать, — с ценой и лучшим магазином.

    Интервал — медиана промежутков между покупками: один пропущенный поход в магазин не должен
    сдвигать ожидаемый срок. Товары с редким ритмом (месяц и реже) тоже попадают сюда: если
    ты берёшь стиральный порошок раз в месяц, подсказка раз в месяц уместна.
    """
    now = today or datetime.now()
    result = []
    for group in grouped_entries(history):
        entries = group["entries"]
        dates = [moment for moment in (parse_date(entry["date"]) for entry in entries) if moment]
        if len(dates) < min_purchases or len(dates) < len(entries):
            # Дату разобрало не у всех позиций: срок всё равно, а ритм — уже неточно.
            continue
        intervals = [int((later - earlier).days)
                     for earlier, later in zip(dates, dates[1:])
                     if (later - earlier).days >= MIN_INTERVAL_DAYS]
        if not intervals:
            continue
        interval = max(round(statistics.median(intervals)), MIN_INTERVAL_DAYS)
        last = dates[-1]
        due = last + timedelta(days=max(round(interval * DUE_SHARE), 1))
        until = (due.date() - now.date()).days
        if until > horizon_days or until < -interval * MAX_OVERDUE_SHARE:
            continue
        prices = [entry["price"] for entry in entries]
        usual = round(statistics.median(prices), 2)
        cheapest = min(entries, key=lambda entry: entry["price"])
        result.append({
            "key": product_key(entries[-1]["name"]),
            "name": entries[-1]["name"],
            "usual": usual,
            "cheapest": cheapest["price"],
            "cheapest_store": cheapest["store"],
            "last_date": last,
            "interval": interval,
            "due_date": due,
            "until": until,
            "count": len(entries),
            "spent": round(sum(entry["sum"] for entry in entries), 2),
        })
    # Сначала то, что просрочено и стоит дороже, — это самое полезное в списке.
    return sorted(result, key=lambda item: (item["until"], -item["spent"]))


async def bought_marks(user_id: int) -> dict[str, str]:
    """Отметки «уже купил»: товар — дата отметки в формате ГГГГ-ММ-ДД."""
    raw = await get_setting(BOUGHT_KEY.format(user_id=user_id), "")
    try:
        stored = json.loads(raw) if raw else {}
    except ValueError:
        return {}
    return ({str(key): str(value) for key, value in stored.items()}
            if isinstance(stored, dict) else {})


async def mark_bought(user_id: int, key: str, today: datetime | None = None) -> None:
    """Помечает товар купленным, чтобы он вернулся только через обычный срок.

    Отметка не трогает ни траты, ни историю: она живёт своей жизнью и сама устаревает. Когда
    появится настоящий чек на этот товар, его дата окажется позже отметки — и товар снова
    начнёт считаться по обычному ритму.
    """
    marks = await bought_marks(user_id)
    marks[key] = (today or datetime.now()).strftime("%Y-%m-%d")
    await set_setting(BOUGHT_KEY.format(user_id=user_id),
                      json.dumps(marks, ensure_ascii=False, sort_keys=True))


def hide_bought(items: list[dict], marks: dict[str, str],
                today: datetime | None = None) -> tuple[list[dict], list[dict]]:
    """Делит список на «пока не напоминать» и остальное.

    Отметка действует только один обычный срок и только если она свежее последней покупки:
    отметка месячной давности не должна навсегда выключить товар.
    """
    now = today or datetime.now()
    visible, marked = [], []
    for item in items:
        moment = parse_date(marks.get(item["key"], ""))
        fresh = (moment is not None and moment.date() >= item["last_date"].date()
                 and (now.date() - moment.date()).days < item["interval"])
        (marked if fresh else visible).append(item)
    return visible, marked


def hide_blocked(items: list[dict], blocked: set[str] | None = None) -> tuple[list[dict], list[dict]]:
    """Делит список на предлагаемое и скрытое личным списком «не брать».

    Скрытое возвращается отдельно, а не выбрасывается: в экране должно быть видно, что товар
    убран и почему, иначе «пропало» выглядит как поломка.
    """
    if not blocked:
        return items, []
    return ([item for item in items if item["key"] not in blocked],
            [item for item in items if item["key"] in blocked])


def _when(item: dict) -> str:
    if item["until"] < 0:
        return f"пора брать: срок прошёл {abs(item['until'])} дн. назад"
    if item["until"] == 0:
        return "пора брать: срок как раз сегодня"
    return f"скоро: ожидаю через {item['until']} дн."


def _cheaper_note(item: dict) -> str:
    """Строка про место, где этот товар выходил дешевле, — только если разница заметна."""
    if not item["cheapest_store"] or item["cheapest"] >= item["usual"] * 0.95:
        return ""
    return f"   дешевле всего было {format_amount(item['cheapest'])}, {md_safe(item['cheapest_store'])}"


def shopping_text(items: list[dict], limit: int = 10, muted: list[dict] | None = None,
                  marked: list[dict] | None = None,
                  blocked: list[dict] | None = None) -> str:
    """Текст списка покупок: что пора взять и во сколько это обойдётся по прошлым ценам."""
    if not items:
        text = ("🛒 **Список покупок**\n\n"
                "Пока ничего не пора: бот выучил ритм тех товаров, которые ты берёшь регулярно, "
                "и молчит, пока до срока далеко.\n"
                "Товар попадёт сюда после трёх покупок в чеках — тогда бот знает и обычный "
                "срок, и обычную цену.")
        text += _hidden_note(muted, marked, blocked)
        return text
    overdue = [item for item in items if item["until"] < 0]
    total = sum(item["usual"] for item in items[:limit])
    head = "🛒 **Список покупок**"
    if overdue:
        head += f" — {len(overdue)} уже пора брать"
    lines = [head, ""]
    for item in items[:limit]:
        line = (f"• **{md_safe(item['name'])}** — обычно {format_amount(item['usual'])}\n"
                f"   берёшь раз в {item['interval']} дн., последний раз "
                f"{item['last_date'].strftime('%d.%m')} · {_when(item)}")
        cheaper = _cheaper_note(item)
        if cheaper:
            line += "\n" + cheaper
        lines.append(line)
    if len(items) > limit:
        lines.append(f"… и ещё {len(items) - limit} товаров — показываю самые крупные по тратам.")
    lines += ["", f"💰 По твоим прошлым ценам это примерно {format_amount(total)}."]
    stores = {}
    for item in items[:limit]:
        if item["cheapest_store"]:
            stores[item["cheapest_store"]] = stores.get(item["cheapest_store"], 0) + 1
    if stores:
        best_store, count = max(stores.items(), key=lambda pair: pair[1])
        if count >= 2:
            lines.append(f"💡 Чаще всего дешевле выходило в «{md_safe(best_store)}» — "
                         f"{count} из {len(items[:limit])} товаров.")
    lines.append("\nБот не знает остатков дома: список построен по ритму твоих чеков, "
                 "а не по инвентаризации.")
    lines.append(_hidden_note(muted, marked, blocked))
    return "\n".join(line for line in lines if line is not None)


def _hidden_note(muted: list[dict] | None, marked: list[dict] | None,
                 blocked: list[dict] | None = None) -> str:
    """Строки о том, что скрыто и почему: без них «пропало» выглядит как сломалось."""
    lines = []
    if blocked:
        lines.append("\n🚫 Убрано из списка (личное «не брать»): "
                     + ", ".join(md_safe(item["name"]) for item in blocked[:5])
                     + ". Вернуть — на экране «🚫 Не брать».")
    if marked:
        lines.append("\n✅ Уже отмечено купленным: "
                     + ", ".join(md_safe(item["name"]) for item in marked[:5])
                     + " — напомню снова через обычный срок.")
    if muted:
        lines.append("🔕 Отключены из напоминаний: "
                     + ", ".join(md_safe(item["name"]) for item in muted[:5]) + ".")
    return "\n".join(lines)
