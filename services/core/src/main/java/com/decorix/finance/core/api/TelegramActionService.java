package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TelegramActorContextApi.ActorContext;
import com.decorix.finance.core.api.TelegramActorContextApi.CreateDraftRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftCancelRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftCancelledResponse;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftDecisionRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftAmountRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftEditRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.HistoryRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.ProductCatalogRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.RepeatTransactionRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.ResolveRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.ReportRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.BudgetUpdateRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.BudgetResetRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.TelegramBudgetProposalRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.TelegramBudgetProposalApplyRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.VoidLatestTransactionRequest;
import com.decorix.finance.core.api.TransactionApi.DashboardSummary;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import com.decorix.finance.core.api.ReportApi.Report;
import com.decorix.finance.core.api.TransactionDraftApi.CreateRequest;
import com.decorix.finance.core.api.TransactionDraftApi.DraftResponse;
import com.decorix.finance.core.api.DebtApi.DebtResponse;
import com.decorix.finance.core.api.BudgetApi.Overview;
import com.decorix.finance.core.api.BudgetApi.BudgetProposalResponse;
import com.decorix.finance.core.api.ProductApi.ProductCatalogResponse;
import com.decorix.finance.core.api.ProductApi.ShoppingList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TelegramActionService {
    private final TelegramActorContextService actorContexts;
    private final TransactionService transactions;
    private final TransactionDraftService drafts;
    private final DebtService debts;
    private final BudgetService budgets;
    private final ReportService reports;
    private final TransactionTemplate transaction;
    private final ProductPriceHistoryService productHistory;

    public TelegramActionService(TelegramActorContextService actorContexts, TransactionService transactions,
                                 TransactionDraftService drafts, DebtService debts, BudgetService budgets, ReportService reports,
                                 TransactionTemplate transaction, ProductPriceHistoryService productHistory) {
        this.actorContexts = actorContexts;
        this.transactions = transactions;
        this.drafts = drafts;
        this.debts = debts;
        this.budgets = budgets;
        this.reports = reports;
        this.transaction = transaction;
        this.productHistory = productHistory;
    }

    public DashboardSummary dashboardSummary(String actorToken) {
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(actorToken, "report.read");
            return transactions.summary(actor.tenantId(), actor.keycloakSubject(), null);
        });
    }

    public Report report(ReportRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Report request is required");
        boolean family;
        if ("personal".equals(request.scope())) {
            family = false;
        } else if ("family".equals(request.scope())) {
            family = true;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Report scope must be personal or family");
        }
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "report.read");
            return reports.get(actor.tenantId(), actor.keycloakSubject(), request.period(), request.month(),
                    request.from(), request.to(), family);
        });
    }

    public ProductCatalogResponse productCatalog(ProductCatalogRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product query is required");
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "receipt.read");
            return productHistory.catalog(actor.tenantId(), actor.userId(), request.query());
        });
    }

    public ShoppingList shopping(ResolveRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shopping request is required");
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "receipt.read");
            return productHistory.shopping(actor.tenantId(), actor.userId());
        });
    }

    public DraftResponse createDraft(CreateDraftRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft request is required");
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "transaction.write.own");
            return drafts.create(actor.tenantId(), actor.keycloakSubject(), request.idempotencyKey(),
                    new CreateRequest(request.text()));
        });
    }

    public TransactionResponse confirmDraft(UUID draftId, DraftDecisionRequest request) {
        if (request == null || request.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft decision is required");
        }
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "transaction.write.own");
            return drafts.confirm(actor.tenantId(), actor.keycloakSubject(), draftId,
                    request.idempotencyKey(), request.version());
        });
    }

    public DraftResponse updateDraftAmount(UUID draftId, DraftAmountRequest request) {
        if (request == null || request.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft amount update is required");
        }
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "transaction.write.own");
            return drafts.updateAmount(actor.tenantId(), actor.keycloakSubject(), draftId,
                    request.version(), request.amount());
        });
    }

    public DraftResponse getDraft(UUID draftId, ResolveRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft lookup is required");
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "transaction.write.own");
            return drafts.get(actor.tenantId(), actor.keycloakSubject(), draftId);
        });
    }

    public DraftResponse editDraft(UUID draftId, DraftEditRequest request) {
        if (request == null || request.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft edit is required");
        }
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "transaction.write.own");
            return drafts.update(actor.tenantId(), actor.keycloakSubject(), draftId, request.version(),
                    new com.decorix.finance.core.api.TransactionDraftApi.UpdateRequest(request.type(), request.amount(),
                            request.currency(), request.categoryCode(), request.subcategoryCode(), request.description(),
                            request.occurredAt(), request.debtId()));
        });
    }

    public DraftCancelledResponse cancelDraft(UUID draftId, DraftCancelRequest request) {
        if (request == null || request.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft cancellation is required");
        }
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "transaction.write.own");
            drafts.cancel(actor.tenantId(), actor.keycloakSubject(), draftId, request.version());
            return new DraftCancelledResponse("cancelled");
        });
    }

    public List<DebtResponse> listOpenDebts(ResolveRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Debt list request is required");
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "debt.read");
            return debts.list(actor.tenantId(), actor.keycloakSubject()).items().stream()
                    .filter(debt -> "open".equals(debt.status())).toList();
        });
    }

    public Overview budgetOverview(ResolveRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget request is required");
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "budget.read");
            return budgets.get(actor.tenantId(), actor.keycloakSubject());
        });
    }

    public Overview updateBudget(String budgetKey, BudgetUpdateRequest request) {
        if (request == null || request.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget update is required");
        }
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "budget.write");
            return budgets.update(actor.tenantId(), actor.keycloakSubject(), budgetKey, request.idempotencyKey(),
                    request.version(), new BudgetApi.UpdateRequest(request.scope(), request.amount(), request.period()));
        });
    }

    public Overview resetPersonalBudgets(BudgetResetRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget reset is required");
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "budget.write");
            return budgets.resetPersonal(actor.tenantId(), actor.keycloakSubject(), request.idempotencyKey());
        });
    }

    public BudgetProposalResponse createBudgetProposal(TelegramBudgetProposalRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget proposal is required");
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "budget.write");
            return budgets.propose(actor.tenantId(), actor.keycloakSubject(), request.idempotencyKey(),
                    new BudgetApi.BudgetProposalRequest(request.monthlyIncome()));
        });
    }

    public BudgetProposalResponse createHistoryBudgetProposal(TelegramBudgetProposalRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget proposal is required");
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "budget.write");
            return budgets.proposeFromHistory(actor.tenantId(), actor.keycloakSubject(), request.idempotencyKey());
        });
    }

    public Overview applyBudgetProposal(UUID proposalId, TelegramBudgetProposalApplyRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Budget proposal apply is required");
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "budget.write");
            return budgets.applyProposal(actor.tenantId(), actor.keycloakSubject(), proposalId, request.idempotencyKey());
        });
    }

    public List<TransactionResponse> listRecentTransactions(HistoryRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History request is required");
        int limit = request.limit() == null ? 8 : request.limit();
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "transaction.read");
            return transactions.recent(actor.tenantId(), actor.keycloakSubject(), limit);
        });
    }

    public DraftResponse repeatTransaction(RepeatTransactionRequest request) {
        if (request == null || request.transactionId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repeat request is required");
        }
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "transaction.write.own");
            return drafts.repeat(actor.tenantId(), actor.keycloakSubject(), request.idempotencyKey(),
                    request.transactionId());
        });
    }

    public TransactionResponse voidLatestTransaction(VoidLatestTransactionRequest request) {
        if (request == null || request.transactionId() == null || request.version() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Undo request is required");
        }
        return transaction.execute(status -> {
            ActorContext actor = actorContexts.require(request.token(), "transaction.write.own");
            return transactions.voidLatest(actor.tenantId(), actor.keycloakSubject(), request.transactionId(),
                    request.idempotencyKey(), request.version());
        });
    }
}
