"""Validate machine-readable API, event and feature-parity contracts."""

import json
import re
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from openapi_spec_validator import validate


ROOT = Path(__file__).resolve().parents[2]


def test_openapi_document_and_operation_contracts_are_valid():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))

    validate(spec)

    assert spec["openapi"] == "3.1.0"
    assert spec["components"]["securitySchemes"]["keycloakOidc"]["type"] == "openIdConnect"
    assert "post" in spec["paths"]["/api/v1/tenants"]
    assert "get" in spec["paths"]["/api/v1/me/tenants"]
    assert "patch" in spec["paths"]["/api/v1/tenants/{tenantId}/profile/me"]
    assert "patch" not in spec["paths"]["/api/v1/tenants/{tenantId}/summary"]
    for path in ("/api/v1/tenants/{tenantId}/members", "/bff/tenants/{tenantId}/members"):
        operation = spec["paths"][path]["get"]
        assert "403" in operation["responses"]
        schema = operation["responses"]["200"]["content"]["application/json"]["schema"]
        assert schema["items"]["$ref"].endswith("TenantMember")
    for path in ("/api/v1/tenants/{tenantId}/transactions", "/bff/tenants/{tenantId}/transactions"):
        operation = spec["paths"][path]["get"]
        member_filter = next(parameter for parameter in operation["parameters"] if parameter["name"] == "memberId")
        assert member_filter.get("required", False) is False
        assert {"all", "uuid"}.issubset({
            option.get("const") or option.get("format")
            for option in member_filter["schema"]["oneOf"]
        })
        assert "403" in operation["responses"]
    assert spec["components"]["schemas"]["TenantMember"]["properties"]["userId"]["format"] == "uuid"
    assert spec["components"]["schemas"]["Transaction"]["properties"]["memberName"]["type"] == ["string", "null"]
    assert spec["components"]["schemas"]["TenantMembership"]["properties"]["userId"]["format"] == "uuid"
    assert {"debtId", "ownerUserId"}.issubset(spec["components"]["schemas"]["Transaction"]["properties"])
    assert "ownerUserId" in spec["components"]["schemas"]["CreateTransaction"]["properties"]
    for path in ("/api/v1/tenants/{tenantId}/transactions/{transactionId}",
                 "/bff/tenants/{tenantId}/transactions/{transactionId}"):
        body = spec["paths"][path]["patch"]["requestBody"]["content"]["application/json"]["schema"]
        assert body["$ref"].endswith("UpdateTransaction")
    assert "debtId" in spec["components"]["schemas"]["UpdateTransaction"]["properties"]
    assert "ownerUserId" in spec["components"]["schemas"]["UpdateTransaction"]["properties"]
    void = spec["paths"]["/api/v1/tenants/{tenantId}/transactions/{transactionId}/void"]["post"]
    assert "412" in void["responses"], "stale transaction versions return HTTP 412"
    transaction_write_paths = (
        ("/api/v1/tenants/{tenantId}/transactions", "post"),
        ("/api/v1/tenants/{tenantId}/transactions/{transactionId}", "patch"),
        ("/api/v1/tenants/{tenantId}/transactions/{transactionId}/void", "post"),
        ("/api/v1/tenants/{tenantId}/transaction-drafts", "post"),
        ("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}", "patch"),
        ("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}", "delete"),
        ("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}/confirm", "post"),
        ("/bff/tenants/{tenantId}/transactions", "post"),
        ("/bff/tenants/{tenantId}/transactions/{transactionId}", "patch"),
        ("/bff/tenants/{tenantId}/transactions/{transactionId}/void", "post"),
        ("/bff/tenants/{tenantId}/transaction-drafts", "post"),
        ("/bff/tenants/{tenantId}/transaction-drafts/{draftId}", "patch"),
        ("/bff/tenants/{tenantId}/transaction-drafts/{draftId}", "delete"),
        ("/bff/tenants/{tenantId}/transaction-drafts/{draftId}/confirm", "post"),
    )
    assert all("403" in spec["paths"][path][method]["responses"] for path, method in transaction_write_paths)
    assert "get" in spec["paths"]["/api/v1/tenants/{tenantId}/budgets"]
    assert "put" in spec["paths"]["/api/v1/tenants/{tenantId}/budgets/{budgetKey}"]
    assert "post" in spec["paths"]["/api/v1/tenants/{tenantId}/budget-proposals"]
    assert "post" in spec["paths"]["/api/v1/tenants/{tenantId}/budget-proposals/history"]
    assert "post" in spec["paths"]["/api/v1/tenants/{tenantId}/budget-proposals/{proposalId}/apply"]
    assert "post" in spec["paths"]["/bff/tenants/{tenantId}/budget-proposals"]
    assert "post" in spec["paths"]["/bff/tenants/{tenantId}/budget-proposals/history"]
    assert "post" in spec["paths"]["/bff/tenants/{tenantId}/budget-proposals/{proposalId}/apply"]
    proposal = spec["components"]["schemas"]["BudgetProposal"]
    assert {"proposalSource", "historyDays", "modelVersion", "promptVersion"}.issubset(proposal["required"])
    assert proposal["properties"]["historyDays"]["maximum"] == 3660
    assert "post" in spec["paths"]["/api/v1/tenants/{tenantId}/debts/{debtId}/payments"]
    assert "post" in spec["paths"]["/bff/tenants/{tenantId}/debts/{debtId}/payments"]
    assert "put" in spec["paths"]["/bff/tenants/{tenantId}/debts/{debtId}/balance"]
    assert "get" in spec["paths"]["/api/v1/tenants/{tenantId}/reports/month"]
    assert "get" in spec["paths"]["/api/v1/tenants/{tenantId}/reports/period"]
    assert "get" in spec["paths"]["/api/v1/tenants/{tenantId}/reports/family"]
    assert "get" in spec["paths"]["/bff/tenants/{tenantId}/reports/period"]
    assert spec["components"]["schemas"]["FinanceReport"]["properties"]["debtPaymentTotal"]["type"] == "string"
    assert spec["components"]["schemas"]["FinanceReport"]["properties"]["weekendSharePercent"]["type"] == ["integer", "null"]
    month_pattern = spec["paths"]["/api/v1/tenants/{tenantId}/reports/month"]["get"]["parameters"][0]["schema"]["pattern"]
    assert re.fullmatch(month_pattern, "2026-10")
    pace_pattern = spec["components"]["schemas"]["DashboardSummary"]["properties"]["projectedExpenseTotal"]["pattern"]
    assert re.fullmatch(pace_pattern, "123.45")
    for schema_name in ("BudgetOverview", "DashboardSummary", "FinanceReport"):
        schema = spec["components"]["schemas"][schema_name]
        assert "rolling7FoodStatus" in schema["required"]
        assert schema["properties"]["rolling7FoodStatus"]["$ref"].endswith("RollingFoodStatus")
    assert spec["components"]["schemas"]["RollingFoodStatus"]["properties"]["historyWeeks"]["maximum"] == 5
    create = spec["paths"]["/api/v1/tenants/{tenantId}/transactions"]["post"]
    assert any(ref["$ref"].endswith("IdempotencyKey") for ref in create["parameters"])
    assert "post" in spec["paths"]["/api/v1/tenants/{tenantId}/transaction-drafts"]
    assert "patch" in spec["paths"]["/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}"]
    assert "post" in spec["paths"]["/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}/confirm"]
    draft = spec["components"]["schemas"]["TransactionDraft"]
    assert {"state", "version", "provider", "modelVersion", "promptVersion"}.issubset(draft["required"])
    amount = spec["components"]["schemas"]["CreateTransaction"]["properties"]["amount"]
    assert amount["type"] == "string"
    assert "pattern" in amount
    money = re.compile(amount["pattern"])
    assert all(money.fullmatch(value) for value in ["0.01", "0.1", "12", "12.50"])
    assert not any(money.fullmatch(value) for value in ["0", "0.00", "-1", "12.345"])


def test_transaction_response_contract_accepts_core_budget_threshold_alerts():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    transaction = spec["components"]["schemas"]["Transaction"]
    alert = spec["components"]["schemas"]["BudgetAlert"]

    assert "budgetAlerts" not in transaction["required"]
    assert transaction["properties"]["budgetAlerts"] == {
        "type": "array", "maxItems": 2, "items": {"$ref": "#/components/schemas/BudgetAlert"}}
    Draft202012Validator(alert).validate({
        "budgetKey": "__total__", "threshold": "exceeded", "limit": "55000.00", "spent": "55001.00",
    })
    with pytest.raises(ValidationError):
        Draft202012Validator(alert).validate({
            "budgetKey": "__total__", "threshold": "disabled", "limit": "55000.00", "spent": "55001.00",
        })


def test_receipt_price_history_is_exposed_through_authenticated_core_and_bff_routes():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))

    for path, expected_security in (
        ("/api/v1/tenants/{tenantId}/products/price-history", None),
        ("/bff/tenants/{tenantId}/products/price-history", [{"bffSession": []}]),
    ):
        operation = spec["paths"][path]["get"]
        assert operation.get("security") == expected_security
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "PriceComparison"
        )
        parameters = spec["paths"][path]["parameters"]
        assert {parameter["name"] for parameter in parameters if parameter.get("in") == "query"} == {
            "receiptId", "itemId",
        }
        assert all(parameter["required"] for parameter in parameters if parameter.get("in") == "query")
        assert "404" in operation["responses"] and "503" in operation["responses"]


def test_product_catalog_is_member_scoped_with_distinct_catalog_and_search_thresholds():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    for path, expected_security in (
        ("/api/v1/tenants/{tenantId}/products", None),
        ("/bff/tenants/{tenantId}/products", [{"bffSession": []}]),
    ):
        operation = spec["paths"][path]["get"]
        assert operation.get("security") == expected_security
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "ProductCatalogResponse"
        )
        query = next(parameter for parameter in operation["parameters"] if parameter["name"] == "query")
        assert query["required"] is False and query["schema"]["maxLength"] == 80
        assert {"403", "404", "503"}.issubset(operation["responses"])
    response = spec["components"]["schemas"]["ProductCatalogResponse"]
    assert response["properties"]["products"]["maxItems"] == 10
    card = spec["components"]["schemas"]["ProductCatalogCard"]
    assert card["properties"]["purchaseCount"]["minimum"] == 1
    assert card["properties"]["history"]["maxItems"] == 12
    assert card["properties"]["chartAvailable"]["description"].startswith("True only")
    assert card["properties"]["baselineUnitPrice"]["type"] == ["string", "null"]


def test_private_product_catalog_contract_requires_tenant_and_core_resolved_member():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-intelligence-v1.yaml").read_text("utf-8"))
    public = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    validate(spec)
    operation = spec["paths"]["/internal/v1/products/catalog"]["post"]
    assert operation["security"] == [{"serviceBearer": []}]
    assert operation["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ProductCatalogRequest"
    )
    assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ProductCatalogResponse"
    )
    request = spec["components"]["schemas"]["ProductCatalogRequest"]
    assert request["additionalProperties"] is False
    assert set(request["required"]) == {"tenantId", "ownerUserId", "query"}
    assert request["properties"]["tenantId"]["format"] == "uuid"
    assert request["properties"]["ownerUserId"]["format"] == "uuid"
    assert request["properties"]["query"]["maxLength"] == 80
    for name in ("ProductCatalogResponse", "ProductCatalogCard", "PriceHistoryPoint"):
        assert spec["components"]["schemas"][name] == public["components"]["schemas"][name]


def test_shopping_candidates_are_member_scoped_and_explicitly_not_inventory():
    public = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    internal = yaml.safe_load((ROOT / "contracts/openapi/finance-intelligence-v1.yaml").read_text("utf-8"))
    validate(public)
    validate(internal)

    for path, expected_security in (
            ("/api/v1/tenants/{tenantId}/shopping", None),
            ("/bff/tenants/{tenantId}/shopping", [{"bffSession": []}])):
        operation = public["paths"][path]["get"]
        assert operation.get("security") == expected_security
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "ShoppingList")
        assert {"401", "403", "404", "503"}.issubset(operation["responses"])

    shopping = public["components"]["schemas"]["ShoppingList"]
    assert shopping["additionalProperties"] is False
    assert shopping["properties"]["inventoryTracked"]["const"] is False
    assert shopping["properties"]["candidates"]["maxItems"] == 10
    assert {"boughtCandidates", "mutedCandidates", "blockedCandidates"}.issubset(shopping["required"])
    assert shopping["properties"]["boughtCandidates"]["items"]["$ref"].endswith("ShoppingCandidate")
    assert shopping["properties"]["mutedCandidates"]["items"]["$ref"].endswith("ShoppingCandidate")
    assert shopping["properties"]["blockedCandidates"]["items"]["$ref"].endswith("BlockedShoppingCandidate")
    candidate = public["components"]["schemas"]["ShoppingCandidate"]
    assert "productKey" in candidate["required"]
    assert candidate["properties"]["productKey"]["pattern"] == "^[a-zа-я0-9]{1,256}$"
    assert candidate["properties"]["purchaseCount"]["minimum"] == 3
    assert candidate["properties"]["medianIntervalDays"]["minimum"] == 3
    assert candidate["properties"]["daysUntilDue"]["maximum"] == 3
    assert public["components"]["schemas"]["BlockedShoppingCandidate"]["properties"]["reasonCode"]["enum"] \
        == ["confirmed_not_to_buy", "rule_backed_not_to_buy"]

    for path, method, operation_id in (
            ("/api/v1/tenants/{tenantId}/shopping/{productKey}/bought", "post", "markShoppingCandidateBought"),
            ("/api/v1/tenants/{tenantId}/suggestions/shopping/{productKey}/mute", "put", "muteShoppingCandidate"),
            ("/api/v1/tenants/{tenantId}/suggestions/shopping/{productKey}/mute", "delete", "unmuteShoppingCandidate"),
            ("/bff/tenants/{tenantId}/shopping/{productKey}/bought", "post", "markBrowserShoppingCandidateBought"),
            ("/bff/tenants/{tenantId}/suggestions/shopping/{productKey}/mute", "put", "muteBrowserShoppingCandidate"),
            ("/bff/tenants/{tenantId}/suggestions/shopping/{productKey}/mute", "delete", "unmuteBrowserShoppingCandidate")):
        operation = public["paths"][path][method]
        assert operation["operationId"] == operation_id
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "ShoppingList")
    for path in ("/bff/tenants/{tenantId}/shopping/{productKey}/bought",
                 "/bff/tenants/{tenantId}/suggestions/shopping/{productKey}/mute"):
        for method in ("post", "put", "delete"):
            if method in public["paths"][path]:
                assert {"bffSession": []} in public["paths"][path][method]["security"]
                assert any(parameter["$ref"].endswith("CsrfToken")
                           for parameter in public["paths"][path][method]["parameters"])

    operation = internal["paths"]["/internal/v1/shopping/candidates"]["post"]
    assert operation["security"] == [{"serviceBearer": []}]
    assert operation["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ShoppingCandidatesRequest")
    assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ShoppingList")
    request = internal["components"]["schemas"]["ShoppingCandidatesRequest"]
    assert request["additionalProperties"] is False
    assert set(request["required"]) == {"tenantId", "ownerUserId"}
    assert "userId" not in request["properties"]
    assert internal["components"]["schemas"]["ShoppingList"]["required"] == [
        "candidates", "estimatedListCost", "inventoryTracked"]
    assert "productKey" not in internal["components"]["schemas"]["ShoppingCandidate"]["required"]

    telegram = public["paths"]["/internal/v1/telegram/shopping"]["post"]
    assert telegram["security"] == [{"telegramServiceToken": []}]
    assert telegram["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ResolveTelegramActorContext")
    assert telegram["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ShoppingList")
    for path, operation_id in (
            ("/internal/v1/telegram/shopping/{productKey}/bought", "markTelegramShoppingCandidateBought"),
            ("/internal/v1/telegram/shopping/{productKey}/mute", "muteTelegramShoppingCandidate"),
            ("/internal/v1/telegram/shopping/{productKey}/unmute", "unmuteTelegramShoppingCandidate")):
        operation = public["paths"][path]["post"]
        assert operation["operationId"] == operation_id
        assert operation["security"] == [{"telegramServiceToken": []}]
        assert operation["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "ResolveTelegramActorContext")
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "ShoppingList")


def test_telegram_actor_context_contract_is_service_scoped_and_never_accepts_user_id():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    paths = spec["paths"]
    for path in (
            "/internal/v1/telegram/tenants",
            "/internal/v1/telegram/actor-contexts",
            "/internal/v1/telegram/actor-contexts/resolve",
            "/internal/v1/telegram/summary",
            "/internal/v1/telegram/transaction-drafts",
            "/internal/v1/telegram/debts",
            "/internal/v1/telegram/products/catalog"):
        operation = paths[path]["post"]
        assert operation["security"] == [{"telegramServiceToken": []}]
        assert "userId" not in operation["requestBody"]["content"]["application/json"]["schema"].get("properties", {})
    context = spec["components"]["schemas"]["TelegramActorContext"]
    assert {"token", "tenantId", "displayName", "role", "permissions", "expiresAt"}.issubset(context["required"])
    assert context["properties"]["token"]["pattern"] == "^[A-Za-z0-9_-]{43}$"
    assert spec["components"]["schemas"]["TelegramActorTenant"]["properties"]["tenantId"]["format"] == "uuid"
    summary = paths["/internal/v1/telegram/summary"]["post"]
    assert summary["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("DashboardSummary")
    assert summary["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ResolveTelegramActorContext")
    product_catalog = paths["/internal/v1/telegram/products/catalog"]["post"]
    assert product_catalog["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ProductCatalogResponse")
    assert product_catalog["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "TelegramProductCatalogRequest")
    create = paths["/internal/v1/telegram/transaction-drafts"]["post"]
    assert create["responses"]["201"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "TransactionDraft")
    decision = paths["/internal/v1/telegram/transaction-drafts/{draftId}/confirm"]["post"]
    assert decision["security"] == [{"telegramServiceToken": []}]
    assert "412" in decision["responses"]
    assert decision["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "TelegramDraftDecision")
    amount = paths["/internal/v1/telegram/transaction-drafts/{draftId}/amount"]["post"]
    assert amount["security"] == [{"telegramServiceToken": []}]
    assert "412" in amount["responses"]
    assert amount["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "TelegramDraftAmount")
    cancel = paths["/internal/v1/telegram/transaction-drafts/{draftId}/cancel"]["post"]
    assert "412" in cancel["responses"]
    assert cancel["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "TelegramDraftCancel")
    for schema_name in ("CreateTelegramTransactionDraft", "TelegramDraftDecision", "TelegramDraftCancel"):
        properties = spec["components"]["schemas"][schema_name]["properties"]
        assert "token" in properties
        assert "userId" not in properties and "tenantId" not in properties
    edit = paths["/internal/v1/telegram/transaction-drafts/{draftId}/edit"]["post"]
    read = paths["/internal/v1/telegram/transaction-drafts/{draftId}/read"]["post"]
    assert edit["security"] == [{"telegramServiceToken": []}]
    assert "412" in edit["responses"]
    assert edit["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "TelegramDraftEdit")
    assert read["security"] == [{"telegramServiceToken": []}]
    debt_list = paths["/internal/v1/telegram/debts"]["post"]
    assert debt_list["security"] == [{"telegramServiceToken": []}]
    assert debt_list["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ResolveTelegramActorContext")
    assert debt_list["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "TelegramDebtList")


def test_receipt_draft_api_keeps_cash_total_and_items_separate():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    create = spec["paths"]["/api/v1/tenants/{tenantId}/receipts"]["post"]

    assert any(parameter["$ref"].endswith("IdempotencyKey") for parameter in create["parameters"])
    assert "403" in create["responses"] and "409" in create["responses"]
    request_schema = create["requestBody"]["content"]["application/json"]["schema"]
    assert request_schema["$ref"].endswith("CreateReceipt")
    receipt = spec["components"]["schemas"]["Receipt"]
    assert {"state", "version", "cashTotal", "itemsTotal", "items"}.issubset(receipt["required"])
    assert receipt["properties"]["cashTotal"]["type"] == ["string", "null"]
    assert receipt["properties"]["itemsTotal"]["type"] == ["string", "null"]
    item = spec["components"]["schemas"]["ReceiptItem"]
    assert item["properties"]["lineSum"]["type"] == ["string", "null"]


def test_receipt_item_edits_are_versioned_and_total_sync_is_explicit():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    items = spec["paths"]["/api/v1/tenants/{tenantId}/receipts/{receiptId}/items"]
    add = items["post"]
    assert any(parameter.get("name") == "If-Match" for parameter in add["parameters"])
    assert add["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith("ReceiptItemInput")
    assert "412" in add["responses"] and "409" in add["responses"]
    page = items["get"]["responses"]["200"]["content"]["application/json"]["schema"]["$ref"]
    assert page.endswith("ReceiptItemPage")
    item = spec["paths"]["/api/v1/tenants/{tenantId}/receipts/{receiptId}/items/{itemId}"]
    for operation in [item["patch"], item["delete"]]:
        assert any(parameter.get("name") == "If-Match" for parameter in operation["parameters"])
        assert "412" in operation["responses"]
    sync = spec["paths"]["/api/v1/tenants/{tenantId}/receipts/{receiptId}/sync-total"]["post"]
    assert any(parameter.get("name") == "If-Match" for parameter in sync["parameters"])
    assert "412" in sync["responses"]

    bff_items = spec["paths"]["/bff/tenants/{tenantId}/receipts/{receiptId}/items"]
    for method in ["get", "post"]:
        operation = bff_items[method]
        assert operation["security"] == [{"bffSession": []}]
        csrf_parameter = any(parameter.get("$ref", "").endswith("CsrfToken")
                             for parameter in operation.get("parameters", []))
        assert csrf_parameter is (method == "post")
    assert "itemCount" in spec["components"]["schemas"]["Receipt"]["required"]


def test_receipt_category_override_is_versioned_and_available_in_bff():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    path = "/api/v1/tenants/{tenantId}/receipts/{receiptId}/category"
    operation = spec["paths"][path]["patch"]
    assert any(parameter.get("name") == "If-Match" for parameter in operation["parameters"])
    assert "412" in operation["responses"] and "403" in operation["responses"]
    assert operation["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith("ReceiptCategorySelection")
    receipt = spec["components"]["schemas"]["Receipt"]
    assert {"categoryCode", "categorySource", "categoryAlgorithmVersion", "alcoholShare", "leisureShare", "leisure"}.issubset(receipt["required"])
    bff = spec["paths"]["/bff/tenants/{tenantId}/receipts/{receiptId}/category"]["patch"]
    assert bff["security"] == [{"bffSession": []}]
    assert any(parameter.get("$ref", "").endswith("CsrfToken") for parameter in bff["parameters"])


def test_receipt_basket_review_is_versioned_private_and_amount_free():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    path = "/api/v1/tenants/{tenantId}/receipts/{receiptId}/basket-review"
    operation = spec["paths"][path]["post"]
    assert any(parameter.get("name") == "If-Match" for parameter in operation["parameters"])
    assert "412" in operation["responses"] and "502" in operation["responses"]
    item = spec["components"]["schemas"]["ReceiptItem"]
    assert "requestBody" not in operation
    assert {"verdict", "advice", "reviewReason", "reviewAction", "verdictSource", "reviewProvider",
            "reviewModelVersion", "reviewPromptVersion", "reviewAlgorithmVersion", "version"}.issubset(item["required"])
    bff = spec["paths"]["/bff/tenants/{tenantId}/receipts/{receiptId}/basket-review"]["post"]
    assert bff["security"] == [{"bffSession": []}]
    assert any(parameter.get("$ref", "").endswith("CsrfToken") for parameter in bff["parameters"])


def test_product_decision_is_user_scoped_reversible_and_disputed_items_are_paged():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    disputed_path = "/api/v1/tenants/{tenantId}/receipts/{receiptId}/disputed-items"
    disputed = spec["paths"][disputed_path]["get"]
    assert any(parameter.get("name") == "page" for parameter in disputed["parameters"])
    assert disputed["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("ReceiptItemPage")
    decision_path = "/api/v1/tenants/{tenantId}/products/{productKey}/decision"
    decision = spec["paths"][decision_path]
    assert "put" in decision and "delete" in decision
    assert decision["put"]["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith("ProductDecisionSelection")
    bff = spec["paths"]["/bff/tenants/{tenantId}/products/{productKey}/decision"]
    for method in ("put", "delete"):
        assert bff[method]["security"] == [{"bffSession": []}]
        assert any(parameter.get("$ref", "").endswith("CsrfToken") for parameter in bff[method]["parameters"])
    assert spec["components"]["schemas"]["ReceiptItem"]["properties"]["productKey"]["type"] == "string"
    allowed_path = "/api/v1/tenants/{tenantId}/products/decisions"
    allowed = spec["paths"][allowed_path]["get"]
    assert allowed["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("ProductDecisionKeys")
    browser_allowed = spec["paths"]["/bff/tenants/{tenantId}/products/decisions"]["get"]
    assert browser_allowed["security"] == [{"bffSession": []}]


def test_product_decisions_expose_separate_confirmed_and_allowed_keys():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    schemas = spec["components"]["schemas"]
    assert schemas["ProductDecisionSelection"]["properties"]["decision"]["enum"] == ["allowed", "confirmed"]
    assert set(schemas["ProductDecisionKeys"]["required"]) == {"productKeys", "confirmedProductKeys"}
    assert schemas["ProductDecisionKeys"]["properties"]["confirmedProductKeys"]["uniqueItems"] is True


def test_do_not_buy_contract_separates_model_guesses_from_shopping_blocks():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    schemas = spec["components"]["schemas"]
    for prefix, security in (("/api/v1", [{"bearerAuth": []}]), ("/bff", [{"bffSession": []}])):
        path = prefix + "/tenants/{tenantId}/products/do-not-buy"
        operation = spec["paths"][path]["get"]
        if prefix == "/bff":
            assert operation["security"] == security
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("AdviceEvidenceReport")
    assert {"available", "reasonCode", "algorithmVersion", "inputVersion", "banned", "guesses"} == set(
        schemas["AdviceEvidenceReport"]["required"])
    assert schemas["AdviceEvidenceGroup"]["properties"]["modelOnly"]["type"] == "boolean"
    assert "rule_backed_not_to_buy" in schemas["BlockedShoppingCandidate"]["properties"]["reasonCode"]["enum"]


def test_telegram_do_not_buy_actions_require_actor_and_service_credentials():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    decisions = spec["paths"]["/internal/v1/telegram/actions/do-not-buy/decisions"]["post"]
    assert decisions["security"] == [{"telegramServiceToken": []}]
    assert decisions["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ProductDecisionKeys")
    for path in ("/internal/v1/telegram/actions/do-not-buy",
                 "/internal/v1/telegram/actions/do-not-buy/{productKey}/{action}"):
        operation = spec["paths"][path]["post"]
        assert operation["security"] == [{"telegramServiceToken": []}]
        assert operation["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "ResolveTelegramActorContext")
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "AdviceEvidenceReport")
    action = spec["paths"]["/internal/v1/telegram/actions/do-not-buy/{productKey}/{action}"]
    assert next(parameter for parameter in action["parameters"] if parameter["name"] == "action")["schema"]["enum"] \
        == ["confirm", "allow", "revoke"]


def test_telegram_recalculation_has_separate_service_scoped_preview_and_apply():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    preview = spec["paths"]["/internal/v1/telegram/actions/review-recalculations/preview"]["post"]
    apply = spec["paths"]["/internal/v1/telegram/actions/review-recalculations/apply"]["post"]
    for operation in (preview, apply):
        assert operation["security"] == [{"telegramServiceToken": []}]
        assert "receipt.write.own" in operation["description"]
    assert preview["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ResolveTelegramActorContext")
    assert preview["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ReceiptRecalculationPreview")
    assert apply["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "TelegramRecalculationApplyRequest")
    assert apply["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ReceiptRecalculationApplyResult")
    assert "412" in apply["responses"]


def test_repeat_warnings_only_describe_prior_confirmed_item_evidence():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    for path in ("/api/v1/tenants/{tenantId}/receipts/{receiptId}/repeat-warnings",
                 "/bff/tenants/{tenantId}/receipts/{receiptId}/repeat-warnings"):
        operation = spec["paths"][path]["get"]
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("ReceiptRepeatWarnings")
    assert spec["paths"]["/bff/tenants/{tenantId}/receipts/{receiptId}/repeat-warnings"]["get"]["security"] == [{"bffSession": []}]
    warning = spec["components"]["schemas"]["ReceiptRepeatWarning"]
    assert {"itemId", "name", "verdict", "count", "lastSum", "advice"}.issubset(warning["required"])


def test_receipt_duplicate_candidate_and_decision_are_versioned():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    candidates = spec["paths"]["/api/v1/tenants/{tenantId}/receipts/{receiptId}/duplicate-candidates"]["get"]
    assert candidates["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("ReceiptDuplicateCandidates")
    decision = spec["paths"]["/api/v1/tenants/{tenantId}/receipts/{receiptId}/duplicate-decision"]["put"]
    assert any(parameter.get("name") == "If-Match" for parameter in decision["parameters"])
    assert "412" in decision["responses"] and "409" in decision["responses"]
    bff = spec["paths"]["/bff/tenants/{tenantId}/receipts/{receiptId}/duplicate-decision"]["put"]
    assert bff["security"] == [{"bffSession": []}]
    assert any(parameter.get("$ref", "").endswith("CsrfToken") for parameter in bff["parameters"])
    assert "duplicateDecision" in spec["components"]["schemas"]["Receipt"]["required"]


def test_receipt_confirmation_is_versioned_and_idempotent():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    route = spec["paths"]["/api/v1/tenants/{tenantId}/receipts/{receiptId}/confirm"]["post"]
    names = {parameter.get("name") for parameter in route["parameters"]}
    assert {"If-Match", "Idempotency-Key"}.issubset(names)
    assert "409" in route["responses"] and "412" in route["responses"]
    assert route["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith("Receipt")
    bff = spec["paths"]["/bff/tenants/{tenantId}/receipts/{receiptId}/confirm"]["post"]
    assert any(parameter.get("$ref", "").endswith("CsrfToken") for parameter in bff["parameters"])
    receipt = spec["components"]["schemas"]["Receipt"]
    assert {"transactionId", "duplicateDecision", "duplicateOfReceiptId"}.issubset(receipt["required"])


def test_private_intelligence_contract_requires_scoped_context_and_service_bearer():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-intelligence-v1.yaml").read_text("utf-8"))

    validate(spec)

    operation = spec["paths"]["/internal/v1/budget-proposals"]["post"]
    assert operation["security"] == [{"serviceBearer": []}]
    schema = spec["components"]["schemas"]["BudgetProposalContext"]
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {
        "monthlyIncome", "historyDays", "categoryCodes", "monthlyExpenseByMonth",
        "monthlyIncomeByMonth", "currentFamilyLimits",
    }
    assert schema["properties"]["historyDays"]["minimum"] == 30
    draft_operation = spec["paths"]["/internal/v1/transaction-drafts"]["post"]
    assert draft_operation["security"] == [{"serviceBearer": []}]
    draft_context = spec["components"]["schemas"]["TransactionDraftContext"]
    assert draft_context["additionalProperties"] is False
    assert set(draft_context["required"]) == {"text", "timezone", "now"}
    expected_headers = {
        "X-Finance-Task-Kind", "X-Finance-Input-Schema-Version", "X-Finance-Output-Schema-Version",
        "X-Finance-Deadline-Unix-Ms", "X-Finance-AI-Policy", "X-Finance-Correlation-ID",
        "X-Finance-Required-Capabilities",
    }
    for path in ["/internal/v1/budget-proposals", "/internal/v1/transaction-drafts",
                 "/internal/v1/receipts/ocr", "/internal/v1/receipts/vision",
                 "/internal/v1/receipts/basket-review"]:
        operation = spec["paths"][path]["post"]
        assert operation["security"] == [{"serviceBearer": []}]
        parameter_names = {
            spec["components"]["parameters"][parameter["$ref"].rsplit("/", 1)[1]]["name"]
            for parameter in operation["parameters"]
        }
        assert expected_headers.issubset(parameter_names)
    assert spec["paths"]["/internal/v1/jobs/{correlationId}/cancel"]["post"]["security"] == [
        {"serviceBearer": []}
    ]
    assert "provenance" in spec["components"]["schemas"]["BudgetProposalAdvice"]["required"]
    assert "usage" in spec["components"]["schemas"]["TransactionDraftAdvice"]["required"]
    basket_context = spec["components"]["schemas"]["ReceiptBasketContext"]
    assert basket_context["additionalProperties"] is False
    item_context = basket_context["properties"]["items"]["items"]
    assert item_context["additionalProperties"] is False
    assert set(item_context["required"]) == {"ordinal", "name"}
    assert "lineSum" not in item_context["properties"]
    basket_response = spec["components"]["schemas"]["ReceiptBasketAdvice"]
    assert "amount" not in basket_response["properties"] and "cashTotal" not in basket_response["properties"]
    ocr = spec["components"]["schemas"]["ReceiptOcrAdvice"]
    assert ocr["properties"]["provider"]["const"] == "tesseract"
    assert {"total", "items"}.issubset(ocr["required"])
    assert ocr["properties"]["items"]["items"]["$ref"].endswith("ReceiptVisionItem")
    api_reading = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    reading_schema = api_reading["components"]["schemas"]["ReceiptOcrReading"]
    assert {"ocrTotal", "ocrItems", "reconciliation"}.issubset(reading_schema["required"])
    assert reading_schema["properties"]["reconciliation"]["$ref"].endswith("ReceiptReconciliation")
    reconciliation = api_reading["components"]["schemas"]["ReceiptReconciliation"]
    assert "suggestedTopUps" in reconciliation["required"]
    assert reconciliation["properties"]["suggestedTopUps"]["maxItems"] == 3
    top_up = api_reading["components"]["schemas"]["ReceiptTopUpSuggestion"]
    assert set(top_up["required"]) == {"ocrOrdinal", "name", "lineSum"}
    assert ocr["properties"]["capabilities"]["items"]["enum"] == ["text"]
    vision = spec["components"]["schemas"]["ReceiptVisionAdvice"]
    assert vision["properties"]["provider"]["const"] == "ollama"
    assert vision["properties"]["requiredCapabilities"]["items"]["enum"] == ["vision", "structured_output"]


def test_private_price_comparison_contract_is_service_scoped_and_exact():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-intelligence-v1.yaml").read_text("utf-8"))

    validate(spec)

    operation = spec["paths"]["/internal/v1/prices/compare"]["post"]
    assert operation["security"] == [{"serviceBearer": []}]
    assert operation["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "PriceCompareRequest"
    )
    assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "PriceComparison"
    )
    request = spec["components"]["schemas"]["PriceCompareRequest"]
    assert request["additionalProperties"] is False
    assert set(request["required"]) == {
        "tenantId", "ownerUserId", "receiptId", "itemId", "name", "quantity", "lineSum", "currency",
        "purchasedAt",
    }
    assert request["properties"]["quantity"]["pattern"] == r"^(?:0|[1-9][0-9]{0,11})(?:\.[0-9]{1,6})?$"
    assert request["properties"]["lineSum"]["pattern"] == r"^(?:0|[1-9][0-9]{0,17})(?:\.[0-9]{1,2})?$"
    comparison = spec["components"]["schemas"]["PriceComparison"]
    assert comparison["properties"]["currentUnitPrice"]["pattern"] == r"^\d{1,24}\.\d{6}$"
    assert comparison["properties"]["baselineUnitPrice"]["type"] == ["string", "null"]
    assert comparison["properties"]["history"]["items"]["$ref"].endswith("PriceHistoryPoint")


def test_public_and_private_price_comparison_explain_values_consistently():
    public = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    private = yaml.safe_load((ROOT / "contracts/openapi/finance-intelligence-v1.yaml").read_text("utf-8"))
    public_schema = public["components"]["schemas"]["PriceComparison"]["properties"]
    private_schema = private["components"]["schemas"]["PriceComparison"]["properties"]

    for field in ("baselineUnitPrice", "change", "relative", "signal", "direction",
                  "priorPurchases", "history"):
        assert public_schema[field].get("description") == private_schema[field].get("description")
    assert "0.125000 means 12.5 percent" in public_schema["relative"]["description"]


def test_android_oidc_client_includes_api_and_subject_scopes_by_default():
    realm = json.loads((ROOT / "ops/keycloak/realm-finance.json").read_text("utf-8"))
    android = next(client for client in realm["clients"] if client["clientId"] == "finance-android")

    assert {"finance-api-audience", "profile", "email", "basic"}.issubset(
        set(android["defaultClientScopes"])
    )
    assert {"finance-api-audience", "profile", "email", "basic"}.issubset(
        {scope["name"] for scope in realm["clientScopes"]}
    )


def test_event_schema_accepts_a_valid_transaction_snapshot():
    schema = json.loads((ROOT / "contracts/events/finance.transaction.v1.schema.json").read_text("utf-8"))
    Draft202012Validator.check_schema(schema)
    event = {
        "event_id": "00000000-0000-4000-8000-000000000001",
        "event_type": "transaction.created",
        "schema_version": 1,
        "tenant_id": "00000000-0000-4000-8000-000000000002",
        "aggregate_type": "transaction",
        "aggregate_id": "00000000-0000-4000-8000-000000000003",
        "aggregate_version": 1,
        "occurred_at": "2026-10-01T12:00:00Z",
        "recorded_at": "2026-10-01T12:00:01Z",
        "producer": "core",
        "payload": {
            "owner_user_id": "00000000-0000-4000-8000-000000000004",
            "type": "expense",
            "amount": "12.50",
            "currency": "RUB",
            "category_code": "food",
            "description": "Lunch",
            "status": "posted",
            "financial_occurred_at": "2026-10-01T12:00:00Z",
        },
    }

    Draft202012Validator(schema).validate(event)


def test_receipt_confirmed_event_schema_carries_price_projection_snapshot():
    schema = json.loads((ROOT / "contracts/events/finance.receipt.v1.schema.json").read_text("utf-8"))
    Draft202012Validator.check_schema(schema)
    event = {
        "event_id": "00000000-0000-4000-8000-000000000001",
        "event_type": "receipt.confirmed",
        "schema_version": 1,
        "tenant_id": "00000000-0000-4000-8000-000000000002",
        "aggregate_type": "receipt",
        "aggregate_id": "00000000-0000-4000-8000-000000000003",
        "aggregate_version": 2,
        "occurred_at": "2026-10-01T09:00:00Z",
        "recorded_at": "2026-10-01T09:00:01Z",
        "producer": "core",
        "payload": {
            "owner_user_id": "00000000-0000-4000-8000-000000000004",
            "transaction_id": "00000000-0000-4000-8000-000000000005",
            "receipt_date": "2026-10-01",
            "currency": "RUB",
            "merchant": "Market",
            "items": [{
                "item_id": "00000000-0000-4000-8000-000000000006",
                "name": "Tea 500g",
                "quantity": "2.000000",
                "line_sum": "50.00",
            }],
        },
    }

    Draft202012Validator(schema).validate(event)


def test_budget_event_schema_accepts_versioned_family_limit():
    schema = json.loads((ROOT / "contracts/events/finance.budget.v1.schema.json").read_text("utf-8"))
    Draft202012Validator.check_schema(schema)
    event = {
        "event_id": "00000000-0000-4000-8000-000000000001",
        "event_type": "budget.updated",
        "schema_version": 1,
        "tenant_id": "00000000-0000-4000-8000-000000000002",
        "aggregate_type": "budget",
        "aggregate_id": "00000000-0000-4000-8000-000000000003",
        "aggregate_version": 1,
        "occurred_at": "2026-10-01T12:00:00Z",
        "recorded_at": "2026-10-01T12:00:01Z",
        "producer": "core",
        "correlation_id": "trace-1",
        "payload": {"budgetKey": "еда", "scope": "family", "amount": "22000.00", "active": True, "version": 1},
    }
    Draft202012Validator(schema).validate(event)


def test_debt_event_schema_accepts_atomic_payment_state():
    schema = json.loads((ROOT / "contracts/events/finance.debt.v1.schema.json").read_text("utf-8"))
    Draft202012Validator.check_schema(schema)
    event = {
        "event_id": "00000000-0000-4000-8000-000000000001",
        "event_type": "debt.payment_recorded",
        "schema_version": 1,
        "tenant_id": "00000000-0000-4000-8000-000000000002",
        "aggregate_type": "debt",
        "aggregate_id": "00000000-0000-4000-8000-000000000003",
        "aggregate_version": 2,
        "occurred_at": "2026-10-01T12:00:00Z",
        "recorded_at": "2026-10-01T12:00:01Z",
        "producer": "core",
        "correlation_id": "trace-1",
        "payload": {"name": "Credit card", "openingBalance": "1000.00", "currentBalance": "700.00",
                    "interestRate": "12.0000", "minimumPayment": "100.00", "status": "open", "version": 2,
                    "action": "debt.payment_recorded"},
    }
    Draft202012Validator(schema).validate(event)


def test_debt_event_schema_accepts_adjusted_payment_state():
    schema = json.loads((ROOT / "contracts/events/finance.debt.v1.schema.json").read_text("utf-8"))
    event = {
        "event_id": "00000000-0000-4000-8000-000000000001",
        "event_type": "debt.payment_adjusted",
        "schema_version": 1,
        "tenant_id": "00000000-0000-4000-8000-000000000002",
        "aggregate_type": "debt",
        "aggregate_id": "00000000-0000-4000-8000-000000000003",
        "aggregate_version": 3,
        "occurred_at": "2026-10-01T12:00:00Z",
        "recorded_at": "2026-10-01T12:00:01Z",
        "producer": "core",
        "correlation_id": "trace-2",
        "payload": {"name": "Credit card", "openingBalance": "1000.00", "currentBalance": "650.00",
                    "interestRate": "12.0000", "minimumPayment": "100.00", "status": "open", "version": 3,
                    "action": "debt.payment_adjusted"},
    }

    Draft202012Validator(schema).validate(event)


@pytest.mark.parametrize("amount", ["0", "-1.00", "01.00", "12.345", 12.5])
def test_event_schema_rejects_invalid_money_representation(amount):
    schema = json.loads((ROOT / "contracts/events/finance.transaction.v1.schema.json").read_text("utf-8"))
    event = {
        "event_id": "00000000-0000-4000-8000-000000000001",
        "event_type": "transaction.created",
        "schema_version": 1,
        "tenant_id": "00000000-0000-4000-8000-000000000002",
        "aggregate_type": "transaction",
        "aggregate_id": "00000000-0000-4000-8000-000000000003",
        "aggregate_version": 1,
        "occurred_at": "2026-10-01T12:00:00Z",
        "recorded_at": "2026-10-01T12:00:01Z",
        "producer": "core",
        "payload": {
            "owner_user_id": "00000000-0000-4000-8000-000000000004",
            "type": "expense",
            "amount": amount,
            "currency": "RUB",
            "category_code": "food",
            "description": "Lunch",
            "status": "posted",
            "financial_occurred_at": "2026-10-01T12:00:00Z",
        },
    }

    with pytest.raises(ValidationError):
        Draft202012Validator(schema).validate(event)


def test_feature_registry_accounts_for_all_legacy_and_new_parity_ids():
    registry = yaml.safe_load((ROOT / "contracts/parity/feature-parity.yaml").read_text("utf-8"))
    features = registry["features"]

    assert [feature["id"] for feature in features] == [f"F{i:02d}" for i in range(1, 61)]
    assert all(feature["status"] in {"planned", "in_progress", "complete", "blocked", "not_applicable"}
               for feature in features)
    assert all(feature["feature"] and feature["acceptance"] for feature in features)
    by_id = {feature["id"]: feature for feature in features}
    assert all(by_id[feature_id]["status"] == "in_progress"
               for feature_id in ("F11", "F59", "F60"))
    assert by_id["F12"]["status"] == "complete"
    assert by_id["F13"]["status"] == "complete"
    assert by_id["F14"]["status"] == "complete"
    assert all(by_id[feature_id]["tests"] and by_id[feature_id]["evidence"]
               for feature_id in ("F11", "F12", "F13", "F14", "F59", "F60"))


def test_advice_waste_internal_contract_is_strict_and_fixture_matches_schema():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-intelligence-v1.yaml").read_text("utf-8"))
    operation = spec["paths"]["/internal/v1/analytics/waste"]["post"]
    assert operation["operationId"] == "buildAdviceWasteReport"
    assert operation["security"] == [{"serviceBearer": []}]
    assert operation["requestBody"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/WasteRequest",
    }
    assert operation["responses"]["200"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/WasteReport",
    }
    request_schema = spec["components"]["schemas"]["WasteRequest"]
    line_schema = spec["components"]["schemas"]["WasteLine"]
    report_schema = spec["components"]["schemas"]["WasteReport"]
    assert request_schema["additionalProperties"] is False
    assert line_schema["additionalProperties"] is False
    assert report_schema["additionalProperties"] is False
    fixture = json.loads((ROOT / "contracts/analytics/advice-waste.v1.json").read_text("utf-8"))
    request_document = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": {"schemas": spec["components"]["schemas"]},
        "$ref": "#/components/schemas/WasteRequest",
    }
    Draft202012Validator(request_document, format_checker=FormatChecker()).validate(fixture["input"])
    expected_report = dict(fixture["expected"], inputVersion="0" * 64)
    report_document = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": {"schemas": spec["components"]["schemas"]},
        "$ref": "#/components/schemas/WasteReport",
    }
    Draft202012Validator(report_document, format_checker=FormatChecker()).validate(expected_report)


def test_advice_f43_internal_contract_is_strict_and_fixture_matches_schemas():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-intelligence-v1.yaml").read_text("utf-8"))
    operation = spec["paths"]["/internal/v1/analytics/advice/f43"]["post"]
    assert operation["operationId"] == "buildAdviceF43Report"
    assert operation["security"] == [{"serviceBearer": []}]
    assert operation["requestBody"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/F43Request",
    }
    assert operation["responses"]["200"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/F43Report",
    }
    schemas = spec["components"]["schemas"]
    for name in ("F43Request", "F43Item", "F43Recalculation", "F43Report", "F43SavingsReport",
                 "F43SavingsGroup", "F43TrendReport", "F43TrendWeek", "F43EffectsReport", "F43Effect",
                 "F43PendingEffect", "F43RecalculationReport", "F43RecalculationWindow"):
        assert schemas[name]["additionalProperties"] is False
    fixture = json.loads((ROOT / "contracts/analytics/advice-f43.v1.json").read_text("utf-8"))
    request_document = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": {"schemas": schemas},
        "$ref": "#/components/schemas/F43Request",
    }
    report_document = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": {"schemas": schemas},
        "$ref": "#/components/schemas/F43Report",
    }
    Draft202012Validator(request_document, format_checker=FormatChecker()).validate(fixture["input"])
    Draft202012Validator(report_document, format_checker=FormatChecker()).validate(fixture["expected"])
    assert fixture["expected"]["effects"]["causalityClaim"] is False
    assert fixture["expected"]["savings"]["label"] == "theoretical_ceiling_not_actual_savings"


def test_advice_evidence_internal_contract_is_strict_and_fixture_matches_schema():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-intelligence-v1.yaml").read_text("utf-8"))
    operation = spec["paths"]["/internal/v1/analytics/advice/evidence-groups"]["post"]
    assert operation["operationId"] == "groupAdviceEvidence"
    assert operation["security"] == [{"serviceBearer": []}]
    assert operation["requestBody"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/EvidenceRequest",
    }
    assert operation["responses"]["200"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/EvidenceResponse",
    }
    schemas = spec["components"]["schemas"]
    for name in ("EvidenceRequest", "EvidenceLine", "EvidenceResponse", "EvidenceGroup"):
        assert schemas[name]["additionalProperties"] is False
    fixture = json.loads((ROOT / "contracts/analytics/advice-evidence.v1.json").read_text("utf-8"))
    request_document = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": {"schemas": schemas},
        "$ref": "#/components/schemas/EvidenceRequest",
    }
    response_document = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": {"schemas": schemas},
        "$ref": "#/components/schemas/EvidenceResponse",
    }
    Draft202012Validator(request_document, format_checker=FormatChecker()).validate(fixture["input"])
    Draft202012Validator(response_document, format_checker=FormatChecker()).validate(
        dict(fixture["expected"], inputVersion="0" * 64))


def test_finance_report_exposes_server_computed_local_day_chart_data():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    report = spec["components"]["schemas"]["FinanceReport"]
    assert "expenseByDay" in report["required"]
    assert report["properties"]["expenseByDay"] == {
        "type": "object", "additionalProperties": {"type": "string", "pattern": r"^\d+\.\d{2}$"},
        "description": "Server-computed expenses keyed by local ISO date, including empty dates as zero.",
    }


def test_finance_report_waste_contract_accepts_complete_and_unavailable_results():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    report = spec["components"]["schemas"]["FinanceReport"]
    assert "waste" in report["required"]
    assert report["properties"]["waste"] == {"$ref": "#/components/schemas/AdviceWasteReport"}
    waste_document = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": {"schemas": spec["components"]["schemas"]},
        "$ref": "#/components/schemas/AdviceWasteReport",
    }
    fixture = json.loads((ROOT / "contracts/analytics/advice-waste.v1.json").read_text("utf-8"))
    validator = Draft202012Validator(waste_document, format_checker=FormatChecker())
    validator.validate(dict(fixture["expected"], inputVersion="0" * 64))
    validator.validate({
        "available": False,
        "reasonCode": "analytics_unavailable",
        "algorithmVersion": None,
        "completeness": "partial",
        "asOf": "2026-10-02T00:00:00Z",
        "inputVersion": None,
        "fromDate": "2026-10-01",
        "toDate": "2026-10-01",
        "reviewedSpend": None,
        "optionalSpend": None,
        "optionalShare": None,
        "reviewedItemCount": 0,
        "optionalItemCount": 0,
        "missingAmountCount": 0,
        "byVerdict": [],
        "bySource": {},
        "topItems": [],
        "repeats": [],
        "corrected": [],
        "optionalByDay": {},
    })


def test_bank_import_preview_contract_is_strict_and_exposes_web_and_api_routes():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    for route in (
        "/bff/tenants/{tenantId}/imports",
        "/bff/tenants/{tenantId}/imports/{importId}/preview",
        "/bff/tenants/{tenantId}/imports/{importId}/rows/{rowId}",
        "/bff/tenants/{tenantId}/imports/{importId}/rows/{rowId}/category",
        "/bff/tenants/{tenantId}/imports/{importId}/classify",
        "/bff/tenants/{tenantId}/imports/{importId}/confirm",
        "/bff/tenants/{tenantId}/imports/{importId}/undo",
        "/api/v1/tenants/{tenantId}/imports",
        "/api/v1/tenants/{tenantId}/imports/{importId}/preview",
        "/api/v1/tenants/{tenantId}/imports/{importId}/rows/{rowId}",
        "/api/v1/tenants/{tenantId}/imports/{importId}/rows/{rowId}/category",
        "/api/v1/tenants/{tenantId}/imports/{importId}/classify",
        "/api/v1/tenants/{tenantId}/merchant-mappings",
        "/api/v1/tenants/{tenantId}/imports/{importId}/confirm",
        "/api/v1/tenants/{tenantId}/imports/{importId}/undo",
    ):
        assert route in spec["paths"]
    assert spec["paths"]["/bff/tenants/{tenantId}/imports"]["post"]["requestBody"]["content"].keys() == {
        "multipart/form-data"
    }
    confirm = spec["paths"]["/bff/tenants/{tenantId}/imports/{importId}/confirm"]["post"]
    assert "If-Match" in [p.get("name") for p in confirm["parameters"]]
    assert any(p.get("$ref", "").endswith("/IdempotencyKey") for p in confirm["parameters"])
    assert "409" in spec["paths"]["/api/v1/tenants/{tenantId}/imports/{importId}/undo"]["post"]["responses"]

    schema = json.loads((ROOT / "contracts/schemas/bank-import-preview.v1.schema.json").read_text("utf-8"))
    preview = {
        "id": "00000000-0000-4000-8000-000000000001", "tenantId": "00000000-0000-4000-8000-000000000002",
        "state": "needs_review", "revision": 1, "quality": "mismatch", "parseVersion": "tbank-pdf.v1",
        "periodStart": "2026-10-01", "periodEnd": "2026-10-01", "parsedExpenseTotal": "13.00",
        "parsedIncomeTotal": "0.00", "expectedExpenseTotal": "12.00", "expectedIncomeTotal": "0.00",
        "expenseTotal": "12.00", "incomeTotal": "0.00", "refundTotal": "0.00", "transferTotal": "0.00",
        "excludedTotal": "1.00", "includedCount": 1, "excludedCount": 1, "createdCount": 0, "duplicateCount": 0,
        "rows": [{
            "id": "00000000-0000-4000-8000-000000000003", "ordinal": 0, "operationDate": "2026-10-01",
            "operationTime": "12:30", "signedAmount": "-12.00", "amount": "12.00", "kind": "purchase",
            "transactionType": "expense", "included": True, "selectionSource": "default", "exclusionReason": None,
            "duplicate": False, "duplicateOfTransactionId": None, "outcome": "pending", "transactionId": None,
            "merchant": "Market", "description": "Payment", "cardLast4": "1234", "categoryCode": None,
            "categorySource": "unknown", "suggestedCategoryCode": None, "categoryConfidence": None,
            "clarificationCandidate": False,
        }, {
            "id": "00000000-0000-4000-8000-000000000004", "ordinal": 1, "operationDate": "2026-10-01",
            "operationTime": "12:31", "signedAmount": "-1.00", "amount": "1.00", "kind": "fee",
            "transactionType": None, "included": False, "selectionSource": "default", "exclusionReason": "bank_fee",
            "duplicate": False, "duplicateOfTransactionId": None, "outcome": "pending", "transactionId": None,
            "merchant": None, "description": "Bank fee", "cardLast4": "1234", "categoryCode": None,
            "categorySource": "unknown", "suggestedCategoryCode": None, "categoryConfidence": None,
            "clarificationCandidate": False,
        }],
    }
    validator = Draft202012Validator(schema)
    assert list(validator.iter_errors(preview)) == []
    assert list(validator.iter_errors({**preview, "unexpected": True}))
    assert list(validator.iter_errors({**preview, "quality": "valid", "parsedExpenseTotal": 13.0}))


def test_merchant_classification_and_mapping_contracts_are_scoped_and_strict():
    finance_api = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    intelligence_api = yaml.safe_load((ROOT / "contracts/openapi/finance-intelligence-v1.yaml").read_text("utf-8"))
    assert "/bff/tenants/{tenantId}/merchant-mappings" in finance_api["paths"]
    assert "/api/v1/tenants/{tenantId}/merchant-mappings" in finance_api["paths"]
    assert "/internal/v1/merchant-classifications" in intelligence_api["paths"]
    assert "merchant-classification" in intelligence_api["components"]["parameters"]["AiTaskKind"]["schema"]["enum"]

    context_schema = json.loads((ROOT / "contracts/schemas/merchant-classification-context.v1.schema.json").read_text("utf-8"))
    result_schema = json.loads((ROOT / "contracts/schemas/merchant-classification-result.v1.schema.json").read_text("utf-8"))
    context_validator = Draft202012Validator(context_schema)
    result_validator = Draft202012Validator(result_schema)
    assert list(context_validator.iter_errors({"merchants": ["market", "taxi"]})) == []
    assert list(context_validator.iter_errors({"merchants": ["market"], "tenantId": "private"}))
    valid_result = {"classifications": [{"merchant": "market", "categoryCode": "еда", "confidence": "0.880"}],
                    "modelVersion": "test-model", "promptVersion": "merchant-category.v1"}
    assert list(result_validator.iter_errors(valid_result)) == []
    assert list(result_validator.iter_errors({**valid_result, "unexpected": True}))

    for route_prefix in ("/bff", "/api/v1"):
        preview_path = f"{route_prefix}/tenants/{{tenantId}}/merchant-reclassifications/preview"
        apply_path = f"{route_prefix}/tenants/{{tenantId}}/merchant-reclassifications/apply"
        assert preview_path in finance_api["paths"]
        assert apply_path in finance_api["paths"]
        preview = finance_api["paths"][preview_path]["post"]
        apply = finance_api["paths"][apply_path]["post"]
        assert preview["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "/MerchantReclassificationPreviewRequest"
        )
        assert apply["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "/MerchantReclassificationApplyRequest"
        )
        assert "412" in preview["responses"] and "412" in apply["responses"]
        if route_prefix == "/bff":
            assert preview["security"] == [{"bffSession": []}]
            assert apply["security"] == [{"bffSession": []}]
            assert any(p.get("$ref", "").endswith("/CsrfToken") for p in preview["parameters"])
            assert any(p.get("$ref", "").endswith("/CsrfToken") for p in apply["parameters"])
        assert any(p.get("$ref", "").endswith("/MerchantReclassificationIdempotencyKey")
                   for p in apply["parameters"])

    reclassification_schemas = finance_api["components"]["schemas"]
    for name in (
        "MerchantReclassificationPreviewRequest", "MerchantReclassificationCandidate",
        "MerchantReclassificationPreview", "MerchantReclassificationCandidateSelection",
        "MerchantReclassificationApplyRequest", "MerchantReclassificationApplyResult",
    ):
        assert reclassification_schemas[name]["additionalProperties"] is False


def test_receipt_photo_jobs_are_owner_scoped_idempotent_and_private():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    for prefix in ("/bff", "/api/v1"):
        upload_path = f"{prefix}/tenants/{{tenantId}}/receipts/photo-jobs"
        status_path = f"{prefix}/tenants/{{tenantId}}/receipt-jobs/{{jobId}}"
        assert upload_path in spec["paths"]
        assert status_path in spec["paths"]
        upload = spec["paths"][upload_path]["post"]
        status = spec["paths"][status_path]["get"]
        assert "multipart/form-data" in upload["requestBody"]["content"]
        part = upload["requestBody"]["content"]["multipart/form-data"]["schema"]
        assert part["required"] == ["file"]
        assert part["properties"]["file"] == {"type": "string", "format": "binary"}
        assert "202" in upload["responses"]
        assert any(p.get("$ref", "").endswith("/IdempotencyKey") for p in upload["parameters"])
        assert status["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "/ReceiptProcessingJob"
        )
        if prefix == "/bff":
            assert upload["security"] == [{"bffSession": []}]
            assert status["security"] == [{"bffSession": []}]
            assert any(p.get("$ref", "").endswith("/CsrfToken") for p in upload["parameters"])
    assert spec["components"]["schemas"]["ReceiptProcessingJob"]["additionalProperties"] is False


def test_receipt_processing_migration_persists_leases_and_one_job_per_document():
    migration = next((path for path in (ROOT / "services/core/src/main/resources/db/migration").glob("V28*")), None)
    assert migration is not None, "receipt photo processing needs an additive V28 job migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    for value in ("create table receipt_processing_jobs", "lease_expires_at", "next_attempt_at",
                  "idempotency_key", "request_hash", "force row level security"):
        assert value in normalized
    assert "unique (tenant_id, owner_user_id, idempotency_key)" in normalized
    assert "unique (tenant_id, document_id)" in normalized


def test_receipt_reading_contract_exposes_typed_vision_fallback_provenance():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    reading = spec["components"]["schemas"]["ReceiptOcrReading"]
    assert {"visionFallbackReason", "ocrFallbackReason"}.issubset(reading["required"])
    assert reading["properties"]["visionFallbackReason"] == {"type": ["string", "null"], "maxLength": 64}
    assert reading["properties"]["ocrFallbackReason"] == {"type": ["string", "null"], "maxLength": 64}


def test_receipt_job_can_persist_distinct_ocr_and_vision_readings():
    migration = next((path for path in (ROOT / "services/core/src/main/resources/db/migration").glob("V29*")), None)
    assert migration is not None, "Vision support needs an additive multi-reader job migration"
    normalized = re.sub(r"--[^\n]*", "", migration.read_text(encoding="utf-8").lower())
    assert "drop index receipt_readings_source_job_once_idx" in normalized
    assert "unique index receipt_readings_source_job_reader_once_idx" in normalized
    assert "(tenant_id, source_job_id, reader)" in normalized
    assert "receipt_processing_jobs_stage_check" in normalized
    assert "'vision'" in normalized


def test_personal_inflation_is_member_scoped_and_has_an_explicit_no_history_contract():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    paths = spec["paths"]
    schema = spec["components"]["schemas"]["PersonalInflation"]
    for path in ("/api/v1/tenants/{tenantId}/analytics/personal-inflation",
                 "/bff/tenants/{tenantId}/analytics/personal-inflation"):
        operation = paths[path]["get"]
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "PersonalInflation")
    assert paths["/bff/tenants/{tenantId}/analytics/personal-inflation"]["get"]["security"] == [
        {"bffSession": []}]
    telegram = paths["/internal/v1/telegram/actions/personal-inflation"]["post"]
    assert telegram["security"] == [{"telegramServiceToken": []}]
    assert telegram["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ResolveTelegramActorContext")
    assert telegram["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "PersonalInflation")
    assert schema["required"] == ["available", "reasonCode", "asOf", "windowDays", "productCount",
                                  "basketBefore", "basketNow", "indexPercent", "rising", "falling"]
    assert schema["properties"]["windowDays"]["const"] == 90
    assert schema["properties"]["reasonCode"]["enum"] == ["available", "insufficient_history"]
    assert schema["properties"]["basketBefore"]["type"] == ["string", "null"]
    item = spec["components"]["schemas"]["PersonalInflationItem"]
    assert item["properties"]["olderPurchaseCount"]["minimum"] == 2
    assert item["properties"]["windowPurchaseCount"]["minimum"] == 1


def test_recurring_v1_golden_fixture_matches_contract_and_cross_field_rules():
    root = ROOT / "contracts/analytics/recurring-v1"
    request = json.loads((root / "request.json").read_text(encoding="utf-8"))
    projection = json.loads((root / "projection.json").read_text(encoding="utf-8"))
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-intelligence-v1.yaml").read_text("utf-8"))
    schemas = spec["components"]["schemas"]

    Draft202012Validator(schemas["RecurringRequest"], format_checker=FormatChecker()).validate(request)
    projection_schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": {"schemas": schemas},
        "$ref": "#/components/schemas/RecurringProjection",
    }
    Draft202012Validator(projection_schema, format_checker=FormatChecker()).validate(projection)

    expenses = projection["expense_series"]
    incomes = projection["income_series"]
    by_expense_id = {series["id"]: series for series in expenses}
    by_income_id = {series["id"]: series for series in incomes}
    assert len(by_expense_id) == len(expenses)
    assert len(by_income_id) == len(incomes)
    assert projection["time_zone"] == request["timeZone"]
    assert [series["id"] for series in projection["due_soon"]] == [
        series["id"] for series in expenses if 0 <= series["days_until"] <= 3
    ]
    assert all(by_expense_id[series["id"]] == series for series in projection["due_soon"])
    assert {series["id"] for series in projection["overdue"]} == {
        series["id"] for series in expenses if series["days_until"] < 0
    }
    assert all(by_expense_id[series["id"]] == series for series in projection["overdue"])
    expected_income = min((series for series in incomes if series["days_until"] >= 0),
                          key=lambda series: series["days_until"], default=None)
    assert projection["next_income"] == expected_income
    assert expected_income is None or by_income_id[expected_income["id"]] == expected_income

    estimates = {}
    for series in expenses:
        amount = Decimal(series["amount"])
        monthly = amount if series["period_code"] == "month" else amount * 30 / series["period_days"]
        estimates[series["currency"]] = estimates.get(series["currency"], Decimal(0)) + monthly
    rounded = {currency: value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
               for currency, value in estimates.items()}
    assert projection["monthly_expense_estimates"] == {
        currency: f"{amount:.2f}" for currency, amount in rounded.items()
    }
    assert projection["monthly_expense_estimate"] == (
        f"{next(iter(rounded.values())):.2f}" if len(rounded) == 1 else None
    )


def test_recurring_projection_routes_require_member_bound_responses():
    spec = yaml.safe_load((ROOT / "contracts/openapi/finance-api-v1.yaml").read_text("utf-8"))
    for path, security in (
        ("/api/v1/tenants/{tenantId}/analytics/recurring", None),
        ("/bff/tenants/{tenantId}/analytics/recurring", [{"bffSession": []}]),
    ):
        operation = spec["paths"][path]["get"]
        assert operation.get("security") == security
        assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "RecurringProjection"
        )
        assert {"403", "404", "503"}.issubset(operation["responses"])
    private = spec["paths"]["/internal/v1/telegram/actions/recurring"]["post"]
    assert private["security"] == [{"telegramServiceToken": []}]
    assert private["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "ResolveTelegramActorContext"
    )
    for path, security, csrf_required in (
        ("/api/v1/tenants/{tenantId}/analytics/recurring/{seriesId}/mute", None, False),
        ("/bff/tenants/{tenantId}/analytics/recurring/{seriesId}/mute", [{"bffSession": []}], True),
    ):
        for method in ("put", "delete"):
            operation = spec["paths"][path][method]
            assert operation.get("security") == security
            if csrf_required:
                assert any(parameter == {"$ref": "#/components/parameters/CsrfToken"}
                           for parameter in operation["parameters"])
            assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
                "RecurringProjection"
            )
            assert {"403", "404", "503"}.issubset(operation["responses"])
    for action in ("mute", "unmute"):
        telegram = spec["paths"][f"/internal/v1/telegram/actions/recurring/{{seriesId}}/{action}"]["post"]
        assert telegram["security"] == [{"telegramServiceToken": []}]
        assert telegram["requestBody"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "ResolveTelegramActorContext"
        )
        assert telegram["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
            "RecurringProjection"
        )
    projection_schema = spec["components"]["schemas"]["RecurringProjection"]
    assert "mutedSeries" in projection_schema["required"]
    assert projection_schema["properties"]["mutedSeries"]["items"]["$ref"].endswith("RecurringSeries")
