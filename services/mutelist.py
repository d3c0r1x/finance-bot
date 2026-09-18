"""Разделы, которые пользователь отключил: «не напоминай мне это».

Одна реализация на все подсказки: регулярные платежи («это не подписка») и товары в списке
покупок («не напоминать»). Набор ключей хранится в настройках пользователя под ключом своего
раздела, поэтому отключение подписки не может случайно скрыть товар, и наоборот.
"""
import hashlib
import json

from database.db import get_setting, set_setting

STORAGE_KEY = "{section}:muted:{user_id}"
RECURRING = "recurring"
SHOPPING = "shopping"


def digest(key: str) -> str:
    """Короткий id для callback_data: в кнопку нельзя положить кириллицу и пробелы.

    Хеш детерминированный: по одному и тому же ключу кнопка всегда одна и та же, поэтому
    держать состояние между экранами не нужно, а кнопка «не напоминать» нажмётся на тот объект,
    который человек видел, даже если список пересчитался.
    """
    return hashlib.sha1((key or "").encode("utf-8")).hexdigest()[:12]


def parse(raw: str) -> set[str]:
    """Ключи из сохранённого значения: формат разбирается в одном месте.

    Читает и бот, и панель управления, поэтому разбор вынесен из асинхронной обёртки.
    """
    try:
        stored = json.loads(raw) if raw else []
    except ValueError:
        return set()
    return {str(item) for item in stored} if isinstance(stored, list) else set()


async def muted_keys(user_id: int, section: str) -> set[str]:
    raw = await get_setting(STORAGE_KEY.format(section=section, user_id=user_id), "")
    return parse(raw)


async def _save(user_id: int, section: str, keys: set[str]) -> None:
    await set_setting(STORAGE_KEY.format(section=section, user_id=user_id),
                      json.dumps(sorted(keys), ensure_ascii=False))


async def mute(user_id: int, section: str, key: str) -> None:
    keys = await muted_keys(user_id, section)
    keys.add(key)
    await _save(user_id, section, keys)


async def unmute(user_id: int, section: str, key: str) -> None:
    keys = await muted_keys(user_id, section)
    keys.discard(key)
    await _save(user_id, section, keys)


def split(items: list[dict], muted: set[str], field: str = "key") -> tuple[list[dict], list[dict]]:
    """Делит находки на активные и отключённые: вторые нужны, чтобы их можно было вернуть."""
    return ([item for item in items if item.get(field) not in muted],
            [item for item in items if item.get(field) in muted])
