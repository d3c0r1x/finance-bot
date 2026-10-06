import asyncio
from datetime import datetime, timezone

import pytest
from aiogram import Bot
from aiogram.fsm.storage.base import StorageKey
from aiogram.types import CallbackQuery, Chat, InlineKeyboardMarkup, Message, MessageEntity, Update, User

from services.python.telegram_gateway.app import build_dispatcher
import services.python.telegram_gateway.app as telegram_app


@pytest.mark.parametrize("command", ["/start", "/menu", "/help", "Меню"])
def test_basic_commands_restore_persistent_menu(monkeypatch, command):
    sent = []

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message_text = command
    entities = ([MessageEntity(type="bot_command", offset=0, length=len(command))]
                if command.startswith("/") else None)
    message = Message(
        message_id=1,
        date=datetime(2026, 10, 2, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text=message_text,
        entities=entities,
    )
    update = Update(update_id=1, message=message)
    bot = Bot("123456:TEST_TOKEN")
    try:
        asyncio.run(build_dispatcher().feed_update(bot, update))
    finally:
        asyncio.run(bot.session.close())

    assert len(sent) == 1
    assert sent[0].chat_id == 42
    markup = sent[0].reply_markup
    assert markup.resize_keyboard is True
    assert [[button.text for button in row] for row in markup.keyboard] == [["Меню"]]
    assert sent[0].text
    if command == "/help":
        assert "/history" in sent[0].text
        assert "/report" in sent[0].text
        assert "/budget" in sent[0].text
        assert "/inflation" in sent[0].text


def test_link_command_redeems_code_for_telegram_sender_id_only(monkeypatch):
    sent = []
    redeemed = []

    class FakeCore:
        async def redeem_link_code(self, code, telegram_user_id, telegram_display_name=None):
            redeemed.append((code, telegram_user_id, telegram_display_name))
            return "linked"

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=2,
        date=datetime(2026, 10, 2, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/link ABCD-EFGH-JKLM-NPQR",
        entities=[MessageEntity(type="bot_command", offset=0, length=5)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        asyncio.run(build_dispatcher(telegram_core=FakeCore()).feed_update(
            bot, Update(update_id=2, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert redeemed == [("ABCD-EFGH-JKLM-NPQR", 42, "Alex")]
    assert len(sent) == 1
    assert "привязан" in sent[0].text.casefold()
    assert "код другим" in sent[0].text.casefold()


def test_link_command_rejects_group_chat_without_contacting_core(monkeypatch):
    sent = []

    class FakeCore:
        async def redeem_link_code(self, code, telegram_user_id):
            raise AssertionError("Group chat must not redeem link codes")

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=3,
        date=datetime(2026, 10, 2, tzinfo=timezone.utc),
        chat=Chat(id=-100, type="supergroup"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/link ABCD-EFGH-JKLM-NPQR",
        entities=[MessageEntity(type="bot_command", offset=0, length=5)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        asyncio.run(build_dispatcher(telegram_core=FakeCore()).feed_update(
            bot, Update(update_id=3, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert len(sent) == 1
    assert "личном чате" in sent[0].text.casefold()


def test_menu_uses_revisioned_choices_and_stores_only_core_issued_actor_context(monkeypatch):
    sent = []
    issued = []
    tenants = [
        {"tenantId": "tenant-a", "displayName": "Home", "role": "owner"},
        {"tenantId": "tenant-b", "displayName": "Family", "role": "member"},
    ]

    class FakeCore:
        async def list_telegram_tenants(self, telegram_user_id, telegram_display_name=None):
            assert telegram_user_id == 42
            assert telegram_display_name == "Alex"
            return tenants

        async def issue_actor_context(self, telegram_user_id, tenant_id):
            issued.append((telegram_user_id, tenant_id))
            return {"token": "opaque-context-token", "tenantId": tenant_id,
                    "displayName": "Home", "role": "owner", "permissions": [],
                    "expiresAt": "2026-10-03T20:00:00Z"}

        async def get_dashboard_summary(self, actor_context_token):
            assert actor_context_token == "opaque-context-token"
            return {"month": "2026-10", "currency": "RUB", "incomeTotal": "0.00",
                    "expenseTotal": "0.00", "transactionCount": 0}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=10,
        date=datetime(2026, 10, 2, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/menu",
        entities=[MessageEntity(type="bot_command", offset=0, length=5)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=10, message=message)))
        keyboard = sent[0].reply_markup
        assert isinstance(keyboard, InlineKeyboardMarkup)
        choices = [button for row in keyboard.inline_keyboard for button in row]
        assert [button.text for button in choices] == ["Home", "Family"]
        assert all("tenant-a" not in button.callback_data and "tenant-b" not in button.callback_data
                   for button in choices)
        callback = CallbackQuery(id="selection-1", from_user=message.from_user, chat_instance="chat-instance",
                                 message=message, data=choices[0].callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=11, callback_query=callback)))
        storage_data = asyncio.run(dispatcher.storage.get_data(
            StorageKey(bot_id=bot.id, chat_id=42, user_id=42)))
    finally:
        asyncio.run(bot.session.close())

    assert issued == [(42, "tenant-a")]
    assert storage_data["telegram_actor_context"]["token"] == "opaque-context-token"
    assert storage_data["telegram_actor_context"]["tenantId"] == "tenant-a"
    assert any("Home" in method.text for method in sent if getattr(method, "text", None))


def test_menu_rejects_stale_tenant_choice_without_issuing_context(monkeypatch):
    sent = []
    issued = []

    class FakeCore:
        async def list_telegram_tenants(self, _telegram_user_id, _telegram_display_name=None):
            return [
                {"tenantId": "tenant-a", "displayName": "Home", "role": "owner"},
                {"tenantId": "tenant-b", "displayName": "Family", "role": "member"},
            ]

        async def issue_actor_context(self, telegram_user_id, tenant_id):
            issued.append((telegram_user_id, tenant_id))
            return {}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=12,
        date=datetime(2026, 10, 2, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/menu",
        entities=[MessageEntity(type="bot_command", offset=0, length=5)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=12, message=message)))
        stale_choice = sent[0].reply_markup.inline_keyboard[0][0].callback_data
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=13, message=message.model_copy(update={"message_id": 13}))))
        callback = CallbackQuery(id="selection-stale", from_user=message.from_user, chat_instance="chat-instance",
                                 message=message, data=stale_choice)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=14, callback_query=callback)))
    finally:
        asyncio.run(bot.session.close())

    assert issued == []
    assert any("обнов" in (getattr(method, "text", "") or "").casefold() for method in sent)


def test_add_command_creates_review_draft_and_callback_confirms_once(monkeypatch):
    sent = []
    calls = []
    draft_id = "9b199adb-4408-4b6b-a4a7-0de897b05ea4"

    class FakeCore:
        async def create_transaction_draft(self, token, key, text):
            calls.append(("create", token, key, text))
            return {"id": draft_id, "tenantId": "tenant-a", "type": "expense", "amount": "2000.00", "currency": "RUB",
                    "categoryCode": "transport", "description": "Такси", "version": 1,
                    "occurredAt": "2026-10-03T10:00:00Z", "subcategoryCode": None,
                    "provider": "ollama", "modelVersion": "qwen-test", "promptVersion": "transaction-draft.v1"}

        async def confirm_transaction_draft(self, token, received_id, key, version):
            calls.append(("confirm", token, received_id, key, version))
            return {"id": "transaction-1", "type": "expense", "amount": "5000.00", "currency": "RUB",
                    "budgetAlerts": [{"budgetKey": "transport", "threshold": "near",
                                      "limit": "5000.00", "spent": "4500.00"}]}

        async def update_transaction_draft_amount(self, token, received_id, version, amount):
            calls.append(("amount", token, received_id, version, amount))
            return {"id": draft_id, "tenantId": "tenant-a", "type": "expense", "amount": amount,
                    "currency": "RUB", "categoryCode": "transport", "description": "Такси",
                    "occurredAt": "2026-10-03T10:00:00Z", "subcategoryCode": None,
                    "version": version + 1, "provider": "ollama", "modelVersion": "qwen-test",
                    "promptVersion": "transaction-draft.v1"}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=100,
        date=datetime(2026, 10, 2, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/add Такси 2 тыс",
        entities=[MessageEntity(type="bot_command", offset=0, length=4)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=20, message=message)))
        markup = sent[0].reply_markup
        buttons = [button for row in markup.inline_keyboard for button in row]
        assert [button.text for button in buttons] == ["Подтвердить", "Отменить", "500", "1 000", "2 000", "5 000",
                                                       "Сумма", "Категория", "Подкатегория", "Описание", "Дата"]
        assert all("opaque-context" not in button.callback_data and "tenant-a" not in button.callback_data
                   for button in buttons)
        update_callback = CallbackQuery(id="amount-1", from_user=message.from_user,
                                        chat_instance="chat-instance", message=message,
                                        data=buttons[5].callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=21, callback_query=update_callback)))
        assert calls[1] == ("amount", "opaque-context", draft_id, 1, "5000.00")
        assert "5000.00" in sent[-1].text
        updated_markup = sent[-1].reply_markup
        updated_buttons = [button for row in updated_markup.inline_keyboard for button in row]
        assert all(":2:" in button.callback_data for button in updated_buttons)
        callback = CallbackQuery(id="confirm-1", from_user=message.from_user,
                                 chat_instance="chat-instance", message=message,
                                 data=updated_buttons[0].callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=22, callback_query=callback)))
    finally:
        asyncio.run(bot.session.close())

    assert calls[0] == ("create", "opaque-context", "tg-add-42-100", "Такси 2 тыс")
    assert calls[2][0:3] == ("confirm", "opaque-context", draft_id)
    assert calls[2][3:] == ("tg-confirm-" + draft_id, 2)
    assert "2000.00" in sent[0].text
    assert "qwen-test" in sent[0].text
    assert any("добавлена" in (getattr(method, "text", "") or "").casefold() for method in sent)
    assert any("достигнут порог 90%" in (getattr(method, "text", "") or "").casefold()
               and "transport" in method.text and "4500.00" in method.text for method in sent)


def test_add_income_labels_source_and_confirms_as_income(monkeypatch):
    sent = []
    calls = []
    draft_id = "36dcb8e5-9714-4986-bab6-c7138128d403"

    class FakeCore:
        async def create_transaction_draft(self, token, key, text):
            calls.append(("create", token, key, text))
            return {"id": draft_id, "tenantId": "tenant-a", "type": "income", "amount": "125000.00",
                    "currency": "RUB", "categoryCode": "income", "subcategoryCode": None,
                    "description": "Salary for October", "occurredAt": "2026-10-02T06:30:00Z",
                    "debtId": None, "state": "pending", "version": 1, "provider": "ollama",
                    "modelVersion": "qwen-test", "promptVersion": "transaction-draft.v2"}

        async def confirm_transaction_draft(self, token, received_id, key, version):
            calls.append(("confirm", token, received_id, key, version))
            return {"id": "income-transaction", "tenantId": "tenant-a", "type": "income",
                    "amount": "125000.00"}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=105,
        date=datetime(2026, 10, 2, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/add Salary 125000",
        entities=[MessageEntity(type="bot_command", offset=0, length=4)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=33, message=message)))
        review = sent[-1]
        assert "Доход: 125000.00 RUB" in review.text
        assert "Источник: Salary for October" in review.text
        assert "Описание:" not in review.text
        confirm = review.reply_markup.inline_keyboard[0][0]
        callback = CallbackQuery(id="confirm-income", from_user=message.from_user,
                                 chat_instance="chat-instance", message=message, data=confirm.callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=34, callback_query=callback)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == [
        ("create", "opaque-context", "tg-add-42-105", "Salary 125000"),
        ("confirm", "opaque-context", draft_id, "tg-confirm-" + draft_id, 1),
    ]
    assert any("добавлена" in (getattr(method, "text", "") or "").casefold()
               and "доход" in (getattr(method, "text", "") or "").casefold() for method in sent)


def test_add_command_and_draft_confirmation_are_private_and_actor_scoped(monkeypatch):
    sent = []

    class FakeCore:
        async def create_transaction_draft(self, *_args):
            raise AssertionError("Group chats cannot create drafts")

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=101,
        date=datetime(2026, 10, 2, tzinfo=timezone.utc),
        chat=Chat(id=-100, type="supergroup"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/add Такси 2 тыс",
        entities=[MessageEntity(type="bot_command", offset=0, length=4)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        asyncio.run(build_dispatcher(telegram_core=FakeCore()).feed_update(bot, Update(update_id=22, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert len(sent) == 1
    assert "личном чате" in sent[0].text.casefold()


def test_draft_field_edit_reloads_core_state_and_saves_a_new_version(monkeypatch):
    sent = []
    calls = []
    draft_id = "9b199adb-4408-4b6b-a4a7-0de897b05ea4"
    current = {"id": draft_id, "tenantId": "tenant-a", "type": "expense", "amount": "2000.00",
               "currency": "RUB", "categoryCode": "transport", "subcategoryCode": None, "description": "Такси",
               "occurredAt": "2026-10-03T10:00:00Z", "debtId": None, "state": "pending", "version": 1,
               "provider": "ollama", "modelVersion": "qwen-test", "promptVersion": "transaction-draft.v1"}

    class FakeCore:
        async def get_transaction_draft(self, token, received_id):
            calls.append(("read", token, received_id))
            return current

        async def update_transaction_draft(self, token, received_id, version, draft):
            calls.append(("edit", token, received_id, version, draft.copy()))
            return {**draft, "id": draft_id, "tenantId": "tenant-a", "currency": "RUB", "state": "pending",
                    "version": version + 1, "provider": "ollama", "modelVersion": "qwen-test",
                    "promptVersion": "transaction-draft.v1"}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=102,
        date=datetime(2026, 10, 2, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="food",
    )
    callback_message = message.model_copy(update={"text": "Review draft"})
    callback = CallbackQuery(id="edit-category-1", from_user=message.from_user,
                                 chat_instance="chat-instance", message=callback_message,
                                 data=f"draft:{draft_id}:1:edit:categoryCode")
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=23, callback_query=callback)))
        assert "категории" in sent[-1].text.casefold()
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=24, message=message)))
        updated_buttons = [button for row in sent[-1].reply_markup.inline_keyboard for button in row]
    finally:
        asyncio.run(bot.session.close())

    assert calls[0] == ("read", "opaque-context", draft_id)
    assert calls[1][0:4] == ("edit", "opaque-context", draft_id, 1)
    assert calls[1][4]["categoryCode"] == "food"
    assert all(":2:" in button.callback_data for button in updated_buttons)


def test_debt_payment_requires_core_listed_debt_before_confirmation(monkeypatch):
    sent = []
    calls = []
    draft_id = "9b199adb-4408-4b6b-a4a7-0de897b05ea4"
    debt_id = "42cf023d-42d6-4e5c-83e6-1b67c9b546de"
    draft = {"id": draft_id, "tenantId": "tenant-a", "type": "debt_payment", "amount": "1500.00",
             "currency": "RUB", "categoryCode": "долги", "subcategoryCode": None,
             "description": "Платёж по кредитке", "occurredAt": "2026-10-03T10:00:00Z", "debtId": None,
             "state": "pending", "version": 1, "provider": "ollama", "modelVersion": "qwen-test",
             "promptVersion": "transaction-draft.v1"}

    class FakeCore:
        async def list_telegram_debts(self, token):
            calls.append(("debts", token))
            return [{"id": debt_id, "name": "Credit card", "currentBalance": "8500.00",
                     "tenantId": "tenant-a", "openingBalance": "10000.00", "interestRate": "25.00",
                     "minimumPayment": "500.00", "status": "open", "version": 3}]

        async def get_transaction_draft(self, token, received_id):
            calls.append(("read", token, received_id))
            return draft

        async def update_transaction_draft(self, token, received_id, version, updated):
            calls.append(("edit", token, received_id, version, updated.copy()))
            return {**updated, "id": draft_id, "tenantId": "tenant-a", "version": version + 1}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=103,
        date=datetime(2026, 10, 2, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="Review debt payment",
    )
    callback = CallbackQuery(id="list-debts", from_user=message.from_user,
                             chat_instance="chat-instance", message=message,
                             data=f"draft:{draft_id}:1:debts")
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=25, callback_query=callback)))
        buttons = [button for row in sent[-1].reply_markup.inline_keyboard for button in row]
        assert [button.text for button in buttons] == ["Credit card · 8500.00 RUB"]
        assert debt_id not in buttons[0].callback_data
        selected = CallbackQuery(id="select-debt", from_user=message.from_user,
                                 chat_instance="chat-instance", message=message,
                                 data=buttons[0].callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=26, callback_query=selected)))
        updated_buttons = [button for row in sent[-1].reply_markup.inline_keyboard for button in row]
    finally:
        asyncio.run(bot.session.close())

    assert calls == [
        ("read", "opaque-context", draft_id),
        ("debts", "opaque-context"),
        ("read", "opaque-context", draft_id),
        ("edit", "opaque-context", draft_id, 1, {**draft, "debtId": debt_id}),
    ]
    assert [button.text for button in updated_buttons[:2]] == ["Подтвердить", "Отменить"]
    assert ":2:" in updated_buttons[0].callback_data


def test_history_command_lists_core_records_repeat_as_draft_and_voids_latest_once(monkeypatch):
    sent = []
    calls = []
    transaction_id = "9b199adb-4408-4b6b-a4a7-0de897b05ea4"
    draft = {"id": "d0b84aa5-e28a-4ad1-873b-34b4da7b7b4f", "tenantId": "tenant-a",
             "type": "expense", "amount": "2000.00", "currency": "RUB", "categoryCode": "transport",
             "subcategoryCode": None, "description": "Такси", "occurredAt": "2026-10-03T10:00:00Z",
             "debtId": None, "state": "pending", "version": 1, "provider": "manual",
             "modelVersion": "repeat", "promptVersion": "transaction-repeat.v1"}

    class FakeCore:
        async def list_telegram_transactions(self, token, limit):
            calls.append(("list", token, limit))
            return [{"id": transaction_id, "tenantId": "tenant-a", "type": "expense",
                     "amount": "2000.00", "currency": "RUB", "categoryCode": "transport",
                     "subcategoryCode": None, "description": "Такси", "source": "text_ai",
                     "occurredAt": "2026-10-03T10:00:00Z", "status": "posted", "version": 2,
                     "createdAt": "2026-10-03T10:00:01Z"}]

        async def repeat_telegram_transaction(self, token, key, received_id):
            calls.append(("repeat", token, key, received_id))
            return draft

        async def void_latest_telegram_transaction(self, token, key, received_id, version):
            calls.append(("void", token, key, received_id, version))
            return {"id": received_id, "status": "voided", "amount": "2000.00", "type": "expense"}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=104,
        date=datetime(2026, 10, 3, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/history",
        entities=[MessageEntity(type="bot_command", offset=0, length=8)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=30, message=message)))
        assert "Такси" in sent[-1].text and "2000.00" in sent[-1].text
        buttons = [button for row in sent[-1].reply_markup.inline_keyboard for button in row]
        repeat_button = next(button for button in buttons if button.text.startswith("Повторить"))
        undo_button = next(button for button in buttons if button.text == "Отменить последнюю")
        assert transaction_id not in repeat_button.callback_data
        repeat_callback = CallbackQuery(id="repeat-history", from_user=message.from_user,
                                        chat_instance="chat-instance", message=message,
                                        data=repeat_button.callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=31, callback_query=repeat_callback)))
        undo_callback = CallbackQuery(id="undo-history", from_user=message.from_user,
                                      chat_instance="chat-instance", message=message,
                                      data=undo_button.callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=32, callback_query=undo_callback)))
    finally:
        asyncio.run(bot.session.close())

    assert calls[0] == ("list", "opaque-context", 8)
    assert calls[1] == ("repeat", "opaque-context", "tg-repeat-42-repeat-history", transaction_id)
    assert any("manual / repeat" in (getattr(method, "text", "") or "") for method in sent)
    assert calls[2] == ("void", "opaque-context", "tg-void-42-undo-history", transaction_id, 2)
    assert any("отменена" in (getattr(method, "text", "") or "").casefold() for method in sent)


def test_debts_command_lists_scoped_balances_rates_and_minimums(monkeypatch):
    sent = []
    calls = []

    class FakeCore:
        async def list_telegram_debts(self, token):
            calls.append(token)
            return [{"id": "debt-1", "tenantId": "tenant-a", "name": "Кредитная карта",
                     "openingBalance": "10000.00", "currentBalance": "8500.00",
                     "interestRate": "25.00", "minimumPayment": "500.00", "status": "open", "version": 3}]

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=106,
        date=datetime(2026, 10, 4, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/debts",
        entities=[MessageEntity(type="bot_command", offset=0, length=6)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=41, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == ["opaque-context"]
    assert len(sent) == 1
    assert "Кредитная карта" in sent[0].text
    assert "8500.00" in sent[0].text
    assert "25.00%" in sent[0].text
    assert "500.00" in sent[0].text
    assert "Итого долгов: 8500.00 RUB" in sent[0].text
    assert sent[0].reply_markup.keyboard[0][0].text == "Меню"


def test_price_command_renders_member_catalog_png_with_real_best_store(monkeypatch):
    sent = []
    calls = []
    catalog = {"mode": "search", "query": "tea", "products": [{
        "productName": "Tea Green 500g", "purchaseCount": 2, "usualUnitPrice": "110.000000",
        "hasBaseline": True, "baselineUnitPrice": "100.000000", "lastUnitPrice": "120.000000",
        "lastPurchasedAt": "2026-09-10T10:00:00Z", "lastMerchant": "Market B",
        "cheapestUnitPrice": "100.000000", "cheapestMerchant": "Market A", "totalSpent": "220.00",
        "change": "20.000000", "relative": "0.200000", "signal": True, "direction": "up",
        "priorPurchases": 1, "chartAvailable": True, "history": [
            {"receiptId": "r1", "itemId": "i1", "purchasedAt": "2026-09-01T10:00:00Z",
             "merchant": "Market A", "name": "Tea Green 500g", "unitPrice": "100.000000", "current": False},
            {"receiptId": "r2", "itemId": "i2", "purchasedAt": "2026-09-10T10:00:00Z",
             "merchant": "Market B", "name": "Tea Green 500g", "unitPrice": "120.000000", "current": False},
        ],
    }]}

    class FakeCore:
        async def get_product_catalog(self, token, query):
            calls.append((token, query))
            return catalog

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=901,
        date=datetime(2026, 10, 5, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/price tea",
        entities=[MessageEntity(type="bot_command", offset=0, length=6)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=901, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == [("opaque-context", "tea")]
    assert len(sent) == 1
    assert sent[0].__class__.__name__ == "SendPhoto"
    assert sent[0].photo.filename == "finance-prices.png"
    assert "Market A" in sent[0].caption
    assert sent[0].reply_markup.keyboard[0][0].text == "Меню"


def test_shopping_command_uses_linked_actor_and_explains_it_is_not_inventory(monkeypatch):
    sent = []
    calls = []
    shopping = {"candidates": [{"productName": "Молоко 1 л", "purchaseCount": 3,
                "productKey": "milk",
                "medianIntervalDays": 10, "usualUnitPrice": "100.000000", "estimatedCost": "100.00",
                "lastPurchasedAt": "2026-10-04T00:00:00Z", "dueAt": "2026-10-05T00:00:00Z",
                "daysUntilDue": 0}], "estimatedListCost": "100.00", "inventoryTracked": False,
                "boughtCandidates": [], "mutedCandidates": [], "blockedCandidates": []}

    class FakeCore:
        async def get_shopping_candidates(self, token):
            calls.append(token)
            return shopping

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=902,
        date=datetime(2026, 10, 5, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/shopping",
        entities=[MessageEntity(type="bot_command", offset=0, length=9)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=902, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == ["opaque-context"]
    assert len(sent) == 1
    assert sent[0].__class__.__name__ == "SendMessage"
    assert "Молоко 1 л" in sent[0].text
    assert "100,00" in sent[0].text
    assert "не учёт запасов" in sent[0].text.lower()
    assert any("Уже купил" in button.text for row in sent[0].reply_markup.inline_keyboard for button in row)


def test_shopping_bought_callback_uses_saved_product_key_and_refreshes_hidden_reason(monkeypatch):
    sent = []
    calls = []
    candidate = {"productName": "Молоко 1 л", "productKey": "milk", "purchaseCount": 3,
                 "medianIntervalDays": 10, "usualUnitPrice": "100.000000", "estimatedCost": "100.00",
                 "lastPurchasedAt": "2026-10-04T00:00:00Z", "dueAt": "2026-10-05T00:00:00Z",
                 "daysUntilDue": 0}
    active = {"candidates": [candidate], "estimatedListCost": "100.00", "inventoryTracked": False,
              "boughtCandidates": [], "mutedCandidates": [], "blockedCandidates": []}
    marked = {"candidates": [], "estimatedListCost": "0.00", "inventoryTracked": False,
              "boughtCandidates": [candidate], "mutedCandidates": [], "blockedCandidates": []}

    class FakeCore:
        async def get_shopping_candidates(self, token):
            assert token == "opaque-context"
            return active

        async def mark_shopping_bought(self, token, key):
            calls.append((token, key))
            return marked

        async def mute_shopping_suggestion(self, _token, _key):
            raise AssertionError("unexpected mute action")

        async def unmute_shopping_suggestion(self, _token, _key):
            raise AssertionError("unexpected unmute action")

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(message_id=903, date=datetime(2026, 10, 5, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"), from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/shopping", entities=[MessageEntity(type="bot_command", offset=0, length=9)])
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=903, message=message)))
        shopping_message = next(method for method in sent if method.__class__.__name__ == "SendMessage")
        bought = next(button for row in shopping_message.reply_markup.inline_keyboard for button in row
                      if button.text.startswith("Уже купил"))
        callback = CallbackQuery(id="shopping-bought", from_user=message.from_user,
                                 chat_instance="chat-instance", message=message, data=bought.callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=904, callback_query=callback)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == [("opaque-context", "milk")]
    edit = next(method for method in sent if method.__class__.__name__ == "EditMessageText")
    assert "Уже куплено" in edit.text
    assert "обычного интервала" in edit.text


def test_personal_inflation_command_uses_linked_actor_and_labels_receipt_prices(monkeypatch):
    sent = []
    calls = []
    inflation = {
        "available": True,
        "reasonCode": "available",
        "asOf": "2026-10-06T12:00:00Z",
        "windowDays": 90,
        "productCount": 3,
        "basketBefore": "1000.00",
        "basketNow": "1050.00",
        "indexPercent": "5.00",
        "rising": [],
        "falling": [],
    }

    class FakeCore:
        async def get_personal_inflation(self, token):
            calls.append(token)
            return inflation

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=960,
        date=datetime(2026, 10, 6, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/inflation",
        entities=[MessageEntity(type="bot_command", offset=0, length=10)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=960, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == ["opaque-context"]
    assert len(sent) == 1
    assert sent[0].__class__.__name__ == "SendMessage"
    assert "1 000,00" in sent[0].text
    assert "1 050,00" in sent[0].text
    assert "только цены из ваших чеков" in sent[0].text.lower()


def test_recurring_command_shows_due_soon_separately_from_overdue(monkeypatch):
    sent = []
    calls = []
    phone = {"id": "1" * 32, "key": "phone plan", "name": "Phone plan", "type": "expense", "currency": "RUB",
             "amount": "200.00", "minAmount": "200.00", "maxAmount": "200.00", "periodCode": "week",
             "periodDays": 7, "minIntervalDays": 7, "maxIntervalDays": 7, "occurrences": 3,
             "lastDate": "2026-08-15", "nextDate": "2026-08-22", "daysUntil": 2}
    overdue = {**phone, "id": "2" * 32, "key": "old payment", "name": "Old payment",
               "lastDate": "2026-08-06", "nextDate": "2026-08-13", "daysUntil": -7}
    muted = {**phone, "id": "3" * 32, "key": "cloud backup", "name": "Cloud backup"}
    projection = {"algorithmVersion": "recurring.v1", "completeness": "complete", "timeZone": "Europe/Moscow",
                  "asOf": "2026-08-20T00:00:00+03:00", "expenseSeries": [phone, overdue], "incomeSeries": [],
                  "dueSoon": [phone], "overdue": [overdue], "nextIncome": None,
                  "monthlyExpenseEstimate": "857.14", "monthlyExpenseEstimates": {"RUB": "857.14"},
                  "mutedSeries": [muted]}

    class FakeCore:
        async def get_recurring_projection(self, token):
            calls.append(token)
            return projection

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(message_id=961, date=datetime(2026, 10, 6, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"), from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/recurring", entities=[MessageEntity(type="bot_command", offset=0, length=10)])
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=961, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == ["opaque-context"]
    assert len(sent) == 1 and sent[0].__class__.__name__ == "SendMessage"
    assert "Phone plan" in sent[0].text and "Old payment" in sent[0].text
    assert "интервал 7–7 дн." in sent[0].text
    assert "скоро спишется" in sent[0].text.lower() and "просрочено на 7 дн." in sent[0].text.lower()
    buttons = [button for row in sent[0].reply_markup.inline_keyboard for button in row]
    assert any(button.callback_data == f"recurring:mute:{phone['id']}" for button in buttons)
    assert any(button.callback_data == f"recurring:unmute:{muted['id']}" for button in buttons)


def test_recurring_buttons_mute_and_restore_current_series(monkeypatch):
    sent = []
    calls = []
    phone = {"id": "1" * 32, "key": "phone plan", "name": "Phone plan", "type": "expense", "currency": "RUB",
             "amount": "200.00", "minAmount": "200.00", "maxAmount": "200.00", "periodCode": "week",
             "periodDays": 7, "minIntervalDays": 7, "maxIntervalDays": 7, "occurrences": 3,
             "lastDate": "2026-08-15", "nextDate": "2026-08-22", "daysUntil": 2}
    active = {"algorithmVersion": "recurring.v1", "completeness": "complete", "timeZone": "Europe/Moscow",
              "asOf": "2026-08-20T00:00:00+03:00", "expenseSeries": [phone], "incomeSeries": [],
              "dueSoon": [phone], "overdue": [], "nextIncome": None, "monthlyExpenseEstimate": "857.14",
              "monthlyExpenseEstimates": {"RUB": "857.14"}, "mutedSeries": []}
    muted = {**active, "expenseSeries": [], "dueSoon": [], "monthlyExpenseEstimate": None,
             "monthlyExpenseEstimates": {}, "mutedSeries": [phone]}

    class FakeCore:
        async def get_recurring_projection(self, _token):
            return active

        async def mute_recurring_series(self, token, series_id):
            calls.append(("mute", token, series_id))
            return muted

        async def unmute_recurring_series(self, token, series_id):
            calls.append(("unmute", token, series_id))
            return active

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(message_id=964, date=datetime(2026, 10, 6, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"), from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/recurring", entities=[MessageEntity(type="bot_command", offset=0, length=10)])
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=964, message=message)))
        first_send = next(method for method in sent if method.__class__.__name__ == "SendMessage")
        mute_button = next(button for row in first_send.reply_markup.inline_keyboard for button in row
                           if button.callback_data == f"recurring:mute:{phone['id']}")
        mute_callback = CallbackQuery(id="recurring-mute", from_user=message.from_user,
                                      chat_instance="chat-instance", message=message, data=mute_button.callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=965, callback_query=mute_callback)))
        muted_edit = next(method for method in reversed(sent) if method.__class__.__name__ == "EditMessageText")
        restore_button = next(button for row in muted_edit.reply_markup.inline_keyboard for button in row
                              if button.callback_data == f"recurring:unmute:{phone['id']}")
        restore_callback = CallbackQuery(id="recurring-restore", from_user=message.from_user,
                                         chat_instance="chat-instance", message=message, data=restore_button.callback_data)
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=966, callback_query=restore_callback)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == [("mute", "opaque-context", phone["id"]), ("unmute", "opaque-context", phone["id"])]
    assert "Отключённые напоминания" in muted_edit.text
    assert any(method.__class__.__name__ == "EditMessageText" and "Регулярные расходы" in method.text
               for method in sent)


def test_debts_command_rejects_foreign_tenant_response_without_disclosing_debt(monkeypatch):
    sent = []

    class FakeCore:
        async def list_telegram_debts(self, _token):
            return [{"id": "debt-1", "tenantId": "tenant-b", "name": "Secret debt",
                     "openingBalance": "10000.00", "currentBalance": "8500.00",
                     "interestRate": None, "minimumPayment": None, "status": "open", "version": 3}]

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=107,
        date=datetime(2026, 10, 4, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/debts",
        entities=[MessageEntity(type="bot_command", offset=0, length=6)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=42, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert len(sent) == 1
    assert "Secret debt" not in sent[0].text
    assert "пространство" in sent[0].text.casefold()


def test_budget_command_shows_server_limits_status_food_pace_and_safe_to_spend(monkeypatch):
    sent = []
    calls = []

    class FakeCore:
        async def get_budget_overview(self, token):
            calls.append(("budget", token))
            return {
                "currency": "RUB", "month": "2026-10", "familyLimits": {"еда": "20000.00"},
                "personalOverrides": {"еда": "15000.00"}, "effectiveLimits": {"еда": "15000.00"},
                "monthlySpent": {"еда": "14000.00"}, "limitStatus": {"еда": "near"},
                "familyTotalLimit": "55000.00", "personalTotalOverride": "50000.00",
                "effectiveTotalLimit": "50000.00", "totalMonthlySpent": "45000.00", "totalLimitStatus": "near",
                "rolling7FoodStatus": {"fromDate": "2026-09-28", "toDate": "2026-10-04", "limit": "3000.00",
                                        "spent": "2500.00", "remaining": "500.00", "limitStatus": "near",
                                        "usualWeeklySpend": "2700.00", "historyWeeks": 5,
                                        "paceStatus": "normal", "paceShare": "92.59"},
            }

        async def get_dashboard_summary(self, token):
            calls.append(("summary", token))
            return {"month": "2026-10", "currency": "RUB", "incomeTotal": "120000.00",
                    "expenseTotal": "40000.00", "transactionCount": 4,
                    "safeToSpend": {"incomeBasis": "actual_income", "incomeBase": "120000.00",
                                    "month": "2026-10", "horizonDate": "2026-10-31", "daysRemaining": 28,
                                    "monthlyExpenses": "40000.00", "reserve": "12000.00",
                                    "promisedPayments": "5000.00", "safeTotal": "63000.00",
                                    "safePerDay": "2250.00"},
                    "rolling7FoodStatus": None}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=108, date=datetime(2026, 10, 4, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"), from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/budget", entities=[MessageEntity(type="bot_command", offset=0, length=7)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=43, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == [("budget", "opaque-context"), ("summary", "opaque-context")]
    assert len(sent) == 1
    for text in ("55000.00", "50000.00", "Еда", "90%", "3000.00", "500.00", "63000.00", "2250.00", "2026-10-31"):
        assert text in sent[0].text
    assert sent[0].reply_markup.keyboard[0][0].text == "Меню"


def test_budget_command_requires_private_chat_before_core_access(monkeypatch):
    sent = []

    class FakeCore:
        async def get_budget_overview(self, _token):
            raise AssertionError("Budget data must not load from a group chat")

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=109, date=datetime(2026, 10, 4, tzinfo=timezone.utc),
        chat=Chat(id=-100, type="supergroup"), from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/budget", entities=[MessageEntity(type="bot_command", offset=0, length=7)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        asyncio.run(build_dispatcher(telegram_core=FakeCore()).feed_update(bot, Update(update_id=44, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert len(sent) == 1
    assert "личном чате" in sent[0].text


def test_budget_command_sets_personal_limit_with_core_version(monkeypatch):
    sent = []
    calls = []
    overview = {
        "currency": "RUB", "month": "2026-10", "familyLimits": {"еда": "20000.00"},
        "personalOverrides": {}, "effectiveLimits": {"еда": "20000.00"}, "monthlySpent": {"еда": "0.00"},
        "limitStatus": {"еда": "normal"}, "familyVersions": {"еда": 2}, "personalVersions": {"еда": 0},
        "familyTotalLimit": "55000.00", "personalTotalOverride": None, "effectiveTotalLimit": "55000.00",
        "totalMonthlySpent": "0.00", "totalLimitStatus": "normal", "familyTotalVersion": 1,
        "personalTotalVersion": 0, "familyRolling7FoodVersion": 0, "personalRolling7FoodVersion": 0,
        "rolling7FoodStatus": None,
    }

    class FakeCore:
        async def get_budget_overview(self, token):
            calls.append(("read", token))
            return overview

        async def update_telegram_budget(self, token, idempotency_key, key, scope, amount, version, period="monthly"):
            calls.append(("update", token, idempotency_key, key, scope, amount, version, period))
            return {**overview, "personalOverrides": {"еда": amount}, "effectiveLimits": {"еда": amount}}

        async def get_dashboard_summary(self, token):
            return {"month": "2026-10", "currency": "RUB", "incomeTotal": "0.00",
                    "expenseTotal": "0.00", "transactionCount": 0, "safeToSpend": None}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=108, date=datetime(2026, 10, 4, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"), from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/budget set personal еда 5000", entities=[MessageEntity(type="bot_command", offset=0, length=7)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "member"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=45, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == [("read", "opaque-context"),
                     ("update", "opaque-context", "tg-budget-42-108", "еда", "personal", "5000.00", 0, "monthly")]
    assert len(sent) == 1
    assert "Лимит обновлён" in sent[0].text
    assert sent[0].reply_markup.keyboard[0][0].text == "Меню"


def test_budget_command_resets_personal_overrides(monkeypatch):
    sent = []
    calls = []

    class FakeCore:
        async def reset_telegram_budget(self, token, idempotency_key):
            calls.append((token, idempotency_key))
            return {"currency": "RUB", "month": "2026-10", "familyLimits": {}, "personalOverrides": {},
                    "effectiveLimits": {}, "monthlySpent": {}, "limitStatus": {}, "familyVersions": {},
                    "personalVersions": {}, "familyTotalLimit": "55000.00", "personalTotalOverride": None,
                    "effectiveTotalLimit": "55000.00", "totalMonthlySpent": "0.00", "totalLimitStatus": "normal",
                    "familyTotalVersion": 1, "personalTotalVersion": 2, "familyRolling7FoodVersion": 0,
                    "personalRolling7FoodVersion": 2, "rolling7FoodStatus": None}

        async def get_dashboard_summary(self, token):
            return {"month": "2026-10", "currency": "RUB", "incomeTotal": "0.00",
                    "expenseTotal": "0.00", "transactionCount": 0, "safeToSpend": None}

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    message = Message(
        message_id=109, date=datetime(2026, 10, 4, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"), from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/budget reset", entities=[MessageEntity(type="bot_command", offset=0, length=7)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "member"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=46, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == [("opaque-context", "tg-budget-reset-42-109")]
    assert len(sent) == 1
    assert "личные лимиты сброшены" in sent[0].text.casefold()


def test_report_command_uses_shared_report_dto_and_renders_photo_with_family_scope(monkeypatch):
    sent = []
    calls = []
    report = {"period": "week", "scope": "family", "fromDate": "2026-09-28", "toDate": "2026-10-04",
              "asOfDate": "2026-10-04", "timezone": "Europe/Moscow", "currency": "RUB",
              "incomeTotal": "100.00", "expenseTotal": "75.50", "debtPaymentTotal": "0.00",
              "refundTotal": "5.00", "transactionCount": 3, "expenseByCategory": {"food": "75.50"},
              "expenseByDay": {f"2026-09-{day:02d}": "0.00" for day in range(28, 31)} | {
                  f"2026-10-{day:02d}": "0.00" for day in range(1, 5)},
              "weekendSharePercent": 25, "monthlyBudgetLimit": "20000.00",
              "monthlyBudgetRemaining": "19924.50", "rolling7FoodStatus": {
                  "fromDate": "2026-09-28", "toDate": "2026-10-04", "limit": "1000.00",
                  "spent": "430.00", "remaining": "570.00", "limitStatus": "normal",
                  "usualWeeklySpend": None, "historyWeeks": 0,
                  "paceStatus": "insufficient_history", "paceShare": None}}

    class FakeCore:
        async def get_report(self, token, *, period, month=None, from_date=None, to_date=None, scope="personal"):
            calls.append((token, period, month, from_date, to_date, scope))
            return report

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    monkeypatch.setattr(telegram_app, "render_report", lambda _report: ("image/png", b"png-bytes"))
    message = Message(
        message_id=105,
        date=datetime(2026, 10, 4, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/report week family",
        entities=[MessageEntity(type="bot_command", offset=0, length=7)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=40, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == [("opaque-context", "week", None, None, None, "family")]
    assert len(sent) == 1
    assert sent[0].__class__.__name__ == "SendPhoto"
    assert sent[0].photo.filename == "finance-report.png"
    assert "75.50 RUB" in sent[0].caption
    assert "Еда за 7 дней: 430.00 / 1000.00 RUB" in sent[0].caption
    assert "Остаток лимита еды: 570.00 RUB" in sent[0].caption
    assert "Недостаточно истории для темпа" in sent[0].caption
    assert "Обычный недельный расход: 0.00 RUB" not in sent[0].caption
    assert sent[0].reply_markup.keyboard[0][0].text == "Меню"


def test_report_command_accepts_custom_dates_and_text_fallback(monkeypatch):
    sent = []
    calls = []
    report = {"period": "custom", "scope": "personal", "fromDate": "2026-09-01", "toDate": "2026-09-30",
              "asOfDate": "2026-09-30", "timezone": "Europe/Moscow", "currency": "RUB",
              "incomeTotal": "100.00", "expenseTotal": "75.50", "debtPaymentTotal": "0.00",
              "refundTotal": "5.00", "transactionCount": 3, "expenseByCategory": {"food": "75.50"},
              "expenseByDay": {f"2026-09-{day:02d}": "0.00" for day in range(1, 31)},
              "weekendSharePercent": 25, "monthlyBudgetLimit": "20000.00",
              "monthlyBudgetRemaining": "19924.50", "rolling7FoodStatus": None}

    class FakeCore:
        async def get_report(self, token, *, period, month=None, from_date=None, to_date=None, scope="personal"):
            calls.append((period, month, from_date, to_date, scope))
            return report

    async def record_request(_bot, method, *_args, **_kwargs):
        sent.append(method)

    monkeypatch.setattr(Bot, "__call__", record_request)
    monkeypatch.setattr(telegram_app, "render_report", lambda _report: ("text/plain; charset=utf-8", b"report text"))
    message = Message(
        message_id=106,
        date=datetime(2026, 10, 4, tzinfo=timezone.utc),
        chat=Chat(id=42, type="private"),
        from_user=User(id=42, is_bot=False, first_name="Alex"),
        text="/report 2026-09-01 2026-09-30",
        entities=[MessageEntity(type="bot_command", offset=0, length=7)],
    )
    bot = Bot("123456:TEST_TOKEN")
    try:
        dispatcher = build_dispatcher(telegram_core=FakeCore())
        state_key = StorageKey(bot_id=bot.id, chat_id=42, user_id=42)
        asyncio.run(dispatcher.storage.set_data(state_key, {"telegram_actor_context": {
            "token": "opaque-context", "tenantId": "tenant-a", "displayName": "Home", "role": "owner"}}))
        asyncio.run(dispatcher.feed_update(bot, Update(update_id=41, message=message)))
    finally:
        asyncio.run(bot.session.close())

    assert calls == [("custom", None, "2026-09-01", "2026-09-30", "personal")]
    assert len(sent) == 1
    assert sent[0].text == "report text"
    assert sent[0].reply_markup.keyboard[0][0].text == "Меню"


@pytest.mark.parametrize(("arguments", "expected"), [
    ("", {"period": "month", "month": None, "from_date": None, "to_date": None, "scope": "personal"}),
    ("90d family", {"period": "90d", "month": None, "from_date": None, "to_date": None, "scope": "family"}),
    ("2026-09", {"period": "month", "month": "2026-09", "from_date": None, "to_date": None,
                  "scope": "personal"}),
])
def test_report_arguments_cover_server_periods_and_scopes(arguments, expected):
    assert telegram_app._parse_report_request(arguments) == expected


def test_report_arguments_allow_a_366_day_inclusive_window():
    parsed = telegram_app._parse_report_request("2025-10-01 2026-10-01")
    assert parsed["period"] == "custom"
    assert parsed["from_date"] == "2025-10-01"
    assert parsed["to_date"] == "2026-10-01"


@pytest.mark.parametrize("arguments", [
    "2026-13",
    "2026-10-05 2026-10-03",
    "2025-10-01 2026-10-02",
    "week family personal",
])
def test_report_arguments_reject_invalid_windows_or_conflicting_scopes(arguments):
    with pytest.raises(ValueError):
        telegram_app._parse_report_request(arguments)
