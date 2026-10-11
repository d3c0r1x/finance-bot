# F54 Android capability status

**Authority:** `PLAN.md` F54, `.agent/PROGRESS.md` F59, and the approved F54 Android parity contract. Global Python/Core/Web F54 remains complete.

**Scope:** Add a read-only Android status screen backed by the existing authenticated tenant BFF `/bff/tenants/{tenantId}/health`. Display only Core-sanitized `localAi`, `receiptVision`, and `receiptOcr` availability. Do not add provider settings, private diagnostics, a new endpoint, or claims about live provider health.

## TDD sequence

1. Test a strict DTO parser for exact capability keys, allowed statuses and diagnostic codes; capture RED before implementation.
2. Test the authenticated tenant GET path and bearer/session behavior with the existing mock HTTP harness; reject malformed response data safely.
3. Add a localized RU/EN screen with pending, unavailable, retry and manual refresh states. Render safe diagnostic explanations with generic fallback; never render raw response content.
4. Test all statuses, both locales, retry, refresh, membership-facing navigation and raw-secret sentinel suppression.
5. Run focused unit/API/screen tests, relevant Android regression, contracts, APK builds and `git diff --check` on the isolated API-27 emulator (`emulator-5554` only).
6. Update parity evidence and `.agent/PROGRESS.md`; independent review; commit/push only after GREEN and verify remote HEAD.

## Acceptance

- Authenticated Android requests use the existing tenant BFF route; Core remains responsible for membership authorization.
- UI explains OCR, receipt vision and local AI availability in RU and EN, including unavailable/disabled states and safe retry.
- Unknown/malformed server values fail closed to a generic unavailable state or bounded error; no provider URL, model, filesystem path, exception, credentials or response dump appears in UI.
- Android F54 stays partial until live authenticated Core/OIDC and real tenant authorization are verified.
