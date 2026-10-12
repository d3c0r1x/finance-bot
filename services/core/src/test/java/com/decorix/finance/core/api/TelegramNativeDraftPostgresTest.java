package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "FINANCE_TEST_DATABASE_URL", matches = ".+")
class TelegramNativeDraftPostgresTest {
    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("FINANCE_TEST_JDBC_URL"), System.getenv("FINANCE_TEST_APP_USER"),
            System.getenv("FINANCE_TEST_APP_PASSWORD"));
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(System.getenv("FINANCE_TEST_ADMIN_JDBC_URL"),
                System.getenv("FINANCE_TEST_MIGRATOR_USER"), System.getenv("FINANCE_TEST_MIGRATOR_PASSWORD"))
                .load().migrate();
    }

    @Test
    void nativeUserCanReviewEditAndCancelOwnDraftButCannotReadAnotherUsersDraft() {
        long telegramId = 1000000000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1000000000L);
        var actors = new TelegramActorContextService(jdbc, tx, new MemberProfileService(jdbc, tx),
                new TelegramAccessPolicy(Long.toString(telegramId), ""));
        UUID tenantId = actors.listTenants(telegramId, "Synthetic friend").tenants().get(0).tenantId();
        String token = actors.issue(new TelegramActorContextApi.IssueRequest(telegramId, tenantId)).token();
        UUID userId = actors.require(token, "transaction.write.own").userId();
        var advisor = mock(TransactionDraftAdvisor.class);
        when(advisor.advise(any())).thenReturn(new TransactionDraftAdvisor.Advice("expense", "100.00", "food",
                null, "Synthetic expense", Instant.now().toString(), "test", "test", "test"));
        var drafts = new TransactionDraftService(jdbc, tx, new MemberProfileService(jdbc, tx), advisor,
                mock(TransactionService.class), mock(DebtService.class));

        String key = "synthetic-native-draft-" + UUID.randomUUID();
        var draft = drafts.createForUser(tenantId, userId, key, new TransactionDraftApi.CreateRequest("Lunch"));
        assertEquals(draft.id(), drafts.createForUser(tenantId, userId, key,
                new TransactionDraftApi.CreateRequest("Lunch")).id());
        assertEquals(draft.id(), drafts.getForUser(tenantId, userId, draft.id()).id());
        assertEquals("150.00", drafts.updateAmountForUser(tenantId, userId, draft.id(), draft.version(), "150.00").amount());
        var edited = drafts.updateForUser(tenantId, userId, draft.id(), 2,
                new TransactionDraftApi.UpdateRequest("expense", "155.00", "RUB", "food", null,
                        "Edited", Instant.now(), null));
        assertEquals("Edited", edited.description());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> drafts.getForUser(tenantId, UUID.randomUUID(), draft.id())).getStatusCode());
        drafts.cancelForUser(tenantId, userId, draft.id(), edited.version());
        assertEquals("cancelled", drafts.getForUser(tenantId, userId, draft.id()).state());
    }
}
