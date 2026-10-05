package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic onboarding proposal; callers must present it for explicit user approval. */
public final class BudgetProposalPolicy {
    private static final BigDecimal SPENDING_SHARE = new BigDecimal("0.70");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private BudgetProposalPolicy() {}

    public static Proposal propose(String income, Map<String, String> currentLimits) {
        BigDecimal monthlyIncome;
        try {
            monthlyIncome = new BigDecimal(income);
        } catch (NumberFormatException | NullPointerException ex) {
            throw new IllegalArgumentException("Income must be a positive decimal", ex);
        }
        if (monthlyIncome.signum() <= 0 || monthlyIncome.scale() > 2) {
            throw new IllegalArgumentException("Income must be a positive decimal with at most two fractional digits");
        }
        BigDecimal total = monthlyIncome.multiply(SPENDING_SHARE).setScale(-3, RoundingMode.HALF_EVEN)
                .setScale(2, RoundingMode.UNNECESSARY);
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (var entry : currentLimits.entrySet()) {
            if ("долги".equals(entry.getKey())) continue;
            BigDecimal weight;
            try {
                weight = new BigDecimal(entry.getValue());
            } catch (NumberFormatException | NullPointerException ex) {
                throw new IllegalArgumentException("Budget weights must be non-negative decimals", ex);
            }
            if (weight.signum() < 0) throw new IllegalArgumentException("Budget weights must be non-negative");
            weights.put(entry.getKey(), weight);
        }
        BigDecimal weightTotal = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, String> limits = new LinkedHashMap<>();
        if (weightTotal.signum() > 0) {
            for (var entry : weights.entrySet()) {
                BigDecimal share = total.multiply(entry.getValue()).divide(weightTotal, 12, RoundingMode.HALF_EVEN)
                        .setScale(-2, RoundingMode.HALF_EVEN).setScale(2, RoundingMode.UNNECESSARY);
                limits.put(entry.getKey(), share.toPlainString());
            }
        } else {
            weights.keySet().forEach(category -> limits.put(category, ZERO.toPlainString()));
        }
        limits.put("долги", ZERO.toPlainString());
        return new Proposal(Map.copyOf(limits), total.toPlainString());
    }

    public static Proposal proposeFromHistoryShares(String income, Map<String, String> currentLimits,
                                                     Map<String, String> proposedShares) {
        BigDecimal monthlyIncome;
        try {
            monthlyIncome = new BigDecimal(income);
        } catch (NumberFormatException | NullPointerException ex) {
            throw new IllegalArgumentException("Income must be a positive decimal", ex);
        }
        if (monthlyIncome.signum() <= 0 || monthlyIncome.scale() > 2 || currentLimits == null || proposedShares == null) {
            throw new IllegalArgumentException("Income and history shares are required");
        }
        Set<String> categories = new TreeSet<>(currentLimits.keySet());
        categories.remove("долги");
        if (!proposedShares.keySet().equals(categories)) {
            throw new IllegalArgumentException("AI shares must contain exactly the current non-debt categories");
        }
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        BigDecimal shareTotal = BigDecimal.ZERO;
        for (String category : categories) {
            BigDecimal share;
            try {
                share = new BigDecimal(proposedShares.get(category));
            } catch (NumberFormatException | NullPointerException ex) {
                throw new IllegalArgumentException("AI shares must be decimals", ex);
            }
            if (share.signum() < 0 || share.compareTo(new BigDecimal("100.00")) > 0 || share.scale() > 2) {
                throw new IllegalArgumentException("AI share is outside the allowed range");
            }
            shares.put(category, share);
            shareTotal = shareTotal.add(share);
        }
        if (shareTotal.compareTo(new BigDecimal("100.00")) != 0) {
            throw new IllegalArgumentException("AI shares must sum to 100.00");
        }

        BigDecimal total = monthlyIncome.multiply(SPENDING_SHARE).setScale(-3, RoundingMode.HALF_EVEN)
                .setScale(2, RoundingMode.UNNECESSARY);
        Map<String, BigDecimal> allocations = new LinkedHashMap<>();
        BigDecimal allocated = BigDecimal.ZERO;
        String largestCategory = null;
        BigDecimal largestShare = BigDecimal.valueOf(-1);
        for (var entry : shares.entrySet()) {
            BigDecimal amount = total.multiply(entry.getValue()).divide(new BigDecimal("100.00"), 2, RoundingMode.HALF_EVEN)
                    .setScale(-2, RoundingMode.HALF_EVEN).setScale(2, RoundingMode.UNNECESSARY);
            allocations.put(entry.getKey(), amount);
            allocated = allocated.add(amount);
            if (entry.getValue().compareTo(largestShare) > 0) {
                largestCategory = entry.getKey();
                largestShare = entry.getValue();
            }
        }
        if (largestCategory != null) {
            allocations.put(largestCategory, allocations.get(largestCategory).add(total.subtract(allocated)));
        }
        Map<String, String> limits = new LinkedHashMap<>();
        allocations.forEach((category, amount) -> limits.put(category, amount.toPlainString()));
        limits.put("долги", ZERO.toPlainString());
        return new Proposal(Map.copyOf(limits), total.toPlainString());
    }

    public record Proposal(Map<String, String> limits, String totalLimit) {}
}
