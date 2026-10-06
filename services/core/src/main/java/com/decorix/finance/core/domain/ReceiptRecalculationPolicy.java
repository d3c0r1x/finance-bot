package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Builds an explicit preview of old receipt verdicts covered by current deterministic rules. */
public final class ReceiptRecalculationPolicy {
    private static final Set<String> RECALCULABLE_SOURCES = Set.of("", "model", "default");

    private ReceiptRecalculationPolicy() {}

    public static Plan preview(List<Line> lines) {
        if (lines == null || lines.size() > 50_000) {
            throw new IllegalArgumentException("receipt recalculation input is invalid");
        }

        List<Update> updates = new ArrayList<>();
        for (Line line : lines) {
            if (line == null || line.itemId() == null || line.name() == null || line.name().isBlank()
                    || (line.lineSum() != null && line.lineSum().signum() < 0)) {
                throw new IllegalArgumentException("receipt recalculation line is invalid");
            }
            String source = line.verdictSource() == null ? "" : line.verdictSource().trim().toLowerCase(Locale.ROOT);
            if (!RECALCULABLE_SOURCES.contains(source)) {
                continue;
            }

            ReceiptBasketPolicy.Proposal proposal = new ReceiptBasketPolicy.Proposal(
                    line.itemId(), line.name(), line.verdict(), line.reason(), line.action());
            ReceiptBasketPolicy.Review review = ReceiptBasketPolicy.apply(List.of(proposal)).get(0);
            if (!"rule".equals(review.source())) {
                continue;
            }
            updates.add(new Update(line.itemId(), line.name(), line.lineSum(), line.verdict(), line.reason(),
                    line.action(), line.verdictSource(), review.verdict(), review.reason(),
                    review.action(), review.source(), !same(line.verdict(), review.verdict())
                    || !same(line.reason(), review.reason()) || !same(line.action(), review.action())));
        }
        return new Plan(lines.size(), List.copyOf(updates));
    }

    private static boolean same(String left, String right) {
        return (left == null ? "" : left).equals(right == null ? "" : right);
    }

    public record Line(UUID itemId, String name, BigDecimal lineSum, String verdict, String reason, String action,
                       String verdictSource) {}

    public record Update(UUID itemId, String name, BigDecimal lineSum, String beforeVerdict, String beforeReason,
                         String beforeAction, String beforeSource, String afterVerdict, String afterReason,
                         String afterAction, String afterSource, boolean changed) {}

    public record Plan(int checked, List<Update> updates) {}
}
