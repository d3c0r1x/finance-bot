# F52 Android RU/EN export acceptance matrix

**Authority:** approved V1 `PLAN.md` F52 and `.agent/specs/F52-android-csv-export.md` acceptance criterion 10. This is a bounded presentation-test follow-up; Core, Web, export API, and UI behavior are out of scope unless a test exposes a mismatch.

## Goal

Prove Android export date errors, Core error mapping, all job states, row counts, empty results, missing links, retry warning, and manual refresh action in Russian and English.

## Acceptance

- For RU and EN, the screen renders invalid date guidance, generic/network and each mapped Core error safely.
- For RU and EN, each of `queued`, `processing`, `ready`, `failed`, and `expired` renders its localized status and exact row count.
- For RU and EN, ready/zero-row copy and a ready/missing-link explanation/action render correctly; signed URLs remain absent from visible text.
- For RU and EN, retry warning/action and pending-job manual refresh action render; callbacks run only after explicit taps.
- No production change unless a test demonstrates a product defect.

## Test-first and verification

Add Compose acceptance tests first and run `FinanceExportsScreensTest` on the isolated Android 8.1/API 27 emulator. A passing first run is acceptable because production RU/EN strings already exist; record that result honestly. Then run F52 Android instrumentation regression, Android JVM tests, contract tests, APK assembly, and `git diff --check` as available. Update F52 parity evidence and `.agent/PROGRESS.md`; commit and push only scoped files after review.

## Limits

This proves localized UI behavior with synthetic fixtures only. It does not prove live Core/OIDC authorization, S3, external system resolver, browser transfer, downloaded bytes, session/tenant cancellation, or wall-clock polling cadence.
