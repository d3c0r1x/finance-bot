package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdviceEvidencePolicyTest {
    @Test
    void modelOnlyGroupRemainsGuessUntilConfirmedAndAllowedOverridesEvidence() {
        var groups = List.of(
                group("coffee", 2, 0, 2, 0, true),
                group("chips", 3, 1, 1, 1, false),
                group("tea", 3, 0, 2, 1, false));

        var initial = AdviceEvidencePolicy.apply(groups, Map.of());
        assertEquals(List.of("chips"), initial.banned().stream().map(AdviceEvidenceApi.EvidenceGroup::productKey).toList());
        assertEquals(List.of("coffee", "tea"), initial.guesses().stream()
                .map(AdviceEvidenceApi.EvidenceGroup::productKey).toList());
        assertFalse(initial.banned().stream().anyMatch(AdviceEvidenceApi.EvidenceGroup::modelOnly));

        var confirmed = AdviceEvidencePolicy.apply(groups, Map.of("coffee", "confirmed"));
        assertEquals(List.of("coffee", "chips"), confirmed.banned().stream()
                .map(AdviceEvidenceApi.EvidenceGroup::productKey).toList());
        assertEquals(List.of("tea"), confirmed.guesses().stream()
                .map(AdviceEvidenceApi.EvidenceGroup::productKey).toList());

        var allowed = AdviceEvidencePolicy.apply(groups, Map.of("coffee", "allowed", "chips", "allowed", "tea", "allowed"));
        assertTrue(allowed.banned().isEmpty());
        assertTrue(allowed.guesses().isEmpty());
    }

    private static AdviceEvidenceApi.EvidenceGroup group(String key, int count, int rules, int models,
                                                          int unmarked, boolean modelOnly) {
        return new AdviceEvidenceApi.EvidenceGroup(key, key, count, "12.00", 0, rules, models,
                unmarked, modelOnly, "harmful", "", Instant.parse("2026-10-01T10:00:00Z"));
    }
}
