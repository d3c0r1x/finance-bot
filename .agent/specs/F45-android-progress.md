# F45 Android progress and history

**Source:** approved `.agent/specs/F45-goal-lifecycle.md` and `PLAN.md` F45. Core remains authoritative; F46 outcome delivery and E8 legacy database rehearsal are separate gates.

## Scope

- Render the server-owned `activeProgress` purchase note and every returned `history` row in the existing Goals screen. Preserve Core array order, including imported legacy rows and nullable `goalId`, `spent`, and `met`.
- Show current Core candidates after cancellation or completion, but never auto-accept a new candidate. Keep all receipt and transaction data read-only.
- Localize progress/history in RU/EN. Preserve exact amount strings and unknown values; never turn `null` into zero or claim causation/savings.
- After successful receipt confirmation, invalidate cached goal overview for that tenant. Re-entering Goals performs the existing authenticated Core GET and displays refreshed progress; failed confirmation does not invalidate it.
- Viewer can read progress/history but cannot accept/cancel. Do not add progress/completion endpoints or client-side lifecycle calculations.

## Acceptance

- Compose tests cover count progress plus known spend in RU/EN, sum progress with unknown amounts and null verdicts, newest-first returned history including `origin=legacy`, and available candidates after completion without implicit acceptance. Viewer can read all returned progress/history without mutation controls.
- Unit tests prove successful receipt invalidation is limited to the cached matching tenant; failed receipt and other tenants preserve the cache. The receipt success path performs invalidation only after the guarded Core confirmation succeeds.
- Run F45 Android tests first and observe RED, then F44/F43/F42/report/model regression, `GoalResponsePolicyTest`, contract suite, APK build/install/launch on isolated API 27 and `git diff --check`.
- Local Android parity may be marked partial only; live authenticated Android-to-Core OIDC and real tenant isolation remain unverified. F45’s legacy SQLite rehearsal is still the separate E8 gate.
