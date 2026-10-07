package com.decorix.finance.core.api;

import com.decorix.finance.core.api.LegacyGoalHistoryMigrationApi.ImportRequest;
import com.decorix.finance.core.api.LegacyGoalHistoryMigrationApi.ImportResponse;
import com.decorix.finance.core.api.LegacyGoalHistoryMigrationApi.LegacyGoalHistoryEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LegacyGoalHistoryMigrationService {
    private static final int MAX_BATCH_SIZE = 500;
    private static final Pattern LEGACY_KEY = Pattern.compile("goal-history:[a-f0-9]{64}:[0-9]{1,4}");
    private static final Pattern MONEY = Pattern.compile("(?:0|[1-9][0-9]{0,29})\\.[0-9]{2}");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public LegacyGoalHistoryMigrationService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
    }

    public ImportResponse importHistory(ImportRequest request) {
        validate(request);
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                    request.tenantId().toString());
            Integer memberCount = jdbc.queryForObject("SELECT count(*) FROM memberships "
                    + "WHERE tenant_id = ? AND user_id = ?", Integer.class, request.tenantId(), request.ownerUserId());
            if (memberCount == null || memberCount != 1) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal history owner is not mapped in tenant");
            }

            int inserted = 0;
            int existing = 0;
            for (LegacyGoalHistoryEntry entry : request.outcomes()) {
                Map<String, Object> goal = new LinkedHashMap<>();
                goal.put("key", entry.key());
                goal.put("name", entry.name());
                goal.put("scope", entry.scope());
                goal.put("unit", entry.unit());
                goal.put("legacyTarget", entry.legacyTarget());
                goal.put("legacyLimit", entry.legacyLimit());
                goal.put("countTarget", entry.countTarget());
                goal.put("monthlyLimit", entry.monthlyLimit());
                goal.put("legacySaved", entry.saved());
                goal.put("legacyWindow", entry.window());
                Map<String, Object> progress = new LinkedHashMap<>();
                progress.put("bought", entry.bought());
                progress.put("spent", entry.spent());
                progress.put("met", entry.met());
                progress.put("saved", entry.saved());
                progress.put("window", entry.window());
                int changed = jdbc.update("""
                        INSERT INTO goal_outcomes (tenant_id, owner_user_id, legacy_key, origin, goal_snapshot,
                            progress_snapshot, accepted_at, completed_at)
                        VALUES (?, ?, ?, 'legacy', CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                        ON CONFLICT (tenant_id, owner_user_id, legacy_key) WHERE origin = 'legacy' DO NOTHING
                        """, request.tenantId(), request.ownerUserId(), entry.legacyKey(), serialize(goal),
                        serialize(progress), java.sql.Timestamp.from(entry.acceptedAt()),
                        java.sql.Timestamp.from(entry.completedAt()));
                if (changed == 1) inserted++;
                else existing++;
            }
            return new ImportResponse(inserted, existing);
        });
    }

    private static void validate(ImportRequest request) {
        if (request == null || request.tenantId() == null || request.ownerUserId() == null
                || request.outcomes() == null || request.outcomes().size() > MAX_BATCH_SIZE) {
            throw badRequest();
        }
        for (LegacyGoalHistoryEntry entry : request.outcomes()) {
            if (entry == null || entry.legacyKey() == null || !LEGACY_KEY.matcher(entry.legacyKey()).matches()
                    || blankOrTooLong(entry.key(), 256) || blankOrTooLong(entry.name(), 200)
                    || !("product".equals(entry.scope()) || "group".equals(entry.scope()))
                    || !("count".equals(entry.unit()) || "sum".equals(entry.unit()))
                    || entry.legacyTarget() == null || entry.legacyTarget() < 0
                    || !validMoneyOrNull(entry.legacyLimit())
                    || entry.countTarget() == null || entry.countTarget() < 0
                    || "count".equals(entry.unit()) && entry.countTarget() < 1
                    || "sum".equals(entry.unit()) && entry.countTarget() != 0
                    || entry.monthlyLimit() != null && !MONEY.matcher(entry.monthlyLimit()).matches()
                    || "sum".equals(entry.unit()) && entry.monthlyLimit() == null
                    || "count".equals(entry.unit()) && entry.monthlyLimit() != null
                    || entry.bought() == null || entry.bought() < 0
                    || !validMoneyOrNull(entry.spent()) || !validMoneyOrNull(entry.saved())
                    || blankOrTooLong(entry.window(), 128) || entry.acceptedAt() == null || entry.completedAt() == null
                    || entry.completedAt().isBefore(entry.acceptedAt())) {
                throw badRequest();
            }
        }
    }

    private static boolean validMoneyOrNull(String value) {
        return value == null || MONEY.matcher(value).matches();
    }

    private static boolean blankOrTooLong(String value, int maxLength) {
        return value == null || value.isBlank() || value.length() > maxLength;
    }

    private String serialize(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize legacy goal outcome", exception);
        }
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid legacy goal history batch");
    }
}
