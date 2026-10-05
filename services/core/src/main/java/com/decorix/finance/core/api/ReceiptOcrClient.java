package com.decorix.finance.core.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReceiptOcrClient {
    private static final int MAX_RESPONSE_BYTES = 2_500_000;
    private static final Pattern MONEY = Pattern.compile("\\d{1,18}(?:\\.\\d{1,2})?");
    private static final Pattern QUANTITY = Pattern.compile("\\d{1,12}(?:\\.\\d{1,6})?");
    private static final Set<String> RESPONSE_FIELDS = Set.of("provider", "text", "words", "modelVersion",
            "total", "items",
            "promptVersion", "taskKind", "inputSchemaVersion", "outputSchemaVersion", "correlationId",
            "executionPolicy", "requiredCapabilities", "capabilities", "latencyMs", "usage", "fallbackReason",
            "provenance");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration timeout;
    private final String executionPolicy;

    public ReceiptOcrClient(ObjectMapper json,
            @Value("${finance.ai.receipt-ocr.url:}") String serviceUrl,
            @Value("${finance.ai.receipt-ocr.service-token:}") String serviceToken,
            @Value("${finance.ai.receipt-ocr.timeout:PT95S}") Duration timeout,
            @Value("${finance.ai.execution-policy:local-only}") String executionPolicy) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.timeout = timeout;
        this.executionPolicy = executionPolicy == null ? "" : executionPolicy.trim();
    }

    public OcrReading read(byte[] image, UUID correlationId) {
        if (image == null || image.length == 0 || image.length > ReceiptImageValidator.MAX_BYTES) {
            throw new IllegalArgumentException("Receipt image size is invalid");
        }
        if (!java.util.Set.of("local-only", "cloud-opt-in").contains(executionPolicy)) {
            throw new IllegalStateException("Receipt OCR execution policy is invalid");
        }
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new IllegalStateException("Receipt OCR service is not configured");
        }
        AiGatewayRequest envelope = AiGatewayRequest.create("receipt-ocr", timeout, "local-only", Set.of("text"), correlationId);
        try {
            String body = json.writeValueAsString(Map.of("imageBase64", Base64.getEncoder().encodeToString(image)));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/receipts/ocr"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json");
            envelope.applyTo(request);
            HttpResponse<java.io.InputStream> response = http.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var responseBody = response.body()) {
                byte[] responseBytes = responseBody.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (responseBytes.length > MAX_RESPONSE_BYTES) throw invalidResponse();
                if (response.statusCode() != 200) {
                    if (response.statusCode() == 502) throw invalidResponse();
                    throw new IllegalStateException("Receipt OCR service is unavailable");
                }
                return parse(new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8), correlationId);
            }
        } catch (InterruptedException error) {
            AiGatewayRequest.cancel(http, serviceUrl, serviceToken, envelope.correlationId());
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Receipt OCR request was interrupted", error);
        } catch (IOException error) {
            AiGatewayRequest.cancel(http, serviceUrl, serviceToken, envelope.correlationId());
            throw new IllegalStateException("Receipt OCR service is unavailable", error);
        } catch (JacksonException error) {
            throw new IllegalStateException("Receipt OCR request could not be encoded", error);
        }
    }

    private OcrReading parse(String body, UUID expectedCorrelationId) {
        try {
            Object decoded = json.readValue(body, Object.class);
            if (!(decoded instanceof Map<?, ?> response) || !RESPONSE_FIELDS.equals(response.keySet())) throw invalidResponse();
            String provider = requiredString(response.get("provider"), 64);
            String modelVersion = requiredString(response.get("modelVersion"), 128);
            String promptVersion = requiredString(response.get("promptVersion"), 64);
            String text = boundedText(response.get("text"));
            if (!"tesseract".equals(provider) || !"tesseract-ocr.v2".equals(promptVersion)
                    || !"receipt-ocr".equals(response.get("taskKind"))
                    || !"receipt-ocr-context.v1".equals(response.get("inputSchemaVersion"))
                    || !"receipt-ocr-result.v1".equals(response.get("outputSchemaVersion"))
                    || !"local-only".equals(response.get("executionPolicy"))) throw invalidResponse();
            UUID correlationId = UUID.fromString(requiredString(response.get("correlationId"), 36));
            if (!correlationId.equals(expectedCorrelationId)) throw invalidResponse();
            if (!List.of("text").equals(stringList(response.get("requiredCapabilities")))) throw invalidResponse();
            List<String> capabilities = stringList(response.get("capabilities"));
            if (!capabilities.contains("text") || number(response.get("latencyMs"), 0, Long.MAX_VALUE) < 0) {
                throw invalidResponse();
            }
            validateUsage(response.get("usage"));
            BigDecimal total = optionalMoney(response.get("total"));
            List<OcrItem> items = parseItems(response.get("items"));
            Object fallback = response.get("fallbackReason");
            if (fallback != null && !(fallback instanceof String)) throw invalidResponse();
            Map<?, ?> provenance = mapWithFields(response.get("provenance"),
                    Set.of("provider", "modelVersion", "promptVersion", "executionPolicy"));
            if (!provider.equals(provenance.get("provider")) || !modelVersion.equals(provenance.get("modelVersion"))
                    || !promptVersion.equals(provenance.get("promptVersion"))
                    || !"local-only".equals(provenance.get("executionPolicy"))) throw invalidResponse();
            List<Map<String, Object>> words = parseWords(response.get("words"));
            double confidence = words.stream().mapToDouble(word -> (double) word.get("confidence")).average().orElse(-1);
            return new OcrReading(text, words, provider, modelVersion, promptVersion,
                    confidence < 0 ? null : java.math.BigDecimal.valueOf(confidence / 100.0)
                            .setScale(4, java.math.RoundingMode.HALF_UP), total, items);
        } catch (JacksonException | IllegalArgumentException error) {
            if (error instanceof IllegalArgumentException illegalArgument
                    && "Receipt OCR response is invalid".equals(illegalArgument.getMessage())) throw illegalArgument;
            throw invalidResponse();
        }
    }

    private static List<Map<String, Object>> parseWords(Object value) {
        if (!(value instanceof List<?> source) || source.size() > 4000) throw invalidResponse();
        List<Map<String, Object>> words = new ArrayList<>(source.size());
        for (Object item : source) {
            Map<?, ?> word = mapWithFields(item, Set.of("text", "confidence", "box"));
            String text = requiredString(word.get("text"), 256);
            double confidence = number(word.get("confidence"), 0, 100);
            Map<?, ?> box = mapWithFields(word.get("box"), Set.of("x", "y", "width", "height"));
            Map<String, Object> normalizedBox = Map.of(
                    "x", integer(box.get("x")), "y", integer(box.get("y")),
                    "width", integer(box.get("width")), "height", integer(box.get("height")));
            words.add(Map.of("text", text, "confidence", confidence, "box", normalizedBox));
        }
        return List.copyOf(words);
    }

    private static List<OcrItem> parseItems(Object value) {
        if (!(value instanceof List<?> source) || source.size() > 80) throw invalidResponse();
        List<OcrItem> items = new ArrayList<>(source.size());
        for (Object item : source) {
            Map<?, ?> fields = mapWithFields(item, Set.of("name", "quantity", "unitPrice", "lineSum"));
            String name = requiredString(fields.get("name"), 200);
            String quantity = optionalDecimal(fields.get("quantity"), QUANTITY, 6);
            String unitPrice = optionalDecimal(fields.get("unitPrice"), MONEY, 2);
            String lineSum = optionalDecimal(fields.get("lineSum"), MONEY, 2);
            items.add(new OcrItem(name, quantity, unitPrice, lineSum));
        }
        return List.copyOf(items);
    }

    private static BigDecimal optionalMoney(Object value) {
        if (value == null) return null;
        String text = requiredString(value, 32);
        try {
            BigDecimal amount = new BigDecimal(text);
            if (!MONEY.matcher(text).matches() || amount.signum() <= 0 || amount.scale() > 2) throw invalidResponse();
            return amount;
        } catch (NumberFormatException error) { throw invalidResponse(); }
    }

    private static String optionalDecimal(Object value, Pattern pattern, int scale) {
        if (value == null) return null;
        String text = requiredString(value, 32);
        try {
            BigDecimal amount = new BigDecimal(text);
            if (!pattern.matcher(text).matches() || amount.signum() <= 0 || amount.scale() > scale) {
                throw invalidResponse();
            }
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

    private static String boundedText(Object value) {
        if (!(value instanceof String text) || text.length() > 1_024_000) throw invalidResponse();
        return text;
    }

    private static long integer(Object value) {
        double number = number(value, 0, Integer.MAX_VALUE);
        if (number != Math.rint(number)) throw invalidResponse();
        return (long) number;
    }

    private static double number(Object value, double min, double max) {
        if (!(value instanceof Number numeric)) throw invalidResponse();
        double number = numeric.doubleValue();
        if (!Double.isFinite(number) || number < min || number > max) throw invalidResponse();
        return number;
    }

    private static IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException("Receipt OCR response is invalid");
    }

    public record OcrReading(String text, List<Map<String, Object>> words, String provider, String modelVersion,
                             String promptVersion, BigDecimal confidence, BigDecimal total, List<OcrItem> items) {
        public OcrReading(String text, List<Map<String, Object>> words, String provider, String modelVersion,
                          String promptVersion, BigDecimal confidence) {
            this(text, words, provider, modelVersion, promptVersion, confidence, null, List.of());
        }

        public OcrReading {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record OcrItem(String name, String quantity, String unitPrice, String lineSum) {}
}
