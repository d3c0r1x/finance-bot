package com.decorix.finance.core.api;

import com.decorix.finance.core.api.AdviceEvidenceApi.EvidenceGroup;
import com.decorix.finance.core.api.AdviceEvidenceApi.EvidenceLine;
import com.decorix.finance.core.api.AdviceEvidenceApi.GoResponse;
import com.decorix.finance.core.api.AdviceEvidenceApi.Request;
import com.decorix.finance.core.domain.ProductIdentityPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class AdviceEvidenceClient {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final Pattern INPUT_VERSION = Pattern.compile("^[0-9a-f]{64}$");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration timeout;

    public AdviceEvidenceClient(ObjectMapper json,
            @Value("${finance.analytics.price-history.url:}") String serviceUrl,
            @Value("${finance.analytics.price-history.service-token:}") String serviceToken,
            @Value("${finance.analytics.price-history.timeout:PT5S}") Duration timeout) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.timeout = timeout;
    }

    public GoResponse calculate(Request request) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Advice evidence service is not configured");
        }
        if (request == null || request.items() == null || request.items().size() > 50_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Advice evidence request is invalid");
        }
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/analytics/advice/evidence-groups"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (response.statusCode() != 200 || body.length > MAX_RESPONSE_BYTES) throw unavailable();
            GoResponse result = json.readValue(body, GoResponse.class);
            validate(result, request);
            return result;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Advice evidence request was interrupted", exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Advice evidence service is unavailable", exception);
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Advice evidence response is invalid", exception);
        }
    }

    private static void validate(GoResponse result, Request request) {
        if (result == null || !"advice-evidence.v1".equals(result.algorithmVersion())
                || result.inputVersion() == null || !INPUT_VERSION.matcher(result.inputVersion()).matches()
                || result.groups() == null || result.groups().size() > 25_000) throw invalid();
        Map<String, Integer> inputCounts = new HashMap<>();
        for (EvidenceLine line : request.items()) inputCounts.merge(line.productKey(), 1, Integer::sum);
        Set<String> seen = new HashSet<>();
        for (EvidenceGroup group : result.groups()) {
            if (group == null || group.productKey() == null || group.productName() == null
                    || group.productName().isBlank() || group.productName().codePointCount(0, group.productName().length()) > 200
                    || group.latestAdvice() == null || group.latestAdvice().codePointCount(0, group.latestAdvice().length()) > 500
                    || !("harmful".equals(group.latestVerdict()) || "unnecessary".equals(group.latestVerdict()))
                    || group.lastPurchasedAt() == null || group.count() < 2
                    || group.count() != inputCounts.getOrDefault(group.productKey(), 0)
                    || group.ruleCount() < 0 || group.modelCount() < 0 || group.unmarkedCount() < 0
                    || group.ruleCount() + group.modelCount() + group.unmarkedCount() != group.count()
                    || group.modelOnly() != (group.modelCount() == group.count())
                    || group.missingAmountCount() < 0 || group.missingAmountCount() > group.count()
                    || group.missingAmountCount() > 0 != (group.amount() == null)
                    || !seen.add(group.productKey())) throw invalid();
            try {
                ProductIdentityPolicy.requireProductKey(group.productKey());
                if (group.amount() != null && (new java.math.BigDecimal(group.amount()).signum() < 0
                        || new java.math.BigDecimal(group.amount()).scale() != 2)) throw invalid();
            } catch (IllegalArgumentException exception) {
                throw invalid();
            }
        }
        for (Map.Entry<String, Integer> entry : inputCounts.entrySet()) {
            if (entry.getValue() >= 2 && !seen.contains(entry.getKey())) throw invalid();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Advice evidence service is unavailable");
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Advice evidence response is incomplete");
    }
}
