# ADR-016: Durable daily and weekly digest delivery

**Status:** Accepted by delegated design decision
**Date:** 2026-10-04

## Context

F32 replaces the legacy in-memory APScheduler path with member-local daily and
weekly digests. F25 requires the digest to use the same rolling-food values as
the Core budget, dashboard and report DTOs. Delivery must survive restarts,
retry transient failures and avoid creating duplicate logical occurrences.

## Decision

- Store notification preferences, durable digest intents and delivery attempts
  in PostgreSQL owned by Java Core. Use a unique occurrence key per member,
  digest kind and scheduled local date. A retry reuses the same intent.
- Use a Python Telegram worker to claim jobs through a service-authenticated
  Core API, send them and acknowledge the result. Core leases claims so workers
  do not process the same pending intent concurrently.
- Use the member profile timezone for local schedule and quiet-hour rules.
  Preserve the v1 defaults for linked Telegram members: daily at 21:00 and
  weekly Sunday at 19:00, both enabled. Quiet hours are unset until configured;
  the default language is RU, and the user can select RU or EN.
- Provision missing default preferences when a Telegram-linked active member
  is discovered, so migrated accounts and members created before F32 also get
  the legacy schedule without a manual settings visit.
- Build each digest from the Core report DTO. The Python renderer displays
  `rolling7FoodStatus` values directly and never recalculates financial totals.
  Skip and record an occurrence when its report has no transactions; do not
  send a synthetic zero-value digest.
- Apply bounded exponential retry for transient delivery failures. Mark
  permanent failures and exhausted retries terminal, retaining attempt history.
- If the worker is offline across several scheduled windows, materialize the
  latest due daily and weekly occurrence and advance the schedule to the next
  future local run. This coalesces stale windows instead of sending a backlog
  of old summaries after recovery.
- Treat logical occurrence creation as idempotent. Telegram does not offer a
  send idempotency key, so delivery is at-least-once across an ambiguous
  send/ack failure; do not claim exactly-once external delivery.
- Keep this notification flow on PostgreSQL. Kafka remains available for the
  analytics and event-consumer flows that need it; adding a second broker to
  this low-volume scheduled path adds no required guarantee.

## Consequences

The database is the source of truth for schedules, leases, retry state and
delivery history. Core remains the source of financial facts. Python owns
Telegram formatting and transport only. Tests must cover timezone/DST
boundaries, disabled schedules, quiet hours, unique occurrences, concurrent
claims, retry/ack transitions, no-data suppression and RU/EN rendering.

The worker may resend a Telegram message if Telegram accepted it but the worker
lost the acknowledgement. The durable intent remains unique, and the ambiguity
is visible in delivery-attempt history.
