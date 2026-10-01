package com.decorix.finance.core.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=${FINANCE_TEST_JDBC_URL:jdbc:postgresql://localhost:5432/finance_test}",
        "spring.datasource.username=${FINANCE_TEST_DB_USER:finance_test}",
        "spring.datasource.password=${FINANCE_TEST_DB_PASSWORD:finance_test}",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FINANCE_TEST_DATABASE_URL", matches = ".+")
class TransactionApiPostgresTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private UUID tenantId;
    private String subject;

    @BeforeEach
    void createTenantAndMembership() {
        subject = "keycloak|integration-" + UUID.randomUUID();
        tenantId = transactions.execute(status -> {
            UUID id = jdbc.queryForObject(
                    "INSERT INTO tenants (display_name) VALUES ('API test') RETURNING id", UUID.class);
            UUID userId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)", userId, subject);
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, id.toString());
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

        UUID transactionId = UUID.fromString(id);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM transactions WHERE tenant_id = ? AND id = ?", Integer.class, tenantId, transactionId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND entity_id = ?", Integer.class, tenantId, transactionId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND aggregate_id = ?", Integer.class, tenantId, transactionId)).isEqualTo(1);
    }
}
