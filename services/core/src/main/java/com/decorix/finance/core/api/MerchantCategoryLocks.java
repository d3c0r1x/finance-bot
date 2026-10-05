package com.decorix.finance.core.api;

import java.sql.PreparedStatement;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

final class MerchantCategoryLocks {
    private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";

    private MerchantCategoryLocks() {}

    static String lockKey(UUID tenantId, UUID userId, String normalizedMerchant) {
        return "finance:merchant-reclassification:v1:" + tenantId + ":" + userId + ":" + normalizedMerchant;
    }

    static void acquire(JdbcTemplate jdbc, UUID tenantId, UUID userId, String normalizedMerchant) {
        String key = lockKey(tenantId, userId, normalizedMerchant);
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
                statement.setString(1, key);
                statement.execute();
            }
            return null;
        });
    }
}
