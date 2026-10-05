package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReceiptVisionClientTest {
    @Test
    void sendsTypedVisionRequestAndValidatesActualModelProvenance() throws Exception {
        UUID correlationId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/receipts/vision", exchange -> {
                byte[] body = validResponse(correlationId).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            var client = new ReceiptVisionClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                    "service-secret", Duration.ofSeconds(2), "local-only");

            var reading = client.read(new byte[] {1, 2, 3}, correlationId);

            assertEquals("qwen3-vl:4b", reading.modelVersion());
            assertEquals("receipt-vision.v1", reading.promptVersion());
            assertEquals("Хлеб", reading.items().get(0).get("name"));
            assertTrue(client.isConfigured());
        } finally { server.stop(0); }
    }

    @Test
    void rejectsInvalidPolicyAndMismatchedCorrelation() throws Exception {
        var invalidPolicy = new ReceiptVisionClient(new ObjectMapper(), "http://127.0.0.1:1", "secret",
                Duration.ofSeconds(1), "cloud");
        assertThrows(IllegalStateException.class, () -> invalidPolicy.read(new byte[] {1}, UUID.randomUUID()));

        UUID correlationId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/receipts/vision", exchange -> {
                byte[] body = validResponse(UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            var client = new ReceiptVisionClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                    "service-secret", Duration.ofSeconds(2), "local-only");
            assertThrows(IllegalArgumentException.class, () -> client.read(new byte[] {1}, correlationId));
        } finally { server.stop(0); }
    }

    private static String validResponse(UUID correlationId) {
        return """
                {"provider":"ollama","store":"Магазин","date":"2026-10-01","total":"100.00",
                 "items":[{"name":"Хлеб","quantity":"1","unitPrice":"100.00","lineSum":"100.00"}],
                 "modelVersion":"qwen3-vl:4b","promptVersion":"receipt-vision.v1","taskKind":"receipt-vision",
                 "inputSchemaVersion":"receipt-vision-context.v1","outputSchemaVersion":"receipt-vision-result.v1",
                 "correlationId":"%s","executionPolicy":"local-only","requiredCapabilities":["structured_output","vision"],
                 "capabilities":["structured_output","text","vision"],"latencyMs":12,
                 "usage":{"inputTokens":null,"outputTokens":null,"cost":null},"fallbackReason":null,
                 "provenance":{"provider":"ollama","modelVersion":"qwen3-vl:4b","promptVersion":"receipt-vision.v1",
                 "executionPolicy":"local-only"}}
                """.formatted(correlationId);
    }
}
