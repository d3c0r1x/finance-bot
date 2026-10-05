package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Stable merchant normalization, mapping precedence, and bounded clarification policy. */
public final class MerchantCategoryPolicy {
    public static final String ALGORITHM_VERSION = "merchant-category.v1";
    public static final BigDecimal CLARIFICATION_THRESHOLD = new BigDecimal("300.00");
    public static final int MAX_CLARIFICATIONS = 6;
    public static final int MAX_BATCH_SIZE = 50;
    public static final Set<String> CATEGORIES = Set.of(
            "еда", "транспорт", "жилье", "досуг", "одежда", "здоровье", "работа", "техника", "долги", "прочее");

    private MerchantCategoryPolicy() {}

    public static String normalizeMerchant(String merchant) {
        if (merchant == null) throw new IllegalArgumentException("merchant is required");
        String normalized = Normalizer.normalize(merchant, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 200 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("merchant must contain 1 to 200 printable characters");
        }
        return normalized;
    }

    public static String resolveCategory(String merchant, Map<String, String> humanMappings,
                                         Map<String, String> modelSuggestions) {
        String key = normalizeMerchant(merchant);
        String human = lookup(humanMappings, key);
        if (human != null) return category(human);
        String model = lookup(modelSuggestions, key);
        return model == null ? "прочее" : category(model);
    }

    public static List<ClarificationCandidate> clarificationCandidates(List<MerchantSpend> spends,
                                                                        Map<String, String> humanMappings) {
        if (spends == null || humanMappings == null) throw new IllegalArgumentException("spends and mappings are required");
        Map<String, BigDecimal> totals = new HashMap<>();
        for (MerchantSpend spend : spends) {
            if (spend == null || spend.amount() == null || spend.amount().signum() < 0
                    || spend.amount().scale() > 2) throw new IllegalArgumentException("merchant spend is invalid");
            if (spend.merchant() == null || spend.merchant().isBlank()) continue;
            String key = normalizeMerchant(spend.merchant());
            if (lookup(humanMappings, key) != null) continue;
            totals.merge(key, spend.amount(), BigDecimal::add);
        }
        List<ClarificationCandidate> candidates = new ArrayList<>();
        totals.forEach((merchant, amount) -> {
            if (amount.compareTo(CLARIFICATION_THRESHOLD) >= 0) {
                candidates.add(new ClarificationCandidate(merchant, amount.setScale(2)));
            }
        });
        return candidates.stream().sorted((left, right) -> {
            int amountOrder = right.amount().compareTo(left.amount());
            return amountOrder == 0 ? left.normalizedMerchant().compareTo(right.normalizedMerchant()) : amountOrder;
        }).limit(MAX_CLARIFICATIONS).toList();
    }

    private static String lookup(Map<String, String> mappings, String normalizedMerchant) {
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (entry.getKey() != null && normalizeMerchant(entry.getKey()).equals(normalizedMerchant)) return entry.getValue();
        }
        return null;
    }

    private static String category(String value) {
        if (value == null || !CATEGORIES.contains(value)) throw new IllegalArgumentException("merchant category is invalid");
        return value;
    }

    public record MerchantSpend(String merchant, BigDecimal amount) {}
    public record ClarificationCandidate(String normalizedMerchant, BigDecimal amount) {}
}
