# F44 Android — member goal candidates

## Scope

Implement the Android counterpart for F44 using Core-owned goal candidates. This slice ends at selecting a count/sum preference, explicitly accepting one candidate, displaying the immutable active 30-day terms, and cancelling that goal. F45 progress/history presentation and F46 outcome delivery remain separate slices.

## Contract and ownership

- Use authenticated Core routes only: `GET /api/v1/tenants/{tenantId}/goals`, `PUT .../goals/unit`, `POST .../goals`, and `POST .../goals/{goalId}/cancel`. Never call the internal Go candidates endpoint from Android.
- Every overview and action is scoped to the active tenant and current member. Viewer can read but cannot change the unit, accept, or cancel. Hide write controls and preserve safe 403/404 handling.
- Candidate cadence, thresholds, and money come from Core/Go. Android does no business calculation. Keep decimal values as strings and unknown amounts as null; never substitute zero.
- Unit changes apply only to future proposals. Accepted unit, target, acceptance timestamp, and 30-day end date remain server-owned and immutable.
- Accept only from an explicit candidate action with that overview's candidate key and watermark. A stale 409 refreshes candidates and explains that they changed; it must not silently submit a different candidate.
- Active goal blocks another acceptance. Cancellation is explicit, writer-only, and refreshes the overview after success.
- Render product vs category, count vs sum, skipped reasons and nullable estimates in RU/EN. No unsupported claims about savings.
- Parse F45 `activeProgress` and `history` fields to preserve the complete Core overview contract, but do not render them in F44; they belong to F45 Android.

## Acceptance

- Typed strict DTO parsing covers the full overview, active goal, candidates, skipped reasons, optional member keys, nullable money, and F45 fields. Reject malformed enums, dates, UUIDs, and inconsistent values.
- MockWebServer tests verify authenticated route/method/body, 201 accept, preference update, cancellation, 409/403 handling, and exact/null values.
- Compose tests verify RU/EN, explicit count/sum save, explicit accept, viewer read-only, active-goal single-active constraint, immutable Core terms, cancellation, stale refresh, skipped reasons, and no null-as-zero presentation.
- Run tests first and record RED. Then run focused Android API and screen tests, F43/F42/report/model regression, APK build/install/launch on isolated API 27, contract suite, and `git diff --check`.
- Mark Android parity PARTIAL until live authenticated Android-to-Core OIDC acceptance/cancellation is verified.
