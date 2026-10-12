import hashlib

import httpx

from services.python.intelligence.evaluation import (
    GoldenCase,
    evaluate_providers,
    load_golden_dataset,
)
from services.python.intelligence.gateway import ProviderRouter, UnsupportedCapability, VertexAIProvider


_DEFAULT_MOCK_TRANSPORT = object()


class FixedProvider:
    name = "fixture-provider"
    requires_cloud_opt_in = False
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
    requires_cloud_opt_in = False
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


def _vertex_candidate(calls, *, transport=_DEFAULT_MOCK_TRANSPORT, token_supplier=None,
                     model_version="synthetic-gemini-v1", model_id="synthetic-model"):
    def respond(request):
        calls.append(("http", request))
        return httpx.Response(200, json={
            "candidates": [{
                "content": {"role": "model", "parts": [{"text": (
                    '{"type":"expense","amount":"200.00","categoryCode":"transport",'
                    '"subcategoryCode":null,"description":"Taxi",'
                    '"occurredAt":"2026-10-01T09:00:00+03:00"}'
                )}]},
                "finishReason": "STOP",
            }],
            "modelVersion": model_version,
        })

    def supply_token():
        calls.append(("token", "requested"))
        return "synthetic-evaluation-token"

    return VertexAIProvider(
        project_id="synthetic-project",
        location="us-central1",
        model_id=model_id,
        access_token_supplier=token_supplier or supply_token,
        transport=httpx.MockTransport(respond) if transport is _DEFAULT_MOCK_TRANSPORT else transport,
        declared_capabilities=frozenset({"text", "structured_output"}),
    )


def test_vertex_golden_evaluation_requires_explicit_candidate_selection():
    dataset = load_golden_dataset()
    case = next(case for case in dataset.cases if case.case_id == "transaction-expense-local-time-v1")
    calls = []

    report = evaluate_providers(
        [_vertex_candidate(calls)], [case], execution_policy="cloud-opt-in",
    )

    result = report["providers"][0]
    assert result["provider"] == "vertex_ai"
    assert result["cases"] == [{
        "caseId": "transaction-expense-local-time-v1",
        "status": "failed",
        "failureCode": "unsupported_capability",
    }]
    assert calls == []


def test_explicit_vertex_candidate_passes_synthetic_golden_without_report_leaks():
    dataset = load_golden_dataset()
    case = next(case for case in dataset.cases if case.case_id == "transaction-expense-local-time-v1")
    calls = []
    provider = _vertex_candidate(calls)

    report = evaluate_providers(
        [provider], [case], execution_policy="cloud-opt-in",
        candidate_provider_names={"vertex_ai"},
    )

    result = report["providers"][0]
    assert result["eligible"] is True
    assert result["supportedCases"] == result["passedCases"] == 1
    assert result["cases"][0]["status"] == "passed"
    assert result["modelVersions"] == [
        "vertex-sha256:" + hashlib.sha256(b"synthetic-model").hexdigest()[:16]
    ]
    assert result["promptVersions"] == ["transaction-draft.v2"]
    assert result["usage"] == result["cost"] == "unknown"
    assert [call[0] for call in calls] == ["http"]
    assert provider.enabled is False
    assert "synthetic-evaluation-token" not in str(report)
    assert case.context["text"] not in str(report)
    assert "Taxi" not in str(report)


def test_vertex_response_provenance_cannot_echo_canary_into_report():
    dataset = load_golden_dataset()
    case = next(case for case in dataset.cases if case.case_id == "transaction-expense-local-time-v1")
    canary = "private-canary-token-123"
    calls = []

    report = evaluate_providers(
        [_vertex_candidate(calls, model_version=canary)], [case],
        execution_policy="cloud-opt-in", candidate_provider_names={"vertex_ai"},
    )

    result = report["providers"][0]
    assert result["modelVersions"] == [
        "vertex-sha256:" + hashlib.sha256(b"synthetic-model").hexdigest()[:16]
    ]
    assert result["promptVersions"] == ["transaction-draft.v2"]
    assert canary not in str(report)


def test_vertex_configured_model_id_is_hashed_before_report_provenance():
    dataset = load_golden_dataset()
    case = next(case for case in dataset.cases if case.case_id == "transaction-expense-local-time-v1")
    canary = "private-canary-token-123"
    calls = []

    report = evaluate_providers(
        [_vertex_candidate(calls, model_id=canary)], [case],
        execution_policy="cloud-opt-in", candidate_provider_names={"vertex_ai"},
    )

    result = report["providers"][0]
    assert result["modelVersions"] != [canary]
    assert canary not in str(report)


def test_vertex_rejects_invalid_model_configuration_before_credentials():
    dataset = load_golden_dataset()
    case = next(case for case in dataset.cases if case.case_id == "transaction-expense-local-time-v1")
    canary = "private model config"
    calls = []

    def token_supplier():
        calls.append("token")
        return "synthetic-evaluation-token"

    report = evaluate_providers(
        [_vertex_candidate(calls, model_id=canary, token_supplier=token_supplier)], [case],
        execution_policy="cloud-opt-in", candidate_provider_names={"vertex_ai"},
    )

    result = report["providers"][0]
    assert result["cases"] == [{
        "caseId": "transaction-expense-local-time-v1",
        "status": "failed",
        "failureCode": "invalid_configuration",
    }]
    assert canary not in str(report)
    assert calls == []


def test_explicit_vertex_candidate_fails_closed_without_injected_mock_transport():
    dataset = load_golden_dataset()
    case = next(case for case in dataset.cases if case.case_id == "transaction-expense-local-time-v1")
    calls = []

    def token_supplier():
        calls.append("token")
        raise AssertionError("evaluation must reject network transport before requesting credentials")

    provider = _vertex_candidate(calls, transport=None, token_supplier=token_supplier)

    report = evaluate_providers(
        [provider], [case], execution_policy="cloud-opt-in",
        candidate_provider_names={"vertex_ai"},
    )

    assert report["providers"][0]["cases"] == [{
        "caseId": "transaction-expense-local-time-v1",
        "status": "blocked",
        "failureCode": "evaluation_transport_required",
    }]
    assert calls == []


def test_vertex_candidate_is_blocked_before_token_under_local_only():
    dataset = load_golden_dataset()
    case = next(case for case in dataset.cases if case.case_id == "transaction-expense-local-time-v1")
    calls = []

    report = evaluate_providers(
        [_vertex_candidate(calls)], [case], execution_policy="local-only",
        candidate_provider_names={"vertex_ai"},
    )

    result = report["providers"][0]
    assert result["eligible"] is False
    assert result["cases"] == [{
        "caseId": "transaction-expense-local-time-v1",
        "status": "blocked",
        "failureCode": "policy_denied",
    }]
    assert calls == []


def test_vertex_remains_unselectable_by_production_router_for_both_policies():
    provider = _vertex_candidate([])

    for policy in ("local-only", "cloud-opt-in"):
        try:
            ProviderRouter([provider]).select({"text", "structured_output"}, policy, "transaction-draft")
        except UnsupportedCapability:
            continue
        raise AssertionError("Vertex must remain disabled in the production router")
    assert provider.enabled is False


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
    assert result["modelVersions"] == [
        "model-sha256:" + hashlib.sha256(b"fixture-1").hexdigest()[:16]
    ]
    assert result["promptVersions"] == [
        "prompt-sha256:" + hashlib.sha256(b"draft.v1").hexdigest()[:16]
    ]
    assert "Такси 200" not in str(report)
    assert "200.00" not in str(report)


def test_evaluation_omits_untrusted_provenance_that_contains_canary_data():
    case = GoldenCase(
        case_id="draft-example",
        task_kind="transaction-draft",
        context={"timezone": "Europe/Moscow", "now": "2026-10-01T12:00:00+03:00"},
        expected={
            "type": "expense", "amount": "200.00", "categoryCode": "transport",
            "subcategoryCode": None, "description": "Taxi",
            "occurredAt": "2026-10-01T09:00:00+03:00",
        },
    )
    canary = "private-canary-token-123"
    provider = FixedProvider({
        **case.expected,
        "modelVersion": canary,
        "promptVersion": "prompt-" + canary,
    })

    report = evaluate_providers([provider], [case], execution_policy="local-only")

    result = report["providers"][0]
    assert result["modelVersions"] and result["modelVersions"][0].startswith("model-sha256:")
    assert result["promptVersions"] and result["promptVersions"][0].startswith("prompt-sha256:")
    assert canary not in str(report)


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
    provider.requires_cloud_opt_in = True

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
