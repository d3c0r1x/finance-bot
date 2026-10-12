# F08 Android durable repeat/void retries

**Authority:** approved V1 `PLAN.md` F08; user approved encrypted local recovery on 2026-10-12. This closes the recorded Android-only process-death retry gap; server/API semantics stay unchanged.

## User outcome

After Android process death, an unresolved repeat or void keeps the original request and idempotency key. The app never resends it on launch. A user action retries only under the same authenticated Core `userId` and tenant. The checkpoint clears only after a valid Core acknowledgement and successful authoritative workspace refresh.

## Design

- Parse Core's stable caller `userId` from `/api/v1/me/tenants`; do not use mutable display name, transaction owner, access token, or process-local `sessionGeneration` as durable identity.
- Replace process-only repeat/void maps with one mutation-attempt repository and an encrypted Android Keystore AES-GCM checkpoint store. Store immutable source transaction, exact repeat timestamp, action, tenant, caller ID, source version for void, and idempotency key. Commit the checkpoint before POST.
- Use a separate preferences file and Keystore alias from `TokenVault`. Preserve an existing encrypted blob if its key or ciphertext is missing/corrupt; fail closed and never mint a replacement idempotency key.
- Model persistence as `MutationCheckpoint(action, actorUserId, tenantId, original, occurredAt?, idempotencyKey, sessionGeneration)` behind `MutationCheckpointStorage.read/write`. Do not serialize `sessionGeneration`; recovered `begin*` rebinds the checkpoint to the active generation after identity checks. `TransactionMutationAttempts` owns validation and begin/complete semantics; production storage is `AndroidEncryptedMutationCheckpointStorage`.
- Capture tenant, caller ID and in-process session generation synchronously at tap. Recovered records receive the current in-process generation only after caller/tenant match. Keep existing API response validation and stale-session guards.
- Show pending actions in transaction history as explicit retries, including a pending void after the source row becomes voided. Do not offer a new mutation for the same source while its prior request is unresolved. No startup/recomposition/network auto-POST.
- On an in-process POST/acknowledgement/refresh failure, publish the already-persisted checkpoint into UI state immediately; do not require app restart or an unrelated refresh before the user can retry. Keep encrypted-store failures fail-closed.
- Keep retry reachable when the source transaction changed version or is outside the current filters/page by rendering a saved-snapshot recovery list for checkpoints not represented in the visible page.
- Keep the checkpoint if POST outcome is ambiguous, Core acknowledgement is invalid, workspace refresh fails, or the active user/tenant changes. Remove only the matching record after authoritative refresh for the same identity.

## TDD and verification

Add failing tests before production edits: repository recovery through a fresh instance (identical key, body/timestamp and void version), caller/tenant/action isolation, completion removes only matching record, retry checkpoint publication after a failed request, recovery list for filtered/page-hidden items, corrupt checkpoint fails closed, encrypted storage survives a fresh store and does not expose transaction text, Core tenant caller-ID parsing, MockWebServer lost-response retry, and explicit-only Compose retry actions for RU/EN including a voided source row and suppression of opposite mutations. Run targeted RED first.

After implementation, run focused JVM and Android instrumentation suites on isolated API 27, F08 history/UI and repeat/void API regressions, full Android JVM suite, parity contracts, debug/AndroidTest APK assembly, installed launch, and `git diff --check`. Review the final diff, update F08 parity evidence and `.agent/PROGRESS.md`, commit only this bounded slice, push, and verify remote SHA.

## Limits

An API-27 emulator force-stop test proves encrypted snapshot persistence across target-process death. It does not exercise authenticated MainActivity recovery or live OIDC/Core. Keep physical-device process death, authenticated Core idempotency, and production tenant-isolation E2E open unless separately exercised.

## OS process-death verification follow-up

- Use a dedicated test-only preferences file and Android Keystore alias so the lifecycle check cannot overwrite a user's pending mutation.
- A host PowerShell runner invokes the seed instrumentation method, force-stops only the isolated `com.decorix.finance.debug` package, confirms its PID exits, relaunches `MainActivity`, then invokes a fresh instrumentation process to compare the exact encrypted repeat and void snapshots.
- The instrumentation suite may run its ordered seed/recovery/cleanup methods together without an OS kill; only `scripts/verify-f08-process-death.ps1` exercises the force-stop boundary. Its cleanup method is always attempted even when a phase fails.
- This verifies Android Keystore and encrypted SharedPreferences survival across target-process death. It does not prove authenticated MainActivity restoration, Core/OIDC connectivity, production tenant isolation, or real server idempotency; those remain separate acceptance gates.
