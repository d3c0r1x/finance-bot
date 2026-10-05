package com.decorix.finance.core.api;

import java.time.Duration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

record AiGatewayRequest(String correlationId, Map<String, String> headers) {
    private static final Map<String, SchemaVersions> TASK_SCHEMAS = Map.of(
            "budget-proposal", new SchemaVersions("budget-proposal-context.v1", "budget-proposal-advice.v1"),
            "transaction-draft", new SchemaVersions("transaction-draft-context.v1", "transaction-draft-advice.v1"),
            "receipt-ocr", new SchemaVersions("receipt-ocr-context.v1", "receipt-ocr-result.v1"),
            "receipt-vision", new SchemaVersions("receipt-vision-context.v1", "receipt-vision-result.v1"),
            "receipt-basket-review", new SchemaVersions("receipt-basket-context.v1", "receipt-basket-review.v1"),
            "merchant-classification", new SchemaVersions("merchant-classification-context.v1", "merchant-classification-result.v1"));
    private static final Set<String> ALLOWED_POLICIES = Set.of("local-only", "cloud-opt-in");
    private static final Set<String> ALLOWED_CAPABILITIES = Set.of(
            "text", "structured_output", "vision", "embeddings", "tool_calling", "streaming");
    private static final long MAX_DEADLINE_MILLIS = Duration.ofMinutes(10).toMillis();

    void applyTo(HttpRequest.Builder request) {
        headers.forEach(request::header);
    }

    static void cancel(HttpClient http, String serviceUrl, String serviceToken, String correlationId) {
        try {
            String base = serviceUrl == null ? "" : serviceUrl.trim().replaceAll("/+$", "");
            if (base.isBlank() || serviceToken == null || serviceToken.isBlank()) {
                return;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/internal/v1/jobs/"
                            + correlationId + "/cancel"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Authorization", "Bearer " + serviceToken)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalArgumentException ignored) {
            // Preserve the original inference failure when cancellation cannot reach the gateway.
        }
    }

    static AiGatewayRequest create(String taskKind, Duration timeout, String executionPolicy,
                                   Set<String> requiredCapabilities) {
        return create(taskKind, timeout, executionPolicy, requiredCapabilities, UUID.randomUUID());
    }

    static AiGatewayRequest create(String taskKind, Duration timeout, String executionPolicy,
                                   Set<String> requiredCapabilities, UUID correlationId) {
        SchemaVersions versions = TASK_SCHEMAS.get(taskKind);
        if (versions == null) {
            throw new IllegalArgumentException("Unsupported AI task kind");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("AI request timeout must be positive");
        }
        if (!ALLOWED_POLICIES.contains(executionPolicy)) {
            throw new IllegalArgumentException("Unsupported AI execution policy");
        }
        if (requiredCapabilities == null || requiredCapabilities.isEmpty()
                || !ALLOWED_CAPABILITIES.containsAll(requiredCapabilities)) {
            throw new IllegalArgumentException("AI request capabilities are invalid");
        }
        Set<String> minimumCapabilities = switch (taskKind) {
            case "receipt-ocr" -> Set.of("text");
            case "receipt-vision" -> Set.of("vision", "structured_output");
            default -> Set.of("text", "structured_output");
        };
        if (correlationId == null || !requiredCapabilities.containsAll(minimumCapabilities)) {
            throw new IllegalArgumentException("AI task requires text and structured output");
        }

        String correlationValue = correlationId.toString();
        long requestedDeadline = Math.min(timeout.toMillis(), MAX_DEADLINE_MILLIS);
        if (requestedDeadline <= 0) {
            throw new IllegalArgumentException("AI request timeout is too small");
        }
        long deadline = System.currentTimeMillis() + requestedDeadline;
        String capabilities = String.join(",", new TreeSet<>(requiredCapabilities));
        return new AiGatewayRequest(correlationValue, Map.of(
                "X-Finance-Task-Kind", taskKind,
                "X-Finance-Input-Schema-Version", versions.input(),
                "X-Finance-Output-Schema-Version", versions.output(),
                "X-Finance-Deadline-Unix-Ms", Long.toString(deadline),
                "X-Finance-AI-Policy", executionPolicy,
                "X-Finance-Correlation-ID", correlationValue,
                "X-Finance-Required-Capabilities", capabilities));
    }

    private record SchemaVersions(String input, String output) {}
}
