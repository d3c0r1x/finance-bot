package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "FINANCE_TEST_DATABASE_URL", matches = ".+")
class TelegramNativeIdentityPostgresTest {
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
        // Fixed synthetic role matches the existing Core PostgreSQL test harness.
        admin.execute("GRANT USAGE ON SCHEMA public TO finance_app_test");
        admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO finance_app_test");
        admin.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO finance_app_test");
    }

    @Test
    void nativeTelegramProvisionsExactlyOneCanonicalUserAndTenantWithoutKeycloak() {
        long id = syntheticId();
        TelegramActorContextService service = service(new TelegramAccessPolicy(Long.toString(id), ""));
        var first = service.listTenants(id, "Synthetic friend");
        var second = service.listTenants(id, "Synthetic friend");
        assertEquals(1, first.tenants().size());
        assertEquals(first, second);
        UUID user = jdbc.queryForObject("SELECT user_id FROM external_identities WHERE provider='telegram' AND subject=?",
                UUID.class, Long.toString(id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM external_identities WHERE user_id=? AND provider='keycloak'", Integer.class, user));
        var context = service.issue(new TelegramActorContextApi.IssueRequest(id, first.tenants().get(0).tenantId()));
        assertEquals(user, service.require(context.token(), null).userId());
        assertThrows(ResponseStatusException.class, () -> service.issue(new TelegramActorContextApi.IssueRequest(id, UUID.randomUUID())));
        jdbc.update("UPDATE users SET status='disabled' WHERE id=?", user);
        assertThrows(ResponseStatusException.class, () -> service.require(context.token(), null));
        assertThrows(ResponseStatusException.class, () -> service.listTenants(id, "Synthetic friend"));
    }

    private TelegramActorContextService service(TelegramAccessPolicy policy) {
        return new TelegramActorContextService(jdbc, tx, new MemberProfileService(jdbc, tx), policy);
    }

    @Test
    void simultaneousNativeStartsUseOneIdentityAndWhitelistRevocationDeniesOldContext() throws Exception {
        long id = syntheticId();
        TelegramActorContextService service = service(new TelegramAccessPolicy(Long.toString(id), ""));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.listTenants(id, "Synthetic concurrent"));
            var second = executor.submit(() -> service.listTenants(id, "Synthetic concurrent"));
            assertEquals(first.get(), second.get());
            var tenant = first.get().tenants().get(0).tenantId();
            var context = service.issue(new TelegramActorContextApi.IssueRequest(id, tenant));
            assertThrows(ResponseStatusException.class,
                    () -> service(new TelegramAccessPolicy("", "")).require(context.token(), null));
            jdbc.update("DELETE FROM external_identities WHERE provider='telegram' AND subject=?", Long.toString(id));
            assertThrows(ResponseStatusException.class, () -> service.require(context.token(), null));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void verifiedAndroidIdentityAttachesToExistingTelegramProfileWithoutChangingItsTenant() {
        long id = syntheticId();
        String subject = "synthetic-android-" + UUID.randomUUID();
        TelegramAccessPolicy policy = new TelegramAccessPolicy(Long.toString(id), id + "=" + subject);
        UUID tenantId = service(policy).listTenants(id, "Synthetic friend").tenants().get(0).tenantId();
        UUID telegramUser = jdbc.queryForObject(
                "SELECT user_id FROM external_identities WHERE provider='telegram' AND subject=?",
                UUID.class, Long.toString(id));
        TenantService tenants = new TenantService(jdbc, tx, policy);
        assertThrows(ResponseStatusException.class, () -> tenants.list(subject, false));
        assertEquals(tenantId, tenants.list(subject, true).get(0).tenantId());
        assertEquals(tenantId, tenants.list(subject, true).get(0).tenantId());
        assertEquals(telegramUser, jdbc.queryForObject(
                "SELECT user_id FROM external_identities WHERE provider='keycloak' AND subject=?",
                UUID.class, subject));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, telegramUser));
        assertEquals("Synthetic friend", new MemberProfileService(jdbc, tx).get(tenantId, subject).displayName());
    }

    @Test
    void changedConfiguredAndroidSubjectRevokesAnExistingTelegramContext() {
        long id = syntheticId();
        String originalSubject = "synthetic-android-" + UUID.randomUUID();
        TelegramAccessPolicy originalPolicy = new TelegramAccessPolicy(Long.toString(id), id + "=" + originalSubject);
        TelegramActorContextService original = service(originalPolicy);
        UUID tenantId = original.listTenants(id, "Synthetic friend").tenants().get(0).tenantId();
        new TenantService(jdbc, tx, originalPolicy).list(originalSubject, true);
        var context = original.issue(new TelegramActorContextApi.IssueRequest(id, tenantId));
        assertEquals(originalSubject, original.require(context.token(), null).keycloakSubject());

        String replacementSubject = "synthetic-android-" + UUID.randomUUID();
        TelegramActorContextService changed = service(new TelegramAccessPolicy(
                Long.toString(id), id + "=" + replacementSubject));
        assertThrows(ResponseStatusException.class, () -> changed.require(context.token(), null));
    }

    @Test
    void disabledConfiguredAndroidAccountDoesNotCreateASecondTelegramProfile() {
        long id = syntheticId();
        String subject = "synthetic-disabled-android-" + UUID.randomUUID();
        UUID userId = new TenantService(jdbc, tx, new TelegramAccessPolicy("", ""))
                .create(subject, new TenantApi.CreateTenantRequest("Synthetic", "Europe/Moscow", "Friend", null))
                .userId();
        assertEquals(1, jdbc.update("UPDATE users SET status='disabled' WHERE id=?", userId));

        TelegramAccessPolicy mapped = new TelegramAccessPolicy(Long.toString(id), id + "=" + subject);
        assertThrows(ResponseStatusException.class,
                () -> service(mapped).listTenants(id, "Synthetic friend"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM external_identities WHERE provider='telegram' AND subject=?",
                Integer.class, Long.toString(id)));
    }

    @Test
    void nativeTelegramReadsOnlyItsOwnRecentTransactionsByCanonicalUserId() {
        long id = syntheticId();
        TelegramActorContextService contexts = service(new TelegramAccessPolicy(Long.toString(id), ""));
        UUID tenantId = contexts.listTenants(id, "Synthetic friend").tenants().get(0).tenantId();
        String token = contexts.issue(new TelegramActorContextApi.IssueRequest(id, tenantId)).token();
        UUID userId = contexts.require(token, "transaction.read").userId();
        UUID transactionId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("""
                    INSERT INTO transactions
                      (id, tenant_id, owner_subject, owner_user_id, type, amount, currency,
                       category_code, source, occurred_at)
                    VALUES (?, ?, ?, ?, 'expense', 123.45, 'RUB', 'food', 'telegram', now())
                    """, transactionId, tenantId, "telegram:" + id, userId);
        });
        TransactionService service = new TransactionService(jdbc, tx, null, null, null, null);
        assertEquals(transactionId, service.recent(tenantId, userId, 8).get(0).id());
        assertThrows(ResponseStatusException.class,
                () -> service.recent(tenantId, UUID.randomUUID(), 8));
    }

    private static long syntheticId() {
        return 1000000000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1000000000L);
    }
}
