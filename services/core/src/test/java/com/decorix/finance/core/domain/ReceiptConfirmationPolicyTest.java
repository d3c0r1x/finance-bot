package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReceiptConfirmationPolicyTest {
    @Test
    void basketAdviceReviewStateDoesNotBlockBalancedReceiptConfirmation() {
        assertTrue(ReceiptConfirmationPolicy.canConfirm("review_required", amount("35.00"), amount("35.00"),
                "unknown", false));
    }

    @Test
    void confirmationRequiresBalancedAmountsAndResolvedDuplicateCandidates() {
        assertFalse(ReceiptConfirmationPolicy.canConfirm("review_required", amount("35.00"), amount("30.00"),
                "independent", false));
        assertFalse(ReceiptConfirmationPolicy.canConfirm("draft", amount("35.00"), amount("35.00"),
                "duplicate", false));
        assertFalse(ReceiptConfirmationPolicy.canConfirm("draft", amount("35.00"), amount("35.00"),
                "unknown", true));
        assertTrue(ReceiptConfirmationPolicy.canConfirm("draft", amount("35.00"), amount("35.00"),
                "independent", true));
        assertFalse(ReceiptConfirmationPolicy.canConfirm("cancelled", amount("35.00"), amount("35.00"),
                "independent", false));
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
