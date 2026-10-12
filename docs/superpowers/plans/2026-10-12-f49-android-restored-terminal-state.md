# F49 Android restored terminal import state

**Authority:** approved V1 `PLAN.md` F49 and `.agent/specs/F49-android-import-dedup-undo.md` acceptance criteria 2 and 7.

## Gap

Android persists only the import UUID, then reloads the authoritative Core preview when the Imports screen opens. After navigation or process recreation, transient `undoResult` is gone. A Core preview with `state = reverted` currently renders reverted rows but no batch-level completion message.

## Goal and acceptance

- A committed/reverted preview from Core renders its durable terminal state even when no transient undo response exists.
- A restored `reverted` preview shows a localized batch outcome in RU and EN.
- A reverted batch never offers the committed-only Undo action.
- No count is fabricated when a restored preview does not contain `revertedCount`; a live undo response may continue showing Core's exact count.
- Core remains the authority; no API, permission, persistence, or financial behavior changes.

## Test-first sequence

Add a Compose test that supplies a Core `reverted` preview with `undoResult = null`, switches RU/EN, and asserts the terminal message plus absence of Undo. Run on isolated API 27 and observe RED before changing the screen. Implement the smallest presentation change, then run F49 undo-screen and related bank-import API/screen regressions, Android JVM suite, contracts, APK assembly, and `git diff --check`.

## Limits

This verifies presentation of a restored Core terminal preview with synthetic data. It does not simulate an OS force-stop, live Core/OIDC, or production tenant isolation; retain those as separate evidence gates.

## Verification note

After updating F49 parity evidence, the contract suite correctly failed because its assertion still required the previous 45/45 evidence count. Update that contract to require the new 54/54 regression count and explicitly preserve the synthetic-fixture/process-death limitation; rerun the contract suite before commit.
