package com.decorix.finance.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class TransactionDraftAdvisorTest {
    private static final String SERVICE_TOKEN = "test-ai-service-token";
    private static final String VALID_RESPONSE = """
            {"type":"expense","amount":"2000.00","categoryCode":"transport",
             "subcategoryCode":null,"description":"Taxi","occurredAt":"2026-10-01T09:00:00+03:00",
             "provider":"ollama","modelVersion":"model-test","promptVersion":"draft.v1"}
            """;

    @Test
    void sendsTaskEnvelopeToPrivateIntelligenceService() throws IOException {
        AtomicReference<Map<String, String>> observed = new AtomicReference<>(Map.of());
        HttpServer server = server();
        server.createContext("/internal/v1/transaction-drafts", exchange -> {
            observed.set(Map.of(
                    "task", exchange.getRequestHeaders().getFirst("X-Finance-Task-Kind"),
                    "input", exchange.getRequestHeaders().getFirst("X-Finance-Input-Schema-Version"),
                    "output", exchange.getRequestHeaders().getFirst("X-Finance-Output-Schema-Version"),
                    "policy", exchange.getRequestHeaders().getFirst("X-Finance-AI-Policy"),
                    "capabilities", exchange.getRequestHeaders().getFirst("X-Finance-Required-Capabilities"),
                    "correlation", exchange.getRequestHeaders().getFirst("X-Finance-Correlation-ID"),
                    "deadline", exchange.getRequestHeaders().getFirst("X-Finance-Deadline-Unix-Ms")));
            reply(exchange, 200, VALID_RESPONSE);
        });
        server.start();
        try {
            var advisor = advisor(server, Duration.ofSeconds(3));
            var advice = advisor.advise(new TransactionDraftAdvisor.Context(
                    "Taxi 2k", "Europe/Moscow", "2026-10-01T12:00:00+03:00"));

            assertThat(advice.provider()).isEqualTo("ollama");
            assertThat(advice.modelVersion()).isEqualTo("model-test");
            assertThat(observed.get())
                    .containsEntry("task", "transaction-draft")
                    .containsEntry("input", "transaction-draft-context.v1")
                    .containsEntry("output", "transaction-draft-advice.v1")
                    .containsEntry("policy", "local-only")
                    .containsEntry("capabilities", "structured_output,text");
            assertThat(observed.get().get("correlation")).matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            assertThat(Long.parseLong(observed.get().get("deadline"))).isGreaterThan(System.currentTimeMillis());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsCancellationWhenInferenceRequestTimesOut() throws IOException, InterruptedException {
        CountDownLatch cancellationReceived = new CountDownLatch(1);
        AtomicInteger cancellationCalls = new AtomicInteger();
        ExecutorService executor = Executors.newCachedThreadPool();
        HttpServer server = server();
        server.setExecutor(executor);
        server.createContext("/internal/v1/transaction-drafts", exchange -> {
            try {
                Thread.sleep(700);
                reply(exchange, 200, VALID_RESPONSE);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                exchange.close();
            }
        });
        server.createContext("/internal/v1/jobs/", exchange -> {
            cancellationCalls.incrementAndGet();
            cancellationReceived.countDown();
            reply(exchange, 200, "{\"status\":\"cancellation_requested\"}");
        });
        server.start();
        try {
            var advisor = advisor(server, Duration.ofMillis(100));

            assertThatThrownBy(() -> advisor.advise(new TransactionDraftAdvisor.Context(
                    "Taxi 2k", "Europe/Moscow", "2026-10-01T12:00:00+03:00")))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(cancellationReceived.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellationCalls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static TransactionDraftAdvisor advisor(HttpServer server, Duration timeout) {
        return new TransactionDraftAdvisor(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
                SERVICE_TOKEN, timeout, "local-only");
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
