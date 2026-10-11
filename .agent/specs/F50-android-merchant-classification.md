# F50 Android merchant classification

## Goal

Expose the existing Core merchant-classification and personal-mapping flow in
the Android bank-import preview. Core remains authoritative for AI suggestions,
confidence, category mappings, and clarification candidates.

## Behavior

- Writers can explicitly request classification for an editable import with an
  included expense row that has a merchant. The app sends the import revision
  to Core and displays the returned hypotheses and clarification flags.
- Android displays Core's clarification merchants and the policy hint (up to six,
  at least RUB 300); it does not recalculate thresholds or infer candidates.
- A writer can choose one of the ten supported category codes for an included
  expense with a merchant. The quoted revision is sent to Core; the returned
  preview is the only source of the displayed saved category and rule source.
- Viewers, excluded rows, non-expenses, missing merchants, and closed imports
  cannot classify or save mappings. Stale responses are not applied. On a
  mutation failure, Android never replays the write and refreshes Core state
  when the same session/tenant remains active.
- RU and EN labels are provided. No amount, description, tenant or member data
  is sent to the classifier from Android. Historical transaction changes remain
  out of scope (F51).

## Verification

- API tests: exact classify/category routes, quoted `If-Match`, request body,
  Core response mapping, permission/revision/provider errors, no hidden retry.
- Model tests: category/provenance/confidence validation and clarification flag.
  Confidence follows the shared preview schema exactly (`0.xxx` or `1.000`).
- Compose tests: explicit classify action, writer/state gating, Core hypothesis
  and clarification rendering, explicit category choice and response authority.
- Run on isolated Android API 27, plus F47-F49 import regression and APK build.

## Limits

This proves local Android parity against Core-shaped responses. Live Android
OIDC/Core behavior, production AI availability, durable PostgreSQL mapping on the
deployed service, and real multi-tenant isolation require external E2E evidence.
