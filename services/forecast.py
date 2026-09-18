"""Прогноз по продуктам: сколько уходит на еду и не идёт ли неделя быстрее обычного.

Идея простая и проверяемая: у человека есть свой обычный недельный темп на продукты, и его
можно посчитать по собственной истории. Сравнивая с ним последние семь дней, бот замечает,
что траты поехали, ещё до конца месяца — когда отчёт за месяц ещё нечего показывать.

Окно — скользящие семь дней, а не «неделя с понедельника»: календарная неделя в середине
сравнивала бы два дня с семью и пугала бы человека зря.

Никакой магии тут нет: обычный темп — медиана недель с покупками, текущая неделя — сумма
за последние семь дней. Поэтому бот может объяснить, откуда взялась оценка, а не сослаться
на «модель так решила».
"""
from datetime import datetime

from database.db import get_setting, get_transactions, set_setting
from services.purchase_history import parse_date
from utils.formatting import format_amount, progress_bar

CATEGORY = "еда"
# Недельный лимит на продукты живёт отдельным ключом: он про неделю, а не про месяц,
# и не должен попадать в разбор личных лимитов бюджета.
FOOD_LIMIT_KEY = "food:week:{user_id}"
LIMIT_NEAR = 0.9    # с какой доли лимита предупреждать
WEEK_DAYS = 7
MEDIAN_WEEKS = 6      # по скольким прошлым неделям считаем обычный темп
MIN_WEEKS = 2         # меньше двух недель с покупками — это ещё не «обычный темп»
ALERT_SHARE = 0.25    # насколько выше обычного уже повод предупредить


def weekly_spend(rows, today: datetime | None = None, category: str = CATEGORY) -> float:
    """Сколько ушло на продукты за последние семь дней.

    Считается отдельно от прогноза: лимит — абсолютная цифра, и он не должен зависеть от того,
    набралось ли у человека достаточно истории для «обычного темпа».
    """
    now = today or datetime.now()
    total = 0.0
    for row in rows or []:
        if row.get("tx_type") != "expense" or row.get("category") != category:
            continue
        moment = parse_date(row.get("created_at"))
        if moment is None:
            continue
        age = (now - moment).days
        if 0 <= age < WEEK_DAYS:
            total += float(row.get("amount") or 0)
    return round(total, 2)


def grocery_forecast(rows, today: datetime | None = None, category: str = CATEGORY,
                     weeks: int = MEDIAN_WEEKS) -> dict | None:
    """Сравнивает последние семь дней с обычным недельным темпом на продукты.

    Обычная неделя — медиана недель, в которых продукты вообще покупались: пустые недели
    (отпуск, питание вне дома) в медиану не подмешиваются, иначе база была бы заниженной.
    Возвращает None, если истории мало — тогда бот молчит, а не выдумывает прогноз.
    """
    now = today or datetime.now()
    buckets = [0.0] * weeks
    for row in rows or []:
        if row.get("tx_type") != "expense" or row.get("category") != category:
            continue
        moment = parse_date(row.get("created_at"))
        if moment is None:
            continue
        age = (now - moment).days
        if age < 0:
            continue
        index = age // WEEK_DAYS
        if index < weeks:
            buckets[index] += float(row.get("amount") or 0)

    current = weekly_spend(rows, today=now, category=category)
    past = [value for value in buckets[1:] if value > 0]
    if len(past) < MIN_WEEKS:
        return None
    usual = round(sorted(past)[len(past) // 2], 2)
    share = round((current - usual) / usual, 3) if usual else 0
    return {
        "category": category,
        "current": round(current, 2),
        "usual": usual,
        "share": share,
        "weeks_counted": len(past),
        "over": share >= ALERT_SHARE,
        "under": share <= -ALERT_SHARE,
    }


async def food_week_status(user_id: int, today: datetime | None = None) -> dict | None:
    """Состояние недельного лимита прямо сейчас: читает только последние семь дней.

    Одна точка входа для главного экрана, ответа на запись траты и панели — иначе каждая
    из них сама решала бы, что такое «неделя», и цифры расходились бы. Без лимита возвращает
    None: показывать «в рамках лимита», которого нет, нельзя.
    """
    limit = await weekly_food_limit(user_id)
    if not limit:
        return None
    return limit_status(await weekly_food_spend(user_id, today), limit)


async def weekly_food_spend(user_id: int, today: datetime | None = None) -> float:
    """Сумма на продукты за последние семь дней по базе — узкий запрос для частых экранов."""
    rows = [dict(row) for row in await get_transactions(user_id=user_id, days=WEEK_DAYS)]
    return weekly_spend(rows, today=today)


async def food_week_line(user_id: int, today: datetime | None = None) -> str:
    """Строка про продукты для главного экрана: с лимитом — прогресс, без него — сумма."""
    spent = await weekly_food_spend(user_id, today)
    return food_line(spent, await weekly_food_limit(user_id))


def food_limit_key(user_id: int) -> str:
    """Ключ настроек недельного лимита: одно место, где записан формат (им пользуется и панель)."""
    return FOOD_LIMIT_KEY.format(user_id=user_id)


async def weekly_food_limit(user_id: int) -> float:
    """Недельный лимит на продукты: 0 — лимит не задан."""
    raw = await get_setting(food_limit_key(user_id), "")
    try:
        return max(0.0, float(raw)) if raw else 0.0
    except (TypeError, ValueError):
        return 0.0


async def set_weekly_food_limit(user_id: int, value: float) -> None:
    await set_setting(food_limit_key(user_id), str(round(float(value), 2)))


def limit_status(current: float, limit: float) -> dict | None:
    """Сравнивает продукты за последние семь дней с недельным лимитом."""
    if not limit or limit <= 0:
        return None
    ratio = current / limit
    return {
        "limit": round(limit, 2),
        "current": round(current, 2),
        "ratio": round(ratio, 3),
        "left": round(limit - current, 2),
        "over": ratio >= 1,
        "near": ratio >= LIMIT_NEAR,
    }


def food_line(spent: float, limit: float) -> str:
    """Строка о продуктах для главного экрана.

    Без лимита показывается только сумма за неделю — это полезная цифра сама по себе;
    без лимита и без трат строки нет вовсе, чтобы не засорять главный экран.
    """
    if not limit:
        return f"🍎 Продукты за неделю: {format_amount(spent)}" if spent else ""
    if not spent:
        return f"🍎 Продукты за неделю: ничего из {format_amount(limit)}"
    status = limit_status(spent, limit)
    percent = min(100, int(status["ratio"] * 100))
    if status["over"]:
        return (f"🚨 Продукты: {format_amount(spent)} из {format_amount(limit)} — "
                f"перерасход {format_amount(abs(status['left']))}")
    marker = "⚠️" if status["near"] else "🍎"
    return (f"{marker} Продукты: {progress_bar(percent)} {percent}% — "
            f"{format_amount(spent)} из {format_amount(limit)} за неделю")


def limit_text(status: dict | None) -> str:
    """Строка про лимит: превышение, близость и обычное состояние — разным тоном."""
    if not status:
        return ""
    current = format_amount(status["current"])
    limit = format_amount(status["limit"])
    if status["over"]:
        return (f"🚨 **Лимит на продукты превышен:** {current} из {limit} за 7 дней — "
                f"на {format_amount(abs(status['left']))} больше.")
    if status["near"]:
        return (f"⚠️ **Продукты почти выбрали недельный лимит:** {current} из {limit}, "
                f"осталось {format_amount(status['left'])}.")
    return f"🍎 Продукты: {current} из {limit} за 7 дней — в рамках лимита."


def forecast_text(stats: dict | None) -> str:
    """Строка про продукты для сводки и дайджеста — с разным тоном по факту."""
    if not stats:
        return ""
    current = format_amount(stats["current"])
    usual = format_amount(stats["usual"])
    percent = round(abs(stats["share"]) * 100)
    if stats["over"]:
        return (f"⚠️ **Продукты быстрее обычного:** за последние 7 дней {current} — "
                f"на {percent}% больше твоей обычной недели ({usual}).")
    if stats["under"]:
        return (f"🟢 **Продукты экономнее обычного:** за последние 7 дней {current} — "
                f"на {percent}% меньше обычной недели ({usual}).")
    return f"🛒 Продукты: за последние 7 дней {current}, обычно {usual} — темп обычный."


def forecast_note(stats: dict | None) -> str:
    """Пояснение под строкой: на чём построен обычный темп — без этого цифра выглядит взятой с потолка."""
    if not stats:
        return ""
    weeks = stats["weeks_counted"]
    return (f"Обычный темп — медиана {weeks} "
            f"{'недели' if weeks % 10 in (2, 3, 4) and weeks % 100 not in (12, 13, 14) else 'недель'} "
            "с покупками продуктов по твоим чекам; недели без покупок в расчёт не идут.")
