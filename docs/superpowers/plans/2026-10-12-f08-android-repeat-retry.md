# F08 Android repeat retry idempotency

**Authority:** approved V1 `PLAN.md` F08 and `docs/specs/CONTINUATION.md` §A02.

**Observed gap:** Android generates a fresh `Idempotency-Key` and recomputes `occurredAt` on every repeat tap. Core stores the transaction and idempotency response atomically; it replays only when both key and request body match. A lost response can therefore create a second transaction on retry.

**Goal:** keep one repeat action safe to retry after an ambiguous transport or workspace-refresh failure.

**Acceptance:**

- A pending repeat is keyed by tenant, authenticated-session generation, and original transaction ID; it keeps an immutable copy of original expense data, the chosen repeat date, and one idempotency key.
- Re-entering the same repeat action returns the exact pending snapshot. Another transaction, tenant, or authentication session gets a separate key.
- Android sends the stored key and exact copied request body on every retry.
- A repeat POST is rejected if the authentication-session generation changed before token capture or before the request is sent; it cannot run under a replacement login.
- Accept only a newly created, `posted` transaction in the same tenant with every intended field and timestamp unchanged.
- Clear the pending attempt only after a successful repeat response is parsed and validated as a new transaction for the same tenant and exact intended values, and the workspace refresh succeeds.
- API or refresh failure leaves the attempt available for retry. A new repeat after successful completion receives a new key.
- No Core endpoint, financial rule, optimistic mutation, credential handling, or automatic network retry changes.

**Test-first evidence:** initial tests failed to compile because `TransactionRepeatAttempts` was absent and `FinanceApi.repeatTransaction` had no explicit key argument. Review then added RED checks for stale authentication-session generations and `pending` responses; they failed to compile until the API accepted and enforced the expected generation. The Android API test uses MockWebServer and synthetic transaction values; it does not contact Core or use real user data.

**Verification:** focused `TransactionRepeatAttemptsTest` GREEN; focused API-27 `FinanceTransactionRepeatApiTest` 3/3 GREEN; existing API-27 `FinanceScreensTest` 89/89 GREEN; full Android JVM suite GREEN. Then run parity/contracts and `git diff --check`. Tests ran on the dedicated isolated API-27 emulator. Keep F08/F59 partial until authenticated live Android-to-Core/OIDC E2E passes.

**Boundary:** pending intent snapshot is process-local and partitioned by auth-session generation; it cannot be sent from a replacement session. App-process death recovery and durable encrypted retry storage remain open. A process death after an ambiguous POST can lose the retry key; do not claim crash-safe replay or full Android F08 parity.
