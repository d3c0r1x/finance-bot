"""Недельный дайджест: одна сводка вместо пяти экранов.

Собирает то, что иначе пришлось бы искать по разным кнопкам: траты недели в сравнении с
предыдущей, заметные подорожания из чеков, что пора купить и какие подписки спишутся.
Текст считает чистая функция, а данные к ней приносит тонкая асинхронная обёртка — поэтому
результат можно проверить тестом без Telegram, базы и модели.

Границы честные: дайджест не повторяет отчёты, а соединяет их выводы. Всё, что попадает
сюда, уже считается своими модулями (цены, список покупок, регулярные платежи).
"""
from datetime import datetime, timedelta

from database.db import get_receipt_price_history, get_receipt_verdicts, get_transactions
from services import advice, mutelist, recurring
from services.analytics import by_category
from services.forecast import (forecast_note, forecast_text, grocery_forecast, limit_status,
                               limit_text, weekly_food_limit, weekly_spend)
from services.purchase_history import MIN_RELATIVE, parse_date, product_groups
from services.shopping import bought_marks, due_items, hide_blocked, hide_bought
from utils.formatting import format_amount, get_category_emoji, md_safe

# История берётся с запасом: подписки и обычный темп продуктов требуют нескольких недель,
# а окно сравнения недель вырезается из неё в памяти.
HISTORY_DAYS = 200
WEEK_DAYS = 7
NEAR_DAYS = 7
TOP_CATEGORIES = 3
RISING_LIMIT = 3
DUE_LIMIT = 3


def _sum_expenses(rows: list[dict]) -> float:
    return round(sum(float(row.get("amount") or 0) for row in rows
                     if row.get("tx_type") == "expense"), 2)


def _share(amount: float, total: float) -> int:
    return int(amount / total * 100) if total else 0


def compare_weeks(current: float, previous: float) -> str:
    """Строка сравнения с прошлой неделей — честная и без ложной точности.

    Разница меньше 5% (и меньше 100 ₽) считается «примерно столько же»: на мелких суммах
    процент выглядит внушительно, но ничего не значит.
    """
    if previous <= 0:
        return ("на прошлой неделе трат не было — сравнивать не с чем" if current > 0 else "")
    change = current - previous
    if abs(change) <= max(previous * 0.05, 100):
        return f"примерно столько же, как на прошлой неделе ({format_amount(previous)})"
    percent = round(abs(change) / previous * 100)
    if change > 0:
        return f"на {percent}% больше, чем на прошлой неделе ({format_amount(previous)})"
    return f"на {percent}% меньше, чем на прошлой неделе ({format_amount(previous)})"


def build_weekly_digest(*, start: datetime, end: datetime, spent: float, previous_spent: float,
                        categories: dict | None = None, income: float = 0.0, rising=None,
                        due=None, subscriptions=None, subscription_month: float = 0.0,
                        grocery: dict | None = None,
                        grocery_status: dict | None = None,
                        effects: dict | None = None, goal_line: str = "") -> str:
    """Текст дайджеста из уже посчитанных данных."""
    lines = [f"🗓 **Недельный дайджест** — {start:%d.%m}–{end:%d.%m}", ""]
    if spent:
        lines.append(f"💸 Потрачено: **{format_amount(spent)}**")
        note = compare_weeks(spent, previous_spent)
        if note:
            lines.append(f"   {note}")
    else:
        lines.append("💸 За эту неделю трат не записано.")
    if income:
        lines.append(f"💰 Доход за неделю: {format_amount(income)}")

    ordered = sorted((categories or {}).items(), key=lambda pair: -pair[1])[:TOP_CATEGORIES]
    if ordered:
        lines += ["", f"📊 Куда ушло ({len(ordered)}):"]
        for name, amount in ordered:
            lines.append(f"   • {get_category_emoji(name)} {name} — {format_amount(amount)} "
                         f"({_share(amount, spent)}%)")

    if rising:
        lines += ["", "🏷 **Заметно подорожало:**"]
        for item in rising[:RISING_LIMIT]:
            # Процент считается от прежней цены — её же и показываем, а не «обычную»:
            # иначе процент и суммы в одной строке были бы из разных баз.
            lines.append(f"   • {md_safe(item['name'])}: {format_amount(item['baseline'])} → "
                         f"{format_amount(item['last'])} ({round(item['trend'] * 100):+d}%)")

    if due:
        total = round(sum(float(item["usual"]) for item in due[:DUE_LIMIT]), 2)
        names = ", ".join(md_safe(item["name"]) for item in due[:DUE_LIMIT])
        lines += ["", f"🛒 **Пора купить ({len(due)}):** {names} — примерно "
                      f"{format_amount(total)} по твоим прошлым ценам"]

    if grocery:
        lines += ["", forecast_text(grocery), forecast_note(grocery)]
    if grocery_status:
        lines.append(limit_text(grocery_status))

    if subscriptions:
        lines += ["", "🔁 **Спишется на этой неделе:**"]
        for item in subscriptions:
            lines.append(f"   • {md_safe(item['name'])} — {format_amount(item['amount'])}, "
                         f"{recurring.when_label(item['days_left'])}")
        if subscription_month:
            lines.append(f"   В месяц на регулярные платежи уходит примерно "
                         f"{format_amount(subscription_month)}.")

    # Эффект советов — в конце: это итог недели, а не срочная цифра. Строка одна, потому что
    # дайджест — сводка; подробный блок живёт в отчёте, а слова у обоих из одного места.
    effect_line = advice.effects_line(effects)
    if effect_line:
        lines += ["", effect_line,
                  "Частота по чекам, а не доказательство: товар мог просто не попасться."]
    # Цель — в самом конце: это не цифра недели, а обещание, которое человек дал сам себе.
    if goal_line:
        lines += ["", goal_line]
    return "\n".join(lines)


async def weekly_digest_text(user_id: int, today: datetime | None = None) -> str:
    """Собирает дайджест по данным пользователя: одна выборка истории на все разделы."""
    now = today or datetime.now()
    start = (now - timedelta(days=WEEK_DAYS - 1)).replace(hour=0, minute=0, second=0, microsecond=0)
    week_ago = start - timedelta(days=WEEK_DAYS)

    rows = [dict(row) for row in await get_transactions(user_id=user_id, days=HISTORY_DAYS)]
    current, previous = [], []
    for row in rows:
        moment = parse_date(row.get("created_at"))
        if moment is None:
            continue
        if moment >= start:
            current.append(row)
        elif moment >= week_ago:
            previous.append(row)

    history = await get_receipt_price_history(user_id)
    rising = []
    for group in product_groups(history):
        last = parse_date(group["last_date"])
        if last and last >= start and group["trend"] >= MIN_RELATIVE:
            rising.append(group)
    rising.sort(key=lambda item: -item["trend"])

    # Вердикты нужны для замера эффекта: они дают дату первого совета, а история чеков —
    # частоту покупок до и после него. Обе выборки уже нужны дайджесту и без этого.
    verdicts = [dict(row) for row in await get_receipt_verdicts(user_id)]
    effects = advice.advice_effects(verdicts, history, today=now)

    muted_shopping = await mutelist.muted_keys(user_id, mutelist.SHOPPING)
    shopping, _ = mutelist.split(due_items(history), muted_shopping)
    shopping, _ = hide_bought(shopping, await bought_marks(user_id), today=now)
    # Что человек сам признал лишним, бот в «пора купить» не предлагает.
    shopping, _ = hide_blocked(shopping, await advice.blocked_keys(user_id))

    found = recurring.find_recurring(rows)
    visible, _ = mutelist.split(found, await mutelist.muted_keys(user_id, mutelist.RECURRING))
    upcoming = [item for item in visible if 0 <= item["days_left"] <= NEAR_DAYS]

    grocery = grocery_forecast(rows, today=now)
    # Цель считает советник: у сводки нет своей частоты покупок, а второй реализации
    # «сколько раз брали в этом месяце» быть не должно. Итог закончившейся цели сводка
    # говорит ровно один раз — отметку после удачной отправки ставит планировщик.
    goal = await advice.stored_goal(user_id)
    goal_line = advice.goal_digest_line(goal, advice.goal_progress(goal, history, today=now),
                                        today=now)
    if goal_line:
        # Итог идёт вместе со счётом по всем целям: одна строка без второй читалась бы как
        # случайность, а не как «как у меня вообще с целями».
        history_line = advice.goal_history_line(await advice.goal_history(user_id))
        goal_line = f"{goal_line}\n{history_line}" if history_line else goal_line
    return build_weekly_digest(
        start=start,
        end=now,
        spent=_sum_expenses(current),
        previous_spent=_sum_expenses(previous),
        categories=by_category(current),
        income=sum(float(row.get("amount") or 0) for row in current
                   if row.get("tx_type") == "income"),
        rising=rising,
        due=shopping,
        subscriptions=upcoming,
        subscription_month=recurring.monthly_total(visible),
        grocery=grocery,
        grocery_status=limit_status(weekly_spend(rows, today=now),
                                    await weekly_food_limit(user_id)),
        effects=effects,
        goal_line=goal_line,
    )
