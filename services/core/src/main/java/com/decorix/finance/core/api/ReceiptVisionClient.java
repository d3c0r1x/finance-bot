package com.decorix.finance.core.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReceiptVisionClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Pattern MONEY = Pattern.compile("\\d{1,18}(?:\\.\\d{1,2})?");
    private static final Pattern QUANTITY = Pattern.compile("\\d{1,12}(?:\\.\\d{1,6})?");
    private static final Set<String> RESPONSE_FIELDS = Set.of("provider", "store", "date", "total", "items",
            "modelVersion", "promptVersion", "taskKind", "inputSchemaVersion", "outputSchemaVersion",
            "correlationId", "executionPolicy", "requiredCapabilities", "capabilities", "latencyMs", "usage",
            "fallbackReason", "provenance");

    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration timeout;
    private final String executionPolicy;

    public ReceiptVisionClient(ObjectMapper json,
            @Value("${finance.ai.receipt-vision.url:}") String serviceUrl,
            @Value("${finance.ai.receipt-vision.service-token:}") String serviceToken,
            @Value("${finance.ai.receipt-vision.timeout:PT95S}") Duration timeout,
            @Value("${finance.ai.execution-policy:local-only}") String executionPolicy) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.timeout = timeout;
        this.executionPolicy = executionPolicy == null ? "" : executionPolicy.trim();
    }

    public boolean isConfigured() {
        return !serviceUrl.isBlank() && !serviceToken.isBlank();
    }

    public VisionReading read(byte[] image, UUID correlationId) {
        if (image == null || image.length == 0 || image.length > ReceiptImageValidator.MAX_BYTES) {
            throw new IllegalArgumentException("Receipt image size is invalid");
        }
        if (!Set.of("local-only", "cloud-opt-in").contains(executionPolicy)) {
            throw new IllegalStateException("Receipt Vision execution policy is invalid");
        }
        if (!isConfigured()) throw new VisionUnavailableException("VISION_NOT_CONFIGURED");
        AiGatewayRequest envelope = AiGatewayRequest.create("receipt-vision", timeout, executionPolicy,
                Set.of("vision", "structured_output"), correlationId);
        try {
            String body = json.writeValueAsString(Map.of("imageBase64", Base64.getEncoder().encodeToString(image)));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/receipts/vision"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json");
            envelope.applyTo(request);
            HttpResponse<java.io.InputStream> response = HTTP.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var responseBody = response.body()) {
                byte[] responseBytes = responseBody.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (responseBytes.length > MAX_RESPONSE_BYTES) throw new VisionUnavailableException("VISION_RESPONSE_TOO_LARGE");
                if (response.statusCode() != 200) {
                    String code = switch (response.statusCode()) {
                        case 422 -> "VISION_UNSUPPORTED";
                        case 502 -> "VISION_INVALID_RESPONSE";
                        case 504 -> "VISION_TIMEOUT";
                        default -> "VISION_UNAVAILABLE";
                    };
                    throw new VisionUnavailableException(code);
                }
                return parse(new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8), correlationId);
            }
        } catch (InterruptedException error) {
            AiGatewayRequest.cancel(HTTP, serviceUrl, serviceToken, envelope.correlationId());
            Thread.currentThread().interrupt();
            throw new VisionUnavailableException("VISION_INTERRUPTED", error);
        } catch (java.net.http.HttpTimeoutException error) {
            AiGatewayRequest.cancel(HTTP, serviceUrl, serviceToken, envelope.correlationId());
            throw new VisionUnavailableException("VISION_TIMEOUT", error);
        } catch (IOException error) {
            AiGatewayRequest.cancel(HTTP, serviceUrl, serviceToken, envelope.correlationId());
            throw new VisionUnavailableException("VISION_UNAVAILABLE", error);
        } catch (JacksonException error) {
            throw new IllegalStateException("Receipt Vision request could not be encoded", error);
        }
    }

    private VisionReading parse(String body, UUID expectedCorrelationId) {
        try {
            Object decoded = json.readValue(body, Object.class);
            if (!(decoded instanceof Map<?, ?> response) || !RESPONSE_FIELDS.equals(response.keySet())) throw invalidResponse();
            String provider = requiredString(response.get("provider"), 64);
            String modelVersion = requiredString(response.get("modelVersion"), 128);
            String promptVersion = requiredString(response.get("promptVersion"), 64);
            if (!"ollama".equals(provider) || !"receipt-vision".equals(response.get("taskKind"))
                    || !"receipt-vision-context.v1".equals(response.get("inputSchemaVersion"))
                    || !"receipt-vision-result.v1".equals(response.get("outputSchemaVersion"))
                    || !executionPolicy.equals(response.get("executionPolicy"))) throw invalidResponse();
            UUID correlationId = UUID.fromString(requiredString(response.get("correlationId"), 36));
            if (!correlationId.equals(expectedCorrelationId)) throw invalidResponse();
            if (!List.of("structured_output", "vision").equals(stringList(response.get("requiredCapabilities")))) {
                throw invalidResponse();
            }
            List<String> capabilities = stringList(response.get("capabilities"));
            if (!capabilities.containsAll(List.of("vision", "structured_output"))) throw invalidResponse();
            if (number(response.get("latencyMs"), 0, Long.MAX_VALUE) < 0) throw invalidResponse();
            validateUsage(response.get("usage"));
            Object fallback = response.get("fallbackReason");
            if (fallback != null && (!(fallback instanceof String reason) || reason.length() > 64)) throw invalidResponse();
            Map<?, ?> provenance = mapWithFields(response.get("provenance"),
                    Set.of("provider", "modelVersion", "promptVersion", "executionPolicy"));
            if (!provider.equals(provenance.get("provider")) || !modelVersion.equals(provenance.get("modelVersion"))
                    || !promptVersion.equals(provenance.get("promptVersion"))
                    || !executionPolicy.equals(provenance.get("executionPolicy"))) throw invalidResponse();
            String store = optionalText(response.get("store"), 100);
            String receiptDate = optionalDate(response.get("date"));
            String total = optionalMoney(response.get("total"));
            List<Map<String, Object>> items = parseItems(response.get("items"));
            return new VisionReading(store, receiptDate, total, items, provider, modelVersion, promptVersion,
                    (String) fallback);
        } catch (JacksonException | IllegalArgumentException error) {
            if (error instanceof IllegalArgumentException illegalArgument
                    && "Receipt Vision response is invalid".equals(illegalArgument.getMessage())) throw illegalArgument;
            throw invalidResponse();
        }
    }

    private static List<Map<String, Object>> parseItems(Object value) {
        if (!(value instanceof List<?> source) || source.size() > 80) throw invalidResponse();
        List<Map<String, Object>> items = new ArrayList<>(source.size());
        for (Object item : source) {
            Map<?, ?> fields = mapWithFields(item, Set.of("name", "quantity", "unitPrice", "lineSum"));
            String name = requiredString(fields.get("name"), 200);
            String quantity = optionalQuantity(fields.get("quantity"));
            String unitPrice = optionalMoney(fields.get("unitPrice"));
            String lineSum = optionalMoney(fields.get("lineSum"));
            Map<String, Object> normalized = new java.util.LinkedHashMap<>();
            normalized.put("name", name);
            normalized.put("quantity", quantity);
            normalized.put("unitPrice", unitPrice);
            normalized.put("lineSum", lineSum);
            items.add(normalized);
        }
        return List.copyOf(items);
    }

    private static String optionalText(Object value, int max) {
        if (value == null) return null;
        return requiredString(value, max);
    }

    private static String optionalDate(Object value) {
        if (value == null) return null;
        String text = requiredString(value, 10);
        try { return LocalDate.parse(text).toString(); }
        catch (java.time.DateTimeException error) { throw invalidResponse(); }
    }

    private static String optionalMoney(Object value) {
        return optionalDecimal(value, MONEY, 2);
    }

    private static String optionalQuantity(Object value) {
        return optionalDecimal(value, QUANTITY, 6);
    }

    private static String optionalDecimal(Object value, Pattern pattern, int scale) {
        if (value == null) return null;
        String text = requiredString(value, 32);
        try {
            if (!pattern.matcher(text).matches() || new BigDecimal(text).signum() <= 0
                    || new BigDecimal(text).scale() > scale) throw invalidResponse();
            return text;
        } catch (NumberFormatException error) { throw invalidResponse(); }
    }

    private static void validateUsage(Object value) {
        Map<?, ?> usage = mapWithFields(value, Set.of("inputTokens", "outputTokens", "cost"));
        for (String key : List.of("inputTokens", "outputTokens", "cost")) {
            Object field = usage.get(key);
            if (field != null && number(field, 0, Double.MAX_VALUE) < 0) throw invalidResponse();
        }
    }

    private static Map<?, ?> mapWithFields(Object value, Set<String> expected) {
        if (!(value instanceof Map<?, ?> map) || !expected.equals(map.keySet())) throw invalidResponse();
        return map;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) throw invalidResponse();
        List<String> result = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof String string)) throw invalidResponse();
            result.add(string);
        }
        return List.copyOf(result);
    }

    private static String requiredString(Object value, int maxLength) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength) throw invalidResponse();
        return text;
    }

    private static double number(Object value, double min, double max) {
        if (!(value instanceof Number numeric)) throw invalidResponse();
        double number = numeric.doubleValue();
        if (!Double.isFinite(number) || number < min || number > max) throw invalidResponse();
        return number;
    }

    private static IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException("Receipt Vision response is invalid");
    }

    public record VisionReading(String store, String date, String total, List<Map<String, Object>> items,
                                String provider, String modelVersion, String promptVersion, String fallbackReason) {}

    public static final class VisionUnavailableException extends RuntimeException {
        private final String errorCode;

        public VisionUnavailableException(String errorCode) {
            super("Receipt Vision is unavailable");
            this.errorCode = errorCode;
        }

        public VisionUnavailableException(String errorCode, Throwable cause) {
            super("Receipt Vision is unavailable", cause);
            this.errorCode = errorCode;
        }

        public String errorCode() { return errorCode; }
    }
}
