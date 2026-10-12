package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.YearMonth;
import java.time.ZoneId;
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
class TelegramNativeReportPostgresTest {
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
    void nativeTelegramReportUsesCanonicalUserWithoutKeycloakSubject() {
        long id = 1000000000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1000000000L);
        TelegramActorContextService contexts = new TelegramActorContextService(jdbc, tx,
                new MemberProfileService(jdbc, tx), new TelegramAccessPolicy(Long.toString(id), ""));
        UUID tenantId = contexts.listTenants(id, "Synthetic friend").tenants().get(0).tenantId();
        String token = contexts.issue(new TelegramActorContextApi.IssueRequest(id, tenantId)).token();
        var actor = contexts.require(token, "report.read");
        assertNull(actor.keycloakSubject());
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("""
                    INSERT INTO transactions
                      (id, tenant_id, owner_subject, owner_user_id, type, amount, currency,
                       category_code, source, occurred_at)
                    VALUES (?, ?, ?, ?, 'expense', 123.45, 'RUB', 'food', 'telegram', now())
                    """, UUID.randomUUID(), tenantId, "telegram:" + id, actor.userId());
        });
        ReportService reports = new ReportService(jdbc, tx, new BudgetService(jdbc, tx, null, null),
                mock(AdviceWasteReportService.class));
        YearMonth month = YearMonth.now(ZoneId.of("Europe/Moscow"));
        var report = reports.get(tenantId, actor.userId(), "month", month, null, null, false);
        assertEquals("123.45", report.expenseTotal());
        assertEquals(1, report.transactionCount());
        assertThrows(ResponseStatusException.class,
                () -> reports.get(tenantId, UUID.randomUUID(), "month", month, null, null, false));

        String androidSubject = "synthetic-android-" + UUID.randomUUID();
        TelegramAccessPolicy linkedPolicy = new TelegramAccessPolicy(Long.toString(id), id + "=" + androidSubject);
        new TenantService(jdbc, tx, linkedPolicy).list(androidSubject, true);
        assertEquals(actor.userId(), jdbc.queryForObject(
                "SELECT user_id FROM external_identities WHERE provider='keycloak' AND subject=?",
                UUID.class, androidSubject));
        assertEquals("123.45", reports.get(tenantId, actor.userId(), "month", month, null, null, false)
                .expenseTotal());
    }
}
