package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.decorix.finance.core.api.GoalCandidatesApi.Decision;
import com.decorix.finance.core.api.GoalCandidatesApi.Purchase;
import com.decorix.finance.core.api.GoalCandidatesApi.Request;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GoalCandidatesClientTest {
    @Test
    void preservesUnknownAmountsForCountCandidatesAndMissingAmountReasons() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/analytics/goals/candidates", exchange -> {
                byte[] response = ("{\"algorithmVersion\":\"goal-candidates-f44.v1\","
                        + "\"inputWatermark\":\"1\",\"unit\":\"count\","
                        + "\"products\":[{\"key\":\"chips\",\"productKey\":\"chips\",\"name\":\"Чипсы\","
                        + "\"unit\":\"count\",\"monthlyRate\":\"4.00\",\"countTarget\":2,"
                        + "\"monthlySpend\":null,\"estimatedReduction\":null,\"purchaseCount\":4,\"evidenceCount\":2}],"
                        + "\"groups\":[],\"skipped\":[{\"productKey\":\"juice\",\"name\":\"Сок\","
                        + "\"monthlySpend\":null,\"reasonCode\":\"missing_amounts\"}]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            var client = new GoalCandidatesClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "analytics-secret", Duration.ofSeconds(2));

            var report = client.calculate(new Request("1", Instant.parse("2026-10-07T12:00:00Z"), "count",
                    List.of(new Decision("chips", "Чипсы", 2, false, false, false)),
                    List.of(new Purchase("chips", "Чипсы", null, Instant.parse("2026-10-07T10:00:00Z")))));

            assertEquals(null, report.products().get(0).monthlySpend());
            assertEquals(null, report.products().get(0).estimatedReduction());
            assertEquals("missing_amounts", report.skipped().get(0).reasonCode());
            assertEquals(null, report.skipped().get(0).monthlySpend());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requiresAndPreservesCapturedKeysForCategoryCandidates() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/analytics/goals/candidates", exchange -> {
                byte[] response = ("{\"algorithmVersion\":\"goal-candidates-f44.v1\",\"inputWatermark\":\"2\","
                        + "\"unit\":\"count\",\"products\":[],\"groups\":[{\"key\":\"cat:sweets\","
                        + "\"memberProductKeys\":[\"cookie\",\"choco\"],\"name\":\"Сладкое\",\"unit\":\"count\","
                        + "\"monthlyRate\":\"2.00\",\"countTarget\":1,\"monthlySpend\":null,"
                        + "\"estimatedReduction\":null,\"purchaseCount\":2,\"evidenceCount\":2}],\"skipped\":[]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            var client = new GoalCandidatesClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "analytics-secret", Duration.ofSeconds(2));
            var report = client.calculate(new Request("2", Instant.parse("2026-10-07T12:00:00Z"), "count",
                    List.of(), List.of()));
            assertEquals(List.of("cookie", "choco"), report.groups().get(0).memberProductKeys());
        } finally {
            server.stop(0);
        }
    }
}
