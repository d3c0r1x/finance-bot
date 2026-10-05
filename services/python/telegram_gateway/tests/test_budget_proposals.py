import asyncio
from datetime import datetime, timezone

import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer
from aiogram import Bot
from aiogram.fsm.storage.base import StorageKey
from aiogram.types import CallbackQuery, Chat, Message, MessageEntity, Update, User

from services.python.telegram_gateway.app import build_dispatcher
from services.python.telegram_gateway.core_client import TelegramCoreClient, TelegramCoreError


PROPOSAL = {
    "id": "a349598d-b48c-4f93-87b4-c0cd98a3f061", "monthlyIncome": "100000.00",
    "totalLimit": "70000.00", "limits": {"еда": "28000.00", "транспорт": "10000.00"},
    "baseVersions": {"еда": 0, "транспорт": 2}, "baseTotalVersion": 1,
    "status": "pending", "createdAt": "2026-10-04T10:00:00Z", "proposalSource": "history_ai",
    "historyDays": 90, "modelVersion": "local-model", "promptVersion": "budget-proposal.v1",
}


def test_core_client_creates_actor_scoped_budget_proposal_and_applies_only_by_explicit_request():
    seen = []
    overview = {
        "currency": "RUB", "month": "2026-10", "familyLimits": {}, "personalOverrides": {},
        "effectiveLimits": {}, "monthlySpent": {}, "limitStatus": {}, "familyVersions": {},
        "personalVersions": {}, "familyTotalLimit": "70000.00", "personalTotalOverride": None,
        "effectiveTotalLimit": "70000.00", "totalMonthlySpent": "0.00", "totalLimitStatus": "normal",
        "familyTotalVersion": 2, "personalTotalVersion": 0, "familyRolling7FoodVersion": 0,
        "personalRolling7FoodVersion": 0, "rolling7FoodStatus": None,
    }

    async def create(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response(PROPOSAL, status=201)

    async def apply(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response(overview)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/budget-proposals/history", create)
        app.router.add_post("/internal/v1/telegram/budget-proposals/{proposal_id}/apply", apply)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            created = await client.propose_history_telegram_budget(
                "opaque-actor-context", "tg-budget-proposal-42-109")
            updated = await client.apply_telegram_budget_proposal(
                "opaque-actor-context", "tg-budget-apply-42-callback", PROPOSAL["id"])
            return created, updated

    created, updated = asyncio.run(exercise())

    assert created == PROPOSAL
    assert updated["familyTotalLimit"] == "70000.00"
    assert seen == [
        ("/internal/v1/telegram/budget-proposals/history", "test-service-secret",
         {"token": "opaque-actor-context", "idempotencyKey": "tg-budget-proposal-42-109"}),
        (f"/internal/v1/telegram/budget-proposals/{PROPOSAL['id']}/apply", "test-service-secret",
         {"token": "opaque-actor-context", "idempotencyKey": "tg-budget-apply-42-callback"}),
    ]
    assert all("tenantId" not in row[2] and "subject" not in row[2] for row in seen)


def test_core_client_rejects_invalid_or_already_applied_budget_proposals():
    async def create(_request):
        return web.json_response({"id": "not-a-uuid", "status": "applied"}, status=201)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/budget-proposals/history", create)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            return await client.propose_history_telegram_budget(
                "opaque-actor-context", "tg-budget-proposal-42-110")

    with pytest.raises(TelegramCoreError):
        asyncio.run(exercise())


def test_telegram_history_budget_proposal_waits_for_explicit_callback_apply(monkeypatch):
    sent = []
    calls = []

    class FakeCore:
        async def propose_history_telegram_budget(self, token, idempotency_key):
            calls.append(("propose", token, idempotency_key))
            return PROPOSAL

        async def apply_telegram_budget_proposal(self, token, idempotency_key, proposal_id):
            calls.append(("apply", token, idempotency_key, proposal_id))
            return {"familyTotalLimit": "70000.00", "month": "2026-10"}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    user = User(id=42, is_bot=False, first_name="Alex")
    message = Message(message_id=110, date=datetime.now(timezone.utc), chat=Chat(id=42, type="private"), from_user=user,
                      text="/budget propose", entities=[MessageEntity(type="bot_command", offset=0, length=7)])
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner",
            "permissions": ["budget.read", "budget.write"]}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=47, message=message)))

        assert calls == [("propose", "opaque-context", "tg-budget-proposal-42-110")]
        assert len(sent) == 1
        assert "90 дней" in sent[0].text
        assert "70 000.00" in sent[0].text or "70 000.00" in sent[0].text
        assert "предложение не применяется" in sent[0].text.casefold()
        apply_button = next(button for row in sent[0].reply_markup.inline_keyboard for button in row)

        callback = CallbackQuery(id="budget-apply-001", from_user=user, chat_instance="ci", message=message,
                                 data=apply_button.callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=48, callback_query=callback)))
        stored = asyncio.run(dispatcher.storage.get_data(state_key))
    finally:
        asyncio.run(bot.session.close())

    assert calls[1] == ("apply", "opaque-context", "tg-budget-proposal-apply-42-budget-apply-001",
                        PROPOSAL["id"])
    assert stored.get("telegram_budget_proposal") is None
    assert any("Лимиты применены" in (getattr(method, "text", "") or "") for method in sent)


def test_telegram_income_budget_proposal_uses_seventy_percent_and_does_not_apply(monkeypatch):
    sent = []
    calls = []

    class FakeCore:
        async def create_telegram_budget_proposal(self, token, idempotency_key, monthly_income):
            calls.append((token, idempotency_key, monthly_income))
            return {**PROPOSAL, "proposalSource": "income", "historyDays": 0,
                    "monthlyIncome": monthly_income, "totalLimit": "70000.00",
                    "modelVersion": None, "promptVersion": None}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    user = User(id=42, is_bot=False, first_name="Alex")
    message = Message(message_id=111, date=datetime.now(timezone.utc), chat=Chat(id=42, type="private"), from_user=user,
                      text="/budget suggest 100000", entities=[MessageEntity(type="bot_command", offset=0, length=7)])
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner",
            "permissions": ["budget.read", "budget.write"]}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=49, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == [("opaque-context", "tg-budget-proposal-42-111", "100000.00")]
    assert "70 000.00" in sent[0].text or "70 000.00" in sent[0].text
    assert "предложение не применяется" in sent[0].text.casefold()
