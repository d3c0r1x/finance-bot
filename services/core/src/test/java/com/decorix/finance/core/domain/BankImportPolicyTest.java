package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BankImportPolicyTest {
    @Test
    void previewIncludesPurchasesIncomeAndRefundsAndNamesEveryExcludedType() {
        var statement = new BankImportPolicy.StatementInput(
                "valid", "100.00", "50.00", "100.00", "50.00", List.of(
                row(0, "-100.00", "purchase"),
                row(1, "50.00", "income"),
                row(2, "5.00", "refund"),
                row(3, "-3.00", "fee"),
                row(4, "-7.00", "transfer_out"),
                row(5, "-10.00", "withdrawal"),
                row(6, "-12.00", "internal")));

        var preview = BankImportPolicy.preview(statement, Map.of());

        assertEquals("valid", preview.quality());
        assertEquals(7, preview.rows().size());
        assertEquals(3, preview.includedCount());
        assertEquals(4, preview.excludedCount());
        assertEquals("100.00", preview.expenseTotal());
        assertEquals("50.00", preview.incomeTotal());
        assertEquals("5.00", preview.refundTotal());
        assertEquals("0.00", preview.transferTotal());
        assertEquals("32.00", preview.excludedTotal());
        assertEquals(Set.of("bank_fee", "external_transfer", "cash_withdrawal", "internal_transfer"),
                preview.rows().stream().filter(row -> !row.included()).map(
                        BankImportPolicy.PreviewRow::exclusionReason).collect(java.util.stream.Collectors.toSet()));
        assertEquals("-100.00", preview.rows().get(0).signedAmount());
        assertEquals("100.00", preview.rows().get(0).amount());
    }

    @Test
    void explicitRowsCanBeIncludedWithVisibleUserSelectionAndRecomputedTotals() {
        var statement = new BankImportPolicy.StatementInput(
                "mismatch", "20.00", "0.00", "30.00", "0.00", List.of(
                row(0, "-20.00", "purchase"),
                row(1, "-4.00", "fee"),
                row(2, "-6.00", "transfer_out")));

        var preview = BankImportPolicy.preview(statement, Map.of(1, "expense", 2, "transfer"));

        assertEquals("mismatch", preview.quality());
        assertEquals("30.00", preview.expectedExpenseTotal());
        assertEquals(3, preview.includedCount());
        assertEquals(0, preview.excludedCount());
        assertEquals("24.00", preview.expenseTotal());
        assertEquals("6.00", preview.transferTotal());
        assertEquals("0.00", preview.excludedTotal());
        assertEquals("user", preview.rows().get(1).selectionSource());
        assertTrue(preview.rows().get(1).included());
        assertFalse(preview.rows().get(1).exclusionReason() != null);
    }

    @Test
    void unverifiableStatementNeverGetsUpgradedByThePreviewPolicy() {
        var statement = new BankImportPolicy.StatementInput(
                "unverifiable", "25.00", "0.00", null, null, List.of(row(0, "-25.00", "purchase")));

        var preview = BankImportPolicy.preview(statement, Map.of());

        assertEquals("unverifiable", preview.quality());
        assertEquals(null, preview.expectedExpenseTotal());
        assertEquals("25.00", preview.expenseTotal());
    }

    @Test
    void rejectsUnknownRowsInvalidSignsAndSelectionsForMissingOrdinals() {
        var badSign = new BankImportPolicy.StatementInput(
                "valid", "10.00", "0.00", "10.00", "0.00", List.of(row(0, "10.00", "purchase")));
        var valid = new BankImportPolicy.StatementInput(
                "valid", "10.00", "0.00", "10.00", "0.00", List.of(row(0, "-10.00", "purchase")));

        assertThrows(IllegalArgumentException.class, () -> BankImportPolicy.preview(badSign, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> BankImportPolicy.preview(valid, Map.of(2, "expense")));
        assertThrows(IllegalArgumentException.class, () -> BankImportPolicy.preview(valid, Map.of(0, "debt_payment")));
    }

    private static BankImportPolicy.OperationInput row(int ordinal, String amount, String kind) {
        return new BankImportPolicy.OperationInput("2026-10-01", "12:30", amount, "RUB", kind,
                "Магазин", "Операция", "1234", ordinal);
    }
}
