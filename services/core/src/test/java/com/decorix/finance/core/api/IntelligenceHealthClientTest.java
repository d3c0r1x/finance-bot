package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IntelligenceHealthClientTest {
    @Test
    void requestsOnlyPrivateHealthAndReturnsSanitizedCapabilities() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/health", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                path.set(exchange.getRequestURI().getPath());
                byte[] response = ("{\"capabilities\":{"
                        + "\"localAi\":{\"status\":\"available\",\"diagnosticCode\":null},"
                        + "\"receiptVision\":{\"status\":\"disabled\",\"diagnosticCode\":\"VISION_DISABLED\"},"
                        + "\"receiptOcr\":{\"status\":\"unavailable\",\"diagnosticCode\":\"TESSERACT_MISSING\"}},"
                        + "\"privateHost\":\"ollama-secret\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            var client = new IntelligenceHealthClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "service-secret", Duration.ofSeconds(2));

            var status = client.status();

            assertEquals("/internal/v1/health", path.get());
            assertEquals("Bearer service-secret", authorization.get());
            assertEquals("available", status.capabilities().get("localAi").status());
            assertEquals("TESSERACT_MISSING", status.capabilities().get("receiptOcr").diagnosticCode());
            assertTrue(status.toString().contains("TESSERACT_MISSING"));
            assertTrue(!status.toString().contains("ollama-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidOrUnavailableUpstreamReturnsFixedSafeFallback() {
        var client = new IntelligenceHealthClient(new ObjectMapper(), "", "", Duration.ofMillis(50));
        var status = client.status();

        assertEquals("unavailable", status.capabilities().get("localAi").status());
        assertEquals("HEALTH_SERVICE_UNAVAILABLE", status.capabilities().get("localAi").diagnosticCode());
        assertEquals("HEALTH_SERVICE_UNAVAILABLE", status.capabilities().get("receiptVision").diagnosticCode());
        assertEquals("HEALTH_SERVICE_UNAVAILABLE", status.capabilities().get("receiptOcr").diagnosticCode());
    }
}
