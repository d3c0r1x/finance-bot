package com.decorix.finance.core.api;

import com.decorix.finance.core.api.NotificationPreferencesApi.PreferencesResponse;
import com.decorix.finance.core.api.NotificationPreferencesApi.UpdateRequest;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationPreferencesService {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public NotificationPreferencesService(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    public PreferencesResponse get(UUID tenantId, String subject) {
        return transaction.execute(status -> {
            Member member = member(tenantId, subject);
            ZoneId zone = zone(member.timezone());
            ensureDefaults(tenantId, member.userId(), zone);
            return read(tenantId, member.userId(), member.timezone(), member.telegramLinked());
        });
    }

    public PreferencesResponse update(UUID tenantId, String subject, long expectedVersion, UpdateRequest request) {
        Validated validated = validate(request);
        return transaction.execute(status -> {
            Member member = member(tenantId, subject);
            ZoneId zone = zone(member.timezone());
            ensureDefaults(tenantId, member.userId(), zone);
            int changed = jdbc.update("""
                    UPDATE notification_preferences
                    SET language = ?, daily_enabled = ?, daily_local_time = ?,
                        weekly_enabled = ?, weekly_day_of_week = ?, weekly_local_time = ?,
                        quiet_hours_start = ?, quiet_hours_end = ?,
                        next_daily_at = ?, next_weekly_at = ?, version = version + 1, updated_at = now()
                    WHERE tenant_id = ? AND user_id = ? AND version = ?
                    """, validated.language(), validated.dailyEnabled(), validated.dailyTime(),
                    validated.weeklyEnabled(), validated.weeklyDay(), validated.weeklyTime(),
                    validated.quietStart(), validated.quietEnd(),
                    validated.dailyEnabled() ? nextDaily(validated.dailyTime(), zone) : null,
                    validated.weeklyEnabled() ? nextWeekly(validated.weeklyDay(), validated.weeklyTime(), zone) : null,
                    tenantId, member.userId(), expectedVersion);
            if (changed == 0) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Notification preferences version changed");
            }
            return read(tenantId, member.userId(), member.timezone(), member.telegramLinked());
        });
    }

    private Member member(UUID tenantId, String subject) {
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification preference request");
        }
        jdbc.queryForObject("SELECT set_config('app.subject', ?, true)", String.class, subject);
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        List<UUID> userIds = jdbc.query("SELECT user_id FROM memberships WHERE tenant_id = ? AND subject = ? AND status = 'active'",
                (rs, row) -> rs.getObject("user_id", UUID.class), tenantId, subject);
        if (userIds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
        List<String> timezones = jdbc.query("SELECT timezone FROM member_profiles WHERE tenant_id = ? AND user_id = ?",
                (rs, row) -> rs.getString("timezone"), tenantId, userIds.get(0));
        if (timezones.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member profile not found");
        boolean telegramLinked = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM external_identities WHERE user_id = ? AND provider = 'telegram')",
                Boolean.class, userIds.get(0)));
        return new Member(userIds.get(0), timezones.get(0), telegramLinked);
    }

    private void ensureDefaults(UUID tenantId, UUID userId, ZoneId zone) {
        jdbc.update("""
                INSERT INTO notification_preferences (tenant_id, user_id, next_daily_at, next_weekly_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id) DO NOTHING
                """, tenantId, userId, nextDaily(LocalTime.of(21, 0), zone), nextWeekly(7, LocalTime.of(19, 0), zone));
    }

    private PreferencesResponse read(UUID tenantId, UUID userId, String timezone, boolean telegramLinked) {
        return jdbc.queryForObject("""
                SELECT language, daily_enabled, daily_local_time, weekly_enabled, weekly_day_of_week,
                       weekly_local_time, quiet_hours_start, quiet_hours_end, version
                FROM notification_preferences WHERE tenant_id = ? AND user_id = ?
                """, (rs, row) -> new PreferencesResponse(timezone, telegramLinked,
                rs.getString("language"), rs.getBoolean("daily_enabled"), format(rs.getObject("daily_local_time", LocalTime.class)),
                rs.getBoolean("weekly_enabled"), rs.getInt("weekly_day_of_week"),
                format(rs.getObject("weekly_local_time", LocalTime.class)),
                formatNullable(rs.getObject("quiet_hours_start", LocalTime.class)),
                formatNullable(rs.getObject("quiet_hours_end", LocalTime.class)), rs.getLong("version")), tenantId, userId);
    }

    private static Validated validate(UpdateRequest request) {
        if (request == null || request.language() == null || !List.of("ru", "en").contains(request.language())
                || request.dailyEnabled() == null || request.weeklyEnabled() == null
                || request.weeklyDayOfWeek() == null || request.weeklyDayOfWeek() < 1 || request.weeklyDayOfWeek() > 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification preferences");
        }
        LocalTime dailyTime = parse(request.dailyLocalTime());
        LocalTime weeklyTime = parse(request.weeklyLocalTime());
        LocalTime quietStart = request.quietHoursStart() == null ? null : parse(request.quietHoursStart());
        LocalTime quietEnd = request.quietHoursEnd() == null ? null : parse(request.quietHoursEnd());
        if ((quietStart == null) != (quietEnd == null) || quietStart != null && quietStart.equals(quietEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quiet hours must be a non-empty time range");
        }
        return new Validated(request.language(), request.dailyEnabled(), dailyTime,
                request.weeklyEnabled(), request.weeklyDayOfWeek(), weeklyTime, quietStart, quietEnd);
    }

    private static LocalTime parse(String value) {
        if (value == null || !value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Times must use HH:mm format");
        }
        try {
            return LocalTime.parse(value, TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Times must use HH:mm format", exception);
        }
    }

    private static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Member timezone is invalid", exception);
        }
    }

    private static java.time.OffsetDateTime nextDaily(LocalTime time, ZoneId zone) {
        return NotificationSchedule.nextDaily(Instant.now(), time, zone).atOffset(ZoneOffset.UTC);
    }

    private static java.time.OffsetDateTime nextWeekly(int dayOfWeek, LocalTime time, ZoneId zone) {
        return NotificationSchedule.nextWeekly(Instant.now(), dayOfWeek, time, zone).atOffset(ZoneOffset.UTC);
    }

    private static String format(LocalTime value) { return value.format(TIME_FORMAT); }
    private static String formatNullable(LocalTime value) { return value == null ? null : format(value); }

    private record Member(UUID userId, String timezone, boolean telegramLinked) {}
    private record Validated(String language, boolean dailyEnabled, LocalTime dailyTime,
            boolean weeklyEnabled, int weeklyDay, LocalTime weeklyTime, LocalTime quietStart, LocalTime quietEnd) {}
}
