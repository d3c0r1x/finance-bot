package com.decorix.finance.core.api;

import com.decorix.finance.core.api.NotificationDeliveryApi.DeliveryClaim;
import com.decorix.finance.core.api.NotificationDeliveryApi.DeliveryRequest;
import com.decorix.finance.core.api.NotificationDeliveryApi.DeliveryResponse;
import com.decorix.finance.core.api.NotificationDeliveryApi.GoalOutcomeMessage;
import com.decorix.finance.core.api.ReportApi.Report;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationDeliveryService {
    private static final int MAX_ATTEMPTS = 8;
    private static final int MAX_BATCH = 20;
    private static final long LEASE_SECONDS = 120;
    private static final long RETRY_BASE_SECONDS = 30;
    private static final long RETRY_MAX_SECONDS = 6 * 60 * 60;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ReportService reports;

    public NotificationDeliveryService(JdbcTemplate jdbc, TransactionTemplate transaction, ReportService reports) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.reports = reports;
    }

    public List<DeliveryClaim> claim(Integer requestedLimit) {
        int limit = requestedLimit == null ? 10 : requestedLimit;
        if (limit < 1 || limit > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Claim limit must be between 1 and 20");
        }
        List<ClaimRow> leased = transaction.execute(status -> {
            enableServiceContext();
            provisionLinkedMembers();
            materializeDueSchedules();
            expireAbandonedLeases();
            List<ClaimRow> rows = jdbc.query("""
                    WITH candidates AS (
                        SELECT intent.id
                        FROM notification_intents intent
                        JOIN external_identities telegram ON telegram.user_id = intent.user_id
                          AND telegram.provider = 'telegram'
                        JOIN users ON users.id = intent.user_id AND users.status = 'active'
                        WHERE intent.attempt_count < ?
                          AND ((intent.state = 'pending' AND intent.available_at <= now())
                            OR (intent.state = 'leased' AND intent.lease_until <= now()))
                        ORDER BY intent.available_at, intent.created_at, intent.id
                        FOR UPDATE OF intent SKIP LOCKED
                        LIMIT ?
                    )
                    UPDATE notification_intents intent
                    SET state = 'leased', attempt_count = intent.attempt_count + 1,
                        lease_token = gen_random_uuid(), lease_until = now() + (? * interval '1 second'),
                        updated_at = now()
                    FROM candidates, external_identities telegram
                    WHERE intent.id = candidates.id AND telegram.user_id = intent.user_id
                      AND telegram.provider = 'telegram'
                    RETURNING intent.id, intent.tenant_id, intent.user_id, intent.digest_kind,
                              intent.scheduled_local_date, intent.language, intent.report_from_date,
                              intent.report_to_date, intent.timezone, intent.attempt_count,
                              intent.lease_token, telegram.subject::bigint AS telegram_user_id
                    """, (rs, row) -> new ClaimRow(rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class),
                    rs.getString("digest_kind"), rs.getObject("scheduled_local_date", LocalDate.class),
                    rs.getString("language"), rs.getObject("report_from_date", LocalDate.class),
                    rs.getObject("report_to_date", LocalDate.class), rs.getString("timezone"),
                    rs.getInt("attempt_count"), rs.getObject("lease_token", UUID.class),
                    rs.getLong("telegram_user_id"), null), MAX_ATTEMPTS, limit, LEASE_SECONDS);
            for (ClaimRow row : rows) {
                jdbc.update("""
                        INSERT INTO notification_delivery_attempts (intent_id, attempt_number)
                        VALUES (?, ?)
                        """, row.intentId(), row.attemptNumber());
            }
            List<ClaimRow> claims = new ArrayList<>(rows.size());
            for (ClaimRow row : rows) {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                        row.tenantId().toString());
                GoalOutcomeMessage outcome = attachWeeklyOutcome(row);
                claims.add(new ClaimRow(row.intentId(), row.tenantId(), row.userId(), row.digestKind(),
                        row.scheduledLocalDate(), row.language(), row.reportFromDate(), row.reportToDate(),
                        row.timezone(), row.attemptNumber(), row.leaseToken(), row.telegramUserId(), outcome));
            }
            return List.copyOf(claims);
        });

        List<DeliveryClaim> result = new ArrayList<>(leased.size());
        for (ClaimRow row : leased) {
            try {
                String subject = jdbc.queryForObject("SELECT subject FROM external_identities "
                        + "WHERE provider = 'keycloak' AND user_id = ?", String.class, row.userId());
                Report report = reports.get(row.tenantId(), subject, "custom", null,
                        row.reportFromDate(), row.reportToDate(), false);
                result.add(new DeliveryClaim(row.intentId(), row.telegramUserId(), row.digestKind(),
                        row.scheduledLocalDate(), row.language(), row.attemptNumber(), row.leaseToken(), report,
                        row.goalOutcome()));
            } catch (RuntimeException unavailable) {
                acknowledge(row.intentId(), new DeliveryRequest(row.leaseToken(), "retryable_failure",
                        "report_unavailable", null));
            }
        }
        return List.copyOf(result);
    }

    private GoalOutcomeMessage attachWeeklyOutcome(ClaimRow row) {
        if (!"weekly".equals(row.digestKind())) return null;
        List<GoalOutcomeMessage> attached = jdbc.query("""
                SELECT outcome.id, outcome.goal_snapshot->>'name' AS name,
                       outcome.goal_snapshot->>'unit' AS unit,
                       (outcome.progress_snapshot->>'bought')::integer AS bought,
                       (outcome.goal_snapshot->>'countTarget')::integer AS count_target,
                       outcome.progress_snapshot->>'spent' AS spent,
                       outcome.goal_snapshot->>'monthlyLimit' AS monthly_limit,
                       (outcome.progress_snapshot->>'met')::boolean AS met,
                       outcome.completed_at
                FROM goal_outcomes outcome
                WHERE outcome.tenant_id = ? AND outcome.owner_user_id = ?
                  AND outcome.origin = 'completed' AND outcome.announcement_intent_id = ?
                """, (rs, index) -> new GoalOutcomeMessage(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("unit"), rs.getInt("bought"), rs.getInt("count_target"), rs.getString("spent"),
                rs.getString("monthly_limit"), rs.getObject("met", Boolean.class),
                instant(rs.getTimestamp("completed_at"))), row.tenantId(), row.userId(), row.intentId());
        if (!attached.isEmpty()) return attached.get(0);

        return jdbc.query("""
                UPDATE goal_outcomes outcome
                SET announcement_intent_id = ?
                WHERE outcome.id = (
                    SELECT pending.id
                    FROM goal_outcomes pending
                    WHERE pending.tenant_id = ? AND pending.owner_user_id = ?
                      AND pending.origin = 'completed' AND pending.announced_at IS NULL
                      AND pending.announcement_intent_id IS NULL
                    ORDER BY pending.completed_at, pending.id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING outcome.id, outcome.goal_snapshot->>'name' AS name,
                          outcome.goal_snapshot->>'unit' AS unit,
                          (outcome.progress_snapshot->>'bought')::integer AS bought,
                          (outcome.goal_snapshot->>'countTarget')::integer AS count_target,
                          outcome.progress_snapshot->>'spent' AS spent,
                          outcome.goal_snapshot->>'monthlyLimit' AS monthly_limit,
                          (outcome.progress_snapshot->>'met')::boolean AS met,
                          outcome.completed_at
                """, (rs, index) -> new GoalOutcomeMessage(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("unit"), rs.getInt("bought"), rs.getInt("count_target"), rs.getString("spent"),
                rs.getString("monthly_limit"), rs.getObject("met", Boolean.class),
                instant(rs.getTimestamp("completed_at"))), row.intentId(), row.tenantId(), row.userId())
                .stream().findFirst().orElse(null);
    }

    public DeliveryResponse acknowledge(UUID intentId, DeliveryRequest request) {
        if (intentId == null || request == null || request.leaseToken() == null
                || request.outcome() == null
                || !List.of("delivered", "no_data", "retryable_failure", "permanent_failure").contains(request.outcome())
                || request.errorCode() != null && !request.errorCode().matches("[a-z0-9_.-]{1,64}")
                || request.providerMessageId() != null && request.providerMessageId().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid delivery result");
        }
        return transaction.execute(status -> {
            enableServiceContext();
            List<AcknowledgementRow> attempts = jdbc.query("""
                    SELECT attempt_count, tenant_id FROM notification_intents
                    WHERE id = ? AND state = 'leased' AND lease_token = ? AND lease_until > now()
                    FOR UPDATE
                    """, (rs, row) -> new AcknowledgementRow(rs.getInt("attempt_count"),
                    rs.getObject("tenant_id", UUID.class)), intentId, request.leaseToken());
            if (attempts.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivery lease expired");
            AcknowledgementRow acknowledgement = attempts.get(0);
            int attempt = acknowledgement.attemptCount();
            String nextState;
            Instant retryAt = null;
            String attemptOutcome = request.outcome();
            switch (request.outcome()) {
                case "delivered" -> nextState = "delivered";
                case "no_data" -> nextState = "skipped_no_data";
                case "permanent_failure" -> nextState = "failed";
                case "retryable_failure" -> {
                    if (attempt >= MAX_ATTEMPTS) nextState = "failed";
                    else {
                        nextState = "pending";
                        long seconds = Math.min(RETRY_MAX_SECONDS,
                                RETRY_BASE_SECONDS * (1L << Math.min(attempt - 1, 20)));
                        retryAt = Instant.now().plus(seconds, ChronoUnit.SECONDS);
                    }
                }
                default -> throw new IllegalStateException("Validated notification result was not handled");
            }
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                    acknowledgement.tenantId().toString());
            jdbc.update("""
                    UPDATE notification_delivery_attempts
                    SET outcome = ?, completed_at = now(), error_code = ?, provider_message_id = ?
                    WHERE intent_id = ? AND attempt_number = ? AND outcome = 'started'
                    """, attemptOutcome, request.errorCode(), request.providerMessageId(), intentId, attempt);
            jdbc.update("""
                    UPDATE notification_intents
                    SET state = ?, available_at = COALESCE(?, available_at),
                        lease_token = NULL, lease_until = NULL,
                        last_error_code = ?, provider_message_id = ?,
                        delivered_at = CASE WHEN ? = 'delivered' THEN now() ELSE NULL END,
                        updated_at = now()
                    WHERE id = ? AND lease_token = ?
                    """, nextState, retryAt == null ? null : Timestamp.from(retryAt), request.errorCode(),
                    request.providerMessageId(), request.outcome(), intentId, request.leaseToken());
            if ("delivered".equals(request.outcome())) {
                jdbc.update("""
                        UPDATE goal_outcomes
                        SET announced_at = COALESCE(announced_at, now()), announcement_intent_id = NULL
                        WHERE tenant_id = ? AND announcement_intent_id = ?
                        """, acknowledgement.tenantId(), intentId);
            } else if (!"pending".equals(nextState)) {
                jdbc.update("""
                        UPDATE goal_outcomes
                        SET announcement_intent_id = NULL
                        WHERE tenant_id = ? AND announcement_intent_id = ? AND announced_at IS NULL
                        """, acknowledgement.tenantId(), intentId);
            }
            return new DeliveryResponse(nextState);
        });
    }

    private void provisionLinkedMembers() {
        List<LinkedMember> linked = jdbc.query("""
                SELECT membership.tenant_id, membership.user_id, profile.timezone
                FROM memberships membership
                JOIN member_profiles profile ON profile.tenant_id = membership.tenant_id
                  AND profile.user_id = membership.user_id
                JOIN external_identities telegram ON telegram.user_id = membership.user_id
                  AND telegram.provider = 'telegram'
                JOIN users ON users.id = membership.user_id AND users.status = 'active'
                WHERE membership.status = 'active'
                  AND NOT EXISTS (SELECT 1 FROM notification_preferences preferences
                    WHERE preferences.tenant_id = membership.tenant_id AND preferences.user_id = membership.user_id)
                ORDER BY membership.tenant_id, membership.user_id
                LIMIT 1000
                """, (rs, row) -> new LinkedMember(rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("timezone")));
        Instant now = Instant.now();
        for (LinkedMember member : linked) {
            ZoneId zone = zone(member.timezone());
            jdbc.update("""
                    INSERT INTO notification_preferences (tenant_id, user_id, next_daily_at, next_weekly_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (tenant_id, user_id) DO NOTHING
                    """, member.tenantId(), member.userId(),
                    NotificationSchedule.nextDaily(now, LocalTime.of(21, 0), zone).atOffset(ZoneOffset.UTC),
                    NotificationSchedule.nextWeekly(now, 7, LocalTime.of(19, 0), zone).atOffset(ZoneOffset.UTC));
        }
    }

    private void materializeDueSchedules() {
        List<PreferenceRow> due = jdbc.query("""
                SELECT preferences.tenant_id, preferences.user_id, preferences.language,
                       preferences.daily_enabled, preferences.daily_local_time,
                       preferences.weekly_enabled, preferences.weekly_day_of_week,
                       preferences.weekly_local_time, preferences.quiet_hours_start,
                       preferences.quiet_hours_end, preferences.next_daily_at,
                       preferences.next_weekly_at, profile.timezone
                FROM notification_preferences preferences
                JOIN member_profiles profile ON profile.tenant_id = preferences.tenant_id
                  AND profile.user_id = preferences.user_id
                WHERE (preferences.daily_enabled AND preferences.next_daily_at <= now())
                   OR (preferences.weekly_enabled AND preferences.next_weekly_at <= now())
                ORDER BY LEAST(COALESCE(preferences.next_daily_at, 'infinity'::timestamptz),
                               COALESCE(preferences.next_weekly_at, 'infinity'::timestamptz)),
                         preferences.tenant_id, preferences.user_id
                FOR UPDATE OF preferences SKIP LOCKED
                LIMIT 250
                """, (rs, row) -> new PreferenceRow(rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("language"), rs.getBoolean("daily_enabled"),
                rs.getObject("daily_local_time", LocalTime.class), rs.getBoolean("weekly_enabled"),
                rs.getInt("weekly_day_of_week"), rs.getObject("weekly_local_time", LocalTime.class),
                rs.getObject("quiet_hours_start", LocalTime.class), rs.getObject("quiet_hours_end", LocalTime.class),
                instant(rs.getTimestamp("next_daily_at")), instant(rs.getTimestamp("next_weekly_at")),
                rs.getString("timezone")));
        Instant now = Instant.now();
        for (PreferenceRow preference : due) {
            ZoneId zone = zone(preference.timezone());
            if (preference.dailyEnabled() && preference.nextDailyAt() != null && !preference.nextDailyAt().isAfter(now)) {
                Instant occurrence = NotificationSchedule.latestDailyOccurrence(now, preference.dailyTime(), zone);
                if (occurrence.isBefore(preference.nextDailyAt())) occurrence = preference.nextDailyAt();
                createIntent(preference, "daily", occurrence, zone);
                jdbc.update("UPDATE notification_preferences SET next_daily_at = ?, updated_at = now() "
                                + "WHERE tenant_id = ? AND user_id = ?",
                        NotificationSchedule.nextDaily(now, preference.dailyTime(), zone).atOffset(ZoneOffset.UTC),
                        preference.tenantId(), preference.userId());
            }
            if (preference.weeklyEnabled() && preference.nextWeeklyAt() != null && !preference.nextWeeklyAt().isAfter(now)) {
                Instant occurrence = NotificationSchedule.latestWeeklyOccurrence(now,
                        preference.weeklyDay(), preference.weeklyTime(), zone);
                if (occurrence.isBefore(preference.nextWeeklyAt())) occurrence = preference.nextWeeklyAt();
                createIntent(preference, "weekly", occurrence, zone);
                jdbc.update("UPDATE notification_preferences SET next_weekly_at = ?, updated_at = now() "
                                + "WHERE tenant_id = ? AND user_id = ?",
                        NotificationSchedule.nextWeekly(now, preference.weeklyDay(), preference.weeklyTime(), zone)
                                .atOffset(ZoneOffset.UTC), preference.tenantId(), preference.userId());
            }
        }
    }

    private void createIntent(PreferenceRow preference, String kind, Instant scheduledAt, ZoneId zone) {
        LocalDate localDate = scheduledAt.atZone(zone).toLocalDate();
        LocalDate fromDate = "daily".equals(kind) ? localDate : localDate.minusDays(6);
        Instant availableAt = NotificationSchedule.availableAfterQuietHours(scheduledAt, zone,
                preference.quietStart(), preference.quietEnd());
        jdbc.update("""
                INSERT INTO notification_intents (tenant_id, user_id, digest_kind, scheduled_local_date,
                  scheduled_at, timezone, language, report_from_date, report_to_date, available_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, digest_kind, scheduled_local_date) DO NOTHING
                """, preference.tenantId(), preference.userId(), kind, localDate,
                Timestamp.from(scheduledAt), zone.getId(), preference.language(), fromDate, localDate,
                Timestamp.from(availableAt));
    }

    private void expireAbandonedLeases() {
        jdbc.update("""
                UPDATE notification_delivery_attempts attempt
                SET outcome = 'retryable_failure', error_code = 'lease_expired', completed_at = now()
                FROM notification_intents intent
                WHERE intent.id = attempt.intent_id AND intent.state = 'leased'
                  AND intent.lease_until <= now() AND attempt.attempt_number = intent.attempt_count
                  AND attempt.outcome = 'started'
                """);
        List<ExpiredIntent> exhausted = jdbc.query("""
                UPDATE notification_intents SET state = 'failed', lease_token = NULL,
                  lease_until = NULL, last_error_code = 'attempts_exhausted', updated_at = now()
                WHERE state = 'leased' AND lease_until <= now() AND attempt_count >= ?
                RETURNING id, tenant_id
                """, (rs, row) -> new ExpiredIntent(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class)), MAX_ATTEMPTS);
        for (ExpiredIntent intent : exhausted) {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                    intent.tenantId().toString());
            jdbc.update("""
                    UPDATE goal_outcomes SET announcement_intent_id = NULL
                    WHERE tenant_id = ? AND announcement_intent_id = ? AND announced_at IS NULL
                    """, intent.tenantId(), intent.intentId());
        }
    }

    private void enableServiceContext() {
        jdbc.queryForObject("SELECT set_config('app.notification_service', 'true', true)", String.class);
        jdbc.queryForObject("SELECT set_config('app.telegram_actor_service', 'true', true)", String.class);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Member timezone is invalid", exception);
        }
    }

    private record LinkedMember(UUID tenantId, UUID userId, String timezone) {}
    private record PreferenceRow(UUID tenantId, UUID userId, String language, boolean dailyEnabled,
            LocalTime dailyTime, boolean weeklyEnabled, int weeklyDay, LocalTime weeklyTime,
            LocalTime quietStart, LocalTime quietEnd, Instant nextDailyAt, Instant nextWeeklyAt, String timezone) {}
    private record ClaimRow(UUID intentId, UUID tenantId, UUID userId, String digestKind,
            LocalDate scheduledLocalDate, String language, LocalDate reportFromDate, LocalDate reportToDate,
            String timezone, int attemptNumber, UUID leaseToken, long telegramUserId,
            GoalOutcomeMessage goalOutcome) {}
    private record AcknowledgementRow(int attemptCount, UUID tenantId) {}
    private record ExpiredIntent(UUID intentId, UUID tenantId) {}
}
