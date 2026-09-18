"""Профиль пользователя: имя, план дохода и пройденная приветственная настройка.

Одно место, где живут персональные настройки. Ключи в таблице settings с префиксом
`profile:{telegram_id}:` — рядом с лимитами бюджета, поэтому панель управления
(panel.py) читает и правит их теми же запросами, что и сам бот.
"""
from config import USERS
from database.db import get_all_settings, get_setting, set_setting

DEFAULT_DISPLAY_NAME = "друг"
LEGACY_DISPLAY_NAMES = {"повелитель", "девушка"}


def telegram_display_name(telegram_user) -> str:
    """Возвращает имя из Telegram без обращения к роли или статическому конфигу.

    Для приветствий используем имя профиля Telegram, затем username и только потом
    нейтральный fallback. Значение не записываем автоматически: пользователь всё ещё
    может задать отдельное имя в приветственной настройке.
    """
    if telegram_user is None:
        return DEFAULT_DISPLAY_NAME
    first_name = " ".join((getattr(telegram_user, "first_name", "") or "").split())
    last_name = " ".join((getattr(telegram_user, "last_name", "") or "").split())
    full_name = " ".join(part for part in (first_name, last_name) if part)
    if full_name:
        return full_name[:NAME_HINT_MAX]
    username = " ".join((getattr(telegram_user, "username", "") or "").split()).strip()
    if username:
        return f"@{username.lstrip('@')}"[:NAME_HINT_MAX]
    return DEFAULT_DISPLAY_NAME

NAME_HINT_MAX = 40


def _key(user_id: int, field: str) -> str:
    return f"profile:{user_id}:{field}"


async def display_name(user_id: int) -> str:
    """Возвращает пользовательское имя, а не роль из конфигурации."""
    stored = await get_setting(_key(user_id, "name"))
    if stored:
        return stored
    return (USERS.get(user_id) or {}).get("name") or DEFAULT_DISPLAY_NAME


async def sync_telegram_name(user_id: int, telegram_user) -> str:
    """Ставит имя Telegram для новых пользователей и удаляет старые шаблонные имена.

    Явное имя, заданное пользователем в настройках, не перезаписываем. Это позволяет
    приветствовать человека его реальным Telegram-именем, но сохраняет его выбор
    «как меня называть» после прохождения onboarding.
    """
    stored = await get_setting(_key(user_id, "name"))
    if stored and stored.strip().casefold() not in LEGACY_DISPLAY_NAMES:
        return stored
    name = telegram_display_name(telegram_user)
    await set_setting(_key(user_id, "name"), name)
    return name


async def set_name(user_id: int, name: str) -> str:
    """Сохраняет имя (обрезает до разумной длины) и возвращает его."""
    cleaned = " ".join((name or "").split())[:NAME_HINT_MAX]
    if cleaned:
        await set_setting(_key(user_id, "name"), cleaned)
    return cleaned


async def planned_income(user_id: int) -> float | None:
    """Ожидаемый доход в месяц — для «безопасно тратить в день» и предложения бюджета."""
    stored = await get_setting(_key(user_id, "income"))
    try:
        value = float(stored)
    except (TypeError, ValueError):
        return None
    return value if value > 0 else None


async def set_planned_income(user_id: int, value: float) -> None:
    await set_setting(_key(user_id, "income"), str(round(float(value), 2)))


async def is_onboarded(user_id: int) -> bool:
    return (await get_setting(_key(user_id, "onboarded"))) == "1"


async def mark_onboarded(user_id: int, value: bool = True) -> None:
    await set_setting(_key(user_id, "onboarded"), "1" if value else "0")


async def all_profiles() -> dict[int, dict]:
    """Профили всех пользователей одной выборкой — для панели управления."""
    stored = await get_all_settings()
    profiles: dict[int, dict] = {}
    for key, value in stored.items():
        parts = key.split(":")
        if len(parts) != 3 or parts[0] != "profile":
            continue
        try:
            user_id = int(parts[1])
        except ValueError:
            continue
        profiles.setdefault(user_id, {})[parts[2]] = value
    return profiles


async def safe_to_spend(user_id: int, spent: float, income: float | None = None,
                        today=None) -> str:
    """«Безопасно тратить в день»: горизонт до зарплаты, минус уже обещанные списания.

    Считаем не «до конца месяца», а до ближайшей ожидаемой зарплаты: если доход приходит
    пятого числа, конец месяца — произвольный срок, а после зарплаты деньги начинаются заново.
    Дата берётся из истории повторяющихся поступлений.

    Из свободных денег вычитаем то, что точно уйдёт до этого дня: подписки и прочие
    регулярные платежи. Включить их в «можно тратить» — значит записать уже потраченное
    в свободные деньги, и подсказка была бы неправдой. Плюс 10% резерва, чтобы один
    крупный платёж не обнулил рекомендацию. Это не финансовая рекомендация, а прозрачная
    арифметическая подсказка.
    """
    from datetime import datetime

    from database.db import get_transactions
    from services import mutelist, recurring
    from utils.formatting import format_amount, plural_ru

    plan = await planned_income(user_id)
    if not plan:
        return ""
    now = today or datetime.now()
    days_in_month = (now.replace(month=now.month % 12 + 1, day=1)
                     - now.replace(day=1)).days if now.month != 12 else 31
    rows = [dict(row) for row in await get_transactions(user_id=user_id, days=200)]
    # Отключённые пользователем серии в расчёт не идут: он сказал «это не подписка»
    subscriptions, _ = mutelist.split(recurring.find_recurring(rows, today=now),
                                      await mutelist.muted_keys(user_id, mutelist.RECURRING))
    salary = recurring.next_income(recurring.find_recurring(rows, today=now, tx_type="income"))

    if salary:
        horizon = max(1, salary["days_left"])
        until = f"до зарплаты ({horizon} "
    else:
        horizon = max(1, days_in_month - now.day + 1)
        until = f"до конца месяца ({horizon} "
    promised = recurring.total_before(subscriptions, horizon)
    free = (income or plan) - spent
    reserve = max(0, (income or plan) * 0.10)
    spendable = free - reserve - promised
    details = (until + plural_ru(horizon, "день", "дня", "дней") + ")")
    if promised:
        details += f", подписки {format_amount(promised)}"
    if spendable <= 0:
        return (f"🚨 {details.capitalize()}: свободные деньги уже заняты резервом "
                "и обещанными списаниями — лучше не добавлять необязательные траты.")
    per_day = spendable / horizon
    return (f"🟢 Безопасно тратить: **{format_amount(round(per_day))}** в день "
            f"({format_amount(round(spendable))} свободно; {details}; резерв 10%)")
