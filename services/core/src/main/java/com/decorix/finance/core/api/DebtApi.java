package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DebtApi {
    private DebtApi() {}

    public record CreateRequest(String name, String openingBalance, String interestRate, String minimumPayment) {}
    public record PaymentRequest(String amount, Instant occurredAt) {}
    public record BalanceAdjustmentRequest(String currentBalance) {}
    public record DebtResponse(UUID id, UUID tenantId, String name, String openingBalance,
                               String currentBalance, String interestRate, String minimumPayment,
                               String status, long version, Instant createdAt) {}
    public record PaymentResponse(UUID transactionId, String balanceReduction, DebtResponse debt) {}
    public record ForecastResponse(UUID debtId, Integer monthsToPayoff, String estimateBasis) {}
    public record Page(List<DebtResponse> items) {}
}
