package com.decorix.finance.core.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReceiptBasketAdvisor {
    private static final Set<String> ALLOWED_VERDICTS = Set.of("useful", "neutral", "harmful", "unnecessary");
    private static final Set<String> RESPONSE_FIELDS = Set.of("provider", "items", "modelVersion", "promptVersion",
            "taskKind", "inputSchemaVersion", "outputSchemaVersion", "correlationId", "executionPolicy",
            "requiredCapabilities", "capabilities", "latencyMs", "usage", "fallbackReason", "provenance");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration requestTimeout;
    private final String executionPolicy;

    public ReceiptBasketAdvisor(ObjectMapper json,
            @Value("${finance.ai.receipt-basket.url:}") String serviceUrl,
            @Value("${finance.ai.receipt-basket.service-token:}") String serviceToken,
            @Value("${finance.ai.receipt-basket.timeout:PT95S}") Duration requestTimeout,
            @Value("${finance.ai.execution-policy:local-only}") String executionPolicy) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.requestTimeout = requestTimeout;
        this.executionPolicy = executionPolicy == null ? "" : executionPolicy.trim();
    }

    public Advice advise(Context context) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt basket AI service is not configured");
        }
        validateContext(context);
        AiGatewayRequest envelope = null;
        try {
            envelope = AiGatewayRequest.create("receipt-basket-review", requestTimeout, executionPolicy,
                    Set.of("text", "structured_output"));
            String body = json.writeValueAsString(context);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/receipts/basket-review"))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json");
            envelope.applyTo(builder);
            HttpResponse<String> response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 502) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receipt basket AI response is invalid");
            }
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt basket AI service is unavailable");
            }
            return parse(response.body(), context);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            cancel(envelope);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt basket AI request was interrupted", ex);
        } catch (IOException ex) {
            cancel(envelope);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt basket AI service is unavailable", ex);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receipt basket AI response is invalid", ex);
        }
    }

    private Advice parse(String body, Context context) throws JacksonException {
        Object decoded = json.readValue(body, Object.class);
        if (!(decoded instanceof Map<?, ?> response) || !RESPONSE_FIELDS.containsAll(response.keySet())) {
            throw invalidResponse();
        }
        String provider = requiredString(response.get("provider"), "provider");
        String modelVersion = requiredString(response.get("modelVersion"), "modelVersion");
        String promptVersion = requiredString(response.get("promptVersion"), "promptVersion");
        if (!"receipt-basket-review".equals(response.get("taskKind"))
                || !"receipt-basket-context.v1".equals(response.get("inputSchemaVersion"))
                || !"receipt-basket-review.v1".equals(response.get("outputSchemaVersion"))
                || !"receipt-basket.v1".equals(promptVersion)) {
            throw invalidResponse();
        }
        if (!(response.get("items") instanceof List<?> rawItems) || rawItems.size() != context.items().size()) {
            throw invalidResponse();
        }
        List<Suggestion> items = new ArrayList<>(rawItems.size());
        for (int i = 0; i < rawItems.size(); i++) {
            Object raw = rawItems.get(i);
            if (!(raw instanceof Map<?, ?> item) || !Set.of("ordinal", "verdict", "reason", "action").equals(item.keySet())
                    || !isExpectedOrdinal(item.get("ordinal"), i + 1)
                    || !ALLOWED_VERDICTS.contains(item.get("verdict"))) {
                throw invalidResponse();
            }
            String reason = boundedString(item.get("reason"));
            String action = boundedString(item.get("action"));
            items.add(new Suggestion(i + 1, (String) item.get("verdict"), reason, action));
        }
        return new Advice(List.copyOf(items), provider, modelVersion, promptVersion);
    }

    private static void validateContext(Context context) {
        if (context == null || context.items() == null || context.items().isEmpty() || context.items().size() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt basket context is invalid");
        }
        for (int index = 0; index < context.items().size(); index++) {
            ItemName item = context.items().get(index);
            if (item == null || item.ordinal() != index + 1 || item.name() == null || item.name().isBlank()
                    || item.name().length() > 200) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt basket context is invalid");
            }
        }
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String string) || string.isBlank() || string.length() > 128) throw invalidResponse();
        return string;
    }

    private static String boundedString(Object value) {
        if (!(value instanceof String string) || string.length() > 500) throw invalidResponse();
        return string.trim();
    }

    private static boolean isExpectedOrdinal(Object value, int expected) {
        if (!(value instanceof Number number)) return false;
        try {
            return new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(expected)) == 0;
        } catch (NumberFormatException invalidNumber) {
            return false;
        }
    }

    private static ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Receipt basket AI response is invalid");
    }

    private void cancel(AiGatewayRequest envelope) {
        if (envelope != null) AiGatewayRequest.cancel(http, serviceUrl, serviceToken, envelope.correlationId());
    }

    public record ItemName(int ordinal, String name) {}
    public record Context(List<ItemName> items) {}
    public record Suggestion(int ordinal, String verdict, String reason, String action) {}
    public record Advice(List<Suggestion> items, String provider, String modelVersion, String promptVersion) {}
}
