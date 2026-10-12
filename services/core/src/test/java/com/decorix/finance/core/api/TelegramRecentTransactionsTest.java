package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.decorix.finance.core.api.TelegramActorContextApi.ActorContext;
import com.decorix.finance.core.api.TelegramActorContextApi.HistoryRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class TelegramRecentTransactionsTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void nativeTelegramHistoryUsesCanonicalUserWithoutKeycloakSubject() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ActorContext actor = new ActorContext(42L, tenantId, userId, null, "owner",
                Set.of("transaction.read"), Instant.now().plusSeconds(300));
        TelegramActorContextService contexts = mock(TelegramActorContextService.class);
        TransactionService transactions = mock(TransactionService.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(contexts.require("opaque-token", "transaction.read")).thenReturn(actor);
        when(transactions.recent(tenantId, userId, 8)).thenReturn(List.of());
        when(tx.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        TelegramActionService service = new TelegramActionService(contexts, transactions,
                mock(TransactionDraftService.class), mock(DebtService.class), mock(BudgetService.class),
                mock(ReportService.class), tx, mock(ProductPriceHistoryService.class));

        assertEquals(List.of(), service.listRecentTransactions(new HistoryRequest("opaque-token", null)));
        verify(transactions).recent(tenantId, userId, 8);
    }
}
