package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic preview policy for statement rows before an explicit user confirmation. */
public final class BankImportPolicy {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999999.99");
    private static final Pattern DECIMAL = Pattern.compile("^-?(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,2})?$");
    private static final Set<String> QUALITIES = Set.of("valid", "mismatch", "unverifiable");
    private static final Set<String> TARGET_TYPES = Set.of("expense", "income", "refund", "transfer");

    private BankImportPolicy() {}

    public static Preview preview(StatementInput statement, Map<Integer, String> overrides) {
        if (statement == null || overrides == null) {
            throw new IllegalArgumentException("statement and overrides are required");
        }
        if (!QUALITIES.contains(statement.quality())) {
            throw new IllegalArgumentException("unsupported statement quality");
        }
        String expectedExpense = optionalAmount(statement.expectedExpenseTotal());
        String expectedIncome = optionalAmount(statement.expectedIncomeTotal());
        amount(statement.parsedExpenseTotal(), false);
        amount(statement.parsedIncomeTotal(), false);

        List<OperationInput> operations = statement.operations() == null ? List.of() : statement.operations();
        Set<Integer> ordinals = new HashSet<>();
        for (OperationInput operation : operations) {
            validateOperation(operation);
            if (!ordinals.add(operation.ordinal())) {
                throw new IllegalArgumentException("operation ordinals must be unique");
            }
        }
        for (Map.Entry<Integer, String> override : overrides.entrySet()) {
            if (!ordinals.contains(override.getKey())) {
                throw new IllegalArgumentException("selection references a missing operation");
            }
            if (!TARGET_TYPES.contains(override.getValue())) {
                throw new IllegalArgumentException("unsupported transaction selection");
            }
        }

        BigDecimal expense = zero();
        BigDecimal income = zero();
        BigDecimal refund = zero();
        BigDecimal transfer = zero();
        BigDecimal excluded = zero();
        int includedCount = 0;
        int excludedCount = 0;
        List<PreviewRow> rows = new ArrayList<>(operations.size());

        for (OperationInput operation : operations) {
            BigDecimal signed = amount(operation.signedAmount(), true);
            String selectedType = overrides.get(operation.ordinal());
            boolean userSelected = selectedType != null;
            String targetType = userSelected ? selectedType : defaultType(operation.kind(), signed);
            String reason = null;
            boolean included = targetType != null;
            if (!userSelected && !included) {
                reason = exclusionReason(operation.kind());
            }
            if (userSelected) {
                validateSign(targetType, signed);
            }
            BigDecimal magnitude = signed.abs().setScale(2, RoundingMode.UNNECESSARY);
            if (included) {
                includedCount++;
                switch (targetType) {
                    case "expense" -> expense = expense.add(magnitude);
                    case "income" -> income = income.add(magnitude);
                    case "refund" -> refund = refund.add(magnitude);
                    case "transfer" -> transfer = transfer.add(magnitude);
                    default -> throw new IllegalArgumentException("unsupported transaction selection");
                }
            } else {
                excludedCount++;
                excluded = excluded.add(magnitude);
            }
            rows.add(new PreviewRow(operation.ordinal(), operation.kind(), signed.toPlainString(),
                    magnitude.toPlainString(), targetType, included, userSelected ? "user" : "default", reason,
                    operation.operationDate(), operation.operationTime(), operation.currency(), operation.merchant(),
                    operation.description(), operation.cardLast4()));
        }

        return new Preview(statement.quality(), expectedExpense, expectedIncome, expense.toPlainString(),
                income.toPlainString(), refund.toPlainString(), transfer.toPlainString(), excluded.toPlainString(),
                includedCount, excludedCount, List.copyOf(rows));
    }

    private static String defaultType(String kind, BigDecimal amount) {
        return switch (kind) {
            case "purchase" -> {
                if (amount.signum() >= 0) {
                    throw new IllegalArgumentException("purchase must have a negative amount");
                }
                yield "expense";
            }
            case "income" -> {
                if (amount.signum() <= 0) {
                    throw new IllegalArgumentException("income must have a positive amount");
                }
                yield "income";
            }
            case "refund" -> {
                if (amount.signum() <= 0) {
                    throw new IllegalArgumentException("refund must have a positive amount");
                }
                yield "refund";
            }
            default -> null;
        };
    }

    private static String exclusionReason(String kind) {
        return switch (kind) {
            case "fee" -> "bank_fee";
            case "transfer_out", "transfer_in" -> "external_transfer";
            case "withdrawal" -> "cash_withdrawal";
            case "internal" -> "internal_transfer";
            default -> "requires_review";
        };
    }

    private static void validateOperation(OperationInput operation) {
        if (operation == null || operation.ordinal() < 0 || operation.kind() == null
                || operation.currency() == null || !"RUB".equals(operation.currency())) {
            throw new IllegalArgumentException("operation fields are invalid");
        }
        if (!Set.of("purchase", "income", "refund", "fee", "transfer_out", "transfer_in", "withdrawal",
                "internal").contains(operation.kind())) {
            throw new IllegalArgumentException("unsupported operation kind");
        }
        amount(operation.signedAmount(), true);
        if (operation.operationDate() != null) {
            LocalDate.parse(operation.operationDate());
        }
        if (operation.operationTime() != null) {
            LocalTime.parse(operation.operationTime());
        }
    }

    private static void validateSign(String type, BigDecimal amount) {
        if (amount.signum() == 0
                || ("expense".equals(type) && amount.signum() >= 0)
                || (("income".equals(type) || "refund".equals(type)) && amount.signum() <= 0)) {
            throw new IllegalArgumentException("selection does not match operation sign");
        }
    }

    private static String optionalAmount(String input) {
        return input == null ? null : amount(input, false).toPlainString();
    }

    private static BigDecimal amount(String input, boolean nonZero) {
        if (input == null || !DECIMAL.matcher(input).matches()) {
            throw new IllegalArgumentException("amount must be a canonical decimal with at most two places");
        }
        BigDecimal parsed = new BigDecimal(input);
        if (parsed.abs().compareTo(MAX_AMOUNT) > 0 || (nonZero && parsed.signum() == 0)) {
            throw new IllegalArgumentException("amount is outside the supported range");
        }
        return parsed.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    public record OperationInput(String operationDate, String operationTime, String signedAmount, String currency,
                                 String kind, String merchant, String description, String cardLast4, int ordinal) {}

    public record StatementInput(String quality, String parsedExpenseTotal, String parsedIncomeTotal,
                                 String expectedExpenseTotal, String expectedIncomeTotal,
                                 List<OperationInput> operations) {}

    public record Preview(String quality, String expectedExpenseTotal, String expectedIncomeTotal, String expenseTotal,
                          String incomeTotal, String refundTotal, String transferTotal, String excludedTotal,
                          int includedCount, int excludedCount, List<PreviewRow> rows) {}

    public record PreviewRow(int ordinal, String kind, String signedAmount, String amount, String transactionType,
                             boolean included, String selectionSource, String exclusionReason, String operationDate,
                             String operationTime, String currency, String merchant, String description,
                             String cardLast4) {}
}
