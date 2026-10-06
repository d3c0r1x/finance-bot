package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TelegramActorContextApi.ResolveRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.ReportRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.BudgetUpdateRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.BudgetResetRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.TelegramBudgetProposalRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.TelegramBudgetProposalApplyRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.CreateDraftRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftCancelRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftCancelledResponse;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftDecisionRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftAmountRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftEditRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DebtListResponse;
import com.decorix.finance.core.api.TelegramActorContextApi.HistoryRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.ProductCatalogRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.RepeatTransactionRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.TransactionHistoryResponse;
import com.decorix.finance.core.api.TelegramActorContextApi.VoidLatestTransactionRequest;
import com.decorix.finance.core.api.TransactionApi.DashboardSummary;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import com.decorix.finance.core.api.ReportApi.Report;
import com.decorix.finance.core.api.TransactionDraftApi.DraftResponse;
import com.decorix.finance.core.api.DebtApi.DebtResponse;
import com.decorix.finance.core.api.BudgetApi.Overview;
import com.decorix.finance.core.api.BudgetApi.BudgetProposalResponse;
import com.decorix.finance.core.api.ProductApi.ProductCatalogResponse;
import com.decorix.finance.core.api.ProductApi.ShoppingList;
import com.decorix.finance.core.api.InflationApi.PersonalInflation;
import com.decorix.finance.core.api.RecurringApi.RecurringProjection;
import java.util.UUID;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/telegram")
public class TelegramActionController {
    private final TelegramActionService actions;
    private final String serviceToken;

    public TelegramActionController(TelegramActionService actions,
                                    @Value("${finance.telegram.service-token:}") String serviceToken) {
        this.actions = actions;
        this.serviceToken = serviceToken;
    }

    @PostMapping("/summary")
    DashboardSummary summary(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.dashboardSummary(request == null ? null : request.token());
    }

    @PostMapping("/report")
    Report report(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody ReportRequest request) {
        requireServiceToken(suppliedToken);
        return actions.report(request);
    }

    @PostMapping("/products/catalog")
    ProductCatalogResponse productCatalog(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody ProductCatalogRequest request) {
        requireServiceToken(suppliedToken);
        return actions.productCatalog(request);
    }

    @PostMapping("/shopping")
    ShoppingList shopping(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.shopping(request);
    }

    @PostMapping("/actions/personal-inflation")
    PersonalInflation personalInflation(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.personalInflation(request);
    }

    @PostMapping("/actions/recurring")
    RecurringProjection recurring(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.recurring(request);
    }

    @PostMapping("/actions/recurring/{seriesId}/mute")
    RecurringProjection muteRecurring(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable String seriesId, @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.muteRecurring(request, seriesId);
    }

    @PostMapping("/actions/recurring/{seriesId}/unmute")
    RecurringProjection unmuteRecurring(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable String seriesId, @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.unmuteRecurring(request, seriesId);
    }

    @PostMapping("/shopping/{productKey}/bought")
    ShoppingList markShoppingBought(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable String productKey, @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.markShoppingBought(request, productKey);
    }

    @PostMapping("/shopping/{productKey}/mute")
    ShoppingList muteShopping(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable String productKey, @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.muteShopping(request, productKey);
    }

    @PostMapping("/shopping/{productKey}/unmute")
    ShoppingList unmuteShopping(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable String productKey, @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.unmuteShopping(request, productKey);
    }

    @PostMapping("/transaction-drafts")
    ResponseEntity<DraftResponse> createDraft(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody CreateDraftRequest request) {
        requireServiceToken(suppliedToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(actions.createDraft(request));
    }

    @PostMapping("/transaction-drafts/{draftId}/confirm")
    TransactionResponse confirmDraft(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable UUID draftId,
            @RequestBody DraftDecisionRequest request) {
        requireServiceToken(suppliedToken);
        return actions.confirmDraft(draftId, request);
    }

    @PostMapping("/transaction-drafts/{draftId}/amount")
    DraftResponse updateDraftAmount(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable UUID draftId,
            @RequestBody DraftAmountRequest request) {
        requireServiceToken(suppliedToken);
        return actions.updateDraftAmount(draftId, request);
    }

    @PostMapping("/transaction-drafts/{draftId}/read")
    DraftResponse getDraft(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable UUID draftId,
            @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.getDraft(draftId, request);
    }

    @PostMapping("/transaction-drafts/{draftId}/edit")
    DraftResponse editDraft(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable UUID draftId,
            @RequestBody DraftEditRequest request) {
        requireServiceToken(suppliedToken);
        return actions.editDraft(draftId, request);
    }

    @PostMapping("/transaction-drafts/{draftId}/cancel")
    DraftCancelledResponse cancelDraft(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable UUID draftId,
            @RequestBody DraftCancelRequest request) {
        requireServiceToken(suppliedToken);
        return actions.cancelDraft(draftId, request);
    }

    @PostMapping("/debts")
    DebtListResponse listOpenDebts(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return new DebtListResponse(actions.listOpenDebts(request));
    }

    @PostMapping("/budgets")
    Overview budgets(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody ResolveRequest request) {
        requireServiceToken(suppliedToken);
        return actions.budgetOverview(request);
    }

    @PostMapping("/budgets/{budgetKey}/update")
    Overview updateBudget(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable String budgetKey,
            @RequestBody BudgetUpdateRequest request) {
        requireServiceToken(suppliedToken);
        return actions.updateBudget(budgetKey, request);
    }

    @PostMapping("/budgets/reset")
    Overview resetPersonalBudgets(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody BudgetResetRequest request) {
        requireServiceToken(suppliedToken);
        return actions.resetPersonalBudgets(request);
    }

    @PostMapping("/budget-proposals")
    ResponseEntity<BudgetProposalResponse> createBudgetProposal(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody TelegramBudgetProposalRequest request) {
        requireServiceToken(suppliedToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(actions.createBudgetProposal(request));
    }

    @PostMapping("/budget-proposals/history")
    ResponseEntity<BudgetProposalResponse> createHistoryBudgetProposal(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody TelegramBudgetProposalRequest request) {
        requireServiceToken(suppliedToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(actions.createHistoryBudgetProposal(request));
    }

    @PostMapping("/budget-proposals/{proposalId}/apply")
    Overview applyBudgetProposal(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @PathVariable UUID proposalId,
            @RequestBody TelegramBudgetProposalApplyRequest request) {
        requireServiceToken(suppliedToken);
        return actions.applyBudgetProposal(proposalId, request);
    }

    @PostMapping("/transactions")
    TransactionHistoryResponse listTransactions(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody HistoryRequest request) {
        requireServiceToken(suppliedToken);
        return new TransactionHistoryResponse(actions.listRecentTransactions(request));
    }

    @PostMapping("/transaction-drafts/repeat")
    ResponseEntity<DraftResponse> repeatTransaction(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody RepeatTransactionRequest request) {
        requireServiceToken(suppliedToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(actions.repeatTransaction(request));
    }

    @PostMapping("/transactions/latest/void")
    TransactionResponse voidLatestTransaction(
            @RequestHeader(name = "X-Finance-Service-Token", required = false) String suppliedToken,
            @RequestBody VoidLatestTransactionRequest request) {
        requireServiceToken(suppliedToken);
        return actions.voidLatestTransaction(request);
    }

    private void requireServiceToken(String suppliedToken) {
        if (serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Telegram service credential is not configured");
        }
        if (!TelegramServiceCredential.matches(serviceToken, suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Telegram service credential");
        }
    }
}
