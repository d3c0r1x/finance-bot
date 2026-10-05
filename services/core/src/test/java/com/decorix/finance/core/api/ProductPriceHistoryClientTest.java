package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ProductPriceHistoryClientTest {
    @Test
    @SuppressWarnings("unchecked")
    void requestsCatalogWithCoreResolvedMemberAndValidatesRealHistory() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/products/catalog", exchange -> {
                path.set(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = validResponse().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            var client = new ProductPriceHistoryClient(new ObjectMapper(), base(server), "analytics-secret",
                    Duration.ofSeconds(2));

            var response = client.catalog(tenant.toString(), owner.toString(), " tea ");

            assertEquals("/internal/v1/products/catalog", path.get());
            assertEquals("Bearer analytics-secret", authorization.get());
            Map<String, Object> sent = new ObjectMapper().readValue(requestBody.get(), Map.class);
            assertEquals(tenant.toString(), sent.get("tenantId"));
            assertEquals(owner.toString(), sent.get("ownerUserId"));
            assertEquals(" tea ", sent.get("query"));
            assertEquals("search", response.mode());
            assertEquals("tea", response.products().get(0).productName());
            assertEquals(2, response.products().get(0).history().size());
        } finally { server.stop(0); }
    }

    @Test
    void rejectsCatalogResponseThatInventsBaselineWithoutPriorPurchase() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/internal/v1/products/catalog", exchange -> {
                byte[] body = """
                        {"mode":"search","query":"tea","products":[{"productName":"tea","purchaseCount":1,
                         "usualUnitPrice":"100.000000","hasBaseline":true,"baselineUnitPrice":"100.000000",
                         "lastUnitPrice":"100.000000","lastPurchasedAt":"2026-09-01T10:00:00Z",
                         "lastMerchant":"Market","cheapestUnitPrice":"100.000000","cheapestMerchant":"Market",
                         "totalSpent":"100.00","change":"0.000000","relative":"0.000000","signal":false,
                         "direction":null,"priorPurchases":1,"chartAvailable":false,"history":[
                           {"receiptId":"00000000-0000-4000-8000-000000000010",
                            "itemId":"00000000-0000-4000-8000-000000000011",
                            "purchasedAt":"2026-09-01T10:00:00Z","merchant":"Market","name":"tea",
                            "unitPrice":"100.000000","current":false}]}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            var client = new ProductPriceHistoryClient(new ObjectMapper(), base(server), "analytics-secret",
                    Duration.ofSeconds(2));

            assertThrows(ResponseStatusException.class,
                    () -> client.catalog(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "tea"));
        } finally { server.stop(0); }
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String validResponse() {
        return """
                {"mode":"search","query":"tea","products":[{"productName":"tea","purchaseCount":2,
                 "usualUnitPrice":"110.000000","hasBaseline":true,"baselineUnitPrice":"100.000000",
                 "lastUnitPrice":"120.000000","lastPurchasedAt":"2026-09-02T10:00:00Z","lastMerchant":null,
                 "cheapestUnitPrice":"100.000000","cheapestMerchant":"Market","totalSpent":"220.00",
                 "change":"20.000000","relative":"0.200000","signal":true,"direction":"up",
                 "priorPurchases":1,"chartAvailable":true,"history":[
                 {"receiptId":"00000000-0000-4000-8000-000000000010","itemId":"00000000-0000-4000-8000-000000000011",
                  "purchasedAt":"2026-09-01T10:00:00Z","merchant":"Market","name":"tea","unitPrice":"100.000000","current":false},
                 {"receiptId":"00000000-0000-4000-8000-000000000012","itemId":"00000000-0000-4000-8000-000000000013",
                  "purchasedAt":"2026-09-02T10:00:00Z","merchant":"Market","name":"tea","unitPrice":"120.000000","current":false}]}]}
                """;
    }
}
