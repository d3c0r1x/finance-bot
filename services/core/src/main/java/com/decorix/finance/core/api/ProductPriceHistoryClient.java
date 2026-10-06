package com.decorix.finance.core.api;

import com.decorix.finance.core.api.InflationApi.PersonalInflation;
import com.decorix.finance.core.api.InflationApi.PersonalInflationItem;
import com.decorix.finance.core.api.InflationApi.PersonalInflationRequest;
import com.decorix.finance.core.api.RecurringApi.RecurringProjection;
import com.decorix.finance.core.api.RecurringApi.RecurringRequest;
import com.decorix.finance.core.api.RecurringApi.RecurringSeries;
import com.decorix.finance.core.api.ProductApi.PriceCompareRequest;
import com.decorix.finance.core.api.ProductApi.PriceComparison;
import com.decorix.finance.core.api.ProductApi.ProductCatalogRequest;
import com.decorix.finance.core.api.ProductApi.ProductCatalogResponse;
import com.decorix.finance.core.api.ProductApi.ProductCard;
import com.decorix.finance.core.api.ProductApi.ShoppingCandidate;
import com.decorix.finance.core.api.ProductApi.ShoppingList;
import com.decorix.finance.core.api.ProductApi.PriceHistoryPoint;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.math.RoundingMode;
import java.util.UUID;
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

    public ShoppingList shopping(String tenantId, String ownerUserId) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Price analytics service is not configured");
        }
        ProductApi.ShoppingCandidatesRequest request = new ProductApi.ShoppingCandidatesRequest(tenantId, ownerUserId);
        try {
            String body = json.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/shopping/candidates"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream stream = response.body()) {
                responseBody = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBody.length > MAX_RESPONSE_BYTES) throw invalidShoppingResponse();
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Shopping analytics service is unavailable");
            }
            ShoppingList result = json.readValue(responseBody, ShoppingList.class);
            validateShoppingResponse(result);
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Shopping analytics request was interrupted", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Shopping analytics service is unavailable", ex);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Shopping analytics response is invalid", ex);
        }
    }

    public PersonalInflation personalInflation(UUID tenantId, UUID ownerUserId, Instant asOf) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Price analytics service is not configured");
        }
        if (tenantId == null || ownerUserId == null || asOf == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Personal inflation scope is required");
        }
        PersonalInflationRequest request = new PersonalInflationRequest(tenantId, ownerUserId, asOf);
        try {
            String body = json.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/analytics/personal-inflation"))
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
                throw unavailableInflation();
            }
            PersonalInflation result = json.readValue(responseBody, PersonalInflation.class);
            validatePersonalInflation(result, request);
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Personal inflation request was interrupted", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Personal inflation service is unavailable", ex);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Personal inflation response is incomplete", ex);
        }
    }

    public RecurringProjection recurring(UUID tenantId, UUID ownerUserId, Instant asOf, String timeZone) {
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recurring analytics service is not configured");
        }
        if (tenantId == null || ownerUserId == null || asOf == null || timeZone == null || timeZone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recurring analytics scope is required");
        }
        RecurringRequest request = new RecurringRequest(tenantId.toString(), ownerUserId.toString(), asOf, timeZone);
        try {
            String body = json.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/analytics/recurring"))
                    .timeout(timeout).header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream stream = response.body()) { responseBody = stream.readNBytes(MAX_RESPONSE_BYTES + 1); }
            if (responseBody.length > MAX_RESPONSE_BYTES || response.statusCode() != 200) throw unavailableRecurring();
            RecurringProjection result = json.readValue(responseBody, RecurringProjection.class);
            validateRecurring(result, request);
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recurring analytics request was interrupted", ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recurring analytics service is unavailable", ex);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Recurring analytics response is invalid", ex);
        }
    }

    private static void validateRecurring(RecurringProjection result, RecurringRequest request) {
        ZoneId zone = ZoneId.of(request.timeZone());
        LocalDate today = request.asOf().atZone(zone).toLocalDate();
        if (result == null || !"recurring.v1".equals(result.algorithmVersion()) || !"complete".equals(result.completeness())
                || !request.timeZone().equals(result.timeZone()) || result.asOf() == null
                || !result.asOf().atZone(zone).toLocalDate().equals(today)
                || !result.asOf().atZone(zone).toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                || result.expenseSeries() == null || result.incomeSeries() == null || result.dueSoon() == null
                || result.overdue() == null || result.monthlyExpenseEstimates() == null
                || result.expenseSeries().size() > 5000 || result.incomeSeries().size() > 5000
                || result.dueSoon().size() > 5000 || result.overdue().size() > 5000) {
            throw invalidRecurringResponse();
        }
        Set<String> ids = new HashSet<>();
        result.expenseSeries().forEach(series -> validateRecurringSeries(series, "expense", ids, today));
        result.incomeSeries().forEach(series -> validateRecurringSeries(series, "income", ids, today));
        List<RecurringSeries> expectedSoon = result.expenseSeries().stream()
                .filter(series -> series.daysUntil() >= 0 && series.daysUntil() <= 3).toList();
        List<RecurringSeries> expectedOverdue = result.expenseSeries().stream()
                .filter(series -> series.daysUntil() < 0).toList();
        if (!expectedSoon.stream().map(RecurringSeries::id).toList().equals(result.dueSoon().stream().map(RecurringSeries::id).toList())
                || !expectedOverdue.stream().map(RecurringSeries::id).collect(java.util.stream.Collectors.toSet())
                        .equals(result.overdue().stream().map(RecurringSeries::id).collect(java.util.stream.Collectors.toSet()))) {
            throw invalidRecurringResponse();
        }
        result.dueSoon().forEach(series -> validateRecurringSeries(series, "expense", new HashSet<>(), today));
        result.overdue().forEach(series -> validateRecurringSeries(series, "expense", new HashSet<>(), today));
        RecurringSeries expectedIncome = result.incomeSeries().stream().filter(series -> series.daysUntil() >= 0)
                .min(java.util.Comparator.comparingInt(RecurringSeries::daysUntil)).orElse(null);
        if (!java.util.Objects.equals(expectedIncome == null ? null : expectedIncome.id(),
                result.nextIncome() == null ? null : result.nextIncome().id())) throw invalidRecurringResponse();
        Map<String, BigDecimal> expectedMonthly = new java.util.HashMap<>();
        result.expenseSeries().forEach(series -> {
            BigDecimal average = new BigDecimal(series.amount());
            BigDecimal monthly = series.periodDays() >= 25 && series.periodDays() <= 35 ? average
                    : average.multiply(BigDecimal.valueOf(30)).divide(BigDecimal.valueOf(series.periodDays()), 12, RoundingMode.HALF_UP);
            expectedMonthly.merge(series.currency(), monthly, BigDecimal::add);
        });
        Map<String, String> actualMonthly = result.monthlyExpenseEstimates();
        if (actualMonthly.size() != expectedMonthly.size()) throw invalidRecurringResponse();
        expectedMonthly.forEach((currency, amount) -> {
            String actual = actualMonthly.get(currency);
            if (!nonNegativeMoney(actual) || new BigDecimal(actual).compareTo(amount.setScale(2, RoundingMode.HALF_UP)) != 0) {
                throw invalidRecurringResponse();
            }
        });
        if (expectedMonthly.size() == 1) {
            if (!positiveOrZeroMoney(result.monthlyExpenseEstimate()) || !actualMonthly.containsValue(result.monthlyExpenseEstimate())) {
                throw invalidRecurringResponse();
            }
        } else if (result.monthlyExpenseEstimate() != null) throw invalidRecurringResponse();
        if (expectedMonthly.isEmpty() && result.monthlyExpenseEstimate() != null) throw invalidRecurringResponse();
    }

    private static void validateRecurringSeries(RecurringSeries series, String type, Set<String> ids, LocalDate today) {
        if (series == null || series.id() == null || !series.id().matches("[0-9a-f]{32}") || !ids.add(series.id())
                || series.key() == null || series.key().isBlank() || series.name() == null || series.name().isBlank()
                || !type.equals(series.type()) || !"RUB".equals(series.currency())
                || !positiveMoney(series.amount()) || !positiveMoney(series.minAmount()) || !positiveMoney(series.maxAmount())
                || new BigDecimal(series.minAmount()).compareTo(new BigDecimal(series.amount())) > 0
                || new BigDecimal(series.maxAmount()).compareTo(new BigDecimal(series.amount())) < 0
                || new BigDecimal(series.maxAmount()).subtract(new BigDecimal(series.minAmount()))
                        .compareTo(new BigDecimal(series.amount()).multiply(new BigDecimal("0.25"))) > 0
                || series.occurrences() < 3 || series.occurrences() > 5000
                || series.minIntervalDays() < 1 || series.maxIntervalDays() < series.minIntervalDays()
                || series.maxIntervalDays() > 3650 || series.lastDate() == null || series.nextDate() == null) {
            throw invalidRecurringResponse();
        }
        LocalDate last = LocalDate.parse(series.lastDate());
        LocalDate next = LocalDate.parse(series.nextDate());
        if (!last.plusDays(series.periodDays()).equals(next)
                || ChronoUnit.DAYS.between(today, next) != series.daysUntil()
                || series.minIntervalDays() > series.periodDays() || series.maxIntervalDays() < series.periodDays()
                || ("week".equals(series.periodCode())
                ? series.periodDays() < 6 || series.periodDays() > 8
                : "month".equals(series.periodCode()) ? series.periodDays() < 25 || series.periodDays() > 35 : true)) {
            throw invalidRecurringResponse();
        }
    }

    private static boolean positiveOrZeroMoney(String value) { return nonNegativeMoney(value); }

    private static ResponseStatusException unavailableRecurring() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recurring analytics service is unavailable");
    }

    private static ResponseStatusException invalidRecurringResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Recurring analytics response is incomplete");
    }

    private static void validatePersonalInflation(PersonalInflation result, PersonalInflationRequest request) {
        if (result == null || result.asOf() == null || !result.asOf().equals(request.asOf())
                || result.windowDays() != 90 || result.productCount() < 0 || result.productCount() > 5000
                || result.rising() == null || result.falling() == null
                || result.rising().size() > 3 || result.falling().size() > 3) {
            throw invalidInflationResponse();
        }
        if (!result.available()) {
            if (!"insufficient_history".equals(result.reasonCode()) || result.productCount() != 0
                    || result.basketBefore() != null || result.basketNow() != null || result.indexPercent() != null
                    || !result.rising().isEmpty() || !result.falling().isEmpty()) {
                throw invalidInflationResponse();
            }
            return;
        }
        if (!"available".equals(result.reasonCode()) || result.productCount() < 3
                || !positiveMoney(result.basketBefore()) || !nonNegativeMoney(result.basketNow())
                || !signedMoney(result.indexPercent())) {
            throw invalidInflationResponse();
        }
        validateInflationItems(result.rising(), true);
        validateInflationItems(result.falling(), false);
    }

    private static void validateInflationItems(List<PersonalInflationItem> items, boolean rising) {
        for (PersonalInflationItem item : items) {
            if (item == null || item.productName() == null || item.productName().isBlank()
                    || !positiveMoney(item.oldUnitPrice()) || !positiveMoney(item.newUnitPrice())
                    || !positiveMoney(item.oldSpendWeight()) || !signedMoney(item.changePercent())
                    || item.olderPurchaseCount() < 2 || item.windowPurchaseCount() < 1
                    || rising && new BigDecimal(item.changePercent()).signum() <= 0
                    || !rising && new BigDecimal(item.changePercent()).signum() >= 0) {
                throw invalidInflationResponse();
            }
        }
    }

    private static boolean signedMoney(String value) {
        if (value == null) return false;
        try {
            BigDecimal amount = new BigDecimal(value);
            return Math.max(0, amount.scale()) <= 2;
        } catch (NumberFormatException invalid) { return false; }
    }

    private static ResponseStatusException unavailableInflation() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Personal inflation service is unavailable");
    }

    private static ResponseStatusException invalidInflationResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Personal inflation response is incomplete");
    }

    private static void validateShoppingResponse(ShoppingList result) {
        if (result == null || result.candidates() == null || result.candidates().size() > 10
                || result.inventoryTracked() || !nonNegativeMoney(result.estimatedListCost())) {
            throw invalidShoppingResponse();
        }
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (ShoppingCandidate candidate : result.candidates()) {
            if (candidate == null || candidate.productName() == null || candidate.productName().isBlank()
                    || candidate.purchaseCount() < 3 || candidate.purchaseCount() > 5000
                    || candidate.medianIntervalDays() < 3 || candidate.medianIntervalDays() > 3650
                    || candidate.daysUntilDue() > 3 || candidate.daysUntilDue() < -2 * candidate.medianIntervalDays()
                    || candidate.lastPurchasedAt() == null || candidate.dueAt() == null
                    || !positiveDecimal(candidate.usualUnitPrice()) || !positiveMoney(candidate.estimatedCost())) {
                throw invalidShoppingResponse();
            }
            BigDecimal usual = new BigDecimal(candidate.usualUnitPrice()).setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal estimate = new BigDecimal(candidate.estimatedCost()).setScale(2, RoundingMode.UNNECESSARY);
            if (usual.compareTo(estimate) != 0) throw invalidShoppingResponse();
            total = total.add(estimate);
        }
        if (total.compareTo(new BigDecimal(result.estimatedListCost()).setScale(2, RoundingMode.UNNECESSARY)) != 0) {
            throw invalidShoppingResponse();
        }
    }

    private static boolean positiveMoney(String value) {
        if (!nonNegativeMoney(value)) return false;
        return new BigDecimal(value).signum() > 0;
    }

    private static boolean nonNegativeMoney(String value) {
        if (value == null) return false;
        try {
            BigDecimal amount = new BigDecimal(value);
            return amount.signum() >= 0 && Math.max(0, amount.scale()) <= 2;
        } catch (NumberFormatException invalid) { return false; }
    }

    private static ResponseStatusException invalidShoppingResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Shopping candidates response is incomplete");
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
