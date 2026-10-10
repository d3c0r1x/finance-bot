package com.decorix.finance.core.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.argThat;

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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import com.decorix.finance.core.api.AdviceRecalculationImpactApi.Impact;
import com.decorix.finance.core.api.GoalCandidatesApi;

@SpringBootTest(properties = {
        "spring.datasource.url=${FINANCE_TEST_JDBC_URL:jdbc:postgresql://localhost:5432/finance_test}",
        "spring.datasource.username=${FINANCE_TEST_APP_USER:finance_app_test}",
        "spring.datasource.password=${FINANCE_TEST_APP_PASSWORD:finance_app_test}",
        "spring.flyway.url=${FINANCE_TEST_ADMIN_JDBC_URL:jdbc:postgresql://localhost:5432/finance_test}",
        "spring.flyway.user=${FINANCE_TEST_MIGRATOR_USER:finance_migrator_test}",
        "spring.flyway.password=${FINANCE_TEST_MIGRATOR_PASSWORD:finance_migrator_test}",
        "finance.receipts.worker.enabled=false",
        "finance.receipts.storage.filesystem-root=build/test-receipt-storage",
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
    private static final String AI_SERVICE_TOKEN = "integration-ai-service-token";
    private static final String TELEGRAM_SERVICE_TOKEN = "integration-telegram-service-token";
    private static final String MIGRATION_SERVICE_TOKEN = "integration-migration-service-token";
    private static final String EXPORT_SERVICE_TOKEN = "integration-export-service-token";
    private static final AtomicInteger AI_CALLS = new AtomicInteger();
    private static final AtomicReference<String> LAST_AI_REQUEST = new AtomicReference<>("");
    private static final AtomicReference<String> AI_DRAFT_RESPONSE = new AtomicReference<>("""
            {"provider":"test-ollama","type":"expense","amount":"2000.00","categoryCode":"transport","subcategoryCode":null,"description":"Такси","occurredAt":"2026-10-01T09:00:00+03:00","modelVersion":"test-model-1","promptVersion":"transaction-draft.v1"}
            """);
    private static final AtomicReference<String> AI_BASKET_RESPONSE = new AtomicReference<>("""
            {"provider":"test-ollama","items":[{"ordinal":1,"verdict":"useful","reason":"model reason","action":"model action"}],
             "modelVersion":"test-model-1","promptVersion":"receipt-basket.v1","taskKind":"receipt-basket-review",
             "inputSchemaVersion":"receipt-basket-context.v1","outputSchemaVersion":"receipt-basket-review.v1"}
            """);
    private static final AtomicReference<String> LAST_BASKET_REQUEST = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_MERCHANT_REQUEST = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_PRICE_COMPARE_REQUEST = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_PRICE_CATALOG_REQUEST = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_SHOPPING_REQUEST = new AtomicReference<>("");
    private static final String DEFAULT_SHOPPING_RESPONSE = """
            {"candidates":[{"productName":"Milk Fresh 1l","purchaseCount":3,"medianIntervalDays":10,
             "usualUnitPrice":"100.000000","estimatedCost":"100.00",
             "lastPurchasedAt":"2026-10-04T00:00:00Z","dueAt":"2026-10-05T00:00:00Z","daysUntilDue":0}],
             "estimatedListCost":"100.00","inventoryTracked":false}
            """;
    private static final AtomicReference<String> SHOPPING_RESPONSE = new AtomicReference<>(DEFAULT_SHOPPING_RESPONSE);
    private static final AtomicReference<String> LAST_PERSONAL_INFLATION_REQUEST = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_RECURRING_REQUEST = new AtomicReference<>("");
    private static final AtomicReference<String> LAST_WASTE_REQUEST = new AtomicReference<>("");
    private static final AtomicInteger WASTE_RESPONSE_STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> LAST_EVIDENCE_REQUEST = new AtomicReference<>("");
    private static final AtomicInteger EVIDENCE_RESPONSE_STATUS = new AtomicInteger(200);
    private static final AtomicBoolean RECURRING_FIXTURE_ENABLED = new AtomicBoolean();
    private static final String RECURRING_SERIES_ID = "abcdef0123456789abcdef0123456789";
    private static final String ANALYTICS_SERVICE_TOKEN = "integration-analytics-price-token";
    private static final HttpServer AI_SERVER = startAiServer();
    private static final String JDBC_URL = System.getenv("FINANCE_TEST_JDBC_URL");

    static {
        if (JDBC_URL != null && !JDBC_URL.isBlank()) {
            ensureAppRole();
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private ReceiptProcessingService receiptProcessing;
    @Autowired private ReceiptObjectStorage receiptStorage;
    @MockitoBean private ReceiptMalwareScanner receiptMalwareScanner;
    @MockitoBean private ReceiptOcrClient receiptOcrClient;
    @MockitoBean private ReceiptVisionClient receiptVisionClient;
    @MockitoBean private AdviceRecalculationImpactClient recalculationImpactClient;
    @MockitoBean private GoalCandidatesClient goalCandidatesClient;
    @MockitoBean private GoalProgressClient goalProgressClient;
    @MockitoBean private ExportDownloadSigner exportDownloadSigner;

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
        properties.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", () -> ISSUER);
        properties.add("spring.security.oauth2.client.provider.keycloak.authorization-uri",
                () -> ISSUER + "/protocol/openid-connect/auth");
        properties.add("spring.security.oauth2.client.provider.keycloak.token-uri",
                () -> ISSUER + "/protocol/openid-connect/token");
        properties.add("spring.security.oauth2.client.provider.keycloak.user-info-uri",
                () -> ISSUER + "/protocol/openid-connect/userinfo");
        properties.add("spring.security.oauth2.client.provider.keycloak.jwk-set-uri", () -> ISSUER + "/jwks");
        properties.add("spring.security.oauth2.client.provider.keycloak.user-name-attribute", () -> "sub");
        properties.add("finance.ai.budget-proposal.url",
                () -> "http://127.0.0.1:" + AI_SERVER.getAddress().getPort());
        properties.add("finance.ai.budget-proposal.service-token", () -> AI_SERVICE_TOKEN);
        properties.add("finance.ai.receipt-basket.url",
                () -> "http://127.0.0.1:" + AI_SERVER.getAddress().getPort());
        properties.add("finance.ai.receipt-basket.service-token", () -> AI_SERVICE_TOKEN);
        properties.add("finance.ai.receipt-ocr.url", () -> "http://127.0.0.1:" + AI_SERVER.getAddress().getPort());
        properties.add("finance.ai.receipt-ocr.service-token", () -> AI_SERVICE_TOKEN);
        properties.add("finance.ai.merchant-classification.url",
                () -> "http://127.0.0.1:" + AI_SERVER.getAddress().getPort());
        properties.add("finance.ai.merchant-classification.service-token", () -> AI_SERVICE_TOKEN);
        properties.add("finance.analytics.price-history.url",
                () -> "http://127.0.0.1:" + AI_SERVER.getAddress().getPort());
        properties.add("finance.analytics.price-history.service-token", () -> ANALYTICS_SERVICE_TOKEN);
        properties.add("finance.exports.service-token", () -> EXPORT_SERVICE_TOKEN);
        properties.add("finance.telegram.service-token", () -> TELEGRAM_SERVICE_TOKEN);
        properties.add("finance.migration.service-token", () -> MIGRATION_SERVICE_TOKEN);
        properties.add("finance.imports.parser.url", () -> "http://127.0.0.1:" + AI_SERVER.getAddress().getPort());
        properties.add("finance.imports.parser.service-token", () -> AI_SERVICE_TOKEN);
    }

    @BeforeEach
    void createTenantAndMembership() {
        lenient().when(recalculationImpactClient.calculate(any())).thenReturn(new Impact(
                "receipt-recalculation-impact.v1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "available", "complete", "0.00", "0.00", "0.00"));
        lenient().when(goalProgressClient.calculate(any())).thenAnswer(invocation -> {
            GoalCandidatesApi.ProgressRequest request = invocation.getArgument(0);
            Instant asOf = request.asOf();
            var goal = request.goal();
            List<GoalCandidatesApi.F45PurchaseInput> matching = request.purchases().stream()
                    .filter(purchase -> !purchase.purchasedAt().isBefore(goal.acceptedAt())
                            && !purchase.purchasedAt().isAfter(goal.endsAt()) && !purchase.purchasedAt().isAfter(asOf))
                    .filter(purchase -> "product".equals(goal.scope()) ? purchase.productKey().equals(goal.key())
                            : goal.memberProductKeys().contains(purchase.productKey()))
                    .toList();
            boolean unknown = matching.stream().anyMatch(purchase -> purchase.lineSum() == null);
            BigDecimal spentAmount = matching.stream().filter(purchase -> purchase.lineSum() != null)
                    .map(purchase -> new BigDecimal(purchase.lineSum())).reduce(BigDecimal.ZERO, BigDecimal::add);
            Boolean over = "count".equals(goal.unit()) ? matching.size() > goal.countTarget()
                    : unknown ? null : spentAmount.compareTo(new BigDecimal(goal.monthlyLimit())) > 0;
            Boolean met = over == null ? null : !over;
            return new GoalCandidatesApi.GoalProgress("goal-progress-f45.v1", request.inputWatermark(),
                    goal.unit(), matching.size(), unknown ? null : spentAmount.setScale(2).toPlainString(), unknown,
                    over, met, asOf.isAfter(goal.endsAt()),
                    Math.max(0, (int) Math.ceil(java.time.Duration.between(asOf, goal.endsAt()).toMillis() / 86_400_000.0)),
                    goal.acceptedAt(), goal.endsAt());
        });
        RECURRING_FIXTURE_ENABLED.set(false);
        LAST_WASTE_REQUEST.set("");
        WASTE_RESPONSE_STATUS.set(200);
        LAST_EVIDENCE_REQUEST.set("");
        EVIDENCE_RESPONSE_STATUS.set(200);
        SHOPPING_RESPONSE.set(DEFAULT_SHOPPING_RESPONSE);
        cleanupReceiptPhotoFixtures();
        grantAppPrivileges();
        subject = "keycloak|integration-" + UUID.randomUUID();
        tenantId = transactions.execute(status -> {
            UUID id = UUID.randomUUID();
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, id.toString());
            jdbc.update("INSERT INTO tenants (id, display_name) VALUES (?, 'API test')", id);
            UUID userId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)", userId, subject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'owner', ?)", id, subject, userId);
            jdbc.update("INSERT INTO member_profiles (tenant_id, user_id, display_name) VALUES (?, ?, 'API test member')",
                    id, userId);
            return id;
        });
    }

    @AfterEach
    void cleanupReceiptPhotoFixturesAfterTest() {
        cleanupReceiptPhotoFixtures();
    }

    private void cleanupReceiptPhotoFixtures() {
        List<String> keys = List.of("receipt-photo-upload-key-0001", "receipt-photo-worker-key-0001",
                "receipt-photo-malware-key-01", "receipt-photo-retry-key-0001", "receipt-photo-lease-key-0001",
                "receipt-photo-vision-fallback-key-0001", "receipt-photo-vision-only-key-0001",
                "receipt-photo-reconciliation-key-0001");
        List<String> storageKeys = new java.util.ArrayList<>();
        try (var connection = adminConnection()) {
            connection.setAutoCommit(false);
            try (var lookup = connection.prepareStatement("""
                    SELECT j.id, j.receipt_id, j.document_id, d.storage_key
                    FROM receipt_processing_jobs j JOIN documents d ON d.tenant_id = j.tenant_id AND d.id = j.document_id
                    WHERE j.idempotency_key = ANY (?)
                    """)) {
                lookup.setArray(1, connection.createArrayOf("varchar", keys.toArray()));
                try (var rows = lookup.executeQuery()) {
                    while (rows.next()) {
                        UUID jobId = rows.getObject("id", UUID.class);
                        UUID receiptId = rows.getObject("receipt_id", UUID.class);
                        UUID documentId = rows.getObject("document_id", UUID.class);
                        storageKeys.add(rows.getString("storage_key"));
                        try (var statement = connection.prepareStatement(
                                "DELETE FROM receipt_readings WHERE source_job_id = ?")) {
                            statement.setObject(1, jobId);
                            statement.executeUpdate();
                        }
                        try (var statement = connection.prepareStatement(
                                "DELETE FROM receipt_processing_jobs WHERE id = ?")) {
                            statement.setObject(1, jobId);
                            statement.executeUpdate();
                        }
                        if (receiptId != null) try (var statement = connection.prepareStatement(
                                "DELETE FROM receipts WHERE id = ?")) {
                            statement.setObject(1, receiptId);
                            statement.executeUpdate();
                        }
                        try (var statement = connection.prepareStatement("DELETE FROM documents WHERE id = ?")) {
                            statement.setObject(1, documentId);
                            statement.executeUpdate();
                        }
                    }
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot clean receipt photo test fixtures", exception);
        }
        for (String storageKey : storageKeys) receiptStorage.delete(storageKey);
    }

    @Test
    void receiptPhotoUploadIsPrivateIdempotentAndOwnerScoped() throws Exception {
        String key = "receipt-photo-upload-key-0001";
        byte[] png = pngBytes(10);
        String first = mvc.perform(multipart("/api/v1/tenants/{tenantId}/receipts/photo-jobs", tenantId)
                        .file(new MockMultipartFile("file", "../../private-name.txt", "application/pdf", png))
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("queued"))
                .andExpect(jsonPath("$.progressPercent").value(0))
                .andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(first, "$.id");

        String replay = mvc.perform(multipart("/api/v1/tenants/{tenantId}/receipts/photo-jobs", tenantId)
                        .file(new MockMultipartFile("file", "receipt.png", "image/png", png))
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(jobId, com.jayway.jsonpath.JsonPath.read(replay, "$.id"));

        mvc.perform(multipart("/api/v1/tenants/{tenantId}/receipts/photo-jobs", tenantId)
                        .file(new MockMultipartFile("file", "receipt.png", "image/png", pngBytes(11)))
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict());

        String otherSubject = "keycloak|receipt-reader-" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID otherUser = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)",
                    otherUser, otherSubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'member', ?)",
                    tenantId, otherSubject, otherUser);
        });
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipt-jobs/{jobId}", tenantId, jobId)
                        .with(jwt().jwt(token -> token.subject(otherSubject))))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/tenants/{tenantId}/receipt-jobs/{jobId}", tenantId, jobId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.documentId").isNotEmpty())
                .andExpect(jsonPath("$.storageKey").doesNotExist());

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            var document = jdbc.queryForMap("SELECT storage_key, mime_type, original_name FROM documents "
                    + "WHERE tenant_id = ? AND uploaded_by_user_id = (SELECT user_id FROM memberships "
                    + "WHERE tenant_id = ? AND subject = ?)", tenantId, tenantId, subject);
            org.junit.jupiter.api.Assertions.assertEquals("image/png", document.get("mime_type"));
            String originalName = String.valueOf(document.get("original_name"));
            org.junit.jupiter.api.Assertions.assertFalse(originalName.contains(".."));
            org.junit.jupiter.api.Assertions.assertFalse(originalName.contains("/"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(png, receiptStorage.get(String.valueOf(document.get("storage_key"))));
            transactions.executeWithoutResult(cleanup -> {
                jdbc.update("DELETE FROM receipt_processing_jobs WHERE tenant_id = ? AND document_id = ?",
                        tenantId, jdbc.queryForObject("SELECT id FROM documents WHERE tenant_id = ? AND uploaded_by_user_id "
                                + "= (SELECT user_id FROM memberships WHERE tenant_id = ? AND subject = ?) ",
                                UUID.class, tenantId, tenantId, subject));
                jdbc.update("DELETE FROM documents WHERE tenant_id = ? AND uploaded_by_user_id = "
                        + "(SELECT user_id FROM memberships WHERE tenant_id = ? AND subject = ?)", tenantId, tenantId, subject);
            });
            receiptStorage.delete(String.valueOf(document.get("storage_key")));
        });
    }

    @Test
    void receiptPhotoWorkerScansLocallyCreatesOneReviewDraftAndNeverPostsTransaction() throws Exception {
        byte[] png = pngBytes(20);
        String response = mvc.perform(multipart("/api/v1/tenants/{tenantId}/receipts/photo-jobs", tenantId)
                        .file(new MockMultipartFile("file", "receipt.png", "image/png", png))
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header("Idempotency-Key", "receipt-photo-worker-key-0001"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(response, "$.id");
        UUID jobUuid = UUID.fromString(jobId);
        var word = java.util.Map.<String, Object>of("text", "TOTAL", "confidence", 96.0,
                "box", java.util.Map.of("x", 1, "y", 2, "width", 20, "height", 8));
        org.mockito.Mockito.when(receiptMalwareScanner.scan(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(ClamAvReceiptScanner.ScanResult.CLEAN);
        org.mockito.Mockito.when(receiptOcrClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid)))
                .thenReturn(new ReceiptOcrClient.OcrReading("TOTAL 120.00", List.of(word), "tesseract",
                        "tesseract-5.3.0", "tesseract-ocr.v2", new BigDecimal("0.9600")));

        org.junit.jupiter.api.Assertions.assertTrue(receiptProcessing.processNext());
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipt-jobs/{jobId}", tenantId, jobId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.stage").value("complete"))
                .andExpect(jsonPath("$.progressPercent").value(100));
        String receiptId = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT receipt_id::text FROM receipt_processing_jobs WHERE id = ?",
                    String.class, jobUuid);
        });
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipts/{receiptId}", tenantId, receiptId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("review_required"))
                .andExpect(jsonPath("$.selectedReader").value("ocr"))
                .andExpect(jsonPath("$.transactionId").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipts/{receiptId}/readings", tenantId, receiptId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("TOTAL 120.00"))
                .andExpect(jsonPath("$.words[0].box.width").value(20));
        org.junit.jupiter.api.Assertions.assertFalse(receiptProcessing.processNext());
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM receipt_readings WHERE tenant_id = ? AND source_job_id = ?", Integer.class,
                    tenantId, jobUuid));
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM transactions WHERE tenant_id = ?", Integer.class, tenantId));
        });
        org.mockito.Mockito.verify(receiptOcrClient, org.mockito.Mockito.times(1))
                .read(org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.eq(jobUuid));
    }

    @Test
    void visionFailureFallsBackToOcrAndIsVisibleInReadingProvenance() throws Exception {
        UUID jobUuid = uploadReceiptJob("receipt-photo-vision-fallback-key-0001", 21);
        stubCleanScan();
        org.mockito.Mockito.when(receiptVisionClient.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(receiptVisionClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid)))
                .thenThrow(new ReceiptVisionClient.VisionUnavailableException("VISION_UNSUPPORTED"));
        org.mockito.Mockito.when(receiptOcrClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid)))
                .thenReturn(new ReceiptOcrClient.OcrReading("TOTAL 120.00", List.of(), "tesseract",
                        "tesseract-5.3.0", "tesseract-ocr.v2", new BigDecimal("0.9600")));

        org.junit.jupiter.api.Assertions.assertTrue(receiptProcessing.processNext());
        String receiptId = receiptId(jobUuid);
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipts/{receiptId}/readings", tenantId, receiptId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("TOTAL 120.00"))
                .andExpect(jsonPath("$.visionFallbackReason").value("VISION_UNSUPPORTED"));
        org.mockito.Mockito.verify(receiptOcrClient).read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid));
    }

    @Test
    void successfulVisionCanCompleteReviewDraftWhenOcrIsUnavailable() throws Exception {
        UUID jobUuid = uploadReceiptJob("receipt-photo-vision-only-key-0001", 22);
        stubCleanScan();
        org.mockito.Mockito.when(receiptVisionClient.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(receiptVisionClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid)))
                .thenReturn(new ReceiptVisionClient.VisionReading("Магазин", "2026-10-04", "123.45",
                        List.of(java.util.Map.of("name", "Хлеб", "quantity", "1", "unitPrice", "123.45",
                                "lineSum", "123.45")), "ollama", "qwen3-vl:4b", "receipt-vision.v1", null));
        org.mockito.Mockito.when(receiptOcrClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid))).thenThrow(new IllegalStateException("OCR unavailable"));

        org.junit.jupiter.api.Assertions.assertTrue(receiptProcessing.processNext());
        String receiptId = receiptId(jobUuid);
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipts/{receiptId}", tenantId, receiptId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("review_required"))
                .andExpect(jsonPath("$.selectedReader").value("vision"))
                .andExpect(jsonPath("$.cashTotal").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.merchant").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.receiptDate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.categoryCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.categorySource").value("unknown"))
                .andExpect(jsonPath("$.transactionId").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipts/{receiptId}/readings", tenantId, receiptId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(""))
                .andExpect(jsonPath("$.provider").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.ocrFallbackReason").value("OCR_UNAVAILABLE"))
                .andExpect(jsonPath("$.vision.modelVersion").value("qwen3-vl:4b"))
                .andExpect(jsonPath("$.vision.store").value("Магазин"))
                .andExpect(jsonPath("$.vision.date").value("2026-10-04"))
                .andExpect(jsonPath("$.vision.total").value("123.45"))
                .andExpect(jsonPath("$.vision.items[0].name").value("Хлеб"));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM receipt_readings WHERE tenant_id = ? AND source_job_id = ? AND reader = 'vision'",
                    Integer.class, tenantId, jobUuid));
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM transactions WHERE tenant_id = ?", Integer.class, tenantId));
        });
    }

    @Test
    void receiptWorkerReconcilesOcrAndVisionOneToOneAndShowsBothSources() throws Exception {
        UUID jobUuid = uploadReceiptJob("receipt-photo-reconciliation-key-0001", 23);
        stubCleanScan();
        org.mockito.Mockito.when(receiptVisionClient.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(receiptVisionClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid)))
                .thenReturn(new ReceiptVisionClient.VisionReading("Магазин", "2026-10-04", "20.00",
                        List.of(
                                java.util.Map.of("name", "Хлеб", "quantity", "1", "unitPrice", "10.00", "lineSum", "10.00"),
                                java.util.Map.of("name", "Хлеб", "quantity", "1", "unitPrice", "10.00", "lineSum", "10.00")),
                        "ollama", "qwen3-vl:4b", "receipt-vision.v1", null));
        org.mockito.Mockito.when(receiptOcrClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid)))
                .thenReturn(new ReceiptOcrClient.OcrReading("Хлеб 10.00", List.of(), "tesseract",
                        "tesseract-5.3.0", "tesseract-ocr.v2", new BigDecimal("0.9600"),
                        new BigDecimal("20.00"), List.of(new ReceiptOcrClient.OcrItem("Хлеб", "1", "10.00", "10.00"))));

        org.junit.jupiter.api.Assertions.assertTrue(receiptProcessing.processNext());
        String receiptId = receiptId(jobUuid);
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipts/{receiptId}", tenantId, receiptId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedReader").value("vision"))
                .andExpect(jsonPath("$.transactionId").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipts/{receiptId}/readings", tenantId, receiptId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ocrTotal").value("20.00"))
                .andExpect(jsonPath("$.ocrItems.length()").value(1))
                .andExpect(jsonPath("$.reconciliation.decision").value("auto_selected"))
                .andExpect(jsonPath("$.reconciliation.selectedReader").value("vision"))
                .andExpect(jsonPath("$.reconciliation.itemEvidence.length()").value(2))
                .andExpect(jsonPath("$.reconciliation.itemEvidence[0].ocrOrdinal").value(1))
                .andExpect(jsonPath("$.reconciliation.itemEvidence[0].status").value("corroborated"))
                .andExpect(jsonPath("$.reconciliation.itemEvidence[1].ocrOrdinal").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.reconciliation.itemEvidence[1].status").value("reader_only"));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM transactions WHERE tenant_id = ?", Integer.class, tenantId));
        });
    }

    @Test
    void receiptWorkerExposesUniqueOcrTopUpsWithoutSelectingOrPostingTheReceipt() throws Exception {
        UUID jobUuid = uploadReceiptJob("receipt-photo-top-up-key-000001", 24);
        stubCleanScan();
        org.mockito.Mockito.when(receiptVisionClient.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(receiptVisionClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid)))
                .thenReturn(new ReceiptVisionClient.VisionReading("Магазин", "2026-10-04", "30.00",
                        List.of(Map.of("name", "Хлеб", "quantity", "1", "unitPrice", "10.00", "lineSum", "10.00")),
                        "ollama", "qwen3-vl:4b", "receipt-vision.v1", null));
        org.mockito.Mockito.when(receiptOcrClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid)))
                .thenReturn(new ReceiptOcrClient.OcrReading("Хлеб Яблоки Молоко Конфеты", List.of(), "tesseract",
                        "tesseract-5.3.0", "tesseract-ocr.v2", new BigDecimal("0.9600"), new BigDecimal("30.00"),
                        List.of(new ReceiptOcrClient.OcrItem("Хлеб", "1", "10.00", "10.00"),
                                new ReceiptOcrClient.OcrItem("Яблоки", null, null, "12.00"),
                                new ReceiptOcrClient.OcrItem("Молоко", null, null, "8.00"),
                                new ReceiptOcrClient.OcrItem("Конфеты", null, null, "15.00"))));

        org.junit.jupiter.api.Assertions.assertTrue(receiptProcessing.processNext());
        String receiptId = receiptId(jobUuid);
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipts/{receiptId}", tenantId, receiptId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedReader").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.transactionId").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipts/{receiptId}/readings", tenantId, receiptId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliation.decision").value("review_required"))
                .andExpect(jsonPath("$.reconciliation.suggestedTopUps.length()").value(2))
                .andExpect(jsonPath("$.reconciliation.suggestedTopUps[0].ocrOrdinal").value(2))
                .andExpect(jsonPath("$.reconciliation.suggestedTopUps[0].name").value("Яблоки"))
                .andExpect(jsonPath("$.reconciliation.suggestedTopUps[0].lineSum").value("12.00"))
                .andExpect(jsonPath("$.reconciliation.suggestedTopUps[1].ocrOrdinal").value(3))
                .andExpect(jsonPath("$.reconciliation.suggestedTopUps[1].name").value("Молоко"));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM transactions WHERE tenant_id = ?", Integer.class, tenantId));
        });
    }

    private UUID uploadReceiptJob(String idempotencyKey, int imageByte) throws Exception {
        String response = mvc.perform(multipart("/api/v1/tenants/{tenantId}/receipts/photo-jobs", tenantId)
                        .file(new MockMultipartFile("file", "receipt.png", "image/png", pngBytes(imageByte)))
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.id"));
    }

    private void stubCleanScan() {
        org.mockito.Mockito.when(receiptMalwareScanner.scan(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(ClamAvReceiptScanner.ScanResult.CLEAN);
    }

    private String receiptId(UUID jobId) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT receipt_id::text FROM receipt_processing_jobs WHERE id = ?",
                    String.class, jobId);
        });
    }

    @Test
    void infectedReceiptImageIsRejectedBeforeOcr() throws Exception {
        String response = mvc.perform(multipart("/api/v1/tenants/{tenantId}/receipts/photo-jobs", tenantId)
                        .file(new MockMultipartFile("file", "infected.png", "image/png", pngBytes(30)))
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header("Idempotency-Key", "receipt-photo-malware-key-01"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(response, "$.id");
        org.mockito.Mockito.when(receiptMalwareScanner.scan(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(ClamAvReceiptScanner.ScanResult.INFECTED);

        org.junit.jupiter.api.Assertions.assertTrue(receiptProcessing.processNext());
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipt-jobs/{jobId}", tenantId, jobId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("rejected"))
                .andExpect(jsonPath("$.errorCode").value("MALWARE_DETECTED"));
        org.mockito.Mockito.verify(receiptOcrClient, org.mockito.Mockito.never())
                .read(org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void receiptScannerOutageStaysRetryableAndRetriesFromDurableQueue() throws Exception {
        String response = mvc.perform(multipart("/api/v1/tenants/{tenantId}/receipts/photo-jobs", tenantId)
                        .file(new MockMultipartFile("file", "receipt.png", "image/png", pngBytes(40)))
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header("Idempotency-Key", "receipt-photo-retry-key-0001"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(response, "$.id");
        UUID jobUuid = UUID.fromString(jobId);
        var word = java.util.Map.<String, Object>of("text", "TOTAL", "confidence", 96.0,
                "box", java.util.Map.of("x", 1, "y", 2, "width", 20, "height", 8));
        org.mockito.Mockito.when(receiptMalwareScanner.scan(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenThrow(new IllegalStateException("scanner offline"))
                .thenReturn(ClamAvReceiptScanner.ScanResult.CLEAN);
        org.mockito.Mockito.when(receiptOcrClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid)))
                .thenReturn(new ReceiptOcrClient.OcrReading("TOTAL 90.00", List.of(word), "tesseract",
                        "tesseract-5.3.0", "tesseract-ocr.v2", new BigDecimal("0.9600")));

        org.junit.jupiter.api.Assertions.assertTrue(receiptProcessing.processNext());
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipt-jobs/{jobId}", tenantId, jobId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("retryable"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.errorCode").value("SCANNER_UNAVAILABLE"));
        org.mockito.Mockito.verify(receiptOcrClient, org.mockito.Mockito.never())
                .read(org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.eq(jobUuid));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE receipt_processing_jobs SET next_attempt_at = now() WHERE tenant_id = ? AND id = ?",
                    tenantId, jobUuid);
        });

        org.junit.jupiter.api.Assertions.assertTrue(receiptProcessing.processNext());
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipt-jobs/{jobId}", tenantId, jobId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.attemptCount").value(2));
        org.mockito.Mockito.verify(receiptOcrClient, org.mockito.Mockito.times(1))
                .read(org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.eq(jobUuid));
    }

    @Test
    void receiptWorkerRecoversAnExpiredLeaseWithoutRegressingProgress() throws Exception {
        String response = mvc.perform(multipart("/api/v1/tenants/{tenantId}/receipts/photo-jobs", tenantId)
                        .file(new MockMultipartFile("file", "receipt.png", "image/png", pngBytes(50)))
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header("Idempotency-Key", "receipt-photo-lease-key-0001"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(response, "$.id");
        UUID jobUuid = UUID.fromString(jobId);
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("""
                    UPDATE receipt_processing_jobs SET state = 'running', stage = 'ocr', progress_percent = 40,
                        attempt_count = 1, lease_expires_at = now() - interval '1 second'
                    WHERE tenant_id = ? AND id = ?
                    """, tenantId, jobUuid);
        });
        var word = java.util.Map.<String, Object>of("text", "TOTAL", "confidence", 96.0,
                "box", java.util.Map.of("x", 1, "y", 2, "width", 20, "height", 8));
        org.mockito.Mockito.when(receiptMalwareScanner.scan(org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(ClamAvReceiptScanner.ScanResult.CLEAN);
        org.mockito.Mockito.when(receiptOcrClient.read(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(jobUuid)))
                .thenReturn(new ReceiptOcrClient.OcrReading("TOTAL 90.00", List.of(word), "tesseract",
                        "tesseract-5.3.0", "tesseract-ocr.v2", new BigDecimal("0.9600")));

        org.junit.jupiter.api.Assertions.assertTrue(receiptProcessing.processNext());
        mvc.perform(get("/api/v1/tenants/{tenantId}/receipt-jobs/{jobId}", tenantId, jobId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.attemptCount").value(2))
                .andExpect(jsonPath("$.progressPercent").value(100));
    }

    private static byte[] pngBytes(int marker) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, marker);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @Test
    void firstLoginCanCreatePersonalTenantAndReadProfile() throws Exception {
        String firstLoginSubject = "keycloak|first-login-" + UUID.randomUUID();
        var auth = jwt().jwt(token -> token.subject(firstLoginSubject));

        var response = mvc.perform(post("/api/v1/tenants").with(auth)
                        .contentType("application/json")
                        .content("{\"displayName\":\"Home\",\"timezone\":\"Europe/Moscow\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Home"))
                .andExpect(jsonPath("$.role").value("owner"))
                .andReturn();
        String createdTenantId = com.jayway.jsonpath.JsonPath.read(
                response.getResponse().getContentAsString(), "$.tenantId");

        mvc.perform(get("/api/v1/me/tenants").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value(createdTenantId))
                .andExpect(jsonPath("$[0].displayName").value("Home"))
                .andExpect(jsonPath("$[0].role").value("owner"));

        Integer profileCount = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, createdTenantId);
            return jdbc.queryForObject("SELECT count(*) FROM member_profiles WHERE tenant_id = ?", Integer.class,
                    UUID.fromString(createdTenantId));
        });
        org.junit.jupiter.api.Assertions.assertEquals(1, profileCount);
    }

    @Test
    void onboardingRejectsUnknownTimezoneBeforeCreatingTenant() throws Exception {
        String firstLoginSubject = "keycloak|invalid-timezone-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/tenants").with(jwt().jwt(token -> token.subject(firstLoginSubject)))
                        .contentType("application/json")
                        .content("{\"displayName\":\"Home\",\"timezone\":\"Mars/Olympus\"}"))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM external_identities WHERE provider = 'keycloak' AND subject = ?",
                Integer.class, firstLoginSubject));
    }

    @Test
    void onboardingKeepsMemberNameAndOptionalIncomeSeparateFromWorkspace() throws Exception {
        String firstLoginSubject = "keycloak|member-profile-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/tenants").with(jwt().jwt(token -> token.subject(firstLoginSubject)))
                        .contentType("application/json")
                        .content("{\"displayName\":\"Home\",\"memberDisplayName\":\"Alex\","
                                + "\"plannedIncome\":100000.00,\"timezone\":\"UTC\"}"))
                .andExpect(status().isCreated());
        var profile = profileForSubject(firstLoginSubject);
        org.junit.jupiter.api.Assertions.assertEquals("Alex", profile.get("display_name"));
        org.junit.jupiter.api.Assertions.assertEquals(new java.math.BigDecimal("100000.00"), profile.get("planned_income"));
        org.junit.jupiter.api.Assertions.assertEquals("complete", profile.get("onboarding_state"));
    }

    @Test
    void onboardingCanSkipIncomeWithoutLosingMemberIdentity() throws Exception {
        String firstLoginSubject = "keycloak|skip-income-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/tenants").with(jwt().jwt(token -> token.subject(firstLoginSubject)))
                        .contentType("application/json")
                        .content("{\"displayName\":\"Family\",\"memberDisplayName\":\"Alex\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isCreated());
        var profile = profileForSubject(firstLoginSubject);
        org.junit.jupiter.api.Assertions.assertEquals("Alex", profile.get("display_name"));
        org.junit.jupiter.api.Assertions.assertNull(profile.get("planned_income"));
        org.junit.jupiter.api.Assertions.assertEquals("started", profile.get("onboarding_state"));
    }

    @Test
    void telegramNameFillsOnlyAnUnchosenWorkspaceDefaultMemberName() throws Exception {
        String firstLoginSubject = "keycloak|telegram-name-default-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/tenants").with(jwt().jwt(token -> token.subject(firstLoginSubject)))
                        .contentType("application/json")
                        .content("{\"displayName\":\"Home\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isCreated());
        String code = issueTelegramLinkCode(firstLoginSubject);
        long telegramUserId = newTelegramUserId();
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId
                                + ",\"telegramDisplayName\":\"Telegram Alex\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("linked"));
        mvc.perform(post("/internal/v1/telegram/tenants")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId
                                + ",\"telegramDisplayName\":\"Updated Telegram Alex\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants[0].tenantId").exists());

        org.junit.jupiter.api.Assertions.assertEquals("Updated Telegram Alex", profileForSubject(firstLoginSubject).get("display_name"));
        org.junit.jupiter.api.Assertions.assertEquals("telegram", profileNameSourceForSubject(firstLoginSubject));
    }

    @Test
    void telegramNameNeverOverwritesAnExplicitMemberName() throws Exception {
        String firstLoginSubject = "keycloak|telegram-name-custom-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/tenants").with(jwt().jwt(token -> token.subject(firstLoginSubject)))
                        .contentType("application/json")
                        .content("{\"displayName\":\"Home\",\"memberDisplayName\":\"Chosen Alex\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isCreated());
        String code = issueTelegramLinkCode(firstLoginSubject);
        long telegramUserId = newTelegramUserId();
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId
                                + ",\"telegramDisplayName\":\"Different Telegram Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("linked"));

        org.junit.jupiter.api.Assertions.assertEquals("Chosen Alex", profileForSubject(firstLoginSubject).get("display_name"));
        org.junit.jupiter.api.Assertions.assertEquals("user", profileNameSourceForSubject(firstLoginSubject));
    }

    @Test
    void laterTelegramNameSyncPreservesAMemberChosenName() throws Exception {
        String firstLoginSubject = "keycloak|telegram-name-edited-" + UUID.randomUUID();
        var auth = jwt().jwt(token -> token.subject(firstLoginSubject));
        mvc.perform(post("/api/v1/tenants").with(auth)
                        .contentType("application/json")
                        .content("{\"displayName\":\"Home\",\"timezone\":\"UTC\"}"))
                .andExpect(status().isCreated());
        String code = issueTelegramLinkCode(firstLoginSubject);
        long telegramUserId = newTelegramUserId();
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId
                                + ",\"telegramDisplayName\":\"Telegram Alex\"}"))
                .andExpect(status().isOk());
        UUID createdTenantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                mvc.perform(get("/api/v1/me/tenants").with(auth)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$[0].tenantId"));

        mvc.perform(patch("/api/v1/tenants/{tenantId}/profile/me", createdTenantId).with(auth)
                        .contentType("application/json")
                        .content("{\"displayName\":\"Chosen Alex\",\"plannedIncome\":42000.00,\"onboardingState\":\"complete\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/v1/telegram/tenants")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId
                                + ",\"telegramDisplayName\":\"Updated Telegram Alex\"}"))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals("Chosen Alex", profileForSubject(firstLoginSubject).get("display_name"));
        var preservedProfile = profileForSubject(firstLoginSubject);
        org.junit.jupiter.api.Assertions.assertEquals(new java.math.BigDecimal("42000.00"), preservedProfile.get("planned_income"));
        org.junit.jupiter.api.Assertions.assertEquals("complete", preservedProfile.get("onboarding_state"));
        org.junit.jupiter.api.Assertions.assertEquals("user", profileNameSourceForSubject(firstLoginSubject));
    }

    @Test
    void telegramLinkCodeIsHashedAndCanOnlyBeRedeemedOnceForTheAuthenticatedUser() throws Exception {
        long telegramUserId = newTelegramUserId();
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post("/api/v1/me/telegram-link").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isString())
                .andReturn();
        String code = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.code");
        String storedHash = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
            return jdbc.queryForObject("SELECT code_hash FROM telegram_link_codes WHERE code_hash = ?",
                    String.class, TelegramLinkCode.hash(code));
        });
        org.junit.jupiter.api.Assertions.assertEquals(TelegramLinkCode.hash(code), storedHash);
        org.junit.jupiter.api.Assertions.assertNotEquals(code, storedHash);

        String request = """
                {"code":"%s","telegramUserId":%d,"userId":"00000000-0000-0000-0000-000000000001"}
                """.formatted(code, telegramUserId);
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("linked"));
        UUID linkedUser = jdbc.queryForObject("SELECT user_id FROM external_identities "
                + "WHERE provider = 'telegram' AND subject = ?", UUID.class, Long.toString(telegramUserId));
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(subject), linkedUser);

        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(request))
                .andExpect(status().isConflict());
    }

    @Test
    void telegramLinkRedemptionRequiresTheScopedServiceCredential() throws Exception {
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", "wrong-service-token")
                        .contentType("application/json")
                        .content("{\"code\":\"ABCD-EFGH-JKLM-NPQR\",\"telegramUserId\":987654321}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void telegramActorContextIsBoundToTheLinkedMemberAndRechecksMembership() throws Exception {
        long telegramUserId = newTelegramUserId();
        var created = mvc.perform(post("/api/v1/me/telegram-link")
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk()).andReturn();
        String code = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.code");
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk());

        String serviceToken = TELEGRAM_SERVICE_TOKEN;
        mvc.perform(post("/internal/v1/telegram/tenants")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants[0].tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.tenants[0].role").value("owner"))
                .andExpect(jsonPath("$.tenants[0].userId").doesNotExist());
        mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/internal/v1/telegram/tenants")
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isUnauthorized());

        String context = mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\""
                                + tenantId + "\",\"userId\":\"00000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.role").value("owner"))
                .andExpect(jsonPath("$.permissions").isArray())
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("transaction.write.any")))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(context, "$.token");
        String tokenHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        Integer storedContext = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.telegram_actor_service', 'true', true)", String.class);
            return jdbc.queryForObject("SELECT count(*) FROM telegram_actor_contexts "
                            + "WHERE context_hash = ? AND telegram_user_id = ? AND tenant_id = ?",
                    Integer.class, tokenHash, telegramUserId, tenantId);
        });
        org.junit.jupiter.api.Assertions.assertEquals(1, storedContext, "database stores the context hash, not its token");
        mvc.perform(post("/internal/v1/telegram/actor-contexts/resolve")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.role").value("owner"));
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId)
                        .with(jwt().jwt(jwt -> jwt.subject(subject)))
                        .header("Idempotency-Key", "telegram-context-expense-0001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"12.34\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Home only\","
                                + "\"occurredAt\":\"2026-10-03T10:00:00Z\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/internal/v1/telegram/summary")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"userId\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseTotal").value("12.34"))
                .andExpect(jsonPath("$.transactionCount").value(1));

        String draftBody = "{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-draft-create-0001\","
                + "\"text\":\"Такси 2 тыс\",\"userId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"tenantId\":\"" + UUID.randomUUID() + "\"}";
        var telegramDraft = mvc.perform(post("/internal/v1/telegram/transaction-drafts")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json").content(draftBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("pending"))
                .andExpect(jsonPath("$.amount").value("2000.00"))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andReturn().getResponse().getContentAsString();
        String telegramDraftId = com.jayway.jsonpath.JsonPath.read(telegramDraft, "$.id");
        mvc.perform(post("/internal/v1/telegram/summary")
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseTotal").value("12.34"))
                .andExpect(jsonPath("$.transactionCount").value(1));
        mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/read", telegramDraftId)
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryCode").value("transport"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/edit", telegramDraftId)
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"version\":1,\"type\":\"expense\","
                                + "\"amount\":\"2000.00\",\"currency\":\"RUB\",\"categoryCode\":\"food\","
                                + "\"subcategoryCode\":null,\"description\":\"Такси\","
                                + "\"occurredAt\":\"2026-10-03T10:00:00Z\",\"debtId\":null,"
                                + "\"userId\":\"00000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryCode").value("food"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/edit", telegramDraftId)
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"version\":1,\"type\":\"expense\","
                                + "\"amount\":\"2000.00\",\"currency\":\"RUB\",\"categoryCode\":\"transport\","
                                + "\"subcategoryCode\":null,\"description\":\"Такси\","
                                + "\"occurredAt\":\"2026-10-03T10:00:00Z\",\"debtId\":null}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/amount", telegramDraftId)
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"version\":2,\"amount\":\"2500.00\","
                                + "\"userId\":\"00000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("2500.00"))
                .andExpect(jsonPath("$.version").value(3));
        String confirmBody = "{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-draft-confirm-0001\","
                + "\"version\":3,\"userId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"tenantId\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/confirm", telegramDraftId)
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json").content(confirmBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("2500.00"));
        mvc.perform(post("/internal/v1/telegram/summary")
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseTotal").value("2512.34"))
                .andExpect(jsonPath("$.transactionCount").value(2));

        var createdDebt = mvc.perform(post("/api/v1/tenants/{tenantId}/debts", tenantId)
                        .with(jwt().jwt(jwt -> jwt.subject(subject)))
                        .header("Idempotency-Key", "telegram-debt-create-0001")
                        .contentType("application/json")
                        .content("{\"name\":\"Credit card\",\"openingBalance\":\"10000.00\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String debtId = com.jayway.jsonpath.JsonPath.read(createdDebt, "$.id");
        mvc.perform(post("/internal/v1/telegram/debts")
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"userId\":\"00000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debts[0].id").value(debtId))
                .andExpect(jsonPath("$.debts[0].status").value("open"));
        String oldAiDraftResponse = AI_DRAFT_RESPONSE.get();
        try {
            AI_DRAFT_RESPONSE.set("""
                    {"provider":"test-ollama","type":"debt_payment","amount":"1500.00","categoryCode":"долги",
                     "subcategoryCode":null,"description":"Credit card payment","occurredAt":"2026-10-03T12:00:00+03:00",
                     "modelVersion":"test-model-1","promptVersion":"transaction-draft.v1"}
                    """);
            var debtDraft = mvc.perform(post("/internal/v1/telegram/transaction-drafts")
                            .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                            .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-debt-draft-0001\","
                                    + "\"text\":\"Платёж по кредитке 1500\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("debt_payment"))
                    .andExpect(jsonPath("$.debtId").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            String debtDraftId = com.jayway.jsonpath.JsonPath.read(debtDraft, "$.id");
            mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/confirm", debtDraftId)
                            .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                            .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-debt-confirm-0001\","
                                    + "\"version\":1}"))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/edit", debtDraftId)
                            .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                            .content("{\"token\":\"" + token + "\",\"version\":1,\"type\":\"debt_payment\","
                                    + "\"amount\":\"1500.00\",\"currency\":\"RUB\",\"categoryCode\":\"долги\","
                                    + "\"subcategoryCode\":null,\"description\":\"Credit card payment\","
                                    + "\"occurredAt\":\"2026-10-03T09:00:00Z\",\"debtId\":\"" + debtId + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2));
            mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/confirm", debtDraftId)
                            .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                            .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-debt-confirm-0001\","
                                    + "\"version\":2}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("debt_payment"))
                    .andExpect(jsonPath("$.amount").value("1500.00"));
            mvc.perform(get("/api/v1/tenants/{tenantId}/debts/{debtId}", tenantId, debtId)
                            .with(jwt().jwt(jwt -> jwt.subject(subject))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currentBalance").value("8500.00"));
        } finally {
            AI_DRAFT_RESPONSE.set(oldAiDraftResponse);
        }

        UUID linkedUser = jdbc.queryForObject("SELECT user_id FROM external_identities "
                + "WHERE provider = 'telegram' AND subject = ?", UUID.class, Long.toString(telegramUserId));
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE memberships SET role = 'viewer' WHERE tenant_id = ? AND user_id = ?",
                    tenantId, linkedUser);
            return null;
        });
        int aiCallsBeforeViewerAction = AI_CALLS.get();
        mvc.perform(post("/internal/v1/telegram/transaction-drafts")
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-viewer-write-0001\","
                                + "\"text\":\"Не должно попасть в AI\"}"))
                .andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertEquals(aiCallsBeforeViewerAction, AI_CALLS.get(),
                "viewer permission must be checked before the AI call");
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE memberships SET role = 'owner' WHERE tenant_id = ? AND user_id = ?",
                    tenantId, linkedUser);
            return null;
        });

        var cancelledDraft = mvc.perform(post("/internal/v1/telegram/transaction-drafts")
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-draft-create-0002\","
                                + "\"text\":\"Кофе 250\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String cancelledDraftId = com.jayway.jsonpath.JsonPath.read(cancelledDraft, "$.id");
        mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/cancel", cancelledDraftId)
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        mvc.perform(post("/internal/v1/telegram/summary")
                        .header("X-Finance-Service-Token", serviceToken).contentType("application/json")
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseTotal").value("2512.34"))
                .andExpect(jsonPath("$.transactionCount").value(2));

        String otherTenant = mvc.perform(post("/api/v1/tenants").with(jwt().jwt(jwt -> jwt.subject(subject)))
                        .contentType("application/json")
                        .content("{\"displayName\":\"Second space\",\"timezone\":\"Europe/Moscow\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID otherTenantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(otherTenant, "$.tenantId"));
        String switchedContext = mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\""
                                + otherTenantId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String switchedToken = com.jayway.jsonpath.JsonPath.read(switchedContext, "$.token");
        String oldTokenHash = tokenHash;
        Integer revokedContext = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.telegram_actor_service', 'true', true)", String.class);
            return jdbc.queryForObject("SELECT count(*) FROM telegram_actor_contexts "
                    + "WHERE context_hash = ? AND revoked_at IS NOT NULL", Integer.class, oldTokenHash);
        });
        org.junit.jupiter.api.Assertions.assertEquals(1, revokedContext);
        mvc.perform(post("/internal/v1/telegram/actor-contexts/resolve")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json").content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/telegram/summary")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json").content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/telegram/actor-contexts/resolve")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json").content("{\"token\":\"" + switchedToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(otherTenantId.toString()));
        mvc.perform(post("/internal/v1/telegram/summary")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json").content("{\"token\":\"" + switchedToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseTotal").value("0.00"))
                .andExpect(jsonPath("$.transactionCount").value(0));

        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, otherTenantId.toString());
            jdbc.update("UPDATE memberships SET status = 'removed' WHERE tenant_id = ? AND user_id = ?",
                    otherTenantId, linkedUser);
            return null;
        });
        mvc.perform(post("/internal/v1/telegram/actor-contexts/resolve")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json")
                        .content("{\"token\":\"" + switchedToken + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/telegram/summary")
                        .header("X-Finance-Service-Token", serviceToken)
                        .contentType("application/json")
                        .content("{\"token\":\"" + switchedToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void telegramHistoryCanRepeatExpenseOnceAndUndoOnlyLatestAccessibleTransaction() throws Exception {
        long telegramUserId = newTelegramUserId();
        String linkResponse = mvc.perform(post("/api/v1/me/telegram-link")
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = com.jayway.jsonpath.JsonPath.read(linkResponse, "$.code");
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk());
        String contextResponse = mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\"" + tenantId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(contextResponse, "$.token");
        var transactionResponse = mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId)
                        .with(jwt().jwt(jwt -> jwt.subject(subject)))
                        .header("Idempotency-Key", "telegram-history-source-0001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"25.50\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"subcategoryCode\":\"lunch\",\"description\":\"Lunch\","
                                + "\"source\":\"manual\",\"occurredAt\":\"2026-10-03T10:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String sourceId = com.jayway.jsonpath.JsonPath.read(transactionResponse, "$.id");

        mvc.perform(post("/internal/v1/telegram/transactions")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"limit\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[0].id").value(sourceId))
                .andExpect(jsonPath("$.transactions[0].amount").value("25.50"));

        String repeatRequest = "{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-repeat-action-0001\","
                + "\"transactionId\":\"" + sourceId + "\"}";
        var firstDraft = mvc.perform(post("/internal/v1/telegram/transaction-drafts/repeat")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(repeatRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("pending"))
                .andExpect(jsonPath("$.amount").value("25.50"))
                .andExpect(jsonPath("$.categoryCode").value("food"))
                .andExpect(jsonPath("$.subcategoryCode").value("lunch"))
                .andExpect(jsonPath("$.description").value("Lunch"))
                .andExpect(jsonPath("$.promptVersion").value("transaction-repeat.v1"))
                .andReturn().getResponse().getContentAsString();
        String draftId = com.jayway.jsonpath.JsonPath.read(firstDraft, "$.id");
        mvc.perform(post("/internal/v1/telegram/transaction-drafts/repeat")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(repeatRequest))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(draftId));
        mvc.perform(post("/internal/v1/telegram/summary")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN).contentType("application/json")
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expenseTotal").value("25.50"));

        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId)
                        .with(jwt().jwt(jwt -> jwt.subject(subject)))
                        .header("Idempotency-Key", "telegram-history-latest-0001")
                        .contentType("application/json")
                        .content("{\"type\":\"income\",\"amount\":\"100.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"salary\",\"description\":\"Later income\","
                                + "\"occurredAt\":\"2026-10-03T10:05:00Z\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/internal/v1/telegram/transactions/latest/void")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-undo-old-0001\","
                                + "\"transactionId\":\"" + sourceId + "\",\"version\":1}"))
                .andExpect(status().isPreconditionFailed());

        var postedRepeat = mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/confirm", draftId)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-repeat-confirm-0001\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("25.50"))
                .andExpect(jsonPath("$.source").value("repeat"))
                .andReturn().getResponse().getContentAsString();
        String repeatedId = com.jayway.jsonpath.JsonPath.read(postedRepeat, "$.id");
        mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/confirm", draftId)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-repeat-confirm-0001\",\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(repeatedId));
        String voidRequest = "{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-undo-latest-0001\","
                + "\"transactionId\":\"" + repeatedId + "\",\"version\":1}";
        mvc.perform(post("/internal/v1/telegram/transactions/latest/void")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(voidRequest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("voided"));
        mvc.perform(post("/internal/v1/telegram/transactions/latest/void")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(voidRequest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("voided"));
        mvc.perform(post("/internal/v1/telegram/summary")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN).contentType("application/json")
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expenseTotal").value("25.50"));
    }

    @Test
    void telegramIncomeDraftPreservesSourceAndDateWithoutChangingExpenseOrBudgetSpend() throws Exception {
        long telegramUserId = newTelegramUserId();
        String linkResponse = mvc.perform(post("/api/v1/me/telegram-link")
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = com.jayway.jsonpath.JsonPath.read(linkResponse, "$.code");
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk());
        String contextResponse = mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\"" + tenantId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(contextResponse, "$.token");
        String originalAdvice = AI_DRAFT_RESPONSE.get();
        try {
            AI_DRAFT_RESPONSE.set("""
                    {"provider":"test-ollama","type":"income","amount":"125000.00","categoryCode":"income",
                     "subcategoryCode":null,"description":"Зарплата за октябрь",
                     "occurredAt":"2026-10-02T09:30:00+03:00","modelVersion":"test-model-1",
                     "promptVersion":"transaction-draft.v2"}
                    """);
            var draftResponse = mvc.perform(post("/internal/v1/telegram/transaction-drafts")
                            .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                            .contentType("application/json")
                            .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-income-create-0001\","
                                    + "\"text\":\"Зарплата 125000\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("income"))
                    .andExpect(jsonPath("$.description").value("Зарплата за октябрь"))
                    .andExpect(jsonPath("$.occurredAt").value("2026-10-02T06:30:00Z"))
                    .andReturn().getResponse().getContentAsString();
            String draftId = com.jayway.jsonpath.JsonPath.read(draftResponse, "$.id");
            var memberAuth = jwt().jwt(jwt -> jwt.subject(subject));

            mvc.perform(post("/internal/v1/telegram/summary")
                            .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN).contentType("application/json")
                            .content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.incomeTotal").value("0.00"))
                    .andExpect(jsonPath("$.expenseTotal").value("0.00"))
                    .andExpect(jsonPath("$.transactionCount").value(0));
            mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(memberAuth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalMonthlySpent").value("0.00"));

            String confirm = "{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-income-confirm-0001\",\"version\":1}";
            mvc.perform(post("/internal/v1/telegram/transaction-drafts/{draftId}/confirm", draftId)
                            .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                            .contentType("application/json").content(confirm))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("income"))
                    .andExpect(jsonPath("$.amount").value("125000.00"))
                    .andExpect(jsonPath("$.description").value("Зарплата за октябрь"))
                    .andExpect(jsonPath("$.source").value("text_ai"))
                    .andExpect(jsonPath("$.occurredAt").value("2026-10-02T06:30:00Z"));
            mvc.perform(post("/internal/v1/telegram/summary")
                            .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN).contentType("application/json")
                            .content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.incomeTotal").value("125000.00"))
                    .andExpect(jsonPath("$.expenseTotal").value("0.00"))
                    .andExpect(jsonPath("$.transactionCount").value(1));
            mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(memberAuth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalMonthlySpent").value("0.00"));
        } finally {
            AI_DRAFT_RESPONSE.set(originalAdvice);
        }
    }

    @Test
    void telegramActorContextRejectsUnknownAndCrossTenantActors() throws Exception {
        long telegramUserId = newTelegramUserId();
        String request = "{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\""
                + UUID.randomUUID() + "\"}";
        mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(request))
                .andExpect(status().isNotFound());
        mvc.perform(post("/internal/v1/telegram/tenants")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void linkedLegacyPartnerUsesMemberScopedTelegramPermissions() throws Exception {
        String partnerSubject = "keycloak|legacy-partner-" + UUID.randomUUID();
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)",
                    userId, partnerSubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'member', ?)",
                    tenantId, partnerSubject, userId);
            jdbc.update("INSERT INTO member_profiles (tenant_id, user_id, display_name, timezone) "
                    + "VALUES (?, ?, 'Legacy partner', 'Europe/Moscow')", tenantId, userId);
            return null;
        });

        String linkResponse = mvc.perform(post("/api/v1/me/telegram-link")
                        .with(jwt().jwt(token -> token.subject(partnerSubject))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = com.jayway.jsonpath.JsonPath.read(linkResponse, "$.code");
        long telegramUserId = newTelegramUserId();
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk());

        mvc.perform(post("/internal/v1/telegram/tenants")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants[0].tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.tenants[0].role").value("member"))
                .andExpect(jsonPath("$.tenants[0].userId").doesNotExist());

        mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\""
                                + tenantId + "\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("member"))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("transaction.write.own")))
                .andExpect(jsonPath("$.permissions").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("transaction.write.any"))))
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    void browserCanIssueTelegramLinkCodeWithCsrfProtection() throws Exception {
        mvc.perform(post("/bff/me/telegram-link")
                        .with(oidcLogin().idToken(token -> token.subject(subject)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.expiresAt").isString());
    }

    @Test
    void issuingANewTelegramLinkCodeInvalidatesPreviousAndEnforcesRateLimit() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        String first = mvc.perform(post("/api/v1/me/telegram-link").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String firstCode = com.jayway.jsonpath.JsonPath.read(first, "$.code");
        mvc.perform(post("/api/v1/me/telegram-link").with(auth)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/me/telegram-link").with(auth)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/me/telegram-link").with(auth)).andExpect(status().isTooManyRequests());

        Integer invalidated = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
            return jdbc.queryForObject("SELECT count(*) FROM telegram_link_codes "
                    + "WHERE code_hash = ? AND invalidated_at IS NOT NULL", Integer.class,
                    TelegramLinkCode.hash(firstCode));
        });
        org.junit.jupiter.api.Assertions.assertEquals(1, invalidated);
    }

    @Test
    void failedTelegramLinkAttemptsArePersistedAndRateLimited() throws Exception {
        long telegramUserId = newTelegramUserId();
        String request = """
                {"code":"ABCD-EFGH-JKLM-NPQR","telegramUserId":%d}
                """.formatted(telegramUserId);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                            .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                            .contentType("application/json").content(request))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(request))
                .andExpect(status().isTooManyRequests());

        Integer attempts = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.telegram_link_service', 'true', true)", String.class);
            return jdbc.queryForObject("SELECT failed_attempts FROM telegram_link_attempts "
                    + "WHERE telegram_user_id = ?", Integer.class, telegramUserId);
        });
        org.junit.jupiter.api.Assertions.assertEquals(5, attempts);
    }

    @Test
    void memberCanUpdateOwnProfileWithoutChangingTenantMembership() throws Exception {
        String profilePath = "/api/v1/tenants/" + tenantId + "/profile/me";
        var auth = jwt().jwt(token -> token.subject(subject));
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "profile-history-retained-01")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"42.00","currency":"RUB","categoryCode":"food","description":"Before profile update","occurredAt":"2026-10-01T10:00:00Z"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(patch(profilePath).with(auth).contentType("application/json")
                        .content("{\"displayName\":\"Alex\",\"plannedIncome\":\"120000.00\","
                                + "\"onboardingState\":\"complete\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alex"))
                .andExpect(jsonPath("$.plannedIncome").value(120000.0))
                .andExpect(jsonPath("$.onboardingState").value("complete"));
        mvc.perform(get("/api/v1/me/tenants").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value(tenantId.toString()));
        mvc.perform(get("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].description").value("Before profile update"));
    }

    @Test
    void budgetAlertsAreReturnedOnlyWhenAnExpenseCrossesTheNinetyOrOneHundredPercentThreshold() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-alert-near-0001")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"18000.00","currency":"RUB","categoryCode":"еда","description":"Near budget alert","occurredAt":"2026-10-04T10:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.budgetAlerts.length()").value(1))
                .andExpect(jsonPath("$.budgetAlerts[0].budgetKey").value("еда"))
                .andExpect(jsonPath("$.budgetAlerts[0].threshold").value("near"))
                .andExpect(jsonPath("$.budgetAlerts[0].spent").value("18000.00"))
                .andExpect(jsonPath("$.budgetAlerts[0].limit").value("20000.00"));

        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-alert-full-0001")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"2000.00","currency":"RUB","categoryCode":"еда","description":"Full budget alert","occurredAt":"2026-10-04T11:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.budgetAlerts.length()").value(1))
                .andExpect(jsonPath("$.budgetAlerts[0].threshold").value("exceeded"))
                .andExpect(jsonPath("$.budgetAlerts[0].spent").value("20000.00"));

        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-alert-repeat-01")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"100.00","currency":"RUB","categoryCode":"еда","description":"No repeated alert","occurredAt":"2026-10-04T12:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.budgetAlerts").isEmpty());
    }

    @Test
    void totalBudgetAlertsAreEmittedWhenCategoryLimitIsDisabled() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-total-near-0001")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"50000.00","currency":"RUB","categoryCode":"жилье","description":"Total near threshold","occurredAt":"2026-10-04T10:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.budgetAlerts.length()").value(1))
                .andExpect(jsonPath("$.budgetAlerts[0].budgetKey").value("__total__"))
                .andExpect(jsonPath("$.budgetAlerts[0].threshold").value("near"))
                .andExpect(jsonPath("$.budgetAlerts[0].limit").value("55000.00"))
                .andExpect(jsonPath("$.budgetAlerts[0].spent").value("50000.00"));

        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-total-full-0001")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"5000.00","currency":"RUB","categoryCode":"транспорт","description":"Category and total thresholds","occurredAt":"2026-10-04T11:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.budgetAlerts.length()").value(2))
                .andExpect(jsonPath("$.budgetAlerts[0].budgetKey").value("транспорт"))
                .andExpect(jsonPath("$.budgetAlerts[0].threshold").value("exceeded"))
                .andExpect(jsonPath("$.budgetAlerts[1].budgetKey").value("__total__"))
                .andExpect(jsonPath("$.budgetAlerts[1].threshold").value("exceeded"))
                .andExpect(jsonPath("$.budgetAlerts[1].spent").value("55000.00"));
    }

    @Test
    void monthlyBudgetEndpointExposesEffectiveTenantAndPersonalLimits() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-spend-fixture-001")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"19800.00","currency":"RUB","categoryCode":"еда","description":"Budget fixture","occurredAt":"2026-10-01T10:00:00Z"}
                                """))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.monthlySpent['еда']").value("19800.00"))
                .andExpect(jsonPath("$.limitStatus['еда']").value("near"))
                .andExpect(jsonPath("$.limitStatus['долги']").value("disabled"))
                .andExpect(jsonPath("$.familyLimits['еда']").value("20000.00"))
                .andExpect(jsonPath("$.effectiveLimits['еда']").value("20000.00"))
                .andExpect(jsonPath("$.personalOverrides").isEmpty());
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-spend-full-001")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"200.00","currency":"RUB","categoryCode":"еда","description":"Full budget fixture","occurredAt":"2026-10-01T11:00:00Z"}
                                """))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlySpent['еда']").value("20000.00"))
                .andExpect(jsonPath("$.limitStatus['еда']").value("exceeded"));
        mvc.perform(put("/api/v1/tenants/{tenantId}/budgets/{budgetKey}", tenantId, "еда").with(auth)
                        .header("Idempotency-Key", "budget-change-family-001")
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("{\"scope\":\"family\",\"amount\":\"22000.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.familyLimits['еда']").value("22000.00"))
                .andExpect(jsonPath("$.familyVersions['еда']").value(1));
        mvc.perform(put("/api/v1/tenants/{tenantId}/budgets/{budgetKey}", tenantId, "еда").with(auth)
                        .header("Idempotency-Key", "budget-change-personal-001")
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("{\"scope\":\"personal\",\"amount\":\"18000.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalOverrides['еда']").value("18000.00"))
                .andExpect(jsonPath("$.effectiveLimits['еда']").value("18000.00"))
                .andExpect(jsonPath("$.personalOverrides['транспорт']").doesNotExist())
                .andExpect(jsonPath("$.effectiveLimits['транспорт']").value("5000.00"))
                .andExpect(jsonPath("$.personalVersions['еда']").value(1));
        mvc.perform(put("/api/v1/tenants/{tenantId}/budgets/{budgetKey}", tenantId, "еда").with(auth)
                        .header("Idempotency-Key", "budget-stale-family-001")
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("{\"scope\":\"family\",\"amount\":\"24000.00\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(delete("/api/v1/tenants/{tenantId}/budgets/personal-overrides", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-reset-personal-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalOverrides").isEmpty())
                .andExpect(jsonPath("$.effectiveLimits['еда']").value("22000.00"))
                .andExpect(jsonPath("$.personalVersions['еда']").value(2));
        mvc.perform(put("/api/v1/tenants/{tenantId}/budgets/{budgetKey}", tenantId, "еда").with(auth)
                        .header("Idempotency-Key", "budget-change-personal-002")
                        .header("If-Match", "\"2\"")
                        .contentType("application/json")
                        .content("{\"scope\":\"personal\",\"amount\":\"19000.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalOverrides['еда']").value("19000.00"))
                .andExpect(jsonPath("$.personalVersions['еда']").value(3));
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE memberships SET role = 'viewer' WHERE tenant_id = ? AND subject = ?", tenantId, subject);
            return null;
        });
        mvc.perform(put("/api/v1/tenants/{tenantId}/budgets/{budgetKey}", tenantId, "еда").with(auth)
                        .header("Idempotency-Key", "budget-viewer-family-001")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"scope\":\"family\",\"amount\":\"23000.00\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportsUseLocalInclusiveDatesSeparateTransactionTypesAndAggregateFamilyScope() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE member_profiles SET timezone = 'Europe/Moscow' WHERE tenant_id = ?", tenantId);
            UUID ownerId = jdbc.queryForObject("SELECT user_id FROM memberships WHERE tenant_id = ? AND subject = ?",
                    UUID.class, tenantId, subject);
            addReportTransaction(ownerId, subject, "expense", "10.00", "food", "2026-09-30T21:00:00Z");
            addReportTransaction(ownerId, subject, "expense", "50.00", "other", "2026-10-03T10:00:00Z");
            addReportTransaction(ownerId, subject, "income", "100.00", "income", "2026-10-02T10:00:00Z");
            addReportTransaction(ownerId, subject, "debt_payment", "20.00", "debt", "2026-10-03T12:00:00Z");
            addReportTransaction(ownerId, subject, "refund", "5.00", "refund", "2026-10-03T13:00:00Z");

            String familySubject = "keycloak|family-report-" + UUID.randomUUID();
            UUID familyUserId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)",
                    familyUserId, familySubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'member', ?)",
                    tenantId, familySubject, familyUserId);
            jdbc.update("INSERT INTO member_profiles (tenant_id, user_id, display_name, timezone) VALUES (?, ?, 'Family', 'Europe/Moscow')",
                    tenantId, familyUserId);
            addReportTransaction(familyUserId, familySubject, "expense", "25.00", "food", "2026-10-02T10:00:00Z");
            jdbc.update("INSERT INTO tenant_budgets (tenant_id, budget_key, period, amount, updated_by) "
                            + "VALUES (?, '__total__', 'monthly', 100000.00, ?)", tenantId, subject);
            jdbc.update("INSERT INTO tenant_budgets (tenant_id, owner_user_id, budget_key, period, amount, updated_by) "
                            + "VALUES (?, ?, '__total__', 'monthly', 20000.00, ?)", tenantId, ownerId, subject);
            return null;
        });

        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/period", tenantId).with(auth)
                        .param("period", "custom").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("personal"))
                .andExpect(jsonPath("$.fromDate").value("2026-10-01"))
                .andExpect(jsonPath("$.toDate").value("2026-10-03"))
                .andExpect(jsonPath("$.timezone").value("Europe/Moscow"))
                .andExpect(jsonPath("$.expenseTotal").value("60.00"))
                .andExpect(jsonPath("$.incomeTotal").value("100.00"))
                .andExpect(jsonPath("$.debtPaymentTotal").value("20.00"))
                .andExpect(jsonPath("$.refundTotal").value("5.00"))
                .andExpect(jsonPath("$.transactionCount").value(5))
                .andExpect(jsonPath("$.expenseByCategory.food").value("10.00"))
                .andExpect(jsonPath("$.expenseByDay['2026-10-01']").value("10.00"))
                .andExpect(jsonPath("$.expenseByDay['2026-10-02']").value("0.00"))
                .andExpect(jsonPath("$.expenseByDay['2026-10-03']").value("50.00"))
                .andExpect(jsonPath("$.weekendSharePercent").value(83));
        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/family", tenantId).with(auth)
                        .param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("family"))
                .andExpect(jsonPath("$.expenseTotal").value("85.00"))
                .andExpect(jsonPath("$.expenseByDay['2026-10-01']").value("10.00"))
                .andExpect(jsonPath("$.expenseByDay['2026-10-02']").value("25.00"))
                .andExpect(jsonPath("$.expenseByDay['2026-10-03']").value("50.00"))
                .andExpect(jsonPath("$.incomeTotal").value("100.00"))
                .andExpect(jsonPath("$.debtPaymentTotal").value("20.00"))
                .andExpect(jsonPath("$.transactionCount").value(6))
                .andExpect(jsonPath("$.weekendSharePercent").value(58));
        mvc.perform(get("/bff/tenants/{tenantId}/reports/family", tenantId).with(oidcLogin()
                        .idToken(token -> token.subject(subject)))
                        .param("period", "custom").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseTotal").value("85.00"))
                .andExpect(jsonPath("$.weekendSharePercent").value(58));
        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/period", tenantId).with(auth).param("period", "week"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromDate").value(LocalDate.now(ZoneId.of("Europe/Moscow")).minusDays(6).toString()))
                .andExpect(jsonPath("$.toDate").value(LocalDate.now(ZoneId.of("Europe/Moscow")).toString()));
        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/period", tenantId).with(auth).param("period", "90d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromDate").value(LocalDate.now(ZoneId.of("Europe/Moscow")).minusDays(89).toString()))
                .andExpect(jsonPath("$.toDate").value(LocalDate.now(ZoneId.of("Europe/Moscow")).toString()));
        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/period", tenantId).with(auth)
                        .param("period", "custom").param("from", "2026-10-04").param("to", "2026-10-03"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/family", tenantId).with(auth)
                        .param("period", "month").param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyBudgetLimit").value("100000.00"))
                .andExpect(jsonPath("$.monthlyBudgetRemaining").value("99920.00"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/month", tenantId).with(auth).param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyBudgetLimit").value("20000.00"))
                .andExpect(jsonPath("$.monthlyBudgetRemaining").value("19945.00"));
    }

    @Test
    void optionalSpendReportUsesConfirmedReceiptFactsAndOwnerScopedAllowedDecisions() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        List<UUID> itemIds = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE member_profiles SET timezone = 'Europe/Moscow' WHERE tenant_id = ?", tenantId);
            UUID ownerId = userIdFor(subject);
            String familySubject = "keycloak|waste-report-family-" + UUID.randomUUID();
            UUID familyUserId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)",
                    familyUserId, familySubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'member', ?)",
                    tenantId, familySubject, familyUserId);
            jdbc.update("INSERT INTO member_profiles (tenant_id, user_id, display_name, timezone) "
                    + "VALUES (?, ?, 'Family', 'Europe/Moscow')", tenantId, familyUserId);

            UUID personalTransaction = addReportTransaction(ownerId, subject, "expense", "100.00", "food",
                    "2026-10-01T10:00:00Z");
            UUID personalItem = addConfirmedReceiptItem(ownerId, subject, personalTransaction, "Чипсы", "100.00",
                    "harmful", "model", 1);
            UUID familyTransaction = addReportTransaction(familyUserId, familySubject, "expense", "200.00", "food",
                    "2026-10-02T10:00:00Z");
            UUID familyItem = addConfirmedReceiptItem(familyUserId, familySubject, familyTransaction, "Чипсы", "200.00",
                    "harmful", "unknown", 2);
            UUID unreviewedTransaction = addReportTransaction(familyUserId, familySubject, "expense", "50.00", "food",
                    "2026-10-03T10:00:00Z");
            UUID unreviewedItem = addConfirmedReceiptItem(familyUserId, familySubject, unreviewedTransaction, "Чай", null,
                    null, "unknown", 3);
            UUID outsideTransaction = addReportTransaction(ownerId, subject, "expense", "300.00", "food",
                    "2026-09-30T10:00:00Z");
            addConfirmedReceiptItem(ownerId, subject, outsideTransaction, "Кофе", "300.00", "harmful", "rule", 4);

            jdbc.update("INSERT INTO user_product_decisions (tenant_id, user_id, product_key, decision, version) "
                    + "VALUES (?, ?, 'чипсы', 'allowed', 4)", tenantId, ownerId);
            return List.of(personalItem, familyItem, unreviewedItem);
        });

        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/period", tenantId).with(auth)
                        .param("period", "custom").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waste.available").value(true))
                .andExpect(jsonPath("$.waste.optionalSpend").value("50.00"));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                ((Number) com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items.length()")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(itemIds.get(0).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[0].itemId"));
        org.junit.jupiter.api.Assertions.assertEquals("чипсы",
                com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[0].productKey"));
        org.junit.jupiter.api.Assertions.assertEquals(true,
                com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[0].allowed"));
        org.junit.jupiter.api.Assertions.assertEquals(4,
                ((Number) com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[0].decisionVersion")).intValue());

        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/family", tenantId).with(auth)
                        .param("period", "custom").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waste.available").value(true));
        org.junit.jupiter.api.Assertions.assertEquals(3,
                ((Number) com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items.length()")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(itemIds.get(0).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[0].itemId"));
        org.junit.jupiter.api.Assertions.assertEquals(true,
                com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[0].allowed"));
        org.junit.jupiter.api.Assertions.assertEquals(itemIds.get(1).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[1].itemId"));
        org.junit.jupiter.api.Assertions.assertEquals(false,
                com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[1].allowed"));
        org.junit.jupiter.api.Assertions.assertEquals(itemIds.get(2).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[2].itemId"));
        org.junit.jupiter.api.Assertions.assertNull(
                com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[2].verdict"));
        org.junit.jupiter.api.Assertions.assertNull(
                com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.items[2].lineSum"));

        WASTE_RESPONSE_STATUS.set(503);
        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/period", tenantId).with(auth)
                        .param("period", "custom").param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseTotal").exists())
                .andExpect(jsonPath("$.waste.available").value(false))
                .andExpect(jsonPath("$.waste.reasonCode").value("analytics_unavailable"))
                .andExpect(jsonPath("$.waste.optionalSpend").doesNotExist());
    }

    @Test
    void recalculationPreviewIsExplicitAndDoesNotChangeReceiptOrTransactionAmounts() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        when(recalculationImpactClient.calculate(any())).thenReturn(new Impact(
                "receipt-recalculation-impact.v1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "available", "complete", "123.45", "0.00", "-123.45"));
        UUID itemId = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            UUID transactionId = addReportTransaction(ownerId, subject, "expense", "123.45", "food",
                    "2026-10-01T10:00:00Z");
            return addConfirmedReceiptItem(ownerId, subject, transactionId, "Макароны перья", "123.45",
                    "unnecessary", "model", 1);
        });

        var previewResponse = mvc.perform(post("/api/v1/tenants/" + tenantId + "/review-recalculations/preview").with(auth)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").exists())
                .andExpect(jsonPath("$.algorithmVersion").value("receipt-basket.v1"))
                .andExpect(jsonPath("$.checked").value(1))
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].beforeVerdict").value("unnecessary"))
                .andExpect(jsonPath("$.changes[0].afterVerdict").value("neutral"))
                .andExpect(jsonPath("$.changes[0].beforeSource").value("model"))
                .andExpect(jsonPath("$.changes[0].afterSource").value("rule"))
                .andExpect(jsonPath("$.changes[0].lineSum").value("123.45"))
                .andExpect(jsonPath("$.impact.optionalSpendBefore").value("123.45"))
                .andExpect(jsonPath("$.impact.optionalSpendAfter").value("0.00"))
                .andExpect(jsonPath("$.impact.optionalSpendDelta").value("-123.45"))
                .andExpect(jsonPath("$.impact.currency").value("RUB"))
                .andReturn();
        verify(recalculationImpactClient).calculate(argThat(request -> request.before().items().size() == 1
                && "unnecessary".equals(request.before().items().get(0).verdict())
                && "neutral".equals(request.after().items().get(0).verdict())
                && "model".equals(request.before().items().get(0).verdictSource())
                && "rule".equals(request.after().items().get(0).verdictSource())));
        UUID runId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                previewResponse.getResponse().getContentAsString(), "$.runId"));

        Map<String, Object> beforeApply = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForMap("""
                    SELECT ri.verdict, ri.verdict_source, ri.line_sum, r.cash_total, t.amount
                    FROM receipt_items ri JOIN receipts r ON r.tenant_id = ri.tenant_id AND r.id = ri.receipt_id
                    JOIN transactions t ON t.tenant_id = r.tenant_id AND t.id = r.transaction_id
                    WHERE ri.tenant_id = ? AND ri.id = ?
                    """, tenantId, itemId);
        });
        org.junit.jupiter.api.Assertions.assertEquals("unnecessary", beforeApply.get("verdict"));
        org.junit.jupiter.api.Assertions.assertEquals("model", beforeApply.get("verdict_source"));
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("123.45"), beforeApply.get("line_sum"));
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("123.45"), beforeApply.get("cash_total"));
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("123.45"), beforeApply.get("amount"));

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/review-recalculations/apply").with(auth)
                        .contentType("application/json").content("{\"runId\":\"" + runId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.state").value("applied"))
                .andExpect(jsonPath("$.appliedCount").value(1))
                .andExpect(jsonPath("$.impact.optionalSpendDelta").value("-123.45"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/review-recalculations/{runId}", tenantId, runId).with(auth)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.state").value("applied"))
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].beforeVerdict").value("unnecessary"))
                .andExpect(jsonPath("$.changes[0].afterVerdict").value("neutral"))
                .andExpect(jsonPath("$.changes[0].lineSum").value("123.45"));
        mvc.perform(post("/api/v1/tenants/" + tenantId + "/review-recalculations/apply").with(auth)
                        .contentType("application/json").content("{\"runId\":\"" + runId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("applied"))
                .andExpect(jsonPath("$.appliedCount").value(1))
                .andExpect(jsonPath("$.impact.optionalSpendDelta").value("-123.45"));

        Map<String, Object> afterApply = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForMap("""
                    SELECT ri.verdict, ri.verdict_source, ri.line_sum, r.cash_total, t.amount
                    FROM receipt_items ri JOIN receipts r ON r.tenant_id = ri.tenant_id AND r.id = ri.receipt_id
                    JOIN transactions t ON t.tenant_id = r.tenant_id AND t.id = r.transaction_id
                    WHERE ri.tenant_id = ? AND ri.id = ?
                    """, tenantId, itemId);
        });
        org.junit.jupiter.api.Assertions.assertEquals("neutral", afterApply.get("verdict"));
        org.junit.jupiter.api.Assertions.assertEquals("rule", afterApply.get("verdict_source"));
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("123.45"), afterApply.get("line_sum"));
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("123.45"), afterApply.get("cash_total"));
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("123.45"), afterApply.get("amount"));
        Integer audits = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM receipt_reviews "
                    + "WHERE tenant_id = ? AND receipt_item_id = ? AND action = 'receipt.verdicts_recalculated'",
                    Integer.class, tenantId, itemId);
        });
        org.junit.jupiter.api.Assertions.assertEquals(1, audits);
    }

    @Test
    void recalculationApplyRejectsStaleReceiptLineWithoutPartialWrites() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        UUID itemId = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            UUID transactionId = addReportTransaction(ownerId, subject, "expense", "45.00", "food",
                    "2026-10-02T10:00:00Z");
            return addConfirmedReceiptItem(ownerId, subject, transactionId, "Энергетик", "45.00",
                    "neutral", "model", 1);
        });
        var preview = mvc.perform(post("/api/v1/tenants/" + tenantId + "/review-recalculations/preview").with(auth)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        UUID runId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                preview.getResponse().getContentAsString(), "$.runId"));

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE receipt_items SET advice = 'human edit', version = version + 1 "
                    + "WHERE tenant_id = ? AND id = ?", tenantId, itemId);
        });

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/review-recalculations/apply").with(auth)
                        .contentType("application/json").content("{\"runId\":\"" + runId + "\"}"))
                .andExpect(status().isPreconditionFailed());
        Map<String, Object> persisted = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForMap("""
                    SELECT ri.verdict, ri.verdict_source, ri.advice, ri.line_sum, r.cash_total, t.amount,
                           (SELECT state FROM recalculation_runs WHERE tenant_id = ? AND id = ?) AS run_state,
                           (SELECT count(*) FROM receipt_reviews WHERE tenant_id = ? AND receipt_item_id = ?) AS audit_count
                    FROM receipt_items ri JOIN receipts r ON r.tenant_id = ri.tenant_id AND r.id = ri.receipt_id
                    JOIN transactions t ON t.tenant_id = r.tenant_id AND t.id = r.transaction_id
                    WHERE ri.tenant_id = ? AND ri.id = ?
                    """, tenantId, runId, tenantId, itemId, tenantId, itemId);
        });
        org.junit.jupiter.api.Assertions.assertEquals("neutral", persisted.get("verdict"));
        org.junit.jupiter.api.Assertions.assertEquals("model", persisted.get("verdict_source"));
        org.junit.jupiter.api.Assertions.assertEquals("human edit", persisted.get("advice"));
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("45.00"), persisted.get("line_sum"));
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("45.00"), persisted.get("cash_total"));
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("45.00"), persisted.get("amount"));
        org.junit.jupiter.api.Assertions.assertEquals("previewed", persisted.get("run_state"));
        org.junit.jupiter.api.Assertions.assertEquals(0L, ((Number) persisted.get("audit_count")).longValue());
    }

    @Test
    void recalculationHistoryIsMemberScopedAndUsesStableCursorPages() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        mvc.perform(post("/api/v1/tenants/{tenantId}/review-recalculations/preview", tenantId).with(auth)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenants/{tenantId}/review-recalculations/preview", tenantId).with(auth)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());

        var first = mvc.perform(get("/api/v1/tenants/{tenantId}/review-recalculations", tenantId).with(auth)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].state").value("previewed"))
                .andExpect(jsonPath("$.runs[0].algorithmVersion").value("receipt-basket.v1"))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.nextCursor");
        String firstRun = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.runs[0].runId");

        mvc.perform(get("/api/v1/tenants/{tenantId}/review-recalculations", tenantId).with(auth)
                        .param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].runId").value(org.hamcrest.Matchers.not(firstRun)))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(get("/api/v1/tenants/{tenantId}/review-recalculations", tenantId).with(auth)
                        .param("limit", "1").param("cursor", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/tenants/{tenantId}/review-recalculations", tenantId)
                        .with(jwt().jwt(token -> token.subject("unlinked-user"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/tenants/{tenantId}/review-recalculations", tenantId).with(auth)
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/tenants/{tenantId}/review-recalculations/{runId}", tenantId, UUID.randomUUID())
                        .with(auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void optionalSpendReportStopsBeforeAnalyticsWhenReceiptLineLimitIsExceeded() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            UUID transactionId = addReportTransaction(ownerId, subject, "expense", "1.00", "food",
                    "2026-10-01T10:00:00Z");
            addConfirmedReceiptItem(ownerId, subject, transactionId, "Product", "1.00", "harmful", "model", 1);
            UUID receiptId = jdbc.queryForObject("SELECT id FROM receipts WHERE tenant_id = ? AND transaction_id = ?",
                    UUID.class, tenantId, transactionId);
            jdbc.update("""
                    INSERT INTO receipt_items (tenant_id, receipt_id, ordinal, name, quantity, line_sum)
                    SELECT ?, ?, ordinal, 'Product ' || ordinal, 1, 1.00
                    FROM generate_series(2, 50001) AS generated(ordinal)
                    """, tenantId, receiptId);
        });

        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/period", tenantId).with(auth)
                        .param("period", "custom").param("from", "2026-10-01").param("to", "2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseTotal").exists())
                .andExpect(jsonPath("$.waste.available").value(false))
                .andExpect(jsonPath("$.waste.reasonCode").value("too_many_items"))
                .andExpect(jsonPath("$.waste.completeness").value("partial"));
        org.junit.jupiter.api.Assertions.assertEquals("", LAST_WASTE_REQUEST.get());
    }

    @Test
    void telegramReportUsesTheSharedReportDtoAndScopesThroughItsActorContext() throws Exception {
        long telegramUserId = newTelegramUserId();
        String linkCode = mvc.perform(post("/api/v1/me/telegram-link")
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = com.jayway.jsonpath.JsonPath.read(linkCode, "$.code");
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk());
        String context = mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\""
                                + tenantId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(context, "$.token");

        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE member_profiles SET timezone = 'Europe/Moscow' WHERE tenant_id = ?", tenantId);
            UUID ownerId = userIdFor(subject);
            addReportTransaction(ownerId, subject, "expense", "10.00", "food", "2026-10-03T10:00:00Z");
            String familySubject = "keycloak|telegram-report-family-" + UUID.randomUUID();
            UUID familyUserId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)",
                    familyUserId, familySubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'member', ?)",
                    tenantId, familySubject, familyUserId);
            jdbc.update("INSERT INTO member_profiles (tenant_id, user_id, display_name, timezone) "
                    + "VALUES (?, ?, 'Family', 'Europe/Moscow')", tenantId, familyUserId);
            addReportTransaction(familyUserId, familySubject, "expense", "25.00", "food", "2026-10-03T11:00:00Z");
            return null;
        });

        String personalRequest = "{\"token\":\"" + token + "\",\"period\":\"custom\","
                + "\"from\":\"2026-10-03\",\"to\":\"2026-10-03\",\"scope\":\"personal\","
                + "\"tenantId\":\"" + UUID.randomUUID() + "\",\"subject\":\"forged\"}";
        mvc.perform(post("/internal/v1/telegram/report").contentType("application/json").content(personalRequest))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/telegram/report")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(personalRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("personal"))
                .andExpect(jsonPath("$.fromDate").value("2026-10-03"))
                .andExpect(jsonPath("$.toDate").value("2026-10-03"))
                .andExpect(jsonPath("$.expenseTotal").value("10.00"))
                .andExpect(jsonPath("$.expenseByCategory.food").value("10.00"));
        mvc.perform(post("/internal/v1/telegram/report")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"period\":\"custom\","
                                + "\"from\":\"2026-10-03\",\"to\":\"2026-10-03\",\"scope\":\"family\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("family"))
                .andExpect(jsonPath("$.expenseTotal").value("35.00"))
                .andExpect(jsonPath("$.expenseByDay['2026-10-03']").value("35.00"));
        mvc.perform(post("/internal/v1/telegram/report")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"period\":\"arbitrary\","
                                + "\"scope\":\"personal\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/v1/telegram/budgets")
                        .contentType("application/json").content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/telegram/budgets")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"tenantId\":\""
                                + UUID.randomUUID() + "\",\"subject\":\"forged\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.familyTotalLimit").value("55000.00"))
                .andExpect(jsonPath("$.effectiveTotalLimit").value("55000.00"))
                .andExpect(jsonPath("$.totalLimitStatus").value("normal"));
        mvc.perform(post("/internal/v1/telegram/budgets/еда/update")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-budget-update-01\","
                                + "\"scope\":\"personal\",\"amount\":\"5000.00\",\"version\":0,"
                                + "\"period\":\"monthly\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalOverrides['еда']").value("5000.00"));
        mvc.perform(post("/internal/v1/telegram/budgets/reset")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"idempotencyKey\":\"telegram-budget-reset-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalOverrides['еда']").doesNotExist());
    }

    private UUID addReportTransaction(UUID ownerId, String ownerSubject, String type, String amount,
                                      String category, String occurredAt) {
        return jdbc.queryForObject("""
                INSERT INTO transactions (tenant_id, owner_subject, owner_user_id, type, amount, currency,
                    category_code, description, source, occurred_at)
                VALUES (?, ?, ?, ?, ?, 'RUB', ?, '', 'test', ?)
                RETURNING id
                """, UUID.class, tenantId, ownerSubject, ownerId, type, new java.math.BigDecimal(amount), category,
                java.sql.Timestamp.from(Instant.parse(occurredAt)));
    }

    private UUID addConfirmedReceiptItem(UUID ownerId, String ownerSubject, UUID transactionId,
                                         String name, String lineSum, String verdict, String source, int ordinal) {
        String amount = lineSum == null ? "50.00" : lineSum;
        LocalDate receiptDate = jdbc.queryForObject(
                "SELECT (occurred_at AT TIME ZONE 'UTC')::date FROM transactions WHERE tenant_id = ? AND id = ?",
                LocalDate.class, tenantId, transactionId);
        UUID receiptId = jdbc.queryForObject("""
                INSERT INTO receipts (tenant_id, owner_user_id, owner_subject, transaction_id, state,
                    cash_total, items_total, receipt_date, selected_reader, version, create_idempotency_key,
                    create_request_hash, confirm_idempotency_key, confirmed_at)
                VALUES (?, ?, ?, ?, 'confirmed', ?, ?, ?, 'manual', 2, ?, repeat('0', 64), ?, now())
                RETURNING id
                """, UUID.class, tenantId, ownerId, ownerSubject, transactionId,
                new java.math.BigDecimal(amount), lineSum == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(lineSum),
                receiptDate, "waste-report-create-" + UUID.randomUUID(), "waste-report-confirm-" + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO receipt_items (tenant_id, receipt_id, ordinal, name, quantity, line_sum, verdict,
                    verdict_source, version)
                VALUES (?, ?, ?, ?, 1, ?::numeric, ?, ?, ?)
                RETURNING id
                """, UUID.class, tenantId, receiptId, ordinal, name, lineSum, verdict, source, ordinal);
    }

    @Test
    void debtPaymentIsIdempotentAndVoidingItRestoresTheExactBalanceEffect() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post("/api/v1/tenants/{tenantId}/debts", tenantId).with(auth)
                        .header("Idempotency-Key", "debt-create-request-0001")
                        .contentType("application/json")
                        .content("{\"name\":\"Credit card\",\"openingBalance\":\"1000.00\",\"interestRate\":\"12.00\",\"minimumPayment\":\"100.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentBalance").value("1000.00"))
                .andReturn();
        String debtId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        var paymentPath = "/api/v1/tenants/" + tenantId + "/debts/" + debtId + "/payments";
        String payment = "{\"amount\":\"300.00\",\"occurredAt\":\"2026-10-01T10:00:00Z\"}";
        var paid = mvc.perform(post(paymentPath).with(auth)
                        .header("Idempotency-Key", "debt-payment-request-0001")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content(payment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceReduction").value("300.00"))
                .andExpect(jsonPath("$.debt.currentBalance").value("700.00"))
                .andReturn();
        String transactionId = com.jayway.jsonpath.JsonPath.read(paid.getResponse().getContentAsString(), "$.transactionId");
        mvc.perform(post(paymentPath).with(auth)
                        .header("Idempotency-Key", "debt-payment-request-0001")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content(payment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debt.currentBalance").value("700.00"));
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions/{transactionId}/void", tenantId, transactionId).with(auth)
                        .header("Idempotency-Key", "debt-payment-void-request-001")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("voided"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/debts/{debtId}", tenantId, debtId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value("1000.00"))
                .andExpect(jsonPath("$.status").value("open"));
        var overpayment = mvc.perform(post(paymentPath).with(auth)
                        .header("Idempotency-Key", "debt-payment-request-0002")
                        .header("If-Match", "\"3\"")
                        .contentType("application/json")
                        .content("{\"amount\":\"900.00\",\"occurredAt\":\"2026-10-01T11:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceReduction").value("900.00"))
                .andExpect(jsonPath("$.debt.currentBalance").value("100.00"))
                .andReturn();
        String overpaymentTransactionId = com.jayway.jsonpath.JsonPath.read(
                overpayment.getResponse().getContentAsString(), "$.transactionId");
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions/{transactionId}/void", tenantId, overpaymentTransactionId).with(auth)
                        .header("Idempotency-Key", "debt-payment-void-request-002")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenants/{tenantId}/debts/{debtId}", tenantId, debtId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value("1000.00"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth).param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incomeTotal").value("0.00"))
                .andExpect(jsonPath("$.expenseTotal").value("0.00"))
                .andExpect(jsonPath("$.transactionCount").value(0));
    }

    @Test
    void editingDebtPaymentReversesPreviousEffectBeforeApplyingUpdatedAmount() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post("/api/v1/tenants/{tenantId}/debts", tenantId).with(auth)
                        .header("Idempotency-Key", "debt-edit-create-0001")
                        .contentType("application/json")
                        .content("{\"name\":\"Edit card\",\"openingBalance\":\"1000.00\","
                                + "\"interestRate\":\"12.00\",\"minimumPayment\":\"100.00\"}"))
                .andExpect(status().isCreated()).andReturn();
        String debtId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        var payment = mvc.perform(post("/api/v1/tenants/{tenantId}/debts/{debtId}/payments", tenantId, debtId).with(auth)
                        .header("Idempotency-Key", "debt-edit-payment-0001")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"amount\":\"300.00\",\"occurredAt\":\"2026-10-01T10:00:00Z\"}"))
                .andExpect(status().isOk()).andReturn();
        String transactionId = com.jayway.jsonpath.JsonPath.read(payment.getResponse().getContentAsString(), "$.transactionId");

        mvc.perform(patch("/api/v1/tenants/{tenantId}/transactions/{transactionId}", tenantId, transactionId).with(auth)
                        .header("Idempotency-Key", "debt-edit-update-0001")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"type\":\"debt_payment\",\"amount\":\"250.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"долги\",\"subcategoryCode\":null,\"description\":\"Updated payment\","
                                + "\"source\":\"web\",\"occurredAt\":\"2026-10-02T10:00:00Z\","
                                + "\"debtId\":\"" + debtId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("debt_payment"))
                .andExpect(jsonPath("$.amount").value("250.00"))
                .andExpect(jsonPath("$.debtId").value(debtId));
        mvc.perform(get("/api/v1/tenants/{tenantId}/debts/{debtId}", tenantId, debtId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentBalance").value("750.00"));
    }

    @Test
    void rollingSevenDayFoodBudgetUsesLocalCalendarWindowAndIndependentVersion() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        Instant now = Instant.now();
        String recentAt = now.minusSeconds(6 * 24 * 60 * 60).toString();
        String oldAt = now.minusSeconds(7 * 24 * 60 * 60).toString();
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "rolling-food-recent-0001")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"140.00","currency":"RUB","categoryCode":"еда","description":"Recent food","occurredAt":"%s"}
                                """.formatted(recentAt)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "rolling-food-outside-001")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"900.00","currency":"RUB","categoryCode":"еда","description":"Outside window","occurredAt":"%s"}
                                """.formatted(oldAt)))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolling7FoodSpent").value("140.00"))
                .andExpect(jsonPath("$.rolling7FoodLimitStatus").value("disabled"));
        mvc.perform(put("/api/v1/tenants/{tenantId}/budgets/{budgetKey}", tenantId, "еда").with(auth)
                        .header("Idempotency-Key", "rolling-food-budget-set-1")
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("{\"scope\":\"family\",\"amount\":\"150.00\",\"period\":\"rolling7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolling7FoodLimit").value("150.00"))
                .andExpect(jsonPath("$.effectiveRolling7FoodLimit").value("150.00"))
                .andExpect(jsonPath("$.rolling7FoodLimitStatus").value("near"))
                .andExpect(jsonPath("$.familyRolling7FoodVersion").value(1));
    }

    @Test
    void debtBalanceCanBeAdjustedWithVersionAuditAndIdempotency() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post("/api/v1/tenants/{tenantId}/debts", tenantId).with(auth)
                        .header("Idempotency-Key", "debt-adjust-create-00001")
                        .contentType("application/json")
                        .content("{\"name\":\"Loan\",\"openingBalance\":\"1000.00\"}"))
                .andExpect(status().isCreated()).andReturn();
        String debtId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String path = "/api/v1/tenants/" + tenantId + "/debts/" + debtId + "/balance";
        var adjusted = mvc.perform(put(path).with(auth)
                        .header("Idempotency-Key", "debt-adjust-request-0001")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{\"currentBalance\":\"1250.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value("1250.00"))
                .andExpect(jsonPath("$.version").value(2))
                .andReturn();
        mvc.perform(put(path).with(auth)
                        .header("Idempotency-Key", "debt-adjust-request-0001")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{\"currentBalance\":\"1250.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(put(path).with(auth)
                        .header("Idempotency-Key", "debt-adjust-request-0002")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{\"currentBalance\":\"900.00\"}"))
                .andExpect(status().isPreconditionFailed());
        String debtIdValue = debtId;
        long auditCount = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
            return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND entity_id = ? AND action = 'debt.balance_adjusted'",
                    Long.class, tenantId, UUID.fromString(debtIdValue));
        });
        org.junit.jupiter.api.Assertions.assertEquals(1, auditCount);
    }

    @Test
    void concurrentDebtPaymentsWithSameVersionOnlyApplyOnce() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post("/api/v1/tenants/{tenantId}/debts", tenantId).with(auth)
                        .header("Idempotency-Key", "debt-concurrent-create-01")
                        .contentType("application/json")
                        .content("{\"name\":\"Concurrent loan\",\"openingBalance\":\"1000.00\"}"))
                .andExpect(status().isCreated()).andReturn();
        String debtId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String path = "/api/v1/tenants/" + tenantId + "/debts/" + debtId + "/payments";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = CompletableFuture.supplyAsync(() -> concurrentPaymentStatus(path, "debt-concurrent-payment-01", auth, ready, start), executor);
            var second = CompletableFuture.supplyAsync(() -> concurrentPaymentStatus(path, "debt-concurrent-payment-02", auth, ready, start), executor);
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int firstStatus = first.get(10, TimeUnit.SECONDS);
            int secondStatus = second.get(10, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertTrue((firstStatus == 200 && secondStatus == 412)
                    || (firstStatus == 412 && secondStatus == 200), "exactly one payment may apply");
        } finally {
            executor.shutdownNow();
        }
        mvc.perform(get("/api/v1/tenants/{tenantId}/debts/{debtId}", tenantId, debtId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value("900.00"))
                .andExpect(jsonPath("$.version").value(2));
    }

    private int concurrentPaymentStatus(String path, String idempotencyKey, RequestPostProcessor auth,
                                        CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            return mvc.perform(post(path).with(auth)
                            .header("Idempotency-Key", idempotencyKey).header("If-Match", "\"1\"")
                            .contentType("application/json")
                            .content("{\"amount\":\"100.00\",\"occurredAt\":\"2026-10-01T10:00:00Z\"}"))
                    .andReturn().getResponse().getStatus();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void budgetProposalIsOnlyAppliedAfterExplicitActionAndRejectsStaleBase() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var proposal = mvc.perform(post("/api/v1/tenants/{tenantId}/budget-proposals", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-proposal-request-0001")
                        .contentType("application/json").content("{\"monthlyIncome\":\"100000.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.totalLimit").value("70000.00"))
                .andReturn();
        String proposalId = com.jayway.jsonpath.JsonPath.read(proposal.getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/tenants/{tenantId}/budget-proposals", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-proposal-request-0001")
                        .contentType("application/json").content("{\"monthlyIncome\":\"100000.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(proposalId));
        mvc.perform(post("/api/v1/tenants/{tenantId}/budget-proposals", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-proposal-request-0001")
                        .contentType("application/json").content("{\"monthlyIncome\":\"110000.00\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.familyTotalLimit").value("55000.00"));
        mvc.perform(post("/api/v1/tenants/{tenantId}/budget-proposals/{proposalId}/apply", tenantId, proposalId).with(auth)
                        .header("Idempotency-Key", "budget-proposal-apply-0001"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.familyTotalLimit").value("70000.00"));
        mvc.perform(post("/api/v1/tenants/{tenantId}/budget-proposals/{proposalId}/apply", tenantId, proposalId).with(auth)
                        .header("Idempotency-Key", "budget-proposal-apply-0001"))
                .andExpect(status().isOk());

        var staleProposal = mvc.perform(post("/api/v1/tenants/{tenantId}/budget-proposals", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-proposal-request-0002")
                        .contentType("application/json").content("{\"monthlyIncome\":\"120000.00\"}"))
                .andExpect(status().isCreated()).andReturn();
        String staleId = com.jayway.jsonpath.JsonPath.read(staleProposal.getResponse().getContentAsString(), "$.id");
        mvc.perform(put("/api/v1/tenants/{tenantId}/budgets/{budgetKey}", tenantId, "еда").with(auth)
                        .header("Idempotency-Key", "budget-proposal-intervening-edit")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{\"scope\":\"family\",\"amount\":\"777.00\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenants/{tenantId}/budget-proposals/{proposalId}/apply", tenantId, staleId).with(auth)
                        .header("Idempotency-Key", "budget-proposal-apply-0002"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void merchantHypothesesAreCachedUntilAUserRuleOverridesThemAndApplyOnImport() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 test statement".getBytes(StandardCharsets.US_ASCII));
        AI_CALLS.set(0);
        LAST_MERCHANT_REQUEST.set("");

        String first = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId).file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String firstId = com.jayway.jsonpath.JsonPath.read(first, "$.id");
        mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/classify", tenantId, firstId).with(auth)
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.rows[0].suggestedCategoryCode").value("еда"))
                .andExpect(jsonPath("$.rows[0].categoryConfidence").value("0.930"))
                .andExpect(jsonPath("$.rows[0].categorySource").value("model"));
        org.junit.jupiter.api.Assertions.assertEquals(1, AI_CALLS.get());
        org.junit.jupiter.api.Assertions.assertTrue(LAST_MERCHANT_REQUEST.get().contains("\"merchants\":[\"market\"]"));
        org.junit.jupiter.api.Assertions.assertFalse(LAST_MERCHANT_REQUEST.get().contains("Payment"));
        org.junit.jupiter.api.Assertions.assertFalse(LAST_MERCHANT_REQUEST.get().contains("12.00"));

        String second = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId).file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String secondId = com.jayway.jsonpath.JsonPath.read(second, "$.id");
        String secondRowId = com.jayway.jsonpath.JsonPath.read(second, "$.rows[0].id");
        mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/classify", tenantId, secondId).with(auth)
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].suggestedCategoryCode").value("еда"));
        org.junit.jupiter.api.Assertions.assertEquals(1, AI_CALLS.get(), "the 90-day tenant-member cache avoids a second inference");

        mvc.perform(put("/api/v1/tenants/{tenantId}/imports/{importId}/rows/{rowId}/category",
                        tenantId, secondId, secondRowId).with(auth)
                        .header("If-Match", "\"2\"").contentType("application/json")
                        .content("{\"categoryCode\":\"еда\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.rows[0].categoryCode").value("еда"))
                .andExpect(jsonPath("$.rows[0].categorySource").value("human"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/merchant-mappings", tenantId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mappings[0].normalizedMerchant").value("market"))
                .andExpect(jsonPath("$.mappings[0].categoryCode").value("еда"));

        String third = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId).file(file).with(auth))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rows[0].categoryCode").value("еда"))
                .andExpect(jsonPath("$.rows[0].categorySource").value("mapping"))
                .andReturn().getResponse().getContentAsString();
        String thirdId = com.jayway.jsonpath.JsonPath.read(third, "$.id");
        String committed = mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, thirdId)
                        .with(auth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "merchant-map-confirm-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String transactionId = com.jayway.jsonpath.JsonPath.read(committed, "$.rows[0].transactionId");
        String persistedCategory = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT category_code FROM transactions WHERE tenant_id = ? AND id = ?",
                    String.class, tenantId, UUID.fromString(transactionId));
        });
        org.junit.jupiter.api.Assertions.assertEquals("еда", persistedCategory);
    }

    @Test
    void merchantReclassificationRequiresReviewedExactRowsAndUpdatesAtomically() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 reclassification test".getBytes(StandardCharsets.US_ASCII));
        String staged = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId).file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String importId = com.jayway.jsonpath.JsonPath.read(staged, "$.id");
        String committed = mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, importId)
                        .with(auth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "merchant-reclass-import-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String transactionId = com.jayway.jsonpath.JsonPath.read(committed, "$.rows[0].transactionId");
        mvc.perform(put("/api/v1/tenants/{tenantId}/merchant-mappings", tenantId).with(auth)
                        .contentType("application/json").content("{\"merchant\":\"Market\",\"categoryCode\":\"еда\"}"))
                .andExpect(status().isOk());

        String preview = mvc.perform(post("/api/v1/tenants/{tenantId}/merchant-reclassifications/preview", tenantId)
                        .with(auth).contentType("application/json").content("{\"merchant\":\" MARKET \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.normalizedMerchant").value("market"))
                .andExpect(jsonPath("$.categoryCode").value("еда"))
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].transactionId").value(transactionId))
                .andExpect(jsonPath("$.candidates[0].currentCategoryCode").value("прочее"))
                .andReturn().getResponse().getContentAsString();
        Number versionValue = com.jayway.jsonpath.JsonPath.read(preview, "$.candidates[0].version");
        long version = versionValue.longValue();
        String applyBody = "{\"merchant\":\"Market\",\"categoryCode\":\"еда\",\"candidates\":[{"
                + "\"transactionId\":\"" + transactionId + "\",\"version\":" + version
                + ",\"currentCategoryCode\":\"прочее\"}]}";
        mvc.perform(post("/api/v1/tenants/{tenantId}/merchant-reclassifications/apply", tenantId).with(auth)
                        .header("Idempotency-Key", "merchant-reclass-stale-0001")
                        .contentType("application/json")
                        .content(applyBody.replace("\"currentCategoryCode\":\"прочее\"",
                                "\"currentCategoryCode\":\"еда\"")))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post("/api/v1/tenants/{tenantId}/merchant-reclassifications/apply", tenantId).with(auth)
                        .header("Idempotency-Key", "merchant-reclass-apply-0001")
                        .contentType("application/json").content(applyBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changedCount").value(1))
                .andExpect(jsonPath("$.transactionIds[0]").value(transactionId));
        mvc.perform(post("/api/v1/tenants/{tenantId}/merchant-reclassifications/apply", tenantId).with(auth)
                        .header("Idempotency-Key", "merchant-reclass-apply-0001")
                        .contentType("application/json").content(applyBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changedCount").value(1));
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals("еда", jdbc.queryForObject(
                    "SELECT category_code FROM transactions WHERE tenant_id = ? AND id = ?", String.class,
                    tenantId, UUID.fromString(transactionId)));
            org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
                    "SELECT version FROM transactions WHERE tenant_id = ? AND id = ?", Long.class,
                    tenantId, UUID.fromString(transactionId)));
            org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
                    "SELECT r.reclassification_version FROM bank_import_rows r WHERE r.tenant_id = ? AND r.transaction_id = ?",
                    Long.class, tenantId, UUID.fromString(transactionId)));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND aggregate_id = ? AND event_type = 'transaction.updated'",
                    Integer.class, tenantId, UUID.fromString(transactionId)));
            return null;
        });
    }

    @Test
    void merchantReclassificationRejectsAUserEditedTransactionWithoutChangingItsCategory() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 manual edit race".getBytes(StandardCharsets.US_ASCII));
        String staged = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId).file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String importId = com.jayway.jsonpath.JsonPath.read(staged, "$.id");
        String committed = mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, importId)
                        .with(auth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "merchant-reclass-edit-import-01")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String transactionId = com.jayway.jsonpath.JsonPath.read(committed, "$.rows[0].transactionId");
        mvc.perform(put("/api/v1/tenants/{tenantId}/merchant-mappings", tenantId).with(auth)
                        .contentType("application/json").content("{\"merchant\":\"Market\",\"categoryCode\":\"еда\"}"))
                .andExpect(status().isOk());
        String preview = mvc.perform(post("/api/v1/tenants/{tenantId}/merchant-reclassifications/preview", tenantId)
                        .with(auth).contentType("application/json").content("{\"merchant\":\"Market\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Number versionValue = com.jayway.jsonpath.JsonPath.read(preview, "$.candidates[0].version");
        long version = versionValue.longValue();

        String transaction = mvc.perform(get("/api/v1/tenants/{tenantId}/transactions/{transactionId}",
                        tenantId, transactionId).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String occurredAt = com.jayway.jsonpath.JsonPath.read(transaction, "$.occurredAt");
        String updateBody = "{\"type\":\"expense\",\"amount\":\"12.00\",\"currency\":\"RUB\","
                + "\"categoryCode\":\"прочее\",\"subcategoryCode\":null,\"description\":\"Ручная правка\","
                + "\"source\":\"bank_import\",\"occurredAt\":\"" + occurredAt
                + "\",\"accountId\":null,\"debtId\":null,\"ownerUserId\":null}";
        mvc.perform(patch("/api/v1/tenants/{tenantId}/transactions/{transactionId}", tenantId, transactionId)
                        .with(auth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "merchant-reclass-user-edit-01")
                        .contentType("application/json").content(updateBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        String applyBody = "{\"merchant\":\"Market\",\"categoryCode\":\"еда\",\"candidates\":[{"
                + "\"transactionId\":\"" + transactionId + "\",\"version\":" + version
                + ",\"currentCategoryCode\":\"прочее\"}]}";
        mvc.perform(post("/api/v1/tenants/{tenantId}/merchant-reclassifications/apply", tenantId).with(auth)
                        .header("Idempotency-Key", "merchant-reclass-after-edit-01")
                        .contentType("application/json").content(applyBody))
                .andExpect(status().isPreconditionFailed());

        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals("прочее", jdbc.queryForObject(
                    "SELECT category_code FROM transactions WHERE tenant_id = ? AND id = ?", String.class,
                    tenantId, UUID.fromString(transactionId)));
            org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
                    "SELECT version FROM transactions WHERE tenant_id = ? AND id = ?", Long.class,
                    tenantId, UUID.fromString(transactionId)));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT reclassification_version FROM bank_import_rows WHERE tenant_id = ? AND transaction_id = ?",
                    Long.class, tenantId, UUID.fromString(transactionId)));
            return null;
        });
    }

    @Test
    void importConfirmationWaitsForTheMerchantReclassificationLock() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 merchant lock test".getBytes(StandardCharsets.US_ASCII));
        String staged = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId).file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String importId = com.jayway.jsonpath.JsonPath.read(staged, "$.id");
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch confirmStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var holder = CompletableFuture.runAsync(() -> transactions.execute(status -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                UUID userId = jdbc.queryForObject("SELECT user_id FROM memberships WHERE tenant_id = ? AND subject = ?",
                        UUID.class, tenantId, subject);
                MerchantCategoryLocks.acquire(jdbc, tenantId, userId, "market");
                lockHeld.countDown();
                try {
                    if (!releaseLock.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test did not release merchant lock");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            }), executor);
            org.junit.jupiter.api.Assertions.assertTrue(lockHeld.await(5, TimeUnit.SECONDS));
            var confirmation = CompletableFuture.supplyAsync(() -> {
                confirmStarted.countDown();
                try {
                    return mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, importId)
                                    .with(auth).header("If-Match", "\"1\"")
                                    .header("Idempotency-Key", "merchant-lock-confirm-0001")
                                    .contentType("application/json").content("{\"confirmed\":true}"))
                            .andReturn().getResponse().getStatus();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }, executor);
            org.junit.jupiter.api.Assertions.assertTrue(confirmStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(250);
            org.junit.jupiter.api.Assertions.assertFalse(confirmation.isDone(),
                    "import must wait until a reviewed merchant-category operation releases its lock");
            releaseLock.countDown();
            org.junit.jupiter.api.Assertions.assertEquals(200, confirmation.get(10, TimeUnit.SECONDS));
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void merchantReclassificationDoesNotReplaceAnExplicitImportRowCategory() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 explicit category".getBytes(StandardCharsets.US_ASCII));
        String staged = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId).file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String importId = com.jayway.jsonpath.JsonPath.read(staged, "$.id");
        String rowId = com.jayway.jsonpath.JsonPath.read(staged, "$.rows[0].id");
        mvc.perform(put("/api/v1/tenants/{tenantId}/imports/{importId}/rows/{rowId}/category",
                        tenantId, importId, rowId).with(auth)
                        .header("If-Match", "\"1\"").contentType("application/json")
                        .content("{\"categoryCode\":\"транспорт\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].categoryCode").value("транспорт"));
        mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, importId)
                        .with(auth).header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "merchant-row-category-import-01")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/tenants/{tenantId}/merchant-mappings", tenantId).with(auth)
                        .contentType("application/json").content("{\"merchant\":\"Market\",\"categoryCode\":\"еда\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenants/{tenantId}/merchant-reclassifications/preview", tenantId).with(auth)
                        .contentType("application/json").content("{\"merchant\":\"Market\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(0));
    }

    @Test
    void merchantReclassificationPreviewAndApplyCannotReachAnotherMembersImport() throws Exception {
        String otherSubject = "keycloak|reclassification-other-" + UUID.randomUUID();
        UUID otherUser = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)",
                    userId, otherSubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'member', ?)",
                    tenantId, otherSubject, userId);
            jdbc.update("INSERT INTO member_profiles (tenant_id, user_id, display_name) VALUES (?, ?, 'Other member')",
                    tenantId, userId);
            return userId;
        });
        var otherAuth = jwt().jwt(token -> token.subject(otherSubject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 other member import".getBytes(StandardCharsets.US_ASCII));
        String staged = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId).file(file).with(otherAuth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String importId = com.jayway.jsonpath.JsonPath.read(staged, "$.id");
        String committed = mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, importId)
                        .with(otherAuth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "other-member-reclass-import-01")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String transactionId = com.jayway.jsonpath.JsonPath.read(committed, "$.rows[0].transactionId");
        var auth = jwt().jwt(token -> token.subject(subject));
        mvc.perform(put("/api/v1/tenants/{tenantId}/merchant-mappings", tenantId).with(auth)
                        .contentType("application/json").content("{\"merchant\":\"Market\",\"categoryCode\":\"еда\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenants/{tenantId}/merchant-reclassifications/preview", tenantId).with(auth)
                        .contentType("application/json").content("{\"merchant\":\"Market\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(0));
        mvc.perform(post("/api/v1/tenants/{tenantId}/merchant-reclassifications/apply", tenantId).with(auth)
                        .header("Idempotency-Key", "other-member-reclass-apply-01")
                        .contentType("application/json")
                        .content("{\"merchant\":\"Market\",\"categoryCode\":\"еда\",\"candidates\":[{"
                                + "\"transactionId\":\"" + transactionId
                                + "\",\"version\":1,\"currentCategoryCode\":\"прочее\"}]}"))
                .andExpect(status().isPreconditionFailed());
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals("прочее", jdbc.queryForObject(
                    "SELECT category_code FROM transactions WHERE tenant_id = ? AND id = ?", String.class,
                    tenantId, UUID.fromString(transactionId)));
            org.junit.jupiter.api.Assertions.assertEquals(otherUser, jdbc.queryForObject(
                    "SELECT owner_user_id FROM transactions WHERE tenant_id = ? AND id = ?", UUID.class,
                    tenantId, UUID.fromString(transactionId)));
            return null;
        });
    }

    @Test
    void browserMerchantReclassificationRequiresCsrfForPreviewAndApply() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 browser reclassification".getBytes(StandardCharsets.US_ASCII));
        String staged = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId).file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String importId = com.jayway.jsonpath.JsonPath.read(staged, "$.id");
        mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, importId)
                        .with(auth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "browser-reclass-import-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/tenants/{tenantId}/merchant-mappings", tenantId).with(auth)
                        .contentType("application/json").content("{\"merchant\":\"Market\",\"categoryCode\":\"еда\"}"))
                .andExpect(status().isOk());
        var browser = oidcLogin().idToken(token -> token.subject(subject));
        mvc.perform(post("/bff/tenants/{tenantId}/merchant-reclassifications/preview", tenantId).with(browser)
                        .contentType("application/json").content("{\"merchant\":\"Market\"}"))
                .andExpect(status().isForbidden());
        String preview = mvc.perform(post("/bff/tenants/{tenantId}/merchant-reclassifications/preview", tenantId)
                        .with(browser).with(csrf()).contentType("application/json")
                        .content("{\"merchant\":\"Market\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String transactionId = com.jayway.jsonpath.JsonPath.read(preview, "$.candidates[0].transactionId");
        Number versionValue = com.jayway.jsonpath.JsonPath.read(preview, "$.candidates[0].version");
        String applyBody = "{\"merchant\":\"Market\",\"categoryCode\":\"еда\",\"candidates\":[{"
                + "\"transactionId\":\"" + transactionId + "\",\"version\":" + versionValue.longValue()
                + ",\"currentCategoryCode\":\"прочее\"}]}";
        mvc.perform(post("/bff/tenants/{tenantId}/merchant-reclassifications/apply", tenantId).with(browser)
                        .header("Idempotency-Key", "browser-reclass-apply-0001")
                        .contentType("application/json").content(applyBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/bff/tenants/{tenantId}/merchant-reclassifications/apply", tenantId)
                        .with(browser).with(csrf()).header("Idempotency-Key", "browser-reclass-apply-0001")
                        .contentType("application/json").content(applyBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changedCount").value(1));
    }

    @Test
    void statementImportStagesVisiblePreviewAndAppliesOnlyExplicitRowSelections() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 test statement".getBytes(StandardCharsets.US_ASCII));

        var created = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId).file(file).with(auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quality").value("valid"))
                .andExpect(jsonPath("$.state").value("needs_review"))
                .andExpect(jsonPath("$.includedCount").value(1))
                .andExpect(jsonPath("$.excludedCount").value(1))
                .andExpect(jsonPath("$.expenseTotal").value("12.00"))
                .andExpect(jsonPath("$.excludedTotal").value("1.00"))
                .andExpect(jsonPath("$.rows[1].exclusionReason").value("bank_fee"))
                .andReturn();
        String body = created.getResponse().getContentAsString();
        String importId = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        String feeRowId = com.jayway.jsonpath.JsonPath.read(body, "$.rows[1].id");

        mvc.perform(patch("/api/v1/tenants/{tenantId}/imports/{importId}/rows/{rowId}",
                        tenantId, importId, feeRowId).with(auth)
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{\"transactionType\":\"expense\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.includedCount").value(2))
                .andExpect(jsonPath("$.excludedCount").value(0))
                .andExpect(jsonPath("$.expenseTotal").value("13.00"))
                .andExpect(jsonPath("$.rows[1].selectionSource").value("user"));

        mvc.perform(get("/api/v1/tenants/{tenantId}/imports/{importId}/preview", tenantId, importId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[1].included").value(true))
                .andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void statementImportCommitDeduplicatesOverlapAndUndoVoidsOnlyItsOwnRows() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 test statement".getBytes(StandardCharsets.US_ASCII));
        String firstPreview = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId)
                        .file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String firstId = com.jayway.jsonpath.JsonPath.read(firstPreview, "$.id");
        String firstTransactionId = mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm",
                        tenantId, firstId).with(auth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "bank-import-confirm-first-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("committed"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.duplicateCount").value(0))
                .andExpect(jsonPath("$.rows[0].outcome").value("created"))
                .andReturn().getResponse().getContentAsString();
        String transactionId = com.jayway.jsonpath.JsonPath.read(firstTransactionId, "$.rows[0].transactionId");

        mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, firstId).with(auth)
                        .header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "bank-import-confirm-first-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCount").value(1));

        String overlapPreview = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId)
                        .file(file).with(auth))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rows[0].duplicate").value(true))
                .andReturn().getResponse().getContentAsString();
        String overlapId = com.jayway.jsonpath.JsonPath.read(overlapPreview, "$.id");
        mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, overlapId).with(auth)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "bank-import-confirm-overlap-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.duplicateCount").value(1))
                .andExpect(jsonPath("$.rows[0].duplicateOfTransactionId").value(transactionId));

        String unrelated = mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth)
                        .header("Idempotency-Key", "unrelated-transaction-import-0001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"8.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"unrelated later\","
                                + "\"source\":\"manual\",\"occurredAt\":\"2026-10-02T09:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String unrelatedId = com.jayway.jsonpath.JsonPath.read(unrelated, "$.id");

        mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/undo", tenantId, firstId).with(auth)
                        .header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "bank-import-undo-first-0001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("reverted"))
                .andExpect(jsonPath("$.revertedCount").value(1));
        mvc.perform(get("/api/v1/tenants/{tenantId}/transactions/{id}", tenantId, transactionId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("voided"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/transactions/{id}", tenantId, unrelatedId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("posted"));

        String retryPreview = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId)
                        .file(file).with(auth))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rows[0].duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        String retryId = com.jayway.jsonpath.JsonPath.read(retryPreview, "$.id");
        mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, retryId).with(auth)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "bank-import-confirm-retry-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCount").value(1));
    }

    @Test
    void statementImportUndoReportsChangedRowsAndKeepsBatchCommitted() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 test statement".getBytes(StandardCharsets.US_ASCII));
        String created = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId)
                        .file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String importId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("""
                    INSERT INTO bank_import_rows (tenant_id, import_id, ordinal, operation_date, operation_time,
                        signed_amount, currency, kind, merchant, description, card_last4)
                    VALUES (?, ?, 2, '2026-10-01', '12:30', -12.00, 'RUB', 'purchase', 'Market', 'Payment', '1234')
                    """, tenantId, UUID.fromString(importId));
            return null;
        });
        String confirmed = mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, importId).with(auth)
                        .header("If-Match", "\"1\"").header("Idempotency-Key", "bank-import-confirm-edit-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCount").value(2))
                .andReturn().getResponse().getContentAsString();
        String transactionId = com.jayway.jsonpath.JsonPath.read(confirmed, "$.rows[0].transactionId");
        String unchangedTransactionId = com.jayway.jsonpath.JsonPath.read(confirmed, "$.rows[2].transactionId");
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions/{id}/void", tenantId, transactionId).with(auth)
                        .header("If-Match", "\"1\"").header("Idempotency-Key", "manual-void-imported-0001"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/undo", tenantId, importId).with(auth)
                        .header("If-Match", "\"2\"").header("Idempotency-Key", "bank-import-undo-conflict-0001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts[0]").value(transactionId));
        mvc.perform(get("/api/v1/tenants/{tenantId}/imports/{importId}/preview", tenantId, importId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("committed"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/transactions/{id}", tenantId, unchangedTransactionId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("posted"));
    }

    @Test
    void statementImportOccurrenceDiscriminatorPreservesLegitimateIdenticalRows() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 test statement".getBytes(StandardCharsets.US_ASCII));
        String firstPreview = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId)
                        .file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String firstId = com.jayway.jsonpath.JsonPath.read(firstPreview, "$.id");
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("""
                    INSERT INTO bank_import_rows (tenant_id, import_id, ordinal, operation_date, operation_time,
                        signed_amount, currency, kind, merchant, description, card_last4)
                    VALUES (?, ?, 2, '2026-10-01', '12:30', -12.00, 'RUB', 'purchase', 'Market', 'Payment', '1234')
                    """, tenantId, UUID.fromString(firstId));
            jdbc.update("""
                    INSERT INTO bank_import_rows (tenant_id, import_id, ordinal, operation_date, operation_time,
                        signed_amount, currency, kind, merchant, description, card_last4)
                    VALUES (?, ?, 3, '2026-10-01', '12:50', 5.00, 'RUB', 'refund', 'Market', 'Refund item', '1234')
                    """, tenantId, UUID.fromString(firstId));
            return null;
        });
        String firstCommit = mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, firstId)
                        .with(auth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "bank-import-identical-first-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCount").value(3))
                .andExpect(jsonPath("$.duplicateCount").value(0))
                .andExpect(jsonPath("$.rows[0].outcome").value("created"))
                .andExpect(jsonPath("$.rows[2].outcome").value("created"))
                .andExpect(jsonPath("$.rows[3].transactionType").value("refund"))
                .andExpect(jsonPath("$.rows[3].outcome").value("created"))
                .andReturn().getResponse().getContentAsString();

        String overlapPreview = mvc.perform(multipart("/api/v1/tenants/{tenantId}/imports", tenantId)
                        .file(file).with(auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String overlapId = com.jayway.jsonpath.JsonPath.read(overlapPreview, "$.id");
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("""
                    INSERT INTO bank_import_rows (tenant_id, import_id, ordinal, operation_date, operation_time,
                        signed_amount, currency, kind, merchant, description, card_last4)
                    VALUES (?, ?, 2, '2026-10-01', '12:30', -12.00, 'RUB', 'purchase', 'Market', 'Payment', '1234')
                    """, tenantId, UUID.fromString(overlapId));
            jdbc.update("""
                    INSERT INTO bank_import_rows (tenant_id, import_id, ordinal, operation_date, operation_time,
                        signed_amount, currency, kind, merchant, description, card_last4)
                    VALUES (?, ?, 3, '2026-10-01', '12:50', 5.00, 'RUB', 'refund', 'Market', 'Refund item', '1234')
                    """, tenantId, UUID.fromString(overlapId));
            return null;
        });
        mvc.perform(post("/api/v1/tenants/{tenantId}/imports/{importId}/confirm", tenantId, overlapId).with(auth)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "bank-import-identical-overlap-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdCount").value(0))
                .andExpect(jsonPath("$.duplicateCount").value(3))
                .andExpect(jsonPath("$.rows[0].outcome").value("duplicate"))
                .andExpect(jsonPath("$.rows[2].outcome").value("duplicate"))
                .andExpect(jsonPath("$.rows[3].transactionType").value("refund"))
                .andExpect(jsonPath("$.rows[3].outcome").value("duplicate"));
        org.junit.jupiter.api.Assertions.assertNotNull(com.jayway.jsonpath.JsonPath.read(firstCommit, "$.rows[2].transactionId"));
    }

    @Test
    void browserBankImportConfirmationRequiresCsrfAndUsesTheOwnerScopedBatch() throws Exception {
        var browser = oidcLogin().idToken(token -> token.subject(subject));
        var file = new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "%PDF-1.7 test statement".getBytes(StandardCharsets.US_ASCII));
        String staged = mvc.perform(multipart("/bff/tenants/{tenantId}/imports", tenantId)
                        .file(file).with(browser).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String importId = com.jayway.jsonpath.JsonPath.read(staged, "$.id");
        mvc.perform(post("/bff/tenants/{tenantId}/imports/{importId}/confirm", tenantId, importId).with(browser)
                        .header("If-Match", "\"1\"").header("Idempotency-Key", "browser-import-confirm-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/bff/tenants/{tenantId}/imports/{importId}/confirm", tenantId, importId).with(browser).with(csrf())
                        .header("If-Match", "\"1\"").header("Idempotency-Key", "browser-import-confirm-0001")
                        .contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("committed"))
                .andExpect(jsonPath("$.createdCount").value(1));
    }

    @Test
    void historyProposalRequiresThirtyDaysAndWaitsForExplicitApply() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        prepareBudgetHistory("100000.00");
        AI_CALLS.set(0);
        LAST_AI_REQUEST.set("");
        addBudgetHistoryTransaction("expense", "1200.00", today.minusDays(29), "private-marker-short");

        mvc.perform(post("/api/v1/tenants/{tenantId}/budget-proposals/history", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-history-proposal-0001"))
                .andExpect(status().isUnprocessableEntity());
        org.junit.jupiter.api.Assertions.assertEquals(0, AI_CALLS.get());

        addBudgetHistoryTransaction("expense", "800.00", today.minusDays(30), "private-marker-long");
        var response = mvc.perform(post("/api/v1/tenants/{tenantId}/budget-proposals/history", tenantId).with(auth)
                        .header("Idempotency-Key", "budget-history-proposal-0002"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.proposalSource").value("history_ai"))
                .andExpect(jsonPath("$.historyDays").value(30))
                .andExpect(jsonPath("$.modelVersion").value("test-model-1"))
                .andExpect(jsonPath("$.promptVersion").value("budget-proposal.v1"))
                .andExpect(jsonPath("$.totalLimit").value("70000.00"))
                .andReturn();
        String proposalId = com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.id");
        org.junit.jupiter.api.Assertions.assertEquals(1, AI_CALLS.get());
        String requestBody = LAST_AI_REQUEST.get();
        org.junit.jupiter.api.Assertions.assertTrue(requestBody.contains("monthlyExpenseByMonth"));
        org.junit.jupiter.api.Assertions.assertTrue(requestBody.contains("monthlyIncome"));
        org.junit.jupiter.api.Assertions.assertFalse(requestBody.contains(tenantId.toString()));
        org.junit.jupiter.api.Assertions.assertFalse(requestBody.contains(subject));
        org.junit.jupiter.api.Assertions.assertFalse(requestBody.contains("private-marker"));

        mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.familyTotalLimit").value("55000.00"));
        mvc.perform(post("/api/v1/tenants/{tenantId}/budget-proposals/{proposalId}/apply", tenantId, proposalId).with(auth)
                        .header("Idempotency-Key", "budget-history-apply-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.familyTotalLimit").value("70000.00"));

        long telegramUserId = newTelegramUserId();
        String linkCode = mvc.perform(post("/api/v1/me/telegram-link").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = com.jayway.jsonpath.JsonPath.read(linkCode, "$.code");
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk());
        String actor = mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\""
                                + tenantId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String actorToken = com.jayway.jsonpath.JsonPath.read(actor, "$.token");
        var telegramProposal = mvc.perform(post("/internal/v1/telegram/budget-proposals/history")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + actorToken + "\",\"idempotencyKey\":"
                                + "\"telegram-budget-proposal-0001\",\"tenantId\":\""
                                + UUID.randomUUID() + "\",\"subject\":\"forged\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.proposalSource").value("history_ai"))
                .andExpect(jsonPath("$.historyDays").value(30))
                .andReturn();
        String telegramProposalId = com.jayway.jsonpath.JsonPath.read(
                telegramProposal.getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.familyTotalLimit").value("70000.00"));
        mvc.perform(post("/internal/v1/telegram/budget-proposals/{proposalId}/apply", telegramProposalId)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + actorToken + "\",\"idempotencyKey\":"
                                + "\"telegram-budget-proposal-apply-0001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.familyTotalLimit").value("70000.00"));

        var incomeProposal = mvc.perform(post("/internal/v1/telegram/budget-proposals")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + actorToken + "\",\"idempotencyKey\":"
                                + "\"telegram-budget-income-0001\",\"monthlyIncome\":\"150000.00\","
                                + "\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.proposalSource").value("income"))
                .andExpect(jsonPath("$.totalLimit").value("105000.00"))
                .andReturn();
        String incomeProposalId = com.jayway.jsonpath.JsonPath.read(
                incomeProposal.getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.familyTotalLimit").value("70000.00"));
        mvc.perform(post("/internal/v1/telegram/budget-proposals/{proposalId}/apply", incomeProposalId)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"token\":\"" + actorToken + "\",\"idempotencyKey\":"
                                + "\"telegram-budget-income-apply-0001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.familyTotalLimit").value("105000.00"));
    }

    @Test
    void textTransactionDraftCreatesNoMoneyUntilConfirmedAndRejectsStaleActions() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        AI_CALLS.set(0);
        LAST_AI_REQUEST.set("");

        var created = mvc.perform(post("/api/v1/tenants/{tenantId}/transaction-drafts", tenantId).with(auth)
                        .header("Idempotency-Key", "text-draft-create-0001")
                        .contentType("application/json")
                        .content("{\"text\":\"Такси 2 тыс\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("pending"))
                .andExpect(jsonPath("$.type").value("expense"))
                .andExpect(jsonPath("$.amount").value("2000.00"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.modelVersion").value("test-model-1"))
                .andReturn();
        String draftId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        org.junit.jupiter.api.Assertions.assertEquals(1, AI_CALLS.get());
        org.junit.jupiter.api.Assertions.assertFalse(LAST_AI_REQUEST.get().contains(tenantId.toString()));
        org.junit.jupiter.api.Assertions.assertFalse(LAST_AI_REQUEST.get().contains(subject));
        mvc.perform(get("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));

        mvc.perform(patch("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}", tenantId, draftId).with(auth)
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"2100.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"transport\",\"subcategoryCode\":null,"
                                + "\"description\":\"Такси corrected\",\"occurredAt\":\"2026-10-01T09:00:00Z\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value("2100.00"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(patch("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}", tenantId, draftId).with(auth)
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"2200.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"transport\",\"subcategoryCode\":null,"
                                + "\"description\":\"stale\",\"occurredAt\":\"2026-10-01T09:00:00Z\"}"))
                .andExpect(status().isPreconditionFailed());

        mvc.perform(post("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}/confirm", tenantId, draftId).with(auth)
                        .header("Idempotency-Key", "text-draft-confirm-0001")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value("2100.00"));
        mvc.perform(post("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}/confirm", tenantId, draftId).with(auth)
                        .header("Idempotency-Key", "text-draft-confirm-0001")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value("2100.00"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(post("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}/confirm", tenantId, draftId).with(auth)
                        .header("Idempotency-Key", "text-draft-confirm-0002")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isConflict());
    }

    @Test
    void textDebtPaymentDraftRequiresDebtChoiceAndConfirmsAtomicPayment() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        String previousResponse = AI_DRAFT_RESPONSE.get();
        AI_DRAFT_RESPONSE.set("""
                {"provider":"test-ollama","type":"debt_payment","amount":"1500.00","categoryCode":"долги","subcategoryCode":null,"description":"Платёж по кредитке","occurredAt":"2026-10-01T09:00:00+03:00","modelVersion":"test-model-1","promptVersion":"transaction-draft.v1"}
                """);
        try {
            var debt = mvc.perform(post("/api/v1/tenants/{tenantId}/debts", tenantId).with(auth)
                            .header("Idempotency-Key", "draft-debt-create-0001")
                            .contentType("application/json").content("{\"name\":\"Credit card\",\"openingBalance\":\"2000.00\"}"))
                    .andExpect(status().isCreated()).andReturn();
            String debtId = com.jayway.jsonpath.JsonPath.read(debt.getResponse().getContentAsString(), "$.id");
            var draft = mvc.perform(post("/api/v1/tenants/{tenantId}/transaction-drafts", tenantId).with(auth)
                            .header("Idempotency-Key", "draft-debt-text-0001")
                            .contentType("application/json").content("{\"text\":\"Платёж по кредитке 1500\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("debt_payment"))
                    .andExpect(jsonPath("$.debtId").doesNotExist())
                    .andReturn();
            String draftId = com.jayway.jsonpath.JsonPath.read(draft.getResponse().getContentAsString(), "$.id");
            mvc.perform(get("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
            mvc.perform(patch("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}", tenantId, draftId).with(auth)
                            .header("If-Match", "\"1\"")
                            .contentType("application/json")
                            .content("{\"type\":\"debt_payment\",\"amount\":\"1500.00\",\"currency\":\"RUB\","
                                    + "\"categoryCode\":\"долги\",\"subcategoryCode\":null,"
                                    + "\"description\":\"Платёж по кредитке\",\"occurredAt\":\"2026-10-01T09:00:00Z\","
                                    + "\"debtId\":\"" + debtId + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.debtId").value(debtId))
                    .andExpect(jsonPath("$.version").value(2));
            mvc.perform(post("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}/confirm", tenantId, draftId).with(auth)
                            .header("Idempotency-Key", "draft-debt-confirm-0001")
                            .header("If-Match", "\"2\""))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("debt_payment"))
                    .andExpect(jsonPath("$.amount").value("1500.00"));
            mvc.perform(post("/api/v1/tenants/{tenantId}/transaction-drafts/{draftId}/confirm", tenantId, draftId).with(auth)
                            .header("Idempotency-Key", "draft-debt-confirm-0001")
                            .header("If-Match", "\"2\""))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("debt_payment"));
            mvc.perform(get("/api/v1/tenants/{tenantId}/debts/{debtId}", tenantId, debtId).with(auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.currentBalance").value("500.00"))
                    .andExpect(jsonPath("$.version").value(2));
            mvc.perform(get("/api/v1/tenants/{tenantId}/transactions", tenantId).with(auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        } finally {
            AI_DRAFT_RESPONSE.set(previousResponse);
        }
    }

    @Test
    void rollingFoodStatusMatchesDashboardAndReportsWithAndWithoutHistory() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        mvc.perform(put("/api/v1/tenants/{tenantId}/budgets/{budgetKey}", tenantId, "еда").with(auth)
                        .header("Idempotency-Key", "food-limit-create-0001")
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("{\"scope\":\"family\",\"amount\":\"1000.00\",\"period\":\"rolling7\"}"))
                .andExpect(status().isOk());
        addBudgetHistoryTransaction("expense", "500.00", today, "food-current");

        String budgetWithoutHistory = mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolling7FoodStatus.spent").value("500.00"))
                .andExpect(jsonPath("$.rolling7FoodStatus.limit").value("1000.00"))
                .andExpect(jsonPath("$.rolling7FoodStatus.historyWeeks").value(0))
                .andExpect(jsonPath("$.rolling7FoodStatus.paceStatus").value("insufficient_history"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolling7FoodStatus.spent").value("500.00"))
                .andExpect(jsonPath("$.rolling7FoodStatus.limitStatus").value("normal"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/reports/period", tenantId).with(auth)
                        .param("period", "week"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolling7FoodStatus.paceStatus").value("insufficient_history"));
        Object budgetStatus = com.jayway.jsonpath.JsonPath.read(budgetWithoutHistory, "$.rolling7FoodStatus");
        var dashboardWithoutHistory = mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(budgetStatus,
                com.jayway.jsonpath.JsonPath.read(dashboardWithoutHistory, "$.rolling7FoodStatus"));

        addBudgetHistoryTransaction("expense", "200.00", today.minusDays(7), "food-week-1");
        addBudgetHistoryTransaction("expense", "400.00", today.minusDays(14), "food-week-2");
        String dashboard = mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolling7FoodStatus.usualWeeklySpend").value("400.00"))
                .andExpect(jsonPath("$.rolling7FoodStatus.historyWeeks").value(2))
                .andExpect(jsonPath("$.rolling7FoodStatus.paceStatus").value("over"))
                .andReturn().getResponse().getContentAsString();
        Object dashboardStatus = com.jayway.jsonpath.JsonPath.read(dashboard, "$.rolling7FoodStatus");
        String budget = mvc.perform(get("/api/v1/tenants/{tenantId}/budgets", tenantId).with(auth))
                .andReturn().getResponse().getContentAsString();
        String personalReport = mvc.perform(get("/api/v1/tenants/{tenantId}/reports/period", tenantId).with(auth)
                        .param("period", "week").param("scope", "personal"))
                .andReturn().getResponse().getContentAsString();
        String familyReport = mvc.perform(get("/api/v1/tenants/{tenantId}/reports/period", tenantId).with(auth)
                        .param("period", "week").param("scope", "family"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(dashboardStatus,
                com.jayway.jsonpath.JsonPath.read(budget, "$.rolling7FoodStatus"));
        org.junit.jupiter.api.Assertions.assertEquals(dashboardStatus,
                com.jayway.jsonpath.JsonPath.read(personalReport, "$.rolling7FoodStatus"));
        org.junit.jupiter.api.Assertions.assertEquals(dashboardStatus,
                com.jayway.jsonpath.JsonPath.read(familyReport, "$.rolling7FoodStatus"));
    }

    @Test
    void notificationPreferencesExposeLocalDefaultsAndVersionedMemberUpdates() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        String path = "/api/v1/tenants/{tenantId}/notification-preferences";

        mvc.perform(get(path, tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.telegramLinked").value(false))
                .andExpect(jsonPath("$.language").value("ru"))
                .andExpect(jsonPath("$.dailyEnabled").value(true))
                .andExpect(jsonPath("$.dailyLocalTime").value("21:00"))
                .andExpect(jsonPath("$.weeklyEnabled").value(true))
                .andExpect(jsonPath("$.weeklyDayOfWeek").value(7))
                .andExpect(jsonPath("$.weeklyLocalTime").value("19:00"))
                .andExpect(jsonPath("$.quietHoursStart").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.quietHoursEnd").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.version").value(0));

        mvc.perform(patch(path, tenantId).with(auth)
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("{\"language\":\"en\",\"dailyEnabled\":false,\"dailyLocalTime\":\"08:30\","
                                + "\"weeklyEnabled\":true,\"weeklyDayOfWeek\":7,\"weeklyLocalTime\":\"19:15\","
                                + "\"quietHoursStart\":\"22:00\",\"quietHoursEnd\":\"07:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.dailyEnabled").value(false))
                .andExpect(jsonPath("$.dailyLocalTime").value("08:30"))
                .andExpect(jsonPath("$.weeklyEnabled").value(true))
                .andExpect(jsonPath("$.weeklyDayOfWeek").value(7))
                .andExpect(jsonPath("$.weeklyLocalTime").value("19:15"))
                .andExpect(jsonPath("$.quietHoursStart").value("22:00"))
                .andExpect(jsonPath("$.quietHoursEnd").value("07:00"));

        mvc.perform(get(path, tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.dailyEnabled").value(false))
                .andExpect(jsonPath("$.dailyLocalTime").value("08:30"));
        mvc.perform(patch(path, tenantId).with(auth).header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("{\"language\":\"ru\",\"dailyEnabled\":true,\"dailyLocalTime\":\"21:00\","
                                + "\"weeklyEnabled\":true,\"weeklyDayOfWeek\":7,\"weeklyLocalTime\":\"19:00\","
                                + "\"quietHoursStart\":null,\"quietHoursEnd\":null}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(patch(path, tenantId).with(auth).header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"language\":\"en\",\"dailyEnabled\":true,\"dailyLocalTime\":\"21:00\","
                                + "\"weeklyEnabled\":true,\"weeklyDayOfWeek\":7,\"weeklyLocalTime\":\"19:00\","
                                + "\"quietHoursStart\":\"22:00\",\"quietHoursEnd\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path, UUID.randomUUID()).with(auth)).andExpect(status().isNotFound());

        var browser = oidcLogin().idToken(token -> token.subject(subject));
        String browserPath = "/bff/tenants/{tenantId}/notification-preferences";
        mvc.perform(get(browserPath, tenantId).with(browser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.language").value("en"));
        mvc.perform(patch(browserPath, tenantId).with(browser).with(csrf())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"language\":\"ru\",\"dailyEnabled\":false,\"dailyLocalTime\":\"08:30\","
                                + "\"weeklyEnabled\":true,\"weeklyDayOfWeek\":7,\"weeklyLocalTime\":\"19:15\","
                                + "\"quietHoursStart\":\"22:00\",\"quietHoursEnd\":\"07:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.language").value("ru"));
    }

    @Test
    void notificationDeliveryClaimAttachesOnlyOldestCompletedOutcomeToWeeklyIntent() throws Exception {
        cleanupNotificationTestFixtures();
        UUID ownerId = prepareNotificationMember();
        UUID oldest = insertGoalOutcome(ownerId, "completed", "Чипсы", 2, 1,
                Instant.now().minusSeconds(3600));
        UUID newer = insertGoalOutcome(ownerId, "completed", "Газировка", 4, 4,
                Instant.now().minusSeconds(1800));
        UUID legacy = insertGoalOutcome(ownerId, "legacy", "Старый совет", 1, 1,
                Instant.now().minusSeconds(7200));
        UUID weeklyIntent = insertNotificationIntent(ownerId, "weekly", LocalDate.now(ZoneOffset.UTC).minusDays(7));

        String weekly = claimNotifications(1);
        org.junit.jupiter.api.Assertions.assertEquals(oldest.toString(),
                com.jayway.jsonpath.JsonPath.read(weekly, "$.items[0].goalOutcome.id"));
        org.junit.jupiter.api.Assertions.assertEquals("Чипсы",
                com.jayway.jsonpath.JsonPath.read(weekly, "$.items[0].goalOutcome.name"));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(weeklyIntent, jdbc.queryForObject(
                    "SELECT announcement_intent_id FROM goal_outcomes WHERE id = ?", UUID.class, oldest));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM goal_outcomes WHERE id = ? AND announcement_intent_id IS NULL",
                    Integer.class, newer));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM goal_outcomes WHERE id = ? AND announcement_intent_id IS NULL",
                    Integer.class, legacy));
        });

        UUID dailyIntent = insertNotificationIntent(ownerId, "daily", LocalDate.now(ZoneOffset.UTC));
        String daily = claimNotifications(1);
        org.junit.jupiter.api.Assertions.assertEquals(dailyIntent.toString(),
                com.jayway.jsonpath.JsonPath.read(daily, "$.items[0].intentId"));
        org.junit.jupiter.api.Assertions.assertNull(
                com.jayway.jsonpath.JsonPath.read(daily, "$.items[0].goalOutcome"));
        cleanupNotificationTestFixtures();
    }

    @Test
    void concurrentWeeklyClaimsAttachDistinctGoalOutcomes() throws Exception {
        cleanupNotificationTestFixtures();
        UUID ownerId = prepareNotificationMember();
        UUID firstOutcome = insertGoalOutcome(ownerId, "completed", "Кофе", 3, 1,
                Instant.now().minusSeconds(3600));
        UUID secondOutcome = insertGoalOutcome(ownerId, "completed", "Сладкое", 5, 2,
                Instant.now().minusSeconds(1800));
        insertNotificationIntent(ownerId, "weekly", LocalDate.now(ZoneOffset.UTC).minusDays(14));
        insertNotificationIntent(ownerId, "weekly", LocalDate.now(ZoneOffset.UTC).minusDays(7));

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var firstClaim = java.util.concurrent.CompletableFuture.supplyAsync(() -> claimNotifications(1), pool);
            var secondClaim = java.util.concurrent.CompletableFuture.supplyAsync(() -> claimNotifications(1), pool);
            String first = firstClaim.join();
            String second = secondClaim.join();
            String firstIntent = com.jayway.jsonpath.JsonPath.read(first, "$.items[0].intentId");
            String secondIntent = com.jayway.jsonpath.JsonPath.read(second, "$.items[0].intentId");
            String firstAttached = com.jayway.jsonpath.JsonPath.read(first, "$.items[0].goalOutcome.id");
            String secondAttached = com.jayway.jsonpath.JsonPath.read(second, "$.items[0].goalOutcome.id");
            org.junit.jupiter.api.Assertions.assertNotEquals(firstIntent, secondIntent);
            org.junit.jupiter.api.Assertions.assertNotEquals(firstAttached, secondAttached);
            org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(firstOutcome.toString(), secondOutcome.toString()),
                    java.util.Set.of(firstAttached, secondAttached));
            transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
                        "SELECT count(DISTINCT announcement_intent_id) FROM goal_outcomes "
                                + "WHERE id IN (?, ?) AND announcement_intent_id IS NOT NULL",
                        Integer.class, firstOutcome, secondOutcome));
            });
        } finally {
            pool.shutdownNow();
            cleanupNotificationTestFixtures();
        }
    }

    @Test
    void successfulWeeklyDeliveryMarksAttachedOutcomeAnnouncedOnce() throws Exception {
        cleanupNotificationTestFixtures();
        UUID ownerId = prepareNotificationMember();
        UUID outcomeId = insertGoalOutcome(ownerId, "completed", "Продукт", 2, 2,
                Instant.now().minusSeconds(120));
        UUID intentId = insertNotificationIntent(ownerId, "weekly", LocalDate.now(ZoneOffset.UTC).minusDays(7));
        String claim = claimNotifications(1);
        String lease = com.jayway.jsonpath.JsonPath.read(claim, "$.items[0].leaseToken");

        acknowledgeNotification(intentId, lease, "delivered");
        Instant announcedAt = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM goal_outcomes WHERE id = ? AND announcement_intent_id IS NOT NULL",
                    Integer.class, outcomeId));
            return jdbc.queryForObject("SELECT announced_at FROM goal_outcomes WHERE id = ?",
                    Instant.class, outcomeId);
        });
        org.junit.jupiter.api.Assertions.assertNotNull(announcedAt);
        mvc.perform(post("/internal/v1/telegram/notifications/{intentId}/delivery", intentId)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"leaseToken\":\"" + lease + "\",\"outcome\":\"delivered\"}"))
                .andExpect(status().isConflict());
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM goal_outcomes WHERE id = ? AND announced_at = ?",
                    Integer.class, outcomeId, java.sql.Timestamp.from(announcedAt)));
        });
        cleanupNotificationTestFixtures();
    }

    @Test
    void retryableDeliveryKeepsAttachedOutcomeForSameIntent() throws Exception {
        cleanupNotificationTestFixtures();
        UUID ownerId = prepareNotificationMember();
        UUID outcomeId = insertGoalOutcome(ownerId, "completed", "Продукт", 2, 1,
                Instant.now().minusSeconds(120));
        UUID intentId = insertNotificationIntent(ownerId, "weekly", LocalDate.now(ZoneOffset.UTC).minusDays(7));
        String first = claimNotifications(1);
        String firstLease = com.jayway.jsonpath.JsonPath.read(first, "$.items[0].leaseToken");
        acknowledgeNotification(intentId, firstLease, "retryable_failure");

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM goal_outcomes WHERE id = ? AND announcement_intent_id = ? "
                            + "AND announced_at IS NULL", Integer.class, outcomeId, intentId));
            jdbc.update("UPDATE notification_intents SET available_at = now() WHERE id = ?", intentId);
        });
        String retry = claimNotifications(1);
        org.junit.jupiter.api.Assertions.assertEquals(intentId.toString(),
                com.jayway.jsonpath.JsonPath.read(retry, "$.items[0].intentId"));
        org.junit.jupiter.api.Assertions.assertEquals(outcomeId.toString(),
                com.jayway.jsonpath.JsonPath.read(retry, "$.items[0].goalOutcome.id"));
        cleanupNotificationTestFixtures();
    }

    @Test
    void terminalDeliveryFailureReleasesOutcomeForNextWeeklyIntent() throws Exception {
        for (String terminalOutcome : List.of("no_data", "permanent_failure")) {
            cleanupNotificationTestFixtures();
            UUID ownerId = prepareNotificationMember();
            UUID outcomeId = insertGoalOutcome(ownerId, "completed", "Продукт", 2, 1,
                    Instant.now().minusSeconds(120));
            UUID firstIntent = insertNotificationIntent(ownerId, "weekly",
                    LocalDate.now(ZoneOffset.UTC).minusDays(14));
            String first = claimNotifications(1);
            String lease = com.jayway.jsonpath.JsonPath.read(first, "$.items[0].leaseToken");
            acknowledgeNotification(firstIntent, lease, terminalOutcome);

            transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                        tenantId.toString());
                org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                        "SELECT count(*) FROM goal_outcomes WHERE id = ? AND announcement_intent_id IS NULL "
                                + "AND announced_at IS NULL", Integer.class, outcomeId));
            });
            UUID nextIntent = insertNotificationIntent(ownerId, "weekly", LocalDate.now(ZoneOffset.UTC).minusDays(7));
            String next = claimNotifications(1);
            org.junit.jupiter.api.Assertions.assertEquals(nextIntent.toString(),
                    com.jayway.jsonpath.JsonPath.read(next, "$.items[0].intentId"));
            org.junit.jupiter.api.Assertions.assertEquals(outcomeId.toString(),
                    com.jayway.jsonpath.JsonPath.read(next, "$.items[0].goalOutcome.id"));
        }
        cleanupNotificationTestFixtures();
    }

    @Test
    void exhaustedExpiredLeaseReleasesAttachedOutcome() throws Exception {
        cleanupNotificationTestFixtures();
        UUID ownerId = prepareNotificationMember();
        UUID outcomeId = insertGoalOutcome(ownerId, "completed", "Продукт", 2, 1,
                Instant.now().minusSeconds(120));
        UUID exhaustedIntent = insertNotificationIntent(ownerId, "weekly",
                LocalDate.now(ZoneOffset.UTC).minusDays(14));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.update("UPDATE notification_intents SET attempt_count = 7 WHERE id = ?", exhaustedIntent);
        });
        String claim = claimNotifications(1);
        org.junit.jupiter.api.Assertions.assertEquals(outcomeId.toString(),
                com.jayway.jsonpath.JsonPath.read(claim, "$.items[0].goalOutcome.id"));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.update("UPDATE notification_intents SET lease_until = now() - interval '1 second' WHERE id = ?",
                    exhaustedIntent);
        });
        UUID nextIntent = insertNotificationIntent(ownerId, "weekly", LocalDate.now(ZoneOffset.UTC).minusDays(7));

        String next = claimNotifications(1);
        org.junit.jupiter.api.Assertions.assertEquals(nextIntent.toString(),
                com.jayway.jsonpath.JsonPath.read(next, "$.items[0].intentId"));
        org.junit.jupiter.api.Assertions.assertEquals(outcomeId.toString(),
                com.jayway.jsonpath.JsonPath.read(next, "$.items[0].goalOutcome.id"));
        cleanupNotificationTestFixtures();
    }

    @Test
    void legacyGoalHistoryMigrationImportsAllRowsIdempotentlyAndExposesHistory() throws Exception {
        String legacySubject = "keycloak|legacy-goal-history-" + UUID.randomUUID();
        UUID ownerId = addTenantMember(legacySubject, "Legacy goal owner", "owner");
        List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
        for (int index = 0; index < 26; index++) entries.add(legacyGoalHistoryEntry(index));
        String request = legacyGoalHistoryRequest(tenantId, ownerId, entries);
        String path = "/internal/v1/migrations/goal-history";

        mvc.perform(post(path).contentType("application/json").content(request))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-Finance-Migration-Token", "wrong-token")
                        .contentType("application/json").content(request))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-Finance-Migration-Token", MIGRATION_SERVICE_TOKEN)
                        .contentType("application/json").content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(26))
                .andExpect(jsonPath("$.alreadyPresent").value(0));
        mvc.perform(post(path).header("X-Finance-Migration-Token", MIGRATION_SERVICE_TOKEN)
                        .contentType("application/json").content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(0))
                .andExpect(jsonPath("$.alreadyPresent").value(26));

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            org.junit.jupiter.api.Assertions.assertEquals(26, jdbc.queryForObject(
                    "SELECT count(*) FROM goal_outcomes WHERE tenant_id = ? AND owner_user_id = ? "
                            + "AND origin = 'legacy'", Integer.class, tenantId, ownerId));
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM notification_intents WHERE tenant_id = ? AND user_id = ?",
                    Integer.class, tenantId, ownerId));
            org.junit.jupiter.api.Assertions.assertEquals("Legacy goal 00", jdbc.queryForObject(
                    "SELECT goal_snapshot->>'name' FROM goal_outcomes WHERE tenant_id = ? AND owner_user_id = ? "
                            + "ORDER BY completed_at DESC LIMIT 1", String.class, tenantId, ownerId));
            org.junit.jupiter.api.Assertions.assertEquals("18", jdbc.queryForObject(
                    "SELECT goal_snapshot->>'legacyTarget' FROM goal_outcomes WHERE tenant_id = ? "
                            + "AND owner_user_id = ? AND legacy_key LIKE '%:17'", String.class, tenantId, ownerId));
            org.junit.jupiter.api.Assertions.assertEquals("0.00", jdbc.queryForObject(
                    "SELECT goal_snapshot->>'legacyLimit' FROM goal_outcomes WHERE tenant_id = ? "
                            + "AND owner_user_id = ? AND legacy_key LIKE '%:17'", String.class, tenantId, ownerId));
        });
        when(goalCandidatesClient.calculate(any())).thenAnswer(invocation ->
                goalCandidates(invocation.getArgument(0)));

        mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId)
                        .with(jwt().jwt(token -> token.subject(legacySubject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()", org.hamcrest.Matchers.is(26)))
                .andExpect(jsonPath("$.history[0].origin").value("legacy"));
    }

    @Test
    void legacyGoalHistoryMigrationRejectsWholeBatchWhenOneEntryIsInvalid() throws Exception {
        String legacySubject = "keycloak|legacy-goal-history-invalid-" + UUID.randomUUID();
        UUID ownerId = addTenantMember(legacySubject, "Invalid legacy goal owner", "owner");
        var valid = legacyGoalHistoryEntry(1);
        var invalid = legacyGoalHistoryEntry(2);
        invalid.put("completedAt", "2025-12-31T00:00:00Z");
        List<java.util.Map<String, Object>> entries = List.of(valid, invalid);

        mvc.perform(post("/internal/v1/migrations/goal-history")
                        .header("X-Finance-Migration-Token", MIGRATION_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content(legacyGoalHistoryRequest(tenantId, ownerId, entries)))
                .andExpect(status().isBadRequest());
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM goal_outcomes WHERE tenant_id = ? AND owner_user_id = ?",
                    Integer.class, tenantId, ownerId));
        });
    }

    @Test
    void exportSnapshotsRequestTimeRowsAndCommitsJobWithOutboxAtomically() throws Exception {
        String memberSubject = "keycloak|export-snapshot-member-" + UUID.randomUUID();
        UUID memberId = addTenantMember(memberSubject, "Export member", "member");
        String ownerTransactionId = createExportFixtureTransaction(subject, "export-owner-before",
                "2026-10-05T10:00:00Z", "export-owner-create-0001");
        String memberTransactionId = createExportFixtureTransaction(memberSubject, "export-member-before",
                "2026-10-06T10:00:00Z", "export-member-create-0001");
        createExportFixtureTransaction(subject, "outside-export-window", "2026-10-04T23:59:00Z",
                "export-outside-create-0001");

        int transactionCountBefore = jdbc.queryForObject("SELECT count(*) FROM transactions WHERE tenant_id = ?",
                Integer.class, tenantId);
        String created = mvc.perform(post("/api/v1/tenants/{tenantId}/exports", tenantId)
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .contentType("application/json")
                        .content("{\"formatVersion\":\"csv-v1\",\"fromDate\":\"2026-10-05\","
                                + "\"toDate\":\"2026-10-06\",\"memberId\":\"all\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.formatVersion").value("csv-v1"))
                .andExpect(jsonPath("$.rowCount").value(2))
                .andReturn().getResponse().getContentAsString();
        String exportId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        org.junit.jupiter.api.Assertions.assertNotNull(
                com.jayway.jsonpath.JsonPath.<String>read(created, "$.snapshotAt"));
        org.junit.jupiter.api.Assertions.assertEquals(transactionCountBefore,
                jdbc.queryForObject("SELECT count(*) FROM transactions WHERE tenant_id = ?", Integer.class, tenantId));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                transactions.executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                    jdbc.update("UPDATE export_snapshot_rows SET description='tampered' WHERE tenant_id=? AND export_id=?",
                            tenantId, UUID.fromString(exportId));
                }));

        mvc.perform(patch("/api/v1/tenants/{tenantId}/transactions/{transactionId}", tenantId, ownerTransactionId)
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .header("Idempotency-Key", "export-after-update-0001").header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"20.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"owner-after\","
                                + "\"source\":\"manual\",\"occurredAt\":\"2026-10-05T10:00:00Z\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions/{transactionId}/void", tenantId,
                        memberTransactionId)
                        .with(jwt().jwt(token -> token.subject(memberSubject)))
                        .header("Idempotency-Key", "export-after-void-0001").header("If-Match", "\"1\""))
                .andExpect(status().isOk());
        createExportFixtureTransaction(subject, "created-after-export", "2026-10-05T11:00:00Z",
                "export-after-create-0001");

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
                    "SELECT count(*) FROM export_snapshot_rows WHERE tenant_id=? AND export_id=?",
                    Integer.class, tenantId, UUID.fromString(exportId)));
            org.junit.jupiter.api.Assertions.assertEquals(List.of("export-owner-before", "export-member-before"),
                    jdbc.query("SELECT description FROM export_snapshot_rows WHERE tenant_id=? AND export_id=? "
                                    + "ORDER BY row_number", (rs, row) -> rs.getString(1), tenantId,
                            UUID.fromString(exportId)));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM export_jobs WHERE tenant_id=? AND id=? AND requester_user_id=? "
                            + "AND row_count=2 AND snapshot_at IS NOT NULL", Integer.class,
                    tenantId, UUID.fromString(exportId), userIdFor(subject)));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE tenant_id=? AND aggregate_type='export' "
                            + "AND aggregate_id=? AND event_type='export.requested'", Integer.class,
                    tenantId, UUID.fromString(exportId)));
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM notification_intents WHERE tenant_id=?", Integer.class, tenantId));
        });
    }

    @Test
    void exportScopeStatusAndRangeAreBoundToActiveMemberRole() throws Exception {
        String memberSubject = "keycloak|export-scope-member-" + UUID.randomUUID();
        UUID memberId = addTenantMember(memberSubject, "Export member", "member");
        createExportFixtureTransaction(subject, "scope-owner-only", "2026-10-01T12:00:00Z", "scope-owner-create-001");
        createExportFixtureTransaction(memberSubject, "scope-member-only", "2026-10-01T13:00:00Z", "scope-member-create-001");
        String ownerAuthSubject = subject;
        var ownerAuth = jwt().jwt(token -> token.subject(ownerAuthSubject));
        var memberAuth = jwt().jwt(token -> token.subject(memberSubject));
        String ownerJob = mvc.perform(post("/api/v1/tenants/{tenantId}/exports", tenantId).with(ownerAuth)
                        .contentType("application/json")
                        .content("{\"formatVersion\":\"csv-v1\",\"fromDate\":\"2026-10-01\","
                                + "\"toDate\":\"2026-10-02\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String ownerExportId = com.jayway.jsonpath.JsonPath.read(ownerJob, "$.id");

        mvc.perform(post("/api/v1/tenants/{tenantId}/exports", tenantId).with(ownerAuth)
                        .contentType("application/json")
                        .content("{\"formatVersion\":\"csv-v1\",\"fromDate\":\"2026-10-01\","
                                + "\"toDate\":\"2026-10-02\",\"memberId\":\"" + memberId + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.memberId").value(memberId.toString()));

        String memberJob = mvc.perform(post("/api/v1/tenants/{tenantId}/exports", tenantId).with(memberAuth)
                        .contentType("application/json")
                        .content("{\"formatVersion\":\"csv-v1\",\"fromDate\":\"2026-10-01\","
                                + "\"toDate\":\"2026-10-02\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andReturn().getResponse().getContentAsString();
        String memberExportId = com.jayway.jsonpath.JsonPath.read(memberJob, "$.id");
        mvc.perform(get("/api/v1/tenants/{tenantId}/exports/{exportId}", tenantId, memberExportId)
                        .with(memberAuth))
                .andExpect(status().isOk());

        String allScope = "{\"formatVersion\":\"csv-v1\",\"fromDate\":\"2026-10-01\","
                + "\"toDate\":\"2026-10-02\",\"memberId\":\"all\"}";
        mvc.perform(post("/api/v1/tenants/{tenantId}/exports", tenantId).with(memberAuth)
                        .contentType("application/json").content(allScope))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tenants/{tenantId}/exports", tenantId).with(memberAuth)
                        .contentType("application/json")
                        .content("{\"formatVersion\":\"csv-v1\",\"fromDate\":\"2026-10-01\","
                                + "\"toDate\":\"2026-10-02\",\"memberId\":\"" + userIdFor(subject) + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenants/{tenantId}/exports/{exportId}", tenantId, ownerExportId)
                        .with(memberAuth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/tenants/{tenantId}/exports/{exportId}", UUID.randomUUID(), ownerExportId)
                        .with(ownerAuth))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/tenants/{tenantId}/exports", tenantId).with(ownerAuth)
                        .contentType("application/json")
                        .content("{\"formatVersion\":\"csv-v1\",\"fromDate\":\"2024-10-01\","
                                + "\"toDate\":\"2026-10-02\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/tenants/{tenantId}/exports", tenantId).with(ownerAuth)
                        .contentType("application/json")
                        .content("{\"formatVersion\":\"csv-v1\",\"fromDate\":\"2026-10-02\","
                                + "\"toDate\":\"2026-10-01\"}"))
                .andExpect(status().isBadRequest());
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(3, jdbc.queryForObject(
                    "SELECT count(*) FROM export_jobs WHERE tenant_id=?", Integer.class, tenantId));
        });
        org.junit.jupiter.api.Assertions.assertEquals(memberId,
                userIdFor(memberSubject), "member manifest identity must remain explicit");
    }

    @Test
    void exportRejectsMoreThanOneHundredThousandRowsWithoutCreatingJob() throws Exception {
        UUID ownerId = userIdFor(subject);
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("INSERT INTO transactions (tenant_id, owner_subject, owner_user_id, type, amount, "
                            + "category_code, occurred_at) SELECT ?, ?, ?, 'expense', 1.00, 'food', "
                            + "TIMESTAMPTZ '2026-10-01 12:00:00+00' FROM generate_series(1, 100001)",
                    tenantId, subject, ownerId);
        });

        mvc.perform(post("/api/v1/tenants/{tenantId}/exports", tenantId)
                        .with(jwt().jwt(token -> token.subject(subject)))
                        .contentType("application/json")
                        .content("{\"formatVersion\":\"csv-v1\",\"fromDate\":\"2026-10-01\","
                                + "\"toDate\":\"2026-10-01\"}"))
                .andExpect(status().isPayloadTooLarge());
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM export_jobs WHERE tenant_id=?", Integer.class, tenantId));
        });
    }

    @Test
    void webBffCreatesAndReturnsScopedExportJob() throws Exception {
        var browser = oidcLogin().idToken(token -> token.subject(subject));
        String created = mvc.perform(post("/bff/tenants/{tenantId}/exports", tenantId).with(browser).with(csrf())
                        .contentType("application/json")
                        .content("{\"formatVersion\":\"csv-v1\",\"fromDate\":\"2026-10-01\","
                                + "\"toDate\":\"2026-10-02\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andReturn().getResponse().getContentAsString();
        String exportId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        mvc.perform(get("/bff/tenants/{tenantId}/exports/{exportId}", tenantId, exportId).with(browser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(exportId))
                .andExpect(jsonPath("$.rowCount").value(0));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE tenant_id=? AND entity_id=? AND action='export.status_viewed'",
                    Integer.class, tenantId, UUID.fromString(exportId)));
        });
    }

    @Test
    void exportWorkerClaimRequiresCredentialAndLeasesEachJobOnce() throws Exception {
        String created = createExportJob(subject, "2026-10-01", "2026-10-02");
        String exportId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        mvc.perform(post("/internal/v1/exports/claim"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/exports/claim").header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(exportId))
                .andExpect(jsonPath("$.leaseToken").isString())
                .andExpect(jsonPath("$.attemptCount").value(1));
        mvc.perform(post("/internal/v1/exports/claim").header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    void exportWorkerReadsOnlyOrderedPagesWithCurrentLease() throws Exception {
        createExportFixtureTransaction(subject, "worker-page-first", "2026-10-01T10:00:00Z", "worker-page-create-001");
        createExportFixtureTransaction(subject, "worker-page-second", "2026-10-01T11:00:00Z", "worker-page-create-002");
        String created = createExportJob(subject, "2026-10-01", "2026-10-02");
        String exportId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        String claim = mvc.perform(post("/internal/v1/exports/claim")
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String lease = com.jayway.jsonpath.JsonPath.read(claim, "$.leaseToken");

        mvc.perform(get("/internal/v1/exports/{exportId}/rows", exportId)
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN)
                        .param("leaseToken", UUID.randomUUID().toString()).param("afterRowNumber", "0").param("limit", "1"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/v1/exports/{exportId}/rows", exportId)
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN)
                        .param("leaseToken", lease).param("afterRowNumber", "0").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].description").value("worker-page-first"))
                .andExpect(jsonPath("$.nextAfterRowNumber").value(1));
        mvc.perform(get("/internal/v1/exports/{exportId}/rows", exportId)
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN)
                        .param("leaseToken", lease).param("afterRowNumber", "1").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].description").value("worker-page-second"))
                .andExpect(jsonPath("$.nextAfterRowNumber").value(2));
    }

    @Test
    void exportWorkerCompleteIsIdempotentAndMakesJobReadyOnlyAfterStorage() throws Exception {
        String created = createExportJob(subject, "2026-10-01", "2026-10-02");
        String exportId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        String claim = mvc.perform(post("/internal/v1/exports/claim")
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String lease = com.jayway.jsonpath.JsonPath.read(claim, "$.leaseToken");
        String result = "{\"leaseToken\":\"" + lease + "\",\"objectKey\":\"tenants/" + tenantId
                + "/exports/" + exportId + "/" + UUID.randomUUID() + ".csv\",\"byteCount\":3,"
                + "\"sha256\":\"" + "a".repeat(64) + "\"}";
        mvc.perform(post("/internal/v1/exports/{exportId}/complete", exportId)
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN)
                        .contentType("application/json").content(result))
                .andExpect(status().isNoContent());
        mvc.perform(post("/internal/v1/exports/{exportId}/complete", exportId)
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN)
                        .contentType("application/json").content(result))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/tenants/{tenantId}/exports/{exportId}", tenantId, exportId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.downloadUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void exportDownloadUrlIsSignedOnlyForAuthorizedReadyAndUnexpiredJob() throws Exception {
        String created = createExportJob(subject, "2026-10-01", "2026-10-02");
        String exportId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        String objectKey = "tenants/" + tenantId + "/exports/" + exportId + "/" + UUID.randomUUID() + ".csv";
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE export_jobs SET status='ready', object_key=?, object_checksum_sha256=?, "
                            + "object_byte_count=3, completed_lease_token=?, expires_at=now()+interval '1 hour' "
                            + "WHERE tenant_id=? AND id=?", objectKey, "a".repeat(64), UUID.randomUUID(), tenantId,
                    UUID.fromString(exportId));
        });
        String signedUrl = "https://s3.example/private.csv?X-Amz-Signature=secret-signature";
        java.util.concurrent.atomic.AtomicReference<java.time.Duration> signedLifetime = new java.util.concurrent.atomic.AtomicReference<>();
        when(exportDownloadSigner.sign(eq(objectKey), any(java.time.Duration.class))).thenAnswer(invocation -> {
            signedLifetime.set(invocation.getArgument(1));
            return signedUrl;
        });

        mvc.perform(get("/api/v1/tenants/{tenantId}/exports/{exportId}", tenantId, exportId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.downloadUrl").value(signedUrl));
        verify(exportDownloadSigner).sign(objectKey, java.time.Duration.ofMinutes(5));

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE export_jobs SET expires_at=now()+interval '90 seconds' WHERE tenant_id=? AND id=?",
                    tenantId, UUID.fromString(exportId));
        });
        mvc.perform(get("/api/v1/tenants/{tenantId}/exports/{exportId}", tenantId, exportId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").value(signedUrl));
        org.junit.jupiter.api.Assertions.assertTrue(signedLifetime.get().compareTo(java.time.Duration.ZERO) > 0);
        org.junit.jupiter.api.Assertions.assertTrue(signedLifetime.get().compareTo(java.time.Duration.ofSeconds(90)) <= 0,
                "signed URL must expire with export job");

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE export_jobs SET expires_at=now()+interval '500 milliseconds' WHERE tenant_id=? AND id=?",
                    tenantId, UUID.fromString(exportId));
        });
        mvc.perform(get("/api/v1/tenants/{tenantId}/exports/{exportId}", tenantId, exportId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").value(org.hamcrest.Matchers.nullValue()));
        verify(exportDownloadSigner, times(2)).sign(eq(objectKey), any(java.time.Duration.class));

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE export_jobs SET expires_at=now()-interval '1 second' WHERE tenant_id=? AND id=?",
                    tenantId, UUID.fromString(exportId));
        });
        mvc.perform(get("/api/v1/tenants/{tenantId}/exports/{exportId}", tenantId, exportId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").value(org.hamcrest.Matchers.nullValue()));
        verify(exportDownloadSigner, times(2)).sign(eq(objectKey), any(java.time.Duration.class));

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            String audit = jdbc.queryForObject("SELECT coalesce(string_agg(after_state::text, ''), '') FROM audit_log "
                            + "WHERE tenant_id=? AND entity_id=?", String.class, tenantId, UUID.fromString(exportId));
            org.junit.jupiter.api.Assertions.assertFalse(audit.contains("X-Amz-Signature"),
                    "signed URL must not be persisted in audit state");
        });
    }

    @Test
    void exportDownloadDoesNotSignForAnotherMember() throws Exception {
        String memberSubject = "keycloak|export-download-member-" + UUID.randomUUID();
        addTenantMember(memberSubject, "Export download member", "member");
        String created = createExportJob(subject, "2026-10-01", "2026-10-02");
        String exportId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        String objectKey = "tenants/" + tenantId + "/exports/" + exportId + "/" + UUID.randomUUID() + ".csv";
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE export_jobs SET status='ready', object_key=?, object_checksum_sha256=?, "
                            + "object_byte_count=3, completed_lease_token=? WHERE tenant_id=? AND id=?",
                    objectKey, "a".repeat(64), UUID.randomUUID(), tenantId, UUID.fromString(exportId));
        });
        mvc.perform(get("/api/v1/tenants/{tenantId}/exports/{exportId}", tenantId, exportId)
                        .with(jwt().jwt(token -> token.subject(memberSubject))))
                .andExpect(status().isNotFound());
        verifyNoInteractions(exportDownloadSigner);
    }

    @Test
    void exportWorkerRetryAndExpiredLeaseFenceStaleAttempts() throws Exception {
        String created = createExportJob(subject, "2026-10-01", "2026-10-02");
        String exportId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        String first = mvc.perform(post("/internal/v1/exports/claim")
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String firstLease = com.jayway.jsonpath.JsonPath.read(first, "$.leaseToken");
        mvc.perform(post("/internal/v1/exports/{exportId}/fail", exportId)
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN).contentType("application/json")
                        .content("{\"leaseToken\":\"" + firstLease + "\",\"errorCode\":\"storage_unavailable\","
                                + "\"retryable\":true}"))
                .andExpect(status().isNoContent());
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE export_jobs SET next_attempt_at=now() WHERE tenant_id=? AND id=?", tenantId,
                    UUID.fromString(exportId));
        });
        String retry = mvc.perform(post("/internal/v1/exports/claim")
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attemptCount").value(2))
                .andReturn().getResponse().getContentAsString();
        String retryLease = com.jayway.jsonpath.JsonPath.read(retry, "$.leaseToken");
        org.junit.jupiter.api.Assertions.assertNotEquals(firstLease, retryLease);
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.export_service', 'true', true)", String.class);
            jdbc.update("UPDATE export_jobs SET lease_expires_at=now()-interval '1 second' WHERE id=?", UUID.fromString(exportId));
        });
        String reclaimed = mvc.perform(post("/internal/v1/exports/claim")
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attemptCount").value(3))
                .andReturn().getResponse().getContentAsString();
        String activeLease = com.jayway.jsonpath.JsonPath.read(reclaimed, "$.leaseToken");
        org.junit.jupiter.api.Assertions.assertNotEquals(retryLease, activeLease);
        mvc.perform(post("/internal/v1/exports/{exportId}/complete", exportId)
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN).contentType("application/json")
                        .content("{\"leaseToken\":\"" + retryLease + "\",\"objectKey\":\"tenants/" + tenantId
                                + "/exports/" + exportId + "/" + UUID.randomUUID() + ".csv\",\"byteCount\":3,"
                                + "\"sha256\":\"" + "b".repeat(64) + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/internal/v1/exports/{exportId}/fail", exportId)
                        .header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN).contentType("application/json")
                        .content("{\"leaseToken\":\"" + activeLease + "\",\"errorCode\":\"storage_corrupt\","
                                + "\"retryable\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/internal/v1/exports/claim").header("X-Export-Service-Token", EXPORT_SERVICE_TOKEN))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/tenants/{tenantId}/exports/{exportId}", tenantId, exportId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("failed"));
    }

    private String createExportJob(String exportSubject, String fromDate, String toDate) throws Exception {
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.export_service', 'true', true)", String.class);
            jdbc.update("UPDATE export_jobs SET status='expired', lease_token=NULL, lease_expires_at=NULL "
                    + "WHERE status IN ('queued','processing')");
        });
        return mvc.perform(post("/api/v1/tenants/{tenantId}/exports", tenantId)
                        .with(jwt().jwt(token -> token.subject(exportSubject))).contentType("application/json")
                        .content("{\"formatVersion\":\"csv-v1\",\"fromDate\":\"" + fromDate
                                + "\",\"toDate\":\"" + toDate + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    }

    private String createExportFixtureTransaction(String transactionSubject, String description,
                                                  String occurredAt, String idempotencyKey) throws Exception {
        String response = mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId)
                        .with(jwt().jwt(token -> token.subject(transactionSubject)))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"10.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"" + description + "\","
                                + "\"source\":\"manual\",\"occurredAt\":\"" + occurredAt + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.id");
    }

    private java.util.Map<String, Object> legacyGoalHistoryEntry(int index) {
        var entry = new java.util.LinkedHashMap<String, Object>();
        entry.put("legacyKey", "goal-history:" + String.format("%064d", index + 1) + ":" + index);
        entry.put("key", "product:coffee");
        entry.put("name", String.format("Legacy goal %02d", index));
        entry.put("scope", "product");
        entry.put("unit", "sum");
        entry.put("legacyTarget", index + 1);
        entry.put("legacyLimit", "0.00");
        entry.put("countTarget", 0);
        entry.put("monthlyLimit", "500.00");
        entry.put("bought", index);
        entry.put("spent", null);
        entry.put("met", null);
        entry.put("saved", null);
        entry.put("window", "01.01–31.01");
        entry.put("acceptedAt", "2026-01-01T00:00:00Z");
        entry.put("completedAt", String.format("2026-01-%02dT00:00:00Z", 31 - index));
        return entry;
    }

    private String legacyGoalHistoryRequest(UUID requestTenantId, UUID ownerId,
                                            List<java.util.Map<String, Object>> entries) throws Exception {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "tenantId", requestTenantId, "ownerUserId", ownerId, "outcomes", entries));
    }

    private UUID prepareNotificationMember() {
        String notificationSubject = "keycloak|f46-notification-" + UUID.randomUUID();
        UUID newMemberId = addTenantMember(notificationSubject, "F46 notification test", "owner");
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'telegram', ?)",
                    newMemberId, Long.toString(java.util.concurrent.ThreadLocalRandom.current()
                            .nextLong(900_000_000L, 990_000_000L)));
            jdbc.update("INSERT INTO notification_preferences (tenant_id, user_id, daily_enabled, weekly_enabled, "
                    + "next_daily_at, next_weekly_at) VALUES (?, ?, false, false, NULL, NULL) "
                    + "ON CONFLICT (tenant_id, user_id) DO UPDATE SET daily_enabled = false, weekly_enabled = false, "
                    + "next_daily_at = NULL, next_weekly_at = NULL", tenantId, newMemberId);
            return newMemberId;
        });
    }

    private UUID insertGoalOutcome(UUID ownerId, String origin, String name, int target, int bought, Instant completedAt) {
        UUID id = UUID.randomUUID();
        UUID goalId = "completed".equals(origin) ? UUID.randomUUID() : null;
        String goal = "{\"name\":\"" + name + "\",\"testFixture\":true,\"unit\":\"count\",\"countTarget\":" + target
                + ",\"monthlyLimit\":null}";
        String progress = "{\"bought\":" + bought + ",\"spent\":null,\"met\":" + (bought >= target) + "}";
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.update("INSERT INTO goal_outcomes (id, tenant_id, owner_user_id, goal_id, legacy_key, origin, "
                            + "goal_snapshot, progress_snapshot, accepted_at, completed_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)",
                    id, tenantId, ownerId, goalId, "legacy".equals(origin) ? "f46-" + id : null, origin,
                    goal, progress, java.sql.Timestamp.from(completedAt.minusSeconds(30L * 86400)),
                    java.sql.Timestamp.from(completedAt));
        });
        return id;
    }

    private UUID insertNotificationIntent(UUID ownerId, String kind, LocalDate scheduledDate) {
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.update("INSERT INTO notification_intents (id, tenant_id, user_id, digest_kind, scheduled_local_date, "
                            + "scheduled_at, timezone, language, report_from_date, report_to_date, available_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'UTC', 'ru', ?, ?, now() - interval '1 minute')",
                    id, tenantId, ownerId, kind, scheduledDate,
                    java.sql.Timestamp.from(Instant.now().minusSeconds(120)),
                    scheduledDate.minusDays("weekly".equals(kind) ? 6 : 0), scheduledDate);
        });
        return id;
    }

    private String claimNotifications(int limit) {
        try {
            return mvc.perform(post("/internal/v1/telegram/notifications/claim")
                            .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                            .contentType("application/json").content("{\"limit\":" + limit + "}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        } catch (Exception exception) {
            throw new IllegalStateException("Notification claim failed", exception);
        }
    }

    private void acknowledgeNotification(UUID intentId, String leaseToken, String outcome) throws Exception {
        mvc.perform(post("/internal/v1/telegram/notifications/{intentId}/delivery", intentId)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"leaseToken\":\"" + leaseToken + "\",\"outcome\":\"" + outcome + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void notificationDeliveryClaimIsDurableUniqueAndUsesCoreReport() throws Exception {
        cleanupNotificationTestFixtures();
        java.time.OffsetDateTime dueAt = java.time.OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)
                .withSecond(0).withNano(0);
        LocalDate dueDate = dueAt.toLocalDate();
        addBudgetHistoryTransaction("expense", "1234.50", dueDate, "notification test");
        UUID userId = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
            UUID memberId = jdbc.queryForObject("SELECT user_id FROM memberships WHERE tenant_id = ? AND subject = ?",
                    UUID.class, tenantId, subject);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'telegram', ?)",
                    memberId, Long.toString(java.util.concurrent.ThreadLocalRandom.current()
                            .nextLong(900_000_000L, 990_000_000L)));
            return memberId;
        });

        String claimBody = "{\"limit\":10}";
        mvc.perform(post("/internal/v1/telegram/notifications/claim")
                        .header("X-Finance-Service-Token", "wrong-token")
                        .contentType("application/json").content(claimBody))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/telegram/notifications/claim")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalTime.of(21, 0), jdbc.queryForObject(
                    "SELECT daily_local_time FROM notification_preferences WHERE tenant_id = ? AND user_id = ?",
                    java.time.LocalTime.class, tenantId, userId));
            org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalTime.of(19, 0), jdbc.queryForObject(
                    "SELECT weekly_local_time FROM notification_preferences WHERE tenant_id = ? AND user_id = ?",
                    java.time.LocalTime.class, tenantId, userId));
            org.junit.jupiter.api.Assertions.assertEquals("ru", jdbc.queryForObject(
                    "SELECT language FROM notification_preferences WHERE tenant_id = ? AND user_id = ?",
                    String.class, tenantId, userId));
            jdbc.update("UPDATE notification_preferences SET daily_enabled = true, daily_local_time = ?, next_daily_at = ?, "
                            + "weekly_enabled = false, next_weekly_at = NULL WHERE tenant_id = ? AND user_id = ?",
                    dueAt.toLocalTime(), dueAt, tenantId, userId);
        });
        String first = mvc.perform(post("/internal/v1/telegram/notifications/claim")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].digestKind").value("daily"))
                .andExpect(jsonPath("$.items[0].language").value("ru"))
                .andExpect(jsonPath("$.items[0].report.transactionCount").value(1))
                .andExpect(jsonPath("$.items[0].report.expenseTotal").value("1234.50"))
                .andExpect(jsonPath("$.items[0].report.rolling7FoodStatus.spent").exists())
                .andReturn().getResponse().getContentAsString();
        String intentId = com.jayway.jsonpath.JsonPath.read(first, "$.items[0].intentId");
        String leaseToken = com.jayway.jsonpath.JsonPath.read(first, "$.items[0].leaseToken");

        mvc.perform(post("/internal/v1/telegram/notifications/claim")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mvc.perform(post("/internal/v1/telegram/notifications/{intentId}/delivery", intentId)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"leaseToken\":\"" + leaseToken
                                + "\",\"outcome\":\"retryable_failure\",\"errorCode\":\"telegram_timeout\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("pending"));

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.update("UPDATE notification_intents SET available_at = now() WHERE id = ?", UUID.fromString(intentId));
        });
        String retry = mvc.perform(post("/internal/v1/telegram/notifications/claim")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].intentId").value(intentId))
                .andExpect(jsonPath("$.items[0].attemptNumber").value(2))
                .andReturn().getResponse().getContentAsString();
        String retryLeaseToken = com.jayway.jsonpath.JsonPath.read(retry, "$.items[0].leaseToken");
        mvc.perform(post("/internal/v1/telegram/notifications/{intentId}/delivery", intentId)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"leaseToken\":\"" + retryLeaseToken
                                + "\",\"outcome\":\"delivered\",\"providerMessageId\":\"7788\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("delivered"));
        mvc.perform(post("/internal/v1/telegram/notifications/{intentId}/delivery", intentId)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"leaseToken\":\"" + retryLeaseToken
                                + "\",\"outcome\":\"delivered\",\"providerMessageId\":\"7788\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/internal/v1/telegram/notifications/claim")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            Integer occurrenceCount = jdbc.queryForObject("SELECT count(*) FROM notification_intents WHERE tenant_id = ? "
                    + "AND user_id = ? AND digest_kind = 'daily' AND scheduled_local_date = ?",
                    Integer.class, tenantId, userId, dueDate);
            org.junit.jupiter.api.Assertions.assertEquals(1, occurrenceCount);
            List<String> outcomes = jdbc.query("SELECT outcome FROM notification_delivery_attempts WHERE intent_id = ? "
                            + "ORDER BY attempt_number", (rs, row) -> rs.getString("outcome"), UUID.fromString(intentId));
            org.junit.jupiter.api.Assertions.assertEquals(List.of("retryable_failure", "delivered"), outcomes);
            return null;
        });
    }

    private void cleanupNotificationTestFixtures() {
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("DELETE FROM goal_outcomes WHERE tenant_id = ? AND goal_snapshot->>'testFixture' = 'true'",
                    tenantId);
            List<UUID> users = jdbc.query("SELECT user_id FROM external_identities WHERE provider = 'telegram' "
                            + "AND subject ~ '^[1-9][0-9]{8,9}$'", (rs, row) -> rs.getObject("user_id", UUID.class));
            for (UUID user : users) {
                jdbc.update("DELETE FROM notification_intents WHERE user_id = ?", user);
                jdbc.update("DELETE FROM notification_preferences WHERE user_id = ?", user);
                jdbc.update("DELETE FROM external_identities WHERE user_id = ? AND provider = 'telegram'", user);
            }
            Integer remaining = jdbc.queryForObject("SELECT count(*) FROM external_identities WHERE provider = 'telegram' "
                    + "AND subject ~ '^[1-9][0-9]{8,9}$'", Integer.class);
            org.junit.jupiter.api.Assertions.assertEquals(0, remaining,
                    "notification integration cleanup must remove both 9- and 10-digit synthetic Telegram IDs");
        });
    }

    @Test
    void dashboardSafeToSpendUsesProfilePlanReserveAndMonthlyFacts() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        prepareBudgetHistory("100000.00");
        addBudgetHistoryTransaction("income", "50000.00", today, "Зарплата");
        addBudgetHistoryTransaction("expense", "20000.00", today, "Текущие расходы");

        mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safeToSpend.incomeBasis").value("actual_income"))
                .andExpect(jsonPath("$.safeToSpend.incomeBase").value("50000.00"))
                .andExpect(jsonPath("$.safeToSpend.monthlyExpenses").value("20000.00"))
                .andExpect(jsonPath("$.safeToSpend.reserve").value("5000.00"))
                .andExpect(jsonPath("$.safeToSpend.promisedPayments").value("0.00"))
                .andExpect(jsonPath("$.safeToSpend.safeTotal").value("25000.00"))
                .andExpect(jsonPath("$.safeToSpend.horizonDate").value(
                        java.time.YearMonth.from(today).atEndOfMonth().toString()));
    }

    @Test
    void dashboardUsesFreshRecurringHistoryAndIncludesChargeDueOnProjectedPayday() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        prepareBudgetHistory("100000.00");
        for (int monthsAgo = 2; monthsAgo >= 0; monthsAgo--) {
            LocalDate occurrence = today.minusDays(monthsAgo * 30L);
            addBudgetHistoryTransaction("income", "50000.00", occurrence, "Зарплата");
            addBudgetHistoryTransaction("expense", "4000.00", occurrence, "Подписка облако");
        }

        mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safeToSpend.incomeBasis").value("actual_income"))
                .andExpect(jsonPath("$.safeToSpend.horizonDate").value(today.plusDays(30).toString()))
                .andExpect(jsonPath("$.safeToSpend.monthlyExpenses").value("4000.00"))
                .andExpect(jsonPath("$.safeToSpend.promisedPayments").value("4000.00"))
                .andExpect(jsonPath("$.safeToSpend.safeTotal").value("37000.00"))
                .andExpect(jsonPath("$.safeToSpend.safePerDay").value("1233.33"));
    }

    @Test
    void dashboardDoesNotInventSafeToSpendWithoutAProfilePlan() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        addBudgetHistoryTransaction("income", "50000.00", today, "Зарплата");

        mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safeToSpend").value(org.hamcrest.Matchers.nullValue()));
    }

    private void prepareBudgetHistory(String plannedIncome) {
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE member_profiles SET planned_income = ? WHERE tenant_id = ? AND user_id = "
                            + "(SELECT user_id FROM memberships WHERE tenant_id = ? AND subject = ?)",
                    new java.math.BigDecimal(plannedIncome), tenantId, tenantId, subject);
            return null;
        });
    }

    private void addBudgetHistoryTransaction(String type, String amount, LocalDate occurredDate, String description) {
        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = jdbc.queryForObject(
                    "SELECT user_id FROM memberships WHERE tenant_id = ? AND subject = ?", UUID.class, tenantId, subject);
            jdbc.update("""
                    INSERT INTO transactions (tenant_id, owner_subject, owner_user_id, type, amount, currency,
                      category_code, description, source, occurred_at)
                    VALUES (?, ?, ?, ?, ?, 'RUB', 'еда', ?, 'test', ?)
                    """, tenantId, subject, ownerId, type, new java.math.BigDecimal(amount), description,
                    java.sql.Timestamp.from(occurredDate.atTime(12, 0).toInstant(ZoneOffset.UTC)));
            return null;
        });
    }

    private java.util.Map<String, Object> profileForSubject(String profileSubject) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, profileSubject);
            UUID profileTenant = jdbc.queryForObject("SELECT tenant_id FROM memberships WHERE subject = ?",
                    UUID.class, profileSubject);
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, profileTenant.toString());
            return jdbc.queryForMap("SELECT p.display_name, p.planned_income, p.onboarding_state, p.timezone, p.currency "
                    + "FROM member_profiles p JOIN memberships m USING (tenant_id, user_id) "
                    + "WHERE m.subject = ? AND p.tenant_id = ?", profileSubject, profileTenant);
        });
    }

    private String issueTelegramLinkCode(String profileSubject) throws Exception {
        var response = mvc.perform(post("/api/v1/me/telegram-link")
                        .with(jwt().jwt(token -> token.subject(profileSubject))))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(response.getResponse().getContentAsString(), "$.code");
    }

    private String profileNameSourceForSubject(String profileSubject) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, profileSubject);
            UUID profileTenant = jdbc.queryForObject("SELECT tenant_id FROM memberships WHERE subject = ?",
                    UUID.class, profileSubject);
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, profileTenant.toString());
            return jdbc.queryForObject("SELECT display_name_source FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                    String.class, profileTenant, userIdFor(profileSubject));
        });
    }

    private UUID userIdFor(String profileSubject) {
        return jdbc.queryForObject("SELECT user_id FROM external_identities WHERE provider = 'keycloak' AND subject = ?",
                UUID.class, profileSubject);
    }

    private UUID addTenantMember(String memberSubject, String displayName, String role) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)",
                    userId, memberSubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, ?, ?)",
                    tenantId, memberSubject, role, userId);
            jdbc.update("INSERT INTO member_profiles (tenant_id, user_id, display_name, timezone) VALUES (?, ?, ?, 'UTC')",
                    tenantId, userId, displayName);
            return userId;
        });
    }

    private static long newTelegramUserId() {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
    }

    @Test
    void browserSessionCanUseSameTenantApiThroughBff() throws Exception {
        mvc.perform(get("/bff/me/tenants").with(oidcLogin()
                        .idToken(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$[0].role").value("owner"));
        mvc.perform(get("/bff/tenants/" + tenantId + "/profile/me").with(oidcLogin()
                        .idToken(token -> token.subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("API test member"));
        mvc.perform(patch("/bff/tenants/" + tenantId + "/profile/me").with(oidcLogin()
                        .idToken(token -> token.subject(subject))).with(csrf()).contentType("application/json")
                        .content("{\"displayName\":\"Browser Member\",\"plannedIncome\":85000,\"onboardingState\":\"complete\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Browser Member"));

        String body = "{\"type\":\"expense\",\"amount\":\"7.50\",\"currency\":\"RUB\","
                + "\"categoryCode\":\"food\",\"description\":\"Coffee\","
                + "\"occurredAt\":\"2026-10-01T10:00:00Z\"}";
        mvc.perform(post("/bff/tenants/" + tenantId + "/transactions")
                        .with(oidcLogin().idToken(token -> token.subject(subject)))
                        .with(csrf()).header("Idempotency-Key", "web-bff-request-0001")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("7.50"));
        mvc.perform(get("/bff/tenants/" + tenantId + "/transactions")
                        .with(oidcLogin().idToken(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].description").value("Coffee"));
    }

    @Test
    void incomeKeepsSourceAndDoesNotBecomeExpense() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        String path = "/api/v1/tenants/" + tenantId + "/transactions";
        var created = mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "income-source-acceptance-001")
                        .contentType("application/json")
                        .content("""
                                {"type":"income","amount":"125000.00","currency":"RUB","categoryCode":"зарплата","description":"Октябрьская зарплата","source":"salary","occurredAt":"2026-10-01T09:30:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("income"))
                .andExpect(jsonPath("$.description").value("Октябрьская зарплата"))
                .andExpect(jsonPath("$.source").value("salary"))
                .andExpect(jsonPath("$.occurredAt").value("2026-10-01T09:30:00Z"))
                .andReturn();
        String transactionId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(patch(path + "/" + transactionId).with(auth)
                        .header("Idempotency-Key", "income-source-edit-0001")
                        .header("If-Match", "\"1\"").contentType("application/json")
                        .content("""
                                {"type":"income","amount":"135000.00","currency":"RUB","categoryCode":"зарплата","description":"Октябрьская зарплата","source":"monthly_salary","occurredAt":"2026-10-02T09:30:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("monthly_salary"))
                .andExpect(jsonPath("$.occurredAt").value("2026-10-02T09:30:00Z"));

        mvc.perform(get(path).with(auth).param("type", "income").param("from", "2026-10-02")
                        .param("to", "2026-10-02").param("search", "Октябрьская"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].source").value("monthly_salary"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth).param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incomeTotal").value("135000.00"))
                .andExpect(jsonPath("$.expenseTotal").value("0.00"));
    }

    @Test
    void transactionHistoryCursorPaginatesWithoutDuplicates() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        String path = "/api/v1/tenants/" + tenantId + "/transactions";
        for (int hour = 10; hour < 13; hour++) {
            mvc.perform(post(path).with(auth)
                            .header("Idempotency-Key", "history-page-create-" + hour + "-001")
                            .contentType("application/json")
                            .content("""
                                    {"type":"expense","amount":"10.00","currency":"RUB","categoryCode":"food","description":"Page %d","occurredAt":"2026-10-01T%02d:00:00Z"}
                                    """.formatted(hour - 9, hour)))
                    .andExpect(status().isCreated());
        }
        var firstPage = mvc.perform(get(path).with(auth).param("pageSize", "2")
                        .param("from", "2026-10-01").param("to", "2026-10-01").param("type", "expense"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty()).andReturn();
        String cursor = com.jayway.jsonpath.JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");
        String firstId = com.jayway.jsonpath.JsonPath.read(firstPage.getResponse().getContentAsString(), "$.items[0].id");
        String secondId = com.jayway.jsonpath.JsonPath.read(firstPage.getResponse().getContentAsString(), "$.items[1].id");
        var lastPage = mvc.perform(get(path).with(auth).param("pageSize", "2").param("cursor", cursor)
                        .param("from", "2026-10-01").param("to", "2026-10-01").param("type", "expense"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue())).andReturn();
        String lastId = com.jayway.jsonpath.JsonPath.read(lastPage.getResponse().getContentAsString(), "$.items[0].id");
        org.junit.jupiter.api.Assertions.assertFalse(List.of(firstId, secondId).contains(lastId));
    }

    @Test
    void ownerCanFilterTransactionsByMemberWhileMembersCannotReadFamilyHistory() throws Exception {
        var ownerAuth = jwt().jwt(token -> token.subject(subject));
        String path = "/api/v1/tenants/" + tenantId + "/transactions";
        String membersPath = "/api/v1/tenants/" + tenantId + "/members";
        mvc.perform(post(path).with(ownerAuth).header("Idempotency-Key", "member-filter-owner-0001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"12.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Owner lunch\","
                                + "\"occurredAt\":\"2026-10-01T10:00:00Z\"}"))
                .andExpect(status().isCreated());

        String memberSubject = "keycloak|transaction-filter-member-" + UUID.randomUUID();
        UUID memberId = addTenantMember(memberSubject, "Taylor", "member");
        var memberAuth = jwt().jwt(token -> token.subject(memberSubject));
        mvc.perform(post(path).with(memberAuth).header("Idempotency-Key", "member-filter-member-001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"18.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Taylor lunch\","
                                + "\"occurredAt\":\"2026-10-01T11:00:00Z\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get(membersPath).with(ownerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").exists())
                .andExpect(jsonPath("$[1].displayName").value("Taylor"))
                .andExpect(jsonPath("$[1].userId").value(memberId.toString()));
        mvc.perform(get(membersPath).with(memberAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Taylor"))
                .andExpect(jsonPath("$[0].userId").value(memberId.toString()));

        mvc.perform(get(path).with(ownerAuth).param("memberId", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.memberName == 'API test member')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.memberName == 'Taylor')]").isNotEmpty());
        mvc.perform(get(path).with(ownerAuth).param("memberId", memberId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].description").value("Taylor lunch"));
        mvc.perform(get(path).with(memberAuth).param("memberId", "all"))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(memberAuth).param("memberId", userIdFor(subject).toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void transactionTypeFiltersIncludeExpenseDebtPaymentAndIncomeRefund() throws Exception {
        var ownerAuth = jwt().jwt(token -> token.subject(subject));
        UUID ownerId = userIdFor(subject);
        String path = "/api/v1/tenants/" + tenantId + "/transactions";

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            Object[][] fixtures = {
                    {ownerId, subject, "expense", "Owner expense", "2026-10-01T08:00:00Z"},
                    {ownerId, subject, "debt_payment", "Owner debt payment", "2026-10-01T09:00:00Z"},
                    {ownerId, subject, "income", "Owner income", "2026-10-01T10:00:00Z"},
                    {ownerId, subject, "refund", "Owner refund", "2026-10-01T11:00:00Z"},
            };
            for (Object[] fixture : fixtures) {
                jdbc.update("""
                        INSERT INTO transactions (tenant_id, owner_subject, owner_user_id, type, amount, currency,
                            category_code, description, source, occurred_at)
                        VALUES (?, ?, ?, ?, 12.34, 'RUB', 'food', ?, 'test', ?)
                        """, tenantId, fixture[1], fixture[0], fixture[2], fixture[3],
                        java.sql.Timestamp.from(Instant.parse((String) fixture[4])));
            }
        });

        mvc.perform(get(path).with(ownerAuth).param("type", "expense"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].type").value("debt_payment"))
                .andExpect(jsonPath("$.items[1].type").value("expense"));
        mvc.perform(get(path).with(ownerAuth).param("type", "income"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].type").value("refund"))
                .andExpect(jsonPath("$.items[1].type").value("income"));
    }

    @Test
    void transactionSearchMatchesMemberDisplayName() throws Exception {
        var ownerAuth = jwt().jwt(token -> token.subject(subject));
        String memberSubject = "keycloak|transaction-search-member-" + UUID.randomUUID();
        UUID memberId = addTenantMember(memberSubject, "Taylor Display", "member");
        String path = "/api/v1/tenants/" + tenantId + "/transactions";
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("""
                    INSERT INTO transactions (tenant_id, owner_subject, owner_user_id, type, amount, currency,
                        category_code, description, source, occurred_at)
                    VALUES (?, ?, ?, 'expense', 12.34, 'RUB', 'food', 'Member expense', 'test', ?)
                    """, tenantId, memberSubject, memberId,
                    java.sql.Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")));
        });

        mvc.perform(get(path).with(ownerAuth).param("memberId", "all").param("search", "Taylor Display"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].memberName").value("Taylor Display"));
    }

    @Test
    void ownerCanEditAndVoidFamilyTransactionsWhileMembersCannotEditOthers() throws Exception {
        var ownerAuth = jwt().jwt(token -> token.subject(subject));
        String memberSubject = "keycloak|transaction-family-edit-" + UUID.randomUUID();
        addTenantMember(memberSubject, "Taylor", "member");
        var memberAuth = jwt().jwt(token -> token.subject(memberSubject));
        String path = "/api/v1/tenants/" + tenantId + "/transactions";
        var memberTransaction = mvc.perform(post(path).with(memberAuth)
                        .header("Idempotency-Key", "family-member-create-0001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"20.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Taylor old\","
                                + "\"occurredAt\":\"2026-10-01T10:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn();
        String memberTransactionId = com.jayway.jsonpath.JsonPath.read(
                memberTransaction.getResponse().getContentAsString(), "$.id");
        mvc.perform(patch(path + "/" + memberTransactionId).with(ownerAuth)
                        .header("Idempotency-Key", "family-owner-edit-0001")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"25.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"subcategoryCode\":\"market\","
                                + "\"description\":\"Taylor corrected\",\"source\":\"panel\","
                                + "\"occurredAt\":\"2026-10-02T10:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("25.00"))
                .andExpect(jsonPath("$.description").value("Taylor corrected"))
                .andExpect(jsonPath("$.memberName").value("Taylor"));

        var ownerTransaction = mvc.perform(post(path).with(ownerAuth)
                        .header("Idempotency-Key", "family-owner-create-0001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"10.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Owner own\","
                                + "\"occurredAt\":\"2026-10-03T10:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn();
        String ownerTransactionId = com.jayway.jsonpath.JsonPath.read(
                ownerTransaction.getResponse().getContentAsString(), "$.id");
        mvc.perform(patch(path + "/" + ownerTransactionId).with(memberAuth)
                        .header("Idempotency-Key", "family-member-deny-0001")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"11.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"forbidden\","
                                + "\"occurredAt\":\"2026-10-03T10:00:00Z\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(path + "/" + memberTransactionId + "/void").with(ownerAuth)
                        .header("Idempotency-Key", "family-owner-void-0001")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("voided"));
    }

    @Test
    void ownerCanAssignAndReassignTransactionsToActiveMembers() throws Exception {
        var ownerAuth = jwt().jwt(token -> token.subject(subject));
        String memberSubject = "keycloak|transaction-assignment-member-" + UUID.randomUUID();
        UUID memberId = addTenantMember(memberSubject, "Taylor", "member");
        String path = "/api/v1/tenants/" + tenantId + "/transactions";
        var created = mvc.perform(post(path).with(ownerAuth)
                        .header("Idempotency-Key", "owner-assign-member-0001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"20.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Family lunch\","
                                + "\"ownerUserId\":\"" + memberId + "\","
                                + "\"occurredAt\":\"2026-10-01T10:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId").value(memberId.toString()))
                .andExpect(jsonPath("$.memberName").value("Taylor"))
                .andReturn();
        String transactionId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(patch(path + "/" + transactionId).with(ownerAuth)
                        .header("Idempotency-Key", "owner-reassign-member-001")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"21.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Family lunch updated\","
                                + "\"ownerUserId\":\"" + userIdFor(subject) + "\","
                                + "\"occurredAt\":\"2026-10-01T10:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").value(userIdFor(subject).toString()))
                .andExpect(jsonPath("$.memberName").value("API test member"));

        var memberAuth = jwt().jwt(token -> token.subject(memberSubject));
        mvc.perform(post(path).with(memberAuth)
                        .header("Idempotency-Key", "member-cannot-assign-001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"20.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Forbidden assignment\","
                                + "\"ownerUserId\":\"" + userIdFor(subject) + "\","
                                + "\"occurredAt\":\"2026-10-01T10:00:00Z\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void transactionEditAndVoidKeepVersionedHistoryAndReverseSpend() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        String path = "/api/v1/tenants/" + tenantId + "/transactions";
        var created = mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "transaction-edit-void-create-01")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"20.00","currency":"RUB","categoryCode":"food","description":"Old name","source":"manual","occurredAt":"2026-10-01T10:00:00Z"}
                                """))
                .andExpect(status().isCreated()).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(patch(path + "/" + id).with(auth)
                        .header("Idempotency-Key", "transaction-edit-void-edit-01")
                        .header("If-Match", "\"1\"").contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"25.00","currency":"RUB","categoryCode":"food","description":"Corrected name","source":"receipt","occurredAt":"2026-10-02T10:00:00Z"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.source").value("receipt"));
        mvc.perform(patch(path + "/" + id).with(auth)
                        .header("Idempotency-Key", "transaction-edit-void-stale-01")
                        .header("If-Match", "\"1\"").contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"30.00","currency":"RUB","categoryCode":"food","description":"Stale name","source":"manual","occurredAt":"2026-10-02T10:00:00Z"}
                                """))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post(path + "/" + id + "/void").with(auth)
                        .header("Idempotency-Key", "transaction-edit-void-confirm-01")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("voided"))
                .andExpect(jsonPath("$.version").value(3));
        mvc.perform(post(path + "/" + id + "/void").with(auth)
                        .header("Idempotency-Key", "transaction-edit-void-confirm-01")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("voided"));
        mvc.perform(get(path).with(auth).param("search", "Corrected name"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("voided"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth).param("month", "2026-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expenseTotal").value("0.00"))
                .andExpect(jsonPath("$.transactionCount").value(0));
    }

    @Test
    void viewerCanReadButCannotMutateTransactions() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        String path = "/api/v1/tenants/" + tenantId + "/transactions";
        var created = mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "viewer-permission-create-01")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"15.00","currency":"RUB","categoryCode":"food","description":"Viewer check","occurredAt":"2026-10-01T10:00:00Z"}
                                """))
                .andExpect(status().isCreated()).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE memberships SET role = 'viewer' WHERE tenant_id = ? AND subject = ?", tenantId, subject);
        });

        mvc.perform(get(path).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "viewer-permission-create-02")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"9.00","currency":"RUB","categoryCode":"food","description":"Denied create","occurredAt":"2026-10-01T11:00:00Z"}
                                """))
                .andExpect(status().isForbidden());
        mvc.perform(patch(path + "/" + id).with(auth)
                        .header("Idempotency-Key", "viewer-permission-edit-0001")
                        .header("If-Match", "\"1\"").contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"18.00","currency":"RUB","categoryCode":"food","description":"Denied edit","occurredAt":"2026-10-01T10:00:00Z"}
                                """))
                .andExpect(status().isForbidden());
        mvc.perform(post(path + "/" + id + "/void").with(auth)
                        .header("Idempotency-Key", "viewer-permission-void-0001")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isForbidden());
        int aiCallsBeforeDraft = AI_CALLS.get();
        mvc.perform(post("/api/v1/tenants/" + tenantId + "/transaction-drafts").with(auth)
                        .header("Idempotency-Key", "viewer-permission-draft-01")
                        .contentType("application/json").content("{\"text\":\"coffee 20\"}"))
                .andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertEquals(aiCallsBeforeDraft, AI_CALLS.get(),
                "viewer draft must be rejected before any model call");
    }

    @Test
    void receiptDraftKeepsCashAndItemTotalsSeparateAndIsIdempotent() throws Exception {
        String authKey = "receipt-create-idempotency-01";
        String body = """
                {"cashTotal":"150.00","merchant":"Market","receiptDate":"2026-10-01","items":[
                  {"name":"Tea","quantity":"1","unitPrice":"40.00","lineSum":"40.00"},
                  {"name":"Bread","quantity":"2","unitPrice":"30.00","lineSum":"60.00"}]}
                """;
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(auth)
                        .header("Idempotency-Key", authKey).contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("review_required"))
                .andExpect(jsonPath("$.cashTotal").value("150.00"))
                .andExpect(jsonPath("$.itemsTotal").value("100.00"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(auth)
                        .header("Idempotency-Key", authKey).contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(receiptId));

        mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(auth)
                        .header("Idempotency-Key", authKey).contentType("application/json")
                        .content(body.replace("150.00", "151.00")))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/receipts/" + receiptId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashTotal").value("150.00"))
                .andExpect(jsonPath("$.itemsTotal").value("100.00"));
    }

    @Test
    void receiptConfirmationEmitsVersionedPriceProjectionSnapshotOnce() throws Exception {
        String path = "/api/v1/tenants/" + tenantId + "/receipts";
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "receipt-price-event-create-01")
                        .contentType("application/json")
                        .content("{\"cashTotal\":\"50.00\",\"merchant\":\"Market\",\"receiptDate\":\"2026-10-01\",\"items\":["
                                + "{\"name\":\"Tea 500g\",\"quantity\":\"2\",\"unitPrice\":\"25.00\",\"lineSum\":\"50.00\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String confirmPath = path + "/" + receiptId + "/confirm";
        var confirmed = mvc.perform(post(confirmPath).with(auth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "receipt-price-event-confirm-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("confirmed"))
                .andExpect(jsonPath("$.version").value(2)).andReturn();
        mvc.perform(post(confirmPath).with(auth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "receipt-price-event-confirm-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

        String event = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE tenant_id = ? "
                    + "AND aggregate_type = 'receipt' AND aggregate_id = ? AND event_type = 'receipt.confirmed'",
                    String.class, tenantId, UUID.fromString(receiptId));
        });
        org.junit.jupiter.api.Assertions.assertEquals("receipt.confirmed",
                com.jayway.jsonpath.JsonPath.read(event, "$.event_type"));
        org.junit.jupiter.api.Assertions.assertEquals("receipt",
                com.jayway.jsonpath.JsonPath.read(event, "$.aggregate_type"));
        org.junit.jupiter.api.Assertions.assertEquals(2,
                com.jayway.jsonpath.JsonPath.<Integer>read(event, "$.aggregate_version").intValue());
        org.junit.jupiter.api.Assertions.assertEquals("2026-10-01",
                com.jayway.jsonpath.JsonPath.read(event, "$.payload.receipt_date"));
        org.junit.jupiter.api.Assertions.assertEquals("RUB",
                com.jayway.jsonpath.JsonPath.read(event, "$.payload.currency"));
        org.junit.jupiter.api.Assertions.assertEquals("Market",
                com.jayway.jsonpath.JsonPath.read(event, "$.payload.merchant"));
        org.junit.jupiter.api.Assertions.assertEquals(
                com.jayway.jsonpath.JsonPath.<String>read(confirmed.getResponse().getContentAsString(), "$.transactionId"),
                com.jayway.jsonpath.JsonPath.<String>read(event, "$.payload.transaction_id"));
        org.junit.jupiter.api.Assertions.assertEquals("Tea 500g",
                com.jayway.jsonpath.JsonPath.read(event, "$.payload.items[0].name"));
        org.junit.jupiter.api.Assertions.assertEquals("2.000000",
                com.jayway.jsonpath.JsonPath.read(event, "$.payload.items[0].quantity"));
        org.junit.jupiter.api.Assertions.assertEquals("50.00",
                com.jayway.jsonpath.JsonPath.read(event, "$.payload.items[0].line_sum"));
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(subject).toString(),
                com.jayway.jsonpath.JsonPath.read(event, "$.payload.owner_user_id"));
    }

    @Test
    void priceHistoryUsesConfirmedOwnReceiptAndCoreResolvedMemberScope() throws Exception {
        String path = "/api/v1/tenants/" + tenantId + "/receipts";
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "receipt-price-api-create-0001")
                        .contentType("application/json")
                        .content("{\"cashTotal\":\"50.00\",\"merchant\":\"Market\",\"receiptDate\":\"2026-10-01\",\"items\":["
                                + "{\"name\":\"Tea 500g\",\"quantity\":\"2\",\"unitPrice\":\"25.00\",\"lineSum\":\"50.00\"}] }"))
                .andExpect(status().isCreated()).andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String itemId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.items[0].id");
        mvc.perform(post(path + "/" + receiptId + "/confirm").with(auth).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "receipt-price-api-confirm-0001"))
                .andExpect(status().isOk());

        LAST_PRICE_COMPARE_REQUEST.set("");
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/products/price-history").with(auth)
                        .param("receiptId", receiptId).param("itemId", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithmVersion").value("price-projection.v1"))
                .andExpect(jsonPath("$.hasBaseline").value(true))
                .andExpect(jsonPath("$.currentUnitPrice").value("25.000000"))
                .andExpect(jsonPath("$.baselineUnitPrice").value("10.000000"))
                .andExpect(jsonPath("$.signal").value(true))
                .andExpect(jsonPath("$.direction").value("up"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[1].current").value(true));
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(subject).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_PRICE_COMPARE_REQUEST.get(), "$.ownerUserId"));
        org.junit.jupiter.api.Assertions.assertEquals(receiptId,
                com.jayway.jsonpath.JsonPath.read(LAST_PRICE_COMPARE_REQUEST.get(), "$.receiptId"));
        org.junit.jupiter.api.Assertions.assertEquals(itemId,
                com.jayway.jsonpath.JsonPath.read(LAST_PRICE_COMPARE_REQUEST.get(), "$.itemId"));

        String memberSubject = "keycloak|price-history-member-" + UUID.randomUUID();
        addTenantMember(memberSubject, "Taylor", "member");
        String ownerScopedRequest = LAST_PRICE_COMPARE_REQUEST.get();
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/products/price-history")
                        .with(jwt().jwt(token -> token.subject(memberSubject)))
                        .param("receiptId", receiptId).param("itemId", itemId))
                .andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals(ownerScopedRequest, LAST_PRICE_COMPARE_REQUEST.get(),
                "another family member must not send this receipt item to analytics");

        mvc.perform(get("/api/v1/tenants/" + tenantId + "/products/price-history").with(auth)
                        .param("receiptId", receiptId).param("itemId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void productCatalogUsesAuthenticatedMemberScope() throws Exception {
        LAST_PRICE_CATALOG_REQUEST.set("");
        mvc.perform(get("/api/v1/tenants/{tenantId}/products", tenantId)
                        .with(jwt().jwt(token -> token.subject(subject))).param("query", "tea"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("search"))
                .andExpect(jsonPath("$.query").value("tea"))
                .andExpect(jsonPath("$.products").isArray());
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(subject).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_PRICE_CATALOG_REQUEST.get(), "$.ownerUserId"));

        String memberSubject = "keycloak|product-catalog-member-" + UUID.randomUUID();
        addTenantMember(memberSubject, "Taylor", "member");
        mvc.perform(get("/api/v1/tenants/{tenantId}/products", tenantId)
                        .with(jwt().jwt(token -> token.subject(memberSubject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("catalog"));
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(memberSubject).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_PRICE_CATALOG_REQUEST.get(), "$.ownerUserId"),
                "a member catalog must use that member's private purchase history");
    }

    @Test
    void shoppingCandidatesUseAuthenticatedMemberScopeAndNeverClaimInventory() throws Exception {
        LAST_SHOPPING_REQUEST.set("");
        mvc.perform(get("/api/v1/tenants/{tenantId}/shopping", tenantId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].purchaseCount").value(3))
                .andExpect(jsonPath("$.estimatedListCost").value("100.00"))
                .andExpect(jsonPath("$.inventoryTracked").value(false));
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(subject).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_SHOPPING_REQUEST.get(), "$.ownerUserId"));

        String memberSubject = "keycloak|shopping-member-" + UUID.randomUUID();
        addTenantMember(memberSubject, "Taylor", "member");
        mvc.perform(get("/api/v1/tenants/{tenantId}/shopping", tenantId)
                        .with(jwt().jwt(token -> token.subject(memberSubject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryTracked").value(false));
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(memberSubject).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_SHOPPING_REQUEST.get(), "$.ownerUserId"),
                "each member must receive only their own shopping rhythm");
    }

    @Test
    void browserShoppingCandidatesUseAuthenticatedMemberScope() throws Exception {
        LAST_SHOPPING_REQUEST.set("");
        mvc.perform(get("/bff/tenants/{tenantId}/shopping", tenantId)
                        .with(oidcLogin().idToken(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryTracked").value(false));
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(subject).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_SHOPPING_REQUEST.get(), "$.ownerUserId"));

        String memberSubject = "keycloak|shopping-bff-member-" + UUID.randomUUID();
        addTenantMember(memberSubject, "Taylor", "member");
        mvc.perform(get("/bff/tenants/{tenantId}/shopping", tenantId)
                        .with(oidcLogin().idToken(token -> token.subject(memberSubject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryTracked").value(false));
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(memberSubject).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_SHOPPING_REQUEST.get(), "$.ownerUserId"));
    }

    @Test
    void shoppingBoughtMarkIsMemberScopedAndNeverChangesTheLedger() throws Exception {
        int transactionsBefore = shoppingTransactionCount();
        String boughtPath = "/api/v1/tenants/" + tenantId + "/shopping/freshmilk/bought";
        mvc.perform(post(boughtPath).with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.boughtCandidates.length()").value(1))
                .andExpect(jsonPath("$.estimatedListCost").value("0.00"));
        org.junit.jupiter.api.Assertions.assertEquals(transactionsBefore, shoppingTransactionCount(),
                "marking a product bought must never create a transaction");

        String memberSubject = "keycloak|shopping-mark-member-" + UUID.randomUUID();
        addTenantMember(memberSubject, "Taylor", "member");
        mvc.perform(get("/api/v1/tenants/{tenantId}/shopping", tenantId)
                        .with(jwt().jwt(token -> token.subject(memberSubject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.boughtCandidates").isEmpty());

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE shopping_marks SET marked_at = now() - interval '10 days' "
                    + "WHERE tenant_id = ? AND user_id = ? AND product_key = 'freshmilk'",
                    tenantId, userIdFor(subject));
        });
        mvc.perform(get("/api/v1/tenants/{tenantId}/shopping", tenantId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.boughtCandidates").isEmpty());
    }

    @Test
    void shoppingMuteCanBeReversedAndBffWritesRequireCsrf() throws Exception {
        String mutePath = "/bff/tenants/" + tenantId + "/suggestions/shopping/freshmilk/mute";
        var auth = oidcLogin().idToken(token -> token.subject(subject));
        mvc.perform(put(mutePath).with(auth)).andExpect(status().isForbidden());
        mvc.perform(put(mutePath).with(auth).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.mutedCandidates[0].productKey").value("freshmilk"));

        String memberSubject = "keycloak|shopping-mute-member-" + UUID.randomUUID();
        addTenantMember(memberSubject, "Taylor", "member");
        mvc.perform(get("/bff/tenants/{tenantId}/shopping", tenantId)
                        .with(oidcLogin().idToken(token -> token.subject(memberSubject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.mutedCandidates").isEmpty());

        mvc.perform(delete(mutePath).with(auth).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.mutedCandidates").isEmpty());
    }

    @Test
    void personalInflationUsesOnlyAuthenticatedActiveMemberForApiAndBff() throws Exception {
        LAST_PERSONAL_INFLATION_REQUEST.set("");
        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/personal-inflation", tenantId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reasonCode").value("insufficient_history"))
                .andExpect(jsonPath("$.basketBefore").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(subject).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_PERSONAL_INFLATION_REQUEST.get(), "$.ownerUserId"));

        String memberSubject = "keycloak|inflation-member-" + UUID.randomUUID();
        addTenantMember(memberSubject, "Taylor", "member");
        mvc.perform(get("/bff/tenants/{tenantId}/analytics/personal-inflation", tenantId)
                        .with(oidcLogin().idToken(token -> token.subject(memberSubject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reasonCode").value("insufficient_history"));
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(memberSubject).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_PERSONAL_INFLATION_REQUEST.get(), "$.ownerUserId"));

        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/personal-inflation", UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isNotFound());
    }

    @Test
    void recurringProjectionUsesOnlyAuthenticatedActiveMemberForApiAndBff() throws Exception {
        LAST_RECURRING_REQUEST.set("");
        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/recurring", tenantId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithmVersion").value("recurring.v1"))
                .andExpect(jsonPath("$.completeness").value("complete"))
                .andExpect(jsonPath("$.expenseSeries").isEmpty())
                .andExpect(jsonPath("$.mutedSeries").isEmpty())
                .andExpect(jsonPath("$.monthlyExpenseEstimate").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(tenantId.toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_RECURRING_REQUEST.get(), "$.tenantId"));
        org.junit.jupiter.api.Assertions.assertEquals(userIdFor(subject).toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_RECURRING_REQUEST.get(), "$.ownerUserId"));
        org.junit.jupiter.api.Assertions.assertEquals("UTC",
                com.jayway.jsonpath.JsonPath.read(LAST_RECURRING_REQUEST.get(), "$.timeZone"));

        String memberSubject = "keycloak|recurring-member-" + UUID.randomUUID();
        UUID memberId = addTenantMember(memberSubject, "Taylor", "member");
        mvc.perform(get("/bff/tenants/{tenantId}/analytics/recurring", tenantId)
                        .with(oidcLogin().idToken(token -> token.subject(memberSubject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithmVersion").value("recurring.v1"))
                .andExpect(jsonPath("$.incomeSeries").isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(memberId.toString(),
                com.jayway.jsonpath.JsonPath.read(LAST_RECURRING_REQUEST.get(), "$.ownerUserId"));

        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/recurring", UUID.randomUUID())
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isNotFound());
    }

    @Test
    void recurringMuteIsMemberScopedRestorableAndKeepsTransactions() throws Exception {
        RECURRING_FIXTURE_ENABLED.set(true);
        var ownerAuth = jwt().jwt(token -> token.subject(subject));
        mvc.perform(post("/api/v1/tenants/{tenantId}/transactions", tenantId)
                        .with(ownerAuth)
                        .header("Idempotency-Key", "recurring-mute-preserve-transaction-0001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"12.34\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"utilities\",\"description\":\"Internet\","
                                + "\"occurredAt\":\"2026-10-03T10:00:00Z\"}"))
                .andExpect(status().isCreated());
        int transactionCountBefore = tenantTransactionCount();
        org.junit.jupiter.api.Assertions.assertEquals(1, transactionCountBefore);

        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/recurring", tenantId).with(ownerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseSeries[0].id").value(RECURRING_SERIES_ID))
                .andExpect(jsonPath("$.monthlyExpenseEstimate").value("1500.00"))
                .andExpect(jsonPath("$.mutedSeries").isEmpty());

        mvc.perform(put("/api/v1/tenants/{tenantId}/analytics/recurring/{seriesId}/mute", tenantId,
                                RECURRING_SERIES_ID).with(ownerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseSeries").isEmpty())
                .andExpect(jsonPath("$.dueSoon").isEmpty())
                .andExpect(jsonPath("$.mutedSeries[0].id").value(RECURRING_SERIES_ID))
                .andExpect(jsonPath("$.monthlyExpenseEstimates").isEmpty())
                .andExpect(jsonPath("$.monthlyExpenseEstimate").doesNotExist());

        String memberSubject = "keycloak|recurring-mute-member-" + UUID.randomUUID();
        UUID memberId = addTenantMember(memberSubject, "Taylor", "member");
        mvc.perform(delete("/api/v1/tenants/{tenantId}/analytics/recurring/{seriesId}/mute", tenantId,
                                RECURRING_SERIES_ID)
                        .with(jwt().jwt(token -> token.subject(memberSubject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseSeries[0].id").value(RECURRING_SERIES_ID))
                .andExpect(jsonPath("$.mutedSeries").isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(1, recurringMuteCount(userIdFor(subject)));

        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/recurring", tenantId).with(ownerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseSeries").isEmpty())
                .andExpect(jsonPath("$.mutedSeries[0].id").value(RECURRING_SERIES_ID));

        mvc.perform(delete("/bff/tenants/{tenantId}/analytics/recurring/{seriesId}/mute", tenantId,
                                RECURRING_SERIES_ID)
                        .with(oidcLogin().idToken(token -> token.subject(subject))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseSeries[0].id").value(RECURRING_SERIES_ID))
                .andExpect(jsonPath("$.mutedSeries").isEmpty())
                .andExpect(jsonPath("$.monthlyExpenseEstimate").value("1500.00"));

        mvc.perform(put("/api/v1/tenants/{tenantId}/analytics/recurring/{seriesId}/mute", tenantId,
                                "00000000000000000000000000000000").with(ownerAuth))
                .andExpect(status().isNotFound());
        org.junit.jupiter.api.Assertions.assertEquals(0, recurringMuteCount(userIdFor(subject)));
        org.junit.jupiter.api.Assertions.assertEquals(0, recurringMuteCount(memberId));
        org.junit.jupiter.api.Assertions.assertEquals(transactionCountBefore, tenantTransactionCount());
    }

    @Test
    void telegramActorCanMuteAndRestoreCurrentRecurringSeries() throws Exception {
        RECURRING_FIXTURE_ENABLED.set(true);
        long telegramUserId = newTelegramUserId();
        String linkResponse = mvc.perform(post("/api/v1/me/telegram-link")
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = com.jayway.jsonpath.JsonPath.read(linkResponse, "$.code");
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk());
        String contextResponse = mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\"" + tenantId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String actorToken = com.jayway.jsonpath.JsonPath.read(contextResponse, "$.token");
        String body = "{\"token\":\"" + actorToken + "\"}";

        mvc.perform(post("/internal/v1/telegram/actions/recurring/{seriesId}/mute", RECURRING_SERIES_ID)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseSeries").isEmpty())
                .andExpect(jsonPath("$.mutedSeries[0].id").value(RECURRING_SERIES_ID));
        mvc.perform(post("/internal/v1/telegram/actions/recurring/{seriesId}/unmute", RECURRING_SERIES_ID)
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseSeries[0].id").value(RECURRING_SERIES_ID))
                .andExpect(jsonPath("$.mutedSeries").isEmpty());
    }

    private int tenantTransactionCount() {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM transactions WHERE tenant_id = ?", Integer.class, tenantId);
        });
    }

    private int recurringMuteCount(UUID userId) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM muted_suggestions WHERE tenant_id = ? AND user_id = ? "
                    + "AND section = 'recurring'", Integer.class, tenantId, userId);
        });
    }

    @Test
    void confirmedNotToBuyDecisionIsShownAsBlockedWithItsReason() throws Exception {
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("INSERT INTO user_product_decisions (tenant_id, user_id, product_key, decision) "
                            + "VALUES (?, ?, 'freshmilk', 'confirmed')",
                    tenantId, userIdFor(subject));
        });

        mvc.perform(get("/api/v1/tenants/{tenantId}/shopping", tenantId)
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.blockedCandidates[0].productKey").value("freshmilk"))
                .andExpect(jsonPath("$.blockedCandidates[0].reasonCode").value("confirmed_not_to_buy"));
    }

    private int shoppingTransactionCount() {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM transactions WHERE tenant_id = ?", Integer.class, tenantId);
        });
    }

    @Test
    void receiptDuplicateDecisionIsOwnerScopedVersionedAndReversible() throws Exception {
        String receiptsPath = "/api/v1/tenants/" + tenantId + "/receipts";
        var auth = jwt().jwt(token -> token.subject(subject));
        String body = "{\"cashTotal\":\"42.00\",\"merchant\":\"Market\",\"items\":["
                + "{\"name\":\"Tea\",\"quantity\":\"1\",\"unitPrice\":\"42.00\",\"lineSum\":\"42.00\"}]}";
        var first = mvc.perform(post(receiptsPath).with(auth)
                        .header("Idempotency-Key", "receipt-duplicate-first-001")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        String firstId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        var second = mvc.perform(post(receiptsPath).with(auth)
                        .header("Idempotency-Key", "receipt-duplicate-second-001")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicateDecision").value("unknown"))
                .andReturn();
        String secondId = com.jayway.jsonpath.JsonPath.read(second.getResponse().getContentAsString(), "$.id");
        String receiptPath = receiptsPath + "/" + secondId;

        mvc.perform(post(receiptsPath).with(auth)
                        .header("Idempotency-Key", "receipt-duplicate-second-001")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(secondId));
        mvc.perform(get(receiptPath + "/duplicate-candidates").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()", org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.candidates[0].id").value(firstId));

        mvc.perform(put(receiptPath + "/duplicate-decision").with(auth).header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"decision\":\"duplicate\",\"duplicateReceiptId\":\"" + firstId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateDecision").value("duplicate"))
                .andExpect(jsonPath("$.duplicateOfReceiptId").value(firstId))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(put(receiptPath + "/duplicate-decision").with(auth).header("If-Match", "\"2\"")
                        .contentType("application/json").content("{\"decision\":\"independent\",\"duplicateReceiptId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateDecision").value("independent"))
                .andExpect(jsonPath("$.duplicateOfReceiptId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.version").value(3));
        mvc.perform(put(receiptPath + "/duplicate-decision").with(auth).header("If-Match", "\"2\"")
                        .contentType("application/json").content("{\"decision\":\"independent\",\"duplicateReceiptId\":null}"))
                .andExpect(status().isPreconditionFailed());

        String confirmKey = "receipt-duplicate-confirm-0001";
        var confirmed = mvc.perform(post(receiptPath + "/confirm").with(auth)
                        .header("If-Match", "\"3\"").header("Idempotency-Key", confirmKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("confirmed"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andReturn();
        String transactionId = com.jayway.jsonpath.JsonPath.read(
                confirmed.getResponse().getContentAsString(), "$.transactionId");
        mvc.perform(post(receiptPath + "/confirm").with(auth)
                        .header("If-Match", "\"3\"").header("Idempotency-Key", confirmKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transactionId));
        mvc.perform(post(receiptPath + "/confirm").with(auth)
                        .header("If-Match", "\"4\"").header("Idempotency-Key", "receipt-duplicate-confirm-0002"))
                .andExpect(status().isConflict());
        var firstConfirmed = mvc.perform(post(receiptsPath + "/" + firstId + "/confirm").with(auth)
                        .header("If-Match", "\"1\"").header("Idempotency-Key", "receipt-duplicate-first-confirm-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("confirmed"))
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andReturn();
        String firstTransactionId = com.jayway.jsonpath.JsonPath.read(
                firstConfirmed.getResponse().getContentAsString(), "$.transactionId");
        org.junit.jupiter.api.Assertions.assertNotEquals(transactionId, firstTransactionId);
        Integer postedTransactions = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM transactions WHERE tenant_id = ? AND source = 'receipt' "
                            + "AND id IN (?, ?)", Integer.class, tenantId,
                    UUID.fromString(transactionId), UUID.fromString(firstTransactionId));
        });
        org.junit.jupiter.api.Assertions.assertEquals(2, postedTransactions);

        Integer reviews = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM receipt_reviews WHERE tenant_id = ? AND receipt_id = ? "
                            + "AND action = 'receipt.duplicate_decision'", Integer.class, tenantId, UUID.fromString(secondId));
        });
        org.junit.jupiter.api.Assertions.assertEquals(2, reviews);
    }

    @Test
    void browserReceiptDuplicateDecisionRequiresCsrfToken() throws Exception {
        var created = mvc.perform(post("/bff/tenants/" + tenantId + "/receipts")
                        .with(oidcLogin().idToken(token -> token.subject(subject))).with(csrf())
                        .header("Idempotency-Key", "receipt-duplicate-browser-001")
                        .contentType("application/json")
                        .content("{\"cashTotal\":\"1.00\",\"merchant\":\"Shop\",\"items\":["
                                + "{\"name\":\"Tea\",\"quantity\":\"1\",\"unitPrice\":\"1.00\",\"lineSum\":\"1.00\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(put("/bff/tenants/" + tenantId + "/receipts/" + receiptId + "/duplicate-decision")
                        .with(oidcLogin().idToken(token -> token.subject(subject))).header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"decision\":\"independent\",\"duplicateReceiptId\":null}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void receiptItemsArePagedVersionedAndOnlyExplicitSyncChangesCashTotal() throws Exception {
        StringBuilder itemJson = new StringBuilder("[");
        for (int index = 1; index <= 9; index++) {
            if (index > 1) itemJson.append(',');
            itemJson.append("{\"name\":\"Item ").append(index)
                    .append("\",\"quantity\":\"1\",\"unitPrice\":\"10.00\",\"lineSum\":\"10.00\"}");
        }
        String body = "{\"cashTotal\":\"101.00\",\"merchant\":\"Market\",\"receiptDate\":\"2026-10-01\",\"items\":"
                + itemJson + "]}";
        String path = "/api/v1/tenants/" + tenantId + "/receipts";
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post(path).with(auth).header("Idempotency-Key", "receipt-items-create-001")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cashTotal").value("101.00"))
                .andExpect(jsonPath("$.itemsTotal").value("90.00"))
                .andExpect(jsonPath("$.itemCount").value(9))
                .andExpect(jsonPath("$.items.length()").value(8))
                .andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String firstItemId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.items[0].id");
        String secondItemId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.items[1].id");

        mvc.perform(get(path + "/" + receiptId + "/items").with(auth).param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(8))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get(path + "/" + receiptId + "/items").with(auth).param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));

        mvc.perform(post(path + "/" + receiptId + "/items").with(auth).header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"name\":\"Extra item\",\"quantity\":\"1\",\"unitPrice\":\"5.00\",\"lineSum\":\"5.00\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.cashTotal").value("101.00"))
                .andExpect(jsonPath("$.itemsTotal").value("95.00"))
                .andExpect(jsonPath("$.itemCount").value(10)).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get(path + "/" + receiptId + "/items").with(auth).param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(false));

        mvc.perform(delete(path + "/" + receiptId + "/items/" + firstItemId).with(auth).header("If-Match", "\"2\""))
                .andExpect(status().isNoContent());
        mvc.perform(get(path + "/" + receiptId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.cashTotal").value("101.00"))
                .andExpect(jsonPath("$.itemsTotal").value("85.00"));

        mvc.perform(patch(path + "/" + receiptId + "/items/" + secondItemId).with(auth)
                        .header("If-Match", "\"3\"").contentType("application/json")
                        .content("{\"name\":\"Corrected item\",\"quantity\":\"1\",\"unitPrice\":\"9.00\",\"lineSum\":\"9.00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cashTotal").value("101.00"))
                .andExpect(jsonPath("$.itemsTotal").value("84.00"))
                .andExpect(jsonPath("$.version").value(4));

        mvc.perform(post(path + "/" + receiptId + "/sync-total").with(auth).header("If-Match", "\"4\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cashTotal").value("84.00"))
                .andExpect(jsonPath("$.itemsTotal").value("84.00"))
                .andExpect(jsonPath("$.state").value("draft"))
                .andExpect(jsonPath("$.version").value(5));

        mvc.perform(patch(path + "/" + receiptId + "/items/" + secondItemId).with(auth)
                        .header("If-Match", "\"4\"").contentType("application/json")
                        .content("{\"name\":\"Stale edit\",\"quantity\":\"1\",\"unitPrice\":\"9.00\",\"lineSum\":\"9.00\"}"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void receiptDraftIsOwnerScopedAndViewerCanReadButCannotCreate() throws Exception {
        var ownerAuth = jwt().jwt(token -> token.subject(subject));
        String body = """
                {"cashTotal":"100.00","merchant":"Market","receiptDate":"2026-10-01","items":[
                  {"name":"Tea","quantity":"1","unitPrice":"100.00","lineSum":"100.00"}]}
                """;
        var created = mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(ownerAuth)
                        .header("Idempotency-Key", "receipt-owner-scope-create-01")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        String otherSubject = "keycloak|receipt-non-owner-" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID userId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)", userId, otherSubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'viewer', ?)",
                    tenantId, otherSubject, userId);
        });
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/receipts/" + receiptId)
                        .with(jwt().jwt(token -> token.subject(otherSubject))))
                .andExpect(status().isNotFound());

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE memberships SET role = 'viewer' WHERE tenant_id = ? AND subject = ?", tenantId, subject);
        });
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/receipts/" + receiptId).with(ownerAuth))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(ownerAuth)
                        .header("Idempotency-Key", "receipt-viewer-denied-create-01")
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void browserCanCreateAndReadReceiptThroughCsrfProtectedBff() throws Exception {
        String body = """
                {"cashTotal":"100.00","merchant":"Market","receiptDate":"2026-10-01","items":[
                  {"name":"Tea","quantity":"1","unitPrice":"100.00","lineSum":"100.00"}]}
                """;
        var browser = oidcLogin().idToken(token -> token.subject(subject));
        var created = mvc.perform(post("/bff/tenants/" + tenantId + "/receipts").with(browser).with(csrf())
                        .header("Idempotency-Key", "receipt-browser-create-001")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cashTotal").value("100.00"))
                .andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/bff/tenants/" + tenantId + "/receipts/" + receiptId).with(browser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("draft"));
        mvc.perform(post("/bff/tenants/" + tenantId + "/receipts/" + receiptId + "/items")
                        .with(browser).with(csrf()).header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"name\":\"Bread\",\"quantity\":\"1\",\"unitPrice\":\"10.00\",\"lineSum\":\"10.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cashTotal").value("100.00"))
                .andExpect(jsonPath("$.itemsTotal").value("110.00"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(post("/bff/tenants/" + tenantId + "/receipts/" + receiptId + "/sync-total")
                        .with(browser).with(csrf()).header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashTotal").value("110.00"));
        mvc.perform(patch("/bff/tenants/" + tenantId + "/receipts/" + receiptId + "/category")
                        .with(browser).with(csrf()).header("If-Match", "\"3\"")
                        .contentType("application/json").content("{\"categoryCode\":\"еда\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryCode").value("еда"))
                .andExpect(jsonPath("$.categorySource").value("human"))
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void receiptCategorySelectionPreservesRuleEvidenceAndRejectsStaleVersion() throws Exception {
        String body = """
                {"cashTotal":"100.00","merchant":"Market","receiptDate":"2026-10-01","items":[
                  {"name":"Пиво","quantity":"1","unitPrice":"20.00","lineSum":"20.00"}]}
                """;
        var auth = jwt().jwt(token -> token.subject(subject));
        String path = "/api/v1/tenants/" + tenantId + "/receipts";
        var created = mvc.perform(post(path).with(auth)
                        .header("Idempotency-Key", "receipt-category-create-0001")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryCode").value("досуг"))
                .andExpect(jsonPath("$.categorySource").value("rule"))
                .andExpect(jsonPath("$.alcoholShare").value("0.2000"))
                .andExpect(jsonPath("$.leisure").value(true))
                .andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        String categoryPath = path + "/" + receiptId + "/category";
        mvc.perform(patch(categoryPath).with(auth).header("If-Match", "\"1\"")
                        .contentType("application/json").content("{\"categoryCode\":\"еда\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryCode").value("еда"))
                .andExpect(jsonPath("$.categorySource").value("human"))
                .andExpect(jsonPath("$.categoryAlgorithmVersion").value("receipt-category.v1"))
                .andExpect(jsonPath("$.alcoholShare").value("0.2000"))
                .andExpect(jsonPath("$.leisureShare").value("0.2000"))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(patch(categoryPath).with(auth).header("If-Match", "\"1\"")
                        .contentType("application/json").content("{\"categoryCode\":\"еда\"}"))
                .andExpect(status().isPreconditionFailed());
        var review = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForMap("""
                    SELECT action, algorithm_version FROM receipt_reviews
                    WHERE receipt_id = ? ORDER BY created_at DESC LIMIT 1
                    """, UUID.fromString(receiptId));
        });
        org.junit.jupiter.api.Assertions.assertEquals("receipt.category_selected", review.get("action"));
        org.junit.jupiter.api.Assertions.assertEquals("receipt-category.v1", review.get("algorithm_version"));
    }

    @Test
    void basketReviewSendsNamesOnlyAndPersistsVersionedItemAdviceAndHistory() throws Exception {
        AI_BASKET_RESPONSE.set("""
                {"provider":"test-ollama","items":[
                  {"ordinal":1,"verdict":"useful","reason":"model reason","action":"model action"},
                  {"ordinal":2,"verdict":"useful","reason":"bread reason","action":"bread action"}],
                 "modelVersion":"test-model-1","promptVersion":"receipt-basket.v1","taskKind":"receipt-basket-review",
                 "inputSchemaVersion":"receipt-basket-context.v1","outputSchemaVersion":"receipt-basket-review.v1"}
                """);
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(auth)
                        .header("Idempotency-Key", "receipt-basket-create-0001")
                        .contentType("application/json")
                        .content("""
                                {"cashTotal":"35.00","merchant":"Market","items":[
                                  {"name":"Пиво","quantity":"1","unitPrice":"20.00","lineSum":"20.00"},
                                  {"name":"Хлеб","quantity":"1","unitPrice":"15.00","lineSum":"15.00"}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String basketPath = "/api/v1/tenants/" + tenantId + "/receipts/" + receiptId + "/basket-review";

        mvc.perform(post(basketPath).with(auth).header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("review_required"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.cashTotal").value("35.00"))
                .andExpect(jsonPath("$.itemsTotal").value("35.00"))
                .andExpect(jsonPath("$.items[0].verdict").value("harmful"))
                .andExpect(jsonPath("$.items[0].verdictSource").value("rule"))
                .andExpect(jsonPath("$.items[0].reviewReason").value("пиво, много калорий"))
                .andExpect(jsonPath("$.items[0].reviewProvider").value("test-ollama"))
                .andExpect(jsonPath("$.items[0].reviewAlgorithmVersion").value("receipt-basket.v1"))
                .andExpect(jsonPath("$.items[1].verdictSource").value("model"));

        org.junit.jupiter.api.Assertions.assertFalse(LAST_BASKET_REQUEST.get().contains("cashTotal"));
        org.junit.jupiter.api.Assertions.assertFalse(LAST_BASKET_REQUEST.get().contains("lineSum"));
        org.junit.jupiter.api.Assertions.assertTrue(LAST_BASKET_REQUEST.get().contains("Пиво"));
        mvc.perform(post(basketPath).with(auth).header("If-Match", "\"1\""))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts/" + receiptId + "/confirm").with(auth)
                        .header("If-Match", "\"2\"").header("Idempotency-Key", "receipt-basket-confirm-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("confirmed"))
                .andExpect(jsonPath("$.transactionId").isNotEmpty());
        Integer auditRows = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM receipt_reviews WHERE tenant_id = ? AND receipt_id = ? "
                            + "AND action = 'receipt.item_reviewed'", Integer.class, tenantId, UUID.fromString(receiptId));
        });
        org.junit.jupiter.api.Assertions.assertEquals(2, auditRows);
    }

    @Test
    void basketReviewBindsDuplicateNamesByItemIdAndKeepsLegacyUnknownProvenance() throws Exception {
        AI_BASKET_RESPONSE.set("""
                {"provider":"test-ollama","items":[
                  {"ordinal":1,"verdict":"useful","reason":"first reason","action":"first action"},
                  {"ordinal":2,"verdict":"harmful","reason":"second reason","action":"second action"}],
                 "modelVersion":"test-model-duplicate","promptVersion":"receipt-basket.v1","taskKind":"receipt-basket-review",
                 "inputSchemaVersion":"receipt-basket-context.v1","outputSchemaVersion":"receipt-basket-review.v1"}
                """);
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(auth)
                        .header("Idempotency-Key", "receipt-basket-same-name-0001")
                        .contentType("application/json")
                        .content("""
                                {"cashTotal":"30.00","merchant":"Market","items":[
                                  {"name":"Brand Z Snack","quantity":"1","unitPrice":"10.00","lineSum":"10.00"},
                                  {"name":"Brand Z Snack","quantity":"1","unitPrice":"20.00","lineSum":"20.00"}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String receiptPath = "/api/v1/tenants/" + tenantId + "/receipts/" + receiptId;
        String basketPath = receiptPath + "/basket-review";

        var reviewed = mvc.perform(post(basketPath).with(auth).header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Brand Z Snack"))
                .andExpect(jsonPath("$.items[0].lineSum").value("10.00"))
                .andExpect(jsonPath("$.items[0].reviewReason").value("first reason"))
                .andExpect(jsonPath("$.items[0].reviewAction").value("first action"))
                .andExpect(jsonPath("$.items[0].verdictSource").value("model"))
                .andExpect(jsonPath("$.items[1].name").value("Brand Z Snack"))
                .andExpect(jsonPath("$.items[1].lineSum").value("20.00"))
                .andExpect(jsonPath("$.items[1].reviewReason").value("second reason"))
                .andExpect(jsonPath("$.items[1].reviewAction").value("second action"))
                .andExpect(jsonPath("$.items[1].verdictSource").value("model")).andReturn();
        UUID firstItemId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                reviewed.getResponse().getContentAsString(), "$.items[0].id"));

        List<Map<String, Object>> history = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForList("""
                    SELECT rr.receipt_item_id::text AS item_id, rr.item_snapshot->'after'->>'id' AS snapshot_id,
                           rr.item_snapshot->'after'->>'reviewReason' AS review_reason
                    FROM receipt_reviews rr JOIN receipt_items ri
                      ON ri.tenant_id = rr.tenant_id AND ri.receipt_id = rr.receipt_id AND ri.id = rr.receipt_item_id
                    WHERE rr.tenant_id = ? AND rr.receipt_id = ? AND rr.action = 'receipt.item_reviewed'
                    ORDER BY ri.ordinal
                    """, tenantId, UUID.fromString(receiptId));
        });
        org.junit.jupiter.api.Assertions.assertEquals(2, history.size());
        org.junit.jupiter.api.Assertions.assertEquals(history.get(0).get("item_id"), history.get(0).get("snapshot_id"));
        org.junit.jupiter.api.Assertions.assertEquals(history.get(1).get("item_id"), history.get(1).get("snapshot_id"));
        org.junit.jupiter.api.Assertions.assertEquals("first reason", history.get(0).get("review_reason"));
        org.junit.jupiter.api.Assertions.assertEquals("second reason", history.get(1).get("review_reason"));

        transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.update("""
                    UPDATE receipt_items SET verdict = 'neutral', verdict_source = 'unknown',
                      review_algorithm_version = 'unknown'
                    WHERE tenant_id = ? AND receipt_id = ? AND id = ?
                    """, tenantId, UUID.fromString(receiptId), firstItemId);
        });
        mvc.perform(get(receiptPath).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].verdict").value("neutral"))
                .andExpect(jsonPath("$.items[0].verdictSource").value("unknown"))
                .andExpect(jsonPath("$.items[0].reviewAlgorithmVersion").value("unknown"));
    }

    @Test
    void browserBasketReviewRequiresCsrfToken() throws Exception {
        var created = mvc.perform(post("/bff/tenants/" + tenantId + "/receipts")
                        .with(oidcLogin().idToken(token -> token.subject(subject))).with(csrf())
                        .header("Idempotency-Key", "receipt-basket-browser-0001")
                        .contentType("application/json")
                        .content("{\"cashTotal\":\"1.00\",\"merchant\":\"Shop\",\"items\":["
                                + "{\"name\":\"Tea\",\"quantity\":\"1\",\"unitPrice\":\"1.00\",\"lineSum\":\"1.00\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/bff/tenants/" + tenantId + "/receipts/" + receiptId + "/basket-review")
                        .with(oidcLogin().idToken(token -> token.subject(subject))).header("If-Match", "\"1\""))
                .andExpect(status().isForbidden());
    }

    @Test
    void telegramDoNotBuyActionsUseActorScopeAndPersistHumanDecision() throws Exception {
        long telegramUserId = newTelegramUserId();
        String code = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/v1/me/telegram-link")
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.code");
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk());
        String context = mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\""
                                + tenantId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(context, "$.token");
        String body = "{\"token\":\"" + token + "\"}";
        String route = "/internal/v1/telegram/actions/do-not-buy";
        mvc.perform(post(route).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(route).header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reasonCode").value("no_optional_items"));
        mvc.perform(post(route + "/coffee/confirm")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        mvc.perform(post(route + "/decisions")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedProductKeys[0]").value("coffee"));
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/products/decisions")
                        .with(jwt().jwt(jwt -> jwt.subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedProductKeys[0]").value("coffee"));
        mvc.perform(post(route + "/coffee/allow")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/products/decisions")
                        .with(jwt().jwt(jwt -> jwt.subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.productKeys[0]").value("coffee"));
        mvc.perform(post(route + "/coffee/revoke")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/products/decisions")
                        .with(jwt().jwt(jwt -> jwt.subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.productKeys").isEmpty());
    }

    @Test
    void telegramReceiptRecalculationUsesActorContextAndSeparateApplyRequest() throws Exception {
        when(recalculationImpactClient.calculate(any())).thenReturn(new Impact(
                "receipt-recalculation-impact.v1", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "available", "complete", "75.00", "0.00", "-75.00"));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            UUID transactionId = addReportTransaction(ownerId, subject, "expense", "75.00", "food",
                    "2026-10-04T10:00:00Z");
            addConfirmedReceiptItem(ownerId, subject, transactionId, "Soda", "75.00", "harmful", "model", 1);
        });
        long telegramUserId = newTelegramUserId();
        String code = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/v1/me/telegram-link")
                        .with(jwt().jwt(token -> token.subject(subject))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.code");
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\",\"telegramUserId\":" + telegramUserId + "}"))
                .andExpect(status().isOk());
        String context = mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + telegramUserId + ",\"tenantId\":\"" + tenantId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(context, "$.token");
        String route = "/internal/v1/telegram/actions/review-recalculations";
        String body = "{\"token\":\"" + token + "\"}";
        mvc.perform(post(route + "/preview").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        var previewResponse = mvc.perform(post(route + "/preview")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("previewed"))
                .andExpect(jsonPath("$.impact.optionalSpendDelta").value("-75.00"))
                .andExpect(jsonPath("$.impact.currency").value("RUB"))
                .andReturn();
        String runId = com.jayway.jsonpath.JsonPath.read(previewResponse.getResponse().getContentAsString(), "$.runId");
        mvc.perform(post(route + "/apply").header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content("{\"token\":\"" + token
                                + "\",\"runId\":\"" + runId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("applied"))
                .andExpect(jsonPath("$.impact.optionalSpendDelta").value("-75.00"));

        String viewerSubject = "keycloak|recalc-viewer-" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID viewerId = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)",
                    viewerId, viewerSubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'viewer', ?)",
                    tenantId, viewerSubject, viewerId);
            jdbc.update("INSERT INTO member_profiles (tenant_id, user_id, display_name) VALUES (?, ?, 'Viewer')",
                    tenantId, viewerId);
        });
        long viewerTelegramId = newTelegramUserId();
        String viewerCode = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/v1/me/telegram-link")
                        .with(jwt().jwt(value -> value.subject(viewerSubject))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.code");
        mvc.perform(post("/internal/v1/telegram/link-codes/redeem")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"code\":\"" + viewerCode + "\",\"telegramUserId\":" + viewerTelegramId + "}"))
                .andExpect(status().isOk());
        String viewerContext = mvc.perform(post("/internal/v1/telegram/actor-contexts")
                        .header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json")
                        .content("{\"telegramUserId\":" + viewerTelegramId + ",\"tenantId\":\"" + tenantId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String viewerToken = com.jayway.jsonpath.JsonPath.read(viewerContext, "$.token");
        mvc.perform(post(route + "/preview").header("X-Finance-Service-Token", TELEGRAM_SERVICE_TOKEN)
                        .contentType("application/json").content("{\"token\":\"" + viewerToken + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void doNotBuyListSeparatesModelGuessesAndScopesReceiptEvidenceToMember() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        String otherSubject = "keycloak|evidence-other-" + UUID.randomUUID();
        List<UUID> ownItems = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID owner = userIdFor(subject);
            UUID other = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)",
                    other, otherSubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'member', ?)",
                    tenantId, otherSubject, other);
            List<UUID> items = new java.util.ArrayList<>();
            items.add(addConfirmedReceiptItem(owner, subject,
                    addReportTransaction(owner, subject, "expense", "13.00", "food", "2026-09-01T10:00:00Z"),
                    "Coffee", "13.00", "unnecessary", "model", 1));
            items.add(addConfirmedReceiptItem(owner, subject,
                    addReportTransaction(owner, subject, "expense", "14.00", "food", "2026-09-10T10:00:00Z"),
                    "Coffee", "14.00", "harmful", "model", 2));
            items.add(addConfirmedReceiptItem(owner, subject,
                    addReportTransaction(owner, subject, "expense", "1.00", "food", "2026-09-01T11:00:00Z"),
                    "Chips", "1.00", "unnecessary", "rule", 3));
            items.add(addConfirmedReceiptItem(owner, subject,
                    addReportTransaction(owner, subject, "expense", "2.00", "food", "2026-09-02T11:00:00Z"),
                    "Chips", "2.00", "harmful", "model", 4));
            addConfirmedReceiptItem(other, otherSubject,
                    addReportTransaction(other, otherSubject, "expense", "99.00", "food", "2026-09-03T10:00:00Z"),
                    "Coffee", "99.00", "harmful", "rule", 5);
            return items;
        });
        String path = "/api/v1/tenants/" + tenantId + "/products/do-not-buy";

        mvc.perform(get(path).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.banned[0].productKey").value("chips"))
                .andExpect(jsonPath("$.guesses[0].productKey").value("coffee"))
                .andExpect(jsonPath("$.guesses[0].modelOnly").value(true));
        org.junit.jupiter.api.Assertions.assertEquals(4,
                ((Number) com.jayway.jsonpath.JsonPath.read(LAST_EVIDENCE_REQUEST.get(), "$.items.length()")).intValue());
        for (UUID item : ownItems) {
            org.junit.jupiter.api.Assertions.assertTrue(LAST_EVIDENCE_REQUEST.get().contains(item.toString()));
        }
        mvc.perform(get("/bff/tenants/" + tenantId + "/products/do-not-buy")
                        .with(oidcLogin().idToken(token -> token.subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guesses[0].productKey").value("coffee"));
        SHOPPING_RESPONSE.set("""
                {"candidates":[
                  {"productName":"Chips","purchaseCount":3,"medianIntervalDays":10,
                   "usualUnitPrice":"1.000000","estimatedCost":"1.00",
                   "lastPurchasedAt":"2026-09-02T11:00:00Z","dueAt":"2026-09-12T11:00:00Z","daysUntilDue":0},
                  {"productName":"Coffee","purchaseCount":3,"medianIntervalDays":10,
                   "usualUnitPrice":"13.000000","estimatedCost":"13.00",
                   "lastPurchasedAt":"2026-09-10T10:00:00Z","dueAt":"2026-09-20T10:00:00Z","daysUntilDue":0}],
                 "estimatedListCost":"14.00","inventoryTracked":false}
                """);
        String shoppingPath = "/api/v1/tenants/" + tenantId + "/shopping";
        mvc.perform(get(shoppingPath).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].productKey").value("coffee"))
                .andExpect(jsonPath("$.blockedCandidates[0].productKey").value("chips"))
                .andExpect(jsonPath("$.blockedCandidates[0].reasonCode").value("rule_backed_not_to_buy"));

        mvc.perform(put("/api/v1/tenants/" + tenantId + "/products/coffee/decision").with(auth)
                        .contentType("application/json").content("{\"decision\":\"confirmed\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(path).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.banned.length()").value(2))
                .andExpect(jsonPath("$.guesses").isEmpty());
        mvc.perform(get(shoppingPath).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.blockedCandidates.length()").value(2));
        mvc.perform(put("/api/v1/tenants/" + tenantId + "/products/chips/decision").with(auth)
                        .contentType("application/json").content("{\"decision\":\"allowed\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(path).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.banned[0].productKey").value("coffee"));
        mvc.perform(get(shoppingPath).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].productKey").value("chips"))
                .andExpect(jsonPath("$.blockedCandidates[0].productKey").value("coffee"));
        EVIDENCE_RESPONSE_STATUS.set(503);
        mvc.perform(get(path).with(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reasonCode").value("analytics_unavailable"))
                .andExpect(jsonPath("$.banned").isEmpty());
    }

    @Test
    void productDecisionCanConfirmModelGuessAndSwitchPerMemberWithAudit() throws Exception {
        String key = "milkcocoa";
        String path = "/api/v1/tenants/" + tenantId + "/products/" + key + "/decision";
        var auth = jwt().jwt(token -> token.subject(subject));
        String otherSubject = "keycloak|product-decision-other-" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID otherUser = jdbc.queryForObject("INSERT INTO users DEFAULT VALUES RETURNING id", UUID.class);
            jdbc.update("INSERT INTO external_identities (user_id, provider, subject) VALUES (?, 'keycloak', ?)",
                    otherUser, otherSubject);
            jdbc.update("INSERT INTO memberships (tenant_id, subject, role, user_id) VALUES (?, ?, 'member', ?)",
                    tenantId, otherSubject, otherUser);
        });

        mvc.perform(put(path).with(auth).contentType("application/json")
                        .content("{\"decision\":\"confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("confirmed"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(path).with(auth).contentType("application/json")
                        .content("{\"decision\":\"confirmed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/products/decisions").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.productKeys").isEmpty())
                .andExpect(jsonPath("$.confirmedProductKeys[0]").value(key));
        mvc.perform(get("/bff/tenants/" + tenantId + "/products/decisions")
                        .with(oidcLogin().idToken(token -> token.subject(subject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedProductKeys[0]").value(key));
        mvc.perform(put("/bff/tenants/" + tenantId + "/products/" + key + "/decision")
                        .with(oidcLogin().idToken(token -> token.subject(subject))).with(csrf())
                        .contentType("application/json").content("{\"decision\":\"confirmed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/products/decisions")
                        .with(jwt().jwt(token -> token.subject(otherSubject))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedProductKeys").isEmpty());

        mvc.perform(put(path).with(auth).contentType("application/json")
                        .content("{\"decision\":\"allowed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(put(path).with(auth).contentType("application/json")
                        .content("{\"decision\":\"confirmed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        mvc.perform(delete(path).with(auth)).andExpect(status().isNoContent());
        mvc.perform(put(path).with(auth).contentType("application/json")
                        .content("{\"decision\":\"rejected\"}"))
                .andExpect(status().isBadRequest());
        Integer events = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM user_product_decision_events "
                    + "WHERE tenant_id = ? AND product_key = ? AND user_id = ?", Integer.class,
                    tenantId, key, userIdFor(subject));
        });
        org.junit.jupiter.api.Assertions.assertEquals(4, events);
    }

    @Test
    void allowedProductDecisionHidesMatchingDisputesAcrossPagesAndCanBeRevoked() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < 10; index++) {
            if (index > 0) items.append(',');
            String name = index == 0 ? "Milk 930ML" : index == 1 ? "MILK 930ml"
                    : "Item " + (index + 1);
            items.append("{\"name\":\"").append(name)
                    .append("\",\"quantity\":\"1\",\"unitPrice\":\"1.00\",\"lineSum\":\"1.00\"}");
        }
        var auth = jwt().jwt(token -> token.subject(subject));
        var created = mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(auth)
                        .header("Idempotency-Key", "receipt-disputed-page-0001")
                        .contentType("application/json")
                        .content("{\"cashTotal\":\"10.00\",\"merchant\":\"Market\",\"items\":[" + items + "]}"))
                .andExpect(status().isCreated()).andReturn();
        String receiptId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String firstKey = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.items[0].productKey");
        String duplicateKey = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.items[1].productKey");
        org.junit.jupiter.api.Assertions.assertEquals(firstKey, duplicateKey);
        UUID parsedReceiptId = UUID.fromString(receiptId);
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE receipt_items SET verdict = 'unnecessary', advice = 'review', verdict_source = 'model' "
                    + "WHERE tenant_id = ? AND receipt_id = ?", tenantId, parsedReceiptId);
        });

        String disputedPath = "/api/v1/tenants/" + tenantId + "/receipts/" + receiptId + "/disputed-items";
        mvc.perform(get(disputedPath + "?page=1").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(10))
                .andExpect(jsonPath("$.items.length()").value(8));
        mvc.perform(get(disputedPath + "?page=2").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(false));

        String decisionPath = "/api/v1/tenants/" + tenantId + "/products/" + firstKey + "/decision";
        mvc.perform(put(decisionPath).with(auth).contentType("application/json")
                        .content("{\"decision\":\"allowed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("allowed"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/products/decisions").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productKeys[0]").value(firstKey));
        mvc.perform(get(disputedPath).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(8));
        mvc.perform(put(decisionPath).with(auth).contentType("application/json")
                        .content("{\"decision\":\"allowed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(delete(decisionPath).with(auth)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/products/decisions").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productKeys").isEmpty());
        mvc.perform(get(disputedPath).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(10));
        Integer events = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM user_product_decision_events "
                    + "WHERE tenant_id = ? AND product_key = ?", Integer.class, tenantId, firstKey);
        });
        org.junit.jupiter.api.Assertions.assertEquals(2, events);
    }

    @Test
    void browserProductDecisionRequiresCsrfToken() throws Exception {
        String productPath = "/bff/tenants/" + tenantId + "/products/milk/decision";
        mvc.perform(put(productPath).with(oidcLogin().idToken(token -> token.subject(subject)))
                        .contentType("application/json").content("{\"decision\":\"allowed\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void repeatWarningsUseEarlierConfirmedReceiptsAndSkipCurrentOrAllowedItems() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        var previousTransaction = mvc.perform(post("/api/v1/tenants/" + tenantId + "/transactions").with(auth)
                        .header("Idempotency-Key", "repeat-warning-tx-prev-001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"12.50\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Previous receipt\","
                                + "\"occurredAt\":\"2026-10-01T10:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn();
        var currentTransaction = mvc.perform(post("/api/v1/tenants/" + tenantId + "/transactions").with(auth)
                        .header("Idempotency-Key", "repeat-warning-tx-now-001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"15.00\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Current receipt\","
                                + "\"occurredAt\":\"2026-10-02T10:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn();
        var futureTransaction = mvc.perform(post("/api/v1/tenants/" + tenantId + "/transactions").with(auth)
                        .header("Idempotency-Key", "repeat-warning-tx-future-001")
                        .contentType("application/json")
                        .content("{\"type\":\"expense\",\"amount\":\"99.99\",\"currency\":\"RUB\","
                                + "\"categoryCode\":\"food\",\"description\":\"Future receipt\","
                                + "\"occurredAt\":\"2026-10-03T10:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID previousTransactionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                previousTransaction.getResponse().getContentAsString(), "$.id"));
        UUID currentTransactionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                currentTransaction.getResponse().getContentAsString(), "$.id"));
        UUID futureTransactionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                futureTransaction.getResponse().getContentAsString(), "$.id"));

        var previous = mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(auth)
                        .header("Idempotency-Key", "repeat-warning-receipt-prev-001")
                        .contentType("application/json").content("""
                                {"cashTotal":"12.50","merchant":"Market","receiptDate":"2026-10-02","items":[
                                  {"name":"Йогурт Активиа","quantity":"1","unitPrice":"12.50","lineSum":"12.50"}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        var current = mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(auth)
                        .header("Idempotency-Key", "repeat-warning-receipt-now-001")
                        .contentType("application/json").content("""
                                {"cashTotal":"15.00","merchant":"Market","receiptDate":"2026-10-02","items":[
                                  {"name":"Йогурт Активиа 150г","quantity":"1","unitPrice":"15.00","lineSum":"15.00"}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        var future = mvc.perform(post("/api/v1/tenants/" + tenantId + "/receipts").with(auth)
                        .header("Idempotency-Key", "repeat-warning-receipt-future-001")
                        .contentType("application/json").content("""
                                {"cashTotal":"99.99","merchant":"Market","receiptDate":"2026-10-03","items":[
                                  {"name":"Йогурт Активиа 150г","quantity":"1","unitPrice":"99.99","lineSum":"99.99"}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        UUID previousReceiptId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                previous.getResponse().getContentAsString(), "$.id"));
        UUID currentReceiptId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                current.getResponse().getContentAsString(), "$.id"));
        UUID futureReceiptId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                future.getResponse().getContentAsString(), "$.id"));
        String productKey = com.jayway.jsonpath.JsonPath.read(current.getResponse().getContentAsString(), "$.items[0].productKey");

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE receipt_items SET verdict = 'harmful', advice = 'buy less', verdict_source = 'model' "
                    + "WHERE tenant_id = ? AND receipt_id IN (?, ?, ?)",
                    tenantId, previousReceiptId, currentReceiptId, futureReceiptId);
            jdbc.update("UPDATE receipts SET state = 'confirmed', transaction_id = ?, confirm_idempotency_key = ?, "
                            + "confirmed_at = now(), version = version + 1 WHERE tenant_id = ? AND id = ?",
                    previousTransactionId, "repeat-confirm-previous-0001", tenantId, previousReceiptId);
            jdbc.update("UPDATE receipts SET state = 'confirmed', transaction_id = ?, confirm_idempotency_key = ?, "
                            + "confirmed_at = now(), version = version + 1 WHERE tenant_id = ? AND id = ?",
                    currentTransactionId, "repeat-confirm-current-0001", tenantId, currentReceiptId);
            jdbc.update("UPDATE receipts SET state = 'confirmed', transaction_id = ?, confirm_idempotency_key = ?, "
                            + "confirmed_at = now(), version = version + 1 WHERE tenant_id = ? AND id = ?",
                    futureTransactionId, "repeat-confirm-future-0001", tenantId, futureReceiptId);
            jdbc.update("UPDATE receipts SET created_at = CASE id "
                            + "WHEN ? THEN '2026-10-02T11:00:00Z'::timestamptz "
                            + "WHEN ? THEN '2026-10-02T12:00:00Z'::timestamptz "
                            + "WHEN ? THEN '2026-10-03T11:00:00Z'::timestamptz END "
                            + "WHERE tenant_id = ? AND id IN (?, ?, ?)",
                    previousReceiptId, currentReceiptId, futureReceiptId, tenantId,
                    previousReceiptId, currentReceiptId, futureReceiptId);
        });

        String warningPath = "/api/v1/tenants/" + tenantId + "/receipts/" + currentReceiptId + "/repeat-warnings";
        mvc.perform(get(warningPath).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0].count").value(1))
                .andExpect(jsonPath("$.warnings[0].lastSum").value("12.50"))
                .andExpect(jsonPath("$.warnings[0].advice").value("buy less"));
        mvc.perform(put("/api/v1/tenants/" + tenantId + "/products/" + productKey + "/decision").with(auth)
                        .contentType("application/json").content("{\"decision\":\"allowed\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(warningPath).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings.length()").value(0));
    }

    @Test
    void createIsAtomicAndIdempotentAndRequiresTenantMembership() throws Exception {
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE member_profiles SET timezone = 'Europe/Moscow' WHERE tenant_id = ?", tenantId);
        });
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Moscow"));
        YearMonth reportMonth = YearMonth.of(2026, 10);
        int daysElapsed = reportMonth.isBefore(YearMonth.from(today)) ? reportMonth.lengthOfMonth()
                : reportMonth.isAfter(YearMonth.from(today)) ? 0 : today.getDayOfMonth();
        String expectedDailyPace = daysElapsed == 0 ? null : new java.math.BigDecimal("12.34")
                .divide(java.math.BigDecimal.valueOf(daysElapsed), 2, java.math.RoundingMode.HALF_EVEN).toPlainString();
        String expectedProjection = daysElapsed < 2 ? null : new java.math.BigDecimal("12.34")
                .multiply(java.math.BigDecimal.valueOf(reportMonth.lengthOfMonth()))
                .divide(java.math.BigDecimal.valueOf(daysElapsed), 2, java.math.RoundingMode.HALF_EVEN).toPlainString();
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
        mvc.perform(get("/api/v1/tenants/" + tenantId + "/summary").with(auth).param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incomeTotal").value("0.00"))
                .andExpect(jsonPath("$.expenseTotal").value("12.34"))
                .andExpect(jsonPath("$.transactionCount").value(1))
                .andExpect(jsonPath("$.asOfDate").value(today.toString()))
                .andExpect(jsonPath("$.daysElapsed").value(daysElapsed))
                .andExpect(jsonPath("$.daysInMonth").value(31))
                .andExpect(jsonPath("$.daysRemaining").value(Math.max(0, 31 - daysElapsed)))
                .andExpect(jsonPath("$.dailyExpensePace").value(expectedDailyPace))
                .andExpect(jsonPath("$.projectedExpenseTotal").value(expectedProjection));
        mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth).param("month", "2024-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysInMonth").value(29))
                .andExpect(jsonPath("$.daysElapsed").value(29))
                .andExpect(jsonPath("$.daysRemaining").value(0))
                .andExpect(jsonPath("$.projectedExpenseTotal").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/tenants/{tenantId}/summary", tenantId).with(auth).param("month", "2026-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysInMonth").value(31))
                .andExpect(jsonPath("$.daysElapsed").value(0))
                .andExpect(jsonPath("$.daysRemaining").value(31))
                .andExpect(jsonPath("$.dailyExpensePace").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.projectedExpenseTotal").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/bff/tenants/" + tenantId + "/summary").with(oidcLogin()
                        .idToken(token -> token.subject(subject))).param("month", "2026-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expenseTotal").value("12.34"));

        mvc.perform(get(path).with(auth).param("from", "2026-10-01").param("to", "2026-10-01")
                        .param("type", "expense").param("search", "lUnCh"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(get(path).with(auth).param("from", "2026-10-02").param("type", "income"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));

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
        mvc.perform(patch(itemPath).with(auth)
                        .header("Idempotency-Key", "transaction-request-0007")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"4.00","currency":"RUB","categoryCode":"food","description":"Corrected date","occurredAt":"2026-10-01T10:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Corrected date"))
                .andExpect(jsonPath("$.occurredAt").value("2026-10-01T10:00:00Z"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(patch(itemPath).with(auth)
                        .header("Idempotency-Key", "transaction-request-0008")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"4.00","currency":"RUB","categoryCode":"food","description":"Stale edit","occurredAt":"2026-10-02T10:00:00Z"}
                                """))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(patch(itemPath).with(auth)
                        .header("Idempotency-Key", "transaction-request-0007")
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("""
                                {"type":"expense","amount":"4.00","currency":"RUB","categoryCode":"food","description":"Corrected date","occurredAt":"2026-10-01T10:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        var voidPath = itemPath + "/void";
        mvc.perform(post(voidPath).with(auth)
                        .header("Idempotency-Key", "transaction-request-0005")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("voided"))
                .andExpect(jsonPath("$.version").value(3));
        mvc.perform(post(voidPath).with(auth)
                        .header("Idempotency-Key", "transaction-request-0005")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        mvc.perform(post(voidPath).with(auth)
                        .header("Idempotency-Key", "transaction-request-0006")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(get(itemPath).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("voided"));

        UUID transactionId = UUID.fromString(id);
        org.assertj.core.api.Assertions.assertThat(countTenantRows("transactions", "id", transactionId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(countTenantRows("audit_log", "entity_id", transactionId)).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(countTenantRows("outbox_events", "aggregate_id", transactionId)).isEqualTo(3);
    }

    @Test
    void adviceAnalyticsUsesIdempotentInputWatermarksAndStalesOldJobs() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        UUID itemId = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            UUID transactionId = addReportTransaction(ownerId, subject, "expense", "100.00", "food",
                    "2026-10-01T10:00:00Z");
            return addConfirmedReceiptItem(ownerId, subject, transactionId, "Чипсы", "100.00", "harmful", "model", 1);
        });

        String first = mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(auth))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("pending"))
                .andExpect(jsonPath("$.inputWatermark").value("1"))
                .andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(first, "$.id");

        mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(auth))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.inputWatermark").value("1"));

        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE receipt_items SET verdict = 'unnecessary', version = version + 1 WHERE tenant_id = ? AND id = ?",
                    tenantId, itemId);
        });
        String refreshed = mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(auth))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inputWatermark").value("2"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertNotEquals(jobId,
                com.jayway.jsonpath.JsonPath.read(refreshed, "$.id"));

        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/advice/jobs/{jobId}", tenantId, jobId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("stale"))
                .andExpect(jsonPath("$.report").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void goalsAreMemberScopedAndChangingPreferenceDoesNotRewriteAcceptedTerms() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            for (int index = 0; index < 4; index++) {
                String date = "2026-10-0" + (index + 1) + "T10:00:00Z";
                UUID transactionId = addReportTransaction(ownerId, subject, "expense", "100.00", "food", date);
                addConfirmedReceiptItem(ownerId, subject, transactionId, "Чипсы", "100.00", "harmful", "rule", index + 1);
            }
        });
        when(goalCandidatesClient.calculate(any())).thenAnswer(invocation -> {
            GoalCandidatesApi.Request request = invocation.getArgument(0);
            return goalCandidates(request);
        });

        String overview = mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("count"))
                .andExpect(jsonPath("$.candidates[0].key").value("snack"))
                .andReturn().getResponse().getContentAsString();
        var captor = org.mockito.ArgumentCaptor.forClass(GoalCandidatesApi.Request.class);
        verify(goalCandidatesClient).calculate(captor.capture());
        GoalCandidatesApi.Request sent = captor.getValue();
        org.junit.jupiter.api.Assertions.assertNotNull(sent);
        org.junit.jupiter.api.Assertions.assertEquals("count", sent.unit());
        org.junit.jupiter.api.Assertions.assertEquals(4, sent.purchases().size());
        org.junit.jupiter.api.Assertions.assertEquals(1, sent.decisions().size());
        org.junit.jupiter.api.Assertions.assertEquals(4, sent.decisions().get(0).harmfulCount());

        String watermark = com.jayway.jsonpath.JsonPath.read(overview, "$.inputWatermark");
        String accepted = mvc.perform(post("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth)
                        .contentType("application/json")
                        .content("{\"candidateKey\":\"snack\",\"inputWatermark\":\"" + watermark + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit").value("count"))
                .andExpect(jsonPath("$.countTarget").value(2))
                .andReturn().getResponse().getContentAsString();
        String goalId = com.jayway.jsonpath.JsonPath.read(accepted, "$.id");
        Instant acceptedAt = Instant.parse(com.jayway.jsonpath.JsonPath.read(accepted, "$.acceptedAt"));
        Instant endsAt = Instant.parse(com.jayway.jsonpath.JsonPath.read(accepted, "$.endsAt"));
        org.junit.jupiter.api.Assertions.assertEquals(acceptedAt.plusSeconds(30L * 24 * 60 * 60), endsAt);
        org.junit.jupiter.api.Assertions.assertEquals(1, countTenantRows("outbox_events", "aggregate_id", UUID.fromString(goalId)));

        mvc.perform(put("/api/v1/tenants/{tenantId}/goals/unit", tenantId).with(auth)
                        .contentType("application/json").content("{\"unit\":\"sum\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unit").value("sum"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("sum"))
                .andExpect(jsonPath("$.active.id").value(goalId))
                .andExpect(jsonPath("$.active.unit").value("count"))
                .andExpect(jsonPath("$.active.countTarget").value(2));
    }

    @Test
    void countGoalCanBeAcceptedWhenReceiptAmountsAreUnknownAndOldPreviewBecomesStale() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            for (int index = 0; index < 4; index++) {
                UUID transactionId = addReportTransaction(ownerId, subject, "expense", "50.00", "food",
                        "2026-10-0" + (index + 1) + "T10:00:00Z");
                addConfirmedReceiptItem(ownerId, subject, transactionId, "Чипсы", null, "harmful", "rule", index + 1);
            }
        });
        when(goalCandidatesClient.calculate(any())).thenAnswer(invocation -> goalCandidates(invocation.getArgument(0)));

        String preview = mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates[0].monthlySpend").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        String watermark = com.jayway.jsonpath.JsonPath.read(preview, "$.inputWatermark");
        String accepted = mvc.perform(post("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth)
                        .contentType("application/json").content("{\"candidateKey\":\"snack\",\"inputWatermark\":\"" + watermark + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.monthlySpend").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        String goalId = com.jayway.jsonpath.JsonPath.read(accepted, "$.id");
        java.math.BigDecimal storedBaseline = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT baseline_monthly_spend FROM goals WHERE tenant_id=? AND id=?",
                    java.math.BigDecimal.class, tenantId, UUID.fromString(goalId));
        });
        org.junit.jupiter.api.Assertions.assertNull(storedBaseline);

        mvc.perform(post("/api/v1/tenants/{tenantId}/goals/{goalId}/cancel", tenantId, goalId).with(auth))
                .andExpect(status().isOk());
        String nextPreview = mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String staleWatermark = com.jayway.jsonpath.JsonPath.read(nextPreview, "$.inputWatermark");
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE receipt_items SET verdict='neutral', version=version+1 WHERE tenant_id=? "
                    + "AND name='Чипсы' AND id=(SELECT id FROM receipt_items WHERE tenant_id=? AND name='Чипсы' ORDER BY id LIMIT 1)",
                    tenantId, tenantId);
        });
        mvc.perform(post("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth)
                        .contentType("application/json").content("{\"candidateKey\":\"snack\",\"inputWatermark\":\"" + staleWatermark + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void acceptedGroupGoalPersistsTheEligibleProductKeysFromItsPreview() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            for (String name : List.of("Чипсы", "Сухарики")) {
                UUID transactionId = addReportTransaction(ownerId, subject, "expense", "100.00", "food",
                        "2026-10-07T10:00:00Z");
                addConfirmedReceiptItem(ownerId, subject, transactionId, name, "100.00", "harmful", "rule", 2);
            }
        });
        var group = new GoalCandidatesApi.Candidate("cat:снеки", null, "Снеки и чипсы", "count",
                "4.00", 2, "400.00", null, "200.00", 4, 4, List.of("чипсы", "сухарики"));
        when(goalCandidatesClient.calculate(any())).thenAnswer(invocation -> {
            GoalCandidatesApi.Request request = invocation.getArgument(0);
            return new GoalCandidatesApi.CandidateReport("goal-candidates-f44.v1", request.inputWatermark(),
                    request.unit(), List.of(), List.of(group), List.of());
        });
        String preview = mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String watermark = com.jayway.jsonpath.JsonPath.read(preview, "$.inputWatermark");
        String accepted = mvc.perform(post("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth)
                        .contentType("application/json")
                        .content("{\"candidateKey\":\"cat:снеки\",\"inputWatermark\":\"" + watermark + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.scope").value("group"))
                .andReturn().getResponse().getContentAsString();
        UUID goalId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(accepted, "$.id"));
        String storedMembers = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT member_product_keys::text FROM goals WHERE tenant_id=? AND id=?",
                    String.class, tenantId, goalId);
        });
        org.junit.jupiter.api.Assertions.assertEquals("[\"чипсы\", \"сухарики\"]", storedMembers);
    }

    @Test
    void activeGoalProgressCountsOnlyPurchasesAfterAcceptanceAndRefreshesAfterReceiptConfirmation() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            for (int index = 0; index < 4; index++) {
                UUID transactionId = addReportTransaction(ownerId, subject, "expense", "40.00", "food",
                        "2026-10-0" + (index + 1) + "T10:00:00Z");
                addConfirmedReceiptItem(ownerId, subject, transactionId, "Чипсы", "40.00", "harmful", "rule", 2);
            }
        });
        when(goalCandidatesClient.calculate(any())).thenAnswer(invocation -> {
            GoalCandidatesApi.Request request = invocation.getArgument(0);
            var candidate = new GoalCandidatesApi.Candidate("чипсы", "чипсы", "Чипсы", "count", "4.00",
                    2, "160.00", null, "80.00", 4, 4, List.of());
            return new GoalCandidatesApi.CandidateReport("goal-candidates-f44.v1", request.inputWatermark(),
                    request.unit(), List.of(candidate), List.of(), List.of());
        });
        String preview = mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String watermark = com.jayway.jsonpath.JsonPath.read(preview, "$.inputWatermark");
        String accepted = mvc.perform(post("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth)
                        .contentType("application/json")
                        .content("{\"candidateKey\":\"чипсы\",\"inputWatermark\":\"" + watermark + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String acceptedGoalId = com.jayway.jsonpath.JsonPath.read(accepted, "$.id");
        UUID ownerId = userIdFor(subject);
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID transactionId = addReportTransaction(ownerId, subject, "expense", "55.00", "food", Instant.now().toString());
            addConfirmedReceiptItem(ownerId, subject, transactionId, "Чипсы", "55.00", "neutral", "rule", 1);
        });
        mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active.id").value(acceptedGoalId))
                .andExpect(jsonPath("$.activeProgress.bought").value(1))
                .andExpect(jsonPath("$.activeProgress.spent").value("55.00"))
                .andExpect(jsonPath("$.activeProgress.met").value(true));
    }

    @Test
    void expiredActiveGoalIsArchivedOnceWhileCancelledGoalNeverEntersHistory() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        String viewerSubject = "keycloak|expired-goal-viewer-" + UUID.randomUUID();
        addTenantMember(viewerSubject, "Expired goal viewer", "viewer");
        var viewerAuth = jwt().jwt(token -> token.subject(viewerSubject));
        List<UUID> goalIds = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            UUID viewerId = userIdFor(viewerSubject);
            UUID completedId = UUID.randomUUID();
            UUID cancelledId = UUID.randomUUID();
            UUID viewerExpiredId = UUID.randomUUID();
            Instant acceptedAt = Instant.now().minusSeconds(31L * 24 * 60 * 60);
            Instant endsAt = acceptedAt.plusSeconds(30L * 24 * 60 * 60);
            String insert = """
                    INSERT INTO goals (id, tenant_id, owner_user_id, goal_key, goal_scope, display_name, unit,
                        baseline_rate, count_target, baseline_monthly_spend, monthly_limit, evidence_count,
                        input_watermark, accepted_at, ends_at, status, member_product_keys)
                    VALUES (?, ?, ?, 'chips', 'product', 'Чипсы', 'count', 4, 2, 100, NULL, 2, '1', ?, ?, ?, '[]'::jsonb)
                    """;
            jdbc.update(insert, completedId, tenantId, ownerId, java.sql.Timestamp.from(acceptedAt),
                    java.sql.Timestamp.from(endsAt), "active");
            jdbc.update(insert, cancelledId, tenantId, ownerId, java.sql.Timestamp.from(acceptedAt.minusSeconds(1)),
                    java.sql.Timestamp.from(endsAt.minusSeconds(1)), "cancelled");
            jdbc.update(insert, viewerExpiredId, tenantId, viewerId, java.sql.Timestamp.from(acceptedAt),
                    java.sql.Timestamp.from(endsAt), "active");
            return List.of(completedId, cancelledId, viewerExpiredId);
        });
        when(goalCandidatesClient.calculate(any())).thenAnswer(invocation -> goalCandidates(invocation.getArgument(0)));

        mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(viewerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active.id").value(goalIds.get(2).toString()))
                .andExpect(jsonPath("$.activeProgress.finished").value(true));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals("active", jdbc.queryForObject(
                    "SELECT status FROM goals WHERE tenant_id=? AND id=?", String.class, tenantId, goalIds.get(2)));
        });

        mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].goalId").value(goalIds.get(0).toString()))
                .andExpect(jsonPath("$.history[0].origin").value("completed"));
        mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.history.length()").value(1));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            org.junit.jupiter.api.Assertions.assertEquals("completed", jdbc.queryForObject(
                    "SELECT status FROM goals WHERE tenant_id=? AND id=?", String.class, tenantId, goalIds.get(0)));
            org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM goal_outcomes WHERE tenant_id=? AND goal_id=?", Integer.class, tenantId, goalIds.get(0)));
            org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM goal_outcomes WHERE tenant_id=? AND goal_id=?", Integer.class, tenantId, goalIds.get(1)));
            org.junit.jupiter.api.Assertions.assertEquals(1, countTenantRows("outbox_events", "aggregate_id", goalIds.get(0)));
        });
    }

    @Test
    void goalsEnforceOneActiveGoalAndViewerCannotChangeThem() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        String viewerSubject = "keycloak|goals-viewer-" + UUID.randomUUID();
        addTenantMember(viewerSubject, "Goal viewer", "viewer");
        var viewerAuth = jwt().jwt(token -> token.subject(viewerSubject));
        when(goalCandidatesClient.calculate(any())).thenAnswer(invocation -> goalCandidates(invocation.getArgument(0)));

        String overview = mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String watermark = com.jayway.jsonpath.JsonPath.read(overview, "$.inputWatermark");
        String body = "{\"candidateKey\":\"snack\",\"inputWatermark\":\"" + watermark + "\"}";
        String goalId = mvc.perform(post("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        goalId = com.jayway.jsonpath.JsonPath.read(goalId, "$.id");

        mvc.perform(post("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth)
                        .contentType("application/json").content(body))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(viewerAuth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(put("/api/v1/tenants/{tenantId}/goals/unit", tenantId).with(viewerAuth)
                        .contentType("application/json").content("{\"unit\":\"sum\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tenants/{tenantId}/goals", tenantId).with(viewerAuth)
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/tenants/{tenantId}/goals/{goalId}/cancel", tenantId, goalId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        mvc.perform(post("/api/v1/tenants/{tenantId}/goals", tenantId).with(auth)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void goalBffReadsTheSameMemberSnapshotAndRequiresCsrfForPreferenceChanges() throws Exception {
        var apiAuth = jwt().jwt(token -> token.subject(subject));
        var bffAuth = oidcLogin().idToken(token -> token.subject(subject));
        when(goalCandidatesClient.calculate(any())).thenAnswer(invocation -> goalCandidates(invocation.getArgument(0)));
        String api = mvc.perform(get("/api/v1/tenants/{tenantId}/goals", tenantId).with(apiAuth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String watermark = com.jayway.jsonpath.JsonPath.read(api, "$.inputWatermark");
        mvc.perform(get("/bff/tenants/{tenantId}/goals", tenantId).with(bffAuth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inputWatermark").value(watermark));
        mvc.perform(put("/bff/tenants/{tenantId}/goals/unit", tenantId).with(bffAuth)
                        .contentType("application/json").content("{\"unit\":\"sum\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/bff/tenants/{tenantId}/goals/unit", tenantId).with(bffAuth).with(csrf())
                        .contentType("application/json").content("{\"unit\":\"sum\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unit").value("sum"));
    }

    @Test
    void adviceAnalyticsIsMemberScopedAndViewerCanOnlyRead() throws Exception {
        String viewerSubject = "keycloak|advice-viewer-" + UUID.randomUUID();
        addTenantMember(viewerSubject, "Advice viewer", "viewer");
        var ownerAuth = jwt().jwt(token -> token.subject(subject));
        var viewerAuth = jwt().jwt(token -> token.subject(viewerSubject));

        String ownerJob = mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(ownerAuth))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(ownerJob, "$.id");

        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(viewerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(viewerAuth))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/advice/jobs/{jobId}", tenantId, jobId).with(viewerAuth))
                .andExpect(status().isNotFound());
    }

    @Test
    void adviceAnalyticsRejectsOverflowWithoutTruncatingOrCreatingAJob() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            UUID ownerId = userIdFor(subject);
            UUID transactionId = addReportTransaction(ownerId, subject, "expense", "1.00", "food",
                    "2026-10-01T10:00:00Z");
            addConfirmedReceiptItem(ownerId, subject, transactionId, "Product", "1.00", "harmful", "model", 1);
            UUID receiptId = jdbc.queryForObject("SELECT id FROM receipts WHERE tenant_id = ? AND transaction_id = ?",
                    UUID.class, tenantId, transactionId);
            jdbc.update("""
                    INSERT INTO receipt_items (tenant_id, receipt_id, ordinal, name, quantity, line_sum)
                    SELECT ?, ?, ordinal, 'Product ' || ordinal, 1, 1.00
                    FROM generate_series(2, 50001) AS generated(ordinal)
                    """, tenantId, receiptId);
        });

        mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(auth))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.reasonCode").value("too_many_items"));
        Integer jobCount = jdbc.queryForObject("SELECT count(*) FROM advice_analytics_jobs WHERE tenant_id = ?", Integer.class, tenantId);
        org.junit.jupiter.api.Assertions.assertEquals(0, jobCount);
    }

    @Test
    void adviceAnalyticsWorkerRequiresCredentialAndDuplicateResultIsIdempotent() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        // The isolated test database is reused between Gradle runs; retire orphaned test leases first.
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.analytics_worker', 'true', true)", String.class);
            jdbc.update("UPDATE advice_analytics_jobs SET state='failed', lease_token=NULL, lease_expires_at=NULL "
                    + "WHERE state IN ('pending','processing')");
        });
        mvc.perform(post("/internal/v1/analytics/advice-jobs/claim"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(auth))
                .andExpect(status().isAccepted());
        String claim = mvc.perform(post("/internal/v1/analytics/advice-jobs/claim")
                        .header("X-Analytics-Service-Token", ANALYTICS_SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(claim, "$.id");
        String leaseToken = com.jayway.jsonpath.JsonPath.read(claim, "$.leaseToken");
        String watermark = com.jayway.jsonpath.JsonPath.read(claim, "$.inputWatermark");
        String algorithm = com.jayway.jsonpath.JsonPath.read(claim, "$.algorithmVersion");
        String result = """
                {"leaseToken":"%s","inputWatermark":"%s","algorithmVersion":"%s","errorCode":null,
                 "report":{"algorithmVersion":"%s","inputWatermark":"%s","completeness":"complete"}}
                """.formatted(leaseToken, watermark, algorithm, algorithm, watermark);
        mvc.perform(post("/internal/v1/analytics/advice-jobs/{jobId}/result", jobId)
                        .header("X-Analytics-Service-Token", ANALYTICS_SERVICE_TOKEN).contentType("application/json").content(result))
                .andExpect(status().isNoContent());
        mvc.perform(post("/internal/v1/analytics/advice-jobs/{jobId}/result", jobId)
                        .header("X-Analytics-Service-Token", ANALYTICS_SERVICE_TOKEN).contentType("application/json").content(result))
                .andExpect(status().isNoContent());
        Integer completedRows = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM advice_analytics_jobs WHERE tenant_id=? AND owner_user_id=? AND id=? AND state='ready'",
                    Integer.class, tenantId, userIdFor(subject), UUID.fromString(jobId));
        });
        org.junit.jupiter.api.Assertions.assertEquals(1, completedRows);
        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/advice/jobs/{jobId}", tenantId, jobId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ready"));
    }

    @Test
    void adviceAnalyticsRetryReleasesLeaseAndRejectsLateAttemptResult() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.analytics_worker', 'true', true)", String.class);
            jdbc.update("UPDATE advice_analytics_jobs SET state='failed', lease_token=NULL, lease_expires_at=NULL "
                    + "WHERE state IN ('pending','processing')");
        });
        mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(auth))
                .andExpect(status().isAccepted());
        String first = mvc.perform(post("/internal/v1/analytics/advice-jobs/claim")
                        .header("X-Analytics-Service-Token", ANALYTICS_SERVICE_TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(first, "$.id");
        String firstToken = com.jayway.jsonpath.JsonPath.read(first, "$.leaseToken");
        String watermark = com.jayway.jsonpath.JsonPath.read(first, "$.inputWatermark");
        String algorithm = com.jayway.jsonpath.JsonPath.read(first, "$.algorithmVersion");
        String failure = """
                {"leaseToken":"%s","inputWatermark":"%s","algorithmVersion":"%s","report":null,"errorCode":"temporary_failure"}
                """.formatted(firstToken, watermark, algorithm);
        mvc.perform(post("/internal/v1/analytics/advice-jobs/{jobId}/result", jobId)
                        .header("X-Analytics-Service-Token", ANALYTICS_SERVICE_TOKEN).contentType("application/json").content(failure))
                .andExpect(status().isNoContent());
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.analytics_worker', 'true', true)", String.class);
            jdbc.update("UPDATE advice_analytics_jobs SET next_attempt_at=now() WHERE id=?", UUID.fromString(jobId));
        });
        String retry = mvc.perform(post("/internal/v1/analytics/advice-jobs/claim")
                        .header("X-Analytics-Service-Token", ANALYTICS_SERVICE_TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String retryToken = com.jayway.jsonpath.JsonPath.read(retry, "$.leaseToken");
        org.junit.jupiter.api.Assertions.assertNotEquals(firstToken, retryToken);
        org.junit.jupiter.api.Assertions.assertEquals(2, (int) com.jayway.jsonpath.JsonPath.read(retry, "$.attemptCount"));
        mvc.perform(post("/internal/v1/analytics/advice-jobs/{jobId}/result", jobId)
                        .header("X-Analytics-Service-Token", ANALYTICS_SERVICE_TOKEN).contentType("application/json").content(failure))
                .andExpect(status().isConflict());
        String success = """
                {"leaseToken":"%s","inputWatermark":"%s","algorithmVersion":"%s","errorCode":null,
                 "report":{"algorithmVersion":"%s","inputWatermark":"%s","completeness":"complete"}}
                """.formatted(retryToken, watermark, algorithm, algorithm, watermark);
        mvc.perform(post("/internal/v1/analytics/advice-jobs/{jobId}/result", jobId)
                        .header("X-Analytics-Service-Token", ANALYTICS_SERVICE_TOKEN).contentType("application/json").content(success))
                .andExpect(status().isNoContent());
    }

    @Test
    void adviceAnalyticsRejectsCompletionAfterMemberInputsAdvance() throws Exception {
        var auth = jwt().jwt(token -> token.subject(subject));
        clearPendingAdviceJobsForTest();
        mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(auth))
                .andExpect(status().isAccepted());
        String claim = mvc.perform(post("/internal/v1/analytics/advice-jobs/claim")
                        .header("X-Analytics-Service-Token", ANALYTICS_SERVICE_TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(claim, "$.id");
        String leaseToken = com.jayway.jsonpath.JsonPath.read(claim, "$.leaseToken");
        String watermark = com.jayway.jsonpath.JsonPath.read(claim, "$.inputWatermark");
        String algorithm = com.jayway.jsonpath.JsonPath.read(claim, "$.algorithmVersion");
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.update("UPDATE member_profiles SET timezone='Europe/Moscow' WHERE tenant_id=? AND user_id=?",
                    tenantId, userIdFor(subject));
        });
        mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(auth))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.inputWatermark").value("2"));
        String staleResult = """
                {"leaseToken":"%s","inputWatermark":"%s","algorithmVersion":"%s","errorCode":null,
                 "report":{"algorithmVersion":"%s","inputWatermark":"%s","completeness":"complete"}}
                """.formatted(leaseToken, watermark, algorithm, algorithm, watermark);
        mvc.perform(post("/internal/v1/analytics/advice-jobs/{jobId}/result", jobId)
                        .header("X-Analytics-Service-Token", ANALYTICS_SERVICE_TOKEN).contentType("application/json").content(staleResult))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/tenants/{tenantId}/analytics/advice/jobs/{jobId}", tenantId, jobId).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("stale"));
    }

    @Test
    void adviceAnalyticsBffUsesSameMemberJobAndRequiresCsrfForRefresh() throws Exception {
        var apiAuth = jwt().jwt(token -> token.subject(subject));
        var bffAuth = oidcLogin().idToken(token -> token.subject(subject));
        String created = mvc.perform(post("/api/v1/tenants/{tenantId}/analytics/advice", tenantId).with(apiAuth))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String jobId = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        mvc.perform(get("/bff/tenants/{tenantId}/analytics/advice", tenantId).with(bffAuth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(jobId));
        mvc.perform(post("/bff/tenants/{tenantId}/analytics/advice", tenantId).with(bffAuth))
                .andExpect(status().isForbidden());
        mvc.perform(post("/bff/tenants/{tenantId}/analytics/advice", tenantId).with(bffAuth).with(csrf()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(jobId));
        mvc.perform(get("/bff/tenants/{tenantId}/analytics/advice/jobs/{jobId}", tenantId, jobId).with(bffAuth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(jobId));
    }

    private int countTenantRows(String table, String column, UUID transactionId) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND " + column + " = ?",
                    Integer.class, tenantId, transactionId);
        });
    }

    private static GoalCandidatesApi.CandidateReport goalCandidates(GoalCandidatesApi.Request request) {
        boolean unknownAmounts = request.purchases().stream().anyMatch(purchase -> purchase.lineSum() == null);
        var candidate = new GoalCandidatesApi.Candidate("snack", "snack", "Чипсы", request.unit(),
                "4.00", "count".equals(request.unit()) ? 2 : 0, unknownAmounts ? null : "400.00",
                "sum".equals(request.unit()) ? "200.00" : null,
                unknownAmounts ? null : "200.00", 4, 4, List.of());
        return new GoalCandidatesApi.CandidateReport("goal-candidates-f44.v1", request.inputWatermark(),
                request.unit(), List.of(candidate), List.of(), List.of());
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
            String issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/finance";
            byte[] metadata = ("{\"issuer\":\"" + issuer + "\","
                    + "\"authorization_endpoint\":\"" + issuer + "/protocol/openid-connect/auth\","
                    + "\"token_endpoint\":\"" + issuer + "/protocol/openid-connect/token\","
                    + "\"userinfo_endpoint\":\"" + issuer + "/protocol/openid-connect/userinfo\","
                    + "\"jwks_uri\":\"" + issuer + "/jwks\","
                    + "\"response_types_supported\":[\"code\"],"
                    + "\"subject_types_supported\":[\"public\"],"
                    + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}")
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/realms/finance/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            for (String path : List.of("/.well-known/openid-configuration/realms/finance",
                    "/realms/finance/.well-known/openid-configuration",
                    "/.well-known/oauth-authorization-server/realms/finance")) {
                server.createContext(path, exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, metadata.length);
                    try (var body = exchange.getResponseBody()) {
                        body.write(metadata);
                    }
                });
            }
            server.start();
            return server;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private void clearPendingAdviceJobsForTest() {
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.analytics_worker', 'true', true)", String.class);
            jdbc.update("UPDATE advice_analytics_jobs SET state='failed', lease_token=NULL, lease_expires_at=NULL "
                    + "WHERE state IN ('pending','processing')");
        });
    }

    private static HttpServer startAiServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/imports/tbank", exchange -> {
                if (!("Bearer " + AI_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                exchange.getRequestBody().readAllBytes();
                String response = """
                        {"operations":[
                          {"operationDate":"2026-10-01","operationTime":"12:30","signedAmount":"-12.00","currency":"RUB","kind":"purchase","merchant":"Market","description":"Payment","cardLast4":"1234"},
                          {"operationDate":"2026-10-01","operationTime":"12:31","signedAmount":"-1.00","currency":"RUB","kind":"fee","merchant":null,"description":"Bank fee","cardLast4":"1234"}],
                         "parsedExpenseTotal":"13.00","parsedIncomeTotal":"0.00","expectedExpenseTotal":"13.00",
                         "expectedIncomeTotal":"0.00","quality":"valid","periodStart":"2026-10-01",
                         "periodEnd":"2026-10-01","parseVersion":"tbank-pdf.v1"}
                        """;
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/budget-proposals", exchange -> {
                if (!("Bearer " + AI_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_AI_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                AI_CALLS.incrementAndGet();
                String response = """
                        {"provider":"test-ollama","modelVersion":"test-model-1","promptVersion":"budget-proposal.v1","shares":{"еда":"40.00","транспорт":"15.00","жилье":"0.00","досуг":"10.00","одежда":"10.00","здоровье":"5.00","работа":"5.00","техника":"5.00","прочее":"10.00"}}
                        """;
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/transaction-drafts", exchange -> {
                if (!("Bearer " + AI_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_AI_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                AI_CALLS.incrementAndGet();
                String response = AI_DRAFT_RESPONSE.get();
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/receipts/basket-review", exchange -> {
                if (!("Bearer " + AI_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_BASKET_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                AI_CALLS.incrementAndGet();
                String response = AI_BASKET_RESPONSE.get();
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/merchant-classifications", exchange -> {
                if (!("Bearer " + AI_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_MERCHANT_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                AI_CALLS.incrementAndGet();
                byte[] body = ("{\"provider\":\"test-ollama\",\"classifications\":["
                        + "{\"merchant\":\"market\",\"categoryCode\":\"еда\",\"confidence\":\"0.930\"}],"
                        + "\"modelVersion\":\"test-model-1\",\"promptVersion\":\"merchant-category.v1\"}"
                        ).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/prices/compare", exchange -> {
                if (!("Bearer " + ANALYTICS_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_PRICE_COMPARE_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String receiptId = com.jayway.jsonpath.JsonPath.read(LAST_PRICE_COMPARE_REQUEST.get(), "$.receiptId");
                String itemId = com.jayway.jsonpath.JsonPath.read(LAST_PRICE_COMPARE_REQUEST.get(), "$.itemId");
                String purchasedAt = com.jayway.jsonpath.JsonPath.read(LAST_PRICE_COMPARE_REQUEST.get(), "$.purchasedAt");
                String response = """
                        {"algorithmVersion":"price-projection.v1","productName":"Tea 500g","hasBaseline":true,
                         "currentUnitPrice":"25.000000","baselineUnitPrice":"10.000000","change":"15.000000",
                         "relative":"1.500000","signal":true,"direction":"up","priorPurchases":1,
                         "history":[{"receiptId":"00000000-0000-4000-8000-000000000010","itemId":"00000000-0000-4000-8000-000000000011",
                           "purchasedAt":"2026-09-01T09:00:00Z","merchant":"Market","name":"Tea 500g","unitPrice":"10.000000","current":false},
                          {"receiptId":"%s","itemId":"%s","purchasedAt":"%s","merchant":null,"name":"Tea 500g","unitPrice":"25.000000","current":true}]}
                        """.formatted(receiptId, itemId, purchasedAt);
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/products/catalog", exchange -> {
                if (!("Bearer " + ANALYTICS_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_PRICE_CATALOG_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String query = com.jayway.jsonpath.JsonPath.read(LAST_PRICE_CATALOG_REQUEST.get(), "$.query");
                String mode = query.isBlank() ? "catalog" : "search";
                String response = "{\"mode\":\"" + mode + "\",\"query\":\"" + query
                        + "\",\"products\":[]}";
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/shopping/candidates", exchange -> {
                if (!("Bearer " + ANALYTICS_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_SHOPPING_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = SHOPPING_RESPONSE.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/analytics/personal-inflation", exchange -> {
                if (!("Bearer " + ANALYTICS_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_PERSONAL_INFLATION_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String asOf = com.jayway.jsonpath.JsonPath.read(LAST_PERSONAL_INFLATION_REQUEST.get(), "$.asOf");
                String response = """
                        {"available":false,"reasonCode":"insufficient_history","asOf":"%s","windowDays":90,
                         "productCount":0,"basketBefore":null,"basketNow":null,"indexPercent":null,
                         "rising":[],"falling":[]}
                        """.formatted(asOf);
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/analytics/waste", exchange -> {
                if (!("Bearer " + ANALYTICS_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_WASTE_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int status = WASTE_RESPONSE_STATUS.get();
                if (status != 200) {
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                    return;
                }
                String asOf = com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.asOf");
                String fromDate = com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.fromDate");
                String toDate = com.jayway.jsonpath.JsonPath.read(LAST_WASTE_REQUEST.get(), "$.toDate");
                String response = """
                        {"available":true,"reasonCode":"available","algorithmVersion":"advice-waste.v1",
                         "completeness":"complete","asOf":"%s","inputVersion":"0000000000000000000000000000000000000000000000000000000000000000",
                         "fromDate":"%s","toDate":"%s","reviewedSpend":"300.00","optionalSpend":"50.00",
                         "optionalShare":"0.167","reviewedItemCount":3,"optionalItemCount":1,"missingAmountCount":0,
                         "byVerdict":[{"verdict":"harmful","amount":"50.00","count":1}],
                         "bySource":{"unknown":"50.00"},"topItems":[],"repeats":[],"corrected":[],"optionalByDay":{}}
                        """.formatted(asOf, fromDate, toDate);
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/analytics/advice/evidence-groups", exchange -> {
                if (!("Bearer " + ANALYTICS_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_EVIDENCE_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int responseStatus = EVIDENCE_RESPONSE_STATUS.get();
                if (responseStatus != 200) {
                    exchange.sendResponseHeaders(responseStatus, -1);
                    exchange.close();
                    return;
                }
                byte[] body = """
                        {"algorithmVersion":"advice-evidence.v1",
                         "inputVersion":"0000000000000000000000000000000000000000000000000000000000000000",
                         "groups":[
                           {"productKey":"chips","productName":"Chips","count":2,"amount":"3.00",
                            "missingAmountCount":0,"ruleCount":1,"modelCount":1,"unmarkedCount":0,
                            "modelOnly":false,"latestVerdict":"harmful","latestAdvice":"",
                            "lastPurchasedAt":"2026-09-02T11:00:00Z"},
                           {"productKey":"coffee","productName":"Coffee","count":2,"amount":"27.00",
                            "missingAmountCount":0,"ruleCount":0,"modelCount":2,"unmarkedCount":0,
                            "modelOnly":true,"latestVerdict":"harmful","latestAdvice":"",
                            "lastPurchasedAt":"2026-09-10T10:00:00Z"}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/internal/v1/analytics/recurring", exchange -> {
                if (!("Bearer " + ANALYTICS_SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    exchange.close();
                    return;
                }
                LAST_RECURRING_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String asOf = com.jayway.jsonpath.JsonPath.read(LAST_RECURRING_REQUEST.get(), "$.asOf");
                String timeZone = com.jayway.jsonpath.JsonPath.read(LAST_RECURRING_REQUEST.get(), "$.timeZone");
                String startOfDay = Instant.parse(asOf).atZone(ZoneId.of(timeZone)).toLocalDate()
                        .atStartOfDay(ZoneId.of(timeZone)).toInstant().toString();
                String response;
                if (RECURRING_FIXTURE_ENABLED.get()) {
                    LocalDate today = Instant.parse(asOf).atZone(ZoneId.of(timeZone)).toLocalDate();
                    String series = """
                            {"id":"%s","key":"internet utilities","name":"Internet","category":"utilities",
                             "type":"expense","currency":"RUB","amount":"1500.00","minAmount":"1500.00",
                             "maxAmount":"1500.00","periodCode":"month","periodDays":30,
                             "minIntervalDays":30,"maxIntervalDays":30,"occurrences":3,
                             "lastDate":"%s","nextDate":"%s","daysUntil":1}
                            """.formatted(RECURRING_SERIES_ID, today.minusDays(29), today.plusDays(1));
                    response = """
                            {"algorithmVersion":"recurring.v1","completeness":"complete","timeZone":"%s","asOf":"%s",
                             "expenseSeries":[%s],"incomeSeries":[],"dueSoon":[%s],"overdue":[],"nextIncome":null,
                             "monthlyExpenseEstimate":"1500.00","monthlyExpenseEstimates":{"RUB":"1500.00"}}
                            """.formatted(timeZone, startOfDay, series, series);
                } else {
                    response = """
                            {"algorithmVersion":"recurring.v1","completeness":"complete","timeZone":"%s","asOf":"%s",
                             "expenseSeries":[],"incomeSeries":[],"dueSoon":[],"overdue":[],"nextIncome":null,
                             "monthlyExpenseEstimate":null,"monthlyExpenseEstimates":{}}
                            """.formatted(timeZone, startOfDay);
                }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
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
