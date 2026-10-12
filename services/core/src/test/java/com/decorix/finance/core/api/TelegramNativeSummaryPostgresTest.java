package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "FINANCE_TEST_DATABASE_URL", matches = ".+")
class TelegramNativeSummaryPostgresTest {
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
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(System.getenv("FINANCE_TEST_ADMIN_JDBC_URL"),
                System.getenv("FINANCE_TEST_DB_USER"), System.getenv("FINANCE_TEST_DB_PASSWORD")));
        admin.execute("GRANT USAGE ON SCHEMA public TO finance_app_test");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO finance_app_test");
        admin.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO finance_app_test");
    }

    @Test
    void nativeTelegramDashboardUsesCanonicalUserWithoutKeycloakIdentity() {
        long id = 1000000000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1000000000L);
        TelegramActorContextService contexts = new TelegramActorContextService(jdbc, tx,
                new MemberProfileService(jdbc, tx), new TelegramAccessPolicy(Long.toString(id), ""));
        UUID tenantId = contexts.listTenants(id, "Synthetic friend").tenants().get(0).tenantId();
        String token = contexts.issue(new TelegramActorContextApi.IssueRequest(id, tenantId)).token();
        UUID userId = contexts.require(token, "report.read").userId();
        assertNull(contexts.require(token, "report.read").keycloakSubject());
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("""
                    INSERT INTO transactions
                      (id, tenant_id, owner_subject, owner_user_id, type, amount, currency,
                       category_code, source, occurred_at)
                    VALUES (?, ?, ?, ?, 'expense', 123.45, 'RUB', 'food', 'telegram', now())
                    """, UUID.randomUUID(), tenantId, "telegram:" + id, userId);
        });
        BudgetService budgets = new BudgetService(jdbc, tx, null, null);
        TransactionService transactions = new TransactionService(jdbc, tx, null, null, budgets,
                new CashPlanningService(jdbc));
        TelegramActionService actions = new TelegramActionService(contexts, transactions, null, null,
                budgets, null, tx, null);
        var summary = actions.dashboardSummary(token);
        assertEquals("123.45", summary.expenseTotal());
        assertEquals(1, summary.transactionCount());
        assertNotNull(actions.budgetOverview(new TelegramActorContextApi.ResolveRequest(token)));
        assertThrows(ResponseStatusException.class,
                () -> transactions.summary(tenantId, UUID.randomUUID(), null));
        assertThrows(ResponseStatusException.class,
                () -> budgets.get(tenantId, UUID.randomUUID()));
    }
}
