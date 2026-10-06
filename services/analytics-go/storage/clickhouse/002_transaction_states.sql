CREATE TABLE IF NOT EXISTS transaction_states
(
    tenant_id UUID,
    owner_user_id UUID,
    transaction_id UUID,
    event_id UUID,
    aggregate_version UInt64,
    type LowCardinality(String),
    amount Decimal(20, 2),
    currency FixedString(3),
    category_code LowCardinality(String),
    description String,
    status LowCardinality(String),
    occurred_at DateTime64(6, 'UTC'),
    recorded_at DateTime64(6, 'UTC')
)
ENGINE = ReplacingMergeTree(aggregate_version)
PARTITION BY cityHash64(tenant_id, owner_user_id, transaction_id) % 32
ORDER BY (tenant_id, owner_user_id, transaction_id);
