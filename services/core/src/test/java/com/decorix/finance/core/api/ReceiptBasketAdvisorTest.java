package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ReceiptBasketAdvisorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsOnlyOrderedItemNamesAndValidatesProvenanceAndResponseOrdinals() throws Exception {
        var server = server("""
                {"provider":"ollama","items":[
                  {"ordinal":1,"verdict":"harmful","reason":"alcohol","action":"limit purchase"},
                  {"ordinal":2,"verdict":"neutral","reason":"","action":""}],
                 "modelVersion":"test-model","promptVersion":"receipt-basket.v1",
                 "taskKind":"receipt-basket-review","inputSchemaVersion":"receipt-basket-context.v1",
                 "outputSchemaVersion":"receipt-basket-review.v1"}
                """);
        try {
            var advisor = advisor(server.getAddress().getPort());
            var advice = advisor.advise(new ReceiptBasketAdvisor.Context(List.of(
                    new ReceiptBasketAdvisor.ItemName(1, "Beer"),
                    new ReceiptBasketAdvisor.ItemName(2, "Milk"))));

            assertEquals("ollama", advice.provider());
            assertEquals("test-model", advice.modelVersion());
            assertEquals("harmful", advice.items().get(0).verdict());
            assertEquals(2, advice.items().get(1).ordinal());
        } finally { server.stop(0); }
    }

    @Test
    void refusesModelOutputContainingAmountsOrMismatchedOrdinals() throws Exception {
        var server = server("""
                {"provider":"ollama","items":[
                  {"ordinal":2,"verdict":"harmful","reason":"alcohol","action":"limit purchase"},
                  {"ordinal":1,"verdict":"neutral","reason":"","action":""}],
                 "modelVersion":"test-model","promptVersion":"receipt-basket.v1",
                 "taskKind":"receipt-basket-review","inputSchemaVersion":"receipt-basket-context.v1",
                 "outputSchemaVersion":"receipt-basket-review.v1","cashTotal":"999.00"}
                """);
        try {
            var advisor = advisor(server.getAddress().getPort());
            assertThrows(ResponseStatusException.class, () -> advisor.advise(new ReceiptBasketAdvisor.Context(
                    List.of(new ReceiptBasketAdvisor.ItemName(1, "Beer"),
                            new ReceiptBasketAdvisor.ItemName(2, "Milk")))));
        } finally { server.stop(0); }
    }

    @Test
    void refusesFractionalOrdinalInsteadOfTruncatingItToAnItemIndex() throws Exception {
        var server = server("""
                {"provider":"ollama","items":[{"ordinal":1.5,"verdict":"neutral","reason":"","action":""}],
                 "modelVersion":"test-model","promptVersion":"receipt-basket.v1",
                 "taskKind":"receipt-basket-review","inputSchemaVersion":"receipt-basket-context.v1",
                 "outputSchemaVersion":"receipt-basket-review.v1"}
                """);
        try {
            var advisor = advisor(server.getAddress().getPort());
            assertThrows(ResponseStatusException.class, () -> advisor.advise(new ReceiptBasketAdvisor.Context(
                    List.of(new ReceiptBasketAdvisor.ItemName(1, "Milk")))));
        } finally { server.stop(0); }
    }

    private ReceiptBasketAdvisor advisor(int port) {
        return new ReceiptBasketAdvisor(json, "http://127.0.0.1:" + port, "private", Duration.ofSeconds(2), "local-only");
    }

    private static HttpServer server(String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/receipts/basket-review", exchange -> {
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        return server;
    }
}
