import asyncio

from aiohttp import web
from aiohttp.test_utils import TestServer
import pytest

from services.python.telegram_gateway.core_client import TelegramCoreClient, TelegramCoreError


def test_core_client_sends_code_identity_and_telegram_name_with_service_credential():
    seen = {}

    async def redeem(request):
        seen["token"] = request.headers.get("X-Finance-Service-Token")
        seen["body"] = await request.json()
        return web.json_response({"status": "linked"})

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/link-codes/redeem", redeem)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            return await client.redeem_link_code("ABCD-EFGH-JKLM-NPQR", 42, "Alex Example")

    assert asyncio.run(exercise()) == "linked"
    assert seen == {
        "token": "test-service-secret",
        "body": {"code": "ABCD-EFGH-JKLM-NPQR", "telegramUserId": 42,
                 "telegramDisplayName": "Alex Example"},
    }


def test_core_client_claims_durable_digests_and_acknowledges_delivery():
    seen = []
    report = {
        "period": "custom", "scope": "personal", "fromDate": "2026-10-05", "toDate": "2026-10-05",
        "asOfDate": "2026-10-05", "timezone": "UTC", "currency": "RUB", "incomeTotal": "0.00",
        "expenseTotal": "10.00", "debtPaymentTotal": "0.00", "refundTotal": "0.00",
        "transactionCount": 1, "expenseByCategory": {"food": "10.00"},
        "expenseByDay": {"2026-10-05": "10.00"}, "weekendSharePercent": None,
        "monthlyBudgetLimit": None, "monthlyBudgetRemaining": None,
        "rolling7FoodStatus": None,
    }

    async def claim(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({"items": [{
            "intentId": "0199b81a-4a9c-7000-8000-000000000001", "telegramUserId": 42,
            "digestKind": "daily", "scheduledLocalDate": "2026-10-05", "language": "ru",
            "attemptNumber": 1, "leaseToken": "0199b81a-4a9c-7000-8000-000000000002", "report": report,
        }]})

    async def delivery(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({"state": "delivered"})

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/notifications/claim", claim)
        app.router.add_post("/internal/v1/telegram/notifications/{intent_id}/delivery", delivery)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "notification-service-secret")
            items = await client.claim_notification_deliveries(10)
            result = await client.acknowledge_notification_delivery(
                items[0]["intentId"], items[0]["leaseToken"], "delivered", provider_message_id="7788")
            return items, result

    items, result = asyncio.run(exercise())
    assert items[0]["telegramUserId"] == 42
    assert items[0]["report"] == report
    assert result == "delivered"
    assert seen == [
        ("/internal/v1/telegram/notifications/claim", "notification-service-secret", {"limit": 10}),
        ("/internal/v1/telegram/notifications/0199b81a-4a9c-7000-8000-000000000001/delivery",
         "notification-service-secret", {"leaseToken": "0199b81a-4a9c-7000-8000-000000000002",
                                        "outcome": "delivered", "errorCode": None,
                                        "providerMessageId": "7788"}),
    ]


def test_core_client_lists_tenants_and_issues_then_resolves_scoped_actor_context():
    seen = []

    async def tenants(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({"tenants": [{"tenantId": "tenant-a", "displayName": "Home", "role": "owner"}]})

    async def issue(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({
            "token": "opaque-actor-context",
            "tenantId": "tenant-a",
            "displayName": "Home",
            "role": "owner",
            "permissions": ["transaction.read", "transaction.write.any"],
            "expiresAt": "2026-10-03T20:00:00Z",
        }, status=201)

    async def resolve(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({
            "tenantId": "tenant-a",
            "displayName": "Home",
            "role": "owner",
            "permissions": ["transaction.read", "transaction.write.any"],
            "expiresAt": "2026-10-03T20:00:00Z",
        })

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/tenants", tenants)
        app.router.add_post("/internal/v1/telegram/actor-contexts", issue)
        app.router.add_post("/internal/v1/telegram/actor-contexts/resolve", resolve)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            listed = await client.list_telegram_tenants(42, "  Alex   Example  ")
            issued = await client.issue_actor_context(42, "tenant-a")
            resolved = await client.resolve_actor_context(issued["token"])
            return listed, issued, resolved

    listed, issued, resolved = asyncio.run(exercise())
    assert listed == [{"tenantId": "tenant-a", "displayName": "Home", "role": "owner"}]
    assert issued["token"] == "opaque-actor-context"
    assert resolved["tenantId"] == "tenant-a"
    assert seen == [
        ("/internal/v1/telegram/tenants", "test-service-secret",
         {"telegramUserId": 42, "telegramDisplayName": "Alex Example"}),
        ("/internal/v1/telegram/actor-contexts", "test-service-secret",
         {"telegramUserId": 42, "tenantId": "tenant-a"}),
        ("/internal/v1/telegram/actor-contexts/resolve", "test-service-secret",
         {"token": "opaque-actor-context"}),
    ]
    assert all("userId" not in body for _, _, body in seen)


def test_core_client_requests_financial_summary_using_only_actor_context():
    seen = {}

    async def summary(request):
        seen["token"] = request.headers.get("X-Finance-Service-Token")
        seen["body"] = await request.json()
        return web.json_response({"month": "2026-10", "currency": "RUB", "incomeTotal": "0.00",
                                  "expenseTotal": "12.34", "transactionCount": 1})

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/summary", summary)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            return await client.get_dashboard_summary("opaque-actor-context")

    result = asyncio.run(exercise())
    assert result["expenseTotal"] == "12.34"
    assert seen == {
        "token": "test-service-secret",
        "body": {"token": "opaque-actor-context"},
    }


def test_core_client_preserves_server_cash_and_weekly_budget_guidance():
    safe_to_spend = {
        "incomeBasis": "actual_income", "incomeBase": "120000.00", "month": "2026-10",
        "horizonDate": "2026-10-31", "daysRemaining": 28, "monthlyExpenses": "40000.00",
        "reserve": "12000.00", "promisedPayments": "5000.00", "safeTotal": "63000.00",
        "safePerDay": "2250.00",
    }
    rolling = {
        "fromDate": "2026-09-28", "toDate": "2026-10-04", "limit": "3000.00",
        "spent": "2500.00", "remaining": "500.00", "limitStatus": "near",
        "usualWeeklySpend": "2700.00", "historyWeeks": 5, "paceStatus": "normal", "paceShare": "92.59",
    }

    async def summary(request):
        assert await request.json() == {"token": "opaque-actor-context"}
        return web.json_response({"month": "2026-10", "currency": "RUB", "incomeTotal": "120000.00",
                                 "expenseTotal": "40000.00", "transactionCount": 4,
                                 "safeToSpend": safe_to_spend, "rolling7FoodStatus": rolling})

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/summary", summary)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            return await client.get_dashboard_summary("opaque-actor-context")

    result = asyncio.run(exercise())
    assert result["safeToSpend"] == safe_to_spend
    assert result["rolling7FoodStatus"] == rolling


def test_core_client_loads_actor_scoped_budget_overview():
    seen = {}
    overview = {
        "currency": "RUB", "month": "2026-10", "familyLimits": {"еда": "20000.00"},
        "personalOverrides": {"еда": "15000.00"}, "effectiveLimits": {"еда": "15000.00"},
        "monthlySpent": {"еда": "14000.00"}, "limitStatus": {"еда": "near"},
        "familyVersions": {"еда": 2}, "personalVersions": {"еда": 1},
        "familyTotalLimit": "55000.00", "personalTotalOverride": "50000.00",
        "effectiveTotalLimit": "50000.00", "totalMonthlySpent": "45000.00", "totalLimitStatus": "near",
        "familyTotalVersion": 1, "personalTotalVersion": 1,
        "familyRolling7FoodVersion": 0, "personalRolling7FoodVersion": 1,
        "rolling7FoodStatus": {"fromDate": "2026-09-28", "toDate": "2026-10-04", "limit": "3000.00",
                                "spent": "2500.00", "remaining": "500.00", "limitStatus": "near",
                                "usualWeeklySpend": "2700.00", "historyWeeks": 5,
                                "paceStatus": "normal", "paceShare": "92.59"},
    }

    async def budgets(request):
        seen["token"] = request.headers.get("X-Finance-Service-Token")
        seen["body"] = await request.json()
        return web.json_response(overview)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/budgets", budgets)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            return await client.get_budget_overview("opaque-actor-context")

    result = asyncio.run(exercise())
    assert result["effectiveLimits"] == {"еда": "15000.00"}
    assert result["totalLimitStatus"] == "near"
    assert result["personalVersions"] == {"еда": 1}
    assert seen == {"token": "test-service-secret", "body": {"token": "opaque-actor-context"}}


def test_core_client_rejects_malformed_budget_overview():
    async def budgets(_request):
        return web.json_response({"currency": "RUB", "effectiveTotalLimit": "NaN"})

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/budgets", budgets)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            return await client.get_budget_overview("opaque-actor-context")

    with pytest.raises(TelegramCoreError):
        asyncio.run(exercise())


def test_core_client_updates_budget_with_actor_scope_version_and_idempotency():
    seen = {}
    overview = {
        "currency": "RUB", "month": "2026-10", "familyLimits": {"еда": "20000.00"},
        "personalOverrides": {"еда": "5000.00"}, "effectiveLimits": {"еда": "5000.00"},
        "monthlySpent": {"еда": "0.00"}, "limitStatus": {"еда": "normal"},
        "familyVersions": {"еда": 2}, "personalVersions": {"еда": 2},
        "familyTotalLimit": "55000.00", "personalTotalOverride": None, "effectiveTotalLimit": "55000.00",
        "totalMonthlySpent": "0.00", "totalLimitStatus": "normal", "familyTotalVersion": 1,
        "personalTotalVersion": 0, "familyRolling7FoodVersion": 0, "personalRolling7FoodVersion": 0,
        "rolling7FoodStatus": None,
    }

    async def update(request):
        seen["path"] = request.path
        seen["token"] = request.headers.get("X-Finance-Service-Token")
        seen["body"] = await request.json()
        return web.json_response(overview)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/budgets/{key}/update", update)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            return await client.update_telegram_budget(
                "opaque-actor-context", "tg-budget-42-108", "еда", "personal", "5000.00", 1)

    result = asyncio.run(exercise())
    assert result["personalOverrides"] == {"еда": "5000.00"}
    assert seen == {
        "path": "/internal/v1/telegram/budgets/еда/update",
        "token": "test-service-secret",
        "body": {"token": "opaque-actor-context", "idempotencyKey": "tg-budget-42-108",
                 "scope": "personal", "amount": "5000.00", "version": 1, "period": "monthly"},
    }


def test_core_client_resets_personal_budget_overrides_using_actor_context():
    seen = {}
    overview = {
        "currency": "RUB", "month": "2026-10", "familyLimits": {}, "personalOverrides": {},
        "effectiveLimits": {}, "monthlySpent": {}, "limitStatus": {}, "familyVersions": {},
        "personalVersions": {}, "familyTotalLimit": "55000.00", "personalTotalOverride": None,
        "effectiveTotalLimit": "55000.00", "totalMonthlySpent": "0.00", "totalLimitStatus": "normal",
        "familyTotalVersion": 1, "personalTotalVersion": 2, "familyRolling7FoodVersion": 0,
        "personalRolling7FoodVersion": 2, "rolling7FoodStatus": None,
    }

    async def reset(request):
        seen["token"] = request.headers.get("X-Finance-Service-Token")
        seen["body"] = await request.json()
        return web.json_response(overview)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/budgets/reset", reset)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            return await client.reset_telegram_budget("opaque-actor-context", "tg-budget-reset-42-109")

    result = asyncio.run(exercise())
    assert result["personalOverrides"] == {}
    assert seen == {"token": "test-service-secret",
                    "body": {"token": "opaque-actor-context", "idempotencyKey": "tg-budget-reset-42-109"}}


def test_core_client_creates_confirms_and_cancels_transaction_drafts_with_actor_context():
    seen = []
    draft_id = "9b199adb-4408-4b6b-a4a7-0de897b05ea4"

    async def create(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({"id": draft_id, "tenantId": "tenant-a", "type": "expense",
                                  "amount": "2000.00", "currency": "RUB", "categoryCode": "transport",
                                  "subcategoryCode": None, "description": "Такси", "state": "pending",
                                  "occurredAt": "2026-10-03T10:00:00Z", "debtId": None,
                                  "version": 1, "provider": "ollama", "modelVersion": "qwen-test",
                                  "promptVersion": "transaction-draft.v1"}, status=201)

    async def confirm(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({"id": "transaction-1", "tenantId": "tenant-a", "type": "expense",
                                  "amount": "2000.00", "budgetAlerts": [{
                                      "budgetKey": "transport", "threshold": "near",
                                      "limit": "5000.00", "spent": "4500.00"}]})

    async def update_amount(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({"id": draft_id, "tenantId": "tenant-a", "type": "expense",
                                  "amount": "2500.00", "currency": "RUB", "categoryCode": "transport",
                                  "subcategoryCode": None, "description": "Такси", "state": "pending",
                                  "occurredAt": "2026-10-03T10:00:00Z", "debtId": None,
                                  "version": 2, "provider": "ollama", "modelVersion": "qwen-test",
                                  "promptVersion": "transaction-draft.v1"})

    async def cancel(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({"status": "cancelled"})

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/transaction-drafts", create)
        app.router.add_post("/internal/v1/telegram/transaction-drafts/{draft_id}/amount", update_amount)
        app.router.add_post("/internal/v1/telegram/transaction-drafts/{draft_id}/confirm", confirm)
        app.router.add_post("/internal/v1/telegram/transaction-drafts/{draft_id}/cancel", cancel)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            draft = await client.create_transaction_draft("opaque-context", "tg-add-42-100", "Такси 2 тыс")
            updated = await client.update_transaction_draft_amount("opaque-context", draft_id, 1, "2500.00")
            transaction = await client.confirm_transaction_draft("opaque-context", draft_id,
                                                                  "tg-confirm-" + draft_id, 2)
            cancelled = await client.cancel_transaction_draft("opaque-context", draft_id, 1)
            return draft, updated, transaction, cancelled

    draft, updated, transaction, cancelled = asyncio.run(exercise())
    assert draft["amount"] == "2000.00"
    assert draft["version"] == 1
    assert updated["amount"] == "2500.00"
    assert updated["version"] == 2
    assert transaction["id"] == "transaction-1"
    assert transaction["budgetAlerts"] == [{"budgetKey": "transport", "threshold": "near",
                                             "limit": "5000.00", "spent": "4500.00"}]
    assert cancelled is True
    assert [row[0] for row in seen] == [
        "/internal/v1/telegram/transaction-drafts",
        f"/internal/v1/telegram/transaction-drafts/{draft_id}/amount",
        f"/internal/v1/telegram/transaction-drafts/{draft_id}/confirm",
        f"/internal/v1/telegram/transaction-drafts/{draft_id}/cancel",
    ]
    assert all(row[1] == "test-service-secret" for row in seen)
    assert [row[2] for row in seen] == [
        {"token": "opaque-context", "idempotencyKey": "tg-add-42-100", "text": "Такси 2 тыс"},
        {"token": "opaque-context", "version": 1, "amount": "2500.00"},
        {"token": "opaque-context", "idempotencyKey": "tg-confirm-" + draft_id, "version": 2},
        {"token": "opaque-context", "version": 1},
    ]
    assert all("userId" not in row[2] and "tenantId" not in row[2] for row in seen)


def test_core_client_requests_the_actor_scoped_report_with_period_and_scope():
    seen = {}
    report = {
        "period": "custom", "scope": "family", "fromDate": "2026-09-01", "toDate": "2026-09-30",
        "asOfDate": "2026-09-30", "timezone": "Europe/Moscow", "currency": "RUB",
        "incomeTotal": "100.00", "expenseTotal": "75.50", "debtPaymentTotal": "0.00",
        "refundTotal": "5.00", "transactionCount": 3, "expenseByCategory": {"food": "75.50"},
        "expenseByDay": {"2026-09-01": "0.00"}, "weekendSharePercent": 25,
        "monthlyBudgetLimit": "20000.00", "monthlyBudgetRemaining": "19924.50",
        "rolling7FoodStatus": None,
    }

    async def get_report(request):
        seen["path"] = request.path
        seen["token"] = request.headers.get("X-Finance-Service-Token")
        seen["body"] = await request.json()
        return web.json_response(report)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/report", get_report)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            return await client.get_report("opaque-context", period="custom", from_date="2026-09-01",
                                           to_date="2026-09-30", scope="family")

    assert asyncio.run(exercise()) == report
    assert seen == {
        "path": "/internal/v1/telegram/report",
        "token": "test-service-secret",
        "body": {"token": "opaque-context", "period": "custom", "month": None,
                 "from": "2026-09-01", "to": "2026-09-30", "scope": "family"},
    }


def test_core_client_requests_member_product_catalog_with_actor_token_only():
    seen = {}
    catalog = {"mode": "search", "query": "tea", "products": [product_card()]}

    async def get_catalog(request):
        seen["path"] = request.path
        seen["token"] = request.headers.get("X-Finance-Service-Token")
        seen["body"] = await request.json()
        return web.json_response(catalog)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/products/catalog", get_catalog)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "telegram-service-secret")
            return await client.get_product_catalog("opaque-context", " tea ")

    result = asyncio.run(exercise())
    assert result["products"][0]["cheapestMerchant"] == "Market A"
    assert seen == {
        "path": "/internal/v1/telegram/products/catalog", "token": "telegram-service-secret",
        "body": {"token": "opaque-context", "query": "tea"},
    }


def test_core_client_requests_shopping_with_actor_token_only_and_validates_no_inventory():
    seen = {}
    shopping = {"candidates": [{"productName": "Milk Fresh 1l", "purchaseCount": 3,
                "productKey": "freshmilk",
                "medianIntervalDays": 10, "usualUnitPrice": "100.000000", "estimatedCost": "100.00",
                "lastPurchasedAt": "2026-10-04T00:00:00Z", "dueAt": "2026-10-05T00:00:00Z",
                "daysUntilDue": 0}], "estimatedListCost": "100.00", "inventoryTracked": False,
                "boughtCandidates": [], "mutedCandidates": [], "blockedCandidates": []}

    async def get_shopping(request):
        seen["path"] = request.path
        seen["token"] = request.headers.get("X-Finance-Service-Token")
        seen["body"] = await request.json()
        return web.json_response(shopping)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/shopping", get_shopping)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "telegram-service-secret")
            return await client.get_shopping_candidates("opaque-context")

    result = asyncio.run(exercise())
    assert result["estimatedListCost"] == "100.00"
    assert seen == {
        "path": "/internal/v1/telegram/shopping", "token": "telegram-service-secret",
        "body": {"token": "opaque-context"},
    }


def test_core_client_rejects_shopping_candidates_that_claim_inventory_or_have_fewer_than_three_purchases():
    valid = {"candidates": [{"productName": "Milk Fresh 1l", "purchaseCount": 3,
             "productKey": "freshmilk",
             "medianIntervalDays": 10, "usualUnitPrice": "100.000000", "estimatedCost": "100.00",
             "lastPurchasedAt": "2026-10-04T00:00:00Z", "dueAt": "2026-10-05T00:00:00Z",
             "daysUntilDue": 0}], "estimatedListCost": "100.00", "inventoryTracked": False,
             "boughtCandidates": [], "mutedCandidates": [], "blockedCandidates": []}

    async def exercise(invalid):
        async def get_shopping(_request):
            return web.json_response(invalid)

        app = web.Application()
        app.router.add_post("/internal/v1/telegram/shopping", get_shopping)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "telegram-service-secret")
            await client.get_shopping_candidates("opaque-context")

    for invalid in ({**valid, "inventoryTracked": True},
                    {**valid, "candidates": [{**valid["candidates"][0], "purchaseCount": 2}]}):
        try:
            asyncio.run(exercise(invalid))
        except TelegramCoreError as error:
            assert error.code == "unavailable"
        else:
            raise AssertionError("invalid shopping candidates were accepted")


def test_shopping_decision_actions_keep_actor_token_and_validate_returned_lists():
    valid = {"candidates": [], "estimatedListCost": "0.00", "inventoryTracked": False,
             "boughtCandidates": [], "mutedCandidates": [], "blockedCandidates": []}
    seen = []

    async def action(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response(valid)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/shopping/freshmilk/bought", action)
        app.router.add_post("/internal/v1/telegram/shopping/freshmilk/mute", action)
        app.router.add_post("/internal/v1/telegram/shopping/freshmilk/unmute", action)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "telegram-service-secret")
            results = [await client.mark_shopping_bought("opaque-context", "freshmilk"),
                       await client.mute_shopping_suggestion("opaque-context", "freshmilk"),
                       await client.unmute_shopping_suggestion("opaque-context", "freshmilk")]
            return results

    results = asyncio.run(exercise())
    assert results == [valid, valid, valid]
    assert seen == [(f"/internal/v1/telegram/shopping/freshmilk/{action}", "telegram-service-secret",
                     {"token": "opaque-context"}) for action in ("bought", "mute", "unmute")]


def test_shopping_validation_requires_member_decision_sections_and_explained_blocks():
    from services.python.telegram_gateway.core_client import TelegramCoreClient

    valid = {"candidates": [], "estimatedListCost": "0.00", "inventoryTracked": False,
             "boughtCandidates": [], "mutedCandidates": [], "blockedCandidates": [
                 {"productKey": "freshmilk", "productName": "Milk Fresh 1l", "reasonCode": "confirmed_not_to_buy"}]}
    assert TelegramCoreClient._validated_shopping_candidates(valid)["blockedCandidates"] == valid["blockedCandidates"]
    invalid = {**valid, "blockedCandidates": [{**valid["blockedCandidates"][0], "reasonCode": "unknown"}]}
    try:
        TelegramCoreClient._validated_shopping_candidates(invalid)
    except TelegramCoreError as error:
        assert error.code == "unavailable"
    else:
        raise AssertionError("an unexplained shopping block was accepted")


def test_core_client_rejects_fabricated_product_baseline_without_history():
    invalid = product_card()
    invalid.update(purchaseCount=1, hasBaseline=False, baselineUnitPrice="0.000000",
                   change=None, relative=None, priorPurchases=0, signal=False, direction=None,
                   chartAvailable=False, history=[invalid["history"][0]])

    async def get_catalog(_request):
        return web.json_response({"mode": "search", "query": "tea", "products": [invalid]})

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/products/catalog", get_catalog)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "telegram-service-secret")
            await client.get_product_catalog("opaque-context", "tea")

    try:
        asyncio.run(exercise())
    except TelegramCoreError as error:
        assert error.code == "unavailable"
    else:
        raise AssertionError("product response with a fabricated baseline was accepted")


def test_core_client_requests_scoped_personal_inflation_and_validates_no_history():
    seen = []
    response = {"available": False, "reasonCode": "insufficient_history", "asOf": "2026-10-06T12:00:00Z",
                "windowDays": 90, "productCount": 0, "basketBefore": None, "basketNow": None,
                "indexPercent": None, "rising": [], "falling": []}

    async def inflation(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response(response)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/actions/personal-inflation", inflation)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "inflation-service-secret")
            return await client.get_personal_inflation("opaque-actor-context")

    result = asyncio.run(exercise())
    assert result == response
    assert seen == [("/internal/v1/telegram/actions/personal-inflation", "inflation-service-secret",
                     {"token": "opaque-actor-context"})]


def test_core_client_rejects_personal_inflation_with_fake_totals_or_too_few_products():
    no_history = {"available": False, "reasonCode": "insufficient_history", "asOf": "2026-10-06T12:00:00Z",
                  "windowDays": 90, "productCount": 0, "basketBefore": None, "basketNow": None,
                  "indexPercent": None, "rising": [], "falling": []}
    invalid = [dict(no_history, basketBefore="0.00"),
               dict(no_history, available=True, reasonCode="available", productCount=2,
                    basketBefore="100.00", basketNow="100.00", indexPercent="0.00")]
    for body in invalid:
        with pytest.raises(TelegramCoreError):
            TelegramCoreClient._validated_personal_inflation(body)


def test_core_client_validates_personal_inflation_product_scope_and_direction():
    from services.python.telegram_gateway.core_client import TelegramCoreClient

    body = {
        "available": True,
        "reasonCode": "available",
        "asOf": "2026-10-06T12:00:00Z",
        "windowDays": 90,
        "productCount": 3,
        "basketBefore": "1250.00",
        "basketNow": "1275.00",
        "indexPercent": "2.00",
        "rising": [{"productName": "Coffee", "oldUnitPrice": "100.00", "newUnitPrice": "110.00",
                    "oldSpendWeight": "500.00", "changePercent": "10.00",
                    "olderPurchaseCount": 2, "windowPurchaseCount": 1}],
        "falling": [{"productName": "Milk", "oldUnitPrice": "200.00", "newUnitPrice": "180.00",
                     "oldSpendWeight": "700.00", "changePercent": "-10.00",
                     "olderPurchaseCount": 3, "windowPurchaseCount": 2}],
    }

    assert TelegramCoreClient._validated_personal_inflation(body) == body

    invalid = {**body, "falling": [{**body["falling"][0], "productName": "coffee"}]}
    with pytest.raises(TelegramCoreError):
        TelegramCoreClient._validated_personal_inflation(invalid)


def test_core_client_requests_and_validates_empty_recurring_projection():
    seen = []
    response = {"algorithmVersion": "recurring.v1", "completeness": "complete", "timeZone": "Europe/Moscow",
                "asOf": "2026-10-06T00:00:00+03:00", "expenseSeries": [], "incomeSeries": [],
                "dueSoon": [], "overdue": [], "nextIncome": None, "monthlyExpenseEstimate": None,
                "monthlyExpenseEstimates": {}, "mutedSeries": []}

    async def get_recurring(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response(response)

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/actions/recurring", get_recurring)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "telegram-service-secret")
            return await client.get_recurring_projection("opaque-actor-context")

    assert asyncio.run(exercise()) == response
    assert seen == [("/internal/v1/telegram/actions/recurring", "telegram-service-secret",
                     {"token": "opaque-actor-context"})]


def test_core_client_validates_recurring_warning_scope_and_monthly_total():
    from services.python.telegram_gateway.core_client import TelegramCoreClient

    due = recurring_series("1", "Phone plan", "2026-08-15", "2026-08-22", 2)
    old = recurring_series("2", "Old payment", "2026-08-06", "2026-08-13", -7)
    muted = recurring_series("3", "Muted payment", "2026-08-15", "2026-08-22", 2)
    body = {"algorithmVersion": "recurring.v1", "completeness": "complete", "timeZone": "Europe/Moscow",
            "asOf": "2026-08-20T00:00:00+03:00", "expenseSeries": [due, old], "incomeSeries": [],
            "dueSoon": [due], "overdue": [old], "nextIncome": None, "monthlyExpenseEstimate": "857.14",
            "monthlyExpenseEstimates": {"RUB": "857.14"}, "mutedSeries": [muted]}

    assert TelegramCoreClient._validated_recurring_projection(body) == body
    with pytest.raises(TelegramCoreError):
        TelegramCoreClient._validated_recurring_projection({**body, "dueSoon": [old]})
    with pytest.raises(TelegramCoreError):
        TelegramCoreClient._validated_recurring_projection({**body, "monthlyExpenseEstimate": "999.00"})
    missing_muted = {key: value for key, value in body.items() if key != "mutedSeries"}
    with pytest.raises(TelegramCoreError):
        TelegramCoreClient._validated_recurring_projection(missing_muted)
    with pytest.raises(TelegramCoreError):
        TelegramCoreClient._validated_recurring_projection({**body, "mutedSeries": [due]})


def test_core_client_posts_recurring_mute_action_with_actor_context():
    seen = []
    series_id = "a" * 32

    async def mute(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({"algorithmVersion": "recurring.v1", "completeness": "complete",
            "timeZone": "UTC", "asOf": "2026-10-06T00:00:00Z", "expenseSeries": [], "incomeSeries": [],
            "dueSoon": [], "overdue": [], "nextIncome": None, "monthlyExpenseEstimate": None,
            "monthlyExpenseEstimates": {}, "mutedSeries": []})

    async def exercise():
        app = web.Application()
        app.router.add_post(f"/internal/v1/telegram/actions/recurring/{series_id}/mute", mute)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "telegram-service-secret")
            return await client.mute_recurring_series("opaque-actor-context", series_id)

    assert asyncio.run(exercise())["mutedSeries"] == []
    assert seen == [(f"/internal/v1/telegram/actions/recurring/{series_id}/mute", "telegram-service-secret",
                     {"token": "opaque-actor-context"})]


def recurring_series(identifier, name, last, next_date, days):
    return {"id": identifier * 32, "key": name.lower(), "name": name, "category": "services", "type": "expense",
            "currency": "RUB", "amount": "100.00", "minAmount": "100.00", "maxAmount": "100.00",
            "periodCode": "week", "periodDays": 7, "minIntervalDays": 7, "maxIntervalDays": 7,
            "occurrences": 3, "lastDate": last, "nextDate": next_date, "daysUntil": days}
    invalid = {**body, "rising": [{**body["rising"][0], "changePercent": "-10.00"}]}
    with pytest.raises(TelegramCoreError):
        TelegramCoreClient._validated_personal_inflation(invalid)


def product_card():
    history = [
        {"receiptId": "r1", "itemId": "i1", "purchasedAt": "2026-09-01T10:00:00Z", "merchant": "Market A",
         "name": "Tea Green 500g", "unitPrice": "100.000000", "current": False},
        {"receiptId": "r2", "itemId": "i2", "purchasedAt": "2026-09-10T10:00:00Z", "merchant": "Market B",
         "name": "Tea Green 500g", "unitPrice": "120.000000", "current": False},
    ]
    return {"productName": "Tea Green 500g", "purchaseCount": 2, "usualUnitPrice": "110.000000",
            "hasBaseline": True, "baselineUnitPrice": "100.000000", "lastUnitPrice": "120.000000",
            "lastPurchasedAt": "2026-09-10T10:00:00Z", "lastMerchant": "Market B",
            "cheapestUnitPrice": "100.000000", "cheapestMerchant": "Market A", "totalSpent": "220.00",
            "change": "20.000000", "relative": "0.200000", "signal": True, "direction": "up",
            "priorPurchases": 1, "chartAvailable": True, "history": history}


def test_core_client_reads_and_edits_owned_draft_with_server_version():
    seen = []
    draft_id = "9b199adb-4408-4b6b-a4a7-0de897b05ea4"

    async def read(request):
        seen.append((request.path, await request.json()))
        return web.json_response({"id": draft_id, "tenantId": "tenant-a", "type": "expense",
                                  "amount": "2000.00", "currency": "RUB", "categoryCode": "transport",
                                  "subcategoryCode": None, "description": "Такси", "occurredAt": "2026-10-03T10:00:00Z",
                                  "debtId": None, "state": "pending", "version": 1, "provider": "ollama",
                                  "modelVersion": "qwen-test", "promptVersion": "transaction-draft.v1"})

    async def edit(request):
        seen.append((request.path, await request.json()))
        return web.json_response({"id": draft_id, "tenantId": "tenant-a", "type": "expense",
                                  "amount": "2000.00", "currency": "RUB", "categoryCode": "food",
                                  "subcategoryCode": None, "description": "Такси", "occurredAt": "2026-10-03T10:00:00Z",
                                  "debtId": None, "state": "pending", "version": 2, "provider": "ollama",
                                  "modelVersion": "qwen-test", "promptVersion": "transaction-draft.v1"})

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/transaction-drafts/{draft_id}/read", read)
        app.router.add_post("/internal/v1/telegram/transaction-drafts/{draft_id}/edit", edit)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            draft = await client.get_transaction_draft("opaque-context", draft_id)
            edited = {**draft, "categoryCode": "food"}
            updated = await client.update_transaction_draft("opaque-context", draft_id, 1, edited)
            return draft, updated

    draft, updated = asyncio.run(exercise())
    assert draft["categoryCode"] == "transport"
    assert updated["categoryCode"] == "food"
    assert updated["version"] == 2
    assert seen == [
        (f"/internal/v1/telegram/transaction-drafts/{draft_id}/read", {"token": "opaque-context"}),
        (f"/internal/v1/telegram/transaction-drafts/{draft_id}/edit", {
            "token": "opaque-context", "version": 1, "type": "expense", "amount": "2000.00",
            "currency": "RUB", "categoryCode": "food", "subcategoryCode": None, "description": "Такси",
            "occurredAt": "2026-10-03T10:00:00Z", "debtId": None,
        }),
    ]
    assert all("userId" not in body and "tenantId" not in body for _, body in seen)


@pytest.mark.parametrize(("status", "expected"), [(401, "unauthorized"), (403, "forbidden"),
                                                     (404, "not_found"), (429, "rate_limited"), (503, "unavailable")])
def test_actor_context_client_maps_errors_without_forwarding_core_details(status, expected):
    async def reject(_request):
        return web.Response(status=status, text="database secret must not reach Telegram")

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/actor-contexts/resolve", reject)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            with pytest.raises(TelegramCoreError) as error:
                await client.resolve_actor_context("opaque-context")
            return error.value.code, str(error.value)

    code, message = asyncio.run(exercise())
    assert code == expected
    assert "database secret" not in message


@pytest.mark.parametrize(("status", "expected"), [(400, "invalid_code"), (409, "already_linked"),
                                                       (429, "rate_limited"), (503, "unavailable")])
def test_core_client_maps_failures_without_forwarding_core_details(status, expected):
    async def reject(_request):
        return web.Response(status=status, text="database secret must not reach Telegram")

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/link-codes/redeem", reject)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            with pytest.raises(TelegramCoreError) as error:
                await client.redeem_link_code("ABCD-EFGH-JKLM-NPQR", 42)
            return error.value.code, str(error.value)

    code, message = asyncio.run(exercise())
    assert code == expected
    assert "database secret" not in message


def test_core_client_lists_only_core_scoped_debts_for_actor_context():
    seen = {}

    async def debts(request):
        seen["body"] = await request.json()
        seen["token"] = request.headers.get("X-Finance-Service-Token")
        return web.json_response({"debts": [{"id": "debt-1", "name": "Loan",
                                             "tenantId": "tenant-a", "openingBalance": "10000.00",
                                             "currentBalance": "8500.00", "interestRate": "25.00",
                                             "minimumPayment": "500.00", "status": "open", "version": 3}]})

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/debts", debts)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            return await client.list_telegram_debts("opaque-context")

    assert asyncio.run(exercise()) == [{"id": "debt-1", "tenantId": "tenant-a", "name": "Loan",
                                        "openingBalance": "10000.00", "currentBalance": "8500.00",
                                        "interestRate": "25.00", "minimumPayment": "500.00",
                                        "status": "open", "version": 3}]
    assert seen == {"body": {"token": "opaque-context"}, "token": "test-service-secret"}


def test_core_client_lists_recent_transactions_repeats_as_draft_and_voids_latest():
    seen = []
    transaction_id = "9b199adb-4408-4b6b-a4a7-0de897b05ea4"
    draft_id = "d0b84aa5-e28a-4ad1-873b-34b4da7b7b4f"
    draft = {"id": draft_id, "tenantId": "a8d413a8-c15d-4a9e-82c6-2d060d991a0a", "type": "expense",
             "amount": "25.50", "currency": "RUB", "categoryCode": "food", "subcategoryCode": "lunch",
             "description": "Lunch", "occurredAt": "2026-10-03T10:00:00Z", "debtId": None,
             "state": "pending", "version": 1, "provider": "manual", "modelVersion": "repeat",
             "promptVersion": "transaction-repeat.v1"}

    async def history(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({"transactions": [{
            "id": transaction_id, "tenantId": "a8d413a8-c15d-4a9e-82c6-2d060d991a0a", "type": "expense",
            "amount": "25.50", "currency": "RUB", "categoryCode": "food", "subcategoryCode": "lunch",
            "description": "Lunch", "source": "manual", "occurredAt": "2026-10-03T10:00:00Z",
            "status": "posted", "version": 1, "createdAt": "2026-10-03T10:00:01Z",
        }]})

    async def repeat(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response(draft, status=201)

    async def undo(request):
        seen.append((request.path, request.headers.get("X-Finance-Service-Token"), await request.json()))
        return web.json_response({"id": transaction_id, "status": "voided", "amount": "25.50",
                                  "type": "expense"})

    async def exercise():
        app = web.Application()
        app.router.add_post("/internal/v1/telegram/transactions", history)
        app.router.add_post("/internal/v1/telegram/transaction-drafts/repeat", repeat)
        app.router.add_post("/internal/v1/telegram/transactions/latest/void", undo)
        async with TestServer(app) as server:
            client = TelegramCoreClient(str(server.make_url("")), "test-service-secret")
            transactions = await client.list_telegram_transactions("opaque-context", 8)
            repeated = await client.repeat_telegram_transaction("opaque-context", "tg-repeat-42-callback", transaction_id)
            undone = await client.void_latest_telegram_transaction("opaque-context", "tg-void-42-callback",
                                                                   transaction_id, 1)
            return transactions, repeated, undone

    transactions, repeated, undone = asyncio.run(exercise())
    assert transactions[0]["id"] == transaction_id
    assert repeated["promptVersion"] == "transaction-repeat.v1"
    assert undone == {"id": transaction_id, "status": "voided", "amount": "25.50", "type": "expense"}
    assert [row[0] for row in seen] == [
        "/internal/v1/telegram/transactions",
        "/internal/v1/telegram/transaction-drafts/repeat",
        "/internal/v1/telegram/transactions/latest/void",
    ]
    assert [row[2] for row in seen] == [
        {"token": "opaque-context", "limit": 8},
        {"token": "opaque-context", "idempotencyKey": "tg-repeat-42-callback", "transactionId": transaction_id},
        {"token": "opaque-context", "idempotencyKey": "tg-void-42-callback",
         "transactionId": transaction_id, "version": 1},
    ]
    assert all("userId" not in row[2] and "tenantId" not in row[2] for row in seen)
