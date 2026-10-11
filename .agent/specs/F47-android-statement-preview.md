# F47 Android T-Bank PDF statement preview

## Authority and scope

- Parent requirement: approved `PLAN.md` F47, “PDF Т-Банка: операции, даты, merchant/card, сверка итогов”.
- Parser and staging behavior: `docs/specs/bank-import-v1.md`; Android API: `contracts/openapi/finance-api-v1.yaml` and Core `/api/v1/tenants/{tenantId}/imports` routes.
- Implement Android upload and read-only preview only. Core remains the parsing and reconciliation authority.
- Do not implement F48 row selection or confirmation, nor F49 duplicate resolution, bulk import, or undo.

## Acceptance

1. An authenticated member can choose one PDF with Android's system document picker and upload it as multipart field `file` to the active tenant's Core imports endpoint. Enforce the 12 MiB contract before reading the whole document into memory. Do not persist the source URI or PDF.
2. The POST is never automatically replayed after 401 because import creation has no Android idempotency key. Safe preview GET may use the standard token refresh retry.
3. Parse only Core's preview DTO. Preserve server `quality` (`valid`, `mismatch`, `unverifiable`), ordered rows, signed decimal strings, date/time, merchant/description, card last four, and nullable expected totals exactly. Do not compute reconciliation or substitute zero for null.
4. Show preview period, parsed totals, any available expected totals, quality, and every returned row in Core order. Explain invalid format, scanned/no-text, unsupported PDF, and safe server failures in RU/EN; never display raw response bodies.
5. Viewer/member access follows Core: upload is hidden for viewer; a returned preview remains read-only. No row selection, type changes, transaction creation, confirmation, bulk insert, dedupe action, or undo appears in F47.
6. Late upload/GET responses from an old auth session, tenant, request, or screen cannot replace current content. Re-entering the section can reload the selected staged preview by authenticated GET.
7. Existing Android behavior and API 23+ support remain intact.

## Test evidence required

- API tests prove exact path, multipart name/content type/file bytes, PDF and size handling, strict DTO values/nulls/order, error mapping, and no 401 POST replay.
- Compose tests prove RU/EN quality and reconciliation display, safe parser explanations, ordered signed rows and row details, viewer read-only behavior, and lack of confirmation/mutation controls.
- Observe API and UI RED against missing F47 Android behavior before adding production code.
- Run focused instrumentation and relevant Android regression on isolated API 27, JVM policy tests if introduced, contract suite if parity registry changes, debug APK builds, install/launch, hash, and `git diff --check`.

## Verification limits

Synthetic API/UI verification does not prove live OIDC access, real T-Bank PDF compatibility on Android, parser service availability, or production tenant isolation. Record these separately in parity evidence.
