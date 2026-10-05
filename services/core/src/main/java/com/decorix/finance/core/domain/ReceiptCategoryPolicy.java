package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic safeguards for model-suggested grocery and leisure receipt categories. */
public final class ReceiptCategoryPolicy {
    public static final String ALGORITHM_VERSION = "receipt-category.v1";
    private static final BigDecimal ALCOHOL_SHARE_LIMIT = new BigDecimal("0.10");
    private static final BigDecimal LEISURE_SHARE_LIMIT = new BigDecimal("0.25");
    private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999999999.99");
    private static final Set<String> CATEGORIES = Set.of(
            "еда", "транспорт", "жилье", "досуг", "одежда", "здоровье", "работа", "техника", "долги", "прочее");
    private static final List<String> ALCOHOL_MARKERS = List.of(
            "пив", "жигул", "балтик", "водк", "вин", "шампан", "коньяк", "виск", "ром", "джин",
            "сидр", "ликёр", "ликер", "алкогол");
    private static final List<String> LEISURE_MARKERS = List.of(
            "чипс", "lays", "лейс", "сухарик", "кириешк", "снек", "попкорн", "энергетик",
            "энергет", "игрушк", "игра", "сувенир");

    private ReceiptCategoryPolicy() {}

    public static Result classify(String suggestedCategory, BigDecimal cashTotal, List<Item> items) {
        validateCategory(suggestedCategory);
        if (cashTotal != null && (cashTotal.signum() <= 0 || cashTotal.scale() > 2
                || cashTotal.compareTo(MAX_MONEY) > 0)) {
            throw new IllegalArgumentException("cash total must be a positive RUB amount");
        }
        List<Item> safeItems = items == null ? List.of() : items;
        BigDecimal alcoholTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal leisureTotal = BigDecimal.ZERO.setScale(2);
        boolean leisure = false;
        boolean missingClassifiedAmount = false;
        for (Item item : safeItems) {
            if (item == null) throw new IllegalArgumentException("receipt item is required");
            String name = normalize(item.name());
            boolean alcohol = containsAny(name, ALCOHOL_MARKERS);
            boolean leisureItem = alcohol || containsAny(name, LEISURE_MARKERS);
            if (!leisureItem) continue;
            leisure = true;
            if (item.lineSum() == null) {
                missingClassifiedAmount = true;
                continue;
            }
            validateAmount(item.lineSum());
            leisureTotal = leisureTotal.add(item.lineSum());
            if (alcohol) alcoholTotal = alcoholTotal.add(item.lineSum());
        }

        BigDecimal alcoholShare = null;
        BigDecimal leisureShare = null;
        boolean ruleCategory = false;
        if (cashTotal != null && !missingClassifiedAmount) {
            alcoholShare = alcoholTotal.divide(cashTotal, 4, RoundingMode.HALF_UP);
            leisureShare = leisureTotal.divide(cashTotal, 4, RoundingMode.HALF_UP);
            ruleCategory = alcoholTotal.compareTo(cashTotal.multiply(ALCOHOL_SHARE_LIMIT)) >= 0
                    || leisureTotal.compareTo(cashTotal.multiply(LEISURE_SHARE_LIMIT)) >= 0;
        }

        return new Result(ALGORITHM_VERSION, ruleCategory ? "досуг" : suggestedCategory, leisure,
                alcoholShare, leisureShare, ruleCategory ? "rule" : suggestedCategory == null ? "unknown" : "model");
    }

    public static Result applyManualOverride(Result inferred, String selectedCategory) {
        if (inferred == null) throw new IllegalArgumentException("receipt category result is required");
        validateCategory(selectedCategory);
        return new Result(inferred.algorithmVersion(), selectedCategory, inferred.leisure(), inferred.alcoholShare(),
                inferred.leisureShare(), "human");
    }

    private static void validateCategory(String category) {
        if (category != null && !CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("receipt category is not allowed");
        }
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount.signum() < 0 || amount.scale() > 2 || amount.compareTo(MAX_MONEY) > 0) {
            throw new IllegalArgumentException("receipt item sum must be a non-negative RUB amount");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, List<String> markers) {
        return markers.stream().anyMatch(value::contains);
    }

    public record Item(String name, BigDecimal lineSum) {}

    public record Result(String algorithmVersion, String category, boolean leisure, BigDecimal alcoholShare,
                         BigDecimal leisureShare, String categorySource) {}
}
