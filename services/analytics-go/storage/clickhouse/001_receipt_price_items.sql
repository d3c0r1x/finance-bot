CREATE DATABASE IF NOT EXISTS finance_analytics;

CREATE TABLE IF NOT EXISTS finance_analytics.receipt_price_items
(
    tenant_id UUID,
    owner_user_id UUID,
    receipt_id UUID,
    transaction_id UUID,
    item_id UUID,
    event_id UUID,
    aggregate_version UInt64,
    receipt_date Date,
    purchased_at DateTime64(6, 'UTC'),
    recorded_at DateTime64(6, 'UTC'),
    merchant Nullable(String),
    name String,
    quantity Decimal(18, 6),
    line_sum Decimal(20, 2),
    unit_price Decimal(30, 6)
)
ENGINE = ReplacingMergeTree(aggregate_version)
PARTITION BY toYYYYMM(receipt_date)
ORDER BY (tenant_id, owner_user_id, receipt_id, item_id)
SETTINGS index_granularity = 8192;
