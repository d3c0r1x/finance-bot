package com.decorix.finance.core.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TransactionDraftAdvisor {
    private final HttpClient http;
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration requestTimeout;
    private final String executionPolicy;

    public TransactionDraftAdvisor(ObjectMapper json,
            @Value("${finance.ai.budget-proposal.url:}") String serviceUrl,
            @Value("${finance.ai.budget-proposal.service-token:}") String serviceToken,
            @Value("${finance.ai.budget-proposal.timeout:PT95S}") Duration requestTimeout,
            @Value("${finance.ai.execution-policy:local-only}") String executionPolicy) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.requestTimeout = requestTimeout;
        this.executionPolicy = executionPolicy == null ? "" : executionPolicy.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public Advice advise(Context context) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Transaction AI service is not configured");
        }
        AiGatewayRequest envelope = null;
        try {
            envelope = AiGatewayRequest.create("transaction-draft", requestTimeout, executionPolicy,
                    Set.of("text", "structured_output"));
            String body = json.writeValueAsString(context);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/transaction-drafts"))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json");
            envelope.applyTo(builder);
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 502) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Transaction AI response is invalid");
            }
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Transaction AI service is unavailable");
            }
            AdvisorResponse parsed = json.readValue(response.body(), AdvisorResponse.class);
            if (parsed == null || parsed.type() == null || parsed.amount() == null || parsed.categoryCode() == null
                    || parsed.occurredAt() == null || parsed.provider() == null || parsed.provider().isBlank()
                    || parsed.modelVersion() == null || parsed.modelVersion().isBlank()
                    || parsed.promptVersion() == null || parsed.promptVersion().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Transaction AI response is incomplete");
            }
            return new Advice(parsed.type(), parsed.amount(), parsed.categoryCode(), parsed.subcategoryCode(),
                    parsed.description(), parsed.occurredAt(), parsed.provider(), parsed.modelVersion(), parsed.promptVersion());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            cancel(envelope);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Transaction AI request was interrupted", ex);
        } catch (IOException ex) {
            cancel(envelope);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Transaction AI service is unavailable", ex);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Transaction AI service is unavailable", ex);
        }
    }

    private void cancel(AiGatewayRequest envelope) {
        if (envelope != null) {
            AiGatewayRequest.cancel(http, serviceUrl, serviceToken, envelope.correlationId());
        }
    }

    public record Context(String text, String timezone, String now) {}
    public record Advice(String type, String amount, String categoryCode, String subcategoryCode,
                        String description, String occurredAt, String provider, String modelVersion,
                        String promptVersion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AdvisorResponse(String type, String amount, String categoryCode, String subcategoryCode,
                                   String description, String occurredAt, String provider,
                                   String modelVersion, String promptVersion) {}
}
