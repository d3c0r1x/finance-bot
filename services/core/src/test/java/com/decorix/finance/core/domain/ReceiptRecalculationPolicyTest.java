package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReceiptRecalculationPolicyTest {
    @Test
    void previewRechecksOnlyModelAndDefaultVerdictsAndPreservesReceiptAmounts() {
        UUID pastaId = UUID.randomUUID();
        UUID chipsId = UUID.randomUUID();
        UUID alreadyRuleId = UUID.randomUUID();
        UUID humanId = UUID.randomUUID();
        UUID unknownId = UUID.randomUUID();
        var lines = List.of(
                new ReceiptRecalculationPolicy.Line(pastaId, "Макароны перья", new BigDecimal("123.45"),
                        "unnecessary", "лишнее", "не брать", "model"),
                new ReceiptRecalculationPolicy.Line(chipsId, "Чипсы Lays 120г", new BigDecimal("77.10"),
                        "harmful", "", "", "default"),
                new ReceiptRecalculationPolicy.Line(alreadyRuleId, "Пиво", new BigDecimal("90.00"),
                        "harmful", "rule reason", "rule action", "rule"),
                new ReceiptRecalculationPolicy.Line(humanId, "Алкоголь", new BigDecimal("300.00"),
                        "useful", "human reason", "human action", "human"),
                new ReceiptRecalculationPolicy.Line(unknownId, "Энергетик", new BigDecimal("50.00"),
                        "neutral", "", "", "unknown"));

        var plan = ReceiptRecalculationPolicy.preview(lines);

        assertEquals(5, plan.checked());
        assertEquals(List.of(pastaId, chipsId), plan.updates().stream()
                .map(ReceiptRecalculationPolicy.Update::itemId).toList());
        var pasta = plan.updates().get(0);
        assertEquals("unnecessary", pasta.beforeVerdict());
        assertEquals("neutral", pasta.afterVerdict());
        assertEquals("model", pasta.beforeSource());
        assertEquals("rule", pasta.afterSource());
        assertEquals(new BigDecimal("123.45"), pasta.lineSum());
        var chips = plan.updates().get(1);
        assertEquals("harmful", chips.afterVerdict());
        assertEquals("снек, много калорий", chips.afterReason());
        assertEquals(new BigDecimal("77.10"), chips.lineSum());
        assertTrue(plan.updates().stream().noneMatch(update ->
                update.itemId().equals(alreadyRuleId) || update.itemId().equals(humanId)
                        || update.itemId().equals(unknownId)));
    }

    @Test
    void previewReportsRuleConfirmationOnceAndLeavesUncoveredModelOpinionUntouched() {
        UUID chipsId = UUID.randomUUID();
        UUID uncoveredId = UUID.randomUUID();
        var lines = List.of(
                new ReceiptRecalculationPolicy.Line(chipsId, "Чипсы Lays 120г", new BigDecimal("77.10"),
                        "harmful", "снек, много калорий", "сравнить цену за 100 г и взять одну пачку", "model"),
                new ReceiptRecalculationPolicy.Line(uncoveredId, "Свежие абрикосы", new BigDecimal("40.00"),
                        "harmful", "дорогой продукт", "покупать реже", "model"));

        var preview = ReceiptRecalculationPolicy.preview(lines);

        assertEquals(1, preview.updates().size());
        assertEquals(chipsId, preview.updates().get(0).itemId());
        assertFalse(preview.updates().get(0).changed());
        var persisted = new ReceiptRecalculationPolicy.Line(chipsId, "Чипсы Lays 120г", new BigDecimal("77.10"),
                "harmful", "снек, много калорий", "сравнить цену за 100 г и взять одну пачку", "rule");
        assertTrue(ReceiptRecalculationPolicy.preview(List.of(persisted)).updates().isEmpty());
    }
}
