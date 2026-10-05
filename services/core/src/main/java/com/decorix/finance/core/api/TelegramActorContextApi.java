package com.decorix.finance.core.api;

import com.decorix.finance.core.api.DebtApi.DebtResponse;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class TelegramActorContextApi {
    private TelegramActorContextApi() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TenantListRequest(Long telegramUserId, String telegramDisplayName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueRequest(Long telegramUserId, UUID tenantId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolveRequest(String token) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryRequest(String token, Integer limit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductCatalogRequest(String token, String query) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReportRequest(String token, String period, YearMonth month, LocalDate from, LocalDate to, String scope) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BudgetUpdateRequest(String token, String idempotencyKey, String scope, String amount,
                               Long version, String period) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BudgetResetRequest(String token, String idempotencyKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramBudgetProposalRequest(String token, String idempotencyKey, String monthlyIncome) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TelegramBudgetProposalApplyRequest(String token, String idempotencyKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RepeatTransactionRequest(String token, String idempotencyKey, UUID transactionId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VoidLatestTransactionRequest(String token, String idempotencyKey, UUID transactionId, Long version) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreateDraftRequest(String token, String idempotencyKey, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DraftDecisionRequest(String token, String idempotencyKey, Long version) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DraftAmountRequest(String token, Long version, String amount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DraftEditRequest(String token, Long version, String type, String amount, String currency,
                            String categoryCode, String subcategoryCode, String description,
                            Instant occurredAt, UUID debtId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DraftCancelRequest(String token, Long version) {}

    record DraftCancelledResponse(String status) {}
    record DebtListResponse(List<DebtResponse> debts) {}
    record TransactionHistoryResponse(List<TransactionResponse> transactions) {}

    record TenantOption(UUID tenantId, String displayName, String role) {}
    record TenantListResponse(List<TenantOption> tenants) {}
    record ActorContextResponse(String token, UUID tenantId, String displayName, String role,
                                List<String> permissions, Instant expiresAt) {}
    record ResolvedContextResponse(UUID tenantId, String displayName, String role,
                                   List<String> permissions, Instant expiresAt) {}
    record ActorContext(long telegramUserId, UUID tenantId, UUID userId, String keycloakSubject, String role,
                        Set<String> permissions, Instant expiresAt) {}
}
