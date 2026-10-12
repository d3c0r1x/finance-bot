# F08 Android void retry idempotency

**Authority:** approved V1 `PLAN.md` F08 and `docs/specs/CONTINUATION.md` §A02.

**Observed gap:** `FinanceApi.voidTransaction` creates a new `Idempotency-Key` for each call. `MainActivity.voidTransaction` does not retain the request across an ambiguous API or workspace-refresh failure. Core stores the void result idempotently by actor/route/key and enforces the original version with `If-Match`; a retry using a new key can return a stale-version conflict after the first void committed.

**Goal:** let Android retry one selected void operation with the exact same request until its result and workspace refresh are confirmed.

**Acceptance:**

- Store the immutable request intent by tenant, source transaction ID/version, and captured authentication-session generation. It contains one idempotency key and the exact `{}` body / `If-Match` version.
- Capture tenant and authentication-session generation synchronously at the UI tap, before `runApi` queues work; recheck both before creating/sending the attempt so an old-screen action cannot adopt a replacement account.
- Re-entering the same intent in the same session returns the same key. Another tenant, transaction, source version, or session gets an isolated intent.
- Reject a request if its captured authentication-session generation is stale, both before request token capture and immediately before the POST.
- Accept only the same transaction ID and tenant, status `voided`, exactly `source.version + 1`, and unchanged type, amount, currency, category/subcategory, description, source, timestamp, account, debt, and owner IDs. A member display-name change is presentation-only and must not block an otherwise exact acknowledgement.
- Clear the pending intent only after response parsing/validation and authoritative workspace refresh both succeed. Transport, validation, or refresh failure retains the intent for an explicit retry.
- No Core endpoint, authorization rule, financial rule, optimistic UI mutation, or automatic network retry changes.

**Test-first sequence:** add meaningful JVM attempt-store tests and API-27 MockWebServer tests before production edits. Observe RED for the absent attempt store and explicit key/session API, then implement the narrow Android path. Cover lost response after server receives the request, identical idempotency key/body/`If-Match` on retry, stale-session rejection before POST, invalid acknowledgement retention, and fresh key only after confirmed completion.

**Verification:** focused attempt-store tests; focused API-27 void instrumentation; existing F08 history/void screen acceptance; full Android JVM suite; feature-parity contract tests; `git diff --check`. Use only synthetic test values and the isolated Android 8.1/API 27 emulator.

**Final GREEN:** focused attempt-store tests 4/4; void API-27 MockWebServer tests 5/5; `FinanceScreensTest` 89/89 on API 27; full Android JVM suite 80/80; updated `FinanceReceiptApiTest` 49/49; parity/contracts 63/63; `git diff --check` clean. The tap-boundary session policy and mutable member display-name regressions were each observed RED before their fixes.

**Boundary:** retry state is process-local. Android F08/F59 remains partial until authenticated live Android-to-Core/OIDC E2E passes. An app-process death after an ambiguous POST can lose the key; do not claim crash-safe replay.
