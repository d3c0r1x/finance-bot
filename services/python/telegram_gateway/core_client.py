"""Narrow authenticated client for Telegram identity-link redemption."""

from __future__ import annotations

import uuid
import re
from datetime import date, datetime, time, timedelta
from decimal import Decimal, InvalidOperation, ROUND_HALF_EVEN, ROUND_HALF_UP
from urllib.parse import quote
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from aiohttp import ClientError, ClientSession, ClientTimeout

_MONEY = re.compile(r"-?\d{1,18}(?:\.\d{1,2})?\Z")
_MONTH = re.compile(r"\d{4}-\d{2}\Z")
_BUDGET_ALERT_AMOUNT = re.compile(r"(?:0|[1-9]\d{0,17})\.\d{2}\Z")
_PRODUCT_UNIT_PRICE = re.compile(r"-?\d{1,30}\.\d{6}\Z")
_PRODUCT_TOTAL = re.compile(r"\d{1,30}\.\d{2}\Z")
_PRODUCT_KEY = re.compile(r"[a-zа-я0-9]{1,256}\Z")
_PERSONAL_INFLATION_MONEY = re.compile(r"(?:0|[1-9]\d{0,29})\.\d{2}\Z")
_PERSONAL_INFLATION_PERCENT = re.compile(r"-?(?:0|[1-9]\d{0,29})\.\d{2}\Z")
_BUDGET_STATES = {"disabled", "normal", "near", "exceeded"}


class TelegramCoreError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def _safe_telegram_display_name(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = " ".join(value.split())[:120].rstrip()
    return normalized or None


class TelegramCoreClient:
    def __init__(self, base_url: str, service_token: str):
        self.base_url = base_url.strip().rstrip("/")
        self.service_token = service_token.strip()

    async def redeem_link_code(self, code: str, telegram_user_id: int,
                               telegram_display_name: str | None = None) -> str:
        payload = {
            "code": code,
            "telegramUserId": telegram_user_id,
        }
        display_name = _safe_telegram_display_name(telegram_display_name)
        if display_name is not None:
            payload["telegramDisplayName"] = display_name
        body = await self._post_json("link-codes/redeem", payload, expected_status=200)
        if body.get("status") == "linked":
            return "linked"
        raise TelegramCoreError("unavailable")

    async def list_telegram_tenants(self, telegram_user_id: int,
                                    telegram_display_name: str | None = None) -> list[dict[str, str]]:
        payload: dict[str, str | int] = {"telegramUserId": telegram_user_id}
        display_name = _safe_telegram_display_name(telegram_display_name)
        if display_name is not None:
            payload["telegramDisplayName"] = display_name
        body = await self._post_json("tenants", payload, expected_status=200)
        tenants = body.get("tenants")
        if not isinstance(tenants, list):
            raise TelegramCoreError("unavailable")
        result = []
        for tenant in tenants:
            if not isinstance(tenant, dict) or any(
                    not isinstance(tenant.get(key), str) for key in ("tenantId", "displayName", "role")):
                raise TelegramCoreError("unavailable")
            result.append({key: tenant[key] for key in ("tenantId", "displayName", "role")})
        return result

    async def issue_actor_context(self, telegram_user_id: int, tenant_id: str) -> dict:
        body = await self._post_json("actor-contexts", {
            "telegramUserId": telegram_user_id,
            "tenantId": tenant_id,
        }, expected_status=201)
        return self._validated_context(body, include_token=True)

    async def resolve_actor_context(self, token: str) -> dict:
        body = await self._post_json("actor-contexts/resolve", {"token": token}, expected_status=200)
        return self._validated_context(body, include_token=False)

    async def get_dashboard_summary(self, actor_context_token: str) -> dict[str, object]:
        body = await self._post_json("summary", {"token": actor_context_token}, expected_status=200)
        required = ("month", "currency", "incomeTotal", "expenseTotal", "transactionCount")
        if any(key not in body for key in required) \
                or any(not isinstance(body.get(key), str) for key in required if key != "transactionCount") \
                or not isinstance(body.get("transactionCount"), int):
            raise TelegramCoreError("unavailable")
        result = {key: body[key] for key in required}
        if "safeToSpend" in body:
            result["safeToSpend"] = self._validated_safe_to_spend(body["safeToSpend"])
        if "rolling7FoodStatus" in body:
            result["rolling7FoodStatus"] = self._validated_rolling_food_status(body["rolling7FoodStatus"])
        return result

    async def get_budget_overview(self, actor_context_token: str) -> dict:
        if not isinstance(actor_context_token, str) or not actor_context_token:
            raise TelegramCoreError("unavailable")
        body = await self._post_json("budgets", {"token": actor_context_token}, expected_status=200)
        return self._validated_budget_overview(body)

    async def update_telegram_budget(self, actor_context_token: str, idempotency_key: str, budget_key: str,
                                    scope: str, amount: str, version: int, period: str = "monthly") -> dict:
        if not isinstance(actor_context_token, str) or not actor_context_token \
                or not isinstance(idempotency_key, str) or not 16 <= len(idempotency_key) <= 128 \
                or not isinstance(budget_key, str) or not budget_key \
                or scope not in {"family", "personal"} or period not in {"monthly", "rolling7"} \
                or not isinstance(amount, str) or not _MONEY.fullmatch(amount) or amount.startswith("-") \
                or type(version) is not int or version < 0:
            raise TelegramCoreError("unavailable")
        body = await self._post_json(f"budgets/{quote(budget_key, safe='')}/update", {
            "token": actor_context_token,
            "idempotencyKey": idempotency_key,
            "scope": scope,
            "amount": amount,
            "version": version,
            "period": period,
        }, expected_status=200)
        return self._validated_budget_overview(body)

    async def reset_telegram_budget(self, actor_context_token: str, idempotency_key: str) -> dict:
        if not isinstance(actor_context_token, str) or not actor_context_token \
                or not isinstance(idempotency_key, str) or not 16 <= len(idempotency_key) <= 128:
            raise TelegramCoreError("unavailable")
        body = await self._post_json("budgets/reset", {
            "token": actor_context_token,
            "idempotencyKey": idempotency_key,
        }, expected_status=200)
        return self._validated_budget_overview(body)

    async def create_telegram_budget_proposal(self, actor_context_token: str, idempotency_key: str,
                                              monthly_income: str) -> dict:
        self._validate_budget_proposal_request(actor_context_token, idempotency_key)
        if not isinstance(monthly_income, str) or not _MONEY.fullmatch(monthly_income) \
                or monthly_income.startswith("-") or monthly_income in {"0", "0.0", "0.00"}:
            raise TelegramCoreError("unavailable")
        body = await self._post_json("budget-proposals", {
            "token": actor_context_token,
            "idempotencyKey": idempotency_key,
            "monthlyIncome": monthly_income,
        }, expected_status=201)
        return self._validated_budget_proposal(body)

    async def propose_history_telegram_budget(self, actor_context_token: str, idempotency_key: str) -> dict:
        self._validate_budget_proposal_request(actor_context_token, idempotency_key)
        body = await self._post_json("budget-proposals/history", {
            "token": actor_context_token,
            "idempotencyKey": idempotency_key,
        }, expected_status=201)
        return self._validated_budget_proposal(body)

    async def apply_telegram_budget_proposal(self, actor_context_token: str, idempotency_key: str,
                                            proposal_id: str) -> dict:
        self._validate_budget_proposal_request(actor_context_token, idempotency_key)
        try:
            normalized_id = str(uuid.UUID(proposal_id))
        except (ValueError, TypeError, AttributeError) as error:
            raise TelegramCoreError("unavailable") from error
        body = await self._post_json(f"budget-proposals/{normalized_id}/apply", {
            "token": actor_context_token,
            "idempotencyKey": idempotency_key,
        }, expected_status=200)
        return self._validated_budget_overview(body)

    @staticmethod
    def _validate_budget_proposal_request(actor_context_token: str, idempotency_key: str) -> None:
        if not isinstance(actor_context_token, str) or not actor_context_token \
                or not isinstance(idempotency_key, str) or not 16 <= len(idempotency_key) <= 128:
            raise TelegramCoreError("unavailable")

    @staticmethod
    def _validated_budget_proposal(body: dict) -> dict:
        required = ("id", "monthlyIncome", "totalLimit", "status", "createdAt", "proposalSource")
        if not isinstance(body, dict) or any(not isinstance(body.get(key), str) or not body[key] for key in required):
            raise TelegramCoreError("unavailable")
        try:
            uuid.UUID(body["id"])
            created_at = datetime.fromisoformat(body["createdAt"].replace("Z", "+00:00"))
        except (ValueError, TypeError, AttributeError) as error:
            raise TelegramCoreError("unavailable") from error
        if created_at.tzinfo is None or body["status"] != "pending" \
                or body["proposalSource"] not in {"income", "history_ai"}:
            raise TelegramCoreError("unavailable")
        money_fields = ("monthlyIncome", "totalLimit")
        if any(not _MONEY.fullmatch(body[key]) or body[key].startswith("-") for key in money_fields):
            raise TelegramCoreError("unavailable")
        limits = body.get("limits")
        versions = body.get("baseVersions")
        if not isinstance(limits, dict) or any(not isinstance(key, str) or not key
                                               or not isinstance(value, str) or not _MONEY.fullmatch(value)
                                               or value.startswith("-") for key, value in limits.items()) \
                or not isinstance(versions, dict) or any(not isinstance(key, str) or not key
                                                         or type(value) is not int or value < 0
                                                         for key, value in versions.items()) \
                or type(body.get("baseTotalVersion")) is not int or body["baseTotalVersion"] < 0 \
                or type(body.get("historyDays")) is not int or body["historyDays"] < 0:
            raise TelegramCoreError("unavailable")
        for key in ("modelVersion", "promptVersion"):
            if body.get(key) is not None and not isinstance(body[key], str):
                raise TelegramCoreError("unavailable")
        return {key: body.get(key) for key in (
            "id", "monthlyIncome", "totalLimit", "limits", "baseVersions", "baseTotalVersion",
            "status", "createdAt", "proposalSource", "historyDays", "modelVersion", "promptVersion")}

    @staticmethod
    def _validated_budget_overview(body: dict) -> dict:
        required_strings = ("currency", "month", "familyTotalLimit", "effectiveTotalLimit",
                            "totalMonthlySpent", "totalLimitStatus")
        if any(not isinstance(body.get(key), str) or not body[key] for key in required_strings):
            raise TelegramCoreError("unavailable")
        if not _MONTH.fullmatch(body["month"]) or body["currency"] != "RUB":
            raise TelegramCoreError("unavailable")
        try:
            date.fromisoformat(body["month"] + "-01")
        except ValueError as error:
            raise TelegramCoreError("unavailable") from error
        for key in ("familyTotalLimit", "effectiveTotalLimit", "totalMonthlySpent"):
            if not _MONEY.fullmatch(body[key]):
                raise TelegramCoreError("unavailable")
        if not isinstance(body["totalLimitStatus"], str) or body["totalLimitStatus"] not in _BUDGET_STATES:
            raise TelegramCoreError("unavailable")
        nullable_amount = body.get("personalTotalOverride")
        if nullable_amount is not None and (not isinstance(nullable_amount, str)
                                            or not _MONEY.fullmatch(nullable_amount)):
            raise TelegramCoreError("unavailable")
        maps = ("familyLimits", "personalOverrides", "effectiveLimits", "monthlySpent")
        for key in maps:
            values = body.get(key)
            if not isinstance(values, dict) or any(not isinstance(name, str) or not name
                                                   or not isinstance(amount, str) or not _MONEY.fullmatch(amount)
                                                   for name, amount in values.items()):
                raise TelegramCoreError("unavailable")
        statuses = body.get("limitStatus")
        if not isinstance(statuses, dict) or any(not isinstance(name, str) or not name
                                                 or not isinstance(value, str) or value not in _BUDGET_STATES
                                                 for name, value in statuses.items()):
            raise TelegramCoreError("unavailable")
        version_maps = ("familyVersions", "personalVersions")
        for key in version_maps:
            versions = body.get(key)
            if not isinstance(versions, dict) or any(not isinstance(name, str) or not name
                                                     or type(version) is not int or version < 0
                                                     for name, version in versions.items()):
                raise TelegramCoreError("unavailable")
        version_fields = ("familyTotalVersion", "personalTotalVersion",
                          "familyRolling7FoodVersion", "personalRolling7FoodVersion")
        if any(type(body.get(key)) is not int or body[key] < 0 for key in version_fields):
            raise TelegramCoreError("unavailable")
        return {
            **{key: body[key] for key in required_strings},
            "personalTotalOverride": nullable_amount,
            **{key: body[key] for key in maps},
            "limitStatus": statuses,
            **{key: body[key] for key in version_maps},
            **{key: body[key] for key in version_fields},
            "rolling7FoodStatus": TelegramCoreClient._validated_rolling_food_status(
                body.get("rolling7FoodStatus")),
        }

    @staticmethod
    def _validated_safe_to_spend(value: object) -> dict | None:
        if value is None:
            return None
        required = ("incomeBase", "month", "horizonDate", "monthlyExpenses", "reserve", "promisedPayments",
                    "safeTotal", "safePerDay")
        if not isinstance(value, dict) or any(not isinstance(value.get(key), str) for key in required):
            raise TelegramCoreError("unavailable")
        if not isinstance(value.get("incomeBasis"), str) \
                or value["incomeBasis"] not in {"actual_income", "planned_income"} \
                or not _MONTH.fullmatch(value["month"]) \
                or any(not _MONEY.fullmatch(value[key]) for key in
                       ("incomeBase", "monthlyExpenses", "reserve", "promisedPayments", "safeTotal", "safePerDay")) \
                or type(value.get("daysRemaining")) is not int or value["daysRemaining"] < 1:
            raise TelegramCoreError("unavailable")
        try:
            date.fromisoformat(value["horizonDate"])
        except ValueError as error:
            raise TelegramCoreError("unavailable") from error
        return {key: value[key] for key in ("incomeBasis", *required, "daysRemaining")}

    @staticmethod
    def _validated_rolling_food_status(value: object) -> dict | None:
        if value is None:
            return None
        string_fields = ("fromDate", "toDate", "limit", "spent", "limitStatus", "paceStatus")
        nullable_strings = ("remaining", "usualWeeklySpend", "paceShare")
        if not isinstance(value, dict) \
                or any(not isinstance(value.get(key), str) or not value[key] for key in string_fields) \
                or any(value.get(key) is not None and not isinstance(value.get(key), str)
                       for key in nullable_strings) \
                or type(value.get("historyWeeks")) is not int or value["historyWeeks"] < 0:
            raise TelegramCoreError("unavailable")
        try:
            date.fromisoformat(value["fromDate"])
            date.fromisoformat(value["toDate"])
        except ValueError as error:
            raise TelegramCoreError("unavailable") from error
        if value["limitStatus"] not in _BUDGET_STATES \
                or value["paceStatus"] not in {"normal", "over", "under", "insufficient_history"} \
                or not _MONEY.fullmatch(value["limit"]) or not _MONEY.fullmatch(value["spent"]) \
                or any(value.get(key) is not None and not _MONEY.fullmatch(value[key])
                       for key in ("remaining", "usualWeeklySpend")):
            raise TelegramCoreError("unavailable")
        return {key: value.get(key) for key in (*string_fields, *nullable_strings, "historyWeeks")}

    async def get_report(self, actor_context_token: str, *, period: str = "month", month: str | None = None,
                         from_date: str | None = None, to_date: str | None = None,
                         scope: str = "personal") -> dict:
        if not isinstance(actor_context_token, str) or not actor_context_token \
                or period not in {"month", "week", "90d", "custom"} \
                or scope not in {"personal", "family"}:
            raise TelegramCoreError("unavailable")
        body = await self._post_json("report", {
            "token": actor_context_token,
            "period": period,
            "month": month,
            "from": from_date,
            "to": to_date,
            "scope": scope,
        }, expected_status=200)
        required_strings = ("period", "scope", "fromDate", "toDate", "asOfDate", "timezone", "currency",
                            "incomeTotal", "expenseTotal", "debtPaymentTotal", "refundTotal")
        if any(not isinstance(body.get(key), str) or not body[key] for key in required_strings) \
                or type(body.get("transactionCount")) is not int or body["transactionCount"] < 0 \
                or not isinstance(body.get("expenseByCategory"), dict) \
                or not isinstance(body.get("expenseByDay"), dict):
            raise TelegramCoreError("unavailable")
        return body

    async def get_product_catalog(self, actor_context_token: str, query: str = "") -> dict:
        if not isinstance(query, str) or len(query.strip()) > 80 or any(ord(char) < 32 for char in query):
            raise TelegramCoreError("invalid_query")
        normalized = query.strip()
        body = await self._post_json("products/catalog", {
            "token": actor_context_token,
            "query": normalized,
        }, expected_status=200)
        return self._validated_product_catalog(body, normalized)

    async def get_shopping_candidates(self, actor_context_token: str) -> dict:
        body = await self._post_json("shopping", {"token": actor_context_token}, expected_status=200)
        return self._validated_shopping_candidates(body)

    async def get_do_not_buy(self, actor_context_token: str) -> dict:
        body = await self._post_json("actions/do-not-buy", {"token": actor_context_token}, expected_status=200)
        return self._validated_do_not_buy(body)

    async def get_do_not_buy_decisions(self, actor_context_token: str) -> dict:
        body = await self._post_json("actions/do-not-buy/decisions", {"token": actor_context_token},
                                     expected_status=200)
        if not isinstance(body, dict) or not isinstance(body.get("productKeys"), list) \
                or not isinstance(body.get("confirmedProductKeys"), list):
            raise TelegramCoreError("unavailable")
        keys = body["productKeys"] + body["confirmedProductKeys"]
        if len(keys) != len(set(keys)) or len(keys) > 50000 \
                or any(not isinstance(key, str) or not _PRODUCT_KEY.fullmatch(key) for key in keys):
            raise TelegramCoreError("unavailable")
        return body

    async def decide_do_not_buy(self, actor_context_token: str, product_key: str, action: str) -> dict:
        if not isinstance(actor_context_token, str) or not actor_context_token \
                or not isinstance(product_key, str) or not _PRODUCT_KEY.fullmatch(product_key) \
                or action not in {"confirm", "allow", "revoke"}:
            raise TelegramCoreError("unavailable")
        body = await self._post_json(
            f"actions/do-not-buy/{quote(product_key, safe='')}/{action}",
            {"token": actor_context_token}, expected_status=200)
        return self._validated_do_not_buy(body)

    async def get_personal_inflation(self, actor_context_token: str) -> dict:
        if not isinstance(actor_context_token, str) or not actor_context_token:
            raise TelegramCoreError("unavailable")
        body = await self._post_json("actions/personal-inflation", {
            "token": actor_context_token,
        }, expected_status=200)
        return self._validated_personal_inflation(body)

    async def get_recurring_projection(self, actor_context_token: str) -> dict:
        if not isinstance(actor_context_token, str) or not actor_context_token:
            raise TelegramCoreError("unavailable")
        body = await self._post_json("actions/recurring", {"token": actor_context_token}, expected_status=200)
        return self._validated_recurring_projection(body)

    async def mute_recurring_series(self, actor_context_token: str, series_id: str) -> dict:
        return await self._recurring_decision(actor_context_token, series_id, "mute")

    async def unmute_recurring_series(self, actor_context_token: str, series_id: str) -> dict:
        return await self._recurring_decision(actor_context_token, series_id, "unmute")

    async def _recurring_decision(self, actor_context_token: str, series_id: str, action: str) -> dict:
        if not isinstance(actor_context_token, str) or not actor_context_token \
                or not isinstance(series_id, str) or not re.fullmatch(r"[0-9a-f]{32}", series_id):
            raise TelegramCoreError("unavailable")
        body = await self._post_json(
            f"actions/recurring/{quote(series_id, safe='')}/{action}",
            {"token": actor_context_token}, expected_status=200)
        return self._validated_recurring_projection(body)

    async def mark_shopping_bought(self, actor_context_token: str, product_key: str) -> dict:
        return await self._shopping_decision(actor_context_token, product_key, "bought")

    async def mute_shopping_suggestion(self, actor_context_token: str, product_key: str) -> dict:
        return await self._shopping_decision(actor_context_token, product_key, "mute")

    async def unmute_shopping_suggestion(self, actor_context_token: str, product_key: str) -> dict:
        return await self._shopping_decision(actor_context_token, product_key, "unmute")

    async def _shopping_decision(self, actor_context_token: str, product_key: str, action: str) -> dict:
        if not isinstance(product_key, str) or not _PRODUCT_KEY.fullmatch(product_key):
            raise TelegramCoreError("unavailable")
        body = await self._post_json(
            f"shopping/{quote(product_key, safe='')}/{action}", {"token": actor_context_token}, expected_status=200)
        return self._validated_shopping_candidates(body)

    @staticmethod
    def _validated_product_catalog(body: dict, expected_query: str) -> dict:
        mode = "catalog" if not expected_query else "search"
        products = body.get("products")
        if body.get("mode") != mode or body.get("query") != expected_query or not isinstance(products, list) \
                or len(products) > (10 if mode == "catalog" else 5):
            raise TelegramCoreError("unavailable")
        minimum = 3 if mode == "catalog" else 1
        normalized_products = []
        for product in products:
            if not isinstance(product, dict):
                raise TelegramCoreError("unavailable")
            name, count = product.get("productName"), product.get("purchaseCount")
            if not isinstance(name, str) or not name.strip() or len(name) > 200 \
                    or type(count) is not int or not minimum <= count <= 5000:
                raise TelegramCoreError("unavailable")
            for field in ("usualUnitPrice", "lastUnitPrice", "cheapestUnitPrice"):
                TelegramCoreClient._validated_product_decimal(product.get(field), field, _PRODUCT_UNIT_PRICE,
                                                              positive=True)
            TelegramCoreClient._validated_product_decimal(product.get("totalSpent"), "totalSpent", _PRODUCT_TOTAL,
                                                          positive=True)
            TelegramCoreClient._validated_product_datetime(product.get("lastPurchasedAt"), "lastPurchasedAt")
            has_baseline, signal, prior = product.get("hasBaseline"), product.get("signal"), product.get("priorPurchases")
            baseline, change, relative = (product.get(field) for field in
                                           ("baselineUnitPrice", "change", "relative"))
            direction = product.get("direction")
            if type(has_baseline) is not bool or type(signal) is not bool or type(prior) is not int or prior < 0:
                raise TelegramCoreError("unavailable")
            if has_baseline:
                if prior != count - 1 or baseline is None or change is None or relative is None:
                    raise TelegramCoreError("unavailable")
                TelegramCoreClient._validated_product_decimal(baseline, "baselineUnitPrice", _PRODUCT_UNIT_PRICE,
                                                              positive=True)
                TelegramCoreClient._validated_product_decimal(change, "change", _PRODUCT_UNIT_PRICE,
                                                              positive=False)
                TelegramCoreClient._validated_product_decimal(relative, "relative", _PRODUCT_UNIT_PRICE,
                                                              positive=False)
                if signal and direction not in {"up", "down"} or not signal and direction is not None:
                    raise TelegramCoreError("unavailable")
            elif baseline is not None or change is not None or relative is not None or prior != 0 \
                    or signal or direction is not None:
                raise TelegramCoreError("unavailable")
            if type(product.get("chartAvailable")) is not bool or product["chartAvailable"] != (count >= 2):
                raise TelegramCoreError("unavailable")
            for field in ("lastMerchant", "cheapestMerchant"):
                if product.get(field) is not None and (not isinstance(product[field], str) or not product[field].strip()):
                    raise TelegramCoreError("unavailable")
            history = product.get("history")
            if not isinstance(history, list) or len(history) != min(count, 12):
                raise TelegramCoreError("unavailable")
            last_time = None
            for point in history:
                if not isinstance(point, dict) or any(not isinstance(point.get(field), str) or not point[field].strip()
                                                      for field in ("receiptId", "itemId", "name")) \
                        or point.get("current") is not False:
                    raise TelegramCoreError("unavailable")
                point_time = TelegramCoreClient._validated_product_datetime(point.get("purchasedAt"), "history.purchasedAt")
                TelegramCoreClient._validated_product_decimal(point.get("unitPrice"), "history.unitPrice",
                                                              _PRODUCT_UNIT_PRICE, positive=True)
                merchant = point.get("merchant")
                if merchant is not None and (not isinstance(merchant, str) or not merchant.strip()):
                    raise TelegramCoreError("unavailable")
                if last_time is not None and point_time < last_time:
                    raise TelegramCoreError("unavailable")
                last_time = point_time
            normalized_products.append(product)
        return {"mode": mode, "query": expected_query, "products": normalized_products}

    @staticmethod
    def _validated_product_decimal(value: object, field: str, pattern: re.Pattern,
                                   *, positive: bool) -> Decimal:
        if not isinstance(value, str) or not pattern.fullmatch(value):
            raise TelegramCoreError("unavailable")
        try:
            number = Decimal(value)
        except InvalidOperation as error:
            raise TelegramCoreError("unavailable") from error
        if not number.is_finite() or positive and number <= 0:
            raise TelegramCoreError("unavailable")
        return number

    @staticmethod
    def _validated_shopping_candidates(body: dict) -> dict:
        candidates = body.get("candidates")
        total_raw = body.get("estimatedListCost")
        if type(body.get("inventoryTracked")) is not bool or body["inventoryTracked"] \
                or not isinstance(candidates, list) or len(candidates) > 10:
            raise TelegramCoreError("unavailable")
        bought = body.get("boughtCandidates")
        muted = body.get("mutedCandidates")
        blocked = body.get("blockedCandidates")
        if not isinstance(bought, list) or not isinstance(muted, list) or not isinstance(blocked, list) \
                or len(candidates) + len(bought) + len(muted) + len(blocked) > 10:
            raise TelegramCoreError("unavailable")
        total = TelegramCoreClient._validated_product_decimal(total_raw, "estimatedListCost", _PRODUCT_TOTAL,
                                                               positive=False)
        estimated = Decimal("0")
        keys = set()

        def validate_candidate_list(items: list, *, active: bool) -> list:
            nonlocal estimated
            normalized_items = []
            for candidate in items:
                if not isinstance(candidate, dict):
                    raise TelegramCoreError("unavailable")
                name = candidate.get("productName")
                key = candidate.get("productKey")
                if not isinstance(name, str) or not name.strip() or len(name) > 200 \
                        or not isinstance(key, str) or not _PRODUCT_KEY.fullmatch(key) or key in keys:
                    raise TelegramCoreError("unavailable")
                keys.add(key)
                count = candidate.get("purchaseCount")
                interval = candidate.get("medianIntervalDays")
                days_until_due = candidate.get("daysUntilDue")
                if type(count) is not int or not 3 <= count <= 5000 \
                        or type(interval) is not int or not 3 <= interval <= 3650 \
                        or type(days_until_due) is not int or not -7300 <= days_until_due <= 3:
                    raise TelegramCoreError("unavailable")
                usual = TelegramCoreClient._validated_product_decimal(
                    candidate.get("usualUnitPrice"), "usualUnitPrice", _PRODUCT_UNIT_PRICE, positive=True)
                cost = TelegramCoreClient._validated_product_decimal(
                    candidate.get("estimatedCost"), "estimatedCost", _PRODUCT_TOTAL, positive=False)
                if usual.quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN) != cost:
                    raise TelegramCoreError("unavailable")
                last = TelegramCoreClient._validated_product_datetime(candidate.get("lastPurchasedAt"),
                                                                      "lastPurchasedAt")
                due = TelegramCoreClient._validated_product_datetime(candidate.get("dueAt"), "dueAt")
                if due <= last:
                    raise TelegramCoreError("unavailable")
                if active:
                    estimated += cost
                normalized_items.append(candidate)
            return normalized_items

        normalized = validate_candidate_list(candidates, active=True)
        normalized_bought = validate_candidate_list(bought, active=False)
        normalized_muted = validate_candidate_list(muted, active=False)
        normalized_blocked = []
        for candidate in blocked:
            if not isinstance(candidate, dict):
                raise TelegramCoreError("unavailable")
            name = candidate.get("productName")
            key = candidate.get("productKey")
            if not isinstance(name, str) or not name.strip() or len(name) > 200 \
                    or not isinstance(key, str) or not _PRODUCT_KEY.fullmatch(key) or key in keys \
                    or candidate.get("reasonCode") not in {"confirmed_not_to_buy", "rule_backed_not_to_buy"}:
                raise TelegramCoreError("unavailable")
            keys.add(key)
            normalized_blocked.append(candidate)
        if estimated != total:
            raise TelegramCoreError("unavailable")
        return {"candidates": normalized, "estimatedListCost": total_raw, "inventoryTracked": False,
                "boughtCandidates": normalized_bought, "mutedCandidates": normalized_muted,
                "blockedCandidates": normalized_blocked}

    @staticmethod
    def _validated_do_not_buy(body: object) -> dict:
        if not isinstance(body, dict) or type(body.get("available")) is not bool \
                or body.get("reasonCode") not in {"available", "no_optional_items", "too_many_items",
                                                  "analytics_unavailable"} \
                or not isinstance(body.get("banned"), list) or not isinstance(body.get("guesses"), list) \
                or len(body["banned"]) + len(body["guesses"]) > 50000:
            raise TelegramCoreError("unavailable")
        available, reason = body["available"], body["reasonCode"]
        version, input_version = body.get("algorithmVersion"), body.get("inputVersion")
        if available != (reason in {"available", "no_optional_items"}) \
                or (available and version != "advice-evidence.v1") \
                or (not available and (version is not None or input_version is not None or body["banned"] or body["guesses"])) \
                or (reason == "no_optional_items" and (input_version is not None or body["banned"] or body["guesses"])) \
                or (reason == "available" and (not isinstance(input_version, str)
                       or not re.fullmatch(r"[0-9a-f]{64}", input_version))):
            raise TelegramCoreError("unavailable")
        keys = set()
        for section in ("banned", "guesses"):
            for group in body[section]:
                if not isinstance(group, dict):
                    raise TelegramCoreError("unavailable")
                key, name = group.get("productKey"), group.get("productName")
                count, missing = group.get("count"), group.get("missingAmountCount")
                rule, model, unmarked = (group.get(field) for field in ("ruleCount", "modelCount", "unmarkedCount"))
                if not isinstance(key, str) or not _PRODUCT_KEY.fullmatch(key) or key in keys \
                        or not isinstance(name, str) or not name.strip() or len(name) > 200 \
                        or type(count) is not int or count < 2 \
                        or any(type(value) is not int or value < 0 for value in (missing, rule, model, unmarked)) \
                        or missing > count or rule + model + unmarked != count \
                        or type(group.get("modelOnly")) is not bool \
                        or group["modelOnly"] != (model == count) \
                        or section == "guesses" and not group["modelOnly"] \
                        or group.get("latestVerdict") not in {"harmful", "unnecessary"} \
                        or not isinstance(group.get("latestAdvice"), str) or len(group["latestAdvice"]) > 500:
                    raise TelegramCoreError("unavailable")
                amount = group.get("amount")
                if amount is not None:
                    TelegramCoreClient._validated_product_decimal(amount, "amount", _PRODUCT_TOTAL, positive=False)
                TelegramCoreClient._validated_product_datetime(group.get("lastPurchasedAt"), "lastPurchasedAt")
                keys.add(key)
        return body

    @staticmethod
    def _validated_personal_inflation(body: object) -> dict:
        if not isinstance(body, dict) or type(body.get("available")) is not bool \
                or body.get("reasonCode") not in {"available", "insufficient_history"} \
                or type(body.get("windowDays")) is not int or body["windowDays"] != 90 \
                or type(body.get("productCount")) is not int or not 0 <= body["productCount"] <= 5000 \
                or not isinstance(body.get("rising"), list) or len(body["rising"]) > 3 \
                or not isinstance(body.get("falling"), list) or len(body["falling"]) > 3:
            raise TelegramCoreError("unavailable")
        try:
            TelegramCoreClient._validated_product_datetime(body.get("asOf"), "asOf")
        except TelegramCoreError:
            raise

        totals = (body.get("basketBefore"), body.get("basketNow"), body.get("indexPercent"))
        if not body["available"]:
            if body["reasonCode"] != "insufficient_history" or body["productCount"] != 0 \
                    or any(value is not None for value in totals) or body["rising"] or body["falling"]:
                raise TelegramCoreError("unavailable")
            return {key: body[key] for key in (
                "available", "reasonCode", "asOf", "windowDays", "productCount", "basketBefore",
                "basketNow", "indexPercent", "rising", "falling")}

        if body["reasonCode"] != "available" or body["productCount"] < 3 \
                or any(not isinstance(value, str) for value in totals):
            raise TelegramCoreError("unavailable")
        before = TelegramCoreClient._validated_product_decimal(
            body["basketBefore"], "basketBefore", _PERSONAL_INFLATION_MONEY, positive=True)
        now = TelegramCoreClient._validated_product_decimal(
            body["basketNow"], "basketNow", _PERSONAL_INFLATION_MONEY, positive=True)
        index = TelegramCoreClient._validated_product_decimal(
            body["indexPercent"], "indexPercent", _PERSONAL_INFLATION_PERCENT, positive=False)
        if index <= Decimal("-100") or before <= 0 or now <= 0:
            raise TelegramCoreError("unavailable")

        seen_names: set[str] = set()

        def validate_items(items: list, *, rising: bool) -> list[dict]:
            result = []
            for item in items:
                if not isinstance(item, dict):
                    raise TelegramCoreError("unavailable")
                name = item.get("productName")
                older = item.get("olderPurchaseCount")
                window = item.get("windowPurchaseCount")
                if not isinstance(name, str) or not name.strip() or len(name) > 200 \
                        or name.casefold() in seen_names \
                        or type(older) is not int or not 2 <= older <= 5000 \
                        or type(window) is not int or not 1 <= window <= 5000:
                    raise TelegramCoreError("unavailable")
                old_price = TelegramCoreClient._validated_product_decimal(
                    item.get("oldUnitPrice"), "oldUnitPrice", _PERSONAL_INFLATION_MONEY, positive=True)
                new_price = TelegramCoreClient._validated_product_decimal(
                    item.get("newUnitPrice"), "newUnitPrice", _PERSONAL_INFLATION_MONEY, positive=True)
                weight = TelegramCoreClient._validated_product_decimal(
                    item.get("oldSpendWeight"), "oldSpendWeight", _PERSONAL_INFLATION_MONEY, positive=True)
                change = TelegramCoreClient._validated_product_decimal(
                    item.get("changePercent"), "changePercent", _PERSONAL_INFLATION_PERCENT, positive=False)
                if rising and change <= 0 or not rising and change >= 0:
                    raise TelegramCoreError("unavailable")
                seen_names.add(name.casefold())
                result.append({
                    "productName": name,
                    "oldUnitPrice": item["oldUnitPrice"],
                    "newUnitPrice": item["newUnitPrice"],
                    "oldSpendWeight": item["oldSpendWeight"],
                    "changePercent": item["changePercent"],
                    "olderPurchaseCount": older,
                    "windowPurchaseCount": window,
                })
            return result

        rising = validate_items(body["rising"], rising=True)
        falling = validate_items(body["falling"], rising=False)
        return {
            "available": True,
            "reasonCode": "available",
            "asOf": body["asOf"],
            "windowDays": 90,
            "productCount": body["productCount"],
            "basketBefore": body["basketBefore"],
            "basketNow": body["basketNow"],
            "indexPercent": body["indexPercent"],
            "rising": rising,
            "falling": falling,
        }

    @staticmethod
    def _validated_recurring_projection(body: object) -> dict:
        required = {"algorithmVersion", "completeness", "timeZone", "asOf", "expenseSeries", "incomeSeries",
                    "dueSoon", "overdue", "nextIncome", "monthlyExpenseEstimate", "monthlyExpenseEstimates",
                    "mutedSeries"}
        if not isinstance(body, dict) or not required <= body.keys() or body.get("algorithmVersion") != "recurring.v1" \
                or body.get("completeness") != "complete" or not isinstance(body.get("timeZone"), str) \
                or not body["timeZone"] or len(body["timeZone"]) > 64 \
                or not isinstance(body.get("monthlyExpenseEstimates"), dict) \
                or len(body["monthlyExpenseEstimates"]) > 8:
            raise TelegramCoreError("unavailable")
        try:
            zone = ZoneInfo(body["timeZone"])
            as_of = TelegramCoreClient._validated_product_datetime(body.get("asOf"), "asOf")
            today = as_of.astimezone(zone).date()
            if as_of.astimezone(zone).time().replace(tzinfo=None) != time.min:
                raise TelegramCoreError("unavailable")
        except (TelegramCoreError, ZoneInfoNotFoundError, ValueError):
            raise TelegramCoreError("unavailable")

        fields = ("expenseSeries", "incomeSeries", "dueSoon", "overdue", "mutedSeries")
        if any(not isinstance(body.get(field), list) or len(body[field]) > 5000 for field in fields):
            raise TelegramCoreError("unavailable")
        ids: set[str] = set()

        def series(item: object, expected_type: str) -> dict:
            if not isinstance(item, dict):
                raise TelegramCoreError("unavailable")
            identifier, key, name = item.get("id"), item.get("key"), item.get("name")
            if not isinstance(identifier, str) or not re.fullmatch(r"[0-9a-f]{32}", identifier) \
                    or identifier in ids or not isinstance(key, str) or not key or len(key) > 512 \
                    or not isinstance(name, str) or not name.strip() or len(name) > 500 \
                    or item.get("type") != expected_type or item.get("currency") != "RUB" \
                    or item.get("periodCode") not in {"week", "month"}:
                raise TelegramCoreError("unavailable")
            if item.get("category") is not None and (not isinstance(item["category"], str) or len(item["category"]) > 64):
                raise TelegramCoreError("unavailable")
            amount = TelegramCoreClient._validated_product_decimal(item.get("amount"), "amount", _BUDGET_ALERT_AMOUNT, positive=True)
            minimum = TelegramCoreClient._validated_product_decimal(item.get("minAmount"), "minAmount", _BUDGET_ALERT_AMOUNT, positive=True)
            maximum = TelegramCoreClient._validated_product_decimal(item.get("maxAmount"), "maxAmount", _BUDGET_ALERT_AMOUNT, positive=True)
            period, low_interval, high_interval, count = (
                item.get("periodDays"), item.get("minIntervalDays"), item.get("maxIntervalDays"), item.get("occurrences"))
            if type(period) is not int or (item["periodCode"] == "week" and not 6 <= period <= 8) \
                    or (item["periodCode"] == "month" and not 25 <= period <= 35) \
                    or type(low_interval) is not int or not 1 <= low_interval <= period \
                    or type(high_interval) is not int or not period <= high_interval <= 3650 \
                    or type(count) is not int or not 3 <= count <= 5000 \
                    or maximum < minimum or minimum > amount or amount > maximum \
                    or maximum - minimum > amount * Decimal("0.25"):
                raise TelegramCoreError("unavailable")
            try:
                last = date.fromisoformat(item.get("lastDate"))
                next_date = date.fromisoformat(item.get("nextDate"))
            except (TypeError, ValueError):
                raise TelegramCoreError("unavailable")
            if last.isoformat() != item.get("lastDate") or next_date.isoformat() != item.get("nextDate") \
                    or last + timedelta(days=period) != next_date \
                    or type(item.get("daysUntil")) is not int or (next_date - today).days != item["daysUntil"]:
                raise TelegramCoreError("unavailable")
            ids.add(identifier)
            return item

        expenses = [series(item, "expense") for item in body["expenseSeries"]]
        incomes = [series(item, "income") for item in body["incomeSeries"]]
        muted_series = []
        for item in body["mutedSeries"]:
            if not isinstance(item, dict) or item.get("type") not in {"expense", "income"}:
                raise TelegramCoreError("unavailable")
            muted_series.append(series(item, item["type"]))
        expense_by_id = {item["id"]: item for item in expenses}
        income_by_id = {item["id"]: item for item in incomes}
        expected_soon = [item["id"] for item in expenses if 0 <= item["daysUntil"] <= 3]
        if [item.get("id") for item in body["dueSoon"]] != expected_soon \
                or len({item.get("id") for item in body["overdue"]}) != len(body["overdue"]) \
                or {item.get("id") for item in body["overdue"]} != {item["id"] for item in expenses if item["daysUntil"] < 0}:
            raise TelegramCoreError("unavailable")
        if any(not isinstance(item, dict) or expense_by_id.get(item.get("id")) != item
               for item in body["dueSoon"] + body["overdue"]):
            raise TelegramCoreError("unavailable")
        next_income = min((item for item in incomes if item["daysUntil"] >= 0), key=lambda item: item["daysUntil"], default=None)
        if body.get("nextIncome") is not None and not isinstance(body["nextIncome"], dict):
            raise TelegramCoreError("unavailable")
        if (body.get("nextIncome") is None) != (next_income is None) \
                or body.get("nextIncome") is not None and income_by_id.get(body["nextIncome"].get("id")) != body["nextIncome"]:
            raise TelegramCoreError("unavailable")
        expected_monthly: dict[str, Decimal] = {}
        for item in expenses:
            amount = Decimal(item["amount"])
            monthly = amount if 25 <= item["periodDays"] <= 35 else amount * 30 / item["periodDays"]
            expected_monthly[item["currency"]] = expected_monthly.get(item["currency"], Decimal("0")) + monthly
        actual_monthly = body["monthlyExpenseEstimates"]
        if set(actual_monthly) != set(expected_monthly):
            raise TelegramCoreError("unavailable")
        for currency, amount in expected_monthly.items():
            value = TelegramCoreClient._validated_product_decimal(actual_monthly[currency], currency,
                                                                  _BUDGET_ALERT_AMOUNT, positive=False)
            if value != amount.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP):
                raise TelegramCoreError("unavailable")
        estimate = body.get("monthlyExpenseEstimate")
        if len(expected_monthly) == 1:
            expected = next(iter(expected_monthly.values())).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
            if TelegramCoreClient._validated_product_decimal(estimate, "monthlyExpenseEstimate", _BUDGET_ALERT_AMOUNT,
                                                              positive=False) != expected:
                raise TelegramCoreError("unavailable")
        elif estimate is not None:
            raise TelegramCoreError("unavailable")
        return body

    @staticmethod
    def _validated_product_datetime(value: object, field: str) -> datetime:
        if not isinstance(value, str):
            raise TelegramCoreError("unavailable")
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as error:
            raise TelegramCoreError("unavailable") from error
        if parsed.tzinfo is None:
            raise TelegramCoreError("unavailable")
        return parsed

    async def claim_notification_deliveries(self, limit: int = 10) -> list[dict]:
        if type(limit) is not int or not 1 <= limit <= 20:
            raise TelegramCoreError("unavailable")
        body = await self._post_json("notifications/claim", {"limit": limit}, expected_status=200)
        raw_items = body.get("items")
        if not isinstance(raw_items, list) or len(raw_items) > limit:
            raise TelegramCoreError("unavailable")
        items = []
        for item in raw_items:
            fields = ("intentId", "digestKind", "scheduledLocalDate", "language", "leaseToken")
            if not isinstance(item, dict) or any(not isinstance(item.get(key), str) for key in fields) \
                    or item["digestKind"] not in {"daily", "weekly"} or item["language"] not in {"ru", "en"} \
                    or type(item.get("telegramUserId")) is not int or item["telegramUserId"] <= 0 \
                    or type(item.get("attemptNumber")) is not int or not 1 <= item["attemptNumber"] <= 8 \
                    or not isinstance(item.get("report"), dict):
                raise TelegramCoreError("unavailable")
            try:
                uuid.UUID(item["intentId"])
                uuid.UUID(item["leaseToken"])
                scheduled = date.fromisoformat(item["scheduledLocalDate"])
            except (ValueError, TypeError, AttributeError) as error:
                raise TelegramCoreError("unavailable") from error
            if scheduled.isoformat() != item["scheduledLocalDate"]:
                raise TelegramCoreError("unavailable")
            report = item["report"]
            if not isinstance(report.get("fromDate"), str) or not isinstance(report.get("toDate"), str) \
                    or report["toDate"] != item["scheduledLocalDate"]:
                raise TelegramCoreError("unavailable")
            items.append({key: item[key] for key in (*fields, "telegramUserId", "attemptNumber", "report")})
        return items

    async def acknowledge_notification_delivery(self, intent_id: str, lease_token: str, outcome: str,
                                               *, error_code: str | None = None,
                                               provider_message_id: str | None = None) -> str:
        try:
            normalized_id = str(uuid.UUID(intent_id))
            normalized_lease = str(uuid.UUID(lease_token))
        except (ValueError, TypeError, AttributeError) as error:
            raise TelegramCoreError("unavailable") from error
        if not isinstance(outcome, str) \
                or outcome not in {"delivered", "no_data", "retryable_failure", "permanent_failure"} \
                or error_code is not None and (not isinstance(error_code, str)
                                               or not re.fullmatch(r"[a-z0-9_.-]{1,64}", error_code)) \
                or provider_message_id is not None and (not isinstance(provider_message_id, str)
                                                          or len(provider_message_id) > 128):
            raise TelegramCoreError("unavailable")
        body = await self._post_json(f"notifications/{quote(normalized_id, safe='')}/delivery", {
            "leaseToken": normalized_lease,
            "outcome": outcome,
            "errorCode": error_code,
            "providerMessageId": provider_message_id,
        }, expected_status=200)
        state = body.get("state")
        if state not in {"pending", "delivered", "skipped_no_data", "failed"}:
            raise TelegramCoreError("unavailable")
        return state

    async def list_telegram_transactions(self, actor_context_token: str, limit: int = 8) -> list[dict]:
        if not 1 <= limit <= 20:
            raise TelegramCoreError("unavailable")
        body = await self._post_json("transactions", {"token": actor_context_token, "limit": limit},
                                     expected_status=200)
        transactions = body.get("transactions")
        if not isinstance(transactions, list):
            raise TelegramCoreError("unavailable")
        result = []
        required_strings = ("id", "tenantId", "type", "amount", "currency", "categoryCode", "description",
                            "source", "occurredAt", "status", "createdAt")
        for transaction in transactions:
            if not isinstance(transaction, dict) \
                    or any(not isinstance(transaction.get(key), str) for key in required_strings) \
                    or not isinstance(transaction.get("version"), int) or transaction["version"] < 1 \
                    or transaction.get("subcategoryCode") is not None \
                    and not isinstance(transaction.get("subcategoryCode"), str):
                raise TelegramCoreError("unavailable")
            try:
                uuid.UUID(transaction["id"])
                uuid.UUID(transaction["tenantId"])
            except (ValueError, TypeError, AttributeError) as error:
                raise TelegramCoreError("unavailable") from error
            result.append({key: transaction.get(key) for key in
                           (*required_strings, "version", "subcategoryCode")})
        return result

    async def repeat_telegram_transaction(self, actor_context_token: str, idempotency_key: str,
                                          transaction_id: str) -> dict[str, str | int | None]:
        try:
            source_id = str(uuid.UUID(transaction_id))
        except (ValueError, TypeError, AttributeError) as error:
            raise TelegramCoreError("not_found") from error
        body = await self._post_json("transaction-drafts/repeat", {
            "token": actor_context_token,
            "idempotencyKey": idempotency_key,
            "transactionId": source_id,
        }, expected_status=201)
        return self._validated_draft(body)

    async def void_latest_telegram_transaction(self, actor_context_token: str, idempotency_key: str,
                                               transaction_id: str, version: int) -> dict[str, str]:
        try:
            target_id = str(uuid.UUID(transaction_id))
        except (ValueError, TypeError, AttributeError) as error:
            raise TelegramCoreError("not_found") from error
        body = await self._post_json("transactions/latest/void", {
            "token": actor_context_token,
            "idempotencyKey": idempotency_key,
            "transactionId": target_id,
            "version": version,
        }, expected_status=200)
        if any(not isinstance(body.get(key), str) for key in ("id", "status", "amount", "type")) \
                or body["id"] != target_id or body["status"] != "voided":
            raise TelegramCoreError("unavailable")
        return {key: body[key] for key in ("id", "status", "amount", "type")}

    async def list_telegram_debts(self, actor_context_token: str) -> list[dict[str, str | int | None]]:
        body = await self._post_json("debts", {"token": actor_context_token}, expected_status=200)
        debts = body.get("debts") if "debts" in body else body
        if not isinstance(debts, list):
            raise TelegramCoreError("unavailable")
        result = []
        for debt in debts:
            required = ("id", "tenantId", "name", "openingBalance", "currentBalance", "status")
            if (not isinstance(debt, dict)
                    or any(not isinstance(debt.get(key), str) or not debt[key] for key in required)
                    or any(debt.get(key) is not None and not isinstance(debt.get(key), str)
                           for key in ("interestRate", "minimumPayment"))
                    or not isinstance(debt.get("version"), int) or isinstance(debt.get("version"), bool)):
                raise TelegramCoreError("unavailable")
            result.append({key: debt.get(key) for key in (
                "id", "tenantId", "name", "openingBalance", "currentBalance", "interestRate",
                "minimumPayment", "status", "version",
            )})
        return result

    async def create_transaction_draft(self, actor_context_token: str, idempotency_key: str,
                                       text: str) -> dict[str, str | int | None]:
        body = await self._post_json("transaction-drafts", {
            "token": actor_context_token,
            "idempotencyKey": idempotency_key,
            "text": text,
        }, expected_status=201)
        return self._validated_draft(body)

    async def confirm_transaction_draft(self, actor_context_token: str, draft_id: str,
                                        idempotency_key: str, version: int) -> dict:
        draft_path = self._draft_path(draft_id)
        body = await self._post_json(f"transaction-drafts/{draft_path}/confirm", {
            "token": actor_context_token,
            "idempotencyKey": idempotency_key,
            "version": version,
        }, expected_status=200)
        if any(not isinstance(body.get(key), str) for key in ("id", "tenantId", "amount", "type")):
            raise TelegramCoreError("unavailable")
        return {**{key: body[key] for key in ("id", "tenantId", "amount", "type")},
                "budgetAlerts": TelegramCoreClient._validated_budget_alerts(body.get("budgetAlerts", []))}

    @staticmethod
    def _validated_budget_alerts(value: object) -> list[dict[str, str]]:
        if not isinstance(value, list) or len(value) > 2:
            raise TelegramCoreError("unavailable")
        alerts = []
        for alert in value:
            if not isinstance(alert, dict) or set(alert) != {"budgetKey", "threshold", "limit", "spent"} \
                    or not isinstance(alert.get("budgetKey"), str) \
                    or not alert["budgetKey"] or len(alert["budgetKey"]) > 64 \
                    or not isinstance(alert.get("threshold"), str) \
                    or alert["threshold"] not in {"near", "exceeded"} \
                    or any(not isinstance(alert.get(key), str) or not _BUDGET_ALERT_AMOUNT.fullmatch(alert[key])
                           for key in ("limit", "spent")) \
                    or alert["limit"] == "0.00":
                raise TelegramCoreError("unavailable")
            alerts.append({key: alert[key] for key in ("budgetKey", "threshold", "limit", "spent")})
        return alerts

    async def update_transaction_draft_amount(self, actor_context_token: str, draft_id: str,
                                              version: int, amount: str) -> dict[str, str | int | None]:
        draft_path = self._draft_path(draft_id)
        body = await self._post_json(f"transaction-drafts/{draft_path}/amount", {
            "token": actor_context_token,
            "version": version,
            "amount": amount,
        }, expected_status=200)
        return self._validated_draft(body)

    async def get_transaction_draft(self, actor_context_token: str,
                                    draft_id: str) -> dict[str, str | int | None]:
        draft_path = self._draft_path(draft_id)
        body = await self._post_json(f"transaction-drafts/{draft_path}/read", {
            "token": actor_context_token,
        }, expected_status=200)
        return self._validated_draft(body)

    async def update_transaction_draft(self, actor_context_token: str, draft_id: str,
                                       version: int, draft: dict) -> dict[str, str | int | None]:
        draft_path = self._draft_path(draft_id)
        editable = ("type", "amount", "currency", "categoryCode", "subcategoryCode", "description",
                    "occurredAt", "debtId")
        if any(key not in draft for key in editable):
            raise TelegramCoreError("unavailable")
        body = await self._post_json(f"transaction-drafts/{draft_path}/edit", {
            "token": actor_context_token,
            "version": version,
            **{key: draft[key] for key in editable},
        }, expected_status=200)
        return self._validated_draft(body)

    async def cancel_transaction_draft(self, actor_context_token: str, draft_id: str,
                                       version: int) -> bool:
        draft_path = self._draft_path(draft_id)
        body = await self._post_json(f"transaction-drafts/{draft_path}/cancel", {
            "token": actor_context_token,
            "version": version,
        }, expected_status=200)
        if body.get("status") != "cancelled":
            raise TelegramCoreError("unavailable")
        return True

    async def _post_json(self, endpoint: str, payload: dict, expected_status: int) -> dict:
        if not self.base_url or not self.service_token:
            raise TelegramCoreError("unavailable")
        timeout = ClientTimeout(total=10)
        try:
            async with ClientSession(timeout=timeout) as session:
                async with session.post(
                    f"{self.base_url}/internal/v1/telegram/{endpoint}",
                    headers={"X-Finance-Service-Token": self.service_token},
                    json=payload,
                ) as response:
                    if response.status != expected_status:
                        code = {
                            400: "invalid_code",
                            401: "unauthorized",
                            403: "forbidden",
                            404: "not_found",
                            409: "already_linked",
                            412: "stale",
                            422: "manual_review",
                            429: "rate_limited",
                        }.get(response.status, "unavailable")
                        raise TelegramCoreError(code)
                    body = await response.json()
                    if not isinstance(body, dict):
                        raise TelegramCoreError("unavailable")
                    return body
        except TelegramCoreError:
            raise
        except (ClientError, TimeoutError, ValueError) as error:
            raise TelegramCoreError("unavailable") from error

    @staticmethod
    def _validated_context(body: dict, include_token: bool) -> dict:
        required = ["tenantId", "displayName", "role", "permissions", "expiresAt"]
        if include_token:
            required.append("token")
        if any(key not in body for key in required) \
                or any(not isinstance(body.get(key), str) for key in required if key != "permissions") \
                or not isinstance(body.get("permissions"), list) \
                or any(not isinstance(permission, str) for permission in body["permissions"]):
            raise TelegramCoreError("unavailable")
        return {key: body[key] for key in required}

    @staticmethod
    def _validated_draft(body: dict) -> dict[str, str | int | None]:
        required_strings = ("id", "tenantId", "type", "amount", "currency", "categoryCode", "description",
                            "occurredAt", "state", "provider", "modelVersion", "promptVersion")
        if any(not isinstance(body.get(key), str) for key in required_strings) \
                or not isinstance(body.get("version"), int) or body["version"] < 1 \
                or body.get("subcategoryCode") is not None and not isinstance(body["subcategoryCode"], str) \
                or body.get("debtId") is not None and not isinstance(body["debtId"], str):
            raise TelegramCoreError("unavailable")
        return {key: body.get(key) for key in (*required_strings, "subcategoryCode", "debtId", "version")}

    @staticmethod
    def _draft_path(draft_id: str) -> str:
        try:
            return str(uuid.UUID(draft_id))
        except (ValueError, TypeError, AttributeError) as error:
            raise TelegramCoreError("not_found") from error
