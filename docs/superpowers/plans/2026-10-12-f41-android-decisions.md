# F41 Android decision controls

## Authority and scope

- Approved V1 `PLAN.md` F41: a model-only guess must not block a purchase or become a human decision without explicit confirmation.
- Extend Android evidence for explicit confirm/allow/revoke controls, viewer read-only behavior, and the confirm API mapping.
- Do not change Core policy, server APIs, or production Android behavior unless a focused test demonstrates a defect.

## Acceptance

1. A writer can explicitly allow a rule-backed product and revoke an existing allow decision.
2. A writer can revoke a confirmed model guess; confirm remains explicit.
3. Only owner/admin/member roles see decision controls; viewer and unknown roles are read-only and cannot emit a decision callback.
4. Android confirm sends the authenticated tenant-scoped Core decision `confirmed` and parses the refreshed confirmed-key list.
5. Tests use synthetic state and an isolated test-only token vault; no real accounts or user session storage.
6. Keep Android parity `partial` for live authenticated Core/OIDC and tenant-isolation E2E.

## Test-first sequence

1. Add focused Compose and MockWebServer tests before production edits.
2. Build APKs only; install/run tests solely on dedicated API 27 `CodexFinanceBotApi27` (`emulator-5554`) using explicit ADB targeting. Never use Gradle `connectedAndroidTest`.
3. If current behavior already meets acceptance, record no behavioral RED and do not modify production code to manufacture one.
4. Update Android F41 registry evidence and `.agent/PROGRESS.md`; run focused parity/contracts, review the diff, commit the bounded slice, and push the approved feature branch.

## Limits

- Compose and MockWebServer tests prove local UI/API contracts only, not database persistence, live OIDC, or tenant authorization.
- Do not touch the unrelated API 35 Planerka emulator or user-owned untracked paths.

## Verification result

- Registry contract RED first confirmed F41 did not list the API test or the local decision-control evidence.
- New API-27 Compose RED exposed a fail-open UI: an unsupported tenant role saw the confirm action. Production now allows controls only for explicit `owner`, `admin`, or `member` roles; Core remains the authorization authority.
- API 27 focused instrumentation passed: writer allow/revoke, viewer read-only, unknown-role read-only, existing model-guess confirmation, and authenticated tenant-scoped API confirm/readback (5/5).
- Full `FinanceScreensTest` passed 93/93 on dedicated Android 8.1/API 27. Debug and AndroidTest APKs assembled; Android JVM unit task passed.
- Parity registry plus OpenAPI/contracts passed 75/75. The registry still marks F41 Android partial for live authenticated Core/OIDC and tenant-isolation E2E.
