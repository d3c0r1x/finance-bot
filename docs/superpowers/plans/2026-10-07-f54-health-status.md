# F54 health and capability status — execution plan

**Status: COMPLETE LOCALLY; Python, Core, contracts, Web tests/build GREEN. Commit pending.**

**Global source:** `PLAN.md` F54; legacy behavior in `services/health.py`.

**Acceptance:** The app shows whether receipt OCR, receipt vision, and local AI are usable. Health checks do not expose credentials, provider URLs, local paths, exception text, or financial/user data. Liveness remains separate from dependency status. Operators can inspect stable sanitized diagnostics; provider failure must not make the intelligence process itself unavailable.

## Design boundary

- Python intelligence owns dependency checks for Ollama text, allowlisted vision, and Tesseract. A private service-token endpoint returns stable capability state and safe diagnostic codes, never raw exception messages or configured addresses/models.
- Core calls that endpoint with its service credential and returns only sanitized user-facing availability through an authenticated tenant BFF route. Membership is required; no tenant data is passed to the health probe. Detailed diagnostics stay on the private service boundary until platform-operator authorization exists.
- Web adds a RU/EN status screen reachable from settings/navigation with a manual refresh and explicit unavailable/disabled states. It does not allow changing provider/model configuration.
- Probe calls have short timeouts and bounded model discovery. Existing `/healthz` remains a cheap liveness check and does not wait on Ollama/Tesseract.

## Task 1: Python health boundary

1. Add tests for token denial, healthy and unavailable dependencies, disabled vision, missing Tesseract, and redaction of endpoint/model/path/exception data.
2. Run the focused suite and capture RED before implementation.
3. Add a private diagnostics endpoint and injectable bounded checks; preserve `/healthz` behavior.

## Task 2: Core sanitized tenant read

1. Add Core client/controller tests for service-token use, timeout/unavailable fallback, membership denial, and safe response fields.
2. Run Core tests and capture RED before implementation.
3. Add a Core client for Python health, member-authenticated BFF read, OpenAPI schema, and fail-closed sanitized response when the intelligence service is unavailable.

## Task 3: Web status surface

1. Add component/API tests for loading, healthy, partial, unavailable, and RU/EN states; capture RED.
2. Add the localized status screen and refresh behavior without exposing provider internals.

## Task 4: Verification and commit

1. Run Python intelligence tests, focused/full Core tests, contract checks, full Web tests/build, and `git diff --check`.
2. Inspect output schemas for secret/path/URL fields and check liveness remains independent.
3. Update `.agent/PROGRESS.md`; commit and push only F54 implementation/tests/plan/progress.

## Execution constraints

- Follow `PLAN → ACCEPTANCE → TESTS → OBSERVED RED → IMPLEMENT → GREEN → REGRESSION → VERIFY → COMMIT → PROGRESS`.
- Do not add a public diagnostic endpoint. `/healthz` stays shallow and public; detailed checks require the service token.
- Do not invent platform-operator privileges. Detailed diagnostics remain private until the approved operator authorization model exists.
