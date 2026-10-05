package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.decorix.finance.core.api.TelegramActorContextApi.ActorContext;
import com.decorix.finance.core.api.TelegramActorContextApi.ProductCatalogRequest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class TelegramProductCatalogTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void productCatalogRequiresCurrentReceiptReadPermissionAndUsesResolvedActorIds() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ActorContext actor = new ActorContext(42L, tenantId, userId, "keycloak-subject", "member",
                Set.of("receipt.read"), Instant.now().plusSeconds(300));
        TelegramActorContextService actorContexts = mock(TelegramActorContextService.class);
        TransactionService transactions = mock(TransactionService.class);
        TransactionDraftService drafts = mock(TransactionDraftService.class);
        DebtService debts = mock(DebtService.class);
        BudgetService budgets = mock(BudgetService.class);
        ReportService reports = mock(ReportService.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        ProductPriceHistoryService products = mock(ProductPriceHistoryService.class);
        ProductApi.ProductCatalogResponse expected = new ProductApi.ProductCatalogResponse("search", "milk", java.util.List.of());
        when(actorContexts.require("opaque-actor", "receipt.read")).thenReturn(actor);
        when(products.catalog(tenantId, userId, "milk")).thenReturn(expected);
        when(transaction.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        TelegramActionService service = new TelegramActionService(actorContexts, transactions, drafts, debts, budgets,
                reports, transaction, products);

        assertEquals(expected, service.productCatalog(new ProductCatalogRequest("opaque-actor", "milk")));
        verify(actorContexts).require("opaque-actor", "receipt.read");
        verify(products).catalog(tenantId, userId, "milk");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void productCatalogDoesNotReachAnalyticsWhenActorCannotReadReceipts() {
        TelegramActorContextService actorContexts = mock(TelegramActorContextService.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        ProductPriceHistoryService products = mock(ProductPriceHistoryService.class);
        when(actorContexts.require("viewer-context", "receipt.read"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        when(transaction.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        TelegramActionService service = new TelegramActionService(actorContexts, mock(TransactionService.class),
                mock(TransactionDraftService.class), mock(DebtService.class), mock(BudgetService.class),
                mock(ReportService.class), transaction, products);

        assertThrows(ResponseStatusException.class,
                () -> service.productCatalog(new ProductCatalogRequest("viewer-context", "milk")));
        verify(products, never()).catalog(any(UUID.class), any(UUID.class), any());
    }
}
