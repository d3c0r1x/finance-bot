package com.decorix.finance.core.api;

import com.decorix.finance.core.api.AdviceRecalculationImpactApi.Impact;
import com.decorix.finance.core.api.AdviceRecalculationImpactApi.Request;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class AdviceRecalculationImpactClient {
    private static final int MAX_REQUEST_ITEMS = 50_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Pattern INPUT_VERSION = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern AMOUNT = Pattern.compile("^-?(?:0|[1-9][0-9]{0,15})(?:\\.[0-9]{1,2})?$");
    private static final Set<String> REASONS = Set.of("available", "no_reviewed_items", "missing_amounts",
            "analytics_unavailable");

    private final HttpClient http;
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration timeout;

    public AdviceRecalculationImpactClient(ObjectMapper json,
            @Value("${finance.analytics.price-history.url:}") String serviceUrl,
            @Value("${finance.analytics.price-history.service-token:}") String serviceToken,
            @Value("${finance.analytics.price-history.timeout:PT5S}") Duration timeout) {
        this(json, serviceUrl, serviceToken, timeout, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    AdviceRecalculationImpactClient(ObjectMapper json, String serviceUrl, String serviceToken, Duration timeout,
                                    HttpClient http) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.timeout = timeout;
        this.http = http;
    }

    public Impact calculate(Request request) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Advice analytics service is not configured");
        }
        if (request == null || request.before() == null || request.after() == null
                || request.before().items() == null || request.after().items() == null
                || request.before().items().size() > MAX_REQUEST_ITEMS
                || request.after().items().size() > MAX_REQUEST_ITEMS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recalculation impact request is invalid");
        }
        try {
            String body = json.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/analytics/recalculation-impact"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream stream = response.body()) {
                responseBody = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBody.length > MAX_RESPONSE_BYTES || response.statusCode() != 200) {
                throw unavailable();
            }
            Impact result = json.readValue(responseBody, Impact.class);
            validate(result);
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Advice analytics request was interrupted", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Advice analytics service is unavailable", ex);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Advice analytics response is invalid", ex);
        }
    }

    private static void validate(Impact result) {
        if (result == null || !"receipt-recalculation-impact.v1".equals(result.algorithmVersion())
                || result.inputVersion() == null || !INPUT_VERSION.matcher(result.inputVersion()).matches()
                || !REASONS.contains(result.reasonCode())
                || !("complete".equals(result.completeness()) || "partial".equals(result.completeness()))) {
            throw invalidResponse();
        }
        if ("available".equals(result.reasonCode())) {
            if (!"complete".equals(result.completeness()) || !amount(result.optionalSpendBefore())
                    || !amount(result.optionalSpendAfter()) || !amount(result.optionalSpendDelta())) {
                throw invalidResponse();
            }
        } else if (result.optionalSpendBefore() != null || result.optionalSpendAfter() != null
                || result.optionalSpendDelta() != null) {
            throw invalidResponse();
        }
    }

    private static boolean amount(String value) {
        return value != null && AMOUNT.matcher(value).matches();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Advice analytics service is unavailable");
    }

    private static ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Advice analytics response is incomplete");
    }
}
