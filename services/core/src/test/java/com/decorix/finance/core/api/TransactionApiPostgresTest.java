package com.decorix.finance.core.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=${FINANCE_TEST_JDBC_URL:jdbc:postgresql://localhost:5432/finance_test}",
        "spring.datasource.username=${FINANCE_TEST_APP_USER:finance_app_test}",
        "spring.datasource.password=${FINANCE_TEST_APP_PASSWORD:finance_app_test}",
        "spring.flyway.url=${FINANCE_TEST_ADMIN_JDBC_URL:jdbc:postgresql://localhost:5432/finance_test}",
        "spring.flyway.user=${FINANCE_TEST_MIGRATOR_USER:finance_migrator_test}",
        "spring.flyway.password=${FINANCE_TEST_MIGRATOR_PASSWORD:finance_migrator_test}",
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FINANCE_TEST_DATABASE_URL", matches = ".+")
class TransactionApiPostgresTest {
    private static final String KEY_ID = "finance-test-key";
    private static final KeyPair SIGNING_KEY = createSigningKey();
    private static final RSAKey PUBLIC_JWK = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) SIGNING_KEY.getPublic())
            .keyID(KEY_ID).algorithm(JWSAlgorithm.RS256).build();
    private static final HttpServer JWKS_SERVER = startJwksServer();
    private static final String ISSUER = "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/realms/finance";
    private static final String JDBC_URL = System.getenv("FINANCE_TEST_JDBC_URL");

    static {
        if (JDBC_URL != null && !JDBC_URL.isBlank()) {
            ensureAppRole();
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private UUID tenantId;
    private String subject;

    void grantAppPrivileges() {
        try (var connection = adminConnection(); var statement = connection.createStatement()) {
            statement.execute("GRANT USAGE ON SCHEMA public TO finance_app_test");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO finance_app_test");
            statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO finance_app_test");
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot prepare least-privilege test role", exception);
        }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER + "/jwks");
    }

    @BeforeEach
    void createTenantAndMembership() {
        grantAppPrivileges();
        subject = "keycloak|integration-" + UUID.randomUUID();
        tenantId = transactions.execute(status -> {
            UUID id = UUID.randomUUID();
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, id.toString());
            jdbc.update("INSERT INTO tenants (id, display_name) VALUES (?, 'API test')", id);
            UUID userId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)", userId, subject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'owner', ?)", id, subject, userId);
            return id;
        });
    }

    @Test
    void createIsAtomicAndIdempotentAndRequiresTenantMembership() throws Exception {
        String body = """
                {"type":"expense","amount":"12.34","currency":"RUB","categoryCode":"food","description":"Lunch","occurredAt":"2026-10-01T10:00:00Z"}
                """;
        var auth = jwt().jwt(token -> token.subject(subject));
        var path = "/api/v1/tenants/" + tenantId + "/transactions";

        var created = mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "transaction-request-0001")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("12.34"))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "transaction-request-0001")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id));

        mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "transaction-request-0001")
                        .contentType("application/json").content(body.replace("12.34", "99.00")))
                .andExpect(status().isConflict());

        mvc.perform(post(path).with(jwt().jwt(token -> token.subject("keycloak|unrelated-user")))
                        .header("Idempotency-Key", "transaction-request-0002")
                        .contentType("application/json").content(body))
                .andExpect(status().isNotFound());

        mvc.perform(get(path).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id));

        mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "transaction-request-0003")
                        .contentType("application/json").content(body.replace("12.34", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mvc.perform(get(path))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(path).header("Authorization", bearer(subject, ISSUER, "finance-api", Instant.now().plusSeconds(120))))
                .andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", bearer(subject, "https://attacker.invalid", "finance-api", Instant.now().plusSeconds(120))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", bearer(subject, ISSUER, "another-api", Instant.now().plusSeconds(120))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", bearer(subject, ISSUER, "finance-api", Instant.now().minusSeconds(30))))
                .andExpect(status().isUnauthorized());

        String older = """
                {"type":"expense","amount":"4.00","currency":"RUB","categoryCode":"food","description":"Yesterday","occurredAt":"2026-09-30T10:00:00Z"}
                """;
        mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "transaction-request-0004")
                        .contentType("application/json").content(older))
                .andExpect(status().isCreated());
        var firstPage = mvc.perform(get(path).with(auth).param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn();
        String cursor = com.jayway.jsonpath.JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");
        mvc.perform(get(path).with(auth).param("pageSize", "1").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].description").value("Yesterday"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        var itemPath = path + "/" + id;
        mvc.perform(get(itemPath).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("posted"));
        var voidPath = itemPath + "/void";
        mvc.perform(post(voidPath).with(auth)
                        .header("Idempotency-Key", "transaction-request-0005")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("voided"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(post(voidPath).with(auth)
                        .header("Idempotency-Key", "transaction-request-0005")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(post(voidPath).with(auth)
                        .header("Idempotency-Key", "transaction-request-0006")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(get(itemPath).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("voided"));

        UUID transactionId = UUID.fromString(id);
        org.assertj.core.api.Assertions.assertThat(countTenantRows("transactions", "id", transactionId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(countTenantRows("audit_log", "entity_id", transactionId)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(countTenantRows("outbox_events", "aggregate_id", transactionId)).isEqualTo(2);
    }

    private int countTenantRows(String table, String column, UUID transactionId) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND " + column + " = ?",
                    Integer.class, tenantId, transactionId);
        });
    }

    private static void ensureAppRole() {
        try (var connection = adminConnection(); var statement = connection.createStatement()) {
            ensureRole(statement, "finance_app_test", "finance_app_test");
            ensureRole(statement, "finance_migrator_test", "finance_migrator_test");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO finance_migrator_test");
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void ensureRole(java.sql.Statement statement, String roleName, String password) throws SQLException {
        var role = statement.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + roleName + "'");
        if (!role.next()) {
            statement.execute("CREATE ROLE " + roleName + " LOGIN PASSWORD '" + password + "' NOSUPERUSER NOBYPASSRLS NOINHERIT");
        }
        var flags = statement.executeQuery("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = '" + roleName + "'");
        if (!flags.next() || flags.getBoolean(1) || flags.getBoolean(2)) {
            throw new IllegalStateException(roleName + " must be non-superuser and NOBYPASSRLS");
        }
    }

    private static java.sql.Connection adminConnection() throws SQLException {
        String url = System.getenv().getOrDefault("FINANCE_TEST_ADMIN_JDBC_URL", JDBC_URL == null
                ? "jdbc:postgresql://localhost:5432/finance_test" : JDBC_URL);
        String user = System.getenv().getOrDefault("FINANCE_TEST_DB_USER", "finance_test");
        String password = System.getenv().getOrDefault("FINANCE_TEST_DB_PASSWORD", "finance_test");
        return DriverManager.getConnection(url, user, password);
    }

    private static KeyPair createSigningKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static HttpServer startJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] jwks = new JWKSet(PUBLIC_JWK).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/realms/finance/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String bearer(String subject, String issuer, String audience, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(KEY_ID).build(), claims);
        token.sign(new RSASSASigner(SIGNING_KEY.getPrivate()));
        return "Bearer " + token.serialize();
    }
}
