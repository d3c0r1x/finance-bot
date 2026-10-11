# F48 Android import selection and confirmation

## Authority and scope

- Parent requirement: approved `PLAN.md` F48, “Preview импорта, подтверждение, покупки/доходы/refund, skipped”.
- Core is authoritative for row defaults, allowed overrides, totals, counts, revision, and committed result.
- Implement Android row-type selection and explicit batch confirmation only. Do not implement F49 duplicate decisions, bulk import, or undo; do not add F50 classification or F51 merchant/category rules.
- Preserve F47 PDF upload/read-only preview and user-owned files.

## Acceptance

1. Preview DTO preserves Core `id`, `tenantId`, `revision`, `state`, exact server totals/counts, row UUID/ordinal, signed values, type, included state, selection source, exclusion reason, duplicate/outcome fields, and existing F47 display fields. Do not calculate or optimistically change money/counts on Android.
2. Writers can choose `expense`, `income`, `refund`, `transfer`, or reset to Core default (`null`) for a row in `needs_review`. Send authenticated `PATCH /api/v1/tenants/{tenantId}/imports/{importId}/rows/{rowId}` with JSON `transactionType` and quoted `If-Match` revision. Apply only the full preview returned by Core. Serialize mutations; stale `412` reloads current preview before editing resumes.
3. Show Core included/excluded counts and expense, income, refund, transfer, and excluded totals. Every row retains its signed amount, including fee/exclusion reason; selecting a type must never flip a sign. Core's valid `transfer_in` row kind must be accepted even though the current OpenAPI enum omits it; correct the contract and test the drift.
4. Confirmation is an explicit user action, shown only for writer and `needs_review` preview with `includedCount > 0`. POST exactly `{"confirmed":true}` with quoted current `If-Match` and a deterministic per-import `Idempotency-Key` reused for any retry. Disable selection and confirmation while a mutation is pending. No automatic POST retry on 401/session replacement. On ambiguous network outcome, reload by preview GET before offering another confirmation; never invent success.
5. Render committed state/counts from Core. Do not show an undo action in F48. A viewer can read an owner-authorized preview but sees no selection or confirmation controls; Core remains the final authorization boundary.
6. Old-session, old-tenant, stale-request, navigation, and screen responses cannot overwrite a replacement login or tenant. Do not retain PDF bytes/source URI. Preserve F47 API 23+ behavior.

## Test-first evidence

- Observe API RED for missing PATCH/confirm methods and exact request contract before implementation.
- Observe Compose RED for row controls, aggregate summaries, explicit confirmation, pending/read-only/state gating, and Core-authoritative result.
- Observe contract RED because Core accepts `transfer_in` but OpenAPI omits it; then make schema and contract test agree.
- Run focused JVM, F47/F48 instrumentation and relevant Android regression on isolated API 27, contract suite, APK builds, install/launch, SHA-256, and `git diff --check`.

## Verification limits

Local synthetic API/UI tests do not prove live Android-to-Core OIDC, PostgreSQL behavior beyond existing Core acceptance, production tenant isolation, or actual T-Bank import quality. Record these as remaining parity gates.
