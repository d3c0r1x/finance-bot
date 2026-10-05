from services.python.intelligence.evaluation import (
    GoldenCase,
    evaluate_providers,
    load_golden_dataset,
)
import httpx


class FixedProvider:
    name = "fixture-provider"
    capabilities = frozenset({"text", "structured_output"})
    supported_tasks = frozenset({"transaction-draft"})

    def __init__(self, response, endpoint=None):
        self.response = response
        self.endpoint = endpoint
        self.calls = []
        self.failure = None

    async def execute(self, task_kind, context, timeout, api_key=""):
        self.calls.append((task_kind, context, timeout))
        if self.failure:
            raise self.failure
        return self.response


class DatasetProvider:
    name = "dataset-provider"
    capabilities = frozenset({"text", "structured_output", "vision"})
    supported_tasks = frozenset({"budget-proposal", "transaction-draft", "receipt-ocr", "receipt-vision",
                                 "merchant-classification"})

    async def execute(self, task_kind, context, timeout, api_key=""):
        if task_kind == "budget-proposal":
            return {"shares": {"food": "60.00", "housing": "40.00"}}
        if task_kind == "transaction-draft":
            return {
                "type": "expense", "amount": "200.00", "categoryCode": "transport",
                "subcategoryCode": None, "description": "Taxi",
                "occurredAt": "2026-10-01T09:00:00+03:00",
            }
        if task_kind == "receipt-ocr":
            return {"text": "SAMPLE MARKET 2026-09-01 BREAD 1 100.00 TOTAL 100.00", "words": []}
        if task_kind == "merchant-classification":
            return {"classifications": [
                {"merchant": "пятёрочка", "categoryCode": "еда", "confidence": "0.900"},
                {"merchant": "яндекс go", "categoryCode": "транспорт", "confidence": "0.900"},
            ]}
        return {
            "store": "SAMPLE MARKET", "date": "2026-09-01", "total": "100.00",
            "items": [{"name": "BREAD", "quantity": "1", "unitPrice": "100.00", "lineSum": "100.00"}],
        }


def test_versioned_golden_dataset_uses_only_synthetic_cases():
    dataset = load_golden_dataset()

    assert dataset.version == "finance-ai-golden.v1"
    assert {case.task_kind for case in dataset.cases} == {
        "budget-proposal", "transaction-draft", "receipt-ocr", "receipt-vision", "merchant-classification"
    }
    assert len({case.case_id for case in dataset.cases}) == len(dataset.cases)
    assert all(case.context.get("syntheticReceipt") for case in dataset.cases
               if case.task_kind.startswith("receipt-"))


def test_all_provider_adapters_use_the_same_versioned_golden_cases():
    dataset = load_golden_dataset()

    report = evaluate_providers([DatasetProvider()], dataset.cases, execution_policy="local-only")

    result = report["providers"][0]
    assert result["eligible"] is True
    assert result["supportedCases"] == len(dataset.cases)
    assert result["passedCases"] == len(dataset.cases)


def test_evaluation_reports_gate_without_echoing_context_or_provider_output():
    case = GoldenCase(
        case_id="draft-example",
        task_kind="transaction-draft",
        context={"text": "Такси 200", "timezone": "Europe/Moscow", "now": "2026-10-01T12:00:00+03:00"},
        expected={
            "type": "expense", "amount": "200.00", "categoryCode": "transport",
            "subcategoryCode": None, "description": "Такси",
            "occurredAt": "2026-10-01T11:00:00+02:00",
        },
    )
    provider = FixedProvider({**case.expected, "modelVersion": "fixture-1", "promptVersion": "draft.v1"})

    report = evaluate_providers([provider], [case], execution_policy="local-only", minimum_pass_rate=1.0)

    result = report["providers"][0]
    assert result["eligible"] is True
    assert result["supportedCases"] == 1
    assert result["passedCases"] == 1
    assert result["cases"][0]["caseId"] == "draft-example"
    assert result["cases"][0]["status"] == "passed"
    assert isinstance(result["cases"][0]["latencyMs"], int)
    assert "Такси 200" not in str(report)
    assert "200.00" not in str(report)


def test_evaluation_fails_gate_on_invalid_output_without_leaking_exception_text():
    case = GoldenCase(
        case_id="draft-example",
        task_kind="transaction-draft",
        context={"text": "private user text", "timezone": "Europe/Moscow", "now": "2026-10-01T12:00:00+03:00"},
        expected={"type": "expense", "amount": "200.00", "categoryCode": "transport",
                  "subcategoryCode": None, "description": "Такси",
                  "occurredAt": "2026-10-01T11:00:00+02:00"},
    )
    provider = FixedProvider({"description": "private model output"})

    report = evaluate_providers([provider], [case], execution_policy="local-only", minimum_pass_rate=1.0)

    result = report["providers"][0]
    assert result["eligible"] is False
    assert result["cases"][0]["caseId"] == "draft-example"
    assert result["cases"][0]["status"] == "failed"
    assert result["cases"][0]["failureCode"] == "invalid_output"
    assert "private" not in str(report)


def test_local_only_evaluation_blocks_remote_provider_before_call():
    case = GoldenCase(
        case_id="draft-example", task_kind="transaction-draft",
        context={"text": "Taxi 200", "timezone": "Europe/Moscow", "now": "2026-10-01T12:00:00+03:00"},
        expected={"type": "expense", "amount": "200.00", "categoryCode": "transport",
                  "subcategoryCode": None, "description": "Taxi",
                  "occurredAt": "2026-10-01T11:00:00+02:00"},
    )
    provider = FixedProvider({**case.expected}, endpoint="https://remote.example")

    report = evaluate_providers([provider], [case], execution_policy="local-only", minimum_pass_rate=1.0)

    assert provider.calls == []
    assert report["providers"][0]["eligible"] is False
    assert report["providers"][0]["cases"] == [
        {"caseId": "draft-example", "status": "blocked", "failureCode": "policy_denied"}
    ]


def test_evaluation_classifies_provider_timeout_separately():
    case = GoldenCase(
        case_id="draft-example", task_kind="transaction-draft",
        context={"text": "Taxi 200", "timezone": "Europe/Moscow", "now": "2026-10-01T12:00:00+03:00"},
        expected={"type": "expense", "amount": "200.00", "categoryCode": "transport",
                  "subcategoryCode": None, "description": "Taxi",
                  "occurredAt": "2026-10-01T09:00:00+03:00"},
    )
    provider = FixedProvider({})
    provider.failure = httpx.ReadTimeout("private timeout detail")

    report = evaluate_providers([provider], [case], execution_policy="local-only")

    assert report["providers"][0]["eligible"] is False
    assert report["providers"][0]["cases"] == [
        {"caseId": "draft-example", "status": "failed", "failureCode": "timeout"}
    ]
