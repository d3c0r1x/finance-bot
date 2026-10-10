package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReceiptRepeatWarningPolicyTest {
    @Test
    void excludesCurrentReceiptAndAllowedProductsAndUsesOnlyEarlierWasteVerdicts() {
        UUID currentReceipt = UUID.randomUUID();
        UUID previousReceipt = UUID.randomUUID();
        UUID milkItem = UUID.randomUUID();
        UUID breadItem = UUID.randomUUID();
        var warnings = ReceiptRepeatWarningPolicy.apply(currentReceipt, List.of(
                        new ReceiptRepeatWarningPolicy.CurrentItem(milkItem, "Йогурт Активиа 150г"),
                        new ReceiptRepeatWarningPolicy.CurrentItem(breadItem, "Хлеб")),
                List.of(
                        new ReceiptRepeatWarningPolicy.HistoryItem(currentReceipt, "Йогурт Активиа 150г", "harmful",
                                new BigDecimal("99.00"), "current", Instant.parse("2026-10-02T00:00:00Z")),
                        new ReceiptRepeatWarningPolicy.HistoryItem(previousReceipt, "Йогурт Активиа", "harmful",
                                new BigDecimal("12.50"), "buy less", Instant.parse("2026-10-01T00:00:00Z")),
                        new ReceiptRepeatWarningPolicy.HistoryItem(previousReceipt, "Хлеб", "unnecessary",
                                new BigDecimal("3.00"), "bread advice", Instant.parse("2026-10-01T00:00:00Z"))),
                Set.of(ProductIdentityPolicy.productKey("Хлеб")));

        assertEquals(1, warnings.size());
        assertEquals(milkItem, warnings.get(0).itemId());
        assertEquals(1, warnings.get(0).count());
        assertEquals("12.50", warnings.get(0).lastSum());
        assertEquals("buy less", warnings.get(0).advice());
    }

    @Test
    void warningsSortByRepeatCountThenMostRecentPreviousAmount() {
        UUID currentReceipt = UUID.randomUUID();
        UUID past = UUID.randomUUID();
        UUID repeated = UUID.randomUUID();
        UUID single = UUID.randomUUID();
        UUID largerAmount = UUID.randomUUID();
        var warnings = ReceiptRepeatWarningPolicy.apply(currentReceipt, List.of(
                        new ReceiptRepeatWarningPolicy.CurrentItem(single, "Coffee"),
                        new ReceiptRepeatWarningPolicy.CurrentItem(repeated, "Chips Lays 120g"),
                        new ReceiptRepeatWarningPolicy.CurrentItem(largerAmount, "Tea")),
                List.of(
                        new ReceiptRepeatWarningPolicy.HistoryItem(past, "Coffee", "harmful", new BigDecimal("20.00"), "", Instant.parse("2026-09-01T00:00:00Z")),
                        new ReceiptRepeatWarningPolicy.HistoryItem(past, "Chips Lays 120g", "unnecessary", new BigDecimal("10.00"), "", Instant.parse("2026-09-01T00:00:00Z")),
                        new ReceiptRepeatWarningPolicy.HistoryItem(UUID.randomUUID(), "CHIPS LAYS 120G", "harmful", new BigDecimal("8.00"), "", Instant.parse("2026-09-02T00:00:00Z")),
                        new ReceiptRepeatWarningPolicy.HistoryItem(past, "Tea", "harmful", new BigDecimal("100.00"), "", Instant.parse("2026-09-01T00:00:00Z"))),
                Set.of());

        assertEquals(List.of(repeated, largerAmount, single), warnings.stream().map(ReceiptRepeatWarningPolicy.Warning::itemId).toList());
        assertEquals(2, warnings.get(0).count());
    }

    @Test
    void excludesHistoryAfterCurrentReceiptEffectivePointButKeepsEarlierSameDayReceipt() {
        UUID currentReceipt = UUID.randomUUID();
        UUID earlierDayReceipt = UUID.randomUUID();
        UUID earlierSameDayReceipt = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        Instant currentEffectiveAt = Instant.parse("2026-10-02T00:00:00Z");
        Instant currentCreatedAt = Instant.parse("2026-10-02T12:00:00Z");

        var warnings = ReceiptRepeatWarningPolicy.apply(currentReceipt, currentEffectiveAt, currentCreatedAt,
                List.of(new ReceiptRepeatWarningPolicy.CurrentItem(item, "Synthetic oat drink")),
                List.of(
                        new ReceiptRepeatWarningPolicy.HistoryItem(earlierDayReceipt, "Synthetic oat drink", "harmful",
                                new BigDecimal("10.00"), "older advice", Instant.parse("2026-10-01T00:00:00Z"),
                                Instant.parse("2026-10-01T10:00:00Z")),
                        new ReceiptRepeatWarningPolicy.HistoryItem(earlierSameDayReceipt, "Synthetic oat drink", "unnecessary",
                                new BigDecimal("14.00"), "same-day advice", currentEffectiveAt,
                                Instant.parse("2026-10-02T11:00:00Z")),
                        new ReceiptRepeatWarningPolicy.HistoryItem(UUID.randomUUID(), "Synthetic oat drink", "harmful",
                                new BigDecimal("99.00"), "future advice", Instant.parse("2026-10-03T00:00:00Z"),
                                Instant.parse("2026-10-03T10:00:00Z"))),
                Set.of());

        assertEquals(1, warnings.size());
        assertEquals(2, warnings.get(0).count());
        assertEquals("14.00", warnings.get(0).lastSum());
        assertEquals("same-day advice", warnings.get(0).advice());
    }

    @Test
    void selectsSameLastWarningWhenHistoricalReceiptTimestampsTie() {
        UUID currentReceipt = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID firstReceipt = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID tieBreakReceipt = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID item = UUID.fromString("00000000-0000-0000-0000-000000000100");
        Instant currentEffectiveAt = Instant.parse("2026-10-02T00:00:00Z");
        Instant currentCreatedAt = Instant.parse("2026-10-02T12:00:00Z");
        Instant occurredAt = Instant.parse("2026-10-01T00:00:00Z");
        Instant createdAt = Instant.parse("2026-10-01T10:00:00Z");
        var first = new ReceiptRepeatWarningPolicy.HistoryItem(firstReceipt, "Synthetic oat drink", "harmful",
                new BigDecimal("10.00"), "first receipt advice", occurredAt, createdAt);
        var tieBreak = new ReceiptRepeatWarningPolicy.HistoryItem(tieBreakReceipt, "Synthetic oat drink", "unnecessary",
                new BigDecimal("20.00"), "stable tie-break advice", occurredAt, createdAt);
        var currentItems = List.of(new ReceiptRepeatWarningPolicy.CurrentItem(item, "Synthetic oat drink"));

        var firstOrder = ReceiptRepeatWarningPolicy.apply(currentReceipt, currentEffectiveAt, currentCreatedAt,
                currentItems, List.of(first, tieBreak), Set.of());
        var reversedOrder = ReceiptRepeatWarningPolicy.apply(currentReceipt, currentEffectiveAt, currentCreatedAt,
                currentItems, List.of(tieBreak, first), Set.of());

        assertEquals(2, firstOrder.get(0).count());
        assertEquals(2, reversedOrder.get(0).count());
        assertEquals("20.00", firstOrder.get(0).lastSum());
        assertEquals("20.00", reversedOrder.get(0).lastSum());
        assertEquals("stable tie-break advice", firstOrder.get(0).advice());
        assertEquals("stable tie-break advice", reversedOrder.get(0).advice());
        assertEquals("unnecessary", firstOrder.get(0).verdict());
        assertEquals("unnecessary", reversedOrder.get(0).verdict());
    }
}
