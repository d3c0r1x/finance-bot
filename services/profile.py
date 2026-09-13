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


async def safe_to_spend(user_id: int, spent: float, income: float | None = None) -> str:
    """«Безопасно тратить в день» с консервативным резервом.

    Доход минус траты не весь доступен для спонтанных покупок: оставляем 10% запасом,
    чтобы один крупный платёж не обнулил рекомендацию. Это не финансовая рекомендация,
    а прозрачная арифметическая подсказка.
    """
    from datetime import datetime

    from utils.formatting import format_amount, plural_ru

    plan = await planned_income(user_id)
    if not plan:
        return ""
    now = datetime.now()
    days_in_month = (now.replace(month=now.month % 12 + 1, day=1) - now.replace(day=1)).days \
        if now.month != 12 else 31
    days_left = max(1, days_in_month - now.day + 1)
    free = (income or plan) - spent
    reserve = max(0, (income or plan) * 0.10)
    spendable = free - reserve
    if spendable <= 0:
        return (f"🚨 До конца месяца {days_left} "
                f"{plural_ru(days_left, 'день', 'дня', 'дней')}: свободные деньги ниже "
                "10% резерва — лучше не добавлять необязательные траты.")
    per_day = spendable / days_left
    return (f"🟢 Безопасно тратить: **{format_amount(round(per_day))}** в день "
            f"({format_amount(round(spendable))} на {days_left} "
            f"{plural_ru(days_left, 'день', 'дня', 'дней')}; резерв 10%)")
