package com.decorix.finance.core.api;

import com.decorix.finance.core.api.GoalCandidatesApi.Candidate;
import com.decorix.finance.core.api.GoalCandidatesApi.CandidateReport;
import com.decorix.finance.core.api.GoalCandidatesApi.Request;
import com.decorix.finance.core.api.GoalCandidatesApi.SkippedCandidate;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class GoalCandidatesClient {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final Pattern WATERMARK = Pattern.compile("^[1-9][0-9]{0,19}$");
    private static final Pattern MONEY = Pattern.compile("^(?:0|[1-9][0-9]{0,14})\\.[0-9]{2}$");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration timeout;

    public GoalCandidatesClient(ObjectMapper json,
            @Value("${finance.analytics.price-history.url:}") String serviceUrl,
            @Value("${finance.analytics.price-history.service-token:}") String serviceToken,
            @Value("${finance.analytics.price-history.timeout:PT5S}") Duration timeout) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.timeout = timeout;
    }

    public CandidateReport calculate(Request request) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Goal analytics service is not configured");
        }
        if (request == null || request.decisions() == null || request.purchases() == null
                || request.decisions().size() + request.purchases().size() > 50_000
                || request.inputWatermark() == null || !WATERMARK.matcher(request.inputWatermark()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal analytics request is invalid");
        }
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/analytics/goals/candidates"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) { body = stream.readNBytes(MAX_RESPONSE_BYTES + 1); }
            if (response.statusCode() != 200 || body.length > MAX_RESPONSE_BYTES) throw unavailable();
            CandidateReport report = json.readValue(body, CandidateReport.class);
            validate(report, request);
            return report;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Goal analytics request was interrupted", exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Goal analytics service is unavailable", exception);
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Goal analytics response is invalid", exception);
        }
    }

    public static String watermark(String unit, List<?> decisions, List<?> purchases, ObjectMapper json) {
        try {
            byte[] input = json.writeValueAsBytes(List.of(unit, decisions, purchases));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            java.math.BigInteger value = new java.math.BigInteger(1, digest)
                    .mod(java.math.BigInteger.TEN.pow(20).subtract(java.math.BigInteger.ONE)).add(java.math.BigInteger.ONE);
            return value.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not fingerprint goal candidate input", exception);
        }
    }

    private static void validate(CandidateReport report, Request request) {
        if (report == null || !"goal-candidates-f44.v1".equals(report.algorithmVersion())
                || !request.inputWatermark().equals(report.inputWatermark()) || !request.unit().equals(report.unit())
                || report.products() == null || report.products().size() > 3
                || report.groups() == null || report.groups().size() > 2
                || report.skipped() == null || report.skipped().size() > 3) throw invalid();
        Set<String> keys = new HashSet<>();
        for (Candidate candidate : report.products()) {
            if (candidate == null || candidate.key() == null || candidate.key().isBlank()
                    || !candidate.key().equals(candidate.productKey()) || candidate.name() == null || candidate.name().isBlank()
                    || !request.unit().equals(candidate.unit()) || candidate.evidenceCount() < 1
                    || candidate.purchaseCount() < 2 || candidate.monthlyRate() == null
                    || !money(candidate.monthlyRate()) || new BigDecimal(candidate.monthlyRate()).compareTo(BigDecimal.valueOf(2)) < 0
                    || ("count".equals(candidate.unit()) && ((candidate.monthlySpend() == null)
                            != (candidate.estimatedReduction() == null)))
                    || (candidate.monthlySpend() != null && !money(candidate.monthlySpend()))
                    || (candidate.estimatedReduction() != null && !money(candidate.estimatedReduction()))
                    || ("count".equals(candidate.unit()) && (candidate.countTarget() < 1 || candidate.monthlyLimit() != null))
                    || ("sum".equals(candidate.unit()) && (candidate.countTarget() != 0
                            || !money(candidate.monthlySpend()) || !money(candidate.estimatedReduction())
                            || !money(candidate.monthlyLimit())))
                    || !keys.add(candidate.key())) throw invalid();
        }
        for (Candidate candidate : report.groups()) {
            if (candidate == null || candidate.key() == null || !candidate.key().startsWith("cat:")
                    || candidate.productKey() != null || !"count".equals(candidate.unit())
                    || candidate.countTarget() < 1 || candidate.monthlyLimit() != null
                    || candidate.name() == null || candidate.name().isBlank() || candidate.evidenceCount() < 2
                    || candidate.purchaseCount() < 2 || !money(candidate.monthlyRate())
                    || ((candidate.monthlySpend() == null) != (candidate.estimatedReduction() == null))
                    || (candidate.monthlySpend() != null && !money(candidate.monthlySpend()))
                    || (candidate.estimatedReduction() != null && !money(candidate.estimatedReduction()))
                    || !keys.add(candidate.key())) throw invalid();
        }
        for (SkippedCandidate skipped : report.skipped()) {
            if (skipped == null || skipped.productKey() == null || skipped.name() == null
                    || !(("minimum_savings".equals(skipped.reasonCode()) && money(skipped.monthlySpend()))
                        || ("missing_amounts".equals(skipped.reasonCode()) && skipped.monthlySpend() == null))) throw invalid();
        }
    }

    private static boolean money(String value) {
        return value != null && MONEY.matcher(value).matches() && new BigDecimal(value).signum() >= 0;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Goal analytics service is unavailable");
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Goal analytics response is invalid");
    }
}
