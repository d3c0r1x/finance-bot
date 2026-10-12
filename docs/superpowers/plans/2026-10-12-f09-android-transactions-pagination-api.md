# F09 Android transaction filters and cursor API

## Authority

- Approved V1 requirement: `PLAN.md` F09.
- Parity target: `contracts/parity/feature-parity.yaml` F09 Android mapping.
- Existing boundary: `FinanceApi.transactions()` and `FinanceModels.transactionQuery()`.
- This slice verifies the Android API contract with synthetic HTTP only. It does not claim live Core/OIDC integration.

## Acceptance

1. Android sends an authenticated GET for the selected tenant with search, type, date range, and member filters.
2. The response parser retains exact transaction fields and the opaque next cursor.
3. A second request with the same filters sends the returned cursor unchanged and parses the next page.
4. Test uses no real account or live service, writes tokens only to isolated test preferences/Keystore alias, and does not change production behavior unless a failing assertion proves a defect.

## Test-first sequence

1. Add focused MockWebServer instrumentation coverage around `FinanceApi.transactions()`.
2. Run only this test on dedicated `CodexFinanceBotApi27` / API 27.
3. If behavior is already correct, record no behavioral RED and keep production unchanged. If a meaningful assertion fails, diagnose before implementation.
4. Run focused parity tests, AndroidTest APK assembly, and diff checks; update parity/progress with verified scope and limits.

## Limits

- MockWebServer proves request construction, authorization header, cursor round trip, and response parsing only.
- Core pagination ordering, authenticated Android-to-Core/OIDC, and tenant isolation require live integration coverage.
