"""Фильтр доступа: пропускает только пользователей из config.USERS."""
from aiogram.filters import BaseFilter
from aiogram.types import Message, CallbackQuery, TelegramObject

from config import USERS


class AccessFilter(BaseFilter):
    async def __call__(self, event: TelegramObject) -> bool:
        if isinstance(event, (Message, CallbackQuery)):
            return event.from_user is not None and event.from_user.id in USERS
        return False
