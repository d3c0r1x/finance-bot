package com.decorix.finance.core.api;

import com.decorix.finance.core.domain.BankImportPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Authenticated bounded client for the private deterministic T-Bank PDF parser. */
@Component
public final class TBankParserClient {
    private static final int MAX_PDF_BYTES = 12 * 1024 * 1024;
    private static final int MAX_OPERATIONS = 10_000;
    private static final Pattern MONEY = Pattern.compile("^-?(?:0|[1-9][0-9]{0,17})\\.[0-9]{2}$");
    private static final Set<String> RESULT_FIELDS = Set.of("operations", "parsedExpenseTotal", "parsedIncomeTotal",
            "expectedExpenseTotal", "expectedIncomeTotal", "quality", "periodStart", "periodEnd", "parseVersion");
    private static final Set<String> OPERATION_FIELDS = Set.of("operationDate", "operationTime", "signedAmount",
            "currency", "kind", "merchant", "description", "cardLast4");
    private static final Set<String> KINDS = Set.of("purchase", "refund", "income", "transfer_out", "internal",
            "withdrawal", "fee");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String serviceToken;
    private final Duration requestTimeout;

    public TBankParserClient(ObjectMapper json,
            @Value("${finance.imports.parser.url:}") String serviceUrl,
            @Value("${finance.imports.parser.service-token:}") String serviceToken,
            @Value("${finance.imports.parser.timeout:PT30S}") Duration requestTimeout) {
        this.json = json;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken;
        this.requestTimeout = requestTimeout;
    }

    public ParsedStatement parse(byte[] pdf) {
        if (pdf == null || pdf.length < 5 || pdf.length > MAX_PDF_BYTES
                || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F' || pdf[4] != '-') {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid PDF file no larger than 12 MiB");
        }
        if (serviceUrl.isBlank() || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Statement import parser is not configured");
        }
        try {
            String body = json.writeValueAsString(Map.of("pdfBase64", Base64.getEncoder().encodeToString(pdf)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(serviceUrl.replaceAll("/+$", "")
                            + "/internal/v1/imports/tbank"))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 422) {
                throw parseFailure(response.body());
            }
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Statement import parser is unavailable");
            }
            return parseResult(response.body());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Statement parsing was interrupted", exception);
        } catch (IOException | IllegalArgumentException | JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Statement parser returned an invalid result", exception);
        }
    }

    private ParsedStatement parseResult(String raw) throws JacksonException {
        Object decoded = json.readValue(raw, Object.class);
        if (!(decoded instanceof Map<?, ?> result) || !RESULT_FIELDS.equals(result.keySet())
                || !"tbank-pdf.v1".equals(result.get("parseVersion"))) {
            throw invalidResult();
        }
        String quality = requiredString(result.get("quality"), Set.of("valid", "mismatch", "unverifiable"));
        String periodStart = date(result.get("periodStart"));
        String periodEnd = date(result.get("periodEnd"));
        if (LocalDate.parse(periodEnd).isBefore(LocalDate.parse(periodStart))) {
            throw invalidResult();
        }
        String parsedExpense = money(result.get("parsedExpenseTotal"), false);
        String parsedIncome = money(result.get("parsedIncomeTotal"), false);
        String expectedExpense = nullableMoney(result.get("expectedExpenseTotal"));
        String expectedIncome = nullableMoney(result.get("expectedIncomeTotal"));
        if (!(result.get("operations") instanceof List<?> rawOperations) || rawOperations.isEmpty()
                || rawOperations.size() > MAX_OPERATIONS) {
            throw invalidResult();
        }
        List<BankImportPolicy.OperationInput> operations = new ArrayList<>(rawOperations.size());
        for (int ordinal = 0; ordinal < rawOperations.size(); ordinal++) {
            Object rawOperation = rawOperations.get(ordinal);
            if (!(rawOperation instanceof Map<?, ?> operation) || !OPERATION_FIELDS.equals(operation.keySet())) {
                throw invalidResult();
            }
            String operationDate = date(operation.get("operationDate"));
            String operationTime = time(operation.get("operationTime"));
            String signedAmount = money(operation.get("signedAmount"), true);
            String currency = requiredString(operation.get("currency"), Set.of("RUB"));
            String kind = requiredString(operation.get("kind"), KINDS);
            String merchant = nullableBounded(operation.get("merchant"), 200, false);
            String description = nullableBounded(operation.get("description"), 2048, true);
            String cardLast4 = nullableBounded(operation.get("cardLast4"), 4, false);
            if (cardLast4 != null && !cardLast4.matches("[0-9]{4}")) {
                throw invalidResult();
            }
            operations.add(new BankImportPolicy.OperationInput(operationDate, operationTime, signedAmount, currency,
                    kind, merchant, description, cardLast4, ordinal));
        }
        BankImportPolicy.StatementInput statement = new BankImportPolicy.StatementInput(quality, parsedExpense,
                parsedIncome, expectedExpense, expectedIncome, operations);
        BankImportPolicy.preview(statement, Map.of());
        return new ParsedStatement(statement, periodStart, periodEnd, "tbank-pdf.v1");
    }

    private ResponseStatusException parseFailure(String body) {
        try {
            Object decoded = json.readValue(body, Object.class);
            if (decoded instanceof Map<?, ?> error && error.get("message") instanceof String message
                    && message.length() <= 256 && error.get("error") instanceof String parserCode
                    && Set.of("invalid_pdf", "invalid_format", "no_text", "no_operations", "invalid_totals",
                    "invalid_operation").contains(parserCode)) {
                return new BankImportParseException(parserCode, message);
            }
        } catch (JacksonException ignored) {
            // Do not expose malformed internal responses to the user.
        }
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "This PDF cannot be imported");
    }

    private static String date(Object value) {
        String parsed = requiredString(value, null);
        try {
            LocalDate.parse(parsed);
            return parsed;
        } catch (RuntimeException exception) {
            throw invalidResult();
        }
    }

    private static String time(Object value) {
        String parsed = requiredString(value, null);
        try {
            LocalTime.parse(parsed);
            return parsed;
        } catch (RuntimeException exception) {
            throw invalidResult();
        }
    }

    private static String money(Object value, boolean signed) {
        String parsed = requiredString(value, null);
        if (!MONEY.matcher(parsed).matches() || (!signed && parsed.startsWith("-"))
                || new BigDecimal(parsed).abs().compareTo(new BigDecimal("999999999999999999.99")) > 0) {
            throw invalidResult();
        }
        return parsed;
    }

    private static String nullableMoney(Object value) {
        return value == null ? null : money(value, false);
    }

    private static String nullableBounded(Object value, int maxLength, boolean required) {
        if (value == null && !required) {
            return null;
        }
        if (!(value instanceof String parsed) || parsed.isBlank() || parsed.length() > maxLength) {
            throw invalidResult();
        }
        return parsed;
    }

    private static String requiredString(Object value, Set<String> allowed) {
        if (!(value instanceof String parsed) || parsed.isBlank() || allowed != null && !allowed.contains(parsed)) {
            throw invalidResult();
        }
        return parsed;
    }

    private static ResponseStatusException invalidResult() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Statement parser returned an invalid result");
    }

    public record ParsedStatement(BankImportPolicy.StatementInput statement, String periodStart, String periodEnd,
                                  String parseVersion) {}
}
