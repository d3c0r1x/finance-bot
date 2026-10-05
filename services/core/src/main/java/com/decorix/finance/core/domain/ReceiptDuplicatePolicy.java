package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Detect likely duplicate receipt uploads without deciding whether they are duplicates. */
public final class ReceiptDuplicatePolicy {
    public static final String ALGORITHM_VERSION = "receipt-duplicate.v1";
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private ReceiptDuplicatePolicy() {}

    public static List<Candidate> candidates(UUID currentId, Instant currentCreatedAt, String currentAmount,
                                             List<Candidate> candidates) {
        if (currentId == null || currentCreatedAt == null || currentAmount == null || candidates == null) {
            throw new IllegalArgumentException("receipt duplicate context is invalid");
        }
        BigDecimal amount = new BigDecimal(currentAmount);
        Instant earliest = currentCreatedAt.minus(WINDOW);
        return candidates.stream()
                .filter(candidate -> candidate != null && candidate.id() != null && candidate.createdAt() != null
                        && candidate.cashTotal() != null && !candidate.id().equals(currentId)
                        && candidate.createdAt().isBefore(currentCreatedAt)
                        && candidate.createdAt().isAfter(earliest)
                        && new BigDecimal(candidate.cashTotal()).subtract(amount).abs().compareTo(TOLERANCE) < 0)
                .sorted(Comparator.comparing(Candidate::createdAt).reversed().thenComparing(Candidate::id))
                .toList();
    }

    public record Candidate(UUID id, Instant createdAt, String cashTotal, String merchant) {}
}
