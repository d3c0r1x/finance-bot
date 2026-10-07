package com.decorix.finance.core.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LegacyGoalHistoryMigrationApi {
    private LegacyGoalHistoryMigrationApi() {}

    public record ImportRequest(UUID tenantId, UUID ownerUserId, List<LegacyGoalHistoryEntry> outcomes) {}
    public record LegacyGoalHistoryEntry(String legacyKey, String key, String name, String scope, String unit,
            Integer legacyTarget, String legacyLimit, Integer countTarget, String monthlyLimit, Integer bought,
            String spent, Boolean met, String saved, String window, Instant acceptedAt, Instant completedAt) {}
    public record ImportResponse(int inserted, int alreadyPresent) {}
}
