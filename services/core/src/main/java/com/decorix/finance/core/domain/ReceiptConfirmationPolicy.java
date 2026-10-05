package com.decorix.finance.core.domain;

import java.math.BigDecimal;

/** Checks receipt evidence and duplicate choices before posting its one expense transaction. */
public final class ReceiptConfirmationPolicy {
    public static final String ALGORITHM_VERSION = "receipt-confirmation.v1";

    private ReceiptConfirmationPolicy() {}

    public static boolean canConfirm(String state, BigDecimal cashTotal, BigDecimal itemsTotal,
                                     String duplicateDecision, boolean hasDuplicateCandidates) {
        if (!"draft".equals(state) && !"review_required".equals(state)) return false;
        if (!ReceiptReconciliationPolicy.totalsReconcile(cashTotal, itemsTotal)) return false;
        if ("duplicate".equals(duplicateDecision)) return false;
        return "independent".equals(duplicateDecision)
                || "unknown".equals(duplicateDecision) && !hasDuplicateCandidates;
    }
}
