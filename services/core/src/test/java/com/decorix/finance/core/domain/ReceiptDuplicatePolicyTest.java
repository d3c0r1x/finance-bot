package com.decorix.finance.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReceiptDuplicatePolicyTest {
    @Test
    void candidatesRequireSameAmountAndStrictlyLessThanTenMinutes() {
        Instant created = Instant.parse("2026-10-01T10:10:00Z");
        UUID currentId = UUID.randomUUID();
        UUID exactAmount = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID wrongAmount = UUID.randomUUID();
        var result = ReceiptDuplicatePolicy.candidates(currentId, created, "100.00", List.of(
                new ReceiptDuplicatePolicy.Candidate(currentId, created, "100.00", "self"),
                new ReceiptDuplicatePolicy.Candidate(exactAmount, created.minusSeconds(599), "100.00", "same"),
                new ReceiptDuplicatePolicy.Candidate(old, created.minusSeconds(600), "100.00", "boundary"),
                new ReceiptDuplicatePolicy.Candidate(wrongAmount, created.minusSeconds(10), "100.01", "different")));

        assertEquals(List.of(exactAmount), result.stream().map(ReceiptDuplicatePolicy.Candidate::id).toList());
    }
}
