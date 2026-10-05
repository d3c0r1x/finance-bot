import asyncio

from aiohttp.test_utils import TestClient, TestServer
from aiogram import Bot
import pytest

from services.python.telegram_gateway.app import create_webhook_app, build_dispatcher, validate_webhook_url
from services.python.telegram_gateway.dedup import RedisUpdateDeduplicator


def test_webhook_checks_secret_and_deduplicates_update_ids(monkeypatch):
    sent = []

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    bot = Bot("123456:TEST_TOKEN")
    dispatcher = build_dispatcher()
    app = create_webhook_app(bot, dispatcher, "private-webhook-secret")
    update = {
        "update_id": 7001,
        "message": {
            "message_id": 2,
            "date": 1790899200,
            "chat": {"id": 42, "type": "private"},
            "from": {"id": 42, "is_bot": False, "first_name": "Alex"},
            "text": "/menu",
            "entities": [{"type": "bot_command", "offset": 0, "length": 5}],
        },
    }

    async def exercise():
        async with TestClient(TestServer(app)) as client:
            denied = await client.post("/telegram/webhook", json=update,
                                       headers={"X-Telegram-Bot-Api-Secret-Token": "wrong"})
            accepted = await client.post("/telegram/webhook", json=update,
                                         headers={"X-Telegram-Bot-Api-Secret-Token": "private-webhook-secret"})
            repeated = await client.post("/telegram/webhook", json=update,
                                         headers={"X-Telegram-Bot-Api-Secret-Token": "private-webhook-secret"})
            return denied.status, accepted.status, repeated.status

    assert asyncio.run(exercise()) == (401, 200, 200)
    assert len(sent) == 1


def test_redis_deduplicator_claims_update_once_with_expiring_key():
    class FakeRedis:
        def __init__(self):
            self.values = set()
            self.calls = []

        async def set(self, key, value, *, nx, ex):
            self.calls.append((key, value, nx, ex))
            if key in self.values:
                return None
            self.values.add(key)
            return True

    redis = FakeRedis()
    deduplicator = RedisUpdateDeduplicator(redis, ttl_seconds=900)

    async def exercise():
        return await deduplicator.is_duplicate(7001), await deduplicator.is_duplicate(7001)

    assert asyncio.run(exercise()) == (False, True)
    assert redis.calls == [
        ("finance:telegram:update:7001", "1", True, 900),
        ("finance:telegram:update:7001", "1", True, 900),
    ]


def test_webhook_url_requires_public_https():
    assert validate_webhook_url("https://finance.example/telegram/webhook") == \
        "https://finance.example/telegram/webhook"
    with pytest.raises(ValueError):
        validate_webhook_url("http://finance.example/telegram/webhook")
    with pytest.raises(ValueError):
        validate_webhook_url("https:///telegram/webhook")
