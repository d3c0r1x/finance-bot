package com.decorix.finance.core.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.decorix.finance.core.domain.MerchantCategoryPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
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
public class MerchantClassificationAdvisor {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration requestTimeout;
    private final String executionPolicy;

    public MerchantClassificationAdvisor(ObjectMapper json,
            @Value("${finance.ai.merchant-classification.url:}") String serviceUrl,
            @Value("${finance.ai.merchant-classification.service-token:}") String serviceToken,
            @Value("${finance.ai.merchant-classification.timeout:PT95S}") Duration requestTimeout,
            @Value("${finance.ai.execution-policy:local-only}") String executionPolicy) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.requestTimeout = requestTimeout;
        this.executionPolicy = executionPolicy == null ? "" : executionPolicy.trim();
    }

    public Advice classify(List<String> merchants) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Merchant classification is not configured");
        }
        if (merchants == null || merchants.isEmpty() || merchants.size() > MerchantCategoryPolicy.MAX_BATCH_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Merchant batch size is invalid");
        }
        AiGatewayRequest envelope = null;
        try {
            envelope = AiGatewayRequest.create("merchant-classification", requestTimeout, executionPolicy,
                    Set.of("text", "structured_output"));
            String body = json.writeValueAsString(new Context(merchants));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/merchant-classifications"))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json");
            envelope.applyTo(builder);
            HttpResponse<String> response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Merchant classification is unavailable");
            }
            GatewayResponse parsed = json.readValue(response.body(), GatewayResponse.class);
            return validate(merchants, parsed);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            AiGatewayRequest.cancel(http, serviceUrl, serviceToken, envelope == null ? "" : envelope.correlationId());
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Merchant classification was interrupted", exception);
        } catch (IOException | IllegalArgumentException | JacksonException exception) {
            AiGatewayRequest.cancel(http, serviceUrl, serviceToken, envelope == null ? "" : envelope.correlationId());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Merchant classification response is invalid", exception);
        }
    }

    private static Advice validate(List<String> requestedMerchants, GatewayResponse response) {
        if (response == null || response.classifications() == null || response.provider() == null
                || response.provider().isBlank() || response.modelVersion() == null || response.modelVersion().isBlank()
                || response.promptVersion() == null || !MerchantCategoryPolicy.ALGORITHM_VERSION.equals(response.promptVersion())
                || response.classifications().size() != requestedMerchants.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Merchant classification response is incomplete");
        }
        Map<String, Classification> byMerchant = new HashMap<>();
        Set<String> expected = new HashSet<>();
        for (String merchant : requestedMerchants) expected.add(MerchantCategoryPolicy.normalizeMerchant(merchant));
        for (Classification classification : response.classifications()) {
            if (classification == null || classification.merchant() == null || classification.categoryCode() == null
                    || classification.confidence() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Merchant classification row is incomplete");
            }
            final String merchant;
            try {
                merchant = MerchantCategoryPolicy.normalizeMerchant(classification.merchant());
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Merchant classification merchant is invalid", exception);
            }
            BigDecimal confidence = classification.confidence();
            if (!expected.contains(merchant) || byMerchant.containsKey(merchant)
                    || !MerchantCategoryPolicy.CATEGORIES.contains(classification.categoryCode())
                    || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0
                    || confidence.scale() > 3) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Merchant classification failed validation");
            }
            byMerchant.put(merchant, new Classification(merchant, classification.categoryCode(), confidence));
        }
        if (!byMerchant.keySet().equals(expected)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Merchant classification omitted a requested shop");
        }
        List<Classification> ordered = requestedMerchants.stream()
                .map(MerchantCategoryPolicy::normalizeMerchant).map(byMerchant::get).toList();
        return new Advice(List.copyOf(ordered), response.provider(), response.modelVersion(), response.promptVersion());
    }

    public record Context(List<String> merchants) {}
    public record Classification(String merchant, String categoryCode, BigDecimal confidence) {}
    public record Advice(List<Classification> classifications, String provider, String modelVersion, String promptVersion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GatewayResponse(List<Classification> classifications, String provider, String modelVersion,
                                   String promptVersion) {}
}
