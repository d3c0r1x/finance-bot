package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptReconciliationPolicyTest {
    @Test
    void manualReceiptEditorsUseTheSameExactToleranceRule() {
        assertTrue(ReceiptReconciliationPolicy.totalsReconcile(
                new BigDecimal("100.00"), new BigDecimal("103.00")));
        assertFalse(ReceiptReconciliationPolicy.totalsReconcile(
                new BigDecimal("100.00"), new BigDecimal("103.01")));
        assertFalse(ReceiptReconciliationPolicy.totalsReconcile(
                new BigDecimal("100.00"), null));
        assertFalse(ReceiptReconciliationPolicy.totalsReconcile(
                new BigDecimal("0.00"), BigDecimal.ZERO));
    }

    @Test
    void selectsTheOnlyReadingWhoseItemsReconcileToTheCashTotal() {
        var result = ReceiptReconciliationPolicy.evaluate(
                reading(ReceiptReconciliationPolicy.Reader.OCR, "100.00", "SAMPLE", "2026-09-01", "97.00"),
                reading(ReceiptReconciliationPolicy.Reader.VISION, "100.00", "SAMPLE", "2026-09-01", "100.00"));

        assertEquals(ReceiptReconciliationPolicy.Decision.AUTO_SELECTED, result.decision());
        assertEquals("receipt-reconciliation.v1", result.algorithmVersion());
        assertEquals(ReceiptReconciliationPolicy.Reader.VISION, result.selectedReader());
        assertEquals(List.of(), result.mismatchFields());
        assertEquals("100.00", result.visionItemsTotal().toPlainString());
    }

    @Test
    void whenBothReadingsReconcileItPrefersTheMoreCompleteAndThenVisionOnATie() {
        var moreComplete = ReceiptReconciliationPolicy.evaluate(
                reading(ReceiptReconciliationPolicy.Reader.OCR, "100.00", "SAMPLE", "2026-09-01", "100.00"),
                reading(ReceiptReconciliationPolicy.Reader.VISION, "100.00", "SAMPLE", "2026-09-01", "60.00", "40.00"));
        var tie = ReceiptReconciliationPolicy.evaluate(
                reading(ReceiptReconciliationPolicy.Reader.OCR, "100.00", "SAMPLE", "2026-09-01", "100.00"),
                reading(ReceiptReconciliationPolicy.Reader.VISION, "100.00", "SAMPLE", "2026-09-01", "100.00"));

        assertEquals(ReceiptReconciliationPolicy.Reader.VISION, moreComplete.selectedReader());
        assertEquals(ReceiptReconciliationPolicy.Reader.VISION, tie.selectedReader());
    }

    @Test
    void coreFieldDisagreementRequiresHumanReviewEvenWhenBothSumsMatch() {
        var result = ReceiptReconciliationPolicy.evaluate(
                reading(ReceiptReconciliationPolicy.Reader.OCR, "100.00", "SAMPLE", "2026-09-01", "100.00"),
                reading(ReceiptReconciliationPolicy.Reader.VISION, "101.00", "OTHER", "2026-09-02", "101.00"));

        assertEquals(ReceiptReconciliationPolicy.Decision.REVIEW_REQUIRED, result.decision());
        assertNull(result.selectedReader());
        assertEquals(List.of("total", "merchant", "date"), result.mismatchFields());
    }

    @Test
    void unresolvedArithmeticDoesNotSilentlySelectAReading() {
        var result = ReceiptReconciliationPolicy.evaluate(
                reading(ReceiptReconciliationPolicy.Reader.OCR, "100.00", "SAMPLE", "2026-09-01", "80.00"),
                reading(ReceiptReconciliationPolicy.Reader.VISION, "100.00", "SAMPLE", "2026-09-01", "70.00"));

        assertEquals(ReceiptReconciliationPolicy.Decision.REVIEW_REQUIRED, result.decision());
        assertNull(result.selectedReader());
        assertEquals(false, result.ocrItemsReconciled());
        assertEquals(false, result.visionItemsReconciled());
    }

    @Test
    void usesExactDecimalArithmeticAndTheLegacyThreePercentMinimumTolerance() {
        var atBoundary = ReceiptReconciliationPolicy.evaluate(
                reading(ReceiptReconciliationPolicy.Reader.OCR, "100.00", "SAMPLE", "2026-09-01", "97.00"), null);
        var outsideBoundary = ReceiptReconciliationPolicy.evaluate(
                reading(ReceiptReconciliationPolicy.Reader.OCR, "100.00", "SAMPLE", "2026-09-01", "96.99"), null);
        var fractionalBoundary = ReceiptReconciliationPolicy.evaluate(
                reading(ReceiptReconciliationPolicy.Reader.OCR, "100.10", "SAMPLE", "2026-09-01", "97.09"), null);

        assertEquals(ReceiptReconciliationPolicy.Decision.AUTO_SELECTED, atBoundary.decision());
        assertEquals(0, atBoundary.allowedDifference().compareTo(new BigDecimal("3.00")));
        assertEquals(ReceiptReconciliationPolicy.Decision.REVIEW_REQUIRED, outsideBoundary.decision());
        assertEquals(ReceiptReconciliationPolicy.Decision.REVIEW_REQUIRED, fractionalBoundary.decision());
    }

    @Test
    void missingReadingsOrTotalsAreMarkedInsufficientRatherThanAsZero() {
        var noReading = ReceiptReconciliationPolicy.evaluate(null, null);
        var noTotal = ReceiptReconciliationPolicy.evaluate(
                new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.OCR, null,
                        "SAMPLE", LocalDate.parse("2026-09-01"), List.of()), null);

        assertEquals(ReceiptReconciliationPolicy.Decision.INSUFFICIENT_DATA, noReading.decision());
        assertEquals(ReceiptReconciliationPolicy.Decision.INSUFFICIENT_DATA, noTotal.decision());
        assertNull(noTotal.selectedReader());
        assertNull(noTotal.allowedDifference());
    }

    @Test
    void oneOcrItemCanCorroborateOnlyOneOfTwoIdenticalVisionItems() {
        var vision = new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.VISION,
                new BigDecimal("30.00"), "SAMPLE", LocalDate.parse("2026-09-01"), List.of(
                        new ReceiptReconciliationPolicy.Item("Хлеб", new BigDecimal("10.00")),
                        new ReceiptReconciliationPolicy.Item("Хлеб", new BigDecimal("20.00"))));
        var ocr = new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.OCR,
                new BigDecimal("30.00"), "SAMPLE", LocalDate.parse("2026-09-01"), List.of(
                        new ReceiptReconciliationPolicy.Item("Хлеб", new BigDecimal("10.00"))));

        var result = ReceiptReconciliationPolicy.evaluate(ocr, vision);

        assertEquals(ReceiptReconciliationPolicy.Reader.VISION, result.selectedReader());
        assertEquals(List.of(
                new ReceiptReconciliationPolicy.ItemEvidence(1, 1,
                        ReceiptReconciliationPolicy.EvidenceStatus.CORROBORATED),
                new ReceiptReconciliationPolicy.ItemEvidence(2, null,
                        ReceiptReconciliationPolicy.EvidenceStatus.READER_ONLY)), result.itemEvidence());
    }

    @Test
    void duplicateNamesMatchTheClosestUnusedOcrAmountInsteadOfItsFirstRow() {
        var vision = new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.VISION,
                new BigDecimal("30.00"), "SAMPLE", LocalDate.parse("2026-09-01"), List.of(
                        new ReceiptReconciliationPolicy.Item("Хлеб", new BigDecimal("10.00")),
                        new ReceiptReconciliationPolicy.Item("Хлеб", new BigDecimal("20.00"))));
        var ocr = new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.OCR,
                new BigDecimal("30.00"), "SAMPLE", LocalDate.parse("2026-09-01"), List.of(
                        new ReceiptReconciliationPolicy.Item("Хлеб", new BigDecimal("20.00")),
                        new ReceiptReconciliationPolicy.Item("Хлеб", new BigDecimal("10.00"))));

        var result = ReceiptReconciliationPolicy.evaluate(ocr, vision);

        assertEquals(List.of(
                new ReceiptReconciliationPolicy.ItemEvidence(1, 2,
                        ReceiptReconciliationPolicy.EvidenceStatus.CORROBORATED),
                new ReceiptReconciliationPolicy.ItemEvidence(2, 1,
                        ReceiptReconciliationPolicy.EvidenceStatus.CORROBORATED)), result.itemEvidence());
    }

    @Test
    void suggestsOnlyAnUnambiguousTopUpOfAtMostThreeUnusedOcrRows() {
        var vision = new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.VISION,
                new BigDecimal("100.00"), null, null, List.of(
                        new ReceiptReconciliationPolicy.Item("Bread", new BigDecimal("60.00"))));
        var ocr = new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.OCR,
                new BigDecimal("100.00"), null, null, List.of(
                        new ReceiptReconciliationPolicy.Item("Bread", new BigDecimal("60.00")),
                        new ReceiptReconciliationPolicy.Item("Apples", new BigDecimal("25.00")),
                        new ReceiptReconciliationPolicy.Item("Milk", new BigDecimal("15.00")),
                        new ReceiptReconciliationPolicy.Item("Candy", new BigDecimal("20.00"))));

        var result = ReceiptReconciliationPolicy.evaluate(ocr, vision);

        assertEquals(ReceiptReconciliationPolicy.Decision.REVIEW_REQUIRED, result.decision());
        assertEquals(List.of(
                new ReceiptReconciliationPolicy.TopUpSuggestion(2, "Apples", new BigDecimal("25.00")),
                new ReceiptReconciliationPolicy.TopUpSuggestion(3, "Milk", new BigDecimal("15.00"))),
                result.suggestedTopUps());
    }

    @Test
    void suppressesAmbiguousTopUpsAndNeverChangesDecisionToConfirmed() {
        var vision = new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.VISION,
                new BigDecimal("100.00"), null, null, List.of(
                        new ReceiptReconciliationPolicy.Item("Bread", new BigDecimal("60.00"))));
        var ambiguousOcr = new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.OCR,
                new BigDecimal("100.00"), null, null, List.of(
                        new ReceiptReconciliationPolicy.Item("Bread", new BigDecimal("60.00")),
                        new ReceiptReconciliationPolicy.Item("Milk", new BigDecimal("20.00")),
                        new ReceiptReconciliationPolicy.Item("Juice", new BigDecimal("20.00")),
                        new ReceiptReconciliationPolicy.Item("Sugar", new BigDecimal("40.00"))));

        var result = ReceiptReconciliationPolicy.evaluate(ambiguousOcr, vision);

        assertEquals(ReceiptReconciliationPolicy.Decision.REVIEW_REQUIRED, result.decision());
        assertTrue(result.suggestedTopUps().isEmpty());
    }

    @Test
    void countsAWithinTwoCentsExtensionAsAnotherPossibleTopUp() {
        var vision = new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.VISION,
                new BigDecimal("100.00"), null, null, List.of(
                        new ReceiptReconciliationPolicy.Item("Bread", new BigDecimal("60.00"))));
        var ocr = new ReceiptReconciliationPolicy.Reading(ReceiptReconciliationPolicy.Reader.OCR,
                new BigDecimal("100.00"), null, null, List.of(
                        new ReceiptReconciliationPolicy.Item("Bread", new BigDecimal("60.00")),
                        new ReceiptReconciliationPolicy.Item("Milk", new BigDecimal("40.00")),
                        new ReceiptReconciliationPolicy.Item("Other", new BigDecimal("0.01")),
                        new ReceiptReconciliationPolicy.Item("Candy", new BigDecimal("50.00"))));

        var result = ReceiptReconciliationPolicy.evaluate(ocr, vision);

        assertEquals(ReceiptReconciliationPolicy.Decision.REVIEW_REQUIRED, result.decision());
        assertTrue(result.suggestedTopUps().isEmpty());
    }

    private static ReceiptReconciliationPolicy.Reading reading(ReceiptReconciliationPolicy.Reader reader,
                                                                String total, String merchant, String date,
                                                                String... itemSums) {
        var items = java.util.stream.IntStream.range(0, itemSums.length)
                .mapToObj(index -> new ReceiptReconciliationPolicy.Item("Item " + index,
                        new BigDecimal(itemSums[index])))
                .toList();
        return new ReceiptReconciliationPolicy.Reading(reader, new BigDecimal(total), merchant,
                LocalDate.parse(date), items);
    }
}
