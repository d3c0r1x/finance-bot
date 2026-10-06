# Recurring transaction projection v1

Core resolves the authenticated active tenant member and their IANA timezone. The Go analytics service reads at most 5,000 current transaction states for that member. It chooses the highest aggregate version for each transaction before filtering status or event time, so a later edit or void replaces older data. Only posted expense and income transactions contribute.

The detector groups transactions by type, currency, normalized description, and category. A series needs at least three occurrences. Its amount range may not exceed 25% of the average. Intervals use local calendar dates and the upper median. The median must be 6–8 days for a weekly series or 25–35 days for a monthly series, and at least 60% of intervals must be within 25% of that median. Repeated transactions on one local day do not count as intervals.

The next date is one median interval after the latest occurrence. Expense series due from today through three days ahead appear in `dueSoon`; older next dates appear only in `overdue`. `nextIncome` is the nearest non-overdue income. No qualifying history returns empty series and null totals.

Monthly expense estimates sum the average amount for monthly series and `average × 30 ÷ median interval` for other series, rounded to two decimals. Estimates remain grouped by currency. The top-level estimate is set only when one currency exists. Values are estimates from transaction history, not a bill schedule or payment confirmation.

The `finance.transactions.v1` worker persists complete states to `transaction_states` before committing Kafka offsets. Retriable storage failures leave the offset uncommitted. Invalid events go to the safe metadata-only dead-letter topic before commit. Core calls the internal member-scoped API; Web BFF, Android, and Telegram use the same Core response.

The machine-readable request and projection examples live in `contracts/analytics/recurring-v1/`. Run local checks with `go test ./...`, `go vet ./...`, Core `:services:core:check`, Python contract and Telegram/presentation tests, Web tests/build, and Android connected instrumentation. Kafka and ClickHouse replay requires `FINANCE_RECURRING_IT_BROKERS`, `FINANCE_RECURRING_IT_CLICKHOUSE_URL`, `FINANCE_RECURRING_IT_CLICKHOUSE_USER`, and `FINANCE_RECURRING_IT_CLICKHOUSE_PASSWORD`; without those endpoints the integration test is reported as skipped, not passed.
