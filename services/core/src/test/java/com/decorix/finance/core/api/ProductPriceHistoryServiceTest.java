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

import com.decorix.finance.core.api.InflationApi.PersonalInflation;
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
    void personalInflationResolvesOnlyAuthenticatedActiveMemberBeforeAnalyticsCall() {
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
        var expected = new PersonalInflation(false, "insufficient_history", java.time.Instant.parse("2026-10-06T12:00:00Z"),
                90, 0, null, null, null, List.of(), List.of());
        when(analytics.personalInflation(eq(tenantId), eq(ownerId), any())).thenReturn(expected);

        var service = new ProductPriceHistoryService(jdbc, transaction, analytics);

        assertEquals(expected, service.personalInflation(tenantId, subject));
        verify(analytics).personalInflation(eq(tenantId), eq(ownerId), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void personalInflationDoesNotQueryAnalyticsForInactiveMember() {
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
                () -> service.personalInflation(tenantId, subject));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(analytics, never()).personalInflation(any(UUID.class), any(UUID.class), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shoppingCandidatesResolveOnlyAuthenticatedActiveMemberBeforeAnalyticsCall() {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String subject = "keycloak-subject";
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        ProductPriceHistoryClient analytics = mock(ProductPriceHistoryClient.class);
        ShoppingDecisionService decisions = mock(ShoppingDecisionService.class);
        when(transaction.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(jdbc.queryForObject(eq("SELECT set_config('app.tenant_id', ?, true)"), eq(String.class), eq(tenantId.toString())))
                .thenReturn(tenantId.toString());
        doAnswer(invocation -> List.of(ownerId)).when(jdbc).query(anyString(), any(RowMapper.class),
                eq(tenantId), eq(subject));
        var expected = new ProductApi.ShoppingList(List.of(new ProductApi.ShoppingCandidate(
                "Milk Fresh 1l", 3, 10, "100.000000", "100.00", java.time.Instant.parse("2026-10-04T00:00:00Z"),
                java.time.Instant.parse("2026-10-05T00:00:00Z"), 0)), "100.00", false);
        when(analytics.shopping(tenantId.toString(), ownerId.toString())).thenReturn(expected);
        when(decisions.apply(tenantId, ownerId, expected)).thenReturn(expected);

        var service = new ProductPriceHistoryService(jdbc, transaction, analytics, decisions);

        assertEquals(expected, service.shopping(tenantId, subject));
        verify(analytics).shopping(tenantId.toString(), ownerId.toString());
        verify(decisions).apply(tenantId, ownerId, expected);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shoppingCandidatesDoNotQueryAnalyticsForInactiveMember() {
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
        var service = new ProductPriceHistoryService(jdbc, transaction, analytics);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.shopping(tenantId, subject));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(analytics, never()).shopping(anyString(), anyString());
    }

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
