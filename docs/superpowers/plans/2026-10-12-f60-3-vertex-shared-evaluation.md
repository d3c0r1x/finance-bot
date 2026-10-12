# F60.3 Vertex shared golden evaluation

**Authority:** approved V1 F60, `docs/specs/CONTINUATION.md` §C and A05–A06, and prior F60.1/F60.2 slices.

**Goal:** evaluate the disabled Vertex transaction-draft adapter through the same synthetic golden evaluator used by existing providers, without making Vertex selectable in production.

**Constraints:** no live credentials, cloud calls, provider activation, business routing changes, new dependencies, private samples, or report leakage. `VertexAIProvider.enabled` remains `False`. `ProviderRouter` continues to reject Vertex. Candidate evaluation requires explicit provider selection and `cloud-opt-in`; `local-only` must block before token supplier and HTTP transport.

## Acceptance

1. Without explicit candidate selection, evaluator preserves current disabled-provider result and performs no token or transport call.
2. Explicitly selected Vertex candidate plus `cloud-opt-in` runs the existing synthetic `transaction-expense-local-time-v1` golden case through `VertexAIProvider` and injected `httpx.MockTransport`; report passes the shared validator and captures model/prompt provenance, while usage/cost remain `unknown` and report omits context, output, token, and prompt text.
3. Candidate selection under `local-only` is blocked before token supplier and transport; there is no cloud fallback.
4. Vertex stays disabled and normal `ProviderRouter` still rejects it under both policies.
5. Focused evaluation/gateway tests, full Python intelligence and contract regression, `compileall`, and `git diff --check` pass.
6. Evaluation refuses Vertex unless its exact transport is `httpx.MockTransport`, supplies only a synthetic token, and reports trusted prompt provenance plus a non-reversible model fingerprint rather than provider response/configuration text.

## TDD and verification

1. Extend `test_all_provider_adapters_use_the_same_versioned_golden_cases` and add fail-closed assertions for local-only and omitted candidate opt-in.
2. Run the focused tests. Expected RED: current evaluator returns `unsupported_capability` for disabled Vertex or never makes the nominated mocked request.
3. Add the smallest evaluation-only candidate opt-in to `evaluate_providers`; keep `ProviderRouter` and provider activation unchanged.
4. Run `test_evaluation.py` and `test_gateway.py`, then the full intelligence plus contract suite and compileall.
5. Review reports for sensitive-data leakage and run `git diff --check`.

## Execution record — 2026-10-12

- **RED, before production edits:**
  `.venv\Scripts\python.exe -m pytest services\python\intelligence\tests\test_evaluation.py -q -p no:cacheprovider -k "vertex_golden_evaluation_requires or explicit_vertex_candidate or vertex_candidate_is_blocked or vertex_remains_unselectable"`
  Result: `2 failed, 2 passed, 6 deselected`. The two candidate-opt-in tests failed because `evaluate_providers` had no explicit candidate-selection argument (`unexpected keyword argument 'candidate_provider_names'`). The no-opt-in test confirmed the existing disabled-provider `unsupported_capability` result and no token/transport calls; the router test confirmed Vertex remained unselectable.
- **Review-driven RED:** independent review found the evaluator could use a configured network transport and token supplier, and copy provider-controlled provenance into reports. Added tests; first RED was `2 failed, 2 passed, 10 deselected`. A second canary test proved configured model ID could also leak before implementation (`1 failed, 13 deselected`).
- **Implementation:** added `candidate_provider_names` solely to the shared evaluation function. It bypasses the disabled production router only for an explicitly named `VertexAIProvider` under `cloud-opt-in`; `local-only` blocks before credentials. Vertex candidates require exact `httpx.MockTransport` before execute and run on a dataclass copy with a fixed synthetic token, never the caller's credential supplier. `ProviderRouter` and provider activation remain unchanged. Reports use trusted `PROMPT_VERSION` plus a SHA-256 fingerprint of a strictly validated configured model ID; remote Vertex metadata is ignored. Existing non-Vertex provenance remains represented as strict-identifier SHA-256 fingerprints. Invalid model configuration produces a fixed failure code. Reports keep usage/cost unknown and omit context, model output, raw provenance, and credentials.
- **Focused GREEN:** `.venv\Scripts\python.exe -m pytest services\python\intelligence\tests\test_evaluation.py services\python\intelligence\tests\test_gateway.py -q -p no:cacheprovider` — `50 passed`.
- **Intelligence regression:** final fresh run `.venv\Scripts\python.exe -m pytest services\python\intelligence\tests -q -p no:cacheprovider` — `152 passed, 1 skipped`. Two preceding full runs exposed intermittent Windows loopback resets; isolated `test_app.py` passed `29/29`, and the final full run passed.
- **Contract regression:** `.venv\Scripts\python.exe -m pytest tools\contracts\test_contracts.py -q -p no:cacheprovider` — `63 passed`.
- **Syntax/diff:** `.venv\Scripts\python.exe -m compileall -q services\python\intelligence` and `git diff --check` pass.
- **Independent review:** final read-only reviewer found no remaining issue; confirmed transport and credential boundaries, provider provenance preservation/redaction, and unchanged disabled routing.
- **Status:** code and regression gates GREEN. Feature commit `5d035b00340c251808d7dafc49bceec9507d9ca8` and progress commit `868fd972fa282324f87607a3a46f85c5bf597a92` were pushed; remote verification confirmed that SHA before the final progress-only update. Production routing, provider enabled state, CLI, dependencies, live credentials, and real network behavior were not changed or exercised.

**Limits:** mocked golden evaluation proves adapter integration and report safety only. Real model quality/holdout, measured usage/cost/latency, cloud-consent/configuration, credentials, quotas/safety controls, canary, and rollback remain separate F60 gates. Vertex stays off.
