"""Process-wide bound for local Ollama model residency and GPU memory."""

from __future__ import annotations

import asyncio
import threading
from collections.abc import Awaitable, Callable
from typing import TypeVar

T = TypeVar("T")


class OllamaModelScheduler:
    def __init__(self):
        self._slot = threading.BoundedSemaphore(1)

    async def run(self, operation: Callable[[], Awaitable[T]]) -> T:
        while not self._slot.acquire(blocking=False):
            await asyncio.sleep(0.025)
        try:
            return await operation()
        finally:
            self._slot.release()


OLLAMA_MODEL_SCHEDULER = OllamaModelScheduler()
