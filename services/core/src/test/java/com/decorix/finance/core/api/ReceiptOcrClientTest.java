package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReceiptOcrClientTest {
    @Test
    void sendsImageToLocalOnlyOcrAndValidatesProvenance() throws Exception {
        UUID correlationId = UUID.randomUUID();
        byte[] image = new byte[] {1, 2, 3};
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/receipts/ocr", exchange -> {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = validResponse(correlationId).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            var client = new ReceiptOcrClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                    "service-secret", Duration.ofSeconds(2), "local-only");

            var reading = client.read(image, correlationId);

            assertEquals("TOTAL 12.00", reading.text());
            assertEquals(1, reading.words().size());
            assertEquals("12.00", reading.total().toPlainString());
            assertEquals("Coffee", reading.items().get(0).name());
            assertEquals("12.00", reading.items().get(0).lineSum());
            assertEquals("AQID", new ObjectMapper().readValue(requestBody.get(), java.util.Map.class).get("imageBase64"));
        } finally { server.stop(0); }
    }

    @Test
    void refusesUnsupportedExecutionPolicyBeforeSendingReceiptImage() {
        var client = new ReceiptOcrClient(new ObjectMapper(), "http://127.0.0.1:1", "secret",
                Duration.ofSeconds(1), "cloud");

        assertThrows(IllegalStateException.class, () -> client.read(new byte[] {1}, UUID.randomUUID()));
    }

    @Test
    void rejectsResponseWithMismatchedCorrelationProvenance() throws Exception {
        UUID correlationId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/receipts/ocr", exchange -> {
                byte[] body = validResponse(UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            var client = new ReceiptOcrClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                    "service-secret", Duration.ofSeconds(2), "local-only");

            assertThrows(IllegalArgumentException.class, () -> client.read(new byte[] {1}, correlationId));
        } finally { server.stop(0); }
    }

    @Test
    void keepsOcrLocalWhenGlobalPolicyAlsoAllowsRemoteVision() throws Exception {
        UUID correlationId = UUID.randomUUID();
        AtomicReference<String> executionPolicy = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/receipts/ocr", exchange -> {
                executionPolicy.set(exchange.getRequestHeaders().getFirst("X-Finance-AI-Policy"));
                byte[] body = validResponse(correlationId).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            var client = new ReceiptOcrClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                    "service-secret", Duration.ofSeconds(2), "cloud-opt-in");

            client.read(new byte[] {1}, correlationId);

            assertEquals("local-only", executionPolicy.get());
        } finally { server.stop(0); }
    }

    private static String validResponse(UUID correlationId) {
        return """
                {"provider":"tesseract","text":"TOTAL 12.00","words":[{"text":"TOTAL","confidence":96.0,
                 "box":{"x":1,"y":2,"width":19,"height":8}}],"modelVersion":"tesseract-5.3.0",
                 "total":"12.00","items":[{"name":"Coffee","quantity":null,"unitPrice":"12.00","lineSum":"12.00"}],
                 "promptVersion":"tesseract-ocr.v2","taskKind":"receipt-ocr",
                 "inputSchemaVersion":"receipt-ocr-context.v1","outputSchemaVersion":"receipt-ocr-result.v1",
                 "correlationId":"%s","executionPolicy":"local-only","requiredCapabilities":["text"],
                 "capabilities":["text"],"latencyMs":12,"usage":{"inputTokens":null,"outputTokens":null,"cost":null},
                 "fallbackReason":null,"provenance":{"provider":"tesseract","modelVersion":"tesseract-5.3.0",
                 "promptVersion":"tesseract-ocr.v2","executionPolicy":"local-only"}}
                """.formatted(correlationId);
    }
}
