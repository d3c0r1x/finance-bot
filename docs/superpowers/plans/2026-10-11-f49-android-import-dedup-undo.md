# F49 Android import outcomes and safe batch undo plan

Goal: finish the Android F49 parity slice using Core-owned duplicate outcomes and atomic batch undo.

1. **Contract audit.** Follow `.agent/specs/F49-android-import-dedup-undo.md`. Use Core/PostgreSQL and Web as behavioral authorities. Never calculate fingerprints or void transactions on device.
2. **Acceptance and RED.** Add API/model tests for typed undo result and request contract, Compose tests for duplicate/created/reverted states and permissions, and a tenant-scoped import checkpoint-store test. Run tests before production code and record behavior-specific RED.
3. **Outcomes.** Render Core's committed duplicate/created/reverted outcome and counts. Keep duplicate IDs private; never treat a pre-commit hint as final.
4. **Undo API and UI.** Add explicit owner/writer undo confirmation. Use current quoted revision and deterministic per-import key; suppress 401 retry. Parse only valid structured 409 business results; keep committed state and show safe conflict descriptions. On success/412/ambiguous outcome, GET Core preview and apply only guarded Core state.
5. **Recovery.** Persist only latest import UUID per tenant. Reload it when import screen opens after process recreation, preserving committed/reverted state. Do not store PDF bytes or source URI. Deterministic undo key lets same batch retry after lost response.
6. **Regression and review.** Run focused F49, prior F47/F48, relevant Android API 27 regression, contracts, debug/AndroidTest builds, install/launch, APK hash, `git diff --check`, and independent read-only review.
7. **Parity/delivery.** Mark Android F49 PARTIAL with local evidence and live Core/OIDC/tenant-isolation gate, update `.agent/PROGRESS.md`, commit/push scoped files only after GREEN.

DoD: Android shows Core-owned import outcomes and safely undoes only a committed Core batch; retries and restarts preserve the same import/key; conflicts never become false success; no Android dedupe algorithm or per-row void loop exists.

## Execution result

Locally GREEN; parity remains PARTIAL pending live Android/Core OIDC, process-kill recovery, and tenant-isolation E2E. Core owns dedupe and atomic undo; Android stores only a tenant-scoped import UUID because Core exposes preview by import UUID but no import-list recovery route.

- RED/GREEN: API/UI instrumentation first exposed invalid fractional integer coercion, offscreen Compose assertions, and session/tenant navigation races. Tests and strict parsing/refresh guards were fixed. A new session-generation race test first failed compilation because the protected-request policy was absent; it now proves stale generations do not enter the request block and account replacement serializes against an active undo call.
- Final local checks: Android import API/UI regression 45/45 on isolated API 27; JVM checkpoint/key/response/session-request policy 15/15; contracts 63/63; debug and AndroidTest APK builds pass; fresh debug APK installed and `MainActivity` resumed on isolated API 27. APK SHA-256 `F7B58D2930D36254F783641AD00354944383A6F30A53E09D669BDD26128D6E9E`; `git diff --check` passes.
- Session replacement is serialized only across the explicit undo call (20-second call timeout); AppAuth token persistence runs on a background executor so that this guard does not block the UI thread.
- Remaining evidence gate: no live authenticated Android/Core OIDC, real process-kill recovery, or external tenant-isolation E2E was run.
