# F45 Goal Progress and Lifecycle

**Source:** `PLAN.md` F45 and preserved behavior in `services/goals.py`. F46 notification delivery remains separate. Execute as independently verifiable slices with tests first, observed RED, implementation, GREEN, regression, commit, and progress update.

## Approved behavior

- Accepted terms are immutable for exactly 30 days. Progress counts confirmed, posted, member-owned purchase facts whose timestamps fall from `acceptedAt` through `min(endsAt, asOf)`; future-dated facts never count.
- Product goals match their accepted product key. Group goals match a fixed product-key list captured by the preview and persisted at acceptance; only eligible (not allowed and not unconfirmed-model) products enter it. Later category-rule changes cannot rewrite an accepted goal.
- Count goals compare purchase count to the fixed target. Sum goals compare known line sums to the fixed monthly limit. If any relevant line sum is unknown, progress money is unavailable (`null`), never a fabricated zero; count progress remains available.
- A cancelled goal never becomes a completed outcome. An expired active goal gets one durable completion outcome and one history row; repeated requests/jobs cannot duplicate or rewrite it.
- History is retained independently from the active goal, newest first, preserving all imported legacy rows. New history is retained to the existing legacy ceiling of 24; history presentation may show five while reporting when older entries are outside retention.
- After completion, the member can see current candidates and choose a new goal. Purchase progress is visible after confirmed receipt refresh; no financial transaction values are changed by goal lifecycle operations.
- Purchase note is a read-only progress update derived from the same authoritative progress DTO; it never marks a purchase or changes receipt data.
- Core owns authorization, tenant/member scoping, goal state transitions, audit/outbox, and durable outcomes. Go owns deterministic progress calculation. Web displays active progress, purchase note, completion history, and candidates. F46 owns one-time external outcome delivery.

## F45.1 calculation contract

- Go request has fixed accepted terms, `asOf`, goal identity/scope, captured group-member keys, and bounded purchase facts; response echoes algorithm version and input watermark and returns bought count, nullable spent, over/met/finished, days left, and exact window.
- Reject malformed unit/scope/target/limit, non-canonical amounts, reversed dates, zero watermark, malformed facts, and more than 50,000 facts. Use exact decimal arithmetic; do not round input money through binary floats.
- Tests cover window boundaries, future facts, product/group filtering, count and sum success/overrun, unknown amounts, completion boundary and malformed/oversized inputs.

## F45.2 durable Core lifecycle

- Progress read is member scoped and sends only that member's confirmed, posted expense receipt facts to Go. Product goals match the product key; group goals match the accepted product-key snapshot. This fixes the current F44 gap where a category preview does not yet expose/persist its members.
- Completion is a conditional transition from `active` after the accepted end instant. Completion row and status/audit/outbox are atomic; uniqueness plus conditional update makes repeats idempotent. Cancellation preserves no completed outcome.
- Add paginated or bounded goal history API, active progress in overview, and keep next candidates available after cancellation/completion. Legacy history import accepts all supplied rows before applying the 24-entry new-history retention rule.
- Isolated PostgreSQL acceptance covers owner/viewer/tenant boundaries, member facts, product/group attribution, before/at/after-end behavior, idempotency, cancel exclusion, retained history, and unchanged receipt/transaction totals.

## F45.3 Web progress and history

- Add component tests first for count/sum progress, unknown sums, note after new purchase, completed history, cancelled exclusion, next candidate availability, RU/EN, viewer read-only, loading/error states.
- Run focused tests, all Web tests, production build, Core/PostgreSQL tests, Go tests/vet, contract validation, and diff checks before commit.
