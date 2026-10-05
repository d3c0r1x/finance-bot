package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TBankParserClientTest {
    private static final String VALID_RESULT = """
            {"operations":[{"operationDate":"2026-10-01","operationTime":"12:30","signedAmount":"-12.00",
              "currency":"RUB","kind":"purchase","merchant":"Market","description":"Payment","cardLast4":"1234"}],
             "parsedExpenseTotal":"12.00","parsedIncomeTotal":"0.00","expectedExpenseTotal":"12.00",
             "expectedIncomeTotal":"0.00","quality":"valid","periodStart":"2026-10-01",
             "periodEnd":"2026-10-01","parseVersion":"tbank-pdf.v1"}
            """;

    @Test
    void sendsPdfOnlyToAuthenticatedPrivateParserAndValidatesResult() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        var server = server(200, VALID_RESULT, authorization, body);
        try {
            var client = new TBankParserClient(new ObjectMapper(), base(server), "private-token",
                    java.time.Duration.ofSeconds(2));

            var parsed = client.parse("%PDF-1.7 fixture".getBytes(StandardCharsets.US_ASCII));

            assertEquals("Bearer private-token", authorization.get());
            assertEquals("{\"pdfBase64\":\"JVBERi0xLjcgZml4dHVyZQ==\"}", body.get());
            assertEquals("valid", parsed.statement().quality());
            assertEquals("-12.00", parsed.statement().operations().get(0).signedAmount());
            assertEquals("12.00", parsed.statement().expectedExpenseTotal());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonPdfAndUntrustedUnexpectedParserFields() throws Exception {
        var client = new TBankParserClient(new ObjectMapper(), "http://127.0.0.1:1", "token",
                java.time.Duration.ofSeconds(1));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> client.parse("not a pdf".getBytes(StandardCharsets.US_ASCII)));

        var server = server(200, VALID_RESULT.replace("\"quality\":\"valid\"",
                "\"quality\":\"valid\",\"unexpected\":true"), new AtomicReference<>(), new AtomicReference<>());
        try {
            var invalidClient = new TBankParserClient(new ObjectMapper(), base(server), "private-token",
                    java.time.Duration.ofSeconds(2));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> invalidClient.parse("%PDF-1.7 fixture".getBytes(StandardCharsets.US_ASCII)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesOnlyKnownSafeParserErrorCodesForLocalizedClientMessages() throws Exception {
        var server = server(422, "{\"error\":\"no_text\",\"message\":\"PDF has no extractable text\"}",
                new AtomicReference<>(), new AtomicReference<>());
        try {
            var client = new TBankParserClient(new ObjectMapper(), base(server), "private-token",
                    java.time.Duration.ofSeconds(2));
            var exception = assertThrows(BankImportParseException.class,
                    () -> client.parse("%PDF-1.7 fixture".getBytes(StandardCharsets.US_ASCII)));
            assertEquals("no_text", exception.parserCode());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(int status, String response, AtomicReference<String> authorization,
                                     AtomicReference<String> body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/imports/tbank", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] content = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, content.length);
            try (var output = exchange.getResponseBody()) {
                output.write(content);
            }
        });
        server.start();
        return server;
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
