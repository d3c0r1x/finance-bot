# F60.2 Explicit provider locality and fail-closed routing

**Authority:** approved F60 and `docs/specs/CONTINUATION.md` §A03.

**Goal:** make external-processing policy independent of an adapter's endpoint shape. Endpoint-less cloud providers and adapters without an explicit locality classification must never be selected by `local-only`.

**Acceptance:**

- Every built-in provider declares whether cloud opt-in is required. Local Ollama/Tesseract are local; remote Ollama, Ollama Vision on remote endpoints, Vertex, and any Vision pool containing an untrusted/remote child require opt-in.
- Under `local-only`, the router rejects endpoint-less cloud and unclassified providers before execution. It selects local providers only.
- Under `cloud-opt-in`, configured cloud providers can be selected.
- A selected local provider failure propagates without attempting a cloud provider. Existing Vision-model retries remain within the configured provider pool and never change processing locality.
- No automatic provider execution fallback, credential routing, or Vertex activation is added.

**Test-first sequence:** add router and no-fallback tests first and observe RED; implement explicit locality on built-in providers and conservative router filtering; run intelligence and contract suites; review provider registrations and update progress/parity evidence; commit and push.

**Scope boundary:** this closes routing-policy semantics only. It does not prove live provider quality, cloud consent UX, OIDC, production quotas, or deployment security. F60 remains partial until shared evaluations and the remaining release gates pass.
