package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecurringProjectionPolicyTest {
    @Test
    void detectsStableMonthlySeriesAndKeepsIncomeAndExpenseSeparate() {
        var history = List.of(
                item("expense", "999.00", "Подписка Netflix", "развлечения", "2026-07-01"),
                item("expense", "999.00", "Подписка Netflix", "развлечения", "2026-08-01"),
                item("expense", "999.00", "Подписка Netflix", "развлечения", "2026-09-01"),
                item("income", "90000.00", "Зарплата за июль", "зарплата", "2026-07-05"),
                item("income", "90000.00", "Зарплата за август", "зарплата", "2026-08-05"),
                item("income", "90000.00", "Зарплата за сентябрь", "зарплата", "2026-09-05"));

        var projections = RecurringProjectionPolicy.detect(history, LocalDate.parse("2026-10-01"));

        assertEquals(2, projections.size());
        assertTrue(projections.stream().anyMatch(item -> item.type().equals("expense")
                && item.amount().equals(new BigDecimal("999.00"))
                && item.nextDueDate().equals(LocalDate.parse("2026-10-02"))));
        assertTrue(projections.stream().anyMatch(item -> item.type().equals("income")
                && item.nextDueDate().equals(LocalDate.parse("2026-10-06"))));
    }

    @Test
    void rejectsUnstableAmountsAndTwoOccurrenceSeries() {
        var history = List.of(
                item("expense", "100.00", "Кофе подписка", "прочее", "2026-07-01"),
                item("expense", "100.00", "Кофе подписка", "прочее", "2026-08-01"),
                item("expense", "200.00", "Кофе подписка", "прочее", "2026-09-01"),
                item("expense", "500.00", "Облако", "прочее", "2026-08-01"),
                item("expense", "500.00", "Облако", "прочее", "2026-09-01"));

        assertTrue(RecurringProjectionPolicy.detect(history, LocalDate.parse("2026-10-01")).isEmpty());
    }

    private static RecurringProjectionPolicy.HistoryItem item(String type, String amount, String description,
                                                               String category, String date) {
        return new RecurringProjectionPolicy.HistoryItem(type, new BigDecimal(amount), category, description,
                LocalDate.parse(date));
    }
}
