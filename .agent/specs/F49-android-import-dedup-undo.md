# F49 Android import outcomes and safe batch undo

## Authority and scope

- Parent requirement: approved `PLAN.md` F49, “Дедупликация импорта, bulk insert, undo именно партии”.
- Core owns duplicate fingerprints, occurrence handling, atomic insertion, conflicts, and batch undo. Android must not implement deduplication or void transactions one at a time.
- Add Android presentation of Core-created/duplicate/reverted outcomes and explicit undo of the currently displayed committed batch. F50/F51 remain separate.
- Persist only the latest staged import UUID per tenant so Android can reload preview after process restart. Never persist PDF bytes, URI, transaction records, or authentication tokens in this checkpoint. A later import replaces that tenant's pointer.

## Acceptance

1. Preserve Core's committed row outcome, `transactionId`, `duplicateOfTransactionId`, duplicate count, state and revision. A pending duplicate hint is not final; only committed Core response defines created versus duplicate. Show localized row outcomes without exposing raw UUIDs.
2. Store the latest import UUID keyed by tenant after staging. Reload it through authenticated Core preview after app restart/screen re-entry; tenant changes cannot show another tenant's preview. Keep the import pointer after commit and undo so terminal state is recoverable.
3. Show an explicit, localized undo action only for a committed batch and a writer. Explain that Core can undo only unchanged transactions created by this batch. Require a second explicit confirmation. Viewer and non-committed states cannot invoke undo.
4. Send one `POST /api/v1/tenants/{tenantId}/imports/{importId}/undo`, with quoted `If-Match` revision and stable deterministic `Idempotency-Key`; send no body. Disable mutations while request is active. Never auto-replay mutation after 401/session replacement. Serialize this request start with session replacement, cap the guarded call at 20 seconds, and keep token persistence off the UI thread.
5. On success, apply only Core's `reverted` result and GET preview; show reverted count from Core. Retrying after lost response reuses the same key; reload before enabling another mutation.
6. Parse Core's structured 409 conflict result. Keep batch `committed`, report conflict rows by matching IDs to Core preview rows, and never claim partial/successful undo. A 412 reloads the preview before another action. Generic 409 error must remain an error, not be mistaken for conflict result.
7. Old session, tenant, screen, or request responses cannot overwrite current state. Preserve F47/F48 behavior and F49 terminal state after navigation/process restart.

## Test-first evidence

- Add API/model tests first for undo method, headers, no body, stable retry key, 200, structured 409, malformed conflict payload, 401, 412, and session replacement. Observe RED before production changes.
- Add Compose tests first for created/duplicate/reverted rows, owner/viewer permissions, confirmation dialog, pending state, Core returned result, and 409 conflict messaging. Observe RED before production changes.
- Add checkpoint-store test first for tenant isolation, latest-import replacement, invalid-ID rejection, and process-restorable import UUID. Keep the terminal import pointer so the final state remains recoverable. Observe RED before storage code.
- Run focused API/UI tests and relevant Android 8.1/API 27 regression, JVM tests, contract suite, APK build/install/launch, SHA-256 and `git diff --check`.

## Verification limits

Local tests do not prove live authenticated Android/Core OIDC, PostgreSQL undo in production, process-kill integration on a physical device, or tenant isolation outside synthetic storage/API tests. Core/PostgreSQL acceptance remains the authority for atomicity and ownership.
