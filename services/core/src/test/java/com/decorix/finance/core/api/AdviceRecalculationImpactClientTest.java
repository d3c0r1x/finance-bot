package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.decorix.finance.core.api.AdviceRecalculationImpactApi.Impact;
import com.decorix.finance.core.api.AdviceRecalculationImpactApi.Request;
import com.decorix.finance.core.api.AdviceWasteApi.ReceiptLine;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class AdviceRecalculationImpactClientTest {
    @Test
    void sendsBeforeAndAfterFactsToAuthenticatedGoAndValidatesDelta() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/analytics/recalculation-impact", exchange -> {
                path.set(exchange.getRequestURI().getPath());
                assertEquals("Bearer analytics-secret", exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                byte[] response = ("{\"algorithmVersion\":\"receipt-recalculation-impact.v1\","
                        + "\"inputVersion\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                        + "\"reasonCode\":\"available\",\"completeness\":\"complete\","
                        + "\"optionalSpendBefore\":\"125.00\",\"optionalSpendAfter\":\"0.00\","
                        + "\"optionalSpendDelta\":\"-125.00\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            var client = new AdviceRecalculationImpactClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "analytics-secret", Duration.ofSeconds(2));
            var before = request("harmful", "model");
            var after = request("useful", "rule");

            Impact result = client.calculate(new Request(before, after));

            assertEquals("/internal/v1/analytics/recalculation-impact", path.get());
            assertEquals("-125.00", result.optionalSpendDelta());
            var sent = new ObjectMapper().readValue(body.get(), java.util.Map.class);
            assertEquals("harmful", ((java.util.Map<?, ?>) ((java.util.List<?>) ((java.util.Map<?, ?>) sent.get("before")).get("items")).get(0)).get("verdict"));
            assertEquals("useful", ((java.util.Map<?, ?>) ((java.util.List<?>) ((java.util.Map<?, ?>) sent.get("after")).get("items")).get(0)).get("verdict"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidGoDeltaResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/analytics/recalculation-impact", exchange -> {
                byte[] response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            var client = new AdviceRecalculationImpactClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "analytics-secret", Duration.ofSeconds(2));
            assertThrows(ResponseStatusException.class,
                    () -> client.calculate(new Request(request("harmful", "model"), request("useful", "rule"))));
        } finally {
            server.stop(0);
        }
    }

    private static AdviceWasteApi.Request request(String verdict, String source) {
        return new AdviceWasteApi.Request("2026-10-04", "2026-10-05", Instant.parse("2026-10-06T00:00:00Z"),
                "UTC", List.of(new ReceiptLine("123e4567-e89b-42d3-a456-426614174000", "chips", "Chips",
                "125.00", verdict, source, Instant.parse("2026-10-04T10:00:00Z"), false, 1, 0)));
    }
}
