import pytest

from services.python.intelligence.gateway import (
    OllamaProvider,
    PolicyDenied,
    UnsupportedCapability,
    ProviderRouter,
    VertexAIProvider,
    authorize_provider,
    require_capabilities,
)
from services.python.intelligence.vision import OllamaVisionProvider


@pytest.mark.parametrize("host", ["http://localhost:11434", "http://127.0.0.1:11434", "http://ollama:11434",
                                  "http://host.docker.internal:11434"])
def test_local_only_allows_only_local_ollama_endpoints(host):
    endpoint = authorize_provider(host, "local-only")
    assert endpoint.endpoint == host
    assert endpoint.execution_policy == "local-only"


@pytest.mark.parametrize("host", ["https://ollama.example", "http://10.0.0.8:11434"])
def test_local_only_rejects_remote_ollama_before_network_call(host):
    with pytest.raises(PolicyDenied):
        authorize_provider(host, "local-only")


def test_remote_ollama_requires_explicit_cloud_opt_in():
    endpoint = authorize_provider("https://ollama.example", "cloud-opt-in")
    assert endpoint.endpoint == "https://ollama.example"
    assert endpoint.execution_policy == "cloud-opt-in"


def test_provider_must_declare_each_requested_capability():
    provider = OllamaProvider("http://ollama:11434", "qwen-text")
    require_capabilities(provider, {"text", "structured_output"})
    with pytest.raises(UnsupportedCapability):
        require_capabilities(provider, {"vision"})
    with pytest.raises(UnsupportedCapability):
        require_capabilities(provider, {"embeddings"})


def test_capability_router_selects_only_provider_that_satisfies_task():
    text_provider = OllamaProvider("http://ollama:11434", "qwen-text")
    vision_provider = OllamaVisionProvider("http://ollama:11434", "qwen-vl")

    selected = ProviderRouter([text_provider, vision_provider]).select(
        {"vision", "structured_output"}, "local-only", "receipt-vision")

    assert selected is vision_provider
    with pytest.raises(UnsupportedCapability):
        ProviderRouter([text_provider]).select({"vision"}, "local-only", "receipt-vision")


def test_text_provider_supports_amount_free_basket_review_task():
    provider = OllamaProvider("http://ollama:11434", "qwen-text")

    selected = ProviderRouter([provider]).select(
        {"text", "structured_output"}, "local-only", "receipt-basket-review")

    assert selected is provider


def test_capability_router_rejects_remote_match_under_local_only():
    remote = OllamaVisionProvider("https://remote.example", "qwen-vl")

    with pytest.raises(PolicyDenied):
        ProviderRouter([remote]).select({"vision", "structured_output"}, "local-only", "receipt-vision")


def test_vertex_provider_is_disabled_until_explicit_evaluation_and_canary_gate():
    provider = VertexAIProvider()

    assert provider.name == "vertex_ai"
    assert provider.enabled is False
    assert provider.capabilities == frozenset()
    with pytest.raises(PolicyDenied):
        provider.enable(gate_approved=False, execution_policy="cloud-opt-in")
