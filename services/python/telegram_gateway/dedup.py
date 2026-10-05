"""Short-lived Telegram update deduplication backed by Redis in deployments."""

from __future__ import annotations

import time
from collections import OrderedDict
from typing import Protocol

from aiogram import BaseMiddleware
from aiogram.types import Update


class UpdateDeduplicator(Protocol):
    async def is_duplicate(self, update_id: int) -> bool: ...


class InMemoryUpdateDeduplicator:
    """Bounded local fallback for single-process development and tests."""

    def __init__(self, ttl_seconds: int = 86_400, max_entries: int = 10_000):
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self._expires: OrderedDict[int, float] = OrderedDict()

    async def is_duplicate(self, update_id: int) -> bool:
        now = time.monotonic()
        while self._expires:
            _, expires_at = next(iter(self._expires.items()))
            if expires_at > now:
                break
            self._expires.popitem(last=False)
        if update_id in self._expires:
            return True
        while len(self._expires) >= self.max_entries:
            self._expires.popitem(last=False)
        self._expires[update_id] = now + self.ttl_seconds
        return False


class RedisUpdateDeduplicator:
    def __init__(self, redis, ttl_seconds: int = 86_400):
        if ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be positive")
        self.redis = redis
        self.ttl_seconds = ttl_seconds

    async def is_duplicate(self, update_id: int) -> bool:
        claimed = await self.redis.set(
            f"finance:telegram:update:{update_id}", "1", nx=True, ex=self.ttl_seconds
        )
        return claimed is None


class DeduplicateUpdates(BaseMiddleware):
    def __init__(self, deduplicator: UpdateDeduplicator):
        self.deduplicator = deduplicator

    async def __call__(self, handler, event: Update, data: dict):
        if await self.deduplicator.is_duplicate(event.update_id):
            return None
        return await handler(event, data)
