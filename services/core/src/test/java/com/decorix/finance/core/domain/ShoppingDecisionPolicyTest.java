package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ShoppingDecisionPolicyTest {
    @Test
    void boughtMarkSuppressesOnlyUntilOneMedianIntervalAndOnlyAfterLastReceipt() {
        Instant latestReceipt = Instant.parse("2026-09-25T02:00:00Z");
        Instant markedAt = Instant.parse("2026-10-01T23:59:00Z");

        assertTrue(ShoppingDecisionPolicy.boughtMarkIsFresh(markedAt, latestReceipt, 10,
                Instant.parse("2026-10-05T00:01:00Z")));
        assertFalse(ShoppingDecisionPolicy.boughtMarkIsFresh(markedAt, latestReceipt, 10,
                Instant.parse("2026-10-11T00:01:00Z")), "the mark expires at the usual interval");
        assertFalse(ShoppingDecisionPolicy.boughtMarkIsFresh(markedAt,
                Instant.parse("2026-10-02T00:00:00Z"), 10, Instant.parse("2026-10-05T00:01:00Z")),
                "a real receipt after the mark makes it obsolete");
        assertFalse(ShoppingDecisionPolicy.boughtMarkIsFresh(
                Instant.parse("2026-09-24T12:00:00Z"), latestReceipt, 10,
                Instant.parse("2026-10-05T00:01:00Z")), "a mark older than the last receipt is obsolete");
    }
}
