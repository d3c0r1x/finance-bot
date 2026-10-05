import asyncio

import pytest

from services.python.intelligence.model_scheduler import OllamaModelScheduler


def test_ollama_scheduler_serializes_model_loads_across_async_calls():
    async def run():
        scheduler = OllamaModelScheduler()
        active = 0
        maximum = 0

        async def inference(value):
            nonlocal active, maximum
            active += 1
            maximum = max(maximum, active)
            await asyncio.sleep(0.02)
            active -= 1
            return value

        results = await asyncio.gather(*(scheduler.run(lambda n=n: inference(n)) for n in range(3)))
        return results, maximum

    results, maximum = asyncio.run(run())
    assert results == [0, 1, 2]
    assert maximum == 1


def test_ollama_scheduler_cancellation_does_not_leak_a_waiting_slot():
    async def run():
        scheduler = OllamaModelScheduler()
        entered = asyncio.Event()
        release = asyncio.Event()

        async def blocked():
            entered.set()
            await release.wait()

        first = asyncio.create_task(scheduler.run(blocked))
        await entered.wait()
        second = asyncio.create_task(scheduler.run(lambda: asyncio.sleep(0)))
        await asyncio.sleep(0.02)
        second.cancel()
        with pytest.raises(asyncio.CancelledError):
            await second
        release.set()
        await first
        return await scheduler.run(lambda: asyncio.sleep(0, result="available"))

    assert asyncio.run(run()) == "available"
