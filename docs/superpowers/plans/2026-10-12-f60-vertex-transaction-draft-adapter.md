# F60.1 Vertex transaction-draft adapter seam

**Authority:** approved V1 F60, `docs/specs/CONTINUATION.md` §C, and F60 in `contracts/parity/feature-parity.yaml`.

**Goal:** add a bounded Vertex `generateContent` adapter for the existing `transaction-draft` task while preserving the shared draft prompt, output validator, and provenance fields. The adapter remains disabled in the production router.

**Scope:** one text + structured-output task; explicit project/location/model configuration; injected OAuth access-token supplier and HTTP transport; strict request/response handling; passed timeouts; existing deadline/schema validation; `modelVersion` and `promptVersion` output. No cloud SDK, credentials, provider activation, app wiring, or live request.

**Acceptance:**

- Default Vertex adapter declares only capabilities and task it implements but remains `enabled=False`, and the normal router rejects it under both policies.
- Direct offline contract tests prove the supported task sends the shared validated prompt to the documented Vertex `generateContent` route; uses only the injected access token; ignores the generic Ollama `api_key`; requests bounded JSON output and caps streamed response bytes; validates exact existing transaction draft fields; and returns model/prompt provenance.
- Unsupported task, missing token source, malformed response, and invalid model output fail closed without unintended network calls.
- No changes to financial business rules or user-facing service routing; no Vertex activation or live/canary/quality claim.

**Test-first sequence:** focused gateway tests first, observe behavioral RED, implement the smallest adapter, rerun focused tests, then the full Python intelligence suite, parity/contracts, and `git diff --check`. Keep full F60 partial until shared comparative evals, cloud execution policy propagation, credentials, quotas/safety controls, canary, and rollback are proven under `CONTINUATION.md` §C.
