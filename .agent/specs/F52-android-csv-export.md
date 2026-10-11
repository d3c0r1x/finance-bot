# F52 Android CSV export

## Authority and scope

- Parent behavior contract: `docs/superpowers/specs/2026-10-07-f52-csv-export-design.md` and `PLAN.md` F52.
- Core remains the source of truth for membership/role authorization, transaction filtering, snapshot creation, CSV serialization, export lifecycle, and signed download URLs. Android requests and presents Core results; it never builds or filters the CSV locally.
- This is the Android counterpart to the already implemented Core/Go/Web F52 flow. It adds an authenticated Android screen for creating an export, observing its durable job, and explicitly opening/downloading a ready file.
- This extension does not add Telegram export, transaction writes, upload/storage credentials, or a new backend route.

## Core contract

- Create with authenticated `POST /api/v1/tenants/{tenantId}/exports` and JSON `{ "formatVersion": "csv-v1", "fromDate": "yyyy-MM-dd", "toDate": "yyyy-MM-dd", "memberId"?: "<active-member-uuid>" | "all" }`.
- Omit `memberId` for the current user's own transactions. `owner` and `admin` may choose their own scope, one active member, or all active members. `member` and `viewer` can request only their own scope. UI gating is convenience only; Core enforces authorization.
- Core responds `202 Accepted` with an `ExportJob`. Read status using authenticated `GET /api/v1/tenants/{tenantId}/exports/{exportId}`.
- `ExportJob` fields: `id`, `status` (`queued`, `processing`, `ready`, `failed`, `expired`), `formatVersion` (`csv-v1`), `fromDate`, `toDate`, nullable `memberId`, `allMembers`, `rowCount`, `snapshotAt`, `createdAt`, `expiresAt`, and nullable `downloadUrl`.
- The request timezone is the active tenant membership's configured timezone. The date interval is inclusive and must contain 1–366 local dates. Default to a finite recent range; the user can edit both dates.
- CSV content is exactly the Core-owned `csv-v1` contract: UTF-8 BOM, semicolon delimiter, legacy Russian column order/labels, timezone-local dates, exact decimal amount, standard quoting, and formula-safe text. Android does not promise locale-translated CSV headers.

## Android behavior

1. Add a distinct CSV export destination reachable to authenticated tenant members. Show an inclusive date range, request scope, create action, current job state, row count/empty result, and explicit ready-file action. RU/EN labels follow the existing Android presentation conventions.
2. Initialize the date range to the latest 30 local calendar dates in the active tenant timezone. Validate parseable dates, `fromDate <= toDate`, and 1–366 inclusive days before enabling create. Core remains the final validator.
3. All roles default to own scope. Only `owner`/`admin` see scope selection; they can choose own, one active member, or all members. Retrieve member choices from the authenticated tenant member endpoint. Send no member scope for own, the selected UUID for one member, or literal `all` for all members.
4. Create exactly one job per explicit tap. Retain the returned export ID only in the current screen/session state. Do not retry a timed-out create automatically because the create endpoint has no client idempotency contract; offer an explicit retry action with clear duplicate-job semantics.
5. While `queued` or `processing`, poll the status endpoint slowly and within a bound: wait at least 5 seconds between automatic GETs, perform no more than 12 automatic GETs for one polling window, and stop polling when the screen is left, the app is backgrounded, the tenant/session changes, or the job becomes terminal. After the bound, keep the last status and offer a manual one-shot status check; a still-active job may start a new bounded window. Never poll a terminal job or poll merely to refresh a ready download URL.
6. Core writes `export.status_viewed` for each status GET and additionally `export.download_url_issued` when a ready response issues a URL. Avoid tight, background, terminal, and repeated automatic polling. A ready response's signed URL is short-lived (at most five minutes) and is a bearer capability.
7. Display a download/open action only for `ready` with a valid HTTPS `downloadUrl` containing no username/password. Only after the user's explicit tap, refresh job status once to obtain a fresh signed URL, validate it again, then hand that URL to the system browser/downloader using an external HTTPS view intent. Do not place the Core access token in this request or intent. Do not fetch an arbitrary URL through the authenticated Core API client.
8. Keep a signed URL in transient memory only as needed between response and explicit action. Never persist it in preferences, saved state, files, logs, analytics, diagnostics, or clipboard; do not store it as part of the job model across process restoration. Do not log signed query parameters or send them to any application service. If status refresh no longer returns a usable URL, keep the job state and explain that the link is unavailable or expired; do not open the stale URL.
9. Show localized loading, queued, processing, ready, failed, expired, empty-export, invalid-range, no-member-choice, authorization, and recoverable network states. Never show raw response bodies, machine stack traces, access tokens, tenant identifiers, or signed URLs in UI.
10. Bind each request to the captured auth-session generation, tenant ID, active destination, and request generation. A late response from logout/session replacement, tenant switch, leaving the screen, or a newer job cannot overwrite current UI state or launch a URL.

## Explicit non-goals

- No on-device CSV generation, rewriting, parsing, or alternate format.
- No changes to transaction data, export snapshot semantics, worker/storage configuration, or Core permissions.
- No automatic download/open, URL prefetch loop, automatic create retry, permanent link, or signed-URL persistence.
- No claim that local Android tests prove live OIDC, Core tenant isolation, S3 availability, or downloaded file integrity.

## Acceptance

1. **Exact DTO contract:** Android strictly parses every required job field, nullable URL/member fields, valid UUIDs/dates/timestamps, supported status and `csv-v1`; malformed fields or unknown status do not fabricate ready state. Test each supported status and malformed/boundary data.
2. **Request contract:** API tests prove authenticated POST path/body and status GET path. Own scope omits `memberId`; selected member and all scope serialize exactly. A `viewer`/`member` UI cannot widen scope, while Core 403 is mapped to a safe localized error.
3. **Dates:** UI/model tests prove a tenant-timezone 30-local-day default, inclusive range validation at 1 and 366 days, and rejection of reversed, unparsable, zero-day, and 367-day ranges before POST.
4. **Role/member scope:** Compose tests prove own scope is default for every role; only owner/admin can choose self, active member, or all. Member list failure does not silently turn a requested specific/all scope into own or all scope.
5. **Create lifecycle:** One explicit tap sends one request and renders the returned job. Loading prevents duplicate taps. Timeout/ambiguous failure never silently replays POST; retry requires an explicit user action. A late old request cannot replace a newer tenant/session/job.
6. **Polling bounds:** A fake clock/scheduler test proves no request before 5 seconds, at most 12 automatic GETs per window, no polling after terminal state, and cancellation on screen exit, app background, tenant switch, or auth-session replacement. After the bound, UI provides one manual check and preserves active status without declaring failure.
7. **Audit-aware ready state:** Once a job is terminal/ready, automatic polling stops. The UI does not repeatedly GET ready status or refresh the signed URL in the background. Only an explicit file-action tap causes one status refresh.
8. **Download safety:** UI shows no action for non-ready jobs, null URL, non-HTTPS URL, or URL with userinfo. On explicit tap it refreshes status, validates the current URL and starts an HTTPS external view/download intent. Tests prove the Core Bearer token is never sent to the signed-URL host, and no intent occurs for stale/invalid/missing URLs or a replaced session/tenant.
9. **Secret handling:** Tests/procedural inspection prove URL query values are not persisted in app preferences/saved state, not written to logs/analytics, and not exposed in user-facing error text. The refreshed URL is transient and discarded on screen exit/session change.
10. **Presentation:** RU/EN tests cover date errors, create/poll failures, queued/processing/ready/failed/expired, zero rows, row count, missing link, and retry/manual-check actions. CSV bytes and financial totals remain Core-owned and unchanged.
11. **Regression:** Existing Android API, navigation, authentication/session, tenant switching, report, and F51 feature tests remain green; API-27 instrumentation suite builds/runs on the isolated test emulator. Core/PostgreSQL/Go/Web behavior remains covered by existing F52 gates; do not duplicate Core's CSV formatter tests in Android.

## Verification limits

Mocked Android tests prove serialization, state handling, validation, polling policy, and intent safety only. They do not prove the Core authorization checks, actual object storage, browser transfer, or downloaded bytes. Keep authenticated Android-to-Core/OIDC, real tenant roles, signed-link delivery, and S3 round-trip as separate runtime evidence. The Core status audit behavior and five-minute signed URL lifetime are defined in `services/core/src/main/java/com/decorix/finance/core/api/ExportService.java`.
