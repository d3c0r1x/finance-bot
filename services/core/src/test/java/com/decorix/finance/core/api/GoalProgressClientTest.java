package com.decorix.finance.core.api;

import com.decorix.finance.core.api.GoalCandidatesApi.F45GoalInput;
import com.decorix.finance.core.api.GoalCandidatesApi.F45PurchaseInput;
import com.decorix.finance.core.api.GoalCandidatesApi.ProgressRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoalProgressClientTest {
    @Test
    void postsOnlyTheMemberSnapshotAndPreservesUnknownMoneyAsNull() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/analytics/goals/progress", exchange -> {
                assertEquals("Bearer progress-secret", exchange.getRequestHeaders().getFirst("Authorization"));
                String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!request.contains("\"memberProductKeys\":[\"чипсы\"]") || request.contains("tenantId")) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }
                byte[] response = ("{\"algorithmVersion\":\"goal-progress-f45.v1\","
                        + "\"inputWatermark\":\"7\",\"unit\":\"count\",\"bought\":1,"
                        + "\"spent\":null,\"amountsUnknown\":true,\"over\":false,\"met\":true,"
                        + "\"finished\":false,\"daysLeft\":29,"
                        + "\"windowStart\":\"2026-10-01T12:00:00Z\","
                        + "\"windowEnd\":\"2026-10-31T12:00:00Z\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            var client = new GoalProgressClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "progress-secret", Duration.ofSeconds(2));
            var request = new ProgressRequest("7", Instant.parse("2026-10-02T12:00:00Z"),
                    new F45GoalInput("cat:снеки", "group", "count", Instant.parse("2026-10-01T12:00:00Z"),
                            Instant.parse("2026-10-31T12:00:00Z"), 2, null, List.of("чипсы")),
                    List.of(new F45PurchaseInput("чипсы", null, Instant.parse("2026-10-02T10:00:00Z"))));
            var progress = client.calculate(request);
            assertEquals(1, progress.bought());
            assertEquals(null, progress.spent());
            assertEquals(true, progress.met());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnalyticsResponsesThatDoNotMatchTheGoalTerms() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            byte[] response = ("{\"algorithmVersion\":\"goal-progress-f45.v1\","
                    + "\"inputWatermark\":\"wrong\",\"unit\":\"count\",\"bought\":0,"
                    + "\"spent\":\"0.00\",\"amountsUnknown\":false,\"over\":false,\"met\":true,"
                    + "\"finished\":false,\"daysLeft\":30,"
                    + "\"windowStart\":\"2026-10-01T12:00:00Z\","
                    + "\"windowEnd\":\"2026-10-31T12:00:00Z\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            server.createContext("/internal/v1/analytics/goals/progress", exchange -> {
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            var client = new GoalProgressClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "progress-secret", Duration.ofSeconds(2));
            var request = new ProgressRequest("7", Instant.parse("2026-10-02T12:00:00Z"),
                    new F45GoalInput("chips", "product", "count", Instant.parse("2026-10-01T12:00:00Z"),
                            Instant.parse("2026-10-31T12:00:00Z"), 2, null, List.of()), List.of());
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> client.calculate(request));
        } finally {
            server.stop(0);
        }
    }
}
