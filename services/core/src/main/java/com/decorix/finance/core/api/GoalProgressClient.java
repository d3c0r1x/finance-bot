package com.decorix.finance.core.api;

import com.decorix.finance.core.api.GoalCandidatesApi.GoalProgress;
import com.decorix.finance.core.api.GoalCandidatesApi.ProgressRequest;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class GoalProgressClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Pattern WATERMARK = Pattern.compile("^[1-9][0-9]{0,19}$");
    private static final Pattern MONEY = Pattern.compile("^(?:0|[1-9][0-9]{0,14})\\.[0-9]{2}$");
    private static final Pattern PRODUCT_KEY = Pattern.compile("^[a-zа-я0-9]{1,256}$");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration timeout;

    public GoalProgressClient(ObjectMapper json,
            @Value("${finance.analytics.price-history.url:}") String serviceUrl,
            @Value("${finance.analytics.price-history.service-token:}") String serviceToken,
            @Value("${finance.analytics.price-history.timeout:PT5S}") Duration timeout) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.timeout = timeout;
    }

    public GoalProgress calculate(ProgressRequest request) {
        if (serviceUrl.isBlank() || serviceToken.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Goal analytics service is not configured");
        validateRequest(request);
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/analytics/goals/progress"))
                    .timeout(timeout).header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) { body = stream.readNBytes(MAX_RESPONSE_BYTES + 1); }
            if (response.statusCode() != 200 || body.length > MAX_RESPONSE_BYTES) throw unavailable();
            GoalProgress progress = json.readValue(body, GoalProgress.class);
            validateResponse(progress, request);
            return progress;
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

    private static void validateRequest(ProgressRequest request) {
        if (request == null || request.asOf() == null || request.goal() == null || request.purchases() == null
                || request.purchases().size() > 50_000 || request.inputWatermark() == null
                || !WATERMARK.matcher(request.inputWatermark()).matches()) throw invalidRequest();
        var goal = request.goal();
        boolean group = "group".equals(goal.scope());
        if (goal.key() == null || goal.key().length() > 300 || goal.acceptedAt() == null || goal.endsAt() == null
                || !goal.endsAt().equals(goal.acceptedAt().plus(Duration.ofDays(30)))
                || request.asOf().isBefore(goal.acceptedAt())
                || !("count".equals(goal.unit()) || "sum".equals(goal.unit()))
                || !((group && goal.key().startsWith("cat:") && "count".equals(goal.unit()))
                    || (!group && PRODUCT_KEY.matcher(goal.key()).matches()))
                || ("count".equals(goal.unit()) && (goal.countTarget() < 1 || goal.monthlyLimit() != null))
                || ("sum".equals(goal.unit()) && (goal.countTarget() != 0 || !money(goal.monthlyLimit())))
                || (group && !validMembers(goal.memberProductKeys()))
                || (!group && goal.memberProductKeys() != null && !goal.memberProductKeys().isEmpty())) throw invalidRequest();
        for (var purchase : request.purchases()) {
            if (purchase == null || purchase.productKey() == null || !PRODUCT_KEY.matcher(purchase.productKey()).matches()
                    || purchase.purchasedAt() == null
                    || (purchase.lineSum() != null && !money(purchase.lineSum()))) throw invalidRequest();
        }
    }

    private static void validateResponse(GoalProgress progress, ProgressRequest request) {
        var goal = request.goal();
        if (progress == null || !"goal-progress-f45.v1".equals(progress.algorithmVersion())
                || !request.inputWatermark().equals(progress.inputWatermark()) || !goal.unit().equals(progress.unit())
                || progress.bought() < 0 || progress.bought() > 50_000 || progress.daysLeft() < 0 || progress.daysLeft() > 30
                || !goal.acceptedAt().equals(progress.windowStart()) || !goal.endsAt().equals(progress.windowEnd())
                || progress.finished() != request.asOf().isAfter(goal.endsAt())
                || (progress.spent() != null && !money(progress.spent()))
                || (progress.amountsUnknown() != (progress.spent() == null))
                || ("count".equals(goal.unit()) && (progress.over() == null || progress.met() == null))
                || ("sum".equals(goal.unit()) && progress.amountsUnknown() && (progress.over() != null || progress.met() != null))
                || ("sum".equals(goal.unit()) && !progress.amountsUnknown() && (progress.over() == null || progress.met() == null))) {
            throw invalidResponse();
        }
    }

    private static boolean validMembers(java.util.List<String> members) {
        if (members == null || members.isEmpty() || members.size() > 50_000) return false;
        var unique = new HashSet<String>();
        for (String member : members) if (member == null || !PRODUCT_KEY.matcher(member).matches() || !unique.add(member)) return false;
        return true;
    }

    private static boolean money(String value) {
        return value != null && MONEY.matcher(value).matches() && new BigDecimal(value).signum() >= 0;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Goal analytics service is unavailable");
    }

    private static ResponseStatusException invalidRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal progress request is invalid");
    }

    private static ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Goal analytics response is invalid");
    }
}
