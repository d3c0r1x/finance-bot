package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ProductApi.PriceCompareRequest;
import com.decorix.finance.core.api.ProductApi.PriceComparison;
import com.decorix.finance.core.api.ProductApi.ProductCatalogRequest;
import com.decorix.finance.core.api.ProductApi.ProductCatalogResponse;
import com.decorix.finance.core.api.ProductApi.ProductCard;
import com.decorix.finance.core.api.ProductApi.PriceHistoryPoint;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProductPriceHistoryClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final HttpClient http;
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration timeout;

    public ProductPriceHistoryClient(ObjectMapper json,
            @Value("${finance.analytics.price-history.url:}") String serviceUrl,
            @Value("${finance.analytics.price-history.service-token:}") String serviceToken,
            @Value("${finance.analytics.price-history.timeout:PT5S}") Duration timeout) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public PriceComparison compare(PriceCompareRequest request) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Price analytics service is not configured");
        }
        try {
            String body = json.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/prices/compare"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream stream = response.body()) {
                responseBody = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBody.length > MAX_RESPONSE_BYTES) {
                throw invalidResponse();
            }
            if (response.statusCode() == 422) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Receipt item does not have a usable quantity and paid line total");
            }
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Price analytics service is unavailable");
            }
            PriceComparison result = json.readValue(responseBody, PriceComparison.class);
            validateResponse(result, request);
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Price analytics request was interrupted", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Price analytics service is unavailable", ex);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Price analytics response is invalid", ex);
        }
    }

    public ProductCatalogResponse catalog(String tenantId, String ownerUserId, String query) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Price analytics service is not configured");
        }
        ProductCatalogRequest request = new ProductCatalogRequest(tenantId, ownerUserId, query == null ? "" : query);
        try {
            String body = json.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/products/catalog"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream stream = response.body()) {
                responseBody = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBody.length > MAX_RESPONSE_BYTES) throw invalidCatalogResponse();
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Price analytics service is unavailable");
            }
            ProductCatalogResponse result = json.readValue(responseBody, ProductCatalogResponse.class);
            validateCatalogResponse(result, request);
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Price analytics request was interrupted", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Price analytics service is unavailable", ex);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Price analytics response is invalid", ex);
        }
    }

    private static void validateCatalogResponse(ProductCatalogResponse result, ProductCatalogRequest request) {
        String expectedMode = request.query().isBlank() ? "catalog" : "search";
        int maxProducts = expectedMode.equals("catalog") ? 10 : 5;
        int minimumPurchases = expectedMode.equals("catalog") ? 3 : 1;
        if (result == null || !expectedMode.equals(result.mode()) || !request.query().trim().equals(result.query())
                || result.products() == null || result.products().size() > maxProducts) {
            throw invalidCatalogResponse();
        }
        for (ProductCard card : result.products()) {
            if (card == null || card.productName() == null || card.productName().isBlank()
                    || card.purchaseCount() < minimumPurchases || card.purchaseCount() > 5000
                    || card.lastPurchasedAt() == null || !positiveDecimal(card.usualUnitPrice())
                    || !positiveDecimal(card.lastUnitPrice()) || !positiveDecimal(card.cheapestUnitPrice())
                    || !positiveDecimal(card.totalSpent()) || card.history() == null || card.history().isEmpty()
                    || card.history().size() > 12 || card.purchaseCount() < card.history().size()
                    || card.chartAvailable() != (card.purchaseCount() >= 2)) {
                throw invalidCatalogResponse();
            }
            boolean completeBaseline = card.hasBaseline() && card.baselineUnitPrice() != null
                    && positiveDecimal(card.baselineUnitPrice()) && card.change() != null
                    && signedDecimal(card.change()) && card.relative() != null && signedDecimal(card.relative())
                    && card.priorPurchases() == card.purchaseCount() - 1 && card.priorPurchases() > 0;
            if (card.hasBaseline() != completeBaseline || !card.hasBaseline()
                    && (card.baselineUnitPrice() != null || card.change() != null || card.relative() != null
                    || card.priorPurchases() != 0 || card.signal() || card.direction() != null)
                    || card.signal() && (!card.hasBaseline() || !("up".equals(card.direction()) || "down".equals(card.direction())))
                    || !card.signal() && card.direction() != null) {
                throw invalidCatalogResponse();
            }
            if (card.history().size() < Math.min(card.purchaseCount(), 12)) throw invalidCatalogResponse();
            for (int index = 0; index < card.history().size(); index++) {
                PriceHistoryPoint point = card.history().get(index);
                if (point == null || point.receiptId() == null || point.itemId() == null || point.purchasedAt() == null
                        || point.name() == null || point.name().isBlank() || !positiveDecimal(point.unitPrice())
                        || point.current() || index > 0 && point.purchasedAt().isBefore(card.history().get(index - 1).purchasedAt())) {
                    throw invalidCatalogResponse();
                }
            }
            if (card.lastMerchant() != null && card.lastMerchant().isBlank()
                    || card.cheapestMerchant() != null && card.cheapestMerchant().isBlank()) {
                throw invalidCatalogResponse();
            }
        }
    }

    private static boolean positiveDecimal(String value) {
        if (value == null) return false;
        try { return new BigDecimal(value).signum() > 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static boolean signedDecimal(String value) {
        if (value == null) return false;
        try { new BigDecimal(value); return true; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static ResponseStatusException invalidCatalogResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Product catalog response is incomplete");
    }

    private static void validateResponse(PriceComparison result, PriceCompareRequest request) {
        if (result == null || !"price-projection.v1".equals(result.algorithmVersion())
                || !request.name().equals(result.productName()) || result.currentUnitPrice() == null
                || result.history() == null || result.history().size() > 5001) {
            throw invalidResponse();
        }
        List<PriceHistoryPoint> history = result.history();
        if (history.isEmpty()) throw invalidResponse();
        PriceHistoryPoint current = history.get(history.size() - 1);
        if (!current.current() || !request.receiptId().equals(current.receiptId())
                || !request.itemId().equals(current.itemId())
                || !request.purchasedAt().equals(current.purchasedAt())
                || !result.currentUnitPrice().equals(current.unitPrice())) {
            throw invalidResponse();
        }
        if (result.hasBaseline()) {
            if (result.baselineUnitPrice() == null || result.change() == null || result.relative() == null
                    || result.priorPurchases() < 1) throw invalidResponse();
        } else if (result.baselineUnitPrice() != null || result.change() != null || result.relative() != null
                || result.signal() || result.direction() != null || result.priorPurchases() != 0) {
            throw invalidResponse();
        }
        if (result.signal()) {
            if (!result.hasBaseline() || !("up".equals(result.direction()) || "down".equals(result.direction()))) {
                throw invalidResponse();
            }
        } else if (result.direction() != null) {
            throw invalidResponse();
        }
    }

    private static ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Price analytics response is incomplete");
    }
}
