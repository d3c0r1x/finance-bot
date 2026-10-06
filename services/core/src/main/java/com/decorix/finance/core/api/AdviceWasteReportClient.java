package com.decorix.finance.core.api;

import com.decorix.finance.core.api.AdviceWasteApi.Request;
import com.decorix.finance.core.api.AdviceWasteApi.WasteReport;
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
public class AdviceWasteReportClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Pattern INPUT_VERSION = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> REASONS = Set.of("available", "no_reviewed_items", "missing_amounts");

    private final HttpClient http;
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration timeout;

    public AdviceWasteReportClient(ObjectMapper json,
            @Value("${finance.analytics.price-history.url:}") String serviceUrl,
            @Value("${finance.analytics.price-history.service-token:}") String serviceToken,
            @Value("${finance.analytics.price-history.timeout:PT5S}") Duration timeout) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public WasteReport calculate(Request request) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Advice analytics service is not configured");
        }
        if (request == null || request.items() == null || request.items().size() > 50_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Advice analytics request is invalid");
        }
        try {
            String body = json.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/analytics/waste"))
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
            WasteReport report = json.readValue(responseBody, WasteReport.class);
            validateResponse(report, request);
            return report;
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

    private static void validateResponse(WasteReport result, Request request) {
        if (result == null || !"advice-waste.v1".equals(result.algorithmVersion())
                || !request.asOf().equals(result.asOf()) || !request.fromDate().equals(result.fromDate())
                || !request.toDate().equals(result.toDate()) || !REASONS.contains(result.reasonCode())
                || !("complete".equals(result.completeness()) || "partial".equals(result.completeness()))
                || result.inputVersion() == null || !INPUT_VERSION.matcher(result.inputVersion()).matches()
                || result.byVerdict() == null || result.byVerdict().size() > 2 || result.bySource() == null
                || result.bySource().size() > 5 || result.topItems() == null || result.topItems().size() > 5
                || result.repeats() == null || result.repeats().size() > 3 || result.corrected() == null
                || result.corrected().size() > 5 || result.optionalByDay() == null || result.optionalByDay().size() > 366
                || result.reviewedItemCount() < 0 || result.optionalItemCount() < 0 || result.missingAmountCount() < 0) {
            throw invalidResponse();
        }
        if (result.available() != "available".equals(result.reasonCode())
                || result.available() && (result.reviewedSpend() == null || result.optionalSpend() == null
                        || result.optionalShare() == null || result.reviewedItemCount() == 0)
                || "missing_amounts".equals(result.reasonCode())
                        && (!"partial".equals(result.completeness()) || result.missingAmountCount() == 0)) {
            throw invalidResponse();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Advice analytics service is unavailable");
    }

    private static ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Advice analytics response is incomplete");
    }
}
