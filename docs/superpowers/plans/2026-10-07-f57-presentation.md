# F57 presentation parity and safe fallbacks

**Global source:** `PLAN.md` F57 and `docs/specs/CONTINUATION.md` RU/EN requirement.

## Scope

- Keep Core/J DTOs semantic and locale-neutral. Status and reason codes stay stable machine values; Python, Web and Android own localized labels.
- Python presentation: exact money formatting from decimal strings, Russian plural forms, safe Telegram Markdown interpolation, and report PNG/text renderers with RU/EN labels.
- PNG reports must use a platform-resolved Cyrillic-capable font and preserve readable bounded long labels. If Pillow/font rendering is unavailable, deliver UTF-8 text fallback rather than failing the Telegram report request.
- Web: centralize RU/EN money and semantic status formatting for the existing dashboard/report/budget presentation paths; do not convert amounts through binary floating point, including displayed budget differences.
- Android: format decimal-string money by locale and translate Core budget/food status codes in the overview and report surfaces; unknown future codes show a safe localized neutral label, never raw enum text.
- Test synthetic strings only: Cyrillic, Markdown punctuation, large/negative/decimal amounts, plural boundaries, long labels, and known/unknown semantic codes. No account data or private receipt photos.

## Test-first gates

1. Inventory current Python formatting/font/report paths, existing Web amount formatters, Android screens/status DTOs, and Core semantic codes. Record files and preserved behavior here.
2. Add failing Python unit tests for exact decimal formatting, Markdown safety, plural boundaries, Cyrillic PNG labels/fallback, and localized report text. Run focused RED.
3. Add failing Web tests for shared locale formatting and semantic code labels; add failing Android unit tests for decimal formatting and status localization. Keep Core response codes unchanged and add/extend contract assertions for representative codes.
4. Implement the narrow shared presentation functions and migrate current affected call sites. Keep business calculations and DTO values unchanged.
5. GREEN focused Python/Web/Android suites; run the established receipt/report/Telegram regressions, Core contract checks, Android instrumentation/build, and `git diff --check`.
6. Commit only after local gates pass; push and check relevant GitHub workflows. Record each command and SHA in `.agent/PROGRESS.md`.

## Acceptance

- RU/EN outputs render readable money, status names and report text; no machine code leaks into the named Android screens.
- Money formatting preserves the decimal-string value and handles grouping, fractional values and negative values deterministically.
- User-controlled Telegram strings cannot break the selected parse mode; ordinary text remains visible.
- PNG output handles Cyrillic and long labels with the resolved font; unavailable image rendering returns usable localized UTF-8 text.
- Java/Core continues returning stable semantic codes, not localized strings; existing API behavior and authorization remain unchanged.
- Automated tests prove the behavior without live providers, user accounts, or personal receipt samples.

## Initial inventory

- Python: `utils/formatting.py` uses float conversion and removes `* _ backtick [ ]` in `md_safe`; `plural_ru` exists but lacks a focused test module. `utils/fonts.py` only resolves monospaced fonts. `services/python/presentation/report_renderer.py` renders English-only reports with Pillow's default font; text fallback is English-only. Telegram `render_report` currently does not pass a language.
- Web: money `Intl.NumberFormat` is repeated across `App.tsx`, `GoalsPanel`, `PersonalInflationPanel`, `AdviceAnalyticsPanel`, `ReceiptsPanel`, `RecurringPanel`, `ShoppingPanel`, `ProductCatalogPanel`, and `ImportsPanel`; current code sometimes converts decimal strings to `Number`.
- Android: `MainActivity.kt` interpolates decimal strings directly and exposes `totalLimitStatus`, `paceStatus`, and related codes in dashboard/report/budget screens.
- Core/J: DTOs already carry semantic fields such as `reasonCode`, `limitStatus`, and `paceStatus`. Preserve these machine codes; translation belongs in clients.

## Observed baseline

- Python RED: `pytest tests/test_presentation_formatting.py -q -p no:cacheprovider` — 5 failed, 2 passed. Failures prove float precision loss for a large decimal-string amount, missing localized report text/PNG APIs, missing Cyrillic sans-font resolver, and missing language-aware renderer fallback.
- Web RED: `pnpm exec vitest run src/formatting.test.ts` — module `src/formatting.ts` does not exist.
- Android initially had no configured/system Java. Verification used an official portable Temurin 17 runtime under `%TEMP%` only; no system Java or permanent PATH settings were changed.
- Add an integration acceptance that Telegram `/report` forwards the Telegram user's RU/EN language to the report renderer; retain default English behavior for users without a supported language.

## Implementation and GREEN evidence — 2026-10-07

- Python now formats decimal-string values with `Decimal`, renders reports and scheduled digests in Russian or English, uses a platform-resolved Cyrillic sans font, bounds long PNG labels, and falls back to localized UTF-8 text. Telegram report captions/digests share the locale-aware render helpers. User-provided report labels remain inert text; report requests infer RU/EN from Telegram language.
- Web now shares exact decimal-string money and semantic status formatters across the existing panels. Decimal subtraction/comparison for displayed limit balances uses `BigInt`; numeric conversion remains only for chart geometry and non-money ratios. Statuses stay canonical Core codes in API contracts.
- Android now formats decimal-string money in RU/EN and localizes budget/pace codes on dashboard, report, budget and budget alerts. Unknown future codes use neutral localized text. No Java/Core DTO values changed.
- New focused contracts cover Python precision/plural/escaping/report/fallback/font behavior, Web locale/large-number/status/exact-subtraction behavior, Android money/status behavior, Telegram language forwarding and localized captions/digests, and unchanged semantic Core enums. Fixtures are synthetic.
- Observed RED: Python focused tests failed before implementation (5 failed/2 passed); Web formatter test initially failed because the module did not exist; new Web exact-subtraction test failed because helper exports were absent; Android 8.1 instrumentation first failed on five assertions requiring the old raw format, then one outdated RU top-item expectation.
- GREEN: Python/contracts/report/Telegram command: `pytest tools/contracts/test_contracts.py tests/test_presentation_formatting.py services/python/presentation/tests services/python/telegram_gateway/tests -q -p no:cacheprovider` — 199 passed. Web `pnpm test` — 95 passed; `pnpm build` — passed. Android Gradle `testDebugUnitTest` — passed from a temporary ASCII junction path after the normal Cyrillic working directory caused Gradle's test worker to report `ClassNotFoundException`; all unit classes compiled. Android `assembleDebug assembleDebugAndroidTest` — passed. The new Android 8.1/API 27 isolated emulator installed/launched `com.decorix.finance.debug`; Android instrumentation reports `OK (50 tests)`. The user's existing AVDs were not used.
- `git diff --check` passed before final progress edits; rerun before commit. No Core or API contract behavior changed; no private receipts or personal finance values were used.
- Next: record final diff/test evidence in `.agent/PROGRESS.md`, rerun `git diff --check`, commit/push F57, and inspect any GitHub workflows triggered by the commit before starting F58.
