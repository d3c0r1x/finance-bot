# Personal receipt price projection v1

This specification implements the F33 price-comparison rules from `PLAN.md` and
the legacy `services/purchase_history.py` reference. It defines the deterministic
projection policy; event ingestion, ClickHouse storage, Core query APIs and client
charts remain separate delivery work.

## Confirmed receipt event

Core publishes `receipt.confirmed` using the versioned contract in
`contracts/events/finance.receipt.v1.schema.json`. The event snapshots every
receipt item in original line order, the owning member, receipt date, currency,
merchant and posted transaction ID. Core inserts the event into its durable
outbox in the same database transaction as receipt confirmation and transaction
creation. Retrying confirmation with its idempotency key returns the original
result and does not publish another event. Consumers deduplicate by event ID and
receipt aggregate version.

The projection derives unit price from the paid line sum and quantity. It must
ignore missing, malformed or non-positive quantities and line sums while keeping
the event available for other analytics.

## Observation and ownership

An observation is one receipt item with a tenant, owning member, confirmed receipt,
product name, purchase time, positive quantity and paid line sum. The projection
caller supplies only confirmed receipt items and scopes data by tenant. Comparisons
also require the same owner; another member's purchase in the same tenant is not a
personal price baseline. The current receipt is always excluded from its own
baseline, as are observations at or after its purchase time.

## Unit price and baseline

Unit price is `paid line_sum / quantity`, rounded to six decimal places using
round-half-even. A missing, non-positive or malformed amount or quantity produces
no usable observation. Prices use exact rational arithmetic; binary floating point
does not participate in the calculation.

The baseline is the median of matching earlier purchases. With an even observation
count, v1 takes the arithmetic mean of the two middle values, matching the legacy
`statistics.median` implementation. A new item is classified as `up` or `down` only
when both conditions hold: absolute change is at least RUB 10 and absolute relative
change is at least 12% of the baseline. No history means no comparison value; it is
never represented as a zero-price baseline.

## Product identity

Identity normalizes case and `ё/е`, drops duplicate tokens and the existing legacy
stop words, then applies the legacy token-overlap and sequence-ratio thresholds.
Recognized package quantities are normalized across equivalent units (for example,
1 L and 1000 ml). If both names carry recognized package quantities and those
quantities differ, the names never match automatically. Brand tokens remain part of
the comparison unless the legacy stop-word list explicitly excludes them. This v1
policy does not create aliases or merge product records; explicit user aliasing is
a later catalog feature.

The projection is versioned as `price-projection.v1`. An identity-policy change
must use a new version and cannot silently remap existing user decisions.

## Member product catalog (F34)

The catalog query is scoped by both tenant and the member ID resolved by Core
from the active Keycloak or Telegram actor context. It reads confirmed receipt
price points only; drafts and other members' purchases are excluded. An empty
search returns up to ten products after at least three purchases, ordered by
total paid spend. /price <words> and the Web search return up to five matching
products after one purchase, with exact token matches ahead of fuzzy overlap.
Brand and known package-size differences remain separate products.

Cards report the median unit price, the latest paid unit price and purchase time,
the lowest observed unit price and its merchant, and total line spend. The
merchant is the store on the cheapest confirmed observation; it is not a live
store offer. A single purchase has no baseline and no chart. Two or more actual
purchase points enable a bounded history chart; baseline values are computed from
earlier purchases only. Web and Telegram PNG views render only these Core/Go
results, with no fabricated prices or series.

## Current implementation and remaining work

`services/analytics-go/prices` validates confirmed-receipt events, computes usable
unit-price points, provides a retryable consumer core, and a credentialed ClickHouse HTTP adapter. The table
DDL lives in `services/analytics-go/storage/clickhouse/001_receipt_price_items.sql`.
Rows use `ReplacingMergeTree(aggregate_version)` and the adapter queries with
`FINAL`, so retry duplicates do not inflate price history before background merges.
This store records only usable paid lines; malformed or missing line prices stay in
the durable event for other consumers. See ClickHouse's guidance on
[query-time deduplication](https://clickhouse.com/resources/engineering/clickhouse-optimize-table-final).

The `cmd/receipt-price-projector` runner consumes `finance.receipts.v1` with a stable
consumer group and starts at the first retained event when no offset exists. It reads
committed Kafka records, writes the complete price projection, then synchronously
commits the offset. Transient ClickHouse errors retry five times with exponential
backoff and jitter; exhausted writes leave the offset uncommitted and stop the process
for supervisor restart. Invalid envelopes go to `finance.receipts.v1.dlq` with source
topic/partition/offset, a fixed error code, a validated event ID when available, and
no receipt or member payload. A failed DLQ publish leaves the source offset uncommitted.
These are at-least-once effects; a crash after the ClickHouse write may replay the
same aggregate version. The Kafka client uses explicit `FetchMessage` and
`CommitMessages` so commit follows projection. See the
[kafka-go explicit commit guide](https://github.com/segmentio/kafka-go#explicit-commits)
and [Apache Kafka delivery semantics](https://kafka.apache.org/41/design/design/).

The internal comparison endpoint is defined in
`contracts/openapi/finance-intelligence-v1.yaml`; Core calls it only after resolving
the active member and confirming that the requested item belongs to that member's
posted receipt. Core and BFF public routes are in `contracts/openapi/finance-api-v1.yaml`.

F33 remains incomplete until Kafka/ClickHouse integration and replay checks pass,
and the approved F31 price/waste report series can consume authoritative F33/F40
data. Web item history and the F34 Web/Telegram purchase catalog consume the
authoritative feed. The Windows environment lacks Docker and a local ClickHouse
runtime, so live projection-store verification remains open. `KAFKA_BROKERS`,
`KAFKA_TLS`, optional SASL variables, and ClickHouse credentials are required to
run the projector; see `.env.example`.
