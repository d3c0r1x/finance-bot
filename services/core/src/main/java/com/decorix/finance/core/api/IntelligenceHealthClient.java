package com.decorix.finance.core.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class IntelligenceHealthClient {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final Set<String> CAPABILITIES = Set.of("localAi", "receiptVision", "receiptOcr");
    private static final Set<String> STATUSES = Set.of("available", "unavailable", "disabled");
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> DIAGNOSTICS = Set.of("OLLAMA_UNAVAILABLE", "MODEL_MISSING", "VISION_DISABLED",
            "VISION_CONFIG_INVALID", "TESSERACT_MISSING", "HEALTH_CHECK_FAILED", "HEALTH_SERVICE_UNAVAILABLE");

    private final HttpClient http;
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration timeout;

    public IntelligenceHealthClient(ObjectMapper json,
            @Value("${finance.ai.health.url:}") String serviceUrl,
            @Value("${finance.ai.health.service-token:}") String serviceToken,
            @Value("${finance.ai.health.timeout:PT3S}") Duration timeout) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        Duration requestedTimeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        this.timeout = requestedTimeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : requestedTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    }

    public HealthStatusApi.Response status() {
        if (serviceUrl.isBlank() || serviceToken.isBlank() || timeout.isNegative() || timeout.isZero()) return unavailable();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/health"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (response.statusCode() != 200 || body.length > MAX_RESPONSE_BYTES) return unavailable();
            HealthStatusApi.Response parsed = json.readValue(body, HealthStatusApi.Response.class);
            return valid(parsed) ? parsed : unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (IOException | IllegalArgumentException | JacksonException exception) {
            return unavailable();
        }
    }

    private static boolean valid(HealthStatusApi.Response response) {
        if (response == null || response.capabilities() == null || !response.capabilities().keySet().equals(CAPABILITIES)) {
            return false;
        }
        for (HealthStatusApi.Capability capability : response.capabilities().values()) {
            if (capability == null || !STATUSES.contains(capability.status())
                    || capability.diagnosticCode() != null && !DIAGNOSTICS.contains(capability.diagnosticCode())
                    || "available".equals(capability.status()) && capability.diagnosticCode() != null) return false;
        }
        return true;
    }

    private static HealthStatusApi.Response unavailable() {
        var fallback = new HealthStatusApi.Capability("unavailable", "HEALTH_SERVICE_UNAVAILABLE");
        return new HealthStatusApi.Response(Map.of("localAi", fallback, "receiptVision", fallback, "receiptOcr", fallback));
    }
}
