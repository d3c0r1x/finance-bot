# F07 Android income API contract

## Authority

- Approved V1 requirement: `PLAN.md` F07.
- Existing Android API boundary: `FinanceApi` transaction draft, explicit confirmation, and history methods.
- This slice adds Android contract evidence only. Core/PostgreSQL owns persistence and income-versus-expense/budget invariants.

## Acceptance

1. Android submits income text to the tenant draft endpoint with a stable idempotency key.
2. Draft response retains exact decimal amount, description, pending state, and timezone-bearing transaction date.
3. Draft review sends no confirmation or direct posted-transaction request.
4. Explicit confirmation uses the draft ID, expected version, and idempotency key.
5. Income history retains type, exact amount, description, source, and transaction date.
6. Test stays isolated from real accounts and live services.

## Test-first result

- Added `FinanceIncomeDraftApiTest` against MockWebServer.
- First run exposed a Kotlin nullable-path compile error in the test; corrected before behavioral assertions ran.
- No behavioral RED: existing `FinanceApi` already satisfies this contract. Do not change production code to manufacture a failure.
- Independent review confirmed request order and endpoint/payload match the current API methods. It also confirmed the test cannot prove database persistence because MockWebServer returns a fixed history fixture.

## Verification

- `:app:assembleDebug :app:assembleDebugAndroidTest` — PASS.
- Ran only `FinanceIncomeDraftApiTest` on dedicated `CodexFinanceBotApi27` / Android 8.1, API 27 (`emulator-5554`) — 1/1 PASS.
- Existing Core PostgreSQL acceptance `telegramIncomeDraftPreservesSourceAndDateWithoutChangingExpenseOrBudgetSpend` covers no posted totals before confirmation, income-only totals after confirmation, and zero budget spend.

## Limits

- Android test uses synthetic HTTP fixtures; authenticated Android-to-Core/OIDC integration remains unverified.
- Database persistence and budget invariants are not claimed by this Android test.
