# F07 Android income draft UI

## Authority

- Approved V1 requirement: `PLAN.md` F07.
- Android counterpart: F07 mapping in `contracts/parity/feature-parity.yaml`.
- Existing UI boundary: `FinanceScreen` transaction draft editor.

## Acceptance

1. User can change a pending expense draft to income in the Android editor.
2. Unsaved changes keep confirmation disabled.
3. Saving sends `type=income` with the draft's amount, description, and version.
4. UI refreshes to the saved draft version before explicit confirmation becomes available.
5. Confirmation callback stays untouched until the user taps confirm.
6. Test uses synthetic in-memory UI state and never writes real session or financial data.

## Test-first sequence

1. Add a focused Compose instrumentation test for type selection, save-before-confirm, and explicit confirmation.
2. Run only the test on dedicated `CodexFinanceBotApi27` / API 27 using an APK and explicit device serial.
3. If existing UI meets acceptance, record no behavioral RED and keep production unchanged. If a meaningful assertion fails, diagnose before implementation.
4. Run focused parity checks and APK build; update parity/progress with verified scope and limits.

## Limits

- Compose test proves UI callbacks and gating only.
- Core persistence, income totals/budget invariants, and live Android-to-Core/OIDC remain covered by separate server tests or external integration gates.

## Verification result

- The existing editor already supports changing the draft type; no behavioral RED or production change was needed.
- Focused `FinanceScreensTest#pendingExpenseCanBeSavedAsIncomeBeforeExplicitConfirmationOfRefreshedVersion` passed 1/1 on dedicated `CodexFinanceBotApi27` / API 27 using direct ADB serial targeting.
- Debug and AndroidTest APK assembly passed.
