# F55 local AI, cloud opt-in, fallback, and model management

**Global source:** `PLAN.md` F55, `docs/specs/CONTINUATION.md` AI Gateway rules, `ai/llm.py` legacy model resolver, and `docs/SECURITY.md`.

**Acceptance:** Self-hosted runs against local Ollama with no third-party AI API. The configured local model wins when installed; if absent, the resolver may choose only a verified installed non-embedding model from an ordered local preference list. No installed model means AI-dependent work reports unavailable while manual finance flows remain usable. Remote inference requires explicit `cloud-opt-in`, uses only the configured model and server-held key, and never occurs as automatic fallback. Vision continues to use only its explicit allowlist. Operators can inspect and manage model configuration through deployment settings and Ollama's local CLI.

## Design boundary

- Reuse legacy priority order and exact model/tag matching; never replace a specific missing tag with a weaker tag of the same family.
- Cache bounded local model inventory briefly. Bound HTTP timeout/body/model count and exclude embedding models from general text-model selection.
- Do not query or enumerate remote model inventories, including in private health diagnostics. Cloud mode sends only configured inference requests when the service/request policies both opt in; remote inventory status is explicitly reported as unchecked.
- No model download/delete API or tenant-facing model switch. Install/update/remove models on the self-hosted machine with Ollama CLI; select through server environment. Web status remains sanitized and is not an operator console.
- If Ollama is absent, preserve manual transaction/budget paths and surface the unavailable state. Never fabricate an AI result or persist a financial mutation from a fallback.

## Task 1: Model inventory and resolver tests

1. Add tests for configured exact match, ordered preference, exact tag behavior, embeddings exclusion, no-model fallback, inventory auth/timeout/size bound/cache, and local-only endpoint validation.
2. Add app-level tests proving the local adapter uses only discovered local model inventory and cloud mode skips inventory discovery.
3. Run focused tests and capture RED before implementation.

## Task 2: Implement safe local selection

1. Add a bounded short-lived local inventory client and pure deterministic resolver.
2. Wire local Ollama text provider selection to that resolver while preserving Vision allowlist and actual model provenance.
3. Add no Ollama text provider when local inventory is missing/unavailable or configuration is invalid, so the request returns safe `503 unavailable`; never route around local-only policy. Keep Tesseract OCR independent.

## Task 3: Operator configuration and unavailable fallback

1. Document local model list/pull/remove and selection through server environment; document cloud opt-in and key storage boundaries.
2. Add tests that no model inventory is fetched remotely (including health), and manual finance flows do not depend on AI.
3. Update `.env.example` with ordered preference config and explicit safety comments.

## Task 4: Verification and commit

1. Run full intelligence/Telegram/contracts suite, Core check, full Web tests/build, and `git diff --check`.
2. Review request traces to prove local-only never contacts a remote model and cloud is never selected by fallback.
3. Update `.agent/PROGRESS.md`; commit and push only F55 files and progress.

## Execution constraints

- Follow `PLAN → ACCEPTANCE → TESTS → OBSERVED RED → IMPLEMENT → GREEN → REGRESSION → VERIFY → COMMIT → PROGRESS`.
- Model inventory is metadata only. No finance/customer payload is sent to Ollama while enumerating models.
- Live cloud inference and model quality remain unverified; do not make billable cloud calls as part of tests.

## Verification record

- RED observed: an empty local inventory still allowed the configured model to return 200; the resolver selected a configured embedding model; Ollama connection errors triggered repeated inventory reads. Cross-surface tests then failed on remote health inventory enumeration, Core's diagnostic-code allowlist, and the missing RU/EN explanation.
- GREEN: Python contracts/migration/Intelligence/Telegram suite — 296 passed, 1 optional skip; Core `:services:core:check --rerun-tasks`; Go `test ./... -count=1` and `go vet ./...`; Web 92 tests and production build; `git diff --check`.
- Cloud calls were not made. Go was run from a SHA-256-verified portable archive in `%TEMP%`; system PATH and installation were unchanged.
- Commit/push: `5df8b06017dcc92a5bd5ac8368958d8423038165` (`feat(F55): resolve safe local AI models`), pushed to `origin/feat/saas-rewrite`. Three workflows passed. Core/PostgreSQL's Gradle check passed, but the final DB-backed contract step failed because its public-event schema test scanned internal `export` and `goal` outbox payloads.
- Follow-up verification: reproduced the `KeyError: aggregate_type` locally against the isolated PostgreSQL DB; fixed the integration test to select the stored aggregate type and validate only the four published event schemas. Targeted DB-backed test passed; full `pytest tools/contracts -q` passed 79/79. Follow-up commit/push and GitHub rerun pending.
