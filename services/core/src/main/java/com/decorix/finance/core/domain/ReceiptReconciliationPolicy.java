package com.decorix.finance.core.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic receipt evidence comparison; a decision never confirms or books a receipt. */
public final class ReceiptReconciliationPolicy {
    public static final String ALGORITHM_VERSION = "receipt-reconciliation.v1";
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MINIMUM_TOLERANCE = new BigDecimal("2.00");
    private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("0.03");
    private static final BigDecimal MAXIMUM_MONEY = new BigDecimal("999999999999999999.99");
    private static final BigDecimal TOP_UP_TOLERANCE = new BigDecimal("0.02");
    private static final int MAX_TOP_UP_LINES = 3;
    private static final int MAX_TOP_UP_CANDIDATES = 30;

    private ReceiptReconciliationPolicy() {}

    public static boolean totalsReconcile(BigDecimal cashTotal, BigDecimal itemsTotal) {
        if (!isValidMoney(cashTotal) || itemsTotal == null || itemsTotal.signum() < 0
                || itemsTotal.scale() > 2 || itemsTotal.compareTo(MAXIMUM_MONEY) > 0) {
            return false;
        }
        return cashTotal.subtract(itemsTotal).abs().compareTo(tolerance(cashTotal)) <= 0;
    }

    public static Result evaluate(Reading ocr, Reading vision) {
        if (ocr != null && ocr.reader() != Reader.OCR || vision != null && vision.reader() != Reader.VISION) {
            throw new IllegalArgumentException("Receipt reading is supplied in the wrong reader slot");
        }
        if (ocr == null && vision == null) {
            return result(Decision.INSUFFICIENT_DATA, null, List.of(), null, null, null, false, false, List.of());
        }

        Evidence ocrEvidence = evidence(ocr);
        Evidence visionEvidence = evidence(vision);
        List<String> mismatches = mismatches(ocr, vision);
        List<ItemEvidence> itemEvidence = itemEvidence(ocr, vision);
        BigDecimal tolerance = largerTolerance(ocr == null ? null : ocr.total(),
                vision == null ? null : vision.total());
        List<TopUpSuggestion> topUps = mismatches.isEmpty()
                ? suggestedTopUps(ocr, vision, ocrEvidence, visionEvidence, itemEvidence, tolerance)
                : List.of();

        if (ocr == null || vision == null) {
            Evidence only = ocr == null ? visionEvidence : ocrEvidence;
            if (!only.hasEnoughData()) {
                return result(Decision.INSUFFICIENT_DATA, null, mismatches, ocrEvidence.itemsTotal(),
                        visionEvidence.itemsTotal(), tolerance, ocrEvidence.reconciled(), visionEvidence.reconciled(),
                        itemEvidence);
            }
            if (only.reconciled()) {
                return result(Decision.AUTO_SELECTED, ocr == null ? Reader.VISION : Reader.OCR, mismatches,
                        ocrEvidence.itemsTotal(), visionEvidence.itemsTotal(), tolerance,
                        ocrEvidence.reconciled(), visionEvidence.reconciled(), itemEvidence);
            }
            return result(Decision.REVIEW_REQUIRED, null, mismatches, ocrEvidence.itemsTotal(),
                    visionEvidence.itemsTotal(), tolerance, ocrEvidence.reconciled(), visionEvidence.reconciled(),
                    itemEvidence);
        }

        if (!mismatches.isEmpty()) {
            return result(Decision.REVIEW_REQUIRED, null, mismatches, ocrEvidence.itemsTotal(),
                    visionEvidence.itemsTotal(), tolerance, ocrEvidence.reconciled(), visionEvidence.reconciled(),
                    itemEvidence);
        }
        if (ocrEvidence.reconciled() && visionEvidence.reconciled()) {
            Reader selected = vision.items().size() >= ocr.items().size() ? Reader.VISION : Reader.OCR;
            return result(Decision.AUTO_SELECTED, selected, mismatches, ocrEvidence.itemsTotal(),
                    visionEvidence.itemsTotal(), tolerance, true, true, itemEvidence);
        }
        if (ocrEvidence.reconciled()) {
            return result(Decision.AUTO_SELECTED, Reader.OCR, mismatches, ocrEvidence.itemsTotal(),
                    visionEvidence.itemsTotal(), tolerance, true, false, itemEvidence);
        }
        if (visionEvidence.reconciled()) {
            return result(Decision.AUTO_SELECTED, Reader.VISION, mismatches, ocrEvidence.itemsTotal(),
                    visionEvidence.itemsTotal(), tolerance, false, true, itemEvidence);
        }
        if (!ocrEvidence.hasEnoughData() && !visionEvidence.hasEnoughData()) {
            return result(Decision.INSUFFICIENT_DATA, null, mismatches, ocrEvidence.itemsTotal(),
                    visionEvidence.itemsTotal(), tolerance, false, false, itemEvidence);
        }
        return result(Decision.REVIEW_REQUIRED, null, mismatches, ocrEvidence.itemsTotal(),
                visionEvidence.itemsTotal(), tolerance, false, false, itemEvidence, topUps);
    }

    private static Result result(Decision decision, Reader selected, List<String> mismatches,
                                 BigDecimal ocrItems, BigDecimal visionItems, BigDecimal tolerance,
                                 boolean ocrReconciled, boolean visionReconciled, List<ItemEvidence> itemEvidence) {
        return new Result(ALGORITHM_VERSION, decision, selected, mismatches, ocrItems, visionItems, tolerance,
                ocrReconciled, visionReconciled, itemEvidence, List.of());
    }

    private static Result result(Decision decision, Reader selected, List<String> mismatches,
                                 BigDecimal ocrItems, BigDecimal visionItems, BigDecimal tolerance,
                                 boolean ocrReconciled, boolean visionReconciled, List<ItemEvidence> itemEvidence,
                                 List<TopUpSuggestion> suggestedTopUps) {
        return new Result(ALGORITHM_VERSION, decision, selected, mismatches, ocrItems, visionItems, tolerance,
                ocrReconciled, visionReconciled, itemEvidence, suggestedTopUps);
    }

    private static List<TopUpSuggestion> suggestedTopUps(Reading ocr, Reading vision,
                                                          Evidence ocrEvidence, Evidence visionEvidence,
                                                          List<ItemEvidence> itemEvidence,
                                                          BigDecimal tolerance) {
        if (ocr == null || vision == null || ocrEvidence.reconciled() || tolerance == null
                || !isValidMoney(ocr.total()) || !isValidMoney(vision.total())
                || ocr.total().subtract(vision.total()).abs().compareTo(tolerance) > 0
                || !isValidMoney(visionEvidence.itemsTotal())) {
            return List.of();
        }
        BigDecimal gap = vision.total().subtract(visionEvidence.itemsTotal());
        if (gap.compareTo(tolerance) <= 0 || ocr.items().size() > MAX_TOP_UP_CANDIDATES) return List.of();

        Set<Integer> usedOrdinals = new HashSet<>();
        for (ItemEvidence evidence : itemEvidence) {
            if (evidence.ocrOrdinal() != null) usedOrdinals.add(evidence.ocrOrdinal());
        }
        List<TopUpSuggestion> candidates = new ArrayList<>();
        for (int index = 0; index < ocr.items().size(); index++) {
            Item item = ocr.items().get(index);
            if (!usedOrdinals.contains(index + 1) && item != null && item.name() != null
                    && !item.name().isBlank() && isValidMoney(item.lineSum())) {
                candidates.add(new TopUpSuggestion(index + 1, item.name(), item.lineSum()));
            }
        }
        if (candidates.isEmpty()) return List.of();

        List<List<TopUpSuggestion>> matches = new ArrayList<>(2);
        findTopUpMatches(candidates, 0, gap, new ArrayList<>(), matches);
        return matches.size() == 1 ? List.copyOf(matches.get(0)) : List.of();
    }

    private static void findTopUpMatches(List<TopUpSuggestion> candidates, int start, BigDecimal gap,
                                         List<TopUpSuggestion> selected,
                                         List<List<TopUpSuggestion>> matches) {
        if (matches.size() > 1) return;
        if (!selected.isEmpty()) {
            BigDecimal sum = selected.stream().map(TopUpSuggestion::lineSum)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.subtract(gap).abs().compareTo(TOP_UP_TOLERANCE) <= 0) {
                matches.add(List.copyOf(selected));
            }
        }
        if (selected.size() == MAX_TOP_UP_LINES) return;
        for (int index = start; index < candidates.size(); index++) {
            selected.add(candidates.get(index));
            findTopUpMatches(candidates, index + 1, gap, selected, matches);
            selected.remove(selected.size() - 1);
            if (matches.size() > 1) return;
        }
    }

    private static Evidence evidence(Reading reading) {
        if (reading == null) return new Evidence(false, null, false);
        BigDecimal itemTotal = sumItems(reading.items());
        boolean hasEnough = isValidMoney(reading.total()) && itemTotal != null;
        boolean reconciled = false;
        if (hasEnough) {
            BigDecimal gap = itemTotal.subtract(reading.total()).abs();
            reconciled = gap.compareTo(tolerance(reading.total())) <= 0;
        }
        return new Evidence(hasEnough, itemTotal, reconciled);
    }

    private static BigDecimal sumItems(List<Item> items) {
        if (items == null || items.isEmpty()) return null;
        BigDecimal total = ZERO;
        for (Item item : items) {
            if (item == null || item.name() == null || item.name().isBlank() || !isValidMoney(item.lineSum())) {
                return null;
            }
            total = total.add(item.lineSum());
            if (total.compareTo(MAXIMUM_MONEY) > 0) return null;
        }
        return total.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static List<ItemEvidence> itemEvidence(Reading ocr, Reading vision) {
        if (vision == null || vision.items().isEmpty()) return List.of();
        List<ItemEvidence> evidence = new ArrayList<>();
        Set<Integer> usedOcrOrdinals = new HashSet<>();
        for (int visionIndex = 0; visionIndex < vision.items().size(); visionIndex++) {
            Item visionItem = vision.items().get(visionIndex);
            Integer matchingOcrIndex = null;
            BigDecimal bestAmountGap = null;
            if (ocr != null && visionItem != null && visionItem.name() != null) {
                String visionName = normalizeItemName(visionItem.name());
                if (!visionName.isEmpty()) {
                    for (int ocrIndex = 0; ocrIndex < ocr.items().size(); ocrIndex++) {
                        Item ocrItem = ocr.items().get(ocrIndex);
                        if (ocrItem != null && ocrItem.name() != null
                                && visionName.equals(normalizeItemName(ocrItem.name()))
                                && !usedOcrOrdinals.contains(ocrIndex + 1)) {
                            BigDecimal candidateGap = amountGap(visionItem, ocrItem);
                            if (matchingOcrIndex == null || candidateGap != null
                                    && (bestAmountGap == null || candidateGap.compareTo(bestAmountGap) < 0)) {
                                matchingOcrIndex = ocrIndex;
                                bestAmountGap = candidateGap;
                            }
                        }
                    }
                }
            }
            EvidenceStatus status = EvidenceStatus.READER_ONLY;
            if (matchingOcrIndex != null) {
                usedOcrOrdinals.add(matchingOcrIndex + 1);
                BigDecimal visionAmount = visionItem.lineSum();
                BigDecimal ocrAmount = ocr.items().get(matchingOcrIndex).lineSum();
                if (!isValidMoney(visionAmount) || !isValidMoney(ocrAmount)) {
                    status = EvidenceStatus.AMOUNT_UNKNOWN;
                } else if (visionAmount.subtract(ocrAmount).abs().compareTo(new BigDecimal("0.02")) <= 0) {
                    status = EvidenceStatus.CORROBORATED;
                } else {
                    status = EvidenceStatus.AMOUNT_DISAGREES;
                }
            }
            evidence.add(new ItemEvidence(visionIndex + 1,
                    matchingOcrIndex == null ? null : matchingOcrIndex + 1, status));
        }
        return List.copyOf(evidence);
    }

    private static BigDecimal amountGap(Item vision, Item ocr) {
        return isValidMoney(vision.lineSum()) && isValidMoney(ocr.lineSum())
                ? vision.lineSum().subtract(ocr.lineSum()).abs() : null;
    }

    private static String normalizeItemName(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder lettersAndDigits = new StringBuilder(normalized.length());
        normalized.codePoints().filter(Character::isLetterOrDigit).forEach(lettersAndDigits::appendCodePoint);
        return lettersAndDigits.toString();
    }

    private static List<String> mismatches(Reading ocr, Reading vision) {
        if (ocr == null || vision == null) return List.of();
        List<String> mismatches = new ArrayList<>();
        if (bothPresent(ocr.total(), vision.total()) && ocr.total().compareTo(vision.total()) != 0) {
            mismatches.add("total");
        }
        if (bothTextPresent(ocr.merchant(), vision.merchant())
                && !normalizeMerchant(ocr.merchant()).equals(normalizeMerchant(vision.merchant()))) {
            mismatches.add("merchant");
        }
        if (ocr.receiptDate() != null && vision.receiptDate() != null
                && !ocr.receiptDate().equals(vision.receiptDate())) {
            mismatches.add("date");
        }
        return List.copyOf(mismatches);
    }

    private static String normalizeMerchant(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return decomposed.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean bothPresent(BigDecimal left, BigDecimal right) {
        return left != null && right != null;
    }

    private static boolean bothTextPresent(String left, String right) {
        return left != null && !left.isBlank() && right != null && !right.isBlank();
    }

    private static boolean isValidMoney(BigDecimal value) {
        return value != null && value.signum() > 0 && value.scale() <= 2
                && value.compareTo(MAXIMUM_MONEY) <= 0;
    }

    private static BigDecimal tolerance(BigDecimal total) {
        if (!isValidMoney(total)) return MINIMUM_TOLERANCE;
        return total.multiply(RELATIVE_TOLERANCE).max(MINIMUM_TOLERANCE);
    }

    private static BigDecimal largerTolerance(BigDecimal first, BigDecimal second) {
        if (!isValidMoney(first)) return isValidMoney(second) ? tolerance(second) : null;
        if (!isValidMoney(second)) return tolerance(first);
        return tolerance(first).max(tolerance(second));
    }

    private record Evidence(boolean hasEnoughData, BigDecimal itemsTotal, boolean reconciled) {}

    public enum Reader { OCR, VISION }

    public enum Decision { AUTO_SELECTED, REVIEW_REQUIRED, INSUFFICIENT_DATA }

    public enum EvidenceStatus { CORROBORATED, AMOUNT_DISAGREES, AMOUNT_UNKNOWN, READER_ONLY }

    public record Item(String name, BigDecimal lineSum) {}

    public record ItemEvidence(int visionOrdinal, Integer ocrOrdinal, EvidenceStatus status) {
        public ItemEvidence {
            if (visionOrdinal <= 0 || ocrOrdinal != null && ocrOrdinal <= 0 || status == null) {
                throw new IllegalArgumentException("Receipt item evidence is invalid");
            }
        }
    }

    public record TopUpSuggestion(int ocrOrdinal, String name, BigDecimal lineSum) {
        public TopUpSuggestion {
            if (ocrOrdinal <= 0 || name == null || name.isBlank() || !isValidMoney(lineSum)) {
                throw new IllegalArgumentException("Receipt top-up suggestion is invalid");
            }
        }
    }

    public record Reading(Reader reader, BigDecimal total, String merchant, LocalDate receiptDate, List<Item> items) {
        public Reading {
            if (reader == null) throw new IllegalArgumentException("Receipt reader is required");
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record Result(String algorithmVersion, Decision decision, Reader selectedReader, List<String> mismatchFields,
                         BigDecimal ocrItemsTotal, BigDecimal visionItemsTotal, BigDecimal allowedDifference,
                         boolean ocrItemsReconciled, boolean visionItemsReconciled,
                         List<ItemEvidence> itemEvidence, List<TopUpSuggestion> suggestedTopUps) {
        public Result {
            mismatchFields = mismatchFields == null ? List.of() : List.copyOf(mismatchFields);
            itemEvidence = itemEvidence == null ? List.of() : List.copyOf(itemEvidence);
            suggestedTopUps = suggestedTopUps == null ? List.of() : List.copyOf(suggestedTopUps);
        }
    }
}
