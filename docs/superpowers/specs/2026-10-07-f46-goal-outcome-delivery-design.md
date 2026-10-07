# F46 Goal Outcome Delivery — Design

**Date:** 2026-10-07
**Global source:** `PLAN.md` F46.
**Related behavior:** `.agent/specs/F45-goal-lifecycle.md`; durable delivery in `NotificationDeliveryService` and the Telegram digest worker.

## Goal

Tell the member once when a newly completed goal's outcome is included in a weekly Telegram digest. Keep the outcome in Core until Telegram confirms delivery. Reuse the existing durable notification lease and retry flow; do not add an immediate message channel.

## Flow

1. F45 closes an expired active goal and inserts one `goal_outcomes(origin='completed')` row atomically with the status, audit, and outbox transition.
2. When the existing weekly notification worker claims a weekly intent, Core associates at most one oldest eligible completed outcome with it. An outcome can be associated with only one intent at a time. Daily intents never claim outcomes.
3. Core returns the outcome snapshot in the weekly claim. The Telegram worker appends its localized result to the weekly digest. If the regular report has no content, the outcome text still produces a message.
4. After Telegram reports success, Core sets `announced_at` and clears the association in the same acknowledgement transaction. Imported `origin='legacy'` rows are not sent again.

## Retry and delivery boundary

- Retryable and expired leases keep the outcome associated with the same weekly intent, so retries contain the same outcome.
- A permanent terminal failure releases the association. The outcome stays unannounced and may be attached to a later weekly intent.
- If Telegram accepted the message but the acknowledgement is lost or times out, Core cannot know that delivery happened. Lease recovery may send a duplicate. The system provides durable retry and eventual acknowledgement, not exactly-once external delivery.
- If weekly notifications are disabled, Core creates no future weekly intent; an intent already queued follows existing delivery rules. Without a Telegram identity, claims wait until the member links Telegram. Outcomes remain visible in Web and unannounced until successful delivery.

## Storage and bounds

- Add a nullable association from `goal_outcomes` to `notification_intents`, unique per intent and indexed for unannounced eligible outcomes. Keep `announced_at` as the success marker.
- Only one outcome is attached to each weekly digest, oldest first. This bounds message length and prevents multiple overdue outcomes from being lost when a newer goal completes.
- The delivery DTO contains the immutable outcome snapshot needed for rendering; the Telegram worker does not query the database or infer results from a report.

## Acceptance

- PostgreSQL tests prove only weekly intents claim outcomes; concurrent claims cannot attach one outcome twice; retries retain the same outcome; successful acknowledgement marks exactly once; terminal failure releases it; legacy outcomes are excluded; and no outcome is dropped when a report has no financial rows.
- Python tests prove RU/EN rendering, outcome-only messages, unchanged daily digest behavior, and safe retry behavior when send or acknowledgement fails.
- Contracts validate the bounded outcome claim and nullable payload for ordinary digests.
- Regression: Core/PostgreSQL, Telegram gateway, contracts, Go, Web, and `git diff --check`.

## Scope limits

No new notification preference, immediate notification, delivery provider, or exactly-once guarantee. This does not implement migration ingestion; legacy goal outcomes are imported by J and must not trigger new notifications.
