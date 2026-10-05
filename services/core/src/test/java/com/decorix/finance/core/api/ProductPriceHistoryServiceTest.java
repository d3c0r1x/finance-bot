package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ProductPriceHistoryServiceTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void catalogResolvesOnlyAuthenticatedActiveMemberBeforeAnalyticsCall() {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String subject = "keycloak-subject";
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        ProductPriceHistoryClient analytics = mock(ProductPriceHistoryClient.class);
        when(transaction.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(jdbc.queryForObject(eq("SELECT set_config('app.tenant_id', ?, true)"), eq(String.class), eq(tenantId.toString())))
                .thenReturn(tenantId.toString());
        doAnswer(invocation -> List.of(ownerId)).when(jdbc).query(anyString(), any(RowMapper.class),
                eq(tenantId), eq(subject));
        ProductApi.ProductCatalogResponse expected = new ProductApi.ProductCatalogResponse("search", "tea", List.of());
        when(analytics.catalog(tenantId.toString(), ownerId.toString(), "tea")).thenReturn(expected);

        ProductPriceHistoryService service = new ProductPriceHistoryService(jdbc, transaction, analytics);
        assertEquals(expected, service.catalog(tenantId, subject, "tea"));
        verify(analytics).catalog(tenantId.toString(), ownerId.toString(), "tea");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void catalogDoesNotQueryAnalyticsForInactiveOrNonMemberIdentity() {
        UUID tenantId = UUID.randomUUID();
        String subject = "inactive-subject";
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        ProductPriceHistoryClient analytics = mock(ProductPriceHistoryClient.class);
        when(transaction.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(jdbc.queryForObject(eq("SELECT set_config('app.tenant_id', ?, true)"), eq(String.class), eq(tenantId.toString())))
                .thenReturn(tenantId.toString());
        doAnswer(invocation -> List.of()).when(jdbc).query(anyString(), any(RowMapper.class), eq(tenantId), eq(subject));
        ProductPriceHistoryService service = new ProductPriceHistoryService(jdbc, transaction, analytics);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.catalog(tenantId, subject, "tea"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(analytics, never()).catalog(anyString(), anyString(), anyString());
    }
}
