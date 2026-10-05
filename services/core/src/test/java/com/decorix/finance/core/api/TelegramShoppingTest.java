package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.decorix.finance.core.api.TelegramActorContextApi.ActorContext;
import com.decorix.finance.core.api.TelegramActorContextApi.ResolveRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class TelegramShoppingTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shoppingRequiresReceiptReadAndUsesOnlyResolvedActorIds() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ActorContext actor = new ActorContext(42L, tenantId, userId, "keycloak-subject", "member",
                Set.of("receipt.read"), Instant.now().plusSeconds(300));
        TelegramActorContextService actorContexts = mock(TelegramActorContextService.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        ProductPriceHistoryService products = mock(ProductPriceHistoryService.class);
        ProductApi.ShoppingList expected = new ProductApi.ShoppingList(List.of(new ProductApi.ShoppingCandidate(
                "Milk Fresh 1l", 3, 10, "100.000000", "100.00", Instant.parse("2026-10-04T00:00:00Z"),
                Instant.parse("2026-10-05T00:00:00Z"), 0)), "100.00", false);
        when(actorContexts.require("opaque-actor", "receipt.read")).thenReturn(actor);
        when(products.shopping(tenantId, userId)).thenReturn(expected);
        when(transaction.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        TelegramActionService service = service(actorContexts, transaction, products);

        assertEquals(expected, service.shopping(new ResolveRequest("opaque-actor")));
        verify(actorContexts).require("opaque-actor", "receipt.read");
        verify(products).shopping(tenantId, userId);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shoppingDoesNotReachAnalyticsWithoutReceiptReadPermission() {
        TelegramActorContextService actorContexts = mock(TelegramActorContextService.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        ProductPriceHistoryService products = mock(ProductPriceHistoryService.class);
        when(actorContexts.require("viewer-context", "receipt.read"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        when(transaction.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        assertThrows(ResponseStatusException.class,
                () -> service(actorContexts, transaction, products).shopping(new ResolveRequest("viewer-context")));
        verify(products, never()).shopping(any(UUID.class), any(UUID.class));
    }

    private static TelegramActionService service(TelegramActorContextService actorContexts,
            TransactionTemplate transaction, ProductPriceHistoryService products) {
        return new TelegramActionService(actorContexts, mock(TransactionService.class),
                mock(TransactionDraftService.class), mock(DebtService.class), mock(BudgetService.class),
                mock(ReportService.class), transaction, products);
    }
}
