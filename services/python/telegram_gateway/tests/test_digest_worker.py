import asyncio
from types import SimpleNamespace

from services.python.telegram_gateway.digest_worker import deliver_notification_claims
from services.python.telegram_gateway.core_client import TelegramCoreError


def _report(transaction_count=1):
    return {
        "period": "custom", "scope": "personal", "fromDate": "2026-10-05", "toDate": "2026-10-05",
        "asOfDate": "2026-10-05", "timezone": "UTC", "currency": "RUB", "incomeTotal": "0.00",
        "expenseTotal": "10.00", "debtPaymentTotal": "0.00", "refundTotal": "0.00",
        "transactionCount": transaction_count, "expenseByCategory": {"food": "10.00"} if transaction_count else {},
        "expenseByDay": {"2026-10-05": "10.00" if transaction_count else "0.00"},
        "weekendSharePercent": None, "monthlyBudgetLimit": None, "monthlyBudgetRemaining": None,
        "rolling7FoodStatus": None,
    }


def _claim(transaction_count=1):
    return {"intentId": "0199b81a-4a9c-7000-8000-000000000001",
            "leaseToken": "0199b81a-4a9c-7000-8000-000000000002", "telegramUserId": 42,
            "digestKind": "daily", "language": "ru", "report": _report(transaction_count)}


def test_delivery_sends_core_report_and_acknowledges_telegram_message():
    class Core:
        def __init__(self):
            self.acks = []

        async def acknowledge_notification_delivery(self, *args, **kwargs):
            self.acks.append((args, kwargs))
            return "delivered"

    class Bot:
        def __init__(self):
            self.sent = []

        async def send_message(self, **kwargs):
            self.sent.append(kwargs)
            return SimpleNamespace(message_id=7788)

    core, bot = Core(), Bot()
    assert asyncio.run(deliver_notification_claims(core, bot, [_claim()])) == 1
    assert bot.sent == [{"chat_id": 42, "text": bot.sent[0]["text"]}]
    assert "Ежедневная сводка" in bot.sent[0]["text"]
    assert core.acks == [(("0199b81a-4a9c-7000-8000-000000000001",
                           "0199b81a-4a9c-7000-8000-000000000002", "delivered"),
                          {"error_code": None, "provider_message_id": "7788"})]


def test_empty_core_report_is_not_sent_and_is_marked_no_data():
    class Core:
        def __init__(self):
            self.acks = []

        async def acknowledge_notification_delivery(self, *args, **kwargs):
            self.acks.append((args, kwargs))
            return "skipped_no_data"

    class Bot:
        sent = []

        async def send_message(self, **kwargs):
            self.sent.append(kwargs)

    core, bot = Core(), Bot()
    assert asyncio.run(deliver_notification_claims(core, bot, [_claim(0)])) == 1
    assert bot.sent == []
    assert core.acks[0][0][2] == "no_data"


def test_empty_financial_report_with_weekly_outcome_sends_and_acknowledges():
    class Core:
        def __init__(self):
            self.acks = []

        async def acknowledge_notification_delivery(self, *args, **kwargs):
            self.acks.append((args, kwargs))
            return "delivered"

    class Bot:
        def __init__(self):
            self.sent = []

        async def send_message(self, **kwargs):
            self.sent.append(kwargs)
            return SimpleNamespace(message_id=99)

    claim = _claim(0)
    claim["digestKind"] = "weekly"
    claim["goalOutcome"] = {
        "id": "0199b81a-4a9c-7000-8000-000000000003", "name": "Coffee", "unit": "count",
        "bought": 3, "countTarget": 4, "spent": None, "monthlyLimit": None,
        "met": False, "completedAt": "2026-10-05T12:00:00Z",
    }
    core, bot = Core(), Bot()

    assert asyncio.run(deliver_notification_claims(core, bot, [claim])) == 1
    assert len(bot.sent) == 1
    assert "Coffee" in bot.sent[0]["text"]
    assert core.acks[0][0][2] == "delivered"


def test_lost_core_ack_does_not_mark_delivery_locally():
    class Core:
        def __init__(self):
            self.acks = 0

        async def acknowledge_notification_delivery(self, *args, **kwargs):
            self.acks += 1
            raise TelegramCoreError("unavailable")

    class Bot:
        def __init__(self):
            self.sent = 0

        async def send_message(self, **kwargs):
            self.sent += 1
            return SimpleNamespace(message_id=101)

    claim = _claim(0)
    claim["digestKind"] = "weekly"
    claim["goalOutcome"] = {
        "id": "0199b81a-4a9c-7000-8000-000000000003", "name": "Coffee", "unit": "count",
        "bought": 3, "countTarget": 4, "spent": None, "monthlyLimit": None,
        "met": False, "completedAt": "2026-10-05T12:00:00Z",
    }
    core, bot = Core(), Bot()

    assert asyncio.run(deliver_notification_claims(core, bot, [claim])) == 0
    assert asyncio.run(deliver_notification_claims(core, bot, [claim])) == 0
    assert core.acks == 2
    assert bot.sent == 2


def test_telegram_transport_failure_is_left_for_durable_retry():
    class Core:
        def __init__(self):
            self.acks = []

        async def acknowledge_notification_delivery(self, *args, **kwargs):
            self.acks.append((args, kwargs))
            return "pending"

    class Bot:
        async def send_message(self, **kwargs):
            raise RuntimeError("temporary transport failure")

    core = Core()
    assert asyncio.run(deliver_notification_claims(core, Bot(), [_claim()])) == 1
    assert core.acks[0][0][2] == "retryable_failure"
    assert core.acks[0][1]["error_code"] == "telegram_transport_error"
