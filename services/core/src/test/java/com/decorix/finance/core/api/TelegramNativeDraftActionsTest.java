package com.decorix.finance.core.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.decorix.finance.core.api.TelegramActorContextApi.ActorContext;
import com.decorix.finance.core.api.TelegramActorContextApi.CreateDraftRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftAmountRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftCancelRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.DraftEditRequest;
import com.decorix.finance.core.api.TelegramActorContextApi.ResolveRequest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class TelegramNativeDraftActionsTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void nativeActorUsesCanonicalUserForDraftLifecycle() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        ActorContext actor = new ActorContext(42L, tenantId, userId, null, "owner",
                Set.of("transaction.write.own"), Instant.now().plusSeconds(300));
        TelegramActorContextService contexts = mock(TelegramActorContextService.class);
        TransactionDraftService drafts = mock(TransactionDraftService.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(contexts.require("opaque", "transaction.write.own")).thenReturn(actor);
        when(tx.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        TelegramActionService actions = new TelegramActionService(contexts, mock(TransactionService.class),
                drafts, mock(DebtService.class), mock(BudgetService.class), mock(ReportService.class),
                tx, mock(ProductPriceHistoryService.class));

        actions.createDraft(new CreateDraftRequest("opaque", "tg-draft-key-123456", "кофе 120"));
        actions.getDraft(draftId, new ResolveRequest("opaque"));
        actions.updateDraftAmount(draftId, new DraftAmountRequest("opaque", 1L, "120.00"));
        actions.editDraft(draftId, new DraftEditRequest("opaque", 1L, "expense", "120.00", "RUB",
                "еда", null, "Кофе", Instant.now(), null));
        actions.cancelDraft(draftId, new DraftCancelRequest("opaque", 1L));

        verify(drafts).createForUser(tenantId, userId, "tg-draft-key-123456", new TransactionDraftApi.CreateRequest("кофе 120"));
        verify(drafts).getForUser(tenantId, userId, draftId);
        verify(drafts).updateAmountForUser(tenantId, userId, draftId, 1L, "120.00");
        verify(drafts).updateForUser(org.mockito.ArgumentMatchers.eq(tenantId), org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(draftId), org.mockito.ArgumentMatchers.eq(1L), any());
        verify(drafts).cancelForUser(tenantId, userId, draftId, 1L);
    }
}
