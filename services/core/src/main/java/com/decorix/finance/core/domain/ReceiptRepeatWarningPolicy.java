package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Repeat warnings use confirmed earlier receipts and the same allowed-product decision overlay. */
public final class ReceiptRepeatWarningPolicy {
    private static final Set<String> WASTE_VERDICTS = Set.of("harmful", "unnecessary");
    private static final Map<String, String> TITLES = Map.of(
            "harmful", "❌ Лучше сократить",
            "unnecessary", "🗑 Можно было не брать");

    private ReceiptRepeatWarningPolicy() {}

    public static List<Warning> apply(UUID currentReceiptId, List<CurrentItem> currentItems,
                                      List<HistoryItem> history, Set<String> allowedProductKeys) {
        Accumulator accumulator = new Accumulator(currentReceiptId, currentItems, allowedProductKeys);
        for (HistoryItem item : history == null ? List.<HistoryItem>of() : history) accumulator.add(item);
        return accumulator.finish();
    }

    public static List<Warning> apply(UUID currentReceiptId, Instant currentEffectiveAt, Instant currentCreatedAt,
                                      List<CurrentItem> currentItems, List<HistoryItem> history,
                                      Set<String> allowedProductKeys) {
        Accumulator accumulator = new Accumulator(currentReceiptId, currentEffectiveAt, currentCreatedAt,
                currentItems, allowedProductKeys);
        for (HistoryItem item : history == null ? List.<HistoryItem>of() : history) accumulator.add(item);
        return accumulator.finish();
    }

    public static final class Accumulator {
        private final UUID currentReceiptId;
        private final Instant currentEffectiveAt;
        private final Instant currentCreatedAt;
        private final List<CurrentState> currentItems;
        private final Set<String> allowedProductKeys;

        public Accumulator(UUID currentReceiptId, List<CurrentItem> currentItems, Set<String> allowedProductKeys) {
            this(currentReceiptId, null, null, currentItems, allowedProductKeys);
        }

        public Accumulator(UUID currentReceiptId, Instant currentEffectiveAt, Instant currentCreatedAt,
                           List<CurrentItem> currentItems, Set<String> allowedProductKeys) {
            if (currentReceiptId == null || currentItems == null || currentItems.size() > 200) {
                throw new IllegalArgumentException("receipt repeat warning context is invalid");
            }
            if ((currentEffectiveAt == null) != (currentCreatedAt == null)) {
                throw new IllegalArgumentException("receipt repeat warning ordering context is invalid");
            }
            this.currentReceiptId = currentReceiptId;
            this.currentEffectiveAt = currentEffectiveAt;
            this.currentCreatedAt = currentCreatedAt;
            Set<String> allowed = allowedProductKeys == null ? Set.of() : Set.copyOf(allowedProductKeys);
            this.currentItems = currentItems.stream().map(item -> {
                if (item == null || item.itemId() == null || item.name() == null || item.name().isBlank()) {
                    throw new IllegalArgumentException("receipt repeat warning item is invalid");
                }
                return new CurrentState(item, allowed);
            }).toList();
            this.allowedProductKeys = allowed;
        }

        public void add(HistoryItem item) {
            if (item == null || item.receiptId() == null || item.name() == null || item.name().isBlank()
                    || item.occurredAt() == null || item.createdAt() == null) {
                throw new IllegalArgumentException("receipt repeat warning history row is invalid");
            }
            if (currentReceiptId.equals(item.receiptId()) || item.verdict() == null
                    || !WASTE_VERDICTS.contains(item.verdict())) return;
            if (currentEffectiveAt != null && !isEarlier(item, currentEffectiveAt, currentCreatedAt)) return;
            String historyKey = ProductIdentityPolicy.productKey(item.name());
            if (historyKey.isEmpty() || allowedProductKeys.contains(historyKey)) return;
            for (CurrentState current : currentItems) {
                if (current.allowed || !ProductIdentityPolicy.sameProduct(current.item.name(), item.name())) continue;
                current.count++;
                if (current.last == null || compareOrder(item, current.last) > 0) current.last = item;
            }
        }

        public List<Warning> finish() {
            List<Warning> warnings = new ArrayList<>();
            for (CurrentState current : currentItems) {
                if (current.count == 0 || current.last == null) continue;
                warnings.add(new Warning(current.item.itemId(), current.item.name(), current.last.verdict(),
                        TITLES.get(current.last.verdict()), current.count, money(current.last.lineSum()), current.last.advice()));
            }
            warnings.sort(Comparator.comparingInt(Warning::count).reversed()
                    .thenComparing(warning -> new BigDecimal(warning.lastSum()), Comparator.reverseOrder()));
            return List.copyOf(warnings);
        }

        private static String money(BigDecimal amount) {
            return (amount == null ? BigDecimal.ZERO : amount).setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        }

        private static boolean isEarlier(HistoryItem item, Instant effectiveAt, Instant createdAt) {
            int effectiveOrder = item.occurredAt().compareTo(effectiveAt);
            return effectiveOrder < 0 || effectiveOrder == 0 && item.createdAt().isBefore(createdAt);
        }

        private static int compareOrder(HistoryItem left, HistoryItem right) {
            int effectiveOrder = left.occurredAt().compareTo(right.occurredAt());
            if (effectiveOrder != 0) return effectiveOrder;
            int createdOrder = left.createdAt().compareTo(right.createdAt());
            if (createdOrder != 0) return createdOrder;
            int receiptHighOrder = Long.compareUnsigned(left.receiptId().getMostSignificantBits(),
                    right.receiptId().getMostSignificantBits());
            return receiptHighOrder != 0 ? receiptHighOrder
                    : Long.compareUnsigned(left.receiptId().getLeastSignificantBits(),
                    right.receiptId().getLeastSignificantBits());
        }
    }

    public record CurrentItem(UUID itemId, String name) {}
    public record HistoryItem(UUID receiptId, String name, String verdict, BigDecimal lineSum,
                              String advice, Instant occurredAt, Instant createdAt) {
        public HistoryItem(UUID receiptId, String name, String verdict, BigDecimal lineSum,
                           String advice, Instant occurredAt) {
            this(receiptId, name, verdict, lineSum, advice, occurredAt, occurredAt);
        }
    }
    public record Warning(UUID itemId, String name, String verdict, String title, int count,
                          String lastSum, String advice) {}

    private static final class CurrentState {
        private final CurrentItem item;
        private final boolean allowed;
        private int count;
        private HistoryItem last;

        private CurrentState(CurrentItem item, Set<String> allowedKeys) {
            this.item = item;
            String key = ProductIdentityPolicy.productKey(item.name());
            this.allowed = key.isEmpty() || allowedKeys.contains(key);
        }
    }
}
